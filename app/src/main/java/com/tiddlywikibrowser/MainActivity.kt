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
import android.provider.OpenableColumns
import android.util.Log
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
import androidx.compose.ui.res.stringResource
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
import androidx.datastore.preferences.core.edit
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
    lateinit var backgroundWebViewManager: BackgroundWebViewManager
    var viewModel: WikiViewModel? = null
    private var serviceConnection: ServiceConnection? = null
    private val serviceIntent by lazy { Intent(this, MediaPlaybackService::class.java) }

    // Dialog state variables
    private var pendingSharedText by mutableStateOf<String?>(null)
    private var showWikiSelector by mutableStateOf(false)
    private var showAddDialog by mutableStateOf(false)
    private var showDeleteConfirmDialog by mutableStateOf(false)
    private var showShareMenu by mutableStateOf(false)
    private var showTagManagement by mutableStateOf(false)
    private var showRenameDialog by mutableStateOf(false)

    // Add these properties for error handling
    private var showLoadErrorDialog by mutableStateOf(false)
    private var loadErrorWiki: WikiInstance? = null

    // Add temporary storage for wiki name during file selection
    private var pendingWikiName by mutableStateOf<String?>(null)

    // Create a file picker launcher
    private val filePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { contentUri ->
            // Try to get a file name from the URI
            val fileName = getFileNameFromUri(contentUri)
            val displayName = pendingWikiName ?: fileName?.substringBeforeLast('.') ?: "Local Wiki"

            // Reset the pending name
            pendingWikiName = null

            // Import the file using WikiViewModel
            viewModel?.importLocalWikiFile(contentUri, fileName)

            // Close the dialog
            showAddDialog = false
        }
    }

    // Helper function to get file name from URI
    private fun getFileNameFromUri(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            // Get the column indexes of the data
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            it.getString(nameIndex)
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

        internal val mediaMonitorScript = """
            (function() {
                let lastUpdate = Date.now();
                const UPDATE_INTERVAL = 250;
                let activeMediaElement = null;
                let monitoringInterval = null;

                function updateMediaState() {
                    const now = Date.now();
                    if (now - lastUpdate < UPDATE_INTERVAL) return;
                    lastUpdate = now;

                    if (!activeMediaElement) {
                        // Only search for media if we don't have an active element
                        const mediaElement = document.querySelector('audio,video');
                        if (mediaElement) {
                            activeMediaElement = mediaElement;
                            setupMediaElement(mediaElement);
                        }
                    } else if (activeMediaElement.ended || activeMediaElement.error) {
                        // Reset if media ended or errored
                        activeMediaElement = null;
                        return;
                    }

                    if (activeMediaElement) {
                        updateMediaMetadata(activeMediaElement);
                    }
                }

                function setupMediaElement(mediaElement) {
                    const events = ['play', 'pause', 'playing', 'timeupdate', 'seeking', 'seeked', 'durationchange', 'loadedmetadata', 'ended', 'error'];
                    events.forEach(event => {
                        mediaElement.addEventListener(event, () => updateMediaMetadata(mediaElement));
                    });
                }

                function updateMediaMetadata(mediaElement) {
                    let title = mediaElement.getAttribute('title') || '';
                    let artist = mediaElement.getAttribute('artist') || '';
                    
                    if (!title) {
                        const currentTiddler = mediaElement.closest('[data-tiddler-title]');
                        if (currentTiddler) {
                            title = currentTiddler.getAttribute('data-tiddler-title');
                        }
                    }
                    
                    if (!title) {
                        const metaTitle = document.querySelector('meta[property="og:title"]');
                        if (metaTitle) title = metaTitle.content;
                    }
                    
                    if (!artist) {
                        const metaArtist = document.querySelector('meta[property="og:audio:artist"]');
                        if (metaArtist) artist = metaArtist.content;
                    }

                    const duration = mediaElement.duration ? Math.floor(mediaElement.duration * 1000) : 0;
                    const position = mediaElement.currentTime ? Math.floor(mediaElement.currentTime * 1000) : 0;
                    
                    window.MediaInterface.onMediaStateChange(
                        title || document.title,
                        artist || 'TiddlyWiki Audio',
                        duration,
                        position,
                        !mediaElement.paused
                    );
                }

                // Start monitoring for media elements
                monitoringInterval = setInterval(updateMediaState, UPDATE_INTERVAL);

                // Add custom skip functions
                window.skipForward = function() {
                    if (activeMediaElement) {
                        activeMediaElement.currentTime = Math.min(
                            activeMediaElement.duration,
                            activeMediaElement.currentTime + 15
                        );
                    }
                };

                window.skipBackward = function() {
                    if (activeMediaElement) {
                        activeMediaElement.currentTime = Math.max(
                            0,
                            activeMediaElement.currentTime - 15
                        );
                    }
                };
            })();
        """.trimIndent()

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

                        // Critical: Initialize proper caching mode for state persistence
                        cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                        domStorageEnabled = true
                        databaseEnabled = true

                        // Set save state flags
                        saveFormData = true
                        savePassword = true

                        // Ensure proper viewport settings
                        useWideViewPort = true
                        loadWithOverviewMode = true

                        // Important for state preservation
                        setGeolocationEnabled(false)  // Disable geolocation to prevent state loss
                        mediaPlaybackRequiresUserGesture = false  // Allow media state preservation

                        // Apply text zoom based on screen size
                        val textZoom = ScreenUtils.getWebViewTextZoom(context)
                        setTextZoom(textZoom)

                        // Force accessibility mode to ensure pinch-to-zoom always works
                        if (ScreenUtils.shouldForceWebViewZoom(context)) {
                            // Force zoom controls to be available on small screens
                            builtInZoomControls = true
                            displayZoomControls = false
                        }

                        // Optimize for very small screens
                        if (ScreenUtils.isVerySmallScreen(context)) {
                            // For very small screens, set a narrower viewport
                            defaultFontSize = (defaultFontSize * 0.9).toInt()
                            minimumFontSize = 8 // Allow smaller minimum font size on VSS

                            // Add additional layout optimization settings
                            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                        }

                        // Critical: Initialize DOM/Database storage
                        try {
                            databasePath = context.getDir("database", Context.MODE_PRIVATE).path
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }

                        // Safely enable file access (required for local TiddlyWiki)
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

                    // CRITICAL: Initialize WebView state flags
                    webView.setTag(R.string.prevent_reload_tag, false)  // Start as not loaded

                    // Initialize state preservation script
                    webView.evaluateJavascript("""
                        (function() {
                            if (!window.__stateInitialized) {
                                // Create state container
                                window.__savedState = {};
                                
                                // Set up state preservation handlers
                                document.addEventListener('pause', function() {
                                    try {
                                        console.log('[State] Saved state:', JSON.stringify(window.__savedState));
                                    } catch(e) {}
                                });
                                
                                // Restore state handler
                                document.addEventListener('resume', function() {
                                    try {
                                    } catch(e) {}
                                });
                                
                                window __stateInitialized = true;
                                console.log('[State] State preservation initialized');
                            }
                            return true;
                        })();
                    """.trimIndent(), null)

                    // Add specific styling for small screens
                    if (ScreenUtils.isVerySmallScreen(context)) {
                        injectSmallScreenCSS(webView)
                    }

                    // Setup download manager for WebView
                    val downloadManager = WebViewDownloadManager(context.applicationContext)
                    downloadManager.setupDownloadListener(webView)

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Create a WebChromeClient to manage rendering lifecycle
            webView.webChromeClient = object : WebChromeClient() {
                private var customView: View? = null
                private var customViewCallback: CustomViewCallback? = null

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    customView = view
                    customViewCallback = callback
                    (context as? MainActivity)?.let { activity ->
                        if (view is PlayerView) {
                            activity.exoPlayerManager.getOrCreatePlayer().also { player ->
                                view.player = player
                            }
                        }
                    }
                }

                override fun onHideCustomView() {
                    customViewCallback?.onCustomViewHidden()
                    customView = null
                    customViewCallback = null
                }

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

                    // Apply CSS adaptations for small screens when page loads
                    if (newProgress > 75 && ScreenUtils.isVerySmallScreen(context)) {
                        injectSmallScreenCSS(view)
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

            // Use an AssetLoader to handle file access
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
                .build()

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    request?.url?.let { url ->
                        if (url.scheme == "file") {
                            return assetLoader.shouldInterceptRequest(url)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    // Apply small screen adaptations on page finish
                    if (ScreenUtils.isVerySmallScreen(context)) {
                        injectSmallScreenCSS(view)
                    }
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
                            (context as? MainActivity)?.let { activity ->
                                when (event) {
                                    "play" -> {
                                        if (src != null) activity.exoPlayerManager.playMedia(src)
                                        activity.mediaSessionManager.updatePlaybackState(true, (currentTime * 1000).toLong())
                                        activity.mediaSessionManager.updateMetadata(
                                            title = title ?: "TiddlyWiki Audio",
                                            artist = "TiddlyWiki",
                                            duration = (duration * 1000).toLong()
                                        )
                                    }
                                    "pause" -> {
                                        activity.mediaSessionManager.updatePlaybackState(false, (currentTime * 1000).toLong())
                                    }
                                    "timeupdate" -> {
                                        activity.mediaSessionManager.updatePlaybackState(true, (currentTime * 1000).toLong())
                                    }
                                    "ended" -> {
                                        activity.mediaSessionManager.updatePlaybackState(false, (duration * 1000).toLong())
                                    }
                                    "loadedmetadata" -> {
                                        activity.mediaSessionManager.updateMetadata(
                                            title = title ?: "TiddlyWiki Audio",
                                            artist = "TiddlyWiki",
                                            duration = (duration * 1000).toLong()
                                        )
                                    }
                                    else -> {} // Added missing else branch
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                @JavascriptInterface
                fun onMediaStateChange(title: String?, artist: String?, duration: Long, position: Long, isPlaying: Boolean) {
                    ThreadManager.runOnMain {
                        try {
                            (context as? MainActivity)?.let { activity ->
                                activity.mediaSessionManager.updateMetadata(title, artist, duration)
                                activity.mediaSessionManager.updatePlaybackState(isPlaying, position)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            try {
                webView.addJavascriptInterface(ScrollInterface(context.applicationContext), "ScrollInterface")
                webView.addJavascriptInterface(MediaInterface(context.applicationContext), "Android")
                webView.addJavascriptInterface(MediaInterface(context.applicationContext), "MediaInterface")
                webView.addJavascriptInterface(object : Any() {
                    @JavascriptInterface
                    fun playMedia(url: String) {
                        (context as? MainActivity)?.let { activity ->
                            activity.runOnUiThread {
                                activity.exoPlayerManager.playMedia(url)
                            }
                        }
                    }
                }, "ExoPlayerInterface")
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return webView
        }

        /**
         * Inject CSS modifications to make TiddlyWiki more usable on very small screens
         */
        private fun injectSmallScreenCSS(webView: WebView?) {
            val context = webView?.context ?: return
            val viewModel = getViewModel(context)
            
            // Only inject CSS if the device has a very small screen or the user has enabled it
            if (!ScreenUtils.isVerySmallScreen(context)) {
                // Remove any existing small screen styles if they exist
                webView.evaluateJavascript("""
                    (function() {
                        let existingStyle = document.getElementById('tidweb-small-screen-styles');
                        if (existingStyle) {
                            existingStyle.parentNode.removeChild(existingStyle);
                        }
                        return true;
                    })();
                """, null)
                return
            }

            webView.evaluateJavascript("""
                (function() {
                    // Remove any previous small screen styles
                    let existingStyle = document.getElementById('tidweb-small-screen-styles');
                    if (existingStyle) {
                        existingStyle.parentNode.removeChild(existingStyle);
                    }
                    
                    // Create new stylesheet for small screen adaptations
                    let styleEl = document.createElement('style');
                    styleEl.id = 'tidweb-small-screen-styles';
                    styleEl.textContent = `
                        /* Optimize TiddlyWiki for very small screens */
                        .tc-tiddler-frame {
                            padding: 0.5em !important;
                            margin: 0.25em 0 !important;
                        }
                        
                        .tc-tiddler-title, .tc-site-title {
                            font-size: 1.2em !important;
                            line-height: 1.2 !important;
                            margin: 0 0 0.5em 0 !important;
                        }
                        
                        .tc-titlebar {
                            margin-bottom: 0.3em !important;
                        }
                        
                        .tc-subtitle {
                            font-size: 0.7em !important;
                            margin: 0 !important;
                        }
                        
                        .tc-tiddler-controls {
                            font-size: 0.85em !important;
                        }
                        
                        .tc-tiddler-controls .tc-btn-invisible {
                            padding: 0.15em !important;
                            margin: 0 0.1em !important;
                        }
                        
                        /* Make dropdown menus more compact */
                        .tc-drop-down {
                            padding: 0.3em !important;
                            font-size: 0.9em !important;
                        }
                        
                        .tc-drop-down a {
                            padding: 0.2em 0.4em !important;
                        }
                        
                        /* Adjust body text and spacing */
                        .tc-tiddler-body {
                            margin: 0.3em 0 !important;
                            font-size: 0.95em !important;
                            line-height: 1.3 !important;
                        }
                        
                        /* Adjust sidebar */
                        .tc-sidebar-lists {
                            padding: 0.3em !important;
                        }
                        
                        .tc-sidebar-tab-open {
                            font-size: 0.9em !important;
                        }
                        
                        /* Force horizontal scrolling rather than overflow */
                        pre, code, .tc-table-of-contents {
                            max-width: 100% !important;
                            overflow-x: auto !important;
                        }
                        
                        /* Keep larger tap targets */
                        button, .tc-btn-invisible, a {
                            min-height: 22px !important;
                            min-width: 22x !important;
                        }
                        
                        /* Reduce image sizes */
                        img {
                            max-width: 100% !important;
                            height: auto !important;
                        }
                        
                        /* Adapt modals */
                        .tc-modal {
                            padding: 0.3em !important;
                        }
                        
                        /* Adjust inputs */
                        input, select, textarea {
                            font-size: 0.95em !important;
                        }
                    `;
                    
                    document.head.appendChild(styleEl);
                    console.log('Small screen CSS adaptations applied');
                    return true;
                })();
            """, null)
        }
    }

    // Keep track of background mode preference
    private val _isBackgroundEnabled = MutableStateFlow(false)
    val isBackgroundEnabled: StateFlow<Boolean> = _isBackgroundEnabled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize WebView configuration
        applyWebViewConfiguration()
        
        // Initialize the media session manager first
        mediaSessionManager = MediaSessionManager(this)
        
        // Initialize the ExoPlayer manager after mediaSessionManager
        exoPlayerManager = ExoPlayerManager(this)
        
        // Initialize the background WebView manager
        backgroundWebViewManager = BackgroundWebViewManager(this)
        
        // Process intent
        handleIntent(intent)
        
        // Load preferences including background mode setting
        loadPreferences()
        
        // Set up the UI
        setupUI()
        
        // Set up network monitoring
        setupNetworkMonitoring()
    }
    
    private fun loadPreferences() {
        // Load other preferences here
        
        // Check if background mode is enabled from preferences
        val dataStore = applicationContext.dataStore
        lifecycleScope.launch {
            dataStore.data.first().let { preferences ->
                val isEnabled = preferences[PreferencesKeys.BACKGROUND_MODE_ENABLED] ?: false
                _isBackgroundEnabled.value = isEnabled
                
                // Start the background service if enabled
                if (isEnabled) {
                    backgroundWebViewManager.startBackgroundService()
                }
            }
        }
    }
    
    /**
     * Save the background mode preference
     */
    private fun saveBackgroundModePreference(isEnabled: Boolean) {
        lifecycleScope.launch {
            applicationContext.dataStore.edit { preferences ->
                preferences[PreferencesKeys.BACKGROUND_MODE_ENABLED] = isEnabled
            }
        }
    }
    
    /**
     * Toggle background mode on/off
     */
    fun toggleBackgroundMode() {
        val newState = !_isBackgroundEnabled.value
        _isBackgroundEnabled.value = newState
        
        // Save the preference
        saveBackgroundModePreference(newState)
        
        // Start or stop the background service
        if (newState) {
            backgroundWebViewManager.startBackgroundService()
            
            // Register the current WebView
            viewModel?.currentWiki?.value?.let { wiki ->
                val key = wiki.idFromUrl ?: wiki.url
                val webView = viewModel?.getOrCreateWebView(wiki, this)
                webView?.let {
                    backgroundWebViewManager.registerWebView(key, it)
                    
                    // Inform the user
                    Toast.makeText(this, 
                        "Background mode enabled. TiddlyWiki will continue running when minimized.", 
                        Toast.LENGTH_LONG).show()
                }
            }
        } else {
            backgroundWebViewManager.stopBackgroundService()
            
            // Inform the user
            Toast.makeText(this, 
                "Background mode disabled.", 
                Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        webViewPaused = true
        exoPlayerManager.onPause()
        
        // Handle current WebView differently if background mode is enabled
        if (!_isBackgroundEnabled.value) {
            viewModel?.let { vm ->
                // Save state for current WebView
                vm.currentWiki.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    WebViewCache.cacheWebView(key, vm.getOrCreateWebView(wiki, this))
                }
                vm.pauseAllWebViews()
            }
        } else {
            // If background mode is enabled, register the current WebView with the service
            viewModel?.currentWiki?.value?.let { wiki ->
                val key = wiki.idFromUrl ?: wiki.url
                val webView = viewModel?.getOrCreateWebView(wiki, this)
                webView?.let {
                    backgroundWebViewManager.registerWebView(key, it)
                }
            }
        }

        // Dispatch pause event to WebView
        getCurrentWebView()?.evaluateJavascript("""
            (function() {
                const event = new Event('pause');
                document.dispatchEvent(event);
            })();
        """.trimIndent(), null)
    }

    override fun onResume() {
        super.onResume()
        webViewPaused = false
        exoPlayerManager.onResume()
        
        // Check if the current WebView is in the background service
        val isInBackgroundService = viewModel?.currentWiki?.value?.let { wiki ->
            val key = wiki.idFromUrl ?: wiki.url
            backgroundWebViewManager.hasWebView(key)
        } ?: false
        
        // Only resume normally if not in background service
        if (!isInBackgroundService) {
            viewModel?.resumeCurrentWebView(viewModel?.currentWiki?.value)
        } else {
            // If it's in the background service, refresh it
            backgroundWebViewManager.refreshCurrentWebView()
        }
        
        // When resuming, get the current WebView and set it for the MediaSessionManager
        getCurrentWebView()?.let { webView ->
            mediaSessionManager.setWebView(webView)
            // Bind to service to ensure continuity of playback controls
            mediaSessionManager.bindToService()
        }
    }

    override fun onStop() {
        super.onStop()
        // Don't cleanup if we're just changing configurations or if background mode is enabled
        if (!isChangingConfigurations && !_isBackgroundEnabled.value) {
            viewModel?.let { vm ->
                vm.currentWiki.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    // Save full state before stopping
                    vm.getOrCreateWebView(wiki, this)?.let { webView ->
                        WebViewCache.cacheWebView(key, webView)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSessionManager.release()
        exoPlayerManager.release()
        backgroundWebViewManager.release()

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
    }

    // Implement the onLowMemory callback
    override fun onLowMemory() {
        super.onLowMemory()
        WebViewCache.onLowMemory()
        viewModel?.onLowMemory()
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

        // Handle any WebViews that need to be retained during configuration changes
        viewModel?.currentWiki?.value?.let { wiki ->
            val key = wiki.idFromUrl ?: wiki.url
            WebViewCache.setCurrentActiveKey(key)
        }

        // Reset flag after a short delay
        Handler(Looper.getMainLooper()).postDelayed({
            WebViewCache.setConfigurationChanging(false)
        }, 1000)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        WebViewCache.setConfigurationChanging(true)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        WebViewCache.setConfigurationChanging(false)
    }

    /**
     * Apply WebView configuration settings
     */
    private fun applyWebViewConfiguration() {
        // Initialize WebView with appropriate settings
        WebView.setWebContentsDebuggingEnabled(true)
        
        // Set default cache mode
        val webViewDatabase = WebViewDatabase.getInstance(this)
        CookieManager.getInstance().setAcceptCookie(true)
        
        // Clear any stale cookies
        ThreadManager.runOnBackground {
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
    
    /**
     * Set up the main UI
     */
    private fun setupUI() {
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

            // Handle initial wiki state preservation
            DisposableEffect(Unit) {
                onDispose {
                    // Save state of current wiki when activity is disposed
                    viewModel.currentWiki.value?.let { wiki ->
                        val key = wiki.idFromUrl ?: wiki.url
                        getCurrentWebView()?.let { webView ->
                            WebViewCache.cacheWebView(key, webView)
                        }
                    }
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
                        MainScreen(
                            viewModel = viewModel,
                            onAddClick = { showAddDialog = true },
                            onShowRenameDialog = { showRenameDialog = true }
                        )
                        
                        // Handle dialog visibility
                        if (showAddDialog) {
                            AddWikiDialog(
                                onDismiss = { showAddDialog = false },
                                onAdd = { name, url ->
                                    viewModel.addWiki(name, url)
                                    showAddDialog = false
                                },
                                onAddLocalFile = {
                                    pendingWikiName = null
                                    filePickerLauncher.launch("*/*")
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
                        
                        if (showRenameDialog && viewModel.currentWiki.value != null) {
                            RenameWikiDialog(
                                currentName = viewModel.currentWiki.value?.name ?: "",
                                onDismiss = { showRenameDialog = false },
                                onRename = { newName ->
                                    viewModel.currentWiki.value?.let { wiki ->
                                        viewModel.renameWiki(wiki, newName)
                                    }
                                    showRenameDialog = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Set up network monitoring
     */
    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                super.onLost(network)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNetworkCheckTime > NETWORK_CHECK_THROTTLE) {
                    lastNetworkCheckTime = currentTime
                    ThreadManager.runOnBackground {
                        val viewModel = getViewModel(this@MainActivity)
                        viewModel.setOfflineState(true)
                    }
                }
            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNetworkCheckTime > NETWORK_CHECK_THROTTLE) {
                    lastNetworkCheckTime = currentTime
                    ThreadManager.runOnBackground {
                        val capabilities = connectivityManager.getNetworkCapabilities(network)
                        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        if (hasInternet) {
                            val viewModel = getViewModel(this@MainActivity)
                            viewModel.setOfflineState(false)
                        }
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
    
    /**
     * Handle the intent received by the activity
     */
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
    
    /**
     * Get the current WebView
     */
    fun getCurrentWebView(): WebView? {
        return viewModel?.currentWiki?.value?.let { wiki ->
            viewModel?.getOrCreateWebView(wiki, this)
        }
    }
    
    /**
     * Handle wiki selection for sharing content
     */
    private fun handleWikiSelection(selectedWiki: WikiInstance, textToShare: String?, selectedTags: List<String>) {
        viewModel?.setCurrentWiki(selectedWiki)
        Handler(Looper.getMainLooper()).postDelayed({
            getCurrentWebView()?.evaluateJavascript("""
                (function() {
                    try {
                        console.log('Checking TiddlyWiki state...');
                        var tiddlywiki = window['${'$'}tw'];
                        if (!tiddlywiki || !tiddlywiki.wiki) {
                            console.log('TiddlyWiki object not found');
                            return { status: 'not_ready' };
                        }
                        
                        var title = 'Shared Content ' + new Date().toISOString();
                        var processedText = ${if (textToShare != null) "\"${textToShare.replace("\"", "\\\"").replace("\n", "\\n")}\"" else "\"\""}
                          .replace(/\\\\n/g, "\n").replace(/\\\\t/g, "\t").replace(/\\n/g, "\n").replace(/\\t/g, "\t");
                        processedText = processedText.replace(/(\n\s*){5,}/g, "\n".repeat(5));
                        var tags = ${JSONArray(selectedTags).toString()};
                        
                        tiddlywiki.wiki.addTiddler({
                            title: title,
                            text: processedText,
                            tags: tags
                        });
                        
                        // Verify tiddler was created
                        var tiddler = tiddlywiki.wiki.getTiddler(title);
                        if (!tiddler) {
                            return { status: 'error', message: 'Failed to create tiddler' };
                        }
                        
                        // Try to navigate to the new tiddler
                        if (tiddlywiki.story && typeof tiddlywiki.story.navigateTiddler === 'function') {
                            tiddlywiki.story.navigateTiddler(title);
                            console.log('Navigated to tiddler:', title);
                        }
                        
                        // Attempt to trigger a save if possible
                        if (typeof tiddlywiki.wiki.saveWiki === 'function') {
                            tiddlywiki.wiki.saveWiki();
                        }
                        
                        return { status: 'success', title: title };
                    } catch (e) {
                        console.error('Error creating tiddler:', e);
                        return { status: 'error', message: e.toString() };
                    }
                })();
            """.trimIndent()) { result ->
                try {
                    val jsonResult = JSONObject(result)
                    when (jsonResult.getString("status")) {
                        "not_ready" -> {
                            // Try again after a longer delay
                            Handler(Looper.getMainLooper()).postDelayed({
                                handleWikiSelection(selectedWiki, textToShare, selectedTags)
                            }, 2000)
                        }
                        "success" -> {
                            Toast.makeText(this, "Content shared to ${selectedWiki.name}", Toast.LENGTH_SHORT).show()
                        }
                        "error" -> {
                            val message = jsonResult.optString("message", "Unknown error")
                            Toast.makeText(this, "Failed to create tiddler: $message", Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            Toast.makeText(this, "Unknown response from TiddlyWiki", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Error processing tiddler creation", Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                }
            }
        }, 1000)
    }

    /**
     * Start the media service
     */
    fun startMediaService() {
        Log.d("MainActivity", "Starting media service")
        try {
            val intent = Intent(this, MediaPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting media service", e)
        }
    }
}

@OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: WikiViewModel,
    onAddClick: () -> Unit,
    onShowRenameDialog: () -> Unit
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
    var showSettings by remember { mutableStateOf(false) }

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
                    animationSpec = tween(
                        durationMillis = 200,  // Match scroll detection speed
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(
                        durationMillis = 200,  // Keep consistent with enter
                        easing = FastOutLinearInEasing
                    )
                )
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
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
                                                        putExtra(Intent.EXTRA_TITLE, tiddler.getString("title") as String)
                                                        putExtra(Intent.EXTRA_TEXT, tiddler.getString("content") as String)
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
                                                // Instead of just reloading the current page, force reload from the original URL
                                                val webView = viewModel.getOrCreateWebView(wiki, context)
                                                // Get the WebViewClient and cast it to ReloadBlockingWebViewClient
                                                val client = webView.webViewClient as? ReloadBlockingWebViewClient
                                                if (client != null) {
                                                    // Use forceReload with the original wiki URL
                                                    client.forceReload(webView, wiki.url)
                                                } else {
                                                    // Fallback to standard reload if casting fails
                                                    webView.loadUrl(wiki.url)
                                                }
                                            }
                                        }
                                    )

                                    DropdownMenuItem(
                                        text = { Text("Rename Wiki") },
                                        onClick = {
                                            showMenu = false
                                            onShowRenameDialog()
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
                                    text = { Text("Settings") },
                                    onClick = {
                                        showMenu = false
                                        showSettings = true
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
                    animationSpec = tween(
                        durationMillis = 200,  // Match scroll detection speed
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(
                        durationMillis = 200,  // Keep consistent with enter
                        easing = FastOutLinearInEasing
                    )
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
                        shadowElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
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

        if (showTagManagement) {
            TagManagementDialog(
                tags = viewModel.quickTags.collectAsState().value,
                onAddTag = { viewModel.addQuickTag(it) },
                onRemoveTag = { viewModel.removeQuickTag(it) },
                onReorderTags = { from, to -> viewModel.reorderQuickTags(from, to) },
                onDismiss = { showTagManagement = false }
            )
        }

        if (showSettings) {
            SettingsDialog(
                isDarkMode = isDarkMode,
                onDarkModeChange = { newMode -> 
                    viewModel.setDarkMode(newMode)
                },
                onManageQuickTags = {
                    showTagManagement = true
                },
                onDismiss = {
                    showSettings = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameWikiDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Wiki") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                TextField(
                    value = newName,
                    onValueChange = { newName = it; error = null },
                    label = { Text("Wiki Name") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null || newName.isBlank(),
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isBlank()) {
                        error = "Name cannot be empty"
                        return@TextButton
                    }

                    if (newName == currentName) {
                        onDismiss()
                        return@TextButton
                    }

                    onRename(newName)
                },
                enabled = newName.isNotBlank()
            ) {
                Text("Rename")
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
fun AddWikiDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
    onAddLocalFile: () -> Unit
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

                // Add a button to select a local file
                OutlinedButton(
                    onClick = onAddLocalFile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "Select File",
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Select Local TiddlyWiki File")
                    }
                }
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
        viewModel.loadTiddlerTemplates()
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

@Composable
fun SettingsDialog(
    isDarkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onManageQuickTags: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val mainActivity = context as? MainActivity
    val viewModel: WikiViewModel = remember { MainActivity.getViewModel(context) }
    val useSmallScreenCSS by viewModel.useSmallScreenCSS.collectAsState()
    val isBackgroundEnabled by mainActivity?.isBackgroundEnabled?.collectAsState() ?: remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                // Dark Mode Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.dark_mode))
                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = onDarkModeChange
                    )
                }

                // Small Screen CSS Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Small Screen Adaptations")
                        Text(
                            "Optimize layout for very small screens",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useSmallScreenCSS,
                        onCheckedChange = { enabled ->
                            viewModel.setUseSmallScreenCSS(enabled)
                        }
                    )
                }
                
                // Background Mode Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.background_mode))
                        Text(
                            stringResource(R.string.background_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isBackgroundEnabled,
                        onCheckedChange = { enabled ->
                            mainActivity?.toggleBackgroundMode()
                        }
                    )
                }
                
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                // Quick Tags Management Button - moved inside the Column with proper spacing
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onManageQuickTags,
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text("Manage Quick Tags")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}