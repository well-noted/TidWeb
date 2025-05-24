package com.tiddlywikibrowser

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.webkit.CookieManager
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
    private var reloadProtectionInstalled = false
    private var contentDetectionAttempts = 0
    private val CONTENT_DETECTION_ATTEMPTS = 3 // Number of attempts to check for content
    private val CONTENT_DETECTION_DELAY = 800L // ms between content detection attempts
    
    // Add tracking for loading from cache
    private var loadedFromCache = false
    private var networkRequestsMade = 0
    private var networkResponsesReceived = 0

    // Keep track of the last successful load time to prevent unnecessary reloads
    private var lastSuccessfulLoadTime = 0L
    private val RELOAD_THROTTLE_MS = 500L

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        Log.d(TAG, "onPageStarted: $url")

        // Reset cache tracking on new page load
        loadedFromCache = false
        networkRequestsMade = 0
        networkResponsesReceived = 0

        // Critical for first load: save the URL to check later
        if (url != null && url != "about:blank") {
            currentPageUrl = url

            // Only mark as loading if this is the first load or an actual navigation
            if (!isInitialLoadFinished) {
                isInitialPageStarted = true
                onLoadingStateChanged(true)
            }
            
            // Save favicon when it's available
            if (favicon != null) {
                saveFavicon(url, favicon)
            }
        }

        super.onPageStarted(view, url, favicon)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (view == null || request == null) return false
        
        try {
            val url = request.url.toString()
            
            // Handle media URLs specially
            if (url.startsWith("blob:") || isMediaUrl(url)) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error in shouldOverrideUrlLoading: ${e.message}", e)
        }
        
        // Let the WebView handle the URL if it's a navigation request
        return false
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        
        // Count network requests for main resources
        if (request.isForMainFrame) {
            networkRequestsMade++
        }
        
        // Don't block download resources
        if (isDownloadableFileType(url)) {
            return null
        }
        
        // Don't block media resources
        if (isMediaUrl(url)) {
            return null
        }
        
        // Block reload-triggering resources
        if (url.contains("refresh.js") || url.contains("reload.js") || 
            url.contains("location.reload") || url.contains("document.reload")) {
            // Return empty response to block the resource
            return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
        }
        
        // Don't intercept requests - let them proceed normally
        return super.shouldInterceptRequest(view, request)
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        super.onReceivedHttpError(view, request, errorResponse)
        
        // If we get HTTP errors for main frame, it might indicate we're offline
        if (request?.isForMainFrame == true) {
            // Check if we might be offline
            ThreadManager.runOnMain {
                val viewModel = MainActivity.getViewModel(context)
                viewModel.setOfflineState(true)
                Log.d(TAG, "HTTP error for main frame - marking as offline")
            }
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        Log.d(TAG, "onPageFinished: $url")
        // Immediately hide the loading spinner when the page signals load complete
        onLoadingStateChanged(false)

        if (view == null || url == null || url == "about:blank") {
            super.onPageFinished(view, url)
            return
        }

        // Determine if loaded from cache
        determineIfLoadedFromCache(view)

        // Enable media features when page is loaded
        view.settings?.blockNetworkImage = false
        view.settings?.loadsImagesAutomatically = true
        view.settings?.mediaPlaybackRequiresUserGesture = false // Allow autoplay for media
        
        // Ensure cookies are saved when page is loaded (important for login persistence)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().flush()
        }
        
        // Inject media monitor script if available
        (context as? MainActivity)?.let { activity ->
            try {
                val field = activity::class.java.getDeclaredField("mediaMonitorScript")
                field.isAccessible = true
                val mediaMonitorScript = field.get(activity) as? String
                if (mediaMonitorScript != null) {
                    view.evaluateJavascript(mediaMonitorScript, null)
                }
                else {

                }
            } catch (e: Exception) {
                Log.d(TAG, "Media monitor script not available: ${e.message}")
            }
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

            // Reset content detection attempts counter
            contentDetectionAttempts = 0

            // Check if this is an actual TiddlyWiki with content after a short delay
            ThreadManager.runOnMainWithDelay(300) {
                if (view.isAttachedToWindow) {
                    checkForWikiContent(view)
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
    private fun checkForWikiContent(webView: WebView) {
        Log.d(TAG, "Checking for wiki content, attempt ${contentDetectionAttempts + 1}")

        // Delay evaluation slightly to give time for DOM to fully render
        ThreadManager.runOnMainWithDelay((100 + contentDetectionAttempts * 200).toLong()) {
            try {
                // Execute JavaScript to check if this is a TiddlyWiki
                webView.evaluateJavascript("""
                    (function() {
                        try {
                            // Check for TiddlyWiki specifically
                            if (window.${'$'}tw && window.${'$'}tw.wiki) {
                                return "tiddlywiki";
                            }
                            
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
                            
                            // Be more permissive on later attempts
                            if (bodyContent.length > 20 && ${contentDetectionAttempts} >= 1) {
                                return "minimal-content:" + bodyContent.length;
                            }
                            
                            // If document is still loading but has some content, give benefit of doubt
                            if (bodyContent.length > 0 && document.readyState !== 'complete') {
                                return "loading-content:" + document.readyState;
                            }
                            
                            // Check if we're getting raw HTML that just needs time to render
                            if (bodyContent.indexOf("<html") > -1 || bodyContent.indexOf("<!DOCTYPE") > -1) {
                                return "html-content";
                            }
                            
                            // Return result as JSON
                            return JSON.stringify({
                                hasTiddlyWikiElements: hasTiddlyWikiElements,
                                hasMinimalHtml: hasMinimalHtml,
                                hasBodyContent: hasBodyContent,
                                hasContent: hasContent,
                                bodyLength: bodyContent.length
                            });
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
                        Log.d(TAG, "Content evaluation attempt ${contentDetectionAttempts + 1}: $resultState")
                        
                        // Consider the page loaded if it has any kind of meaningful content
                        if (resultState.startsWith("tiddlywiki") || 
                            resultState.startsWith("content:") || 
                            resultState.startsWith("minimal-content:") ||
                            resultState.startsWith("loading-content") ||
                            resultState.startsWith("html-content") ||
                            resultState.startsWith("error-with-content") ||
                            resultState.contains("hasContent:true")) {
                            
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
                                    if (!hasReportedSuccess) {
                                        onLoadingStateChanged(false)
                                        onErrorReceived("No valid wiki content found")
                                        onPageLoaded(false)
                                    }
                                }
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
            } catch (e: Exception) {
                Log.e(TAG, "Exception during content evaluation: ${e.message}", e)
                
                // If we got an error evaluating JavaScript but the webview seems OK,
                // still try to be permissive rather than showing an error
                if (contentDetectionAttempts < CONTENT_DETECTION_ATTEMPTS - 1) {
                    contentDetectionAttempts++
                    checkForWikiContent(webView)
                } else {
                    // On final attempt with error, just assume content is ok to prevent frustrating the user
                    handleSuccessfulLoad(webView)
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

        // Update offline status based on our detection
        updateOfflineState(loadedFromCache)

        // Force loading state to false to ensure spinner is dismissed
        ThreadManager.runOnMain {
            // Update UI state - ensure this runs on UI thread
            onLoadingStateChanged(false)
            onPageLoaded(true)
        }

        // Ensure cookies are persisted to disk when wiki content is loaded
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().flush()
        }

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
        contentDetectionAttempts = 0

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
                        
                        // Add special handling for video elements to support background playback
                        if (media.tagName.toLowerCase() === 'video') {
                            // Make sure video elements can play in background like audio
                            media.setAttribute('playsinline', 'true');
                            
                            // Enable background video playback
                            if (!media.hasAttribute('webkit-playsinline')) {
                                media.setAttribute('webkit-playsinline', 'true');
                            }
                            
                            // Critical for background playback on Android WebView
                            media.setAttribute('x-webkit-airplay', 'allow');
                            
                            // Set critical flags for Android WebView
                            if (!media.hasAttribute('crossorigin')) {
                                media.setAttribute('crossorigin', 'anonymous');
                            }
                            
                            // Ensure controls are enabled for better user experience
                            media.controls = true;
                            
                            // Disable picture-in-picture auto-exit when backgrounded
                            if ('disablePictureInPicture' in media) {
                                media.disablePictureInPicture = false;
                            }
                            
                            // Critical enhancement to prevent system from automatically pausing video
                            if (!media.__videoBackgroundPlaybackEnhanced) {
                                media.__videoBackgroundPlaybackEnhanced = true;
                                
                                // Flag to track when video should be playing
                                media.__shouldBePlaying = !media.paused && !media.ended;
                                
                                // Listen for pause events that might be caused by the system
                                const originalPause = media.pause;
                                media.pause = function() {
                                    console.log('[Video] Pause called');
                                    // If we're in background and this video should be playing,
                                    // try to prevent the pause
                                    if (document.visibilityState === 'hidden' && media.__shouldBePlaying) {
                                        console.log('[Video] Preventing system pause');
                                        // Don't actually pause - just pretend we did
                                        return undefined;
                                    }
                                    
                                    // If this is a deliberate pause, update our tracking flag
                                    if (document.visibilityState === 'visible') {
                                        media.__shouldBePlaying = false;
                                    }
                                    
                                    // Call the original pause method
                                    return originalPause.apply(this, arguments);
                                };
                                
                                // Keep track of when the video should be playing
                                media.addEventListener('play', function() {
                                    media.__shouldBePlaying = true;
                                    console.log('[Video] Play event - setting shouldBePlaying=true');
                                });
                                
                                media.addEventListener('ended', function() {
                                    media.__shouldBePlaying = false;
                                    console.log('[Video] Ended event - setting shouldBePlaying=false');
                                });
                                
                                // Track video playback events for media session
                                media.addEventListener('play', function() {
                                    if (window.MediaInterface) {
                                        window.MediaInterface.onMediaEvent(
                                            'play',
                                            media.id || 'video-' + Math.random().toString(36).substring(2, 9),
                                            media.currentTime || 0,
                                            media.duration || 0,
                                            media.src || '',
                                            media.getAttribute('title') || 'TiddlyWiki Video'
                                        );
                                    }
                                });
                                
                                media.addEventListener('pause', function() {
                                    if (window.MediaInterface) {
                                        window.MediaInterface.onMediaEvent(
                                            'pause',
                                            media.id || 'unknown',
                                            media.currentTime || 0,
                                            media.duration || 0,
                                            media.src || '',
                                            media.getAttribute('title') || 'TiddlyWiki Video'
                                        );
                                    }
                                });
                                
                                media.addEventListener('timeupdate', function() {
                                    if (window.MediaInterface && media.paused === false) {
                                        window.MediaInterface.onMediaEvent(
                                            'timeupdate',
                                            media.id || 'unknown',
                                            media.currentTime || 0,
                                            media.duration || 0,
                                            media.src || '',
                                            media.getAttribute('title') || 'TiddlyWiki Video'
                                        );
                                    }
                                });
                            }
                        }
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
                    
                    // Mark protection as applied
                    window.__reloadProtectionApplied = true;
                    return true;
                } catch(e) {
                    console.error('Error applying reload protection:', e);
                    return false;
                }
            })();
        """.trimIndent(), null)
        
        // Apply video background playback detection script
        webView.evaluateJavascript("""
            (function() {
                // Skip if already initialized
                if (window.__videoBackgroundPlaybackInitialized) return true;
                
                // Create a flag to track if ExoPlayer routing is enabled
                // This enables background media control support
                window.__useExoPlayerForMedia = true;
                
                // Function to intercept media play events and route through ExoPlayer
                function interceptMediaPlayback(mediaElement) {
                    if (!mediaElement || mediaElement.__exoPlayerIntercepted) return;
                    
                    // Mark as intercepted
                    mediaElement.__exoPlayerIntercepted = true;
                    
                    // Store the original play method
                    const originalPlay = mediaElement.play.bind(mediaElement);
                    
                    // Override the play method
                    mediaElement.play = function() {
                        console.log('[Media Intercept] Play called on:', mediaElement.src);
                        
                        // If we have ExoPlayer interface and a valid source, route through ExoPlayer
                        if (window.__useExoPlayerForMedia && window.ExoPlayerInterface && mediaElement.src && 
                            !mediaElement.src.startsWith('blob:') && !mediaElement.src.startsWith('data:')) {
                            
                            console.log('[Media Intercept] Routing to ExoPlayer:', mediaElement.src);
                            
                            // Hide controls on the WebView media element since ExoPlayer will handle playback
                            mediaElement.style.visibility = 'hidden';
                            mediaElement.style.height = '0';
                            
                            // Get media title
                            const title = mediaElement.getAttribute('title') || 
                                         mediaElement.closest('.tc-tiddler-frame')?.querySelector('.tc-title')?.textContent || 
                                         'TiddlyWiki Media';
                            
                            // Notify MediaInterface about metadata
                            if (window.MediaInterface) {
                                window.MediaInterface.onMediaEvent(
                                    'loadedmetadata',
                                    mediaElement.id || 'media-' + Math.random().toString(36).substring(2, 9),
                                    0,
                                    mediaElement.duration || 0,
                                    mediaElement.src,
                                    title
                                );
                            }
                            
                            // Play through ExoPlayer
                            window.ExoPlayerInterface.playMedia(mediaElement.src);
                            
                            // Return a resolved promise to satisfy the play() API
                            return Promise.resolve();
                        } else {
                            // Fallback to normal playback
                            return originalPlay();
                        }
                    };
                    
                    // Also intercept pause to ensure ExoPlayer stops
                    const originalPause = mediaElement.pause.bind(mediaElement);
                    mediaElement.pause = function() {
                        console.log('[Media Intercept] Pause called');
                        
                        // If using ExoPlayer, we need to pause it through media controls
                        if (window.__useExoPlayerForMedia && window.ExoPlayerInterface && mediaElement.style.visibility === 'hidden') {
                            // The pause will be handled by MediaSession controls
                            console.log('[Media Intercept] Pause should be handled by MediaSession');
                        }
                        
                        // Always call original pause
                        return originalPause();
                    };
                }
                
                // Create a MutationObserver to detect new video elements
                const videoObserver = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        if (mutation.addedNodes && mutation.addedNodes.length > 0) {
                            for (let i = 0; i < mutation.addedNodes.length; i++) {
                                const node = mutation.addedNodes[i];
                                // Check direct node if it's a video or audio
                                if (node.tagName && (node.tagName.toLowerCase() === 'video' || node.tagName.toLowerCase() === 'audio')) {
                                    enableBackgroundPlayback(node);
                                    interceptMediaPlayback(node);
                                }
                                // Check if the added node contains videos or audio
                                if (node.querySelectorAll) {
                                    const mediaElements = node.querySelectorAll('video, audio');
                                    mediaElements.forEach(function(media) {
                                        enableBackgroundPlayback(media);
                                        interceptMediaPlayback(media);
                                    });
                                }
                            }
                        }
                    });
                });
                
                // Start observing the document with configured parameters
                videoObserver.observe(document.documentElement, {
                    childList: true,
                    subtree: true
                });
                
                // Process any existing videos and audio elements
                document.querySelectorAll('video, audio').forEach(function(media) {
                    enableBackgroundPlayback(media);
                    interceptMediaPlayback(media);
                });
                
                // Function to enable background playback for a video element
                function enableBackgroundPlayback(videoElement) {
                    if (!videoElement || videoElement.__backgroundPlaybackEnabled) return;
                    
                    // Mark this video as processed
                    videoElement.__backgroundPlaybackEnabled = true;
                    
                    // Enable inline playback
                    videoElement.setAttribute('playsinline', 'true');
                    videoElement.setAttribute('webkit-playsinline', 'true');
                    videoElement.setAttribute('x-webkit-airplay', 'allow');
                    
                    // Set critical flags for Android WebView
                    if (!videoElement.hasAttribute('crossorigin')) {
                        videoElement.setAttribute('crossorigin', 'anonymous');
                    }
                            
                    // Ensure controls are enabled for better user experience
                    videoElement.controls = true;
                    
                    // Disable picture-in-picture auto-exit
                    if ('disablePictureInPicture' in videoElement) {
                        videoElement.disablePictureInPicture = false;
                    }
                    
                    // Add data to identify video for background service
                    videoElement.setAttribute('data-background-playback', 'enabled');
                    
                    // Override pause to prevent system from pausing during background playback
                    if (!videoElement.__pauseOverridden) {
                        videoElement.__pauseOverridden = true;
                        videoElement.__shouldBePlaying = !videoElement.paused && !videoElement.ended;
                        
                        const originalPause = videoElement.pause;
                        videoElement.pause = function() {
                            // If we're in background and this video should be playing,
                            // prevent the pause
                            if (document.visibilityState === 'hidden' && videoElement.__shouldBePlaying) {
                                console.log('[Background Video] Preventing system pause');
                                return undefined;
                            }
                            
                            // If this is a deliberate pause, update our tracking flag
                            if (document.visibilityState === 'visible') {
                                videoElement.__shouldBePlaying = false;
                            }
                            
                            // Call the original pause method
                            return originalPause.apply(this, arguments);
                        };
                        
                        // Track play/ended states
                        videoElement.addEventListener('play', function() {
                            videoElement.__shouldBePlaying = true;
                        });
                        
                        videoElement.addEventListener('ended', function() {
                            videoElement.__shouldBePlaying = false;
                        });
                        
                        // Add visibilitychange handler to resume if paused in background
                        if (!window.__videoVisibilityHandlerAdded) {
                            window.__videoVisibilityHandlerAdded = true;
                            
                            document.addEventListener('visibilitychange', function() {
                                if (document.visibilityState === 'visible') {
                                    // When page becomes visible again, resume any videos that should be playing
                                    document.querySelectorAll('video[data-background-playback="enabled"]').forEach(function(video) {
                                        if (video.__shouldBePlaying && video.paused) {
                                            try {
                                                console.log('[Background Video] Resuming after visibility change');
                                                video.play().catch(e => console.error('Failed to resume video:', e));
                                            } catch(e) {
                                                console.error('[Background Video] Error resuming:', e);
                                            }
                                        }
                                    });
                                }
                            });
                        }
                    }
                    
                    // Log for debugging
                    console.log('[Background Video] Enabled for:', videoElement.src || 'video element');
                }
                
                // Mark as initialized
                window.__videoBackgroundPlaybackInitialized = true;
                return true;
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
                
                const scrollThreshold = 1; // Minimum pixels to trigger direction change
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
    
    /**
     * Cancels any ongoing loads and resets the loading state
     * Call this when cleaning up to ensure we don't have dangling state
     */
    fun cancelLoading() {
        if (isInitialPageStarted) {
            isInitialPageStarted = false
            onLoadingStateChanged(false)
        }
        
        // Use the current WebView if available
        val currentWebView = (context as? MainActivity)?.getCurrentWebView()
        currentWebView?.let { webView ->
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
     * Determine if the page was loaded from cache by analyzing various signals
     */
    private fun determineIfLoadedFromCache(webView: WebView) {
        try {
            // Check WebView cache mode first
            val cacheMode = webView.settings.cacheMode
            if (cacheMode == WebSettings.LOAD_CACHE_ONLY) {
                loadedFromCache = true
                updateOfflineState(true)
                return
            }
            
            // If we had network responses, it's likely not from cache
            if (networkResponsesReceived > 0) {
                loadedFromCache = false
                return
            }
            
            // Check navigator.onLine status and network info
            webView.evaluateJavascript("""
                (function() {
                    try {
                        // Check navigator.onLine
                        const isOnline = navigator.onLine;
                        
                        // Check performance timing data
                        let loadTiming = {};
                        if (window.performance && window.performance.timing) {
                            const t = window.performance.timing;
                            loadTiming = {
                                redirectTime: t.redirectEnd - t.redirectStart,
                                dnsTime: t.domainLookupEnd - t.domainLookupStart,
                                connTime: t.connectEnd - t.connectStart,
                                responseTime: t.responseEnd - t.responseStart,
                                domTime: t.domComplete - t.domLoading
                            };
                        }
                        
                        // Zero DNS/Connect time can indicate a cached response
                        const networkTimeSuspiciouslyLow = 
                            loadTiming.dnsTime === 0 && 
                            loadTiming.connTime === 0 && 
                            loadTiming.responseTime < 10;
                        
                        return JSON.stringify({
                            online: isOnline,
                            timing: loadTiming,
                            cachedLikelihood: networkTimeSuspiciouslyLow ? 'high' : 'low'
                        });
                    } catch(e) {
                        return JSON.stringify({error: e.toString()});
                    }
                })();
            """.trimIndent()) { result ->
                try {
                    val jsonResult = result.trim('"').replace("\\\"", "\"")
                    if (jsonResult.contains("\"online\":false") || 
                        jsonResult.contains("\"cachedLikelihood\":\"high\"")) {
                        loadedFromCache = true
                        updateOfflineState(true)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking cache state: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error determining cache state: ${e.message}")
        }
    }
    
    /**
     * Update the offline state in the ViewModel
     */
    private fun updateOfflineState(isOffline: Boolean) {
        ThreadManager.runOnMain {
            val viewModel = MainActivity.getViewModel(context)
            viewModel.setOfflineState(isOffline)
            Log.d(TAG, "Offline state updated: $isOffline")
        }
    }

    /**
     * Save favicon to the ViewModel
     */
    private fun saveFavicon(url: String, favicon: Bitmap) {
        try {
            // Get the ViewModel from MainActivity
            val viewModel = MainActivity.getViewModel(context)
            
            // Convert URL to a consistent format for storage (remove fragments)
            val baseUrl = url.substringBefore('#')
            
            // Save the favicon to the ViewModel
            viewModel.updateFavicon(baseUrl, favicon)
            
            Log.d(TAG, "Favicon saved for: $baseUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save favicon: ${e.message}", e)
        }
    }
}