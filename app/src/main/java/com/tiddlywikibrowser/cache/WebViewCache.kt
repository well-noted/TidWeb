package com.tiddlywikibrowser

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlinx.coroutines.*
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.read
import kotlin.concurrent.write

object WebViewCache {
    private val webViewCache = ConcurrentHashMap<String, WebView>()
    private val webViewStates = ConcurrentHashMap<String, Bundle>()
    private val webViewLoadedState = ConcurrentHashMap<String, Boolean>()
    private val lastAccessTime = LinkedHashMap<String, Long>()  // Track LRU
    private var tempWebView: WebView? = null
    private var isConfigurationChanging = false
    private var currentActiveKey: String? = null
    private const val MAX_CACHE_SIZE = 5  // Limit total cached WebViews
    private const val TAG = "WebViewCache"
    
    // Improve locking mechanism with separate locks for different operations
    private val cacheLock = ReentrantReadWriteLock()
    private val stateLock = ReentrantReadWriteLock() 
    private val accessTimeLock = ReentrantReadWriteLock()
    
    // Coroutine scope for background and UI operations
    private val cacheScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Operation queue with priority instead of simple flag
    private data class WebViewOperation(
        val key: String?,
        val priority: Int,
        val operation: suspend () -> Unit
    )
    
    // Operation comparator for priority queue (higher priority first)
    private val operationComparator = Comparator<WebViewOperation> { op1, op2 ->
        op2.priority - op1.priority
    }
    
    private val operationQueue = PriorityQueue<WebViewOperation>(11, operationComparator)
    private val isProcessingQueue = AtomicBoolean(false)
    
    /**
     * Public method to check if configuration is currently changing
     * This allows other components to safely check configuration state
     */
    fun isInConfigChange(): Boolean {
        return isConfigurationChanging
    }

    fun setConfigurationChanging(changing: Boolean) {
        isConfigurationChanging = changing
    }
    
    fun setCurrentActiveKey(key: String?) {
        cacheLock.read {
            Log.d(TAG, "Setting current active key to: $key")
            currentActiveKey = key
        }
    }
    
    fun getCurrentActiveKey(): String? {
        return cacheLock.read { currentActiveKey }
    }

    fun clearCache(context: Context) {
        if (!isConfigurationChanging) {
            enqueueOperation("clear_cache", 10) {
                withContext(Dispatchers.Main) {
                    try {
                        tempWebView?.destroy()
                        tempWebView = WebView(context.applicationContext)
                        tempWebView?.clearCache(true)
                        tempWebView?.destroy()
                        tempWebView = null
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun monitorWebViewStateSize(key: String, bundle: Bundle) {
        enqueueOperation(key, 3) {
            try {
                val parcel = android.os.Parcel.obtain()
                bundle.writeToParcel(parcel, 0)
                val sizeBytes = parcel.dataSize()
                parcel.recycle()
                
                if (sizeBytes > 500 * 1024) { // 500KB
                    Log.e(TAG, "WebView state for $key is too large: ${sizeBytes/1024}KB!")
                    
                    // Remove problematic state to prevent crashes
                    stateLock.write {
                        webViewStates.remove(key)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error measuring bundle size: ${e.message}")
            }
        }
    }

    fun checkForLeakedWebViews() {
        enqueueOperation("leak_check", 1) {
            val leakedKeys = mutableListOf<String>()
            
            cacheLock.read {
                webViewCache.forEach { (key, webView) ->
                    if (key != currentActiveKey && webView.parent != null) {
                        // This is a potential leak - the WebView is attached but not active
                        Log.w(TAG, "Potential WebView leak detected for key: $key")
                        leakedKeys.add(key)
                    }
                }
            }
            
            // Fix leaks on main thread
            if (leakedKeys.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    leakedKeys.forEach { key ->
                        cacheLock.read {
                            webViewCache[key]?.let { webView ->
                                try {
                                    (webView.parent as? ViewGroup)?.removeView(webView)
                                    Log.d(TAG, "Fixed leaked WebView for key: $key")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error fixing WebView leak: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Cache a WebView with its state for future restoration
     */
    fun cacheWebView(key: String, webView: WebView) {
        enqueueOperation(key, 5) {
            withContext(Dispatchers.Main) {
                try {
                    // Save complete WebView state including scroll position
                    val bundle = Bundle()
                    webView.saveState(bundle)
                    
                    // Track loaded state and other metadata
                    val isLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
                    
                    // Break up operations that hold the lock
                    stateLock.write {
                        webViewLoadedState[key] = isLoaded
                        webViewStates[key] = bundle
                    }
                    
                    updateAccessTime(key)
                    
                    // Before caching, preserve active media state
                    webView.evaluateJavascript("""
                        (function() {
                            const media = document.querySelector('audio,video');
                            if (media) {
                                return JSON.stringify({
                                    hasMedia: true,
                                    currentTime: media.currentTime,
                                    isPlaying: !media.paused,
                                    duration: media.duration,
                                    volume: media.volume,
                                    src: media.src
                                });
                            }
                            return '{"hasMedia":false}';
                        })();
                    """.trimIndent()) { result ->
                        try {
                            bundle.putString("media_state", result.trim('"'))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving media state: ${e.message}")
                        }
                    }
                    
                    // Store in cache if not already there
                    cacheLock.write {
                        if (!webViewCache.containsKey(key)) {
                            webViewCache[key] = webView
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error caching WebView: ${e.message}")
                }
            }
        }
    }

    // Helper extension function for Bundle size estimation
    private fun Bundle.sizeAsParcel(): Int {
        val parcel = android.os.Parcel.obtain()
        try {
            writeToParcel(parcel, 0)
            return parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    private fun updateAccessTime(key: String) {
        accessTimeLock.write {
            lastAccessTime[key] = System.currentTimeMillis()
        }
    }

    private fun trimCache() {
        cacheLock.read {
            if (webViewCache.size > MAX_CACHE_SIZE) {
                // Find oldest WebView that isn't the current active one
                accessTimeLock.read {
                    val oldestKey = lastAccessTime.entries
                        .sortedBy { it.value }
                        .firstOrNull { it.key != currentActiveKey }
                        ?.key
    
                    oldestKey?.let { key ->
                        enqueueOperation(key, 2) {
                            withContext(Dispatchers.Main) { 
                                removeCachedWebView(key)
                            }
                            Log.d(TAG, "Trimmed cached WebView: $key")
                        }
                    }
                }
            }
        }
    }

    /**
     * Get a cached WebView if available
     */
    fun getCachedWebView(key: String): WebView? {
        return cacheLock.read {
            val webView = webViewCache[key]
            if (webView != null) {
                updateAccessTime(key)
                Log.d(TAG, "Retrieved cached WebView for key: $key")
            }
            webView
        }
    }
    
    /**
     * Get a WebView with its state properly restored or create a new one
     */
    fun getAndRestoreCachedWebView(key: String, newWebViewFactory: () -> WebView): WebView {
        val existingWebView = cacheLock.read { webViewCache[key] }
        
        if (existingWebView != null) {
            enqueueOperation(key, 8) {
                // Make sure detachment happens on the main thread
                withContext(Dispatchers.Main) {
                    (existingWebView.parent as? ViewGroup)?.removeView(existingWebView)
                
                    // Break up operations with delays
                    delay(20) // Small delay to let UI thread breathe
                    
                    // Only restore state after ensuring proper detachment
                    stateLock.read {
                        // Check if we need to restore state
                        val isAlreadyLoaded = existingWebView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
                        if (!isAlreadyLoaded) {
                            webViewStates[key]?.let { state ->
                                try {
                                    existingWebView.restoreState(state)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to restore state: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }
            
            return existingWebView
        } else {
            Log.d(TAG, "No cached WebView found for key: $key, creating new one")
            
            // Create new WebView with proper initialization
            return try {
                val newWebView = newWebViewFactory()
                
                enqueueOperation(key, 7) {
                    withContext(Dispatchers.Main) {
                        // Ensure proper initial state for new WebViews
                        newWebView.setTag(R.string.prevent_reload_tag, false)
                        
                        // Cache the new WebView
                        cacheLock.write {
                            webViewCache[key] = newWebView
                        }
                        
                        stateLock.write {
                            webViewLoadedState[key] = false
                        }
                        
                        // Start WebView in visible state
                        newWebView.visibility = View.VISIBLE
                    }
                }
                
                newWebView
            } catch (e: Exception) {
                Log.e(TAG, "Error creating WebView: ${e.message}")
                throw e
            }
        }
    }

    // This helper method ensures we have a context to create a fallback WebView
    private fun getContextSafely(): Context {
        // Try to use context from an existing WebView if available
        cacheLock.read {
            webViewCache.values.firstOrNull()?.context?.let {
                return it
            }
        }
        
        // If no context available and we're trying to create a WebView, this is a serious error
        // We'll use application context as a last resort, but it should be provided by the app
        throw IllegalStateException("No valid context available to create WebView")
    }

    /**
     * Restores the WebView state from a saved bundle
     */
    fun restoreWebViewState(key: String, webView: WebView): Boolean {
        return stateLock.write {
            // CRITICAL FIX: Don't restore state if the WebView is already loaded to prevent reloads
            val isAlreadyLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
            
            if (isAlreadyLoaded) {
                Log.d(TAG, "WebView already loaded, skipping state restoration: $key")
                return@write false
            }
            
            webViewStates[key]?.let { state -> 
                try {
                    webView.restoreState(state)
                    
                    // Also restore loaded state if we have it
                    if (webViewLoadedState[key] == true) {
                        webView.setTag(R.string.prevent_reload_tag, true)
                        Log.d(TAG, "Restored loaded flag for WebView: $key")
                    }
                    
                    Log.d(TAG, "State restored for WebView: $key")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore state: ${e.message}")
                    false
                }
            } ?: false
        }
    }

    /**
     * Pause all WebViews except the active one
     */
    fun pauseAllWebViewsExcept(activeKey: String?) {
        enqueueOperation(activeKey, 7) {
            val webViews = cacheLock.read { webViewCache.toMap() }
            
            webViews.forEach { (key, webView) ->
                if (key != activeKey) {
                    try {
                        withContext(Dispatchers.Main) {
                            // Always save state before pausing
                            val bundle = Bundle()
                            webView.saveState(bundle)
                            
                            // Break up lock operations
                            stateLock.write {
                                webViewStates[key] = bundle
                                
                                // Remember if this WebView has been fully loaded
                                val isLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
                                webViewLoadedState[key] = isLoaded
                            }
                            

                            // Pause WebView and detach from parent
                            webView.onPause()
                            (webView.parent as? ViewGroup)?.removeView(webView)
                            
                            // Hide the WebView to prevent it from showing when we switch
                            webView.visibility = View.INVISIBLE
                            
                            Log.d(TAG, "Paused WebView: $key")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to pause WebView: ${e.message}")
                    }
                }
            }
        }
    }

    fun onLowMemory() {
        enqueueOperation("low_memory", 10) {
            // Keep only the active WebView and discard others
            val currentKey = currentActiveKey
            val keysToRemove = cacheLock.read { webViewCache.keys.filter { it != currentKey } }
            
            for (key in keysToRemove) {
                withContext(Dispatchers.Main) {
                    removeCachedWebView(key)
                }
            }
            
            // Clean up other resources
            stateLock.write {
                webViewStates.keys.filter { it != currentKey }.forEach {
                    webViewStates.remove(it)
                }
                webViewLoadedState.keys.filter { it != currentKey }.forEach {
                    webViewLoadedState.remove(it)
                }
            }
            
            // Force garbage collection
            System.gc()
        }
    }

    /**
     * Resume a specific WebView
     */
    fun resumeWebView(key: String) {
        enqueueOperation(key, 8) {
            val webView = cacheLock.read { webViewCache[key] }
            
            webView?.let {
                withContext(Dispatchers.Main) {
                    try {
                        // Make sure the WebView is visible
                        webView.visibility = View.VISIBLE
                        
                        // Check if WebView is already loaded to avoid reloads
                        val isAlreadyLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
                        
                        // Only restore state if the WebView isn't already loaded with content
                        stateLock.read {
                            if (!isAlreadyLoaded) {
                                webViewStates[key]?.let { state ->
                                    try {
                                        webView.restoreState(state)
                                        
                                        // Restore media state if present
                                        state.getString("media_state")?.let { mediaState ->
                                            webView.evaluateJavascript("""
                                                (function() {
                                                    try {
                                                        const state = ${mediaState};
                                                        if (state.hasMedia) {
                                                            const media = document.querySelector('audio,video');
                                                            if (media) {
                                                                media.currentTime = ${state}.currentTime || 0;
                                                                media.volume = ${state}.volume || 1;
                                                                if (${state}.isPlaying) {
                                                                    media.play();
                                                                }
                                                            }
                                                        }
                                                        return true;
                                                    } catch(e) {
                                                        console.error('Error restoring media state:', e);
                                                        return false;
                                                    }
                                                })();
                                            """.trimIndent(), null)
                                        }
                                        
                                        Log.d(TAG, "Restored state during resume for WebView: $key")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error restoring state during resume: ${e.message}")
                                    }
                                }
                            } else {
                                Log.d(TAG, "Skipping state restore on resume (already loaded): $key")
                            }
                        }

                        // Always restore the loaded state flag from our cache
                        stateLock.read {
                            if (webViewLoadedState[key] == true) {
                                webView.setTag(R.string.prevent_reload_tag, true)
                            }
                        }
                        
                        // Resume WebView and make it the active key
                        webView.onResume()
                        setCurrentActiveKey(key)
                        
                        Log.d(TAG, "Resumed WebView: $key")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resume WebView: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Check if a WebView has been fully loaded
     */
    private fun isWebViewLoaded(webView: WebView): Boolean {
        return webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
    }

    /**
     * Remove a cached WebView if it's no longer needed
     */
    fun removeCachedWebView(key: String) {
        if (isConfigurationChanging) return
        
        enqueueOperation(key, 5) {
            val webView = cacheLock.write {
                val view = webViewCache.remove(key)
                view
            }
            
            stateLock.write {
                webViewStates.remove(key)
                webViewLoadedState.remove(key)
            }
            
            webView?.let {
                withContext(Dispatchers.Main) {
                    try {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.stopLoading()
                        webView.clearHistory()
                        webView.loadUrl("about:blank")
                        webView.removeAllViews()
                        webView.destroy()
                        
                        Log.d(TAG, "Removed WebView: $key")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error destroying WebView: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Clear all cached WebViews
     */
    fun clearAll() {
        if (isConfigurationChanging) return
        
        enqueueOperation("clear_all", 10) {
            val webViews = cacheLock.read { webViewCache.values.toList() }
            
            withContext(Dispatchers.Main) {
                webViews.forEach { webView ->
                    try {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.stopLoading()
                        webView.clearHistory()
                        webView.loadUrl("about:blank")
                        webView.removeAllViews()
                        webView.destroy()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing WebView: ${e.message}")
                    }
                }
                
                cacheLock.write {
                    webViewCache.clear()
                }
                
                stateLock.write {
                    webViewStates.clear()
                    webViewLoadedState.clear()
                }
                
                tempWebView?.destroy()
                tempWebView = null
                currentActiveKey = null
                
                Log.d(TAG, "Cleared all WebViews")
            }
        }
    }
    
    /**
     * Enqueue an operation with a priority
     * Higher priority operations will be processed first
     */
    private fun enqueueOperation(key: String?, priority: Int, operation: suspend () -> Unit) {
        synchronized(operationQueue) {
            operationQueue.add(WebViewOperation(key, priority, operation))
        }
        processOperationQueue()
    }
    
    /**
     * Process the operation queue
     */
    private fun processOperationQueue() {
        if (!isProcessingQueue.compareAndSet(false, true)) {
            return
        }
        
        cacheScope.launch {
            try {
                while (true) {
                    val operation = synchronized(operationQueue) {
                        if (operationQueue.isEmpty()) null else operationQueue.poll()
                    } ?: break
                    
                    try {
                        operation.operation()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing WebView operation for ${operation.key}: ${e.message}")
                    }
                    
                    // Yield to allow other coroutines to execute 
                    yield()
                }
            } finally {
                isProcessingQueue.set(false)
                
                // Check if more operations were added during processing
                if (operationQueue.isNotEmpty()) {
                    processOperationQueue()
                }
            }
        }
    }
}

