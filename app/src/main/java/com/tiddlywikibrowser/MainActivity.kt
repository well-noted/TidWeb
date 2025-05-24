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
import com.tiddlywikibrowser.webview.WebViewFactory
import com.tiddlywikibrowser.webview.MediaInterface
import com.tiddlywikibrowser.media.MediaSessionManager
import com.tiddlywikibrowser.cache.WebViewCache
import com.tiddlywikibrowser.TiddlerTransferManager

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
    internal lateinit var backgroundWebViewManager: BackgroundWebViewManager
    private val TAG = "MainActivity"
    private var currentWebView: WebView? = null
    var viewModel: WikiViewModel? = null
    private var serviceConnection: ServiceConnection? = null
    private val serviceIntent by lazy { Intent(this, MediaPlaybackService::class.java) }

    // Initialize TiddlerTransferState
    val tiddlerTransferState = TiddlerTransferManager.TiddlerTransferState()

    // Dialog state variables
    private var pendingSharedText by mutableStateOf<String?>(null)
    private var showWikiSelector by mutableStateOf(false)
    private var showAddDialog by mutableStateOf(false)
    private var showDeleteConfirmDialog by mutableStateOf(false)
    private var showShareMenu by mutableStateOf(false)
    private var showTagManagement by mutableStateOf(false)
    private var showRenameDialog by mutableStateOf(false)
    private var showTemplateSelectionDialog by mutableStateOf(false)

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

    // Keep track of background mode preference
    internal val _isBackgroundEnabled = MutableStateFlow(false)
    internal val isBackgroundEnabled: StateFlow<Boolean> = _isBackgroundEnabled

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

        // Initialize MediaSessionManager
        mediaSessionManager = MediaSessionManager.getInstance(this)

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
                }
            }
        })

        exoPlayerManager = ExoPlayerManager(this)
        backgroundWebViewManager = BackgroundWebViewManager(applicationContext)

        try {
            // Initialize WebView configuration first
            applyWebViewConfiguration()

            // Request notification permission for Android 13+
            requestNotificationPermission()

            // Synchronously load critical preferences before initializing services
            loadBackgroundModePreference()

            // Load remaining preferences
            loadPreferences()

            // Process intent after core components are initialized
            handleIntent(intent)

            // Set up the UI last
            setupUI()

            // Set up network monitoring
            setupNetworkMonitoring()

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
    }

    /**
     * Synchronously load the background mode preference
     * This is critical to have loaded before initializing services
     */
    private fun loadBackgroundModePreference() {
        try {
            val dataStore = applicationContext.dataStore
            val preferences = kotlinx.coroutines.runBlocking {
                dataStore.data.first()
            }
            val isEnabled = preferences[PreferencesKeys.BACKGROUND_MODE_ENABLED] ?: false
            _isBackgroundEnabled.value = isEnabled

            Log.d("MainActivity", "Background mode preference loaded synchronously: $isEnabled")

            // We need this to be available immediately, so we initialize the value synchronously
            if (isEnabled) {
                // Don't start services here, will be done in loadPreferences
                Log.d("MainActivity", "Background mode will be enabled during initialization")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading background mode preference", e)
            // Default to false if there's an error
            _isBackgroundEnabled.value = false
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
                // Assign the non-null ViewModel from remember to this.viewModel
                this@MainActivity.viewModel = viewModel
            }

            // Handle background mode state
            LaunchedEffect(Unit) {
                viewModel.currentWiki.collect { wiki ->
                    if (_isBackgroundEnabled.value && wiki != null) {
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
                                },
                                onCreateSingleFileWiki = {
                                    showAddDialog = false
                                    showTemplateSelectionDialog = true
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

                        // Add TiddlerTemplateSelectionDialog
                        if (showTemplateSelectionDialog) {
                            TiddlerTemplateSelectionDialog(
                                onDismiss = { showTemplateSelectionDialog = false },
                                onTemplateSelected = { template ->
                                    showTemplateSelectionDialog = false
                                    viewModel.createSingleFileWiki(this@MainActivity, template)
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
                                    TextButton(onClick = {
                                        showDeleteConfirmDialog = false
                                    }) {
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
        // Load other preferences here (background mode is already loaded by loadBackgroundModePreference)

        // Start the background services if enabled
        if (_isBackgroundEnabled.value) {
            Log.d("MainActivity", "Starting background services from loadPreferences")

            // Start both background manager service and media service
            backgroundWebViewManager.startBackgroundService()

            // Use a slight delay to ensure the background service is bound first
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    startMediaService()
                    Log.d("MainActivity", "Media service started on app initialization")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error starting media service during initialization", e)
                }
            }, 500)

            Log.d("MainActivity", "Background mode initialized from preferences")
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
            startMediaService() // Start media service for background audio

            // Register the current WebView
            viewModel?.currentWiki?.value?.let { wiki ->
                val key = wiki.idFromUrl ?: wiki.url
                val webView = viewModel?.getOrCreateWebView(wiki, this)
                webView?.let {
                    // Ensure WebView is ready before registering
                    it.evaluateJavascript("""
                        (function() {
                            // Initialize state preservation if needed
                            if (!window.__statePreservationHandlersAttached) {
                                document.addEventListener('pause', function() {
                                    try {
                                        console.log('[State] Saving wiki state');
                                        // Add any additional state saving logic here
                                    } catch(e) {
                                        console.error('[State] Error saving state:', e);
                                    }
                                });
                                
                                document.addEventListener('resume', function() {
                                    try {
                                        console.log('[State] Restoring wiki state');
                                        // Add any additional state restoration logic here
                                    } catch(e) {
                                        console.error('[State] Error restoring state:', e);
                                    }
                                });
                                
                                window.__statePreservationHandlersAttached = true;
                                console.log('[State] State preservation initialized');
                            }

                            // Enable background audio
                            if (!window.__audioEnabled) {
                                const audioContext = new (window.AudioContext || window.webkitAudioContext)();
                                window.__audioEnabled = true;
                                console.log('[Audio] Background audio enabled');
                            }

                            return document.readyState;
                        })();
                    """.trimIndent()) { state ->
                        if (state.contains("complete") || state.contains("interactive")) {
                            backgroundWebViewManager.registerWebView(key, it)
                            Log.d("BackgroundMode", "Registered WebView for background mode: ${wiki.name}")

                            // Dispatch a custom event to notify the wiki
                            it.evaluateJavascript("""
                                (function() {
                                    document.dispatchEvent(new CustomEvent('backgroundModeEnabled'));
                                    return true;
                                })();
                            """.trimIndent(), null)
                        } else {
                            // If not ready, set up a load listener
                            it.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (view != null && _isBackgroundEnabled.value) {
                                        backgroundWebViewManager.registerWebView(key, view)
                                        Log.d("BackgroundMode", "Registered WebView after load: ${wiki.name}")
                                    }
                                }
                            }
                        }
                    }

                    // Configure WebView settings for background audio
                    it.settings.mediaPlaybackRequiresUserGesture = false
                    it.settings.javaScriptCanOpenWindowsAutomatically = true

                    // Inform the user
                    Toast.makeText(this,
                        "Background mode enabled. TiddlyWiki will continue running when minimized.",
                        Toast.LENGTH_LONG).show()
                }
            }
        } else {
            // Clean up background mode
            viewModel?.currentWiki?.value?.let { wiki ->
                val key = wiki.idFromUrl ?: wiki.url
                val webView = getCurrentWebView()
                webView?.let {
                    // Save state before disabling background mode
                    WebViewCache.cacheWebView(key, it)

                    // Notify the wiki that background mode is being disabled
                    it.evaluateJavascript("""
                        (function() {
                            document.dispatchEvent(new CustomEvent('backgroundModeDisabled'));
                            return true;
                        })();
                    """.trimIndent(), null)
                }
            }

            backgroundWebViewManager.stopBackgroundService()
            stopService(serviceIntent) // Stop media service

            // Inform the user
            Toast.makeText(this,
                "Background mode disabled.",
                Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Set background mode enabled/disabled
     */
    fun setBackgroundEnabled(enabled: Boolean) {
        if (_isBackgroundEnabled.value != enabled) {
            Log.d("MainActivity", "Setting background mode to: $enabled")
            _isBackgroundEnabled.value = enabled

            // Save the preference
            saveBackgroundModePreference(enabled)

            if (enabled) {
                // First, clear any WebView cache that may have the wrong settings
                viewModel?.currentWiki?.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    // Just clean up the cache entry, don't destroy the view yet
                    WebViewCache.clearCacheEntry(key)
                }

                // Start services first
                backgroundWebViewManager.startBackgroundService()

                // Use a slight delay to ensure the background service is initialized
                Handler(Looper.getMainLooper()).postDelayed({
                    startMediaService()

                    // Register current WebView after service is started
                    viewModel?.currentWiki?.value?.let { wiki ->
                        Log.d("MainActivity", "Registering current wiki for background mode: ${wiki.name}")
                        viewModel?.getOrCreateWebView(wiki, this)?.let { webView ->
                            registerWebViewForBackground(wiki, webView)

                            // Force registration regardless of cache state
                            val key = wiki.idFromUrl ?: wiki.url
                            if (!backgroundWebViewManager.hasWebView(key)) {
                                Log.d("MainActivity", "Force registering WebView for background mode: ${wiki.name}")
                                backgroundWebViewManager.registerWebView(key, webView)
                            }
                        }
                    }
                }, 500)

                Toast.makeText(this,
                    "Background mode enabled. TiddlyWiki will continue running when minimized.",
                    Toast.LENGTH_LONG).show()
            } else {
                // Clean up background mode
                Log.d("MainActivity", "Disabling background mode")
                viewModel?.currentWiki?.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    val webView = getCurrentWebView()
                    webView?.let {
                        // Trigger state preservation before disabling
                        it.evaluateJavascript("""
                            (function() {
                                console.log('[Background] Disabling background mode');
                                document.dispatchEvent(new Event('pause'));
                                document.dispatchEvent(new CustomEvent('backgroundModeDisabled'));
                                
                                // Clean up any background mode intervals
                                if (window.__stateSaveInterval) {
                                    clearInterval(window.__stateSaveInterval);
                                    delete window.__stateSaveInterval;
                                }
                                
                                return true;
                            })();
                        """.trimIndent(), null)

                        // Cache the WebView state
                        WebViewCache.cacheWebView(key, it)
                    }
                }

                backgroundWebViewManager.stopBackgroundService()
                stopService(serviceIntent)

                Toast.makeText(this,
                    "Background mode disabled.",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Register a WebView for background operation with state preservation
     * @param wiki The wiki instance associated with this WebView
     * @param webView The WebView to register for background processing
     */
    private fun registerWebViewForBackground(wiki: WikiInstance, webView: WebView) {
        val key = wiki.idFromUrl ?: wiki.url

        // First ensure the WebView is properly configured for background operation
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(false)
        }

        // Initialize state preservation handlers
        webView.evaluateJavascript("""
            (function() {
                if (!window.__backgroundModeInitialized) {
                    // Set up state container
                    window.__savedState = window.__savedState || {};
                    
                    // Add state preservation handlers
                    document.addEventListener('pause', function() {
                        try {
                            // Capture current scroll position
                            window.__savedState.scrollPosition = window.scrollY;
                            // Capture any active media states
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
                    
                    // Set up periodic state saving
                    window.__stateSaveInterval = setInterval(function() {
                        if (document.visibilityState === 'hidden') {
                            document.dispatchEvent(new Event('pause'));
                        }
                    }, 30000); // Save state every 30 seconds when in background
                    
                    window.__backgroundModeInitialized = true;
                }
                return document.readyState;
            })();
        """.trimIndent()) { state ->
            if (state.contains("complete") || state.contains("interactive")) {
                backgroundWebViewManager.registerWebView(key, webView)
                Log.d("BackgroundMode", "Registered WebView with state preservation: ${wiki.name}")
            } else {
                // Set up a load listener if the page isn't ready
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (view != null && _isBackgroundEnabled.value) {
                            backgroundWebViewManager.registerWebView(key, view)
                            Log.d("BackgroundMode", "Registered WebView after load completion: ${wiki.name}")
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webViewPaused = true

        if (!_isBackgroundEnabled.value) {
            Log.d("MainActivity", "onPause - Background mode disabled, performing standard pause.")
            exoPlayerManager.onPause()
            viewModel?.let { vm ->
                vm.currentWiki.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    // Cache only if background mode is off
                    WebViewCache.cacheWebView(key, vm.getOrCreateWebView(wiki, this))
                }
                // Pause all WebViews *only* if background mode is off
                vm.pauseAllWebViews()
            }
        } else {
            // Special handling for background mode
            Log.d("MainActivity", "onPause - Background mode enabled, skipping standard pause actions.")
            viewModel?.currentWiki?.value?.let { wiki ->
                viewModel?.getOrCreateWebView(wiki, this)?.let { webView ->
                    // Re-register WebView if needed
                    val key = wiki.idFromUrl ?: wiki.url
                    if (!backgroundWebViewManager.hasWebView(key)) {
                        Log.w("MainActivity", "Re-registering WebView for background mode on pause (was it lost?)")
                        registerWebViewForBackground(wiki, webView)
                    }

                    // Make sure the media service is running
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to start media service in onPause", e)
                    }

                    // Trigger state preservation
                    webView.evaluateJavascript("""
                         (function() {
                             document.dispatchEvent(new Event('pause'));
                             return true;
                         })();
                     """.trimIndent(), null)

                    // Force resume videos to ensure they keep playing in background
                    backgroundWebViewManager.service?.forceResumeVideos()

                    // Notify the service directly that app went to background
                    val serviceIntent = Intent(this, BackgroundWebViewService::class.java).apply {
                        action = BackgroundWebViewService.ACTION_APP_BACKGROUND
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                        Log.d("MainActivity", "Sent app background notification to service")
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to send background notification to service", e)
                    }

                    // Notify the video should keep playing when hidden
                    webView.evaluateJavascript("""
                        (function() {
                            // Mark all playing videos to continue in background
                            document.querySelectorAll('video').forEach(function(video) {
                                if (!video.paused && !video.ended) {
                                    video.__shouldBePlaying = true;
                                    console.log('[BackgroundVideo] Video should keep playing in background');
                                }
                            });
                            return true;
                        })();
                    """.trimIndent(), null)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webViewPaused = false
        exoPlayerManager.onResume()

        // Log the current background mode state for diagnosis
        Log.d("MainActivity", "onResume - Background mode is ${if (_isBackgroundEnabled.value) "ENABLED" else "DISABLED"}")

        // Set the WebViewProvider for MediaSessionManager
        mediaSessionManager.setWebViewProvider(object : WebViewProvider {
            override fun executeJavascript(script: String, callback: ((String) -> Unit)?) {
                getCurrentWebView()?.evaluateJavascript(script, callback)
            }

            override fun getCurrentMediaState(callback: (title: String?, artist: String?, duration: Long?, position: Long?, isPlaying: Boolean?) -> Unit) {
                getCurrentWebView()?.evaluateJavascript("(" + Companion.mediaMonitorScript + ")()") { result ->
                    // Process the result from mediaMonitorScript to extract media state
                    try {
                        // Check if result is null, empty, or not a JSON string
                        if (result.isNullOrEmpty() || result == "null" || result == "undefined") {
                            Log.d("MainActivity", "No valid media state returned from JS")
                            callback(null, null, null, null, null)
                            return@evaluateJavascript
                        }
                        
                        // Handle string escaping and remove quotes if present
                        val jsonString = result.trim()
                            .let { if (it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1) else it }
                            .replace("\\\\", "\\")
                            .replace("\\n", "")
                        
                        // Check if the content is a valid JSON
                        if (!jsonString.startsWith("{")) {
                            Log.d("MainActivity", "Invalid JSON format: $jsonString")
                            callback(null, null, null, null, null)
                            return@evaluateJavascript
                        }
                        
                        val json = JSONObject(jsonString)
                        if (json.optBoolean("exists", false)) {
                            val title = json.optString("title", getCurrentWebView()?.title ?: "TiddlyWiki Media")
                            val artist = json.optString("artist", "Unknown Artist")
                            val duration = json.optLong("duration", 0)
                            val position = json.optLong("position", 0)
                            val isPlaying = json.optBoolean("playing", false)
                            Log.d("MainActivity", "Media state: title=$title, duration=$duration, playing=$isPlaying")
                            callback(title, artist, duration, position, isPlaying)
                        } else {
                            Log.d("MainActivity", "No active media found in JSON response")
                            callback(null, null, null, null, null)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error parsing media state from JS: ${result}", e)
                        callback(null, null, null, null, null)
                    }
                }
            }
        })

        if (webViewPaused) {
            currentWebView?.onResume() // Use the class property
            currentWebView?.resumeTimers() // Use the class property
            webViewPaused = false
            Log.d("MainActivity", "WebView resumed and timers started.")
        }

        if (!_isBackgroundEnabled.value) {
            // Standard behavior
            Log.d("MainActivity", "onResume - Background mode disabled, resuming standard WebView.")
            viewModel?.resumeCurrentWebView(viewModel?.currentWiki?.value)
        } else {
            // Special handling for background mode
            Log.d("MainActivity", "Resuming with background mode enabled")
            val currentWiki = viewModel?.currentWiki?.value
            if (currentWiki != null) {
                val key = currentWiki.idFromUrl ?: currentWiki.url
                var webView = backgroundWebViewManager.getWebView(key) // Try to get from manager first

                if (webView == null) {
                    Log.w("MainActivity", "WebView not found in BackgroundWebViewManager on resume, attempting fallback retrieval.")
                    // Fallback to the standard retrieval method if not found in manager
                    // This might happen if registration failed or the manager lost the view
                    webView = viewModel?.getOrCreateWebView(currentWiki, this)

                    // If we got a view via fallback, ensure it's registered (again)
                    if (webView != null && !backgroundWebViewManager.hasWebView(key)) {
                        Log.d("MainActivity", "Re-registering WebView for background mode on resume")
                        registerWebViewForBackground(currentWiki, webView)
                    }
                }

                webView?.let { wv ->
                    try {
                        // Check if WebView is not destroyed before using it
                        if (!isWebViewDestroyed(wv)) {
                            // Make WebView visible and active
                            wv.visibility = View.VISIBLE
                            wv.onResume()

                            // Only evaluate JavaScript if WebView is not destroyed
                            try {
                                wv.evaluateJavascript("""
                                    (function() {
                                        document.dispatchEvent(new Event('resume'));
                                        return true;
                                    })();
                                """.trimIndent(), null)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error dispatching resume event to WebView: ${e.message}")
                            }
                        } else {
                            Log.w("MainActivity", "Skipping resume operations on destroyed WebView")
                            // Force recreation of this WebView
                            WebViewCache.removeCachedWebView(key)
                            viewModel?.getOrCreateWebView(currentWiki, this)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error resuming WebView: ${e.message}")
                        // Force recreation of this WebView
                        WebViewCache.removeCachedWebView(key)
                        viewModel?.getOrCreateWebView(currentWiki, this)
                    }
                } ?: run {
                    Log.e("MainActivity", "Failed to get WebView instance on resume in background mode for wiki: ${currentWiki.name}")
                }
            }
        }

        // Update media session
        try {
            getCurrentWebView()?.let { webView ->
                if (!isWebViewDestroyed(webView)) {
                    mediaSessionManager.setWebView(webView)
                    // Only bind to service if we haven't already bound
                    if (!mediaSessionManager.isServiceBound()) {
                        mediaSessionManager.bindToService()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error updating media session: ${e.message}")
        }
    }

    /**
     * Safely check if a WebView is destroyed to avoid crashes
     */
    private fun isWebViewDestroyed(webView: WebView): Boolean {
        return try {
            // A simple and safe test - try to get the settings which will fail for destroyed WebViews
            webView.settings
            false // If we get here, WebView is not destroyed
        } catch (e: Exception) {
            Log.w("MainActivity", "WebView appears to be destroyed: ${e.message}")
            true // WebView is likely destroyed
        }
    }

    override fun onStop() {
        super.onStop()
        // Don't cleanup if we're just changing configurations OR if background mode is enabled
        if (!isChangingConfigurations && !_isBackgroundEnabled.value) {
            // Perform standard cleanup/caching only if background mode is DISABLED and not changing config
            Log.d("MainActivity", "onStop - Background mode disabled and not changing config, performing standard cleanup.")
            viewModel?.let { vm ->
                vm.currentWiki.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    // Save state using cache before stopping
                    vm.getOrCreateWebView(wiki, this)?.let { webView ->
                        WebViewCache.cacheWebView(key, webView)
                    }
                }
            }
        } else if (_isBackgroundEnabled.value) {
            // If background mode is enabled, ensure videos keep playing in background
            Log.d("MainActivity", "onStop - Background mode enabled, ensuring videos continue in background.")

            // Force resume any videos that should be playing
            backgroundWebViewManager.forceResumeVideos()

            // Also make sure the service is still running with explicit background action
            try {
                val serviceIntent = Intent(this, BackgroundWebViewService::class.java).apply {
                    action = BackgroundWebViewService.ACTION_APP_BACKGROUND
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d("MainActivity", "Sent explicit app background notification in onStop")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error ensuring service is running in onStop", e)
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
        Log.d("MainActivity", "onLowMemory called - cleaning up resources")
        WebViewCache.onLowMemory()
        viewModel?.onLowMemory()
    }

    // Implement onTrimMemory
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("MainActivity", "onTrimMemory called with level: $level")

        // In background mode, we need to be more careful about memory management
        if (_isBackgroundEnabled.value) {
            // Only mark state as uncertain - don't remove the WebView
            viewModel?.currentWiki?.value?.let { wiki ->
                val key = wiki.idFromUrl ?: wiki.url
                WebViewCache.markWebViewStateUncertain(key)

                // Make sure the WebView is registered with the background service
                if (backgroundWebViewManager.hasWebView(key)) {
                    Log.d("MainActivity", "WebView is registered with background service, skipping state marking")
                } else {
                    // If it's not in the background service, try to recover
                    Log.d("MainActivity", "WebView not found in background service, attempting recovery")
                    viewModel?.getOrCreateWebView(wiki, this)?.let { webView ->
                        backgroundWebViewManager.registerWebView(key, webView)
                    }
                }
            }
        }

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
                    ThreadManager.runOnMain {
                        // Update offline status in ViewModel
                        val viewModel = getViewModel(this@MainActivity)
                        viewModel.setOfflineState(true)
                        Log.d("NetworkMonitor", "Network lost - App is now offline")

                        // Update WebView cache mode for offline operation
                        viewModel.currentWiki.value?.let { wiki ->
                            if (!wiki.isLocalFile) {
                                viewModel.getOrCreateWebView(wiki, this@MainActivity)?.let { webView ->
                                    webView.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY
                                }
                            }
                        }
                    }
                }
            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNetworkCheckTime > NETWORK_CHECK_THROTTLE) {
                    lastNetworkCheckTime = currentTime

                    // Set offline to false immediately after network becomes available
                    ThreadManager.runOnMain {
                        val viewModel = getViewModel(this@MainActivity)
                        viewModel.setOfflineState(false)
                        Log.d("NetworkMonitor", "Network available - App is now online")

                        // Restore normal cache mode
                        viewModel.currentWiki.value?.let { wiki ->
                            if (!wiki.isLocalFile) {
                                viewModel.getOrCreateWebView(wiki, this@MainActivity)?.let { webView ->
                                    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                                }
                            }
                        }
                    }

                    // Validate internet access in the background, but don't wait for it
                    ThreadManager.runOnBackground {
                        val capabilities = connectivityManager.getNetworkCapabilities(network)
                        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                        if (!hasInternet) {
                            Log.d("NetworkMonitor", "Network available but internet validation failed")
                        }
                    }
                }
            }
        }

        // Register network callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback!!)

                // Initial check for network state
                ThreadManager.runOnBackground {
                    val activeNetwork = connectivityManager.activeNetwork
                    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                    val isOffline = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true ||
                            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true

                    ThreadManager.runOnMain {
                        val viewModel = getViewModel(this@MainActivity)
                        viewModel.setOfflineState(isOffline)
                        Log.d("NetworkMonitor", "Initial network state: ${if (isOffline) "OFFLINE" else "ONLINE"}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback in case of exception - assume we might be offline
                val viewModel = getViewModel(this@MainActivity)
                viewModel.setOfflineState(true)
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
     * Get the current WebView instance from the ViewModel
     * @return The current WebView or null if not available
     */
    internal fun getCurrentWebView(): WebView? {
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
                    Log.d("MainActivity", "Starting initial WebView observer, background mode: ${_isBackgroundEnabled.value}")

                    wikiFlow.collect { wiki ->
                        wiki?.let {
                            // Ensure WebView is registered when first loaded
                            if (_isBackgroundEnabled.value) {
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
                                            if (view != null && _isBackgroundEnabled.value) {
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
                                                    if (_isBackgroundEnabled.value) {
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





