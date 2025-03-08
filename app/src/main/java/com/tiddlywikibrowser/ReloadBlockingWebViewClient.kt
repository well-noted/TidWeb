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
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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
    }
    
    private var lastLoadTime = 0L
    private var isFirstLoad = true
    private val isLoading = AtomicBoolean(false)
    private var webViewRef: WeakReference<WebView>? = null
    private var reloadProtectionInstalled = false
    
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
        webViewRef = WeakReference(view)
    }
    
    /**
     * Save the WebView state for proper restoration
     */
    fun saveWebViewState(webView: WebView): Bundle {
        val bundle = Bundle()
        try {
            webView.saveState(bundle)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving WebView state: ${e.message}")
        }
        return bundle
    }
    
    /**
     * Restore the WebView state
     */
    fun restoreWebViewState(webView: WebView, bundle: Bundle) {
        try {
            webView.restoreState(bundle)
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring WebView state: ${e.message}")
        }
    }
    
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        // Use a safe implementation to prevent NPEs
        if (view == null) return
        
        super.onPageStarted(view, url, favicon)
        
        // Avoid multiple loading indicators
        if (isLoading.compareAndSet(false, true)) {
            // Only update loading state if we're actually loading
            ThreadManager.runOnMain {
                onLoadingStateChanged(true)
            }
        }
        
        // Skip checking for reloads if this is the first load for this WebView
        if (!isFirstLoad) {
            // If this isn't the first load and it's too soon since the last load,
            // and the WebView has already been loaded, block this reload
            val now = System.currentTimeMillis()
            if (now - lastLoadTime < RELOAD_PROTECTION_WINDOW && isWebViewLoaded(view)) {
                Log.d(TAG, "Blocking reload for $url - too soon after previous load")
                view.stopLoading()
                
                // Reset loading state since we stopped the load
                isLoading.set(false)
                ThreadManager.runOnMain {
                    onLoadingStateChanged(false)
                }
                return
            }
        }
        
        // For valid loads, track the state
        lastLoadTime = System.currentTimeMillis()
        webViewRef = WeakReference(view)
    }
    
    override fun onPageFinished(view: WebView?, url: String?) {
        // Use a safe implementation to prevent NPEs
        if (view == null) return
        
        super.onPageFinished(view, url)
        
        // Check that the view exists and update its state
        try {
            // For first loads, check if content loaded successfully using JavaScript
            if (isFirstLoad) {
                view.evaluateJavascript("""
                    (function() {
                        try {
                            if (window.${'$'}tw && ${'$'}tw.wiki) {
                                return "loaded";
                            }
                            return document.body.innerHTML.length > 0 ? "content" : "empty";
                        } catch (e) {
                            return "error";
                        }
                    })();
                """.trimIndent()) { result ->
                    try {
                        val resultState = result.trim('"')
                        when (resultState) {
                            "loaded", "content" -> {
                                isFirstLoad = false
                                
                                // Mark this WebView as loaded so we know to prevent reloads
                                markWebViewAsLoaded(view)
                                
                                // Notify that loading is complete and content is available
                                isLoading.set(false)
                                ThreadManager.runOnMain {
                                    onLoadingStateChanged(false)
                                    onPageLoaded(true)
                                    onErrorReceived(null) // Clear any previous errors
                                }
                                
                                // Now install reload protection
                                injectReloadProtection(view)
                            }
                            else -> {
                                isLoading.set(false)
                                ThreadManager.runOnMain {
                                    onLoadingStateChanged(false)
                                    onPageLoaded(false)
                                    onErrorReceived("Could not load wiki content")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling page finished state: ${e.message}", e)
                        isLoading.set(false)
                        ThreadManager.runOnMain {
                            onLoadingStateChanged(false)
                            onErrorReceived("Error evaluating page content")
                        }
                    }
                }
            } else {
                // For non-first loads, just update loading state
                isLoading.set(false)
                ThreadManager.runOnMain {
                    onLoadingStateChanged(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPageFinished: ${e.message}", e)
            isLoading.set(false)
            ThreadManager.runOnMain {
                onLoadingStateChanged(false)
                onErrorReceived("Error while loading page")
            }
        }
    }
    
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        
        if (request?.isForMainFrame == true) {
            isLoading.set(false)
            ThreadManager.runOnMain {
                onErrorReceived("Error loading page: ${error?.description}")
                onLoadingStateChanged(false)
            }
        }
    }
    
    /**
     * Prevent unwanted redirects that could cause reloads, but allow downloads
     */
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        
        // Allow download URLs to pass through
        if (url.startsWith("blob:") || 
            isDownloadableFileType(url) ||
            request.requestHeaders["Content-Disposition"]?.contains("attachment") == true) {
            Log.d(TAG, "Allowing download URL: $url")
            return false
        }
        
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
     * Check if the URL points to a downloadable file based on extension
     */
    private fun isDownloadableFileType(url: String): Boolean {
        val downloadExtensions = arrayOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".zip", ".rar", ".7z", ".tar", ".gz", ".apk",
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp",
            ".mp3", ".mp4", ".wav", ".avi", ".mov", ".mkv",
            ".txt", ".csv", ".json", ".xml", ".html", ".htm",
            ".exe", ".msi", ".dmg", ".iso"
        )
        
        val lowercaseUrl = url.lowercase()
        return downloadExtensions.any { lowercaseUrl.endsWith(it) }
    }
    
    /**
     * Inject JavaScript to prevent unwanted reload attempts from within the page
     */
    private fun injectReloadProtection(webView: WebView) {
        try {
            webView.evaluateJavascript("""
                (function() {
                    // Basic reload prevention
                    if (window.__reloadProtectionInstalled) {
                        return "already_installed"; // Already installed
                    }
                    
                    try {
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
                            if (!window.${'$'}tw.__originalRefresh) {
                                window.${'$'}tw.__originalRefresh = ${'$'}tw.wiki.refresh;
                                ${'$'}tw.wiki.refresh = function(changes, source) {
                                    if (source === 'load' || source === 'reload') {
                                        console.log("[Reload blocked] ${'$'}tw.wiki.refresh from source: " + source);
                                        return false;
                                    }
                                    return window.${'$'}tw.__originalRefresh.apply(this, arguments);
                                };
                            }
                            
                            // Allow saving without reload
                            if (${'$'}tw.syncer && !window.${'$'}tw.__originalLoadTiddler) {
                                window.${'$'}tw.__originalLoadTiddler = ${'$'}tw.syncer.loadTiddler;
                                ${'$'}tw.syncer.loadTiddler = function(title) {
                                    console.log("[Syncer] Loading tiddler without reload: " + title);
                                    try {
                                        return window.${'$'}tw.__originalLoadTiddler.apply(this, arguments);
                                    } catch (e) {
                                        console.log("[Syncer] Error loading tiddler: " + e);
                                        return null;
                                    }
                                };
                                
                                // Add safe mode to prevent auto-refresh
                                if (${'$'}tw.safeMode === undefined) {
                                    ${'$'}tw.safeMode = true;
                                    console.log("[${'$'}tw] Enabled TiddlyWiki safe mode");
                                }
                            }
                        }
                        
                        // Mark as installed
                        window.__reloadProtectionInstalled = true;
                        console.log("[Reload Protection] Successfully installed");
                        return "installed";
                    } catch (e) {
                        console.error("[Reload Protection] Error installing: " + e);
                        return "error: " + e.message;
                    }
                })();
            """.trimIndent()) { result ->
                reloadProtectionInstalled = result.contains("installed")
                Log.d(TAG, "Reload protection installation result: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting reload protection: ${e.message}", e)
        }
    }
    
    /**
     * Interceptor for WebResource requests to block reload-triggering resources
     */
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        
        // Don't block download resources
        if (isDownloadableFileType(url)) {
            return null
        }
        
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
    
    /**
     * Cancels any ongoing loads and resets the loading state
     * Call this when cleaning up to ensure we don't have dangling state
     */
    fun cancelLoading() {
        if (isLoading.compareAndSet(true, false)) {
            ThreadManager.runOnMain {
                onLoadingStateChanged(false)
            }
        }
        
        webViewRef?.get()?.let { webView ->
            if (webView.isAttachedToWindow) {
                try {
                    webView.stopLoading()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping WebView loading: ${e.message}")
                }
            }
        }
    }
}