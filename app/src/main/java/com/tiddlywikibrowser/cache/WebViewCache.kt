package com.tiddlywikibrowser

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashMap

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

    fun setConfigurationChanging(changing: Boolean) {
        isConfigurationChanging = changing
    }
    
    fun setCurrentActiveKey(key: String?) {
        Log.d(TAG, "Setting current active key to: $key")
        currentActiveKey = key
    }
    
    fun getCurrentActiveKey(): String? {
        return currentActiveKey
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

    /**
     * Cache a WebView with its state for future restoration
     */
    fun cacheWebView(key: String, webView: WebView) {
        try {
            // Always save the state even if we already have the WebView cached
            val bundle = Bundle()
            webView.saveState(bundle)
            webViewStates[key] = bundle
            
            // Track loaded state - CRITICAL for preventing reloads
            val isLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
            webViewLoadedState[key] = isLoaded
            Log.d(TAG, "Cached WebView state for key: $key, loaded=$isLoaded")
            
            // Update access time
            updateAccessTime(key)
            
            // Store in cache if it's not already there
            if (!webViewCache.containsKey(key)) {
                webViewCache[key] = webView
                Log.d(TAG, "Added WebView to cache for key: $key")
            }

            // Trim cache if needed
            if (webViewCache.size > MAX_CACHE_SIZE) {
                trimCache()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error caching WebView: ${e.message}")
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
                removeCachedWebView(key)
                Log.d(TAG, "Trimmed cached WebView: $key")
            }
        }
    }

    /**
     * Get a cached WebView if available
     */
    fun getCachedWebView(key: String): WebView? {
        val webView = webViewCache[key]
        if (webView != null) {
            updateAccessTime(key)
            Log.d(TAG, "Retrieved cached WebView for key: $key")
        }
        return webView
    }
    
    /**
     * Get a WebView with its state properly restored or create a new one
     */
    fun getAndRestoreCachedWebView(key: String, newWebViewFactory: () -> WebView): WebView {
        val existingWebView = webViewCache[key]
        
        if (existingWebView != null) {
            Log.d(TAG, "Restoring cached WebView for key: $key")
            
            // Make sure the WebView is detached from any previous parent
            (existingWebView.parent as? ViewGroup)?.removeView(existingWebView)
            
            // Critical fix: Ensure we restore the state ONLY if the WebView doesn't already have content
            val isAlreadyLoaded = existingWebView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
            
            if (!isAlreadyLoaded) {
                // Only restore state if the WebView isn't already loaded with content
                webViewStates[key]?.let { state ->
                    try {
                        existingWebView.restoreState(state)
                        Log.d(TAG, "State restored for WebView: $key")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore state: ${e.message}")
                    }
                }
            } else {
                Log.d(TAG, "WebView already has content, skipping state restore to prevent reload")
            }
            
            // Make it visible
            existingWebView.visibility = View.VISIBLE
            
            // Always restore loaded state tag when we have it
            if (webViewLoadedState[key] == true) {
                existingWebView.setTag(R.string.prevent_reload_tag, true)
                Log.d(TAG, "Restored loaded state tag for WebView: $key")
            }
            
            return existingWebView
        } else {
            Log.d(TAG, "No cached WebView found for key: $key, creating new one")
            
            // Create a new WebView using the provided factory function
            val newWebView = newWebViewFactory()
            
            // New WebViews start with loaded=false
            newWebView.setTag(R.string.prevent_reload_tag, false)
            
            // Cache the new WebView
            webViewCache[key] = newWebView
            
            return newWebView
        }
    }

    /**
     * Restores the WebView state from a saved bundle
     */
    fun restoreWebViewState(key: String, webView: WebView): Boolean {
        // CRITICAL FIX: Don't restore state if the WebView is already loaded to prevent reloads
        val isAlreadyLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
        
        if (isAlreadyLoaded) {
            Log.d(TAG, "WebView already loaded, skipping state restoration: $key")
            return false
        }
        
        return webViewStates[key]?.let { state -> 
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

    /**
     * Pause all WebViews except the active one
     */
    fun pauseAllWebViewsExcept(activeKey: String?) {
        webViewCache.forEach { (key, webView) ->
            if (key != activeKey) {
                try {
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
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pause WebView: ${e.message}")
                }
            }
        }
    }

    /**
     * Resume a specific WebView
     */
    fun resumeWebView(key: String) {
        webViewCache[key]?.let { webView ->
            try {
                // Make sure the WebView is visible
                webView.visibility = View.VISIBLE
                
                // Check if WebView is already loaded to avoid reloads
                val isAlreadyLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
                
                // Only restore state if the WebView isn't already loaded with content
                if (!isAlreadyLoaded) {
                    webViewStates[key]?.let { state ->
                        try {
                            webView.restoreState(state)
                            Log.d(TAG, "Restored state during resume for WebView: $key")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error restoring state during resume: ${e.message}")
                        }
                    }
                } else {
                    Log.d(TAG, "Skipping state restore on resume (already loaded): $key")
                }
                
                // Always restore the loaded state flag from our cache
                if (webViewLoadedState[key] == true) {
                    webView.setTag(R.string.prevent_reload_tag, true)
                }
                
                // Resume WebView and make it the active key
                webView.onResume()
                currentActiveKey = key
                
                Log.d(TAG, "Resumed WebView: $key")
                
                // Apply JavaScript to prevent reloads after resuming
                if (isAlreadyLoaded) {
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
                            }
                            return "reload-blocker-reinforced";
                        })();
                    """.trimIndent(), null)
                } else {

                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume WebView: ${e.message}")
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
        if (!isConfigurationChanging) {
            webViewCache.remove(key)?.let { webView ->
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
            webViewStates.remove(key)
            webViewLoadedState.remove(key)
        }
    }

    /**
     * Clear all cached WebViews
     */
    fun clearAll() {
        if (!isConfigurationChanging) {
            webViewCache.values.forEach { webView ->
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
            webViewCache.clear()
            webViewStates.clear()
            webViewLoadedState.clear()
            tempWebView?.destroy()
            tempWebView = null
            currentActiveKey = null
            
            Log.d(TAG, "Cleared all WebViews")
        }
    }
}