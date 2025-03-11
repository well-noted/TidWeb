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
import android.view.View
import android.webkit.WebSettings

/**
 * A specialized WebViewClient that prevents TiddlyWiki from reloading unnecessarily.
 * This client marks a WebView as loaded after the first successful load and then
 * prevents future reloads by checking for a tag on the WebView.
 */
class ReloadBlockingWebViewClient(
    private val context: Context,
    private val wikiUrl: String,
    private val onLoadingStateChanged: (Boolean) -> Unit = {},
    private val onErrorReceived: (String) -> Unit = {},
    private val onPageLoaded: (Boolean) -> Unit = {}
) : WebViewClient() {

    private val TAG = "ReloadBlockingClient"
    private var isInitialPageStarted = false
    private var isInitialLoadFinished = false
    private var hasCheckedForContent = false
    private var hasReportedSuccess = false
    private var currentPageUrl: String? = null

    // Keep track of the last successful load time to prevent unnecessary reloads
    private var lastSuccessfulLoadTime = 0L
    private val RELOAD_THROTTLE_MS = 500L

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        Log.d(TAG, "onPageStarted: $url")
        
        // Critical for first load: save the URL to check later
        if (url != null && url != "about:blank") {
            currentPageUrl = url
            
            // Only mark as loading if this is the first load or an actual navigation
            if (!isInitialLoadFinished) {
                isInitialPageStarted = true
                onLoadingStateChanged(true)
            }
        }
        
        super.onPageStarted(view, url, favicon)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        // Let the WebView handle the URL if it's a navigation request
        return false
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        // Don't intercept requests - let them proceed normally
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        Log.d(TAG, "onPageFinished: $url")
        
        if (view == null || url == null || url == "about:blank") {
            super.onPageFinished(view, url)
            return
        }

        // Get the current state of the WebView - is it already marked as loaded?
        val isAlreadyLoaded = view.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
        
        if (isAlreadyLoaded) {
            // Reinforce scroll detection even for already loaded pages
            reinforceScrollDetection(view)
            
            // Continue with normal flow
            onLoadingStateChanged(false)
            super.onPageFinished(view, url)
            return
        }
        
        // For the initial load, we need to validate the content
        if (isInitialPageStarted && !hasCheckedForContent) {
            Log.d(TAG, "Initial load finished, checking for wiki content")
            
            // Mark that we've started checking for content
            hasCheckedForContent = true
            
            // Check if this is an actual TiddlyWiki with content after a short delay
            ThreadManager.runOnMainWithDelay(300) {
                if (view.isAttachedToWindow) {
                    checkForWikiContent(view, url)
                    // Add scroll detection after content check
                    reinforceScrollDetection(view)
                }
            }
        }
        
        super.onPageFinished(view, url)
    }

    /**
     * Check if the loaded content is a valid TiddlyWiki
     */
    private fun checkForWikiContent(webView: WebView, url: String?) {
        Log.d(TAG, "Checking for wiki content in: $url")
        
        // Execute JavaScript to check if this is a TiddlyWiki
        webView.evaluateJavascript("""
            (function() {
                // Look for TiddlyWiki indicators
                const hasTiddlyWikiElements = 
                    document.querySelector('#storeArea') !== null || 
                    document.querySelector('.tc-tiddler-frame') !== null ||
                    document.querySelector('.tc-story-river') !== null ||
                    (typeof window.${'$'}tw !== 'undefined');
                
                // Check for minimal HTML structure
                const hasMinimalHtml = 
                    document.querySelector('html') !== null && 
                    document.querySelector('head') !== null && 
                    document.querySelector('body') !== null;
                
                // Check if body has content
                const bodyContent = document.body ? document.body.textContent || '' : '';
                const hasBodyContent = bodyContent.length > 100;
                
                // Check if we have any meaningful content at all
                const hasContent = hasTiddlyWikiElements || (hasMinimalHtml && hasBodyContent);
                
                // Return result as JSON
                return JSON.stringify({
                    hasTiddlyWikiElements: hasTiddlyWikiElements,
                    hasMinimalHtml: hasMinimalHtml,
                    hasBodyContent: hasBodyContent,
                    hasContent: hasContent,
                    bodyLength: bodyContent.length
                });
            })();
        """.trimIndent()) { result ->
            try {
                // Process the result
                val cleanResult = result.replace("\"", "")
                    .replace("\\", "")
                    .removePrefix("{")
                    .removeSuffix("}")
                
                Log.d(TAG, "Content check result: $cleanResult")
                
                // Parse to see if we have TiddlyWiki content
                val hasContent = cleanResult.contains("hasContent:true")
                
                if (hasContent) {
                    // SUCCESS: We have a valid wiki with content
                    handleSuccessfulLoad(webView)
                } else {
                    // ERROR: No valid content found
                    val bodyLength = cleanResult.substringAfter("bodyLength:").substringBefore(",")
                    Log.d(TAG, "No valid wiki content found. Body length: $bodyLength")
                    
                    if (!hasReportedSuccess) {
                        onLoadingStateChanged(false)
                        onErrorReceived("No valid wiki content found")
                        onPageLoaded(false)
                    }
                }
                
            } catch (e: Exception) {
                // Error processing the result
                Log.e(TAG, "Error checking wiki content: ${e.message}")
                if (!hasReportedSuccess) {
                    onLoadingStateChanged(false)
                    onErrorReceived("Error checking content: ${e.message}")
                    onPageLoaded(false)
                }
            }
        }
    }

    /**
     * Handle a successful wiki load - apply state preservation
     */
    private fun handleSuccessfulLoad(webView: WebView) {
        if (hasReportedSuccess) return
        
        Log.d(TAG, "Successful wiki load - applying state preservation")
        
        // Mark this as a successful load
        hasReportedSuccess = true
        lastSuccessfulLoadTime = System.currentTimeMillis()
        
        // Mark the WebView as loaded to prevent future reloads
        webView.setTag(R.string.prevent_reload_tag, true)
        
        // Update UI state
        onLoadingStateChanged(false)
        onPageLoaded(true)
        
        // Apply reload protection
        reinforceReloadProtection(webView)
    }
    
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        
        // Only handle main frame errors
        if (request?.isForMainFrame == true) {
            Log.e(TAG, "Error loading page: ${error?.description}")
            onLoadingStateChanged(false)
            onErrorReceived("Error loading wiki: ${error?.description}")
            onPageLoaded(false)
        }
    }

    /**
     * Force a reload of the WebView by removing the prevention tag and loading the URL
     */
    fun forceReload(webView: WebView, url: String) {
        // Clear all state
        isInitialPageStarted = false
        isInitialLoadFinished = false
        hasCheckedForContent = false
        hasReportedSuccess = false
        
        // Remove the prevention tag to allow the reload
        webView.setTag(R.string.prevent_reload_tag, false)
        
        // Load the URL
        onLoadingStateChanged(true)
        webView.loadUrl(url)
    }

    /**
     * Apply JavaScript to prevent the wiki from reloading itself.
     * This is called after a successful load and during page resume.
     */
    fun reinforceReloadProtection(webView: WebView) {
        // Only apply protection if the WebView is marked as loaded
        if (webView.getTag(R.string.prevent_reload_tag) != true) {
            Log.d(TAG, "Skipping reload protection - WebView not marked as loaded")
            return
        }
        
        Log.d(TAG, "Applying reload protection")
        
        webView.evaluateJavascript("""
            (function() {
                // Check if protection is already applied
                if (window.__reloadProtectionApplied) return true;
                
                try {
                    // Override the reload function to prevent accidental reloads
                    if (window.location && typeof window.location.reload === 'function') {
                        const originalReload = window.location.reload;
                        window.location.reload = function(forceGet) {
                            console.log('Reload attempt intercepted');
                            if (forceGet === true && forceGet.source === 'TidWebInternal') {
                                console.log('Allowing internal reload');
                                originalReload.call(window.location, true);
                            }
                            return false;
                        };
                    }
                    
                    // For TiddlyWiki specifically - override the reloadPage function
                    if (window.${'$'}tw && window.${'$'}tw.wiki) {
                        const originalRefresh = window.${'$'}tw.wiki.refresh;
                        window.${'$'}tw.wiki.refresh = function() {
                            console.log('TW refresh - allowing but monitoring');
                            try {
                                return originalRefresh.apply(this, arguments);
                            } catch(e) {
                                console.error('Error in TW refresh:', e);
                                return false;
                            }
                        };
                    }
                    
                    // Mark protection as applied
                    window.__reloadProtectionApplied = true;
                    return true;
                } catch(e) {
                    console.error('Error applying reload protection:', e);
                    return false;
                }
            })();
        """.trimIndent(), null)
    }

    /**
     * Reinforce scroll detection to ensure UI state is properly managed
     */
    private fun reinforceScrollDetection(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Remove any existing scroll handler to avoid duplicates
                if (window.tidScrollHandler) {
                    document.removeEventListener('scroll', window.tidScrollHandler);
                    clearTimeout(window.scrollTimer);
                }
                
                // Initialize scroll tracking variables
                let lastScrollY = window.scrollY || 0;
                let lastScrollTime = 0;
                let scrollTimer = null;
                let isScrollingDown = false;
                let barState = true; // true = visible, false = hidden
                
                const scrollThreshold = 20; // Minimum pixels to trigger direction change
                const timeThreshold = 100; // Minimum ms between scroll events to process
                
                window.tidScrollHandler = function() {
                    const now = Date.now();
                    const scrollY = window.scrollY || 0;
                    
                    // Don't process every scroll event - throttle for performance
                    if (now - lastScrollTime < timeThreshold) return;
                    
                    // Clear any pending timer
                    clearTimeout(scrollTimer);
                    
                    // Determine scroll direction when moving significantly
                    if (Math.abs(scrollY - lastScrollY) > scrollThreshold) {
                        // Update the direction state
                        isScrollingDown = scrollY > lastScrollY;
                        
                        // Only change state if needed
                        if (isScrollingDown && barState) {
                            // Hide bars when scrolling down
                            barState = false;
                            window.ScrollInterface.onScroll(false);
                        } else if (!isScrollingDown && !barState) {
                            // Show bars when scrolling up
                            barState = true; 
                            window.ScrollInterface.onScroll(true);
                        }
                        
                        // Update tracking variables
                        lastScrollY = scrollY;
                        lastScrollTime = now;
                    }
                    
                    // Special case: Always show UI when at the top of the page
                    if (scrollY <= 5 && !barState) {
                        barState = true;
                        window.ScrollInterface.onScroll(true);
                    }
                };
                
                // Add the event listener with the stored handler
                document.addEventListener('scroll', window.tidScrollHandler, { passive: true });
                
                // Store reference to the timer
                window.scrollTimer = scrollTimer;
                
                // Initial state - show UI bars
                barState = true;
                window.ScrollInterface.onScroll(true);
                
                // Handle touch events to improve responsiveness
                document.addEventListener('touchstart', function() {
                    clearTimeout(scrollTimer);
                }, { passive: true });
                
                // Don't automatically show on touch end
                document.addEventListener('touchend', function() {
                    // No auto-show behavior, maintain the current state
                }, { passive: true });
                
                return true;
            })();
        """.trimIndent(), null)
    }
}