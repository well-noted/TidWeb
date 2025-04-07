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
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings

    // Cache for WebViews - increased to handle larger wikis
    private val MAX_WEBVIEW_CACHE = 10
    private val webViewCache = mutableMapOf<String, WebView>()

    

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
    val stableKey = remember(wikiKey) { wikiKey }
    
    // Use LaunchedEffect for setup tasks that should run once per key change
    LaunchedEffect(stableKey) {
        // Allow some time for UI transitions before attempting to load the WebView
        delay(50)
        isActive = true
        
        // Set this wiki as active when the composable is shown
        WebViewCache.setCurrentActiveKey(wikiKey)
    }
    
    // Add LaunchedEffect to fetch local file URL based on wiki type
    LaunchedEffect(stableKey) {
        // Skip for local files - they should be loaded directly
        if (!wiki.isLocalFile && !wiki.url.startsWith("file://")) {
            try {
                // For non-local files, use network-first approach
                localFileUrl = fileManager.getLocalFileUrlNetworkFirst(wiki.url)
                Log.d("WikiView", "Network-first approach for ${wiki.url}, localFileUrl: $localFileUrl")
            } catch (e: Exception) {
                Log.e("WikiView", "Error loading with network-first approach: ${e.message}")
                
                // Fallback to cache-first approach if network-first fails
                try {
                    localFileUrl = fileManager.getLocalFileUrl(wiki.url)
                    Log.d("WikiView", "Fallback to cache-first for ${wiki.url}, localFileUrl: $localFileUrl")
                } catch (e: Exception) {
                    Log.e("WikiView", "Error during fallback to cache-first: ${e.message}")
                    // If both approaches fail, we'll use the original URL
                }
            }
        } else {
            // For local files, just use the URL directly
            Log.d("WikiView", "Local file detected, using direct URL: ${wiki.url}")
            // We leave localFileUrl as null for local files so wiki.url is used directly
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
                                
                                // Determine URL to load based on file type
                                val urlToLoad = if (wiki.isLocalFile || wiki.url.startsWith("file://")) {
                                    // For local files, use the wiki URL directly
                                    wiki.url
                                } else {
                                    // For network files, use cached URL if available
                                    localFileUrl ?: wiki.url
                                }
                                
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
                                
                                // For non-local files, attempt to refresh the network cache too
                                if (!wiki.isLocalFile && !wiki.url.startsWith("file://")) {
                                    scope.launch {
                                        try {
                                            // Get fresh content from network
                                            localFileUrl = fileManager.getLocalFileUrlNetworkFirst(wiki.url)
                                        } catch (e: Exception) {
                                            Log.e("WikiView", "Hard reload network refresh failed: ${e.message}")
                                            // If it fails, we'll use whatever localFileUrl already has
                                        }
                                    }
                                }
                                
                                // Small delay to let removal finish
                                ThreadManager.runOnMainWithDelay(100) {
                                    val urlToLoad = if (wiki.isLocalFile || wiki.url.startsWith("file://")) {
                                        // For local files, use the wiki URL directly
                                        wiki.url
                                    } else {
                                        // For network files, use cached URL if available
                                        localFileUrl ?: wiki.url
                                    }
                                    
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
                        Log.d("WikiView", "AndroidView Factory for: $wikiKey (Stable Key: $stableKey)")
                        
                        val mainActivity = ctx as? MainActivity
                        val isBackgroundEnabled = mainActivity?.isBackgroundEnabled?.value ?: false
                        
                        // Initialize webView directly instead of using lateinit
                        val webView: WebView = if (isBackgroundEnabled) {
                            Log.d("WikiView", "Background mode is ON. Trying to get WebView from BackgroundWebViewManager for key: $wikiKey")
                            val backgroundWebView = mainActivity?.backgroundWebViewManager?.getWebView(wikiKey)
                            if (backgroundWebView != null) {
                                Log.d("WikiView", "Found WebView in BackgroundWebViewManager for $wikiKey. Reusing it.")
                                // IMPORTANT: Remove from previous parent if exists
                                (backgroundWebView.parent as? ViewGroup)?.removeView(backgroundWebView)
                                // Ensure it's visible and resumed state is correct
                                backgroundWebView.visibility = View.VISIBLE
                                backgroundWebView.onResume()
                                // Mark as already having content to avoid unnecessary reloads triggered by state flags
                                hasContent = true
                                isLoading = false
                                backgroundWebView // Assign to webView
                            } else {
                                Log.d("WikiView", "WebView not found in BackgroundWebViewManager for $wikiKey. Falling back to standard creation.")
                                // Fallback to standard creation/cache retrieval
                                WebViewCache.getAndRestoreCachedWebView(
                            wikiKey,
                            newWebViewFactory = { 
                                viewModel.getOrCreateWebView(wiki, ctx).apply {
                                    setTag(R.string.prevent_reload_tag, false)
                                }
                                    }
                                ) // Assign to webView
                            }
                        } else {
                            Log.d("WikiView", "Background mode is OFF. Using standard WebView creation/retrieval for key: $wikiKey")
                            // Standard behavior when background mode is off
                            WebViewCache.getAndRestoreCachedWebView(
                                wikiKey,
                                newWebViewFactory = {
                                    viewModel.getOrCreateWebView(wiki, ctx).apply {
                                        setTag(R.string.prevent_reload_tag, false)
                                    }
                                }
                            ) // Assign to webView
                        }

                        // Ensure client is set up
                        if (webView.webViewClient !is ReloadBlockingWebViewClient) {
                             val client = ReloadBlockingWebViewClient(
                                context = ctx,
                                wikiUrl = wiki.url,
                                onLoadingStateChanged = { isLoadingValue ->
                                    isLoading = isLoadingValue
                                    if (!isLoadingValue) {
                                        errorState = null
                                    }
                                    
                                    // Apply background mode settings on every navigation state change
                                    if (isBackgroundEnabled && isLoadingValue) {
                                        Log.d("WikiView", "Applying background mode during navigation for $wikiKey")
                                        // This ensures background mode is applied during navigations within the wiki
                                        ThreadManager.runOnMain {
                                            WikiViewEnhancer.injectBackgroundRunningScript(webView)
                                        }
                                    }
                                },
                                onPageLoaded = { success ->
                                    isLoading = false
                                    hasContent = success
                                    lastLoadTime = System.currentTimeMillis()
                                    if (success) {
                                        WikiViewEnhancer.injectScrollDetectionScript(webView)
                                        WikiViewEnhancer.injectSmallScreenOptimizations(webView, ctx)
                                        WikiViewEnhancer.injectMediaFunctionalityScript(webView)
                                        if (isBackgroundEnabled) {
                                             WikiViewEnhancer.injectBackgroundRunningScript(webView)
                                        }
                                    }
                                },
                                onErrorReceived = { errorDesc ->
                                    isLoading = false
                                    errorState = errorDesc ?: "Failed to load wiki"
                                    hasContent = false
                                }
                             )
                             webView.webViewClient = client
                             webViewClientState.value = client
                        } else {
                             // If client already exists, ensure our state reference is updated
                             webViewClientState.value = webView.webViewClient as ReloadBlockingWebViewClient
                        }

                        // Apply essential WebView settings that might have been lost
                        webView.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true // Ensure database is enabled
                            allowFileAccess = true
                            
                            // IMPORTANT: Apply background mode settings early
                            // When background mode is enabled, we don't require user gesture for media playback
                            mediaPlaybackRequiresUserGesture = !isBackgroundEnabled
                            
                            // Apply specific background mode settings
                            if (isBackgroundEnabled) {
                                Log.d("WikiView", "Applying early background mode settings for $wikiKey")
                                // Set additional settings needed for background operation
                                setMediaPlaybackRequiresUserGesture(!isBackgroundEnabled) // Ensure this is set immediately
                                // Allow JavaScript to continue executing in background
                                setJavaScriptCanOpenWindowsAutomatically(true)
                            }
                            
                            setGeolocationEnabled(false) // Keep disabled
                            // Re-apply zoom settings etc. if needed
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            cacheMode = WebSettings.LOAD_DEFAULT // Load from network first, fallback to cache

                            // Apply text zoom based on screen size (important on resume)
                            val textZoom = ScreenUtils.getWebViewTextZoom(context)
                            setTextZoom(textZoom)
                            
                            // Force accessibility mode if needed
                            if (ScreenUtils.shouldForceWebViewZoom(context)) {
                                builtInZoomControls = true
                                displayZoomControls = false
                            }
                            
                            // Optimize for very small screens if needed
                            if (ScreenUtils.isVerySmallScreen(context)) {
                                layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                            }
                        }

                        // Initialize download listener
                        downloadManager.setupDownloadListener(webView)
                        
                        // Initial Load Logic (Only if not already loaded/restored from background)
                        val needsInitialLoad = (webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false) == false || webView.url == null || webView.url == "about:blank"
                        
                        if (needsInitialLoad && !hasContent) {
                            Log.d("WikiView", "WebView needs initial load for $wikiKey.")
                            
                            // Pre-inject background script if background mode is enabled
                            if (isBackgroundEnabled) {
                                Log.d("WikiView", "Pre-injecting background script for initial load")
                                WikiViewEnhancer.injectBackgroundRunningScript(webView)
                            }
                            
                            val urlToLoad = when {
                                wiki.isLocalFile || wiki.url.startsWith("file://") -> wiki.url
                                else -> localFileUrl ?: wiki.url // Use cached file URL if available
                            }
                            
                            Log.d("WikiView", "Loading URL: $urlToLoad")
                                webView.loadUrl(urlToLoad)
                            webView.setTag(R.string.prevent_reload_tag, true) // Mark as loading initiated
                        } else {
                             Log.d("WikiView", "WebView for $wikiKey already loaded or restored. Skipping initial load.")
                             // Ensure visibility and resume state even if not loading
                             webView.visibility = View.VISIBLE
                             webView.onResume()
                             // If it was restored from background, loading should be false
                             if (isBackgroundEnabled && mainActivity?.backgroundWebViewManager?.hasWebView(wikiKey) == true) {
                            isLoading = false
                             }
                        }
                        
                        return@AndroidView webView

                    } catch (e: Exception) {
                        Log.e("WikiView", "Error in AndroidView factory", e)
                        // Return a dummy view or handle error appropriately
                        android.widget.FrameLayout(ctx) // Placeholder FrameLayout on error
                    }
                },
                update = { view -> // Use 'view' as parameter name, it's the standard
                    // This block runs when the composable updates but the view instance is reused.
                    Log.d("WikiView", "AndroidView Update for: $wikiKey (isActive: $isActive)")
                    if (isActive) {
                        // Always ensure visibility
                        view.visibility = android.view.View.VISIBLE
                        
                        // Use safe cast to WebView before calling methods
                        (view as? WebView)?.let { webViewInstance ->
                            // Crucial: Call webView.onResume() here to ensure rendering resumes
                            Log.d("WikiView", "Calling webView.onResume() in update lambda for $wikiKey")
                            webViewInstance.onResume()
                            
                            // Reinforce reload protection
                        webViewClientState.value?.let { client ->
                                if (webViewInstance.getTag(R.string.prevent_reload_tag) == true) {
                                runCatching {
                                        Log.d("WikiView", "Reinforcing reload protection in update lambda for $wikiKey")
                                        // Pass the safely casted WebView instance
                                        client.reinforceReloadProtection(webViewInstance)
                                    }.onFailure { e ->
                                        Log.w("WikiView", "Error reinforcing reload protection in update lambda: ${e.message}")
                                    }
                                }
                            }
                        } ?: run {
                            // Log if the view passed to update is unexpectedly not a WebView
                            Log.w("WikiView", "Update lambda called but view is not a WebView? Type: ${view::class.simpleName}")
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