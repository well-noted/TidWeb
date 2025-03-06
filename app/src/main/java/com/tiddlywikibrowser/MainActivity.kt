package com.tiddlywikibrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Snackbar
import androidx.compose.material.pullrefresh.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewAssetLoader
import com.google.accompanist.permissions.rememberPermissionState
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.ViewModel
import android.webkit.WebChromeClient
import android.webkit.JsResult
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import com.tiddlywikibrowser.model.TiddlerTemplate
import androidx.compose.ui.draw.alpha
import kotlin.math.absoluteValue
import androidx.compose.animation.AnimatedVisibilityScope

// Memory threshold for optimization (50MB)
private const val MEMORY_THRESHOLD = 50L * 1024L * 1024L

// WikiViewModel has been moved to separate file

// Wiki loading strategy enum already exists in WikiLoadStrategy.kt
// enum class WikiLoadStrategy {...}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private var webViewPaused = false
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkCheckTime = 0L
    private val NETWORK_CHECK_THROTTLE = 5000L
    internal lateinit var mediaSessionManager: MediaSessionManager
    internal lateinit var exoPlayerManager: ExoPlayerManager
    private var viewModel: WikiViewModel? = null
    private var serviceConnection: ServiceConnection? = null
    private val serviceIntent by lazy { Intent(this, MediaPlaybackService::class.java) }
    
    // Dialog state variables
    private var pendingSharedText by mutableStateOf<String?>(null)
    private var showWikiSelector by mutableStateOf(false)
    private var showAddDialog by mutableStateOf(false)
    private var showDeleteConfirmDialog by mutableStateOf(false)
    private var showShareMenu by mutableStateOf(false)
    private var showTagManagement by mutableStateOf(false)
    
    // Add these properties for error handling
    private var showLoadErrorDialog by mutableStateOf(false)
    private var loadErrorWiki: WikiInstance? = null

    // Add method to access current WebView
    fun getCurrentWebView(): WebView? {
        return viewModel?.currentWiki?.value?.let { wiki ->
            viewModel?.getOrCreateWebView(wiki, this)
        }
    }

    // Initialize non-critical components on background thread
    private fun initializeNonCriticalComponents() {
        // This is called from a background thread
        // Pre-warm caches, initialize background services, etc.
        ThreadManager.runOnBackground {
            CookieManager.getInstance().removeAllCookies(null)
            // Use main thread for WebView operations
            ThreadManager.runOnMain {
                // Create a temporary WebView to clear cache
                val tempWebView = WebView(applicationContext)
                tempWebView.clearCache(true)
                tempWebView.destroy()
            }
        }
    }

    companion object {
        private var viewModelInstance: WikiViewModel? = null

        internal fun getViewModel(context: Context): WikiViewModel {
            return viewModelInstance ?: synchronized(this) {
                viewModelInstance ?: let {
                    val factory = ViewModelFactory(context.applicationContext)
                    val viewModelProvider = ViewModelProvider(context as ComponentActivity, factory)
                    viewModelProvider[WikiViewModel::class.java].also { viewModelInstance = it }
                }
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        internal fun createWebView(context: Context): WebView {
            val webView = WebView(context.applicationContext)  // Use applicationContext to prevent memory leaks
            ThreadManager.runOnMain {
                try {
                    // Set layer type to hardware for better performance
                    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    
                    // Fix for orientation changes: set fixed layout size
                    webView.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        allowFileAccess = true
                        
                        // Fix for orientation: support viewport meta tag
                        useWideViewPort = true
                        loadWithOverviewMode = true

                        // Important setting for responsiveness
                        layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                        
                        // Safe way to set database path
                        try {
                            databasePath = context.getDir("database", Context.MODE_PRIVATE).path
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        // Optimize GPU rendering and resource loading
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        
                        // Set the proper dark mode setting on Android Q and above
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                forceDark = WebSettings.FORCE_DARK_AUTO
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        
                        // Performance improvements for memory usage
                        javaScriptEnabled = true
                        domStorageEnabled = true // Use modern cache APIs
                        setGeolocationEnabled(false) // Disable features we don't need
                        javaScriptCanOpenWindowsAutomatically = false
                        allowContentAccess = true
                        
                        // Optimize loading and parsing to reduce memory pressure
                        blockNetworkImage = true // Initially block images to improve first render
                        loadsImagesAutomatically = false // Control this manually for better performance
                        
                        // Safely enable file access from file URLs (required for local TiddlyWiki)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                            try {
                                javaClass.getMethod("setAllowFileAccessFromFileURLs", Boolean::class.java)
                                    .invoke(this, true)
                                javaClass.getMethod("setAllowUniversalAccessFromFileURLs", Boolean::class.java)
                                    .invoke(this, true)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    
                    // CRITICAL FIX: Override WebView's JS execution to be non-blocking
                    webView.evaluateJavascript("""
                        (function() {
                            // Use throttled and chunked processing for heavy operations
                            const originalSetTimeout = window.setTimeout;
                            window.setTimeout = function(callback, delay) {
                                if (delay < 10) delay = 10; // Minimum delay to avoid busy-waiting
                                return originalSetTimeout(callback, delay);
                            };
                            
                            // Ensure delayed initialization for TiddlyWiki components
                            window.addEventListener('DOMContentLoaded', function() {
                                // Set up rendering optimization
                                const style = document.createElement('style');
                                style.textContent = `
                                    * { transform: translateZ(0); }
                                    img:not([loading]) { loading: lazy; }
                                    .tc-tiddler-frame { contain: content; }
                                `;
                                document.head.appendChild(style);
                            });
                        })();
                    """, null)
                    
                    // CRITICAL FIX: Add viewport setting script for orientation changes
                    webView.evaluateJavascript("""
                        (function() {
                            // Ensure proper mobile viewport settings
                            let viewport = document.querySelector('meta[name="viewport"]');
                            if (!viewport) {
                                viewport = document.createElement('meta');
                                viewport.name = 'viewport';
                                document.head.appendChild(viewport);
                            }
                            viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes';
                            
                            // Fix for orientation changes: re-layout on orientation change
                            window.addEventListener('orientationchange', function() {
                                setTimeout(function() {
                                    const evt = new Event('resize');
                                    window.dispatchEvent(evt);
                                }, );
                            });
                            
                            // Throttle heavy operations
                            const originalSetTimeout = window.setTimeout;
                            window.setTimeout = function(callback, delay) {
                                if (delay < 10) delay = 10; // Minimum delay to avoid busy-waiting
                                return originalSetTimeout(callback, delay);
                            };
                        })();
                    """, null)
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Create a WebChromeClient to manage rendering lifecycle
            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    
                    // Gradually enable features as the page loads to prevent jank
                    if (newProgress > 50 && !view?.settings?.loadsImagesAutomatically!!) {
                        ThreadManager.runOnBackground {
                            Thread.sleep(100) // Small delay to avoid adding work during critical rendering
                            ThreadManager.runOnMain {
                                view.settings.loadsImagesAutomatically = true
                            }
                        }
                    }
                }
                
                override fun onJsBeforeUnload(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    // Cancel any attempt to unload the page that might close the app
                    result?.cancel()
                    return true
                }
                
                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    // Handle alerts ourselves to prevent potential app closure
                    ThreadManager.runOnMain {
                        Toast.makeText(context, message ?: "Alert", Toast.LENGTH_SHORT).show()
                    }
                    result?.confirm()
                    return true
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    
                    // CRITICAL FIX: Immediately inject safety scripts to prevent auto-closing and navigation
                    view?.evaluateJavascript("""
                        (function() {
                            // Override window.close
                            window.close = function() { 
                                console.log('Window close prevented');
                                return false; 
                            };
                            
                            // Override history methods to prevent app minimizing
                            if (window.history) {
                                window.history.original_back = window.history.back;
                                window.history.back = function() {
                                    // Only allow back navigation within TiddlyWiki context
                                    if (document.body.classList.contains('tc-body')) {
                                        var story = document.querySelector('.tc-story-river');
                                        if (story && story.children.length > 1) {
                                            // Internal TiddlyWiki navigation - allow it
                                            return window.history.original_back.apply(window.history);
                                        }
                                    }
                                    console.log('History back prevented');
                                    return false;
                                };
                            }
                            
                            // Improve scroll performance
                            document.addEventListener('scroll', function() {}, { passive: true });
                            
                            // Throttle heavy DOM operations
                            const observer = new MutationObserver((mutations) => {
                                if (mutations.length > 100) {
                                    console.log('Throttling large DOM mutation batch');
                                    requestAnimationFrame(() => {
                                        requestAnimationFrame(() => {
                                            // Process after two animation frames
                                        });
                                    });
                                }
                            });
                            
                            // Start observing once document is loaded
                            document.addEventListener('DOMContentLoaded', () => {
                                observer.observe(document.body, { 
                                    childList: true, 
                                    subtree: true,
                                    attributes: true 
                                });
                            });
                        })();
                    """, null)
                    
                    // Optimize document rendering with progressive loading
                    view?.evaluateJavascript("""
                        (function() {
                            // Reduce style calculation overhead
                            document.documentElement.style.visibility = 'hidden';
                            
                            window.addEventListener('DOMContentLoaded', function() {
                                // Defer non-critical parsing and rendering
                                requestAnimationFrame(function() {
                                    document.documentElement.style.visibility = 'visible';
                                });
                            });
                        })();
                    """, null)
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    
                    // Progressive enhancement using low-priority thread to avoid ANR
                    ThreadManager.runOnLowPriority {
                        // Enable images in phases to avoid jank
                        ThreadManager.runOnBackgroundWithDelay(100) {
                            ThreadManager.runOnMain {
                                view?.settings?.blockNetworkImage = false
                            }
                        }
                        
                        // Load image content in a staggered way
                        view?.evaluateJavascript("""
                            (function() {
                                // Enable lazy loading for images
                                const images = Array.from(document.querySelectorAll('img:not([loading])'));
                                
                                // Process in small batches to avoid jank
                                function processNextBatch(startIndex) {
                                    const batch = images.slice(startIndex, startIndex + 5);
                                    if (batch.length === 0) return;
                                    
                                    setTimeout(function() {
                                        batch.forEach(img => {
                                            if (!img.loading) img.loading = 'lazy';
                                            if (!img.decoding) img.decoding = 'async';
                                        });
                                        processNextBatch(startIndex + 5);
                                    }, 50);
                                }
                                
                                if (images.length > 0) {
                                    processNextBatch(0);
                                }
                                
                                return true;
                            })();
                        """, null)
                    }
                }

                // The rest of the existing WebViewClient implementation...
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    
                    // Block analytics and tracking to improve performance
                    if (url.contains("analytics") || url.contains("tracking") || 
                        url.contains("google-analytics") || url.contains("facebook.com") ||
                        url.contains("tracker") || url.contains("pixel.gif")) {
                        return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                    }
                    
                    return null
                }
                
                // Add error handling to prevent WebView crashes
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        ThreadManager.runOnMain {
                            view?.loadUrl("about:blank")
                            view?.loadDataWithBaseURL(
                                null,
                                "<html><body><h3>Unable to load page</h3><p>Please check your connection and try again.</p></body></html>",
                                "text/html",
                                "utf-8",
                                null
                            )
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true) {
                        ThreadManager.runOnMain {
                            // Show error dialog through MainActivity
                            (context as? MainActivity)?.let { activity ->
                                activity.viewModel?.currentWiki?.value?.let { wiki ->
                                    activity.loadErrorWiki = wiki
                                    activity.showLoadErrorDialog = true
                                }
                            }
                        }
                    }
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    ThreadManager.runOnMain {
                        // Show error dialog through MainActivity
                        (context as? MainActivity)?.let { activity ->
                            activity.viewModel?.currentWiki?.value?.let { wiki ->
                                activity.loadErrorWiki = wiki
                                activity.showLoadErrorDialog = true
                            }
                        }
                    }
                    handler?.cancel()
                }

                // Add this to prevent navigation that might close the app
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    
                    // If the URL is a special scheme that might close the app, block it
                    if (url.startsWith("intent:") || 
                        url.startsWith("market:") || 
                        url.startsWith("tel:") ||
                        url.startsWith("geo:")) {
                        return true
                    }
                    
                    // For normal URLs, let the WebView handle them
                    return false
                }
            }

            // Add JavaScript interface for scroll detection
            class ScrollInterface(private val context: Context) {
                @JavascriptInterface
                fun onScroll(showBars: Boolean) {
                    // Use our thread manager for better scheduling
                    ThreadManager.runOnMain {
                        try {
                            getViewModel(context).setFrameVisible(showBars)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                
                // Add a method to prevent window closing
                @JavascriptInterface
                public fun preventClose() {
                    // Do nothing, just a hook to keep the app open
                }
                
                // Add method to report performance issues to the app
                @JavascriptInterface
                public fun reportPerformance(metric: String, value: String) {
                    // Could be used for telemetry or debugging
                }
            }
            
            // Add JavaScript interface for media handling
            class MediaInterface(private val context: Context) {
                @JavascriptInterface
                fun onMediaEvent(
                    event: String,
                    elementId: String,
                    currentTime: Float,
                    duration: Float,
                    src: String?,
                    title: String?
                ) {
                    ThreadManager.runOnMain {
                        try {
                            (context as? MainActivity)?.mediaSessionManager?.onMediaEvent(
                                event, elementId, currentTime, duration, src, title
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            try {
                webView.addJavascriptInterface(ScrollInterface(context.applicationContext), "ScrollInterface")
                webView.addJavascriptInterface(MediaInterface(context.applicationContext), "Android")
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            return webView
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            
            // Initialize managers and view models first before any UI
            mediaSessionManager = MediaSessionManager(this)
            exoPlayerManager = ExoPlayerManager(this)
            viewModel = getViewModel(this)
            
            // Setup network monitoring before UI
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            setupNetworkCallback()
            
            // Setup UI with state management protection
            setContent {
                val currentContext = LocalContext.current as ComponentActivity
                val viewModel = remember(currentContext) { getViewModel(currentContext) }
                val isDarkMode by viewModel.isDarkMode.collectAsState(initial = false)
                
                // Protect against composition errors with SideEffect
                SideEffect {
                    if (viewModel != this.viewModel) {
                        this.viewModel = viewModel
                    }
                }
                
                MaterialTheme(
                    colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            MainContent()
                        }
                    }
                }
            }
            
            // Handle intent after UI is set up
            handleIntent(intent)
            
            // Set up media player callbacks
            setupMediaCallbacks()
            
            // Initialize media session binding
            mediaSessionManager.bindToService()
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    @Composable
    private fun MainContent() {
        val localContext = LocalContext.current as ComponentActivity
        val viewModel = ViewModelProvider(localContext)[WikiViewModel::class.java]
        
        // Add error handling dialog
        if (showLoadErrorDialog && loadErrorWiki != null) {
            AlertDialog(
                onDismissRequest = { 
                    showLoadErrorDialog = false
                    loadErrorWiki = null
                },
                title = { Text("Failed to Load Wiki") },
                text = { Text("Unable to load ${loadErrorWiki?.name}. Would you like to try again?") },
                confirmButton = {
                    TextButton(onClick = {
                        loadErrorWiki?.let { wiki ->
                            viewModel.setCurrentWiki(wiki)
                        }
                        showLoadErrorDialog = false
                        loadErrorWiki = null
                    }) {
                        Text("Try Again")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showLoadErrorDialog = false
                        loadErrorWiki = null
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // This is the top-level MainScreen that contains all UI elements
        MainScreen(
            viewModel = viewModel,
            onAddClick = { showAddDialog = true }
        )
        
        // Handle dialog visibility from the MainActivity state
        if (showAddDialog) {
            AddWikiDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { name, url ->
                    viewModel.addWiki(name, url)
                    showAddDialog = false
                }
            )
        }
        
        if (showWikiSelector && pendingSharedText != null) {
            WikiSelectionDialog(
                wikis = viewModel.allWikis.collectAsState().value,
                quickTags = viewModel.quickTags.collectAsState().value,
                onDismiss = {
                    showWikiSelector = false
                    pendingSharedText = null
                    if (!isTaskRoot) {
                        finish()
                    }
                },
                onWikiSelected = { wiki, tags ->
                    handleWikiSelection(wiki, pendingSharedText, tags)
                    showWikiSelector = false
                    pendingSharedText = null
                    if (!isTaskRoot) {
                        finish()
                    }
                },
                onAddNew = {
                    showWikiSelector = false
                    showAddDialog = true
                }
            )
        }
        
        if (showDeleteConfirmDialog && viewModel.currentWiki.value != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Delete Wiki") },
                text = { Text("Are you sure you want to delete '${viewModel.currentWiki.value?.name}'?") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.currentWiki.value?.let { wiki ->
                            viewModel.deleteWiki(wiki)
                        }
                        showDeleteConfirmDialog = false
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
        
        if (showTagManagement) {
            TagManagementDialog(
                tags = viewModel.quickTags.collectAsState().value,
                onAddTag = { viewModel.addQuickTag(it) },
                onRemoveTag = { viewModel.removeQuickTag(it) },
                onReorderTags = { from, to -> viewModel.reorderQuickTags(from, to) },
                onDismiss = { showTagManagement = false }
            )
        }
    }

    private fun setupMediaCallbacks() {
        exoPlayerManager.getOrCreatePlayer().addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMediaSessionState()
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (exoPlayerManager.getOrCreatePlayer().isPlaying) {
                            // Extract metadata and update media session
                            val currentItem = exoPlayerManager.getOrCreatePlayer().currentMediaItem
                            val title = currentItem?.mediaMetadata?.title?.toString() ?: "Unknown Title"
                            val artist = currentItem?.mediaMetadata?.artist?.toString() ?: "TiddlyWiki Audio"
                            val duration = exoPlayerManager.getOrCreatePlayer().duration
                            
                            mediaSessionManager.updateMetadata(title, artist, duration)
                            startMediaService()
                        }
                    }
                    Player.STATE_ENDED -> stopMediaService()
                    else -> {} // No action needed for other states
                }
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateMediaSessionState()
                if (isPlaying) {
                    // Extract metadata and update
                    val currentItem = exoPlayerManager.getOrCreatePlayer().currentMediaItem
                    val title = currentItem?.mediaMetadata?.title?.toString() ?: "Unknown Title"
                    val artist = currentItem?.mediaMetadata?.artist?.toString() ?: "TiddlyWiki Audio"
                    val duration = exoPlayerManager.getOrCreatePlayer().duration
                    
                    mediaSessionManager.updateMetadata(title, artist, duration)
                    startMediaService()
                }
            }
        })

        // Setup service connection
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
                val mediaService = (binder as? MediaPlaybackService.LocalBinder)?.service
                mediaService?.setCallback(object : MediaPlaybackService.MediaPlayerCallback {
                    override fun onPlay() {
                        ThreadManager.runOnMain {
                            exoPlayerManager.getOrCreatePlayer().play()
                        }
                    }

                    override fun onPause() {
                        ThreadManager.runOnMain {
                            exoPlayerManager.getOrCreatePlayer().pause()
                        }
                    }

                    override fun onSeekTo(pos: Long) {
                        ThreadManager.runOnMain {
                            exoPlayerManager.getOrCreatePlayer().seekTo(pos)
                        }
                    }

                    override fun onSkipForward() {
                        ThreadManager.runOnMain {
                            val currentPosition = exoPlayerManager.getOrCreatePlayer().currentPosition
                            exoPlayerManager.getOrCreatePlayer().seekTo(currentPosition + 15000)
                        }
                    }

                    override fun onSkipBackward() {
                        ThreadManager.runOnMain {
                            val currentPosition = exoPlayerManager.getOrCreatePlayer().currentPosition
                            exoPlayerManager.getOrCreatePlayer().seekTo(maxOf(0, currentPosition - 15000))
                        }
                    }
                })
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                // Service has been killed, clean up
                serviceConnection = null
            }
        }
    }

    fun startMediaService() {
        // Start the service first to ensure it's running in foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Bind to the service to receive callbacks
        serviceConnection?.let { connection ->
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        } ?: run {
            // Create new connection if none exists
            setupMediaCallbacks()
            serviceConnection?.let { connection ->
                bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    private fun stopMediaService() {
        // Don't stop the service immediately when playback pauses
        // Just unbind if needed, but let the service handle its own lifecycle
        serviceConnection?.let { connection ->
            try {
                unbindService(connection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        serviceConnection = null
        
        // Don't stop the service here - let it run in foreground
        // This allows notifications to persist
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    pendingSharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    showWikiSelector = true
                }
            }
        }
    }

    private fun handleWikiSelection(selectedWiki: WikiInstance, textToShare: String?, selectedTags: List<String>) {
        lifecycleScope.launch {
            try {
                // First, show loading state in UI
                withContext(Dispatchers.Main) {
                    // Show loading indicator (implementation not shown)
                }

                // Preload in background
                withContext(Dispatchers.IO) {
                    viewModel?.preloadWebView(selectedWiki, this@MainActivity)
                }

                // Then update UI
                withContext(Dispatchers.Main) {
                    viewModel?.setCurrentWiki(selectedWiki)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    // Show error dialog
                    loadErrorWiki = selectedWiki
                    showLoadErrorDialog = true
                }
            }
        }
    }

    // Monitor memory conditions to prevent ANR
    private val activityManager by lazy {
        getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    private fun adaptToMemoryConditions() {
        lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)

                val availableMem = memoryInfo.availMem
                val lowMemory = memoryInfo.lowMemory

                if (lowMemory || availableMem < MEMORY_THRESHOLD) {
                    withContext(Dispatchers.Main) {
                        viewModel?.reduceMemoryUsage()
                    }
                }

                // Check every 30 seconds
                delay(30000)
            }
        }
    }

    private fun updateMediaSessionState() {
        val player = exoPlayerManager.getOrCreatePlayer()
        val isPlaying = player.isPlaying
        val currentPosition = player.currentPosition
        mediaSessionManager.updatePlaybackState(isPlaying, currentPosition)
    }

    private fun setupNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                super.onLost(network)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNetworkCheckTime > NETWORK_CHECK_THROTTLE) {
                    lastNetworkCheckTime = currentTime
                    val viewModel = getViewModel(this@MainActivity)
                    viewModel.setOfflineState(true)
                }
            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNetworkCheckTime > NETWORK_CHECK_THROTTLE) {
                    lastNetworkCheckTime = currentTime
                    val capabilities = connectivityManager.getNetworkCapabilities(network)
                    val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    if (hasInternet) {
                        val viewModel = getViewModel(this@MainActivity)
                        viewModel.setOfflineState(false)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webViewPaused = true
        exoPlayerManager.onPause()
        viewModel?.pauseAllWebViews()
    }

    override fun onResume() {
        super.onResume()
        webViewPaused = false
        exoPlayerManager.onResume()
        viewModel?.resumeCurrentWebView(viewModel?.currentWiki?.value)
        
        // When resuming, get the current WebView and set it for the MediaSessionManager
        getCurrentWebView()?.let { webView ->
            mediaSessionManager.setWebView(webView)
            // Bind to service to ensure continuity of playback controls
            mediaSessionManager.bindToService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSessionManager.release()
        exoPlayerManager.release()

        // Unbind the service connection
        serviceConnection?.let { connection ->
            try {
                unbindService(connection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            serviceConnection = null
        }

        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!webViewPaused) {
            viewModel?.clearWebViews()
            viewModel = null
        }
    }

    // Implement the onLowMemory callback
    override fun onLowMemory() {
        super.onLowMemory()
        ThreadManager.runOnBackground {
            viewModel?.onLowMemory()
            System.gc()
        }
    }

    // Implement onTrimMemory
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            ThreadManager.runOnBackground {
                viewModel?.onLowMemory()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        WebViewCache.setConfigurationChanging(true)
        super.onConfigurationChanged(newConfig)

        // Fix for orientation changes: update WebView layout
        ThreadManager.runOnBackgroundWithDelay(100) {
            ThreadManager.runOnMain {
                val currentWikiUrl = viewModel?.currentWiki?.value?.url
                currentWikiUrl?.let { url ->
                    viewModel?.getOrCreateWebView(viewModel?.currentWiki?.value ?: return@let, this)?.let { webView ->
                        // Get current orientation
                        val isPortrait = newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

                        // Trigger layout update with orientation awareness
                        webView.evaluateJavascript("""
                        (function() {
                            // Force layout recalculation
                            document.body.style.width = window.innerWidth + 'px';
                            const evt = new Event('resize');
                            window.dispatchEvent(evt);
                            
                            // Handle orientation-specific resizing
                            const isPortrait = ${isPortrait};
                            
                            // Safely check if TiddlyWiki is available
                            if (typeof window !== 'undefined' && 
                                window.${"$"}tw && 
                                typeof window.${"$"}tw.utils === 'object') {
                                
                                try {
                                    if (isPortrait) {
                                        // In portrait mode, use window resize event for better content flow
                                        window.${"$"}tw.utils.resizeAll();
                                        // Additional portrait-specific adjustments
                                        document.body.classList.add('tc-portrait-mode');
                                        document.body.classList.remove('tc-landscape-mode');
                                    } else {
                                        // In landscape mode, use TiddlyWiki's resize utils
                                        window.${"$"}tw.utils.resizeAll();
                                        // Additional landscape-specific adjustments
                                        document.body.classList.add('tc-landscape-mode');
                                        document.body.classList.remove('tc-portrait-mode');
                                    }
                                    return "TiddlyWiki resized for " + (isPortrait ? "portrait" : "landscape");
                                } catch(e) {
                                    console.error('TiddlyWiki resize error:', e);
                                    return "Error: " + e.message;
                                }
                            } else {
                                console.log('TiddlyWiki not fully initialized yet');
                                return "TiddlyWiki not ready";
                            }
                        })();
                    """) { result ->

                        }
                    }
                }
            }
        }

        WebViewCache.setConfigurationChanging(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        WebViewCache.setConfigurationChanging(true)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        WebViewCache.setConfigurationChanging(false)
    }
}

@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: WikiViewModel,
    onAddClick: () -> Unit
) {
    val context = LocalContext.current
    val currentWiki by viewModel.currentWiki.collectAsState()
    val wikis by viewModel.allWikis.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val isFrameVisible by viewModel.isFrameVisible.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }
    var showTagManagement by remember { mutableStateOf(false) }
    var showTemplateSelectionDialog by remember { mutableStateOf(false) }

    var draggedWiki by remember { mutableStateOf<WikiInstance?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    var showTrashCan by remember { mutableStateOf(false) }
    val trashCanAlpha by animateFloatAsState(if (showTrashCan) 1f else 0f)
    val dragStartTime = remember { mutableStateOf(0L) }
    val holdThreshold = 500L // 500ms hold time to show trash can
    var wikiToDelete by remember { mutableStateOf<WikiInstance?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isFrameVisible,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                )
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp
                ) {
                    TopAppBar(
                        title = { Text(currentWiki?.name ?: "TiddlyWiki Browser") },
                        actions = {
                            // Share button
                            IconButton(onClick = { showShareMenu = true }) {
                                Icon(Icons.Default.Share, contentDescription = "Share")
                            }

                            // Share menu
                            DropdownMenu(
                                expanded = showShareMenu,
                                onDismissRequest = { showShareMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Share Current Tiddler") },
                                    onClick = {
                                        showShareMenu = false
                                        val activity = context as? MainActivity
                                        activity?.getCurrentWebView()?.evaluateJavascript("""
                                            (function() {
                                                var currentTiddler = document.querySelector(".tc-tiddler-frame:not(.tc-tiddler-preview)");
                                                if (currentTiddler) {
                                                    var title = currentTiddler.querySelector(".tc-tiddler-title");
                                                    var content = currentTiddler.querySelector(".tc-tiddler-body");
                                                    if (title && content) {
                                                        return JSON.stringify({
                                                            title: title.textContent.trim(),
                                                            content: content.textContent.trim()
                                                        });
                                                    }
                                                }
                                                return null;
                                            })();
                                        """.trimIndent()) { result ->
                                            if (result != "null") {
                                                try {
                                                    val tiddler = JSONObject(result.trim('"').replace("\\\"", "\""))
                                                    val shareIntent = Intent().apply {
                                                        action = Intent.ACTION_SEND
                                                        putExtra(Intent.EXTRA_TITLE, tiddler.getString("title"))
                                                        putExtra(Intent.EXTRA_TEXT, tiddler.getString("content"))
                                                        type = "text/plain"
                                                    }
                                                    context.startActivity(Intent.createChooser(shareIntent, "Share Tiddler"))
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Failed to share tiddler", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Share Current URL") },
                                    onClick = {
                                        showShareMenu = false
                                        val activity = context as? MainActivity
                                        activity?.getCurrentWebView()?.let { webView ->
                                            val currentUrl = webView.url
                                            if (currentUrl != null) {
                                                val shareIntent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                                                    type = "text/plain"
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "Share URL"))
                                            }
                                        }
                                    }
                                )
                            }

                            // More options menu
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                if (currentWiki != null) {
                                    DropdownMenuItem(
                                        text = { Text("Refresh") },
                                        onClick = {
                                            showMenu = false
                                            currentWiki?.let { wiki ->
                                                viewModel.getOrCreateWebView(wiki, context).reload()
                                            }
                                        }
                                    )
                                }

                                DropdownMenuItem(
                                    text = { Text("Add new wiki") },
                                    onClick = {
                                        showMenu = false
                                        onAddClick()
                                    }
                                )
                                
                                DropdownMenuItem(
                                    text = { Text("Create Single File Tiddler") },
                                    onClick = {
                                        showMenu = false
                                        showTemplateSelectionDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Manage Quick Tags") },
                                    onClick = {
                                        showMenu = false
                                        showTagManagement = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text(if (isDarkMode) "Light mode" else "Dark mode") },
                                    onClick = {
                                        showMenu = false
                                        viewModel.setDarkMode(!isDarkMode)
                                    }
                                )

                                if (currentWiki != null) {
                                    DropdownMenuItem(
                                        text = { Text("Delete this Wiki", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenu = false
                                            showDeleteConfirmDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Replace the WikiView composable reference with WikiViewComposable
                currentWiki?.let { wiki ->
                    WikiViewComposable(wiki = wiki, viewModel = viewModel)
                } ?: Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Add your first TiddlyWiki using the menu button",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = isFrameVisible,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                )
            ) {
                Box {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showTrashCan,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(bottom = 8.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                            shape = CircleShape,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete wiki",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .alpha(trashCanAlpha)
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        shadowElevation = 3.dp
                    ) {
                        NavigationBar {
                            if (wikis.isEmpty()) {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Add, contentDescription = "Add Wiki") },
                                    label = { Text("Add Wiki") },
                                    selected = false,
                                    onClick = onAddClick
                                )
                            } else {
                                wikis.forEachIndexed { index, wiki ->
                                    val isSelected = wiki == currentWiki
                                    var offsetX by remember { mutableStateOf(0f) }
                                    
                                    NavigationBarItem(
                                        icon = { 
                                            Icon(
                                                Icons.Default.Book,
                                                contentDescription = wiki.name,
                                                modifier = Modifier
                                                    .pointerInput(Unit) {
                                                        detectDragGestures(
                                                            onDragStart = { offset ->
                                                                dragStartTime.value = System.currentTimeMillis()
                                                                draggedWiki = wiki
                                                                isDragging = true
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                offsetX += dragAmount.x
                                                                dragOffset = offsetX
                                                                
                                                                // Show trash can after hold threshold
                                                                if (System.currentTimeMillis() - dragStartTime.value > holdThreshold) {
                                                                    showTrashCan = true
                                                                }
                                                            },
                                                            onDragEnd = {
                                                                // Check if dragged to trash can position
                                                                if (showTrashCan && dragOffset.absoluteValue < 100) {
                                                                    wikiToDelete = draggedWiki
                                                                    showDeleteConfirmDialog = true
                                                                } else {
                                                                    // Calculate new position
                                                                    val dragDistance = dragOffset
                                                                    val itemWidth = size.width.toFloat()
                                                                    val newPosition = (dragDistance / itemWidth).roundToInt()
                                                                    
                                                                    if (newPosition != 0) {
                                                                        val targetIndex = (index + newPosition).coerceIn(0, wikis.size - 1)
                                                                        if (targetIndex != index) {
                                                                            viewModel.reorderWikis(index, targetIndex)
                                                                        }
                                                                    }
                                                                }
                                                                
                                                                // Reset states
                                                                draggedWiki = null
                                                                isDragging = false
                                                                dragOffset = 0f
                                                                offsetX = 0f
                                                                showTrashCan = false
                                                            }
                                                        )
                                                    }
                                                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                                                    .scale(if (isDragging && draggedWiki == wiki) 1.1f else 1f)
                                            )
                                        },
                                        label = { Text(wiki.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        selected = isSelected,
                                        onClick = { viewModel.setCurrentWiki(wiki) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showTemplateSelectionDialog) {
            TiddlerTemplateSelectionDialog(
                onDismiss = { showTemplateSelectionDialog = false },
                onTemplateSelected = { template ->
                    showTemplateSelectionDialog = false
                    viewModel.createSingleFileTiddler(context, template)
                }
            )
        }

        if (showDeleteConfirmDialog && (wikiToDelete != null || currentWiki != null)) {
            val wiki = wikiToDelete ?: currentWiki
            AlertDialog(
                onDismissRequest = { 
                    showDeleteConfirmDialog = false
                    wikiToDelete = null
                },
                title = { Text("Delete Wiki") },
                text = { Text("Are you sure you want to delete '${wiki?.name}'?") },
                confirmButton = {
                    TextButton(onClick = {
                        wiki?.let { viewModel.deleteWiki(it) }
                        showDeleteConfirmDialog = false
                        wikiToDelete = null
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showDeleteConfirmDialog = false 
                        wikiToDelete = null
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWikiDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add TiddlyWiki") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Wiki Name") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = name.isBlank(),
                    singleLine = true
                )
                
                TextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Wiki URL") },
                    placeholder = { Text("e.g., http://example.com or 192.168.1.1:8080") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null || url.isBlank(),
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { 
                    if (name.isBlank() || url.isBlank()) {
                        error = "Name and URL are required"
                        return@TextButton
                    }
                    
                    // Use URL validation and make sure to call onAdd with the formatted URL
                    WikiInstance.validateUrl(url)
                        .onSuccess { formattedUrl ->
                            // Critical: actually call onAdd with the formattedUrl
                            onAdd(name, formattedUrl)
                            Toast.makeText(context, "Wiki added successfully", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { 
                            error = it.message ?: "Invalid URL format"
                        }
                },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiSelectionDialog(
    wikis: List<WikiInstance>,
    quickTags: List<String>,
    onDismiss: () -> Unit,
    onWikiSelected: (WikiInstance, List<String>) -> Unit,
    onAddNew: () -> Unit
) {
    var selectedTags by remember { mutableStateOf(setOf("Shared")) }  // Using Set to prevent duplicates

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Wiki and Tags") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Select Tags",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Quick tags selection using simple Row + wrapping
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = true,  // Shared is always selected
                            enabled = false,  // Cannot deselect Shared
                            onClick = { },
                            label = { Text("Shared") }
                        )
                        quickTags.filter { it != "Shared" }.forEach { tag ->
                            FilterChip(
                                selected = selectedTags.contains(tag),
                                onClick = {
                                    selectedTags = if (selectedTags.contains(tag)) {
                                        selectedTags - tag
                                    } else {
                                        selectedTags + tag
                                    }
                                },
                                label = { Text(tag) }
                            )
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 16.dp))

                Text(
                    text = "Select Wiki",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Wiki selection
                Column(modifier = Modifier.fillMaxWidth()) {
                    wikis.forEach { wiki ->
                        TextButton(
                            onClick = { onWikiSelected(wiki, selectedTags.toList()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(wiki.name)
                        }
                    }
                    TextButton(
                        onClick = onAddNew,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ Add New Wiki")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSelectionDialog(
    tags: List<String>,
    selectedTags: List<String>,
    onTagToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Tags") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                tags.forEach { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selectedTags.contains(tag),
                            onCheckedChange = { onTagToggle(tag) }
                        )
                        Text(tag, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagementDialog(
    tags: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onReorderTags: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var newTag by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Quick Tags") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text(text = "New Tag") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (newTag.isNotBlank()) {
                                onAddTag(newTag)
                                newTag = ""
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Tag"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Replace scrolling Column with LazyColumn
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(tags) { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = tag,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRemoveTag(tag) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove Tag"
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Done")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TiddlerTemplateSelectionDialog(
    onDismiss: () -> Unit,
    onTemplateSelected: (TiddlerTemplate) -> Unit
) {
    val context = LocalContext.current
    val viewModel: WikiViewModel = remember { MainActivity.getViewModel(context) }
    val templates by viewModel.tiddlerTemplates.collectAsState()
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        viewModel.loadTiddlerTemplates(context)
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Tiddler Template") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (templates.isEmpty()) {
                    Text(
                        text = "No templates found. Please add template files to the assets/tiddler_templates folder.",
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(templates) { template ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onTemplateSelected(template) }
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = template.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = template.description ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
