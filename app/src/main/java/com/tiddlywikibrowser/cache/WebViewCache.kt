package com.tiddlywikibrowser

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

object WebViewCache {
    private val webViewCache = ConcurrentHashMap<String, WebView>()
    private val webViewStates = ConcurrentHashMap<String, Bundle>()
    private var tempWebView: WebView? = null
    private var isConfigurationChanging = false
    private var currentActiveKey: String? = null

    fun setConfigurationChanging(changing: Boolean) {
        isConfigurationChanging = changing
    }
    
    fun setCurrentActiveKey(key: String?) {
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

    fun cacheWebView(key: String, webView: WebView) {
        // Remove from parent view if attached
        (webView.parent as? ViewGroup)?.removeView(webView)
        
        // Save WebView state before caching
        val bundle = Bundle()
        webView.saveState(bundle)
        webViewStates[key] = bundle
        
        // Store in cache
        webViewCache[key] = webView
    }

    fun getCachedWebView(key: String): WebView? {
        return webViewCache[key]
    }

    fun restoreWebViewState(key: String, webView: WebView) {
        webViewStates[key]?.let { state ->
            try {
                webView.restoreState(state)
            } catch (e: Exception) {
                Log.e("WebViewCache", "Failed to restore state: ${e.message}")
            }
        }
    }

    fun pauseAllWebViewsExcept(activeKey: String?) {
        webViewCache.forEach { (key, webView) ->
            if (key != activeKey) {
                try {
                    // Save state before pausing
                    val bundle = Bundle()
                    webView.saveState(bundle)
                    webViewStates[key] = bundle
                    
                    // Pause WebView and detach from parent
                    webView.onPause()
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    
                    // Hide the WebView to prevent it from showing when we switch
                    webView.visibility = View.INVISIBLE
                } catch (e: Exception) {
                    Log.e("WebViewCache", "Failed to pause WebView: ${e.message}")
                }
            }
        }
    }

    fun resumeWebView(key: String) {
        webViewCache[key]?.let { webView ->
            try {
                // Make sure the WebView is visible
                webView.visibility = View.VISIBLE
                
                // Resume WebView
                webView.onResume()
                
                // Request layout to ensure it's properly displayed
                webView.requestLayout()
                
                // Force redraw
                webView.invalidate()
                
                // Update current key
                currentActiveKey = key
            } catch (e: Exception) {
                Log.e("WebViewCache", "Failed to resume WebView: ${e.message}")
            }
        }
    }

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
                } catch (e: Exception) {
                    Log.e("WebViewCache", "Error destroying WebView: ${e.message}")
                }
            }
            webViewStates.remove(key)
        }
    }

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
                    Log.e("WebViewCache", "Error clearing WebView: ${e.message}")
                }
            }
            webViewCache.clear()
            webViewStates.clear()
            tempWebView?.destroy()
            tempWebView = null
            currentActiveKey = null
        }
    }
}