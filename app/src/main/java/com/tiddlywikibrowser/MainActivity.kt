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
import android.content.res.Configuration
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
import com.tiddlywikibrowser.WebViewProvider
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
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import android.os.PowerManager
import android.provider.Settings
import android.os.PersistableBundle
import android.view.KeyEvent
import com.tiddlywikibrowser.PreferencesKeys
import com.tiddlywikibrowser.dataStore
import com.tiddlywikibrowser.ui.MainScreen
import com.tiddlywikibrowser.ui.dialogs.*
import com.tiddlywikibrowser.ui.DialogStateManager
import com.tiddlywikibrowser.webview.WebViewFactory
import com.tiddlywikibrowser.webview.MediaInterface
import com.tiddlywikibrowser.media.MediaSessionManager
import com.tiddlywikibrowser.cache.WebViewCache
import com.tiddlywikibrowser.TiddlerTransferManager
import com.tiddlywikibrowser.network.NetworkManager
import com.tiddlywikibrowser.managers.BackgroundModeManager
import com.tiddlywikibrowser.handlers.*

// Memory threshold for optimization (50MB)
private const val MEMORY_THRESHOLD = 50L * 1024L * 1024L

// WikiViewModel has been moved to separate file

// Wiki loading strategy enum already exists in WikiLoadStrategy.kt
// enum class WikiLoadStrategy {...}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    internal lateinit var mediaSessionManager: MediaSessionManager
    internal lateinit var backgroundWebViewManager: BackgroundWebViewManager
    private val TAG = "MainActivity"
    var viewModel: WikiViewModel? = null
    private var serviceConnection: ServiceConnection? = null
    private val serviceIntent by lazy { Intent(this, MediaPlaybackService::class.java) }

    // Initialize TiddlerTransferState
    val tiddlerTransferState = TiddlerTransferManager.TiddlerTransferState()

    // Managers and handlers
    private lateinit var dialogStateManager: DialogStateManager
    private lateinit var networkManager: NetworkManager
    private lateinit var backgroundModeManager: BackgroundModeManager
    private lateinit var lifecycleHandler: LifecycleHandler
    private lateinit var sharedContentHandler: SharedContentHandler
    private lateinit var intentHandler: IntentHandler

    // Create a file picker launcher
    private val filePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (::intentHandler.isInitialized) {
            intentHandler.handleFilePickerResult(uri)
        }
    }

    // Initialize non-critical components on background thread
    private fun initializeNonCriticalComponents() {
        // This is called from a background thread
        // Pre-warm caches, initialize background services, etc.
        ThreadManager.runOnBackground {
            // Initialize cookie manager but don't clear cookies
            CookieManager.getInstance().setAcceptCookie(true)

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
            return WebViewFactory.createWebView(context)
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
    }

    // Background mode is managed by BackgroundModeManager
    internal val _isBackgroundEnabled: StateFlow<Boolean>
        get() = if (::backgroundModeManager.isInitialized) backgroundModeManager.isBackgroundEnabled else MutableStateFlow(false)
    internal val isBackgroundEnabled: StateFlow<Boolean>
        get() = _isBackgroundEnabled

    // Request permission for notifications on Android 13+ (API 33+)
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS

            // Check if permission is already granted
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Request the permission
                requestPermissions(arrayOf(permission), 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize ViewModel with ViewModelFactory
        viewModel = ViewModelProvider(this, ViewModelFactory(applicationContext)).get(WikiViewModel::class.java)

        // Initialize managers
        initializeManagers()
        
        // Initialize MediaSessionManager and bind to service
        mediaSessionManager.bindToService()

        try {
            // Initialize WebView configuration first
            applyWebViewConfiguration()

            // Request notification permission for Android 13+
            requestNotificationPermission()

            // Synchronously load critical preferences before initializing services
            backgroundModeManager.loadBackgroundModePreference()

            // Load remaining preferences
            loadPreferences()

            // Process intent after core components are initialized
            intentHandler.handleIntent(intent)

            // Set up the UI last
            setupUI()

            // Set up network monitoring
            networkManager.setupNetworkMonitoring()

            // Register initial WebView observer after UI is ready
            ThreadManager.runOnBackground {
                try {
                    Thread.sleep(500) // Small delay to ensure UI is rendered
                    ThreadManager.runOnMain {
                        registerInitialWebViewObserver()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error registering WebView observer", e)
                }
            }
        } catch (e: Exception) {
            // Log and recover from initialization errors
            Log.e("MainActivity", "Error during app initialization", e)
            Toast.makeText(this, "Error initializing app. Please try again.", Toast.LENGTH_LONG).show()
        }
    }    private fun initializeManagers() {
        // Initialize MediaSessionManager
        mediaSessionManager = MediaSessionManager.getInstance(this)
          // Initialize BackgroundWebViewManager first so we can pass it to MediaSessionManager
        backgroundWebViewManager = BackgroundWebViewManager(applicationContext)
        backgroundWebViewManager.startBackgroundService()
        
        // Set the background manager in MediaSessionManager for background JavaScript execution
        mediaSessionManager.setBackgroundWebViewManager(backgroundWebViewManager)
        
        // Set up media session callbacks
        mediaSessionManager.setWebViewProvider(object : WebViewProvider {
            override fun executeJavascript(script: String, callback: ((String) -> Unit)?) {
                getCurrentWebView()?.evaluateJavascript(script) { result ->
                    callback?.invoke(result)
                }
            }

            override fun getCurrentMediaState(callback: (title: String?, artist: String?, duration: Long?, position: Long?, isPlaying: Boolean?) -> Unit) {
                getCurrentWebView()?.evaluateJavascript("""
                    (function() {
                        const media = document.querySelector('audio,video');
                        if (media) {
                            return JSON.stringify({
                                title: media.title || document.title,
                                artist: media.artist || '',
                                duration: Math.round(media.duration * 1000),
                                position: Math.round(media.currentTime * 1000),
                                isPlaying: !media.paused
                            });
                        }
                        return null;
                    })();
                """.trimIndent()) { result ->
                    try {
                        if (result != null && result != "null") {
                            val json = JSONObject(result)
                            callback(
                                json.optString("title").takeIf { it != "null" },
                                json.optString("artist").takeIf { it != "null" },
                                json.optLong("duration").takeIf { it > 0 },
                                json.optLong("position").takeIf { it >= 0 },
                                json.optBoolean("isPlaying")
                            )
                        } else {
                            callback(null, null, null, null, false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing media state", e)
                        callback(null, null, null, null, false)
                    }
                } ?: run {
                    callback(null, null, null, null, false)
                }
            }
        })
        
        // Set up WebView provider for media controls
        mediaSessionManager.setWebViewProvider(object : WebViewProvider {
            override fun executeJavascript(script: String, callback: ((String) -> Unit)?) {
                getCurrentWebView()?.evaluateJavascript(script, callback ?: {})
            }

            override fun getCurrentMediaState(callback: (title: String?, artist: String?, duration: Long?, position: Long?, isPlaying: Boolean?) -> Unit) {
                getCurrentWebView()?.evaluateJavascript("""
                    (function() {
                        const media = document.querySelector('audio,video');
                        if (media) {
                            return JSON.stringify({
                                title: media.title || document.title,
                                artist: media.artist || '',
                                duration: Math.round(media.duration * 1000),
                                position: Math.round(media.currentTime * 1000),
                                isPlaying: !media.paused
                            });
                        }
                        return JSON.stringify({
                            title: null,
                            artist: null,
                            duration: null,
                            position: null,
                            isPlaying: false
                        });
                    })();
                """.trimIndent()) { result ->
                    try {
                        val json = JSONObject(result.replace("\"", "").replace("\"", ""))
                        callback(
                            json.optString("title").takeIf { it != "null" },
                            json.optString("artist").takeIf { it != "null" },
                            json.optLong("duration").takeIf { it > 0 },
                            json.optLong("position").takeIf { it >= 0 },
                            json.optBoolean("isPlaying")
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing media state", e)
                        callback(null, null, null, null, false)
                    }
                } ?: run {
                    callback(null, null, null, null, false)
                }            }        })

        // Initialize other managers (backgroundWebViewManager already initialized above)
        dialogStateManager = DialogStateManager()
        backgroundModeManager = BackgroundModeManager(
            applicationContext, 
            backgroundWebViewManager,
            lifecycleScope
        )
        networkManager = NetworkManager(this) { viewModel!! }
        lifecycleHandler = LifecycleHandler(
            this,
            { viewModel },
            backgroundModeManager,
            backgroundWebViewManager,
            mediaSessionManager
        )
        sharedContentHandler = SharedContentHandler(this, viewModel!!)
        intentHandler = IntentHandler(
            this, 
            viewModel!!, 
            dialogStateManager,
            filePickerLauncher
        )
    }



    /**
     * Set up the main UI
     */
    private fun setupMediaMonitoring(webView: WebView) {
        // Configure WebView for media playback
        webView.settings.apply {
            mediaPlaybackRequiresUserGesture = false
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // Set up WebChromeClient for media playback
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // Update media session when page loads
                if (newProgress > 50) {
                    updateMediaSession()
                }
            }
        }

        // Set up WebViewClient to handle page loading
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Update media session when page finishes loading
                updateMediaSession()
            }
        }
    }


    private fun updateMediaSession() {
        getCurrentWebView()?.let { webView ->
            webView.evaluateJavascript("""
                (function() {
                    const media = document.querySelector('audio,video');
                    if (media) {
                        return JSON.stringify({
                            title: media.title || document.title,
                            artist: media.artist || '',
                            duration: Math.round(media.duration * 1000),
                            position: Math.round(media.currentTime * 1000),
                            isPlaying: !media.paused
                        });
                    }
                    return null;
                })();
            """.trimIndent()) { result ->
                try {
                    if (result != null && result != "null") {
                        val json = JSONObject(result)
                        val title = json.optString("title").takeIf { it != "null" }
                        val artist = json.optString("artist").takeIf { it != "null" }
                        val duration = json.optLong("duration").takeIf { it > 0 }
                        val position = json.optLong("position").takeIf { it >= 0 }
                        val isPlaying = json.optBoolean("isPlaying")

                        if (title != null && duration != null && position != null) {
                            // Update metadata
                            mediaSessionManager.updateMetadata(
                                title = title,
                                artist = artist ?: "TiddlyWiki",
                                duration = duration
                            )
                            
                            // Update playback state
                            mediaSessionManager.updatePlaybackState(
                                isPlaying = isPlaying,
                                position = position
                            )
                            
                            // Bind to service if not already bound and media is playing
                            if (isPlaying) {
                                mediaSessionManager.bindToService()
                            }
                        } else {
                            // No active media, update state to stopped
                            mediaSessionManager.updatePlaybackState(false, 0)
                        }
                    } else {
                        // No media element found
                        mediaSessionManager.updatePlaybackState(false, 0)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating media session", e)
                    mediaSessionManager.updatePlaybackState(false, 0)
                }
            }
        } ?: run {
            mediaSessionManager.updatePlaybackState(false, 0)
        }
    }

    private fun setupUI() {
        setContent {
            val currentContext = LocalContext.current as ComponentActivity
            val viewModel = remember(currentContext) { getViewModel(currentContext) }
            val isDarkMode by viewModel.isDarkMode.collectAsState(initial = false)

            // Protect against composition errors with SideEffect
            SideEffect {
                // Assign the non-null ViewModel from remember to this.viewModel
                this@MainActivity.viewModel = viewModel
            }

            // Handle background mode state
            LaunchedEffect(Unit) {
                viewModel.currentWiki.collect { wiki ->
                    if (backgroundModeManager.isBackgroundEnabled.value && wiki != null) {
                        val key = wiki.idFromUrl ?: wiki.url
                        val webView = viewModel.getOrCreateWebView(wiki, this@MainActivity)
                        webView?.let {
                            // Ensure the WebView is properly initialized before registering
                            it.evaluateJavascript("""
                                (function() {
                                    return document.readyState;
                                })();
                            """.trimIndent()) { state ->
                                if (state.contains("complete") || state.contains("interactive")) {
                                    backgroundWebViewManager.registerWebView(key, it)
                                    Log.d("MainActivity", "Registered WebView for background mode: ${wiki.name}")
                                } else {
                                    // If not ready, wait for page load completion
                                    it.webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            if (view != null) {
                                                backgroundWebViewManager.registerWebView(key, view)
                                                Log.d("MainActivity", "Registered WebView after load: ${wiki.name}")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // The MaterialTheme
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
                            onAddClick = { dialogStateManager.showAddDialog = true },
                            onShowRenameDialog = { dialogStateManager.showRenameDialog = true }
                        )

                        // Handle dialog visibility
                        if (dialogStateManager.showAddDialog) {
                            AddWikiDialog(
                                onDismiss = { dialogStateManager.showAddDialog = false },
                                onAdd = { name, url ->
                                    viewModel.addWiki(name, url)
                                    dialogStateManager.showAddDialog = false
                                },
                                onAddLocalFile = {
                                    dialogStateManager.pendingWikiName = null
                                    filePickerLauncher.launch("*/*")
                                },
                                onCreateSingleFileWiki = {
                                    dialogStateManager.showAddDialog = false
                                    dialogStateManager.showTemplateSelectionDialog = true
                                }
                            )
                        }

                        if (dialogStateManager.showWikiSelector && dialogStateManager.pendingSharedText != null) {
                            WikiSelectionDialog(
                                wikis = viewModel.allWikis.collectAsState().value,
                                quickTags = viewModel.quickTags.collectAsState().value,
                                onDismiss = {
                                    dialogStateManager.closeWikiSelector()
                                    if (!isTaskRoot) {
                                        finish()
                                    }
                                },
                                onWikiSelected = { wiki, tags ->
                                    sharedContentHandler.handleWikiSelection(
                                        wiki, 
                                        dialogStateManager.pendingSharedText, 
                                        tags
                                    )
                                    dialogStateManager.closeWikiSelector()
                                    if (!isTaskRoot) {
                                        finish()
                                    }
                                },
                                onAddNew = {
                                    dialogStateManager.showWikiSelector = false
                                    dialogStateManager.showAddDialog = true
                                }
                            )
                        }

                        // Add TiddlerTemplateSelectionDialog
                        if (dialogStateManager.showTemplateSelectionDialog) {
                            TiddlerTemplateSelectionDialog(
                                onDismiss = { dialogStateManager.showTemplateSelectionDialog = false },
                                onTemplateSelected = { template ->
                                    dialogStateManager.showTemplateSelectionDialog = false
                                    viewModel.createSingleFileWiki(this@MainActivity, template)
                                }
                            )
                        }

                        if (dialogStateManager.showDeleteConfirmDialog && viewModel.currentWiki.value != null) {
                            AlertDialog(
                                onDismissRequest = { dialogStateManager.showDeleteConfirmDialog = false },
                                title = { Text("Delete Wiki") },
                                text = { Text("Are you sure you want to delete '${viewModel.currentWiki.value?.name}'?") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        viewModel.currentWiki.value?.let { wiki ->
                                            viewModel.deleteWiki(wiki)
                                        }
                                        dialogStateManager.showDeleteConfirmDialog = false
                                    }) {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        dialogStateManager.showDeleteConfirmDialog = false
                                    }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }

                        if (dialogStateManager.showRenameDialog && viewModel.currentWiki.value != null) {
                            RenameWikiDialog(
                                currentName = viewModel.currentWiki.value?.name ?: "",
                                onDismiss = { dialogStateManager.showRenameDialog = false },
                                onRename = { newName ->
                                    viewModel.currentWiki.value?.let { wiki ->
                                        viewModel.renameWiki(wiki, newName)
                                    }
                                    dialogStateManager.showRenameDialog = false
                                }
                            )
                        }

                        // Tiddler Transfer Dialogs
                        if (tiddlerTransferState.showTiddlerSelectionDialog) {
                            TiddlerTransferManager.TiddlerSelectionDialog(
                                tiddlers = tiddlerTransferState.availableTiddlers,
                                onDismiss = { tiddlerTransferState.showTiddlerSelectionDialog = false },
                                onConfirm = { selectedTiddlers ->
                                    tiddlerTransferState.showTiddlerSelectionDialog = false
                                    if (selectedTiddlers.isNotEmpty()) {
                                        TiddlerTransferManager.showWikiSelectionDialog(
                                            this@MainActivity,
                                            selectedTiddlers,
                                            tiddlerTransferState.sourceWiki!!,
                                            tiddlerTransferState.sourceWebView!!,
                                            viewModel
                                        )
                                    }
                                }
                            )
                        }

                        if (tiddlerTransferState.showWikiSelectionDialog) {
                            TiddlerTransferManager.WikiSelectionDialog(
                                wikis = tiddlerTransferState.availableWikis,
                                onDismiss = { tiddlerTransferState.showWikiSelectionDialog = false },
                                onWikiSelected = { targetWiki ->
                                    tiddlerTransferState.showWikiSelectionDialog = false
                                    TiddlerTransferManager.performTransfer(
                                        this@MainActivity,
                                        tiddlerTransferState.sourceWebView!!,
                                        targetWiki,
                                        tiddlerTransferState.selectedTiddlers,
                                        viewModel
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun loadPreferences() {
        // Load other preferences here (background mode is already loaded by BackgroundModeManager)

        // Start the background services if enabled
        backgroundModeManager.startServicesIfEnabled { startMediaService() }
    }

    /**
     * Toggle background mode on/off
     */
    fun toggleBackgroundMode() {
        backgroundModeManager.toggleBackgroundMode(
            viewModel,
            this,
            { startMediaService() },
            { stopService(serviceIntent) }
        )
    }

    /**
     * Set background mode enabled/disabled
     */
    fun setBackgroundEnabled(enabled: Boolean) {
        backgroundModeManager.setBackgroundEnabled(
            enabled,
            viewModel,
            this,
            { startMediaService() },
            { stopService(serviceIntent) }
        )    }    override fun onPause() {
        super.onPause()
        lifecycleHandler.onPause()
        
        // Notify MediaSessionManager that app is backgrounded
        try {
            mediaSessionManager.onAppBackgrounded()
            // Also notify RobustMediaController
            RobustMediaController.getInstance(this).onAppBackgrounded()
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying MediaSessionManager of app background", e)
        }
        
        // Update media session state when activity is paused
        updateMediaSession()
    }    override fun onStop() {
        super.onStop()
        lifecycleHandler.onStop(isChangingConfigurations)
    }

    override fun onDestroy() {
        // Release media session manager first
        try {
            mediaSessionManager.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaSessionManager", e)
        }
        
        // Release other managers
        backgroundWebViewManager.release()
        networkManager.release()

        // Unbind the service connection
        serviceConnection?.let { connection ->
            try {
                unbindService(connection)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
            serviceConnection = null
        }

        super.onDestroy()
    }

    // Implement the onLowMemory callback
    override fun onLowMemory() {
        super.onLowMemory()
        lifecycleHandler.onLowMemory()
    }

    // Implement onTrimMemory
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        lifecycleHandler.onTrimMemory(level)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentHandler.handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        WebViewCache.setConfigurationChanging(true)

        // Handle any WebViews that need to be retained during configuration changes
        val safeViewModel = viewModel
        if (safeViewModel != null) {
            safeViewModel.currentWiki.value?.let { wiki ->
                val key = wiki.idFromUrl ?: wiki.url
                WebViewCache.setCurrentActiveKey(key)
            }
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


    /**
     * Apply WebView configuration settings
     */
    private fun applyWebViewConfiguration() {
        // Initialize WebView with appropriate settings
        WebView.setWebContentsDebuggingEnabled(true)

        // Set default cache mode
        val webViewDatabase = WebViewDatabase.getInstance(this)
        CookieManager.getInstance().setAcceptCookie(true)

        // Ensure cookies are persisted to disk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush()
        }
    }



    /**
     * Get the current WebView instance from the ViewModel
     * @return The current WebView or null if not available
     */
    internal fun getCurrentWebView(): WebView? {
        return viewModel?.currentWiki?.value?.let { wiki ->
            viewModel?.getOrCreateWebView(wiki, this)?.also { webView ->
                // Ensure media monitoring is set up for the WebView
                if (webView.webViewClient == null) {
                    setupMediaMonitoring(webView)
                }
            }
        }
    }

    /**
     * Start the media service
     */
    fun startMediaService() {
        Log.d("MainActivity", "Starting media service")
        try {
            // First check if the service is already running
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (MediaPlaybackService::class.java.name == service.service.className) {
                    Log.d("MainActivity", "Media service is already running")
                    return
                }
            }

            // Start the service based on Android version
            val intent = Intent(this, MediaPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(intent)
                    Log.d("MainActivity", "Started media service as foreground service")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error starting foreground service", e)
                    // Fall back to regular service
                    startService(intent)
                }
            } else {
                startService(intent)
                Log.d("MainActivity", "Started media service as regular service")
            }

            // Bind to the service if needed
            if (serviceConnection == null) {
                serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        Log.d("MainActivity", "Connected to media service")
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        Log.d("MainActivity", "Disconnected from media service")
                    }
                }

                bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting media service", e)
            Toast.makeText(this, "Error starting background audio", Toast.LENGTH_SHORT).show()
        }
    }

    // Add a new method to register the initial WebView
    private fun registerInitialWebViewObserver() {
        viewModel?.currentWiki?.let { wikiFlow ->
            lifecycleScope.launch {
                try {
                    Log.d("MainActivity", "Starting initial WebView observer, background mode: ${backgroundModeManager.isBackgroundEnabled.value}")

                    wikiFlow.collect { wiki ->
                        wiki?.let {
                            // Ensure WebView is registered when first loaded
                            if (backgroundModeManager.isBackgroundEnabled.value) {
                                Log.d("MainActivity", "Background mode enabled, registering WebView for wiki: ${wiki.name}")

                                val key = wiki.idFromUrl ?: wiki.url
                                val webView = viewModel?.getOrCreateWebView(wiki, this@MainActivity)

                                webView?.let { view ->
                                    // Configure WebView settings early
                                    ThreadManager.runOnMain {
                                        view.settings.apply {
                                            javaScriptEnabled = true
                                            domStorageEnabled = true
                                            databaseEnabled = true
                                            mediaPlaybackRequiresUserGesture = false
                                            setGeolocationEnabled(false)
                                        }
                                    }

                                    // Set up WebView client to handle registration after page load
                                    view.webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            Log.d("MainActivity", "Page loading started for ${wiki.name}")

                                            // Configure WebView settings early
                                            ThreadManager.runOnMain {
                                                view?.settings?.apply {
                                                    javaScriptEnabled = true
                                                    domStorageEnabled = true
                                                    databaseEnabled = true
                                                    mediaPlaybackRequiresUserGesture = false
                                                }
                                            }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            if (view != null && backgroundModeManager.isBackgroundEnabled.value) {
                                                Log.d("MainActivity", "Page load finished, registering for background: ${wiki.name}")

                                                // Initialize state preservation and background mode
                                                view.evaluateJavascript("""
                                                    (function() {
                                                        // Initialize state container
                                                        window.__savedState = window.__savedState || {};
                                                        
                                                        // Set up state preservation handlers if not already done
                                                        if (!window.__statePreservationHandlersAttached) {
                                                            document.addEventListener('pause', function() {
                                                                try {
                                                                    // Capture scroll position
                                                                    window.__savedState.scrollPosition = window.scrollY;
                                                                    
                                                                    // Capture media states
                                                                    const mediaElements = document.querySelectorAll('audio,video');
                                                                    window.__savedState.mediaStates = Array.from(mediaElements).map(el => ({
                                                                        src: el.src,
                                                                        currentTime: el.currentTime,
                                                                        isPlaying: !el.paused
                                                                    }));
                                                                    
                                                                    console.log('[Background] State preserved:', JSON.stringify(window.__savedState));
                                                                } catch(e) {
                                                                    console.error('[Background] Error saving state:', e);
                                                                }
                                                            });
                                                            
                                                            document.addEventListener('resume', function() {
                                                                try {
                                                                    if (window.__savedState.scrollPosition) {
                                                                        window.scrollTo(0, window.__savedState.scrollPosition);
                                                                    }
                                                                    if (window.__savedState.mediaStates) {
                                                                        window.__savedState.mediaStates.forEach(state => {
                                                                            const el = Array.from(document.querySelectorAll('audio,video'))
                                                                                .find(e => e.src === state.src);
                                                                            if (el) {
                                                                                el.currentTime = state.currentTime;
                                                                                if (state.isPlaying) el.play();
                                                                            }
                                                                        });
                                                                    }
                                                                    console.log('[Background] State restored');
                                                                } catch(e) {
                                                                    console.error('[Background] Error restoring state:', e);
                                                                }
                                                            });
                                                            
                                                            window.__statePreservationHandlersAttached = true;
                                                            console.log('[Background] State preservation initialized');
                                                        }
                                                        
                                                        // Set up periodic state saving
                                                        if (!window.__stateSaveInterval) {
                                                            window.__stateSaveInterval = setInterval(function() {
                                                                if (document.visibilityState === 'hidden') {
                                                                    document.dispatchEvent(new Event('pause'));
                                                                }
                                                            }, 30000);
                                                        }
                                                        
                                                        // Enable background audio
                                                        if (!window.__audioEnabled) {
                                                            try {
                                                                const audioContext = new (window.AudioContext || window.webkitAudioContext)();
                                                                window.__audioEnabled = true;
                                                                console.log('[Background] Audio enabled');
                                                            } catch(e) {
                                                                console.error('[Background] Error enabling audio:', e);
                                                            }
                                                        }
                                                        
                                                        return true;
                                                    })();
                                                """.trimIndent()) { _ ->
                                                    // After initialization, register the WebView
                                                    if (backgroundModeManager.isBackgroundEnabled.value) {
                                                        backgroundWebViewManager.registerWebView(key, view)
                                                        Log.d("MainActivity", "Successfully registered WebView for background mode: ${wiki.name}")

                                                        // Trigger media monitoring script
                                                        view.evaluateJavascript(mediaMonitorScript, null)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error in initial WebView observer", e)
                }
            }
        }
    }

    /**
     * Helper method to set the visibility of the main content (WebView containers)
     */
    fun setMainContentVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE

        // Try to find containers using different potential IDs
        try {
            // Try common container IDs
            val containerIds = arrayOf(
                "webview_container", "webview_container_layout",
                "content_layout", "main_content", "webview_parent"
            )

            var foundContainer = false
            for (id in containerIds) {
                val resId = resources.getIdentifier(id, "id", packageName)
                if (resId != 0) {
                    findViewById<View>(resId)?.visibility = visibility
                    foundContainer = true
                }
            }

            // If no specific container found, try to manage the root layout
            if (!foundContainer) {
                // Find the root content view and apply visibility to direct children
                val rootContent = findViewById<ViewGroup>(android.R.id.content)
                if (rootContent != null && rootContent.childCount > 0) {
                    for (i in 0 until rootContent.childCount) {
                        val child = rootContent.getChildAt(i)
                        // Don't hide the view that might contain our custom view
                        if (child !is FrameLayout) {
                            child.visibility = visibility
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error changing content visibility: ${e.message}")
        }
    }
}





