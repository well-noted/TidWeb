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
    val wikiKey = wiki.id ?: wiki.url
    var isLoading by remember { mutableStateOf(true) }
    var loadingProgress by remember { mutableStateOf(0f) }
    var loadStrategy by remember { mutableStateOf(WikiLoadStrategy.INITIALIZING) }
    var errorState by remember { mutableStateOf<String?>(null) }
    var webViewInitialized by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val wikiCache = remember { TiddlyWikiCache(context) }
    val wikiSplitter = remember { TiddlyWikiSplitter(context) }
    val fileManager = remember { WebViewFileManager(context) }
    var localFileUrl by remember { mutableStateOf<String?>(null) }
    
    // Simplified state tracking
    var isFirstLoad by remember { mutableStateOf(true) }
    var hasContent by remember { mutableStateOf(false) }
    var lastLoadTime by remember { mutableStateOf(0L) }

    // Add lifecycle observer to prevent premature cleanup
    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Don't cleanup WebView if we're just rotating or being backgrounded
                if (!activity?.isChangingConfigurations!!) {
                    viewModel.recycleWebView(wikiKey)
                }
            }
        }
        
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
                                    viewModel.getOrCreateWebView(wiki, context).loadUrl(url)
                                }
                            } ?: run {
                                val now = System.currentTimeMillis()
                                if (now - lastLoadTime > 5000) {
                                    lastLoadTime = now
                                    viewModel.getOrCreateWebView(wiki, context).loadUrl(wiki.url)
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
        
        AndroidView(
            factory = { ctx ->
                try {
                    viewModel.getOrCreateWebView(wiki, ctx).apply {
                        settings.apply {
                            // Essential settings
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            
                            // Always allow initial load
                            blockNetworkImage = false
                            loadsImagesAutomatically = true
                        }
                        
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                
                                // Allow first load, prevent subsequent reloads
                                if (!isFirstLoad) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastLoadTime < 2000) {
                                        view?.stopLoading()
                                        return
                                    }
                                }
                                
                                isLoading = true
                                errorState = null
                                lastLoadTime = System.currentTimeMillis()

                                // Only inject protection after first successful load
                                if (!isFirstLoad) {
                                    view?.evaluateJavascript("""
                                        (function() {
                                            window.location.reload = function() { return false; };
                                            window.stop = function() { return false; };
                                        })();
                                    """.trimIndent(), null)
                                }
                            }
                            
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                
                                if (isFirstLoad) {
                                    // Check if content loaded successfully
                                    view?.evaluateJavascript("""
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
                                                hasContent = true
                                                isLoading = false
                                                
                                                // Now install reload protection
                                                view.evaluateJavascript("""
                                                    (function() {
                                                        // Basic reload prevention
                                                        window.location.reload = function() { return false; };
                                                        window.stop = function() { return false; };
                                                        
                                                        if (window.${'$'}tw && ${'$'}tw.wiki) {
                                                            const originalRefresh = ${'$'}tw.wiki.refresh;
                                                            ${'$'}tw.wiki.refresh = function(changes, source) {
                                                                if (source === 'load' || source === 'reload') {
                                                                    return false;
                                                                }
                                                                return originalRefresh.apply(this, arguments);
                                                            };
                                                        }
                                                    })();
                                                """.trimIndent(), null)
                                            }
                                            else -> {
                                                isLoading = false
                                                errorState = "Could not load wiki content"
                                            }
                                        }
                                    }
                                } else {
                                    isLoading = false
                                }
                            }
                            
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    errorState = "Error loading page: ${error?.description}"
                                    isLoading = false
                                }
                            }
                            
                            // Prevent unwanted redirects
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString() ?: return false
                                return url == view?.url // Block same-page refreshes
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
                if (errorState == null && isFirstLoad) {
                    try {
                        webView.loadUrl(localFileUrl ?: wiki.url)
                    } catch (e: Exception) {
                        errorState = "Error loading URL: ${e.message}"
                        e.printStackTrace()
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Show loading overlay
        LoadingIndicator(isLoading)
    }
}