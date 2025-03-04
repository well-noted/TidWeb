package com.tiddlywikibrowser

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Bundle
import android.util.Log

/**
 * A specialized WebViewClient that blocks unwanted reloads when switching between wikis
 * while maintaining the ability to interact with the loaded content.
 */
class ReloadBlockingWebViewClient(
    private val context: Context,
    private val wikiUrl: String,
    private val onLoadingStateChanged: (Boolean) -> Unit,
    private val onErrorReceived: (String?) -> Unit,
    private val onPageLoaded: (Boolean) -> Unit
) : WebViewClient() {
    companion object {
        private const val TAG = "ReloadBlockingWVC"
        private const val RELOAD_PROTECTION_WINDOW = 2000L // Only allow reloads every 2 seconds
        
        // The tag key used to mark WebViews that have already been loaded
        private const val LOADED_STATE_KEY = "wiki_loaded_state"
    }
    
    private var lastLoadTime = 0L
    private var isFirstLoad = true
    
    /**
     * Check if the WebView has already been loaded with content
     */
    private fun isWebViewLoaded(view: WebView?): Boolean {
        return view?.getTag(R.string.prevent_reload_tag) == true
    }
    
    /**
     * Mark this WebView as having been loaded
     */
    private fun markWebViewAsLoaded(view: WebView?) {
        view?.setTag(R.string.prevent_reload_tag, true)
    }
    
    /**
     * Save the WebView state for proper restoration
     */
    fun saveWebViewState(webView: WebView): Bundle {
        val bundle = Bundle()
        webView.saveState(bundle)
        return bundle
    }
    
    /**
     * Restore the WebView state
     */
    fun restoreWebViewState(webView: WebView, bundle: Bundle) {
        webView.restoreState(bundle)
    }
    
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        
        // Skip checking for reloads if this is the first load for this WebView
        if (!isFirstLoad && view != null) {
            // If this isn't the first load and it's too soon since the last load,
            // and the WebView has already been loaded, block this reload
            val now = System.currentTimeMillis()
            if (now - lastLoadTime < RELOAD_PROTECTION_WINDOW && isWebViewLoaded(view)) {
                Log.d(TAG, "Blocking reload for $url - too soon after previous load")
                view.stopLoading()
                return
            }
        }
        
        // For valid loads, track the state and notify listener
        lastLoadTime = System.currentTimeMillis()
        onLoadingStateChanged(true)
        onErrorReceived(null) // Clear any previous errors
    }
    
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        
        // Check that the view exists and update its state
        if (view != null) {
            // For first loads, check if content loaded successfully using JavaScript
            if (isFirstLoad) {
                view.evaluateJavascript("""
                    (function() {
                        if (window.${'$'}tw && ${'$'}tw.wiki) {
                            return "loaded";
                        }
                        return document.body.innerHTML.length > 0 ? "content" : "empty";
                    })();
                """.trimIndent()) { result ->
                    when (result.trim('"')) {
                        "loaded", "content" -> {
                            isFirstLoad = false
                            
                            // Mark this WebView as loaded so we know to prevent reloads
                            markWebViewAsLoaded(view)
                            
                            // Notify that loading is complete and content is available
                            onLoadingStateChanged(false)
                            onPageLoaded(true)
                            
                            // Now install reload protection
                            injectReloadProtection(view)
                        }
                        else -> {
                            onLoadingStateChanged(false)
                            onPageLoaded(false)
                            onErrorReceived("Could not load wiki content")
                        }
                    }
                }
            } else {
                // For non-first loads, just update loading state
                onLoadingStateChanged(false)
            }
        } else {
            // If view is null somehow, update loading state anyway
            onLoadingStateChanged(false)
        }
    }
    
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            onErrorReceived("Error loading page: ${error?.description}")
            onLoadingStateChanged(false)
        }
    }
    
    /**
     * Prevent unwanted redirects that could cause reloads
     */
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        
        // Block same-page refreshes
        if (url == view?.url) {
            Log.d(TAG, "Blocking same-page refresh: $url")
            return true
        }
        
        // Prevent navigation to special URLs that would cause reloads
        if (url.contains("about:blank") || 
            url.contains("javascript:location.reload()") || 
            url.contains("javascript:window.location.reload()")) {
            Log.d(TAG, "Blocking reload URL: $url")
            return true
        }
        
        // Allow other navigation
        return false 
    }
    
    /**
     * Inject JavaScript to prevent unwanted reload attempts from within the page
     */
    private fun injectReloadProtection(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Basic reload prevention
                if (window.__reloadProtectionInstalled) {
                    return; // Already installed
                }
                
                // Block various reload methods
                window.location.reload = function() { 
                    console.log("[Reload blocked] location.reload()");
                    return false; 
                };
                window.stop = function() { 
                    console.log("[Reload blocked] window.stop()");
                    return false; 
                };
                
                // Block reload attempts through history API
                const originalPushState = history.pushState;
                history.pushState = function() {
                    console.log("[History API] Monitoring pushState");
                    return originalPushState.apply(this, arguments);
                };
                
                // TiddlyWiki-specific protections
                if (window.${'$'}tw && ${'$'}tw.wiki) {
                    // Block auto-refreshes from reload/syncing
                    const originalRefresh = ${'$'}tw.wiki.refresh;
                    ${'$'}tw.wiki.refresh = function(changes, source) {
                        if (source === 'load' || source === 'reload') {
                            console.log("[Reload blocked] ${'$'}tw.wiki.refresh from source: " + source);
                            return false;
                        }
                        return originalRefresh.apply(this, arguments);
                    };
                    
                    // Allow saving without reload
                    if (${'$'}tw.syncer) {
                        const originalLoadTiddler = ${'$'}tw.syncer.loadTiddler;
                        ${'$'}tw.syncer.loadTiddler = function(title) {
                            console.log("[Syncer] Loading tiddler without reload: " + title);
                            try {
                                return originalLoadTiddler.apply(this, arguments);
                            } catch (e) {
                                console.log("[Syncer] Error loading tiddler: " + e);
                                return null;
                            }
                        };
                    }
                }
                
                // Mark as installed
                window.__reloadProtectionInstalled = true;
                console.log("[Reload Protection] Successfully installed");
            })();
        """.trimIndent(), null)
    }
    
    /**
     * Interceptor for WebResource requests to block reload-triggering resources
     */
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        
        // Block reload-triggering resources
        if (url.contains("refresh.js") || url.contains("reload.js") || 
            url.contains("location.reload") || url.contains("document.reload")) {
            // Return empty response to block the resource
            return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
        }
        
        return null
    }
    
    /**
     * Reinforces reload protection when the WebView is resumed
     * (e.g., after coming back from being in the background)
     */
    fun reinforceReloadProtection(webView: WebView) {
        if (isWebViewLoaded(webView)) {
            injectReloadProtection(webView)
        }
    }
}