package com.tiddlywikibrowser.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import android.util.Log

class ReloadBlockingWebViewClient : WebViewClient() {
    
    private var isReloading = false
    private var originalUrl: String? = null
    
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return if (isReloading) {
            // If we're reloading, let the WebView handle it normally
            false
        } else {
            // For navigation requests, handle normally
            super.shouldOverrideUrlLoading(view, request)
        }
    }
    
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (isReloading && url == originalUrl) {
            // We're successfully reloading the original URL
            Log.d("ReloadBlockingWebViewClient", "Reloading original URL: $url")
        }
    }
    
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (isReloading) {
            isReloading = false
            originalUrl = null
        }
    }
    
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (isReloading) {
            isReloading = false
            originalUrl = null
        }
    }
    
    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?
    ) {
        // Handle SSL errors appropriately
        // In production, you should show a dialog to the user
        handler?.cancel()
    }
    
    fun forceReload(webView: WebView, url: String) {
        isReloading = true
        originalUrl = url
        webView.loadUrl(url)
    }
} 