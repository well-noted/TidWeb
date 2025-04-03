package com.tiddlywikibrowser

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
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
    
    // Add lock for synchronizing cache operations
    private val cacheLock = ReentrantReadWriteLock()
    
    // Add flag to track active operations
    private var activeOperation = false
    private var lastOperationTime = 0L
    private const val OPERATION_COOLDOWN = 500L // ms between cache operations

    fun setConfigurationChanging(changing: Boolean) {
        isConfigurationChanging = changing
    }
    
    fun setCurrentActiveKey(key: String?) {
        cacheLock.write {
            Log.d(TAG, "Setting current active key to: $key")
            currentActiveKey = key
        }
    }
    
    fun getCurrentActiveKey(): String? {
        return cacheLock.read { currentActiveKey }
    }

    fun clearCache(context: Context) {
        if (!isConfigurationChanging) {
            ThreadManager.runOnMain {
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

    private fun monitorWebViewStateSize(key: String, bundle: Bundle) {
        ThreadManager.runOnBackground {
            try {
                val parcel = android.os.Parcel.obtain()
                bundle.writeToParcel(parcel, 0)
                val sizeBytes = parcel.dataSize()
                parcel.recycle()
                
                if (sizeBytes > 500 * 1024) { // 500KB
                    Log.e(TAG, "WebView state for $key is too large: ${sizeBytes/1024}KB!")
                    
                    // Remove problematic state to prevent crashes
                    cacheLock.write {
                        webViewStates.remove(key)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error measuring bundle size: ${e.message}")
            }
        }
    }

    fun checkForLeakedWebViews() {
        if (isActiveOperation()) return
        
        ThreadManager.runOnBackground {
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
                ThreadManager.runOnMain {
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
        if (isActiveOperation()) {
            Log.d(TAG, "Skipping cacheWebView operation as another operation is in progress")
            return
        }
        
        startOperation()
        
        ThreadManager.runOnMain {
            try {
                cacheLock.write {
                    // Limit bundle size to prevent TransactionTooLargeException
                    val bundle = Bundle()
                    webView.saveState(bundle)
                    
                    // Preserve important WebView settings
                    val settingsBundle = Bundle()
                    settingsBundle.putBoolean("mediaPlaybackRequiresUserGesture", 
                        !webView.settings.mediaPlaybackRequiresUserGesture)
                    settingsBundle.putBoolean("javaScriptEnabled", 
                        webView.settings.javaScriptEnabled)
                    settingsBundle.putBoolean("domStorageEnabled", 
                        webView.settings.domStorageEnabled)
                    
                    // Store the settings in the main bundle
                    bundle.putBundle("webview_settings", settingsBundle)
                    
                    // Check if this WebView is running in background mode
                    val backgroundEnabledKey = "$key:background_enabled"
                    val backgroundEnabled = bundle.getBoolean(backgroundEnabledKey, false) ||
                                            !webView.settings.mediaPlaybackRequiresUserGesture
                    bundle.putBoolean(backgroundEnabledKey, backgroundEnabled)
                    
                    Log.d(TAG, "Cached WebView for $key with background mode: $backgroundEnabled")
                    
                    // Check bundle size and trim if necessary
                    if (bundle.sizeAsParcel() > 400 * 1024) { // 400KB limit
                        Log.w(TAG, "WebView state bundle too large, using minimal state")
                        val minimalBundle = Bundle()
                        minimalBundle.putString("url", webView.url)
                        // Preserve the critical settings even in minimal state
                        minimalBundle.putBundle("webview_settings", settingsBundle)
                        minimalBundle.putBoolean(backgroundEnabledKey, backgroundEnabled)
                        webViewStates[key] = minimalBundle
                    } else {
                        webViewStates[key] = bundle
                    }
                    
                    // Track loaded state
                    val isLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
                    webViewLoadedState[key] = isLoaded
                    
                    // Update access time
                    updateAccessTime(key)
                    
                    // Store in cache if not already there
                    if (!webViewCache.containsKey(key)) {
                        // Ensure WebView is detached from any parent
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webViewCache[key] = webView
                    }
                    
                    // Trim cache if needed
                    if (webViewCache.size > MAX_CACHE_SIZE) {
                        trimCache()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error caching WebView: ${e.message}")
            } finally {
                endOperation()
            }
        }
    }

    // Helper extension function for Bundle size estimation
    private fun Bundle.sizeAsParcel(): Int {
        val parcel = android.os.Parcel.obtain()
        try {
            parcel.setDataPosition(0)
            writeToParcel(parcel, 0)
            return parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    private fun updateAccessTime(key: String) {
        lastAccessTime[key] = System.currentTimeMillis()
    }

    private fun trimCache() {
        if (webViewCache.size > MAX_CACHE_SIZE) {
            // Find oldest WebView that isn't the current active one
            val oldestKey = lastAccessTime.entries
                .sortedBy { it.value }
                .firstOrNull { it.key != currentActiveKey }
                ?.key

            oldestKey?.let { key ->
                ThreadManager.runOnMain { 
                    removeCachedWebView(key)
                }
                Log.d(TAG, "Trimmed cached WebView: $key")
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
        if (isActiveOperation()) {
            Log.d(TAG, "Throttling getAndRestoreCachedWebView during active operation")
        }
        
        startOperation()
        
        try {
            val existingWebView = cacheLock.read { webViewCache[key] }
            
            if (existingWebView != null) {
                // Make sure detachment happens on the main thread
                ThreadManager.runOnMain {
                    (existingWebView.parent as? ViewGroup)?.removeView(existingWebView)
                }
                
                // Only restore state after ensuring proper detachment
                ThreadManager.runOnMainWithDelay(50) {
                    cacheLock.write {
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
                
                return existingWebView
            } else {
                Log.d(TAG, "No cached WebView found for key: $key, creating new one")
                
                // Create new WebView with proper error handling
                return try {
                    val newWebView = newWebViewFactory()
                    
                    cacheLock.write {
                        // New WebViews start with loaded=false
                        newWebView.setTag(R.string.prevent_reload_tag, false)
                        
                        // Cache the new WebView
                        webViewCache[key] = newWebView
                    }
                    
                    newWebView
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating WebView: ${e.message}")
                    
                    // Return a blank WebView as fallback
                    cacheLock.read {
                        WebView(webViewCache.values.firstOrNull()?.context ?: getContextSafely()).apply {
                            loadUrl("about:blank")
                        }
                    }
                }
            }
        } finally {
            endOperation()
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
        if (isActiveOperation()) return false
        
        startOperation()
        
        try {
            return cacheLock.write {
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
        } finally {
            endOperation()
        }
    }

    /**
     * Pause all WebViews except the active one
     */
    fun pauseAllWebViewsExcept(activeKey: String?) {
        if (isActiveOperation()) return
        
        startOperation()
        
        try {
            val webViews = cacheLock.read { webViewCache.toMap() }
            
            webViews.forEach { (key, webView) ->
                if (key != activeKey) {
                    try {
                        cacheLock.write {
                            // Always save state before pausing
                            val bundle = Bundle()
                            webView.saveState(bundle)
                            webViewStates[key] = bundle
                            
                            // Remember if this WebView has been fully loaded
                            val isLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
                            webViewLoadedState[key] = isLoaded
                            Log.d(TAG, "Saved state before pausing WebView: $key, loaded=$isLoaded")
                            
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
        } finally {
            endOperation()
        }
    }

    fun onLowMemory() {
        if (isActiveOperation()) return
        
        startOperation()
        
        try {
            // Keep only the active WebView and discard others
            val currentKey = cacheLock.read { currentActiveKey }
            val keysToRemove = cacheLock.read { webViewCache.keys.filter { it != currentKey } }
            
            for (key in keysToRemove) {
                ThreadManager.runOnBackground {
                    removeCachedWebView(key)
                }
            }
            
            // Clean up other resources
            cacheLock.write {
                webViewStates.keys.filter { it != currentKey }.forEach {
                    webViewStates.remove(it)
                }
                webViewLoadedState.keys.filter { it != currentKey }.forEach {
                    webViewLoadedState.remove(it)
                }
            }
            
            // Force garbage collection
            System.gc()
        } finally {
            endOperation()
        }
    }

    /**
     * Resume a specific WebView
     */
    fun resumeWebView(key: String) {
        if (isActiveOperation()) {
            Log.d(TAG, "Postponing WebView resume operation - another operation in progress")
            ThreadManager.runOnMainWithDelay(100) {
                resumeWebView(key)
            }
            return
        }
        
        startOperation()
        
        try {
            val webView = cacheLock.read { webViewCache[key] }
            
            webView?.let {
                ThreadManager.runOnMain {
                    try {
                        cacheLock.write {
                            // Make sure the WebView is visible
                            webView.visibility = View.VISIBLE
                            
                            // Check if WebView is already loaded to avoid reloads
                            val isAlreadyLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
                            
                            // Only restore state if the WebView isn't already loaded with content
                            if (!isAlreadyLoaded) {
                                webViewStates[key]?.let { state ->
                                    try {
                                        // Restore WebView settings first
                                        state.getBundle("webview_settings")?.let { settingsBundle ->
                                            try {
                                                webView.settings.apply {
                                                    javaScriptEnabled = settingsBundle.getBoolean("javaScriptEnabled", true)
                                                    domStorageEnabled = settingsBundle.getBoolean("domStorageEnabled", true)
                                                    
                                                    // Restore background mode settings
                                                    val allowBackgroundMedia = settingsBundle.getBoolean("mediaPlaybackRequiresUserGesture", false)
                                                    if (allowBackgroundMedia) {
                                                        mediaPlaybackRequiresUserGesture = false
                                                        Log.d(TAG, "Restored background media setting: $key")
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error restoring WebView settings: ${e.message}")
                                            }
                                        }
                                        
                                        // Also check direct background mode flag
                                        val backgroundEnabledKey = "$key:background_enabled"
                                        val wasBackgroundEnabled = state.getBoolean(backgroundEnabledKey, false)
                                        if (wasBackgroundEnabled) {
                                            webView.settings.mediaPlaybackRequiresUserGesture = false
                                            Log.d(TAG, "Restored WebView with background mode enabled: $key")
                                        }
                                        
                                        // Then restore the full WebView state
                                        webView.restoreState(state)
                                        Log.d(TAG, "Restored state during resume for WebView: $key")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error restoring state during resume: ${e.message}")
                                    }
                                }
                            } else {
                                Log.d(TAG, "Skipping state restore on resume (already loaded): $key")
                                
                                // Even for already loaded WebViews, restore background mode settings
                                webViewStates[key]?.let { state ->
                                    state.getBundle("webview_settings")?.let { settingsBundle ->
                                        val allowBackgroundMedia = settingsBundle.getBoolean("mediaPlaybackRequiresUserGesture", false)
                                        if (allowBackgroundMedia) {
                                            webView.settings.mediaPlaybackRequiresUserGesture = false
                                            Log.d(TAG, "Restored background media setting for loaded WebView: $key")
                                        }
                                    }
                                    
                                    // Also check direct background mode flag
                                    val backgroundEnabledKey = "$key:background_enabled"
                                    val wasBackgroundEnabled = state.getBoolean(backgroundEnabledKey, false)
                                    if (wasBackgroundEnabled) {
                                        webView.settings.mediaPlaybackRequiresUserGesture = false
                                    }
                                }
                            }
                            
                            // Always restore the loaded state flag from our cache
                            if (webViewLoadedState[key] == true) {
                                webView.setTag(R.string.prevent_reload_tag, true)
                            }
                            
                            // Resume WebView and make it the active key
                            webView.onResume()
                            currentActiveKey = key
                            
                            // Update access time
                            updateAccessTime(key)
                            
                            Log.d(TAG, "Resumed WebView: $key")
                        }
                        
                        // Apply JavaScript to prevent reloads after resuming
                        if (webView.getTag(R.string.prevent_reload_tag) == true) {
                            webView.evaluateJavascript("""
                                (function() {
                                    // Basic reload prevention for resumed WebViews
                                    if (!window.__reloadBlockerInstalled) {
                                        console.log("[WebViewCache] Installing reload blocker");
                                        window.location.reload = function() { 
                                            console.log("[Reload blocked] location.reload()"); 
                                            return false; 
                                        };
                                        window.__reloadBlockerInstalled = true;
                                        
                                        // Safe way to disable reloads for TiddlyWiki
                                        if (window.${'$'}tw && window.${'$'}tw.safeMode === undefined) {
                                            window.${'$'}tw.safeMode = true;
                                            console.log("[WebViewCache] Enabled TiddlyWiki safe mode");
                                        }
                                    }
                                    return true;
                                })();
                            """.trimIndent(), null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resume WebView: ${e.message}")
                    }
                }
            }
        } finally {
            endOperation()
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
        
        if (isActiveOperation()) {
            Log.d(TAG, "Postponing WebView removal - another operation in progress")
            ThreadManager.runOnBackgroundWithDelay(100) {
                removeCachedWebView(key)
            }
            return
        }
        
        startOperation()
        
        try {
            val webView = cacheLock.write {
                val view = webViewCache.remove(key)
                webViewStates.remove(key)
                webViewLoadedState.remove(key)
                view
            }
            
            webView?.let {
                ThreadManager.runOnMain {
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
        } finally {
            endOperation()
        }
    }

    /**
     * Clear all cached WebViews
     */
    fun clearAll() {
        if (isConfigurationChanging) return
        
        if (isActiveOperation()) {
            Log.d(TAG, "Postponing WebView clearAll - another operation in progress")
            ThreadManager.runOnBackgroundWithDelay(100) {
                clearAll()
            }
            return
        }
        
        startOperation()
        
        try {
            val webViews = cacheLock.read { webViewCache.values.toList() }
            
            ThreadManager.runOnMain {
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
                    webViewStates.clear()
                    webViewLoadedState.clear()
                    tempWebView?.destroy()
                    tempWebView = null
                    currentActiveKey = null
                }
                
                Log.d(TAG, "Cleared all WebViews")
            }
        } finally {
            endOperation()
        }
    }
    
    /**
     * Check if there's another operation in progress
     */
    private fun isActiveOperation(): Boolean {
        val now = System.currentTimeMillis()
        return activeOperation && (now - lastOperationTime < OPERATION_COOLDOWN)
    }
    
    /**
     * Mark the start of an operation
     */
    private fun startOperation() {
        activeOperation = true
        lastOperationTime = System.currentTimeMillis()
    }
    
    /**
     * Mark the end of an operation
     */
    private fun endOperation() {
        lastOperationTime = System.currentTimeMillis()
        activeOperation = false
    }

    /**
     * Clear just the cache entry for a specific key without destroying the WebView
     * This is useful when we need to force a refresh of settings but keep the WebView instance
     */
    fun clearCacheEntry(key: String) {
        if (isActiveOperation()) {
            Log.d(TAG, "Postponing clearCacheEntry - another operation in progress")
            ThreadManager.runOnBackgroundWithDelay(100) {
                clearCacheEntry(key)
            }
            return
        }
        
        startOperation()
        
        try {
            Log.d(TAG, "Clearing cache entry for: $key")
            cacheLock.write {
                // Just remove the state data, not the WebView itself
                webViewStates.remove(key)
                webViewLoadedState.remove(key)
                lastAccessTime.remove(key)
            }
        } finally {
            endOperation()
        }
    }
}

