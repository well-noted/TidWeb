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
    
    // Create a stable key for this composable that doesn't change during its lifecycle
    // This helps prevent recomposition issues when switching between wikis quickly
    val stableKey = remember(wiki.url) { "${wiki.url}_${System.currentTimeMillis()}" }
    
    // Use LaunchedEffect for setup tasks that should run once per key change
    LaunchedEffect(stableKey) {
        // Allow some time for UI transitions before attempting to load the WebView
        delay(50)
        isActive = true
        
        // Set this wiki as active when the composable is shown
        WebViewCache.setCurrentActiveKey(wikiKey)
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
                    delay(100)
                    // Clean up only if the composable is truly gone, not just during recomposition
                    if (!isActive) {
                        Log.d("WikiView", "Cleaning up resources for: ${wiki.url}")
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
                    viewModel.recycleWebView(wikiKey)
                }
            }
            
            override fun onResume(owner: LifecycleOwner) {
                // Verify we're still the active wiki before restoring
                if (WebViewCache.getCurrentActiveKey() == wikiKey) {
                    // When the activity resumes, reinforce reload protection
                    ThreadManager.runOnMain {
                        webViewClientState.value?.let { client ->
                            viewModel.getOrCreateWebView(wiki, context)?.let { webView ->
                                client.reinforceReloadProtection(webView)
                                
                                // Re-inject the scroll detection script when resuming
                                injectScrollDetectionScript(webView, viewModel)
                            }
                        }
                    }
                }
            }
        }
        
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
        }
    }

    // Wrap the main content in a key to ensure proper recomposition
    key(stableKey) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Show error state if there's an error
            errorState?.let { error ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp)
                        )
                        Button(
                            onClick = {
                                // Set loading state first to show progress indicator
                                isLoading = true
                                errorState = null
                                
                                // Fetch the URL to load
                                val urlToLoad = localFileUrl ?: wiki.url
                                
                                // Use our new forceReload method that properly resets WebView state
                                viewModel.getOrCreateWebView(wiki, context)?.let { webView ->
                                    // Reset all state flags in both WebView and client
                                    webView.setTag(R.string.prevent_reload_tag, false)
                                    webViewClientState.value?.forceReload(webView, urlToLoad)
                                    
                                    // Also reset our compose state
                                    isFirstLoad = true 
                                    hasContent = false
                                    lastLoadTime = System.currentTimeMillis()
                                }
                            }
                        ) {
                            Text("Retry")
                        }
                        
                        // Add a secondary option for hard reload
                        TextButton(
                            onClick = {
                                // Completely recreate the WebView for a clean slate
                                WebViewCache.removeCachedWebView(wikiKey)
                                isLoading = true
                                errorState = null
                                isFirstLoad = true
                                hasContent = false
                                
                                // Small delay to let removal finish
                                ThreadManager.runOnMainWithDelay(100) {
                                    val urlToLoad = localFileUrl ?: wiki.url
                                    
                                    // This will create a fresh WebView since we just removed the cached one
                                    viewModel.getOrCreateWebView(wiki, context)?.let { webView ->
                                        webView.loadUrl(urlToLoad)
                                    }
                                }
                            }
                        ) {
                            Text("Hard Reload")
                        }
                    }
                }
                return@Box
            }
            
            // Use stable key for AndroidView to prevent unnecessary recreations
            AndroidView(
                factory = { ctx ->
                    try {
                        Log.d("WikiView", "Creating/retrieving WebView for: ${wiki.url}")
                        
                        // Check if there's a cached WebView first to avoid creating a new one
                        val webView = WebViewCache.getAndRestoreCachedWebView(
                            wikiKey,
                            newWebViewFactory = { 
                                // Only create a new WebView if necessary
                                viewModel.getOrCreateWebView(wiki, ctx).apply {
                                    // The WebView is new, so mark it for initial load
                                    setTag(R.string.prevent_reload_tag, false)
                                }
                            }
                        )
                        
                        // Apply essential WebView settings
                        webView.settings.apply {
                            // Essential settings
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            
                            // Always allow initial load
                            blockNetworkImage = false
                            loadsImagesAutomatically = true
                            
                            // Enable file download support
                            allowFileAccess = true
                            setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW)
                        }
                        
                        // Set up download listener
                        downloadManager.setupDownloadListener(webView)
                        
                        // Set WebView to visible
                        webView.visibility = android.view.View.VISIBLE
                        
                        // Create our custom WebViewClient that prevents reloads
                        val webViewClient = ReloadBlockingWebViewClient(
                            context = ctx,
                            wikiUrl = wiki.url,
                            onLoadingStateChanged = { loading ->
                                // Only update state if still active to prevent state updates
                                // after component disposal
                                if (isActive) {
                                    isLoading = loading
                                }
                            },
                            onErrorReceived = { error ->
                                if (isActive) {
                                    errorState = error
                                }
                            },
                            onPageLoaded = { success ->
                                if (isActive) {
                                    hasContent = success
                                    isFirstLoad = false
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
                            Log.d("WikiView", "Using restored WebView, no reload needed")
                            isLoading = false
                            isFirstLoad = false
                            hasContent = true
                            
                            // Make sure the WebView shows its content properly
                            webView.invalidate()
                        }
                        
                        // Inject scroll detection script for showing/hiding UI elements
                        injectScrollDetectionScript(webView, viewModel)
                        
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
                            // Reinforce protection - this will only do something if the WebView is loaded
                            if (webView.getTag(R.string.prevent_reload_tag) == true) {
                                // Use runCatching to prevent any exceptions from affecting the UI
                                runCatching {
                                    client.reinforceReloadProtection(webView)
                                }
                            }
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
            }
            
            // Improved scroll detection for hiding/showing UI
            let lastScrollY = window.scrollY || 0;
            let lastScrollTime = 0;
            let scrollTimer = null;
            const scrollThreshold = 20; // Minimum pixels to trigger direction change
            const timeThreshold = 100; // Minimum ms between scroll events to process
            
            window.tidScrollHandler = function() {
                const now = Date.now();
                const scrollY = window.scrollY || 0;
                
                // Don't process every scroll event - throttle for performance
                if (now - lastScrollTime < timeThreshold) return;
                
                // Determine scroll direction when moving significantly
                if (Math.abs(scrollY - lastScrollY) > scrollThreshold) {
                    const isScrollingDown = scrollY > lastScrollY;
                    
                    // Show when scrolling up, hide when scrolling down
                    // Use the JavascriptInterface to communicate with Android
                    window.ScrollInterface.onScroll(!isScrollingDown);
                    
                    lastScrollY = scrollY;
                    lastScrollTime = now;
                }
                
                // Also show UI when at the top of the page
                if (scrollY <= 5) {
                    window.ScrollInterface.onScroll(true);
                }
                
                // Auto-hide timer - when scrolling stops, show the UI again
                clearTimeout(scrollTimer);
                scrollTimer = setTimeout(function() {
                    window.ScrollInterface.onScroll(true);
                }, 3000);
            };
            
            // Add the event listener with the stored handler
            document.addEventListener('scroll', window.tidScrollHandler, { passive: true });
            
            // Initial state - show UI bars
            window.ScrollInterface.onScroll(true);
            
            // Handle touch events to improve responsiveness
            document.addEventListener('touchstart', function() {
                clearTimeout(scrollTimer);
            }, { passive: true });
            
            document.addEventListener('touchend', function() {
                // Wait a bit after touch ends to see if it becomes a scroll
                scrollTimer = setTimeout(function() {
                    window.ScrollInterface.onScroll(true);
                }, 1000);
            }, { passive: true });
            
            return true;
        })();
    """, null)
}

/**
 * Safe composable wrapper to prevent compose state exceptions
 * during rapid wiki transitions
 */
@Composable
fun WikiView(wiki: WikiInstance, viewModel: WikiViewModel) {
    // Handle any exceptions during composition to prevent app crashes
    var composeError by remember { mutableStateOf<String?>(null) }
    
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
        return
    }

}