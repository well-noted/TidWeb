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
    
    // Dialog state variables
    private var pendingSharedText by mutableStateOf<String?>(null)
    private var showWikiSelector by mutableStateOf(false)
    private var showAddDialog by mutableStateOf(false)
    private var showDeleteConfirmDialog by mutableStateOf(false)
    private var showShareMenu by mutableStateOf(false)
    private var showTagManagement by mutableStateOf(false)

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
                        setDatabasePath(context.getDir("database", Context.MODE_PRIVATE).path)
                        
                        // Add performance-optimizing settings
                        setRenderPriority(WebSettings.RenderPriority.HIGH)
                        
                        // Disable features that might cause crashes
                        allowContentAccess = true
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                        
                        // Prevent WebView from becoming unresponsive
                        mediaPlaybackRequiresUserGesture = false
                        
                        // Set the proper dark mode setting
                        when (AppCompatDelegate.getDefaultNightMode()) {
                            AppCompatDelegate.MODE_NIGHT_YES -> forceDark = WebSettings.FORCE_DARK_ON
                            AppCompatDelegate.MODE_NIGHT_NO -> forceDark = WebSettings.FORCE_DARK_OFF
                            else -> forceDark = WebSettings.FORCE_DARK_AUTO
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    
                    // Inject loading optimization script early
                    view?.evaluateJavascript("""
                        // Defer non-critical operations
                        window.requestIdleCallback = window.requestIdleCallback || 
                            function(cb) {
                                return setTimeout(function() {
                                    var start = Date.now();
                                    cb({
                                        didTimeout: false,
                                        timeRemaining: function() {
                                            return Math.max(0, 50 - (Date.now() - start));
                                        }
                                    });
                                }, 1);
                            };
                    """.trimIndent(), null)
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    
                    // Progressive enhancement using low-priority thread to avoid ANR
                    ThreadManager.runOnLowPriority {
                        // First enable essential functionality
                        view?.evaluateJavascript("""
                            (function() {
                                // Enable responsive layout
                                document.querySelectorAll('meta[name="viewport"]').forEach(meta => {
                                    if (!meta.content.includes('width=device-width')) {
                                        meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0';
                                    }
                                });
                                
                                // Optimize scroll performance
                                let lastScrollY = window.scrollY;
                                window.addEventListener('scroll', function() {
                                    const currentScrollY = window.scrollY;
                                    const isScrollingUp = currentScrollY < lastScrollY;
                                    lastScrollY = currentScrollY;
                                    window.ScrollInterface.onScroll(isScrollingUp || currentScrollY === 0);
                                }, { passive: true });
                                
                                // Report success
                                return true;
                            })();
                        """.trimIndent(), null)
                        
                        // Wait a bit for the page to stabilize
                        ThreadManager.runOnBackgroundWithDelay(200) {
                            // Enable images and other non-critical resources
                            ThreadManager.runOnMain {
                                if (view != null && view.settings != null) {
                                    view.settings.blockNetworkImage = false
                                    view.settings.loadsImagesAutomatically = true
                                }
                            }
                        }
                    }
                }

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
                    // Don't load error pages for non-main frame errors
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

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    // Handle all URLs internally to prevent crashes
                    return false
                }
                
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // Reset WebView state if needed
                    view?.clearFormData()
                    view?.clearCache(false)
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
            }

            try {
                webView.addJavascriptInterface(ScrollInterface(context.applicationContext), "ScrollInterface")
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            return webView
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            
            // Initialize managers and view models
            mediaSessionManager = MediaSessionManager(this)
            exoPlayerManager = ExoPlayerManager(this)
            viewModel = getViewModel(this)
            
            // Handle intent (for sharing)
            handleIntent(intent)
            
            // Start the media service for background audio playback
            val serviceIntent = Intent(this, MediaPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // Add MediaPlayerCallback implementation
            exoPlayerManager.getOrCreatePlayer().addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateMediaSessionState()
                }
                
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateMediaSessionState()
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
                    // Just cleanup, no need to do anything special
                }
            }.also { connection ->
                try {
                    bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // Setup network monitoring
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            setupNetworkCallback()
            
            // Setup UI
            setContent {
                val viewModel = getViewModel(LocalContext.current as ComponentActivity)
                val isDarkMode by viewModel.isDarkMode.collectAsState()
                
                MaterialTheme(
                    colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            MainScreen(
                                viewModel = viewModel,
                                onAddClick = { showAddDialog = true }
                            )
                            
                            // Dialogs
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
                                    onWikiSelected = { selectedWiki, selectedTags ->
                                        handleWikiSelection(selectedWiki, pendingSharedText, selectedTags)
                                    },
                                    onAddNew = {
                                        showWikiSelector = false
                                        showAddDialog = true
                                    }
                                )
                            }

                            if (showAddDialog) {
                                AddWikiDialog(
                                    onDismiss = {
                                        showAddDialog = false
                                        if (pendingSharedText != null) {
                                            showWikiSelector = true
                                        }
                                    },
                                    onAdd = { name, url ->
                                        viewModel.addWiki(name, url)
                                        showAddDialog = false
                                        if (pendingSharedText != null) {
                                            showWikiSelector = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            // Set up WebView data directory
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    WebView.setDataDirectorySuffix("tidweb_webview_data")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // Process separation - add this before any WebView is created
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val process = WebViewProcessControl.processName
                if (process == null || !process.endsWith(":webview_process")) {
                    try {
                        WebViewProcessControl.setProcessNameForNextWebView(applicationContext.packageName + ":webview_process")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // Defer non-critical initialization
            lifecycleScope.launch {
                // Only do this after the Activity is created
                withContext(Dispatchers.Default) {
                    try {
                        initializeNonCriticalComponents()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // Add ThreadManager cleanup on destroy
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    ThreadManager.shutdown()
                }
            })
            
            // Start memory monitoring
            adaptToMemoryConditions()
            
        } catch (e: Exception) {
            // Log the exception to help with debugging
            e.printStackTrace()
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        // Use ThreadManager for smoother transitions
        lifecycleScope.launch {
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
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
                                        text = { Text("Delete wiki", color = MaterialTheme.colorScheme.error) },
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

            AnimatedVisibility(
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
                            wikis.forEach { wiki ->
                                NavigationBarItem(
                                    icon = {
                                        viewModel.faviconMap.collectAsState().value[wiki.url]?.let { favicon ->
                                            Image(
                                                bitmap = favicon.asImageBitmap(),
                                                contentDescription = wiki.name,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        } ?: Icon(Icons.Default.Description, contentDescription = wiki.name)
                                    },
                                    label = { Text(wiki.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    selected = currentWiki?.url == wiki.url,
                                    onClick = { viewModel.setCurrentWiki(wiki) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showDeleteConfirmDialog && currentWiki != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Delete Wiki") },
                text = { Text("Are you sure you want to delete '${currentWiki?.name}'?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            currentWiki?.let { viewModel.deleteWiki(it) }
                            showDeleteConfirmDialog = false
                        }
                    ) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWikiDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

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
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Wiki URL") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, url) },
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
