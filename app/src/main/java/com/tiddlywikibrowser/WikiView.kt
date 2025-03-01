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

@Composable
fun WikiViewComposable(wiki: WikiInstance, viewModel: WikiViewModel) {
    val wikiKey = wiki.id ?: wiki.url
    var isLoading by remember { mutableStateOf(true) }
    var loadingProgress by remember { mutableStateOf(0f) }
    var loadStrategy by remember { mutableStateOf(WikiLoadStrategy.INITIALIZING) }
    var errorState by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val wikiCache = remember { TiddlyWikiCache(context) }
    val wikiSplitter = remember { TiddlyWikiSplitter(context) }
    val fileManager = remember { WebViewFileManager(context) }
    var localFileUrl by remember { mutableStateOf<String?>(null) }
    
    // Progressive Loading System: First analyze wiki size, then determine loading strategy
    LaunchedEffect(wikiKey) {
        try {
            // Phase 1: Initialize and determine loading strategy based on wiki size
            viewModel.analyzeWikiSize(wiki).collect { strategy ->
                loadStrategy = strategy
                
                // Phase 2: Prepare optimized file loading based on wiki size
                withContext(Dispatchers.IO) {
                    when (strategy) {
                        WikiLoadStrategy.LARGE_WIKI -> {
                            // For very large wikis (>5MB), create a lightweight loader
                            try {
                                val loaderFile = wikiSplitter.createLightweightWikiLoader(wiki.url, context.cacheDir)
                                localFileUrl = loaderFile.absolutePath.toFileUrl
                            } catch (e: Exception) {
                                errorState = "Error loading large wiki: ${e.message}"
                                e.printStackTrace()
                            }
                        }
                        WikiLoadStrategy.MEDIUM_WIKI, WikiLoadStrategy.SMALL_WIKI -> {
                            try {
                                val url = fileManager.getLocalFileUrl(wiki.url)
                                localFileUrl = url
                            } catch (e: Exception) {
                                errorState = "Error loading wiki: ${e.message}"
                                e.printStackTrace()
                            }
                        }
                        else -> {}
                    }
                }
                
                // Phase 3: Pre-warm the WebView on a background thread
                if (errorState == null) {
                    ThreadManager.executeTask {
                        try {
                            viewModel.preloadWebView(wiki, context)
                        } catch (e: Exception) {
                            errorState = "Error preparing WebView: ${e.message}"
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            errorState = "Error analyzing wiki: ${e.message}"
            e.printStackTrace()
        }
    }
    
    // Cleanup WebView when not visible to prevent memory leaks
    DisposableEffect(wikiKey) {
        onDispose {
            ThreadManager.runOnBackground {
                viewModel.recycleWebView(wikiKey)
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Show error state if there's an error
        errorState?.let { error ->
            Box(
                modifier = Modifier.fillMaxSize(),
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
                            // Retry loading
                            localFileUrl?.let { url ->
                                viewModel.getOrCreateWebView(wiki, context).loadUrl(url)
                            } ?: viewModel.getOrCreateWebView(wiki, context).loadUrl(wiki.url)
                        }
                    ) {
                        Text("Retry")
                    }
                }
            }
            return@Box
        }
        
        AndroidView(
            factory = { ctx ->
                // Create or get WebView for this wiki with error handling
                try {
                    viewModel.getOrCreateWebView(wiki, ctx).apply {
                        // Configure the WebView for performance
                        settings.apply {
                            // Progressive loading settings
                            blockNetworkImage = loadStrategy == WikiLoadStrategy.LARGE_WIKI
                            loadsImagesAutomatically = loadStrategy == WikiLoadStrategy.SMALL_WIKI
                        }
                        
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                errorState = null
                                
                                // For large wikis, inject performance optimization scripts early
                                if (loadStrategy == WikiLoadStrategy.LARGE_WIKI || loadStrategy == WikiLoadStrategy.MEDIUM_WIKI) {
                                    ThreadManager.runOnBackground {
                                        view?.evaluateJavascript("""
                                            // Prevent layout thrashing
                                            (function() {
                                                try {
                                                    const style = document.createElement('style');
                                                    style.textContent = `
                                                        img { opacity: 0; transition: opacity 0.3s ease-in; }
                                                        .loaded { opacity: 1; }
                                                    `;
                                                    document.head.appendChild(style);
                                                    
                                                    // Progressive enhancement
                                                    if (window.requestIdleCallback) {
                                                        requestIdleCallback(() => {
                                                            document.body.style.visibility = 'visible';
                                                        });
                                                    }
                                                    return true;
                                                } catch (e) {
                                                    console.error('Error in optimization script:', e);
                                                    return false;
                                                }
                                            })();
                                        """.trimIndent(), null)
                                    }
                                }
                            }
                            
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                
                                // Apply optimizations based on wiki size
                                view?.let { webView -> 
                                    // Schedule optimization on a background thread
                                    ThreadManager.runOnBackground {
                                        try {
                                            viewModel.applyWikiSizeOptimizations(webView, loadStrategy)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                }
                            }
                            
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    errorState = "Error loading page: ${error?.description}"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    errorState = "Error creating WebView: ${e.message}"
                    e.printStackTrace()
                    // Return an empty WebView as a fallback
                    WebView(ctx)
                }
            },
            update = { webView ->
                if (errorState == null) {
                    // Use cached file if available, otherwise use original URL
                    val urlToLoad = localFileUrl ?: wiki.url
                    
                    // Only reload if URL has changed
                    if (webView.url != urlToLoad) {
                        try {
                            webView.loadUrl(urlToLoad)
                        } catch (e: Exception) {
                            errorState = "Error loading URL: ${e.message}"
                            e.printStackTrace()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Show loading overlay
        if (isLoading && errorState == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        
        // Enable images after page load completes with a delay to prevent janky scrolling
        LaunchedEffect(isLoading) {
            if (!isLoading && errorState == null) {
                // Wait a bit before enabling images to avoid jank during initial rendering
                kotlinx.coroutines.delay(300)
                try {
                    viewModel.enableImagesForWebView(wikiKey)
                } catch (e: Exception) {
                    errorState = "Error enabling images: ${e.message}"
                    e.printStackTrace()
                }
            }
        }
    }
}