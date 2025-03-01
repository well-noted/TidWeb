package com.tiddlywikibrowser

import android.content.Context
import android.webkit.WebView

object WebViewCache {
    private var tempWebView: WebView? = null

    fun clearCache(context: Context) {
        ThreadManager.runOnMain {
            // Create a temporary WebView if needed
            if (tempWebView == null) {
                tempWebView = WebView(context.applicationContext)
            }
            
            // Clear the cache
            tempWebView?.clearCache(true)
            
            // Destroy and clean up
            tempWebView?.destroy()
            tempWebView = null
        }
    }
}