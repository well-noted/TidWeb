package com.tiddlywikibrowser

import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

object WebViewCache {
    private val webViewCache = ConcurrentHashMap<String, WebView>()
    private val webViewStates = ConcurrentHashMap<String, Bundle>()
    private var tempWebView: WebView? = null
    private var isConfigurationChanging = false

    fun setConfigurationChanging(changing: Boolean) {
        isConfigurationChanging = changing
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
        webViewCache[key] = webView
        // Save WebView state
        val bundle = Bundle()
        webView.saveState(bundle)
        webViewStates[key] = bundle
    }

    fun getCachedWebView(key: String): WebView? {
        return webViewCache[key]
    }

    fun restoreWebViewState(key: String, webView: WebView) {
        webViewStates[key]?.let { state ->
            webView.restoreState(state)
        }
    }

    fun removeCachedWebView(key: String) {
        if (!isConfigurationChanging) {
            webViewCache.remove(key)?.destroy()
            webViewStates.remove(key)
        }
    }

    fun clearAll() {
        if (!isConfigurationChanging) {
            webViewCache.values.forEach { it.destroy() }
            webViewCache.clear()
            webViewStates.clear()
            tempWebView?.destroy()
            tempWebView = null
        }
    }
}