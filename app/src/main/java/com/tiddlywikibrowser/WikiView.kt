package com.tiddlywikibrowser

import android.content.Context
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.animation.*
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import android.webkit.JavascriptInterface

// Add this import to resolve ReloadBlockingWebViewClient reference
import com.tiddlywikibrowser.ReloadBlockingWebViewClient

@Composable
fun LoadingIndicator(isVisible: Boolean) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun WikiViewComposable(wiki: WikiInstance, viewModel: WikiViewModel) {
    val wikiKey = wiki.idFromUrl ?: wiki.url
    var isLoading by remember { mutableStateOf(true) }
    var loadingProgress by remember { mutableStateOf(0f) }
    var loadStrategy by remember { mutableStateOf(WikiLoadStrategy.INITIALIZING) }
    var errorState by remember { mutableStateOf<String?>(null) }
    var webViewInitialized by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val wikiCache = remember { TiddlyWikiCache(context) }
    val wikiSplitter = remember { TiddlyWikiSplitter(context) }
    val fileManager = remember { WebViewFileManager(context) }
    val downloadManager = remember { WebViewDownloadManager(context) }
    var localFileUrl by remember { mutableStateOf<String?>(null) }

    // Track composable lifecycle and active state
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var isActive by remember { mutableStateOf(true) }

    // Create a stable key for this composable
    val stableKey = remember(wiki.url) { "${wiki.url}_${System.currentTimeMillis()}" }

    // Use LaunchedEffect for setup tasks that should run once per key change
    LaunchedEffect(stableKey) {
        // Allow some time for UI transitions before attempting to load the WebView
        delay(50)
        isActive = true

        // Set this wiki as active when the composable is shown
        WebViewCache.setCurrentActiveKey(wikiKey)

        // Pre-register this WebView in the cache system if not already there
        // This ensures consistent behavior between first load and subsequent loads
        viewModel.getOrCreateWebView(wiki, context).let { webView ->
            if (webView.getTag(R.string.prevent_reload_tag) != true) {
                Log.d("WikiView", "Pre-initializing WebView in cache for first load: $wikiKey")
                WebViewCache.cacheWebView(wikiKey, webView)
            }
        }
    }

    // Set current wiki and view model for download manager
    DisposableEffect(stableKey) {
        downloadManager.setCurrentWiki(wiki, viewModel)
        onDispose {
            // Only consider inactive if we're not in configuration change
            val activity = context as? ComponentActivity
            if (activity?.isChangingConfigurations != true) {
                isActive = false

                // Use a short delay to ensure we don't compete with new wiki's setup
                scope.launch {
                    delay(50)
                    // Check configuration changing state without directly accessing private property
                    if (!WebViewCache.isInConfigChange()) {
                        // Don't cache when the app is actually closing
                    }
                }
            }
        }
    }

    // Simplified state tracking - use rememberSaveable to preserve across config changes
    var isFirstLoad by rememberSaveable(wiki.url) { mutableStateOf(true) }
    var hasContent by rememberSaveable(wiki.url) { mutableStateOf(false) }
    var lastLoadTime by rememberSaveable { mutableStateOf(0L) }

    // Create a client state that can be accessed in both factory and update lambdas
    val webViewClientState = remember(stableKey) {
        mutableStateOf<ReloadBlockingWebViewClient?>(null)
    }

    // Add lifecycle observer to prevent premature cleanup
    DisposableEffect(stableKey) {
        val activity = context as? ComponentActivity
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Don't cleanup WebView if we're just rotating or being backgrounded
                if (activity?.isChangingConfigurations != true) {
                    // Log only to avoid excessive operations
                    Log.d("WikiView", "Activity stopping (not config change), saving WebView state")
                }
            }

            override fun onResume(owner: LifecycleOwner) {
                // When resuming, we need to ensure reload protection is in place
                ThreadManager.runOnMain {
                    webViewClientState.value?.let { client ->
                        try {
                            viewModel.getOrCreateWebView(wiki, context).let { webView ->
                                client.reinforceReloadProtection(webView)
                            }
                        } catch (e: Exception) {
                            Log.e("WikiView", "Failed to reinforce reload protection: ${e.message}")
                        }
                    }
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                // When pausing, save the WebView state immediately
                ThreadManager.runOnMain {
                    try {
                        WebViewCache.cacheWebView(wikiKey, viewModel.getOrCreateWebView(wiki, context))
                    } catch (e: Exception) {
                        Log.e("WikiView", "Failed to cache WebView on pause: ${e.message}")
                    }
                }
            }
        }

        // Register the observer
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Wrap the main content in a key to ensure proper recomposition
    key(stableKey) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Show error state if there's an error
            errorState?.let { error ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Error loading wiki: $error",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                return@Box
            }

            // Use stable key for AndroidView to prevent unnecessary recreations
            AndroidView(
                factory = { ctx ->
                    try {
                        val webView = viewModel.getOrCreateWebView(wiki, ctx)
                        webViewInitialized = true

                        // Create WebViewClient to handle page loads
                        val webViewClient = ReloadBlockingWebViewClient(
                            context = ctx,
                            wikiUrl = wiki.url,
                            onLoadingStateChanged = { loading ->
                                if (isActive) {
                                    isLoading = loading
                                    if (!loading) {
                                        hasContent = true
                                        isFirstLoad = false
                                    }
                                }
                            },
                            onErrorReceived = { errorMsg ->
                                if (isActive && !hasContent) {
                                    errorState = errorMsg
                                }
                            },
                            onPageLoaded = { success ->
                                if (isActive) {
                                    if (success) {
                                        // Successfully loaded
                                        hasContent = true
                                        isFirstLoad = false
                                    } else if (!hasContent) {
                                        // Failed to load and we don't have content
                                        errorState = "Could not load content"
                                    }
                                }
                            }
                        )

                        // Store the client for access in update lambda and lifecycle events
                        webViewClientState.value = webViewClient

                        // Set the client on the WebView
                        webView.webViewClient = webViewClient

                        // Check if this WebView needs to be loaded or if it's a restored one
                        val isAlreadyLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false

                        if (!isAlreadyLoaded && isFirstLoad) {
                            // Initial load needed - this is a new WebView
                            Log.d("WikiView", "Initial load needed for: $wikiKey")
                            lastLoadTime = System.currentTimeMillis()
                            isLoading = true

                            // Only load URL for new WebViews, not restored ones
                            ThreadManager.runOnMain {
                                val urlToLoad = localFileUrl ?: wiki.url
                                Log.d("WikiView", "Initial load of URL: $urlToLoad")
                                webView.loadUrl(urlToLoad)
                            }
                        } else {
                            // This WebView was restored, no need to reload
                            Log.d("WikiView", "Using restored WebView, no reload needed for: $wikiKey")
                            isLoading = false
                            isFirstLoad = false
                            hasContent = true

                            // Re-cache the state to ensure it's preserved
                            WebViewCache.cacheWebView(wikiKey, webView)

                            // Make sure the WebView shows its content properly
                            webView.invalidate()
                        }

                        // Apply screen adaptations for very small screens
                        if (ScreenUtils.isVerySmallScreen(ctx)) {
                            // Apply custom optimizations for flip phones and very small screens
                            WikiViewEnhancer.injectSmallScreenOptimizations(webView, ctx)
                        } else {
                            // Use standard scroll detection for normal screens
                            WikiViewEnhancer.injectScrollDetectionScript(webView)
                        }

                        webView
                    } catch (e: Exception) {
                        Log.e("WikiView", "Error creating WebView: ${e.message}", e)
                        errorState = "Error creating WebView: ${e.message}"
                        // Return an empty WebView as a fallback
                        WebView(ctx)
                    }
                },
                update = { webView ->
                    // Avoid any state updates during update if the composable is not active
                    if (isActive) {
                        // Always ensure visibility - NO need to reload when switching wikis
                        webView.visibility = android.view.View.VISIBLE

                        // We can safely ensure our reload protection is in place though
                        webViewClientState.value?.let { client ->
                            try {
                                client.reinforceReloadProtection(webView)
                            } catch (e: Exception) {
                                Log.e("WikiView", "Failed to reinforce reload protection during update: ${e.message}")
                            }
                        }
                        
                        // Ensure screen-specific optimizations are applied after updates
                        if (ScreenUtils.isVerySmallScreen(context)) {
                            WikiViewEnhancer.injectSmallScreenOptimizations(webView, context)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Show loading overlay
            if (isActive) {
                LoadingIndicator(isLoading)
            }
        }
    }
}

/**
 * Helper function to inject scroll detection script into WebView
 * This script monitors scroll events and triggers UI visibility changes
 */
private fun injectScrollDetectionScript(webView: WebView, viewModel: WikiViewModel) {
    webView.evaluateJavascript("""
        (function() {
            // Remove any existing scroll handler to avoid duplicates
            if (window.tidScrollHandler) {
                document.removeEventListener('scroll', window.tidScrollHandler);
                clearTimeout(window.scrollTimer);
            }
            
            // Improved scroll detection for hiding/showing UI
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
    """, null)

    // Also add a second JavaScript interface for scroll callbacks
    try {
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onScroll(showBars: Boolean) {
                ThreadManager.runOnMain {
                    viewModel.setFrameVisible(showBars)
                }
            }
        }, "ScrollInterface")
    } catch (e: Exception) {
        Log.e("WikiView", "Failed to add JavaScript interface", e)
    }
}

/**
 * Safe composable wrapper to prevent compose state exceptions
 * during rapid wiki transitions
 */
@Composable
fun WikiView(wiki: WikiInstance, viewModel: WikiViewModel) {
    // Handle any exceptions during composition to prevent app crashes
    var composeError by remember { mutableStateOf<String?>(null) }

    // Use proper if-else structure rather than if used as an expression
    if (composeError != null) {
        // Show error UI if there was a compose error
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Error displaying wiki. Please try again.",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    } else {
        // No error, display the actual wiki view
        LaunchedEffect(wiki.url) {
            composeError = null
        }

        // Directly call the composable - we'll use state to handle errors
        WikiViewComposable(wiki, viewModel)

        // Monitor for errors using an effect
        DisposableEffect(wiki.url) {
            // Create error handler to catch exceptions
            val errorHandler = CoroutineExceptionHandler { _, throwable ->
                Log.e("WikiView", "Error in WikiView composable: ${throwable.message}", throwable)
                composeError = throwable.message ?: "Unknown error displaying wiki"
            }

            // Launch a coroutine with our error handler to monitor for errors
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
            val job = scope.launch(errorHandler) {
                // This coroutine exists just to have a place to attach the error handler
                // It will be cancelled when the DisposableEffect is disposed
            }

            onDispose {
                job.cancel() // Cancel our monitoring job on dispose
            }
        }
    }
}