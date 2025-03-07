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
    
    // Simplified state tracking
    var isFirstLoad by remember(wiki.url) { mutableStateOf(true) }
    var hasContent by remember(wiki.url) { mutableStateOf(false) }
    var lastLoadTime by remember { mutableStateOf(0L) }
    
    // Key the webview by wiki URL to ensure recomposition on wiki change
    val webViewKey = remember(wiki.url) { wiki.url }
    
    // Create a client state that can be accessed in both factory and update lambdas
    val webViewClientState = remember(webViewKey) {
        mutableStateOf<ReloadBlockingWebViewClient?>(null)
    }

    // Add lifecycle observer to prevent premature cleanup
    DisposableEffect(webViewKey) {
        val activity = context as? ComponentActivity
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Don't cleanup WebView if we're just rotating or being backgrounded
                if (activity?.isChangingConfigurations != true) {
                    viewModel.recycleWebView(wikiKey)
                }
            }
            
            override fun onResume(owner: LifecycleOwner) {
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
        
        // Set this wiki as active when the composable is shown
        WebViewCache.setCurrentActiveKey(wikiKey)
        
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
        }
    }

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
                            errorState = null
                            isLoading = true
                            // Retry loading with reload protection
                            localFileUrl?.let { url ->
                                val now = System.currentTimeMillis()
                                if (now - lastLoadTime > 5000) { // Only allow reloads every 5 seconds
                                    lastLoadTime = now
                                    // Use normal loading for explicit user request
                                    viewModel.getOrCreateWebView(wiki, context)?.let { webView ->
                                        // Reset the tag to allow an actual reload
                                        webView.setTag(R.string.prevent_reload_tag, false)
                                        webView.loadUrl(url)
                                    }
                                }
                            } ?: run {
                                val now = System.currentTimeMillis()
                                if (now - lastLoadTime > 5000) {
                                    lastLoadTime = now
                                    // Use normal loading for explicit user request
                                    viewModel.getOrCreateWebView(wiki, context)?.let { webView ->
                                        // Reset the tag to allow an actual reload
                                        webView.setTag(R.string.prevent_reload_tag, false)
                                        webView.loadUrl(wiki.url)
                                    }
                                }
                            }
                        }
                    ) {
                        Text("Retry")
                    }
                }
            }
            return@Box
        }
        
        // Make webview keyed by wiki URL to force recomposition when wiki changes
        key(webViewKey) {
            AndroidView(
                factory = { ctx ->
                    try {
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
                                isLoading = loading
                            },
                            onErrorReceived = { error ->
                                errorState = error
                            },
                            onPageLoaded = { success ->
                                hasContent = success
                                isFirstLoad = false
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
                        
                        webView
                    } catch (e: Exception) {
                        errorState = "Error creating WebView: ${e.message}"
                        e.printStackTrace()
                        // Return an empty WebView as a fallback
                        WebView(ctx)
                    }
                },
                update = { webView ->
                    // Always ensure visibility - NO need to reload when switching wikis
                    webView.visibility = android.view.View.VISIBLE
                    
                    // CRITICAL: Do NOT call loadUrl() here in the update lambda 
                    // as it will trigger on every recomposition
                    
                    // We can safely ensure our reload protection is in place though
                    webViewClientState.value?.let { client ->
                        // Reinforce protection - this will only do something if the WebView is loaded
                        if (webView.getTag(R.string.prevent_reload_tag) == true) {
                            ThreadManager.runOnMain {
                                client.reinforceReloadProtection(webView)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // Show loading overlay
        LoadingIndicator(isLoading)
    }
}