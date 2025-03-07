package com.tiddlywikibrowser.config

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import com.tiddlywikibrowser.model.WikiInstance
import com.tiddlywikibrowser.model.WikiLoadStrategy

class WebViewSetupConfig(private val wiki: WikiInstance, private val context: Context) {
    fun configure(webView: WebView, strategy: WikiLoadStrategy = WikiLoadStrategy.MEDIUM_WIKI) {
        webView.settings.apply {
            // Essential settings that should always be enabled
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            defaultTextEncodingName = "UTF-8"
            
            // Configure based on wiki size strategy
            when (strategy) {
                WikiLoadStrategy.LARGE_WIKI -> {
                    // Aggressive optimizations for large wikis
                    blockNetworkImage = true // Defer image loading
                    loadsImagesAutomatically = false
                    cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    setGeolocationEnabled(false)
                }
                
                WikiLoadStrategy.MEDIUM_WIKI -> {
                    // Moderate optimizations
                    blockNetworkImage = false
                    loadsImagesAutomatically = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    setGeolocationEnabled(false)
                }
                
                WikiLoadStrategy.SMALL_WIKI -> {
                    // Standard settings for small wikis
                    blockNetworkImage = false
                    loadsImagesAutomatically = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                
                WikiLoadStrategy.INITIALIZING -> {
                    // Minimal settings during initialization
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
            }
            
            // Common settings for all strategies
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            
            try {
                // Additional optimizations if available
                setRenderPriority(WebSettings.RenderPriority.HIGH)
            } catch (e: Exception) {
                // Ignore if not available on this Android version
            }
        }
    }
    
    companion object {
        fun applyMemoryOptimizations(webView: WebView) {
            webView.settings.apply {
                // Memory optimizations
                setGeolocationEnabled(false)
                setNeedInitialFocus(false)
                saveFormData = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                blockNetworkImage = true
            }
        }
    }
}