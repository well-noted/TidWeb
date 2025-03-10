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
        private const val CONTENT_DETECTION_ATTEMPTS = 3 // Number of attempts to check for content
        private const val CONTENT_DETECTION_DELAY = 800L // ms between content detection attempts
    }
    
    private var lastLoadTime = 0L
    private var isFirstLoad = true
    private val isLoading = AtomicBoolean(false)
    private var webViewRef: WeakReference<WebView>? = null
    private var reloadProtectionInstalled = false
    private var contentDetectionAttempts = 0
    
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
            // Reset content detection attempts counter
            contentDetectionAttempts = 0
            
            // Enable media features when page is loaded
            view.settings?.blockNetworkImage = false
            view.settings?.loadsImagesAutomatically = true
            view.settings?.mediaPlaybackRequiresUserGesture = false // Allow autoplay for media
            
            // Inject media monitor script if available
            (context as? MainActivity)?.let { activity ->
                view.evaluateJavascript(MainActivity.mediaMonitorScript, null)
            }
            
            // For first loads, check if content loaded successfully using JavaScript
            if (isFirstLoad) {
                checkForWikiContent(view)
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
                onErrorReceived("Error while loading page: ${e.message}")
            }
        }
    }
    
    /**
     * More robust content detection with multiple attempts
     */
    private fun checkForWikiContent(webView: WebView) {
        // Delay evaluation slightly to give time for DOM to fully render
        ThreadManager.runOnMainWithDelay((100 + contentDetectionAttempts * 200).toLong()) {
            try {
                webView.evaluateJavascript("""
                    (function() {
                        try {
                            // Check for TiddlyWiki specifically
                            if (window.${'$'}tw && window.${'$'}tw.wiki) {
                                return "tiddlywiki";
                            }
                            
                            // Check document readiness
                            var readyState = document.readyState;
                            
                            // Check for any content
                            var bodyContent = document.body ? (document.body.innerHTML || "") : "";
                            var contentLength = bodyContent.length;
                            
                            // Look for common TiddlyWiki indicators even if tw isn't ready
                            if (bodyContent.indexOf("TiddlyWiki") > -1 || 
                                (document.querySelector && (
                                    document.querySelector(".tc-tiddler-frame") !== null ||
                                    document.querySelector("[data-tiddler-title]") !== null ||
                                    document.querySelector("#storeArea") !== null))
                               ) {
                                return "tiddlywiki-content";
                            }
                            
                            // Accept any page with reasonable content
                            if (contentLength > 100) {
                                return "content:" + contentLength;
                            }
                            
                            // Be more permissive on later attempts
                            if (contentLength > 20 && ${contentDetectionAttempts} >= 1) {
                                return "minimal-content:" + contentLength;
                            }
                            
                            // If document is still loading but has some content, give benefit of doubt
                            if (contentLength > 0 && readyState !== 'complete') {
                                return "loading-content:" + readyState;
                            }
                            
                            // Check if we're getting raw HTML that just needs time to render
                            if (bodyContent.indexOf("<html") > -1 || bodyContent.indexOf("<!DOCTYPE") > -1) {
                                return "html-content";
                            }
                            
                            // Return detailed info about the current state
                            return "empty:" + readyState + ":" + contentLength;
                        } catch (e) {
                            console.log("[Error checking content]", e);
                            // If there's an error but we have content, still consider it loaded
                            try {
                                return document.body && document.body.innerHTML.length > 0 
                                    ? "error-with-content:" + document.body.innerHTML.length 
                                    : "error:" + e.message;
                            } catch(e2) {
                                return "critical-error";
                            }
                        }
                    })();
                """.trimIndent()) { result ->
                    try {
                        val resultState = result.trim('"')
                        Log.d(TAG, "Content evaluation attempt ${contentDetectionAttempts + 1}: $resultState for URL: ${webView.url}")
                        
                        // Consider the page loaded if it has any kind of meaningful content
                        if (resultState.startsWith("tiddlywiki") || 
                            resultState.startsWith("content:") || 
                            resultState.startsWith("minimal-content:") ||
                            resultState.startsWith("loading-content") ||
                            resultState.startsWith("html-content") ||
                            resultState.startsWith("error-with-content")) {
                            
                            handleSuccessfulLoad(webView)
                        } else if (contentDetectionAttempts < CONTENT_DETECTION_ATTEMPTS - 1) {
                            // Try again after a delay if we haven't reached max attempts
                            contentDetectionAttempts++
                            checkForWikiContent(webView)
                        } else {
                            // One final check after the maximum attempts
                            webView.evaluateJavascript("""
                                (function() {
                                    try {
                                        // Final check - be very permissive
                                        var hasAnyContent = document.body && document.body.innerHTML.length > 0;
                                        var hasTiddlyWikiKeywords = document.documentElement.innerHTML.indexOf("TiddlyWiki") > -1;
                                        var hasStoreArea = document.getElementById("storeArea") !== null;
                                        var hasTiddlers = document.querySelector("[data-tiddler-title]") !== null;
                                        
                                        // If we have any TiddlyWiki indicators, accept the content
                                        if (hasTiddlyWikiKeywords || hasStoreArea || hasTiddlers) {
                                            return "final-tw-check:pass";
                                        }
                                        
                                        // If we have any content at all on final check, accept it
                                        return hasAnyContent ? "final-content-check:pass" : "final-check:fail";
                                    } catch(e) {
                                        // Even on error, if we can detect HTML, consider it a pass
                                        return document.documentElement ? "final-doc-check:pass" : "final-check:error";
                                    }
                                })();
                            """.trimIndent()) { finalResult ->
                                val finalState = finalResult.trim('"')
                                Log.d(TAG, "Final content check: $finalState")
                                
                                if (finalState.endsWith(":pass")) {
                                    // Accept the content on final check
                                    handleSuccessfulLoad(webView)
                                } else {
                                    // We've tried everything - report failure
                                    isLoading.set(false)
                                    ThreadManager.runOnMain {
                                        onLoadingStateChanged(false)
                                        onPageLoaded(false)
                                        onErrorReceived("Could not load wiki content")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling page state: ${e.message}", e)
                        isLoading.set(false)
                        ThreadManager.runOnMain {
                            onLoadingStateChanged(false)
                            onErrorReceived("Error evaluating page content: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during content evaluation: ${e.message}", e)
                
                // If we got an error evaluating JavaScript but the webview seems OK,
                // still try to be permissive rather than showing an error
                if (contentDetectionAttempts < CONTENT_DETECTION_ATTEMPTS - 1) {
                    contentDetectionAttempts++
                    checkForWikiContent(webView)
                } else {
                    isLoading.set(false)
                    ThreadManager.runOnMain {
                        onLoadingStateChanged(false)

                        // On final attempt with error, just assume content is ok to prevent frustrating the user
                        handleSuccessfulLoad(webView)
                    }
                }
            }
        }
    }
    
    /**
     * Handle a successful content load
     */
    private fun handleSuccessfulLoad(webView: WebView) {
        isFirstLoad = false
        
        // Mark this WebView as loaded so we know to prevent reloads
        markWebViewAsLoaded(webView)
        
        // Notify that loading is complete and content is available
        isLoading.set(false)
        ThreadManager.runOnMain {
            onLoadingStateChanged(false)
            onPageLoaded(true)
            onErrorReceived(null) // Clear any previous errors
        }
        
        // Now install reload protection
        injectReloadProtection(webView)
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
     * Prevent unwanted redirects that could cause reloads, but allow downloads and navigation
     */
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (view == null || request == null) return false
        
        try {
            val url = request.url.toString()
            
            // Handle media URLs specially
            if (url.startsWith("blob:") ||
                isMediaUrl(url)) {
                Log.d(TAG, "Allowing media URL: $url")
                return false
            }
            
            // Allow download URLs to pass through
            if (isDownloadableFileType(url)) {
                // Safely access headers with null check
                val headers = request.requestHeaders
                val contentDisposition = headers?.get("Content-Disposition")
                if (contentDisposition?.contains("attachment") == true) {
                    Log.d(TAG, "Allowing download URL with attachment: $url")
                    return false
                }
                
                Log.d(TAG, "Allowing download URL: $url")
                return false
            }
            
            // Block same-page refreshes
            if (url == view.url) {
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
            
            // Allow wiki-internal navigation (fragments and TiddlyWiki navigation)
            if (url.contains("#") || isTiddlyWikiNavigation(url, view.url)) {
                Log.d(TAG, "Allowing wiki-internal navigation: $url")
                return false
            }
            
            // Allow navigation to regular HTTP/HTTPS URLs
            if ((url.startsWith("http://") || url.startsWith("https://")) && request.isForMainFrame) {
                Log.d(TAG, "Allowing normal navigation to: $url")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in shouldOverrideUrlLoading: ${e.message}", e)
        }
        
        // Allow any other navigation by default
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
     * Check if a URL is for media content
     */
    private fun isMediaUrl(url: String): Boolean {
        val mediaExtensions = arrayOf(
            ".mp3", ".mp4", ".m4a", ".wav", ".ogg", ".webm", ".flac", 
            ".aac", ".mov", ".mkv", ".avi"
        )
        
        val lowercaseUrl = url.lowercase()
        return mediaExtensions.any { lowercaseUrl.endsWith(it) } ||
               lowercaseUrl.contains("audio") ||
               lowercaseUrl.contains("video") ||
               lowercaseUrl.contains("media")
    }
    
    /**
     * Check if navigation is within the same TiddlyWiki
     */
    private fun isTiddlyWikiNavigation(newUrl: String, currentUrl: String?): Boolean {
        if (currentUrl == null) return false
        
        // If the base URL is the same (ignoring fragments), it's internal navigation
        val newUrlBase = newUrl.substringBefore('#')
        val currentUrlBase = currentUrl.substringBefore('#')
        
        return newUrlBase == currentUrlBase
    }
    
    /**
     * Inject JavaScript to prevent unwanted reload attempts from within the page
     * while allowing proper media handling
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
                        
                        // TiddlyWiki-specific protections - only if TW actually exists
                        if (window.${'$'}tw && window.${'$'}tw.wiki) {
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
                        
                        // Improve media handling
                        document.querySelectorAll('audio, video').forEach(function(media) {
                            media.addEventListener('error', function(e) {
                                console.error('Media error:', e.target.error);
                                if (window.MediaInterface) {
                                    window.MediaInterface.onMediaEvent(
                                        'error',
                                        e.target.id || 'unknown',
                                        e.target.currentTime || 0,
                                        e.target.duration || 0,
                                        e.target.src || '',
                                        e.target.getAttribute('title') || 'Error'
                                    );
                                }
                            });
                        });
                        
                        // Improve link handling
                        document.addEventListener('click', function(e) {
                            // Check if clicked element is a link
                            let link = e.target.closest('a');
                            if (link && link.href) {
                                // Special handling for in-wiki navigation
                                if (link.classList.contains('tc-tiddlylink')) {
                                    // Let internal wiki links work normally
                                    return true;
                                }
                            }
                        }, true);
                        
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
    
    /**
     * Forcibly reload the WebView - used by the retry button
     */
    fun forceReload(webView: WebView, url: String) {
        try {
            // Reset state tracking
            isFirstLoad = true
            contentDetectionAttempts = 0
            webView.setTag(R.string.prevent_reload_tag, false)
            
            // Clear any existing web content
            webView.loadUrl("about:blank")
            
            // Small delay to ensure blank page is loaded before attempting the real URL
            ThreadManager.runOnMainWithDelay(100) {
                Log.d(TAG, "Forcing reload of URL: $url")
                webView.loadUrl(url)
                
                // Update loading state
                isLoading.set(true)
                ThreadManager.runOnMain {
                    onLoadingStateChanged(true)
                    onErrorReceived(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during force reload: ${e.message}", e)
            ThreadManager.runOnMain {
                onErrorReceived("Error reloading page: ${e.message}")
                onLoadingStateChanged(false)
            }
        }
    }
}