package com.tiddlywikibrowser

import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.*
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import androidx.datastore.preferences.core.edit
import androidx.appcompat.app.AppCompatDelegate
import android.util.Log
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.widget.Toast
import java.io.File
import com.tiddlywikibrowser.model.TiddlerTemplate
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import com.tiddlywikibrowser.R

// Add improved navigation throttling variables
private var lastNavigationTime = 0L
private val NAVIGATION_THROTTLE_MS = 500L // Increased from 300ms to 500ms
private var pendingNavigation: WikiInstance? = null
private var navigationJob: Job? = null
private val navigationMutex = Mutex() // Add mutex for thread-safe navigation

/**
 * WebViewClient specifically designed to handle and fix raw HTML content
 */
private class RawHtmlFixingWebViewClient(private val originalUrl: String) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null

        // Only intercept the main page to fix raw HTML display issues
        if (url == originalUrl || url.contains("tiddlywiki")) {
            try {
                val connection = URL(url).openConnection()
                // Set appropriate headers to ensure we get HTML
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.connect()

                // Always force content type to text/html for TiddlyWiki
                return WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    connection.getInputStream()
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return null
    }
}



// Extension function for Map to help with clearing WebViews
private fun MutableMap<String, WebView>.clearAllExcept(currentId: String?) {
    val keysToRemove = keys.filter { it != currentId }
    keysToRemove.forEach { key ->
        this[key]?.let { webView ->
            try {
                webView.stopLoading()
                webView.clearHistory()
                webView.loadUrl("about:blank")
                webView.removeAllViews()
                webView.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        remove(key)
    }
}

// Extension function for Map to help with recycling WebViews
private fun MutableMap<String, WebView>.recycle(key: String) {
    this[key]?.let { webView ->
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.onPause()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class WikiViewModel(private val context: Context) : ViewModel() {
    private val _currentWiki = MutableStateFlow<WikiInstance?>(null)
    val currentWiki: StateFlow<WikiInstance?> = _currentWiki

    private val _allWikis = MutableStateFlow<List<WikiInstance>>(emptyList())
    val allWikis: StateFlow<List<WikiInstance>> = _allWikis

    private val themeManager = ThemeManager(context)
    private val _isDarkMode = MutableStateFlow(themeManager.isDarkModeEnabled())
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    private val _isFrameVisible = MutableStateFlow(true)
    val isFrameVisible: StateFlow<Boolean> = _isFrameVisible

    private val _favicon = MutableStateFlow<Bitmap?>(null)
    val favicon: StateFlow<Bitmap?> = _favicon

    private val _faviconMap = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val faviconMap: StateFlow<Map<String, Bitmap>> = _faviconMap

    // Cache for WebViews - increased to handle larger wikis
    private val MAX_WEBVIEW_CACHE = 10
    private val webViewCache = mutableMapOf<String, WebView>()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline

    private val _quickTags = MutableStateFlow<List<String>>(emptyList())
    val quickTags: StateFlow<List<String>> = _quickTags

    // Add tiddler templates state
    private val _tiddlerTemplates = MutableStateFlow<List<TiddlerTemplate>>(emptyList())
    val tiddlerTemplates: StateFlow<List<TiddlerTemplate>> = _tiddlerTemplates.asStateFlow()

    // Add this property to track WebView initialization state
    private val _isWebViewReady = MutableStateFlow(false)
    val isWebViewReady: StateFlow<Boolean> = _isWebViewReady

    // Add these properties to track and prevent reload loops
    private val reloadTracker = mutableMapOf<String, Long>()
    private val RELOAD_PROTECTION_WINDOW = 5000L // 5 seconds window to detect rapid reloads
    private val MAX_RELOADS_IN_WINDOW = 2 // Maximum number of allowed reloads in the window

    // Add state for crash recovery
    private val _isRecovering = MutableStateFlow(false)
    val isRecovering: StateFlow<Boolean> = _isRecovering

    // Add crash tracking
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    // Add tracking for first wiki load
    private var isFirstWikiLoad = true

    // Add new property to track first-time single file wiki load
    private var isInitializingSingleFileWiki = false

    // Add new property to track WebView initialization
    private val _isWebViewSystemReady = MutableStateFlow(false)
    val isWebViewSystemReady: StateFlow<Boolean> = _isWebViewSystemReady

    // Add state tracking for WebView initialization
    private var webViewState = mutableMapOf<String, Bundle>()

    init {
        setupCrashHandler()
        viewModelScope.launch {
            try {
                // Step 1: Initialize WebView subsystem
                initializeWebViewSubsystem()
                
                // Step 2: Wait for WebView to be fully ready
                val startTime = System.currentTimeMillis()
                while (!_isWebViewSystemReady.value) {
                    if (System.currentTimeMillis() - startTime > 10000) { // 10 second timeout
                        Log.e("WikiViewModel", "WebView initialization timed out")
                        break
                    }
                    delay(100)
                }

                // Step 3: Force set ready state if needed
                if (!_isWebViewSystemReady.value) {
                    _isWebViewSystemReady.value = true
                }

                // Step 4: Only now start loading preferences and wikis
                context.dataStore.data.collect { preferences ->
                    val wikiListJson = preferences[PreferencesKeys.WIKI_LIST] ?: "[]"
                    val currentWikiJson = preferences[PreferencesKeys.CURRENT_WIKI]
                    val faviconsJson = preferences[PreferencesKeys.FAVICONS] ?: "{}"
                    val tagsJson = preferences[PreferencesKeys.QUICK_TAGS] ?: "[]"

                    // Update dark mode first as it doesn't depend on WebView
                    preferences[PreferencesKeys.IS_DARK_MODE]?.let { darkMode ->
                        _isDarkMode.value = darkMode
                    }

                    // Load wiki list first
                    val wikis = parseWikiList(wikiListJson)
                    _allWikis.value = wikis

                    // Now handle current wiki - removed delay for single file wikis
                    if (currentWikiJson != null) {
                        val current = parseWikiInstance(currentWikiJson)
                        _currentWiki.value = current
                    }

                    // Load favicons last as they're not critical
                    loadFavicons(faviconsJson)
                    _quickTags.value = parseQuickTags(tagsJson)
                }
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error during initialization", e)
                _isWebViewSystemReady.value = true // Ensure we don't block forever
            }
        }
    }

    private suspend fun initializeWebViewSubsystem() {
        var tempWebView: WebView? = null
        try {
            withContext(Dispatchers.Main) {
                // Create a test WebView to ensure the system is ready
                tempWebView = WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        defaultTextEncodingName = "UTF-8"
                        mediaPlaybackRequiresUserGesture = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        // Increase initial cache mode for better performance
                        cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }
                }

                // Load blank page with timeout using coroutine
                withTimeoutOrNull(10000) { // Increased from 5s to 10s for slower devices
                    suspendCancellableCoroutine<Unit> { continuation ->
                        tempWebView?.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url == "about:blank" && !continuation.isCompleted) {
                                    _isWebViewSystemReady.value = true
                                    continuation.resume(Unit)
                                    view?.destroy()
                                }
                            }
                            
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (!continuation.isCompleted) {
                                    continuation.resume(Unit)
                                    _isWebViewSystemReady.value = true // Force ready on error
                                    Log.w("WikiViewModel", "WebView initialization error: ${error?.description}")
                                }
                            }
                        }
                        tempWebView?.loadUrl("about:blank")

                        // Add cancellation handler
                        continuation.invokeOnCancellation {
                            ThreadManager.runOnMain {
                                try {
                                    tempWebView?.stopLoading()
                                    tempWebView?.destroy()
                                } catch (e: Exception) {
                                    Log.e("WikiViewModel", "Error cleaning up temp WebView", e)
                                }
                            }
                        }
                    }
                } ?: run {
                    // Timeout occurred - force ready state but log warning
                    Log.w("WikiViewModel", "WebView initialization timed out, forcing ready state")
                    _isWebViewSystemReady.value = true
                }
            }
        } catch (e: Exception) {
            Log.e("WikiViewModel", "Error initializing WebView subsystem", e)
            _isWebViewSystemReady.value = true // Ensure we don't block forever
            
            // Notify about initialization error
            _lastError.value = "WebView initialization error: ${e.message}"
        } finally {
            // Ensure cleanup even if initialization fails
            try {
                tempWebView?.destroy()
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error destroying temp WebView", e)
            }
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun handleCrash(throwable: Throwable) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                _isRecovering.value = true
                _lastError.value = throwable.message ?: "Unknown error"

                // Clear problematic state
                clearWebViews()
                _currentWiki.value = null

                // Save crash state to preferences
                context.dataStore.edit { preferences ->
                    preferences.remove(PreferencesKeys.CURRENT_WIKI)
                }

                // Force garbage collection to free memory
                System.gc()

                // Reset recovery state after a delay
                delay(1000)
                _isRecovering.value = false
            } catch (e: Exception) {
                // If recovery fails, at least try to clear state
                _currentWiki.value = null
                clearWebViews()
            }
        }
    }

    // Add recovery method that can be called from Activity
    fun recoverFromCrash() {
        viewModelScope.launch {
            try {
                _isRecovering.value = true

                // Clear all WebView state
                clearWebViews()
                WebViewCache.clearAll()

                // Clear current wiki to return to home screen
                _currentWiki.value = null

                // Clear preferences
                context.dataStore.edit { preferences ->
                    preferences.remove(PreferencesKeys.CURRENT_WIKI)
                }

                // Reload wiki list from preferences
                var newWikiList = emptyList<WikiInstance>()
                context.dataStore.data.collect { preferences ->
                    val wikiListJson = preferences[PreferencesKeys.WIKI_LIST] ?: "[]"
                    newWikiList = parseWikiList(wikiListJson)
                    _allWikis.value = newWikiList
                }

                // Clear memory
                System.gc()

                delay(1000)
                _isRecovering.value = false
                _lastError.value = null
            } catch (e: Exception) {
                _currentWiki.value = null
                _lastError.value = "Recovery failed: ${e.message ?: "Unknown error"}"
            }
        }
    }

    private fun loadFavicons(json: String) {
        try {
            val jsonObject = JSONObject(json)
            val faviconMap = mutableMapOf<String, Bitmap>()

            jsonObject.keys().forEach { url ->
                val base64 = jsonObject.getString(url)
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    faviconMap[url] = bitmap
                }
            }

            _faviconMap.value = faviconMap
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Helper method to create WebViewClient for a wiki
    fun createWebViewClient(wiki: WikiInstance): WebViewClient {
        return object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Reset ready state when page starts loading
                _isWebViewReady.value = false

                favicon?.let { bitmap ->
                    setFavicon(wiki.url, bitmap)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Enable images progressively after page load
                view?.post {
                    view.settings.blockNetworkImage = false
                    view.settings.loadsImagesAutomatically = true
                    _isWebViewReady.value = true
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    _isWebViewReady.value = false
                }
            }
        }
    }

    // Implementation for the missing setFavicon method
    fun setFavicon(url: String, bitmap: Bitmap?) {
        viewModelScope.launch {
            _faviconMap.value = if (bitmap != null) {
                _faviconMap.value + (url to bitmap)
            } else {
                _faviconMap.value - url
            }
            saveFavicons() // Save favicons whenever they change
        }
    }

    // Helper method to save favicons to preferences
    private fun saveFavicons() {
        viewModelScope.launch {
            val jsonObject = JSONObject()
            _faviconMap.value.forEach { (url, bitmap) ->
                val byteArrayOutputStream = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
                val byteArray = byteArrayOutputStream.toByteArray()
                val base64 = android.util.Base64.encodeToString(byteArray, android.util.Base64.DEFAULT)
                jsonObject.put(url, base64)
            }

            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.FAVICONS] = jsonObject.toString()
            }
        }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.IS_DARK_MODE] = enabled
            }
            _isDarkMode.value = enabled
            themeManager.setDarkMode(enabled)
        }
    }

    fun addWiki(name: String, url: String) {
        val newWiki = WikiInstance(
            name = name,
            url = url,
            isLocalFile = url.startsWith("file:") || url.startsWith("content:")
        )
        viewModelScope.launch {
            val newList = _allWikis.value + newWiki
            _allWikis.value = newList
            if (_currentWiki.value == null) {
                _currentWiki.value = newWiki
            }
            saveWikis(newList)
        }
    }

    // Replace setCurrentWiki with improved synchronized version
    fun setCurrentWiki(wiki: WikiInstance?) {
        viewModelScope.launch {
            // Use mutex to ensure only one navigation happens at a time
            navigationMutex.withLock {
                // Cancel any pending navigations
                navigationJob?.cancel()
                
                // Throttle rapid navigations
                val now = System.currentTimeMillis()
                if (now - lastNavigationTime < NAVIGATION_THROTTLE_MS) {
                    // If we're trying to navigate too quickly, schedule it for later
                    Log.d("WikiViewModel", "Navigation throttled, scheduling delayed navigation to: ${wiki?.name}")
                    pendingNavigation = wiki
                    
                    // Clear previous job if exists
                    navigationJob?.cancel()
                    
                    // Schedule the navigation after the throttle period
                    navigationJob = viewModelScope.launch {
                        delay(NAVIGATION_THROTTLE_MS)
                        navigationMutex.withLock {
                            pendingNavigation?.let { delayed ->
                                lastNavigationTime = System.currentTimeMillis()
                                Log.d("WikiViewModel", "Executing delayed navigation to: ${delayed.name}")
                                performNavigation(delayed)
                                pendingNavigation = null
                            }
                        }
                    }
                } else {
                    // Immediate navigation
                    lastNavigationTime = now
                    performNavigation(wiki)
                }
            }
        }
    }

    // Move the actual navigation logic here
    private fun performNavigation(wiki: WikiInstance?) {
        viewModelScope.launch {
            try {
                // If navigation is to the current wiki, don't do anything
                if (_currentWiki.value?.url == wiki?.url) {
                    Log.d("WikiViewModel", "Already on wiki: ${wiki?.name}, ignoring navigation")
                    return@launch
                }
                
                // Ensure WebView system is ready
                val startTime = System.currentTimeMillis()
                while (!_isWebViewSystemReady.value) {
                    if (System.currentTimeMillis() - startTime > 5000) {
                        Log.e("WikiViewModel", "Timeout waiting for WebView system")
                        break
                    }
                    delay(100)
                }

                // Don't proceed if system isn't ready
                if (!_isWebViewSystemReady.value) {
                    Log.e("WikiViewModel", "WebView system not ready, cannot set wiki")
                    return@launch
                }

                if (_currentWiki.value?.url != wiki?.url) {
                    Log.d("WikiViewModel", "Switching wiki to: ${wiki?.name}")

                    // Special handling for first-time single file wiki load
                    if (isFirstWikiLoad && wiki?.isLocalFile == true) {
                        Log.d("WikiViewModel", "First load of single file wiki, applying special handling")
                        isInitializingSingleFileWiki = true
                        _isWebViewReady.value = false

                        try {
                            // Create WebView with minimal settings first
                            val webView = WebView(context).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    allowFileAccess = true
                                    allowContentAccess = true
                                    defaultTextEncodingName = "UTF-8"
                                }
                            }

                            // Setup a basic WebViewClient for initial load
                            webView.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    Log.d("WikiViewModel", "Initial page load started for single file wiki")
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    Log.d("WikiViewModel", "Initial page load finished for single file wiki")
                                    if (url != "about:blank") {
                                        view?.post {
                                            _isWebViewReady.value = true
                                        }
                                    }
                                }

                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    super.onReceivedError(view, request, error)
                                    Log.e("WikiViewModel", "Error loading single file wiki: ${error?.description}")
                                    handleCrash(Exception("Failed to load single file wiki: ${error?.description}"))
                                }
                            }

                            // Load blank page first
                            webView.loadUrl("about:blank")
                            delay(500)

                            // Now enable full settings
                            webView.settings.apply {
                                mediaPlaybackRequiresUserGesture = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                cacheMode = WebSettings.LOAD_NO_CACHE
                                setGeolocationEnabled(false)
                            }

                            // Store in cache
                            val key = wiki.idFromUrl ?: wiki.url
                            webViewCache[key] = webView
                            WebViewCache.cacheWebView(key, webView)

                            // Load the actual content
                            Log.d("WikiViewModel", "Loading single file wiki URL: ${wiki.url}")
                            webView.loadUrl(wiki.url)

                            isFirstWikiLoad = false
                            isInitializingSingleFileWiki = false
                        } catch (e: Exception) {
                            Log.e("WikiViewModel", "Error initializing single file wiki", e)
                            handleCrash(e)
                            return@launch
                        }
                    }

                    // Save current wiki's state before switching
                    _currentWiki.value?.let { currentWiki ->
                        val key = currentWiki.idFromUrl ?: currentWiki.url
                        // Add a safety check for null WebView
                        webViewCache[key]?.let { currentWebView ->
                            try {
                                // Ensure we save the "loaded" state with the WebView
                                val isLoaded = currentWebView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
                                Log.d("WikiViewModel", "Saving state for previous wiki: $key, loaded=$isLoaded")

                                // Cache the current WebView with its full state to preserve it
                                WebViewCache.cacheWebView(key, currentWebView)

                                // Pause the WebView to reduce resource usage
                                currentWebView.onPause()
                            } catch (e: Exception) {
                                Log.e("WikiViewModel", "Error saving state for previous wiki", e)
                            }
                        }
                    }

                    // Ensure the navigation doesn't proceed if the wiki is null
                    if (wiki == null) {
                        _currentWiki.value = null
                        return@launch
                    }

                    try {
                        // Use a single atomic update of the current wiki
                        _currentWiki.value = wiki

                        val key = wiki.idFromUrl ?: wiki.url
                        Log.d("WikiViewModel", "Setting active key: $key")

                        // Set this as the active key in WebViewCache before pausing others
                        WebViewCache.setCurrentActiveKey(key)

                        // Pause all other WebViews
                        WebViewCache.pauseAllWebViewsExcept(key)

                        // Add a delay to ensure UI has time to process state changes
                        delay(50)

                        // Get or create the WebView after the UI state is updated
                        viewModelScope.launch {
                            try {
                                // Get or create the WebView (this should restore from cache if available)
                                val webView = getOrCreateWebView(wiki, context)

                                // Explicitly ensure we're preserving the loaded state
                                val isLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
                                Log.d("WikiViewModel", "Resuming wiki: $key, loaded=$isLoaded")

                                // Resume the WebView without triggering a reload
                                webView.onResume()
                                WebViewCache.resumeWebView(key)

                                ThreadManager.runOnBackground {
                                    try {
                                        // Update favicon if available
                                        webView.favicon?.let { bitmap ->
                                            _faviconMap.value = _faviconMap.value + (wiki.url to bitmap)
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }

                                // Save current wiki to preferences
                                context.dataStore.edit { preferences ->
                                    preferences[PreferencesKeys.CURRENT_WIKI] = wikiToJson(wiki)
                                }
                            } catch (e: Exception) {
                                Log.e("WikiViewModel", "Error switching wiki: ${e.message}", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("WikiViewModel", "Error updating current wiki state", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error in performNavigation", e)
                isInitializingSingleFileWiki = false
                handleCrash(e)
            }
        }
    }

    private fun detachWebView(url: String) {
        webViewCache[url]?.let { webView ->
            // Remove from its parent so it's no longer in the view hierarchy
            (webView.parent as? ViewGroup)?.removeView(webView)
            // Pause WebView processing so it remains in the background
            webView.onPause()
        }
    }

    private fun cleanupWebView(url: String) {
        webViewCache[url]?.let { oldWebView ->
            try {
                // Save state before cleanup
                saveWebViewState(url, oldWebView)
                
                // Check for active media before cleanup
                oldWebView.evaluateJavascript(
                    """
                    (function() {
                        const media = document.querySelector('audio,video');
                        return media ? media.paused : true;
                    })()
                    """.trimIndent()
                ) { isPausedResult ->
                    val isPaused = isPausedResult.toBooleanStrictOrNull() ?: true
                    if (isPaused) {
                        // Only clean up if no active media
                        (oldWebView.parent as? ViewGroup)?.removeView(oldWebView)
                        oldWebView.stopLoading()
                        oldWebView.clearHistory()
                        oldWebView.loadUrl("about:blank")
                        oldWebView.removeAllViews()
                        oldWebView.destroy()
                        webViewCache.remove(url)
                    }
                }
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error in cleanupWebView", e)
            }
        }
    }

    private fun createAndCacheWebView(wiki: WikiInstance) {
        if (webViewCache.size >= MAX_WEBVIEW_CACHE) {
            // Remove the oldest WebView that isn't the current wiki, using an LRU-like strategy.
            val urlToRemove = webViewCache.keys.firstOrNull { it != _currentWiki.value?.url }
            urlToRemove?.let { url ->
                cleanupWebView(url)
            }
        }
        val newWebView = MainActivity.createWebView(context).apply {
            loadUrl(wiki.url)
        }
        webViewCache[wiki.url] = newWebView
    }

    // Pre-initialize WebView in background
    fun preloadWebView(wiki: WikiInstance, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            // Create WebView configuration on background thread
            val webViewConfig = WebViewSetupConfig(wiki, context)

            // Switch to main thread for actual WebView creation
            withContext(Dispatchers.Main) {
                getOrCreateWebView(wiki, context)
            }
        }
    }

    // Improved WebView creation with better error handling and reload protection
    fun getOrCreateWebView(wiki: WikiInstance, context: Context): WebView {
        return try {
            val key = wiki.idFromUrl ?: wiki.url

            // Special handling for first-time single file wiki
            if (isInitializingSingleFileWiki) {
                Log.d("WikiViewModel", "Creating WebView with special handling for single file wiki")
                
                return WebView(context).apply {
                    settings.apply {
                        // Essential settings first
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        
                        // Defer loading of resources initially
                        blockNetworkImage = true
                        loadsImagesAutomatically = false
                        
                        // No caching for first load
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        
                        // Other essential settings
                        defaultTextEncodingName = "UTF-8"
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    
                    // Use simplified WebViewClient for initial load
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            _isWebViewReady.value = false
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url != "about:blank") {
                                view?.post {
                                    view.settings.apply {
                                        blockNetworkImage = false
                                        loadsImagesAutomatically = true
                                        cacheMode = WebSettings.LOAD_DEFAULT
                                    }
                                    _isWebViewReady.value = true
                                }
                            }
                        }
                    }

                    // Use formattedUrl instead of url
                    loadUrl(wiki.formattedUrl)
                }
            }

            // Check if we already have a cached WebView
            WebViewCache.getCachedWebView(key)?.let { cachedWebView ->
                return cachedWebView
            }

            return webViewCache[key] ?: synchronized(this) {
                webViewCache[key]?.let { return it }

                val webView = MainActivity.createWebView(context).apply {
                    settings.apply {
                        // Force this to be true initially to avoid crashes
                        blockNetworkImage = true
                        loadsImagesAutomatically = false

                        // CRITICAL: Set correct MIME type handling for HTML
                        defaultTextEncodingName = "UTF-8"

                        // Ensure media types are handled properly
                        mediaPlaybackRequiresUserGesture = false

                        // Make sure JS can run
                        javaScriptEnabled = true
                        domStorageEnabled = true

                        // Fix for raw HTML display issue
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        // Ensure proper rendering of content
                        useWideViewPort = true
                        loadWithOverviewMode = true

                        // For large wikis, use modern cache APIs instead of deprecated ones
                        try {
                            domStorageEnabled = true
                            // Replace deprecated app cache with modern cache handling
                            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    // Create a custom WebViewClient that handles reload loops
                    webViewClient = object : WebViewClient() {
                        private var isInitialLoad = true
                        private var hasInjectedReloadProtection = false

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)

                            // Track reloads to detect loops
                            if (url != null && !isInitialLoad) {
                                val now = System.currentTimeMillis()
                                val previousReloads = reloadTracker[url] ?: 0L
                                val reloadCount = previousReloads + 1
                                reloadTracker[url] = reloadCount

                                // If too many reloads in short period, inject protection
                                if (reloadCount >= MAX_RELOADS_IN_WINDOW && !hasInjectedReloadProtection) {
                                    hasInjectedReloadProtection = true

                                    ThreadManager.runOnMain {
                                        // Inject reload protection script
                                        view?.evaluateJavascript("""
                                            (function() {
                                                // Block automatic reloads and refreshes
                                                const originalReload = window.location.reload;
                                                window.location.reload = function() {
                                                    console.log('Blocked automatic reload');
                                                    return false;
                                                };
                                                
                                                // Intercept history API calls
                                                const originalPushState = history.pushState;
                                                history.pushState = function() {
                                                    console.log('Monitored pushState call');
                                                    return originalPushState.apply(this, arguments);
                                                };
                                                
                                                // Intercept navigation attempts
                                                window.addEventListener('beforeunload', function(e) {
                                                    e.preventDefault();
                                                    e.returnValue = '';
                                                    return '';
                                                });
                                                
                                                // Stabilize TiddlyWiki
                                                if (window.${'$'}tw) {
                                                    // Add stability patches for TiddlyWiki
                                                    try {
                                                        // Prevent automatic saving that might cause reloads
                                                        if (${'$'}tw.syncer) {
                                                            ${'$'}tw.syncer.syncFromServer = function() {
                                                                return false;
                                                            };
                                                        }
                                                        
                                                        // Prevent full page refresh actions
                                                        if (${'$'}tw.pageRefreshers) {
                                                            ${'$'}tw.pageRefreshers = [];
                                                        }
                                                    } catch(e) {
                                                        console.error("Failed to patch TiddlyWiki: " + e);
                                                    }
                                                }
                                                
                                                console.log('Reload protection installed');
                                                return "protection-installed";
                                            })();
                                        """.trimIndent(), null)
                                    }
                                }
                            }

                            isInitialLoad = false
                            _isWebViewReady.value = false

                            favicon?.let { bitmap ->
                                setFavicon(wiki.url, bitmap)
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)

                            // Reset reload tracking after a successful page load
                            if (url != null) {
                                reloadTracker.remove(url)
                            }

                            view?.post {
                                view.settings.loadsImagesAutomatically = true
                                view.settings.blockNetworkImage = false
                                _isWebViewReady.value = true

                                // Inject optimization for large wikis
                                view.evaluateJavascript("""
                                    (function() {
                                        // Check if this page is a TiddlyWiki
                                        if (typeof window.${'$'}tw !== 'undefined') {
                                            // Memory optimizations for large wikis
                                            if (document.querySelector('.tc-story-river') && 
                                                document.querySelectorAll('.tc-tiddler-frame').length > 20) {
                                                    
                                                // It's a large wiki - optimize rendering
                                                window.${'$'}tw.wiki.addEventListener("change", function(changes) {
                                                    if (Object.keys(changes).length > 10) {
                                                        // Batch UI updates for large changes
                                                        window.${'$'}tw.notifier.sortByPriority = true;
                                                        return false; // Let notifier handle it
                                                    }
                                                });
                                                
                                                // Prevent unwanted refreshes
                                                const originalRefresh = window.${'$'}tw.wiki.refresh;
                                                window.${'$'}tw.wiki.refresh = function(changes, source) {
                                                    if (source === "reloadPage") {
                                                        console.log("Prevented reload-based refresh");
                                                        return;
                                                    }
                                                    return originalRefresh.call(this, changes, source);
                                                };
                                                
                                                return "large-wiki-optimized";
                                            }
                                        }
                                        
                                        // Fix common reload triggers
                                        var reloadButtons = document.querySelectorAll('a[href="javascript:window.location.reload()"]');
                                        reloadButtons.forEach(function(btn) {
                                            btn.href = "javascript:void(0)";
                                            btn.addEventListener('click', function(e) {
                                                e.preventDefault();
                                                // Allow manual reloads after cooldown period
                                                var lastClick = parseInt(this.dataset.lastClick || 0);
                                                var now = Date.now();
                                                if (now - lastClick > 5000) {
                                                    this.dataset.lastClick = now;
                                                    // Soft refresh only what's needed
                                                    if (window.${'$'}tw && window.${'$'}tw.wiki) {
                                                        window.${'$'}tw.wiki.clearCache();
                                                        window.${'$'}tw.wiki.addTiddlers(window.${'$'}tw.wiki.tiddlers);
                                                    }
                                                }
                                            });
                                        });
                                        
                                        return "reload-protection-applied";
                                    })();
                                """.trimIndent(), null)
                            }
                        }

                        // Fix content type issues for large wikis
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null

                            // Intercept main page to ensure proper content handling
                            if (url == wiki.url) {
                                try {
                                    val connection = URL(url).openConnection()
                                    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                                    connection.connectTimeout = 10000
                                    connection.readTimeout = 15000
                                    connection.connect()

                                    // Always serve HTML content for the main wiki URL
                                    return WebResourceResponse(
                                        "text/html",
                                        "UTF-8",
                                        connection.getInputStream()
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            // Block potential problem resources
                            if (url.contains("analytics") || url.contains("tracking") ||
                                url.contains("google-analytics") || url.contains("facebook") ||
                                url.contains("refresh.js") || url.contains("reload.js")) {
                                return WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
                            }

                            return null
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                _isWebViewReady.value = false
                            }
                        }
                    }

                    // Only load for NEW webviews (not cached/restored ones)
                    post {
                        try {
                            // For large wikis, prioritize cache - fixed suspend function call
                            viewModelScope.launch {
                                analyzeWikiSize(wiki).collect { strategy ->
                                    if (strategy == WikiLoadStrategy.LARGE_WIKI) {
                                        settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                                    }
                                    applyWikiSizeOptimizations(this@apply, strategy)
                                }

                                // Only load URL for NEWLY CREATED webviews
                                ThreadManager.runOnMain {
                                    loadUrl(wiki.formattedUrl)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            // Fallback if analysis fails
                            loadUrl(wiki.formattedUrl)
                        }
                    }
                }

                // Cache the WebView
                webViewCache[key] = webView
                WebViewCache.cacheWebView(key, webView)
                webView
            }
        } catch (e: Exception) {
            // If WebView creation fails, trigger recovery
            handleCrash(e)
            // Return a blank WebView as fallback
            WebView(context).apply {
                settings.javaScriptEnabled = true
                loadUrl("about:blank")
            }
        }
    }

    // Better lifecycle management
    fun pauseAllWebViews() {
        webViewCache.values.forEach { it.onPause() }
    }

    fun resumeCurrentWebView(wiki: WikiInstance?) {
        wiki?.let {
            val key = it.idFromUrl ?: it.url
            webViewCache[key]?.onResume()
            WebViewCache.resumeWebView(key)
        }
    }

    fun setFrameVisible(visible: Boolean) {
        // Update on the main thread to ensure UI state changes immediately
        ThreadManager.runOnMain {
            _isFrameVisible.value = visible
        }
    }

    fun setFavicon(bitmap: Bitmap?) {
        viewModelScope.launch {
            _favicon.value = bitmap
        }
    }

    fun setOfflineState(offline: Boolean) {
        // Don't update if state hasn't changed to avoid unnecessary UI updates
        if (_isOffline.value != offline) {
            viewModelScope.launch {
                _isOffline.value = offline
                // Only update WebView cache mode if we're going offline
                if (offline) {
                    _currentWiki.value?.let { wiki ->
                        webViewCache[wiki.url]?.settings?.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }
                }
            }
        }
    }

    internal fun clearWebViews() {
        try {
            _faviconMap.value.values.forEach {
                try {
                    it.recycle()
                } catch (e: Exception) {
                    Log.e("WikiViewModel", "Error recycling favicon", e)
                }
            }
            _faviconMap.value = emptyMap()

            try {
                _favicon.value?.recycle()
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error recycling main favicon", e)
            }
            _favicon.value = null

            webViewCache.values.forEach { webView ->
                try {
                    webView.stopLoading()
                    webView.clearHistory()
                    webView.loadUrl("about:blank")
                    webView.removeAllViews()
                    webView.destroy()
                } catch (e: Exception) {
                    Log.e("WikiViewModel", "Error clearing WebView", e)
                }
            }
            webViewCache.clear()
            WebViewCache.clearAll()

            // Force garbage collection
            System.gc()
        } catch (e: Exception) {
            Log.e("WikiViewModel", "Error in clearWebViews", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        clearWebViews()
    }

    fun deleteWiki(wiki: WikiInstance) {
        viewModelScope.launch {
            val newList = _allWikis.value.filter { it.url != wiki.url }
            _allWikis.value = newList

            // Clear current wiki if it was the one deleted
            if (_currentWiki.value?.url == wiki.url) {
                _currentWiki.value = newList.firstOrNull()
            }

            // Save the updated list
            saveWikis(newList)

            // Remove from WebView cache
            webViewCache.remove(wiki.url)
            WebViewCache.removeCachedWebView(wiki.url)
        }
    }

    private suspend fun saveWikis(wikis: List<WikiInstance>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WIKI_LIST] = wikiListToJson(wikis)
        }
    }

    private fun wikiListToJson(wikis: List<WikiInstance>): String {
        val jsonArray = JSONArray()
        wikis.forEach { wiki ->
            jsonArray.put(JSONObject().apply {
                put("name", wiki.name)
                put("url", wiki.url)
                put("isLocalFile", wiki.isLocalFile)
                wiki.sourceUrl?.let { put("sourceUrl", it) }
                wiki.id?.let { put("id", it) }
            })
        }
        return jsonArray.toString()
    }

    private fun wikiToJson(wiki: WikiInstance): String {
        return JSONObject().apply {
            put("name", wiki.name)
            put("url", wiki.url)
            put("isLocalFile", wiki.isLocalFile)
            wiki.sourceUrl?.let { put("sourceUrl", it) }
            wiki.id?.let { put("id", it) }
        }.toString()
    }

    private fun parseWikiList(json: String): List<WikiInstance> {
        return try {
            val jsonArray = JSONArray(json)
            List(jsonArray.length()) { i ->
                val obj = jsonArray.getJSONObject(i)
                WikiInstance(
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    id = if (obj.has("id")) obj.getString("id") else null,
                    isLocalFile = if (obj.has("isLocalFile")) obj.getBoolean("isLocalFile") else false,
                    sourceUrl = if (obj.has("sourceUrl")) obj.getString("sourceUrl") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseWikiInstance(json: String): WikiInstance? {
        return try {
            val obj = JSONObject(json)
            WikiInstance(
                name = obj.getString("name"),
                url = obj.getString("url"),
                id = if (obj.has("id")) obj.getString("id") else null,
                isLocalFile = if (obj.has("isLocalFile")) obj.getBoolean("isLocalFile") else false,
                sourceUrl = if (obj.has("sourceUrl")) obj.getString("sourceUrl") else null
            )
        } catch (e: Exception) {
            null
        }
    }

    fun reorderWikis(from: Int, to: Int) {
        val newList = _allWikis.value.toMutableList()
        val wiki = newList.removeAt(from)
        newList.add(to, wiki)
        _allWikis.value = newList
        viewModelScope.launch {
            saveWikis(newList)
        }
    }

    private fun parseQuickTags(json: String): List<String> {
        return try {
            val jsonArray = JSONArray(json)
            List(jsonArray.length()) { i -> jsonArray.getString(i) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addQuickTag(tag: String) {
        viewModelScope.launch {
            val newList = _quickTags.value + tag
            _quickTags.value = newList
            saveQuickTags(newList)
        }
    }

    fun removeQuickTag(tag: String) {
        viewModelScope.launch {
            val newList = _quickTags.value - tag
            _quickTags.value = newList
            saveQuickTags(newList)
        }
    }

    fun reorderQuickTags(from: Int, to: Int) {
        val newList = _quickTags.value.toMutableList()
        val tag = newList.removeAt(from)
        newList.add(to, tag)
        _quickTags.value = newList
        viewModelScope.launch {
            saveQuickTags(newList)
        }
    }

    private suspend fun saveQuickTags(tags: List<String>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.QUICK_TAGS] = JSONArray(tags).toString()
        }
    }

    // Add onLowMemory method
    fun onLowMemory() {
        viewModelScope.launch(Dispatchers.IO) {
            // Clear all but current WebView
            val key = currentWiki.value?.idFromUrl ?: currentWiki.value?.url
            webViewCache.clearAllExcept(key)
            // Clear disk caches
            clearWebViewDiskCache()
        }
    }

    // Utility method to clear WebView disk cache
    fun clearWebViewDiskCache() {
        ThreadManager.runOnBackground {
            try {
                context.cacheDir.deleteRecursively()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * For large wikis, analyze file size and optimize loading strategy
     */
    fun analyzeWikiSize(wiki: WikiInstance): Flow<WikiLoadStrategy> = flow {
        emit(WikiLoadStrategy.INITIALIZING)

        try {
            val url = URL(wiki.url)
            val connection = url.openConnection()
            connection.connectTimeout = 5000

            // Check content length if available
            val contentLength = connection.contentLength

            if (contentLength > 5 * 1024 * 1024) { // Larger than 5MB
                emit(WikiLoadStrategy.LARGE_WIKI)
            } else if (contentLength > 1 * 1024 * 1024) { // Larger than 1MB
                emit(WikiLoadStrategy.MEDIUM_WIKI)
            } else {
                emit(WikiLoadStrategy.SMALL_WIKI)
            }
        } catch (e: Exception) {
            // Default to medium if we can't determine
            emit(WikiLoadStrategy.MEDIUM_WIKI)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Apply specific optimizations based on wiki size
     */
    fun applyWikiSizeOptimizations(webView: WebView, strategy: WikiLoadStrategy) {
        when (strategy) {
            WikiLoadStrategy.LARGE_WIKI -> {
                // Apply aggressive optimizations
                webView.settings.blockNetworkImage = true // Defer image loading

                // Inject JavaScript to optimize large wiki
                webView.evaluateJavascript("""
                    // Adapt TiddlyWiki for large content
                    if (typeof window.${'$'}tw !== 'undefined') {
                        window.${'$'}tw.wiki.addEventListener("change", function(changes) {
                            // Limit refreshes and rendering for large changes
                            if (Object.keys(changes).length > 50) {
                                // Prevent automatic rendering of all changed tiddlers
                                return false;
                            }
                        });
                    }
                """.trimIndent(), null)
            }

            WikiLoadStrategy.MEDIUM_WIKI -> {
                // Medium optimizations
                webView.settings.blockNetworkImage = false
            }

            WikiLoadStrategy.SMALL_WIKI -> {
                // Small wiki - normal loading
                webView.settings.blockNetworkImage = false
            }

            else -> {} // No action for initializing state
        }
    }

    // Enable images for a WebView after it's loaded
    fun enableImagesForWebView(key: String) {
        webViewCache[key]?.let { webView ->
            ThreadManager.runOnMain {
                webView.settings.loadsImagesAutomatically = true
                webView.settings.blockNetworkImage = false
                // Force a re-render to show images
                webView.evaluateJavascript(
                    """
                    (function() {
                        document.querySelectorAll('img').forEach(img => {
                            const src = img.src;
                            img.src = '';
                            img.src = src;
                        });
                    })();
                    """.trimIndent(), null
                )
            }
        }
    }

    // Reduce memory usage during low memory conditions
    fun reduceMemoryUsage() {
        ThreadManager.runOnBackground {
            // Clear WebView caches except for current wiki
            webViewCache.clearAllExcept(currentWiki.value?.url)

            // Force garbage collection
            System.gc()
        }
    }

    // Recycle WebView when it's no longer needed
    fun recycleWebView(wikiKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            WebViewCache.removeCachedWebView(wikiKey)
            webViewCache.remove(wikiKey)
            System.gc()
        }
    }

    private fun saveWebViewState(key: String, webView: WebView) {
        val bundle = Bundle()
        webView.saveState(bundle)
        webViewState[key] = bundle
    }

    private fun restoreWebViewState(key: String, webView: WebView): Boolean {
        return webViewState[key]?.let { bundle ->
            try {
                webView.restoreState(bundle)
                true
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error restoring WebView state", e)
                false
            }
        } ?: false
    }

    /**
     * Load tiddler templates from the assets/tiddler_templates folder
     */
    fun loadTiddlerTemplates(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // List template files in the assets/tiddler_templates directory
                val templateFiles = context.assets.list("tiddler_templates") ?: emptyArray()
                val templates = mutableListOf<TiddlerTemplate>()

                for (fileName in templateFiles) {
                    if (fileName.endsWith(".html")) {
                        // Read the first few lines to extract metadata
                        val inputStream = context.assets.open("tiddler_templates/$fileName")
                        val reader = inputStream.bufferedReader()
                        val firstLines = buildString {
                            repeat(10) {
                                append(reader.readLine() ?: "")
                                append("\n")
                            }
                        }

                        // Try to extract title and description from the HTML
                        val title = extractTitle(firstLines) ?: fileName.removeSuffix(".html")
                        val description = extractDescription(firstLines)

                        templates.add(TiddlerTemplate(
                            name = title,
                            fileName = fileName,
                            description = description
                        ))

                        reader.close()
                        inputStream.close()
                    }
                }

                withContext(Dispatchers.Main) {
                    _tiddlerTemplates.value = templates
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _tiddlerTemplates.value = emptyList()
                }
            }
        }
    }

    /**
     * Extract title from HTML content
     */
    private fun extractTitle(htmlContent: String): String? {
        val titleRegex = "<title>(.*?)</title>".toRegex()
        val match = titleRegex.find(htmlContent)
        return match?.groupValues?.get(1)
    }

    /**
     * Extract description from HTML content (looks for meta description tag)
     */
    private fun extractDescription(htmlContent: String): String? {
        val descRegex = "<meta\\s+name=[\"']description[\"']\\s+content=[\"'](.*?)[\"']".toRegex()
        val match = descRegex.find(htmlContent)
        return match?.groupValues?.get(1)
    }

    /**
     * Create a single file tiddler based on the selected template
     */
    fun createSingleFileTiddler(context: Context, template: TiddlerTemplate) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Create a unique filename for the new tiddler
                val timestamp = System.currentTimeMillis()
                val fileName = "${template.name.replace(" ", "_")}_$timestamp.html"

                // Get the documents directory
                val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val tidWebDir = File(documentsDir, "TidWeb")
                if (!tidWebDir.exists()) {
                    tidWebDir.mkdirs()
                }

                // Create the output file
                val outputFile = File(tidWebDir, fileName)

                // Copy the template content to the new file
                val inputStream = context.assets.open("tiddler_templates/${template.fileName}")
                val outputStream = outputFile.outputStream()

                inputStream.copyTo(outputStream)

                inputStream.close()
                outputStream.close()

                // Create a Uri for the new file
                val fileUri = Uri.fromFile(outputFile)
                val fileUrlString = fileUri.toString()

                // Add the new tiddler to the list of wikis
                val newWiki = WikiInstance(
                    name = template.name,
                    url = fileUrlString,
                    isLocalFile = true  // Ensure this is marked as a local file
                )

                withContext(Dispatchers.Main) {
                    // Add the wiki to the list
                    addWiki(newWiki.name, newWiki.url)
                    
                    // Set it as the current wiki immediately
                    setCurrentWiki(newWiki)

                    // Show a success message using a safe context
                    if (context is androidx.activity.ComponentActivity) {
                        Toast.makeText(
                            context,
                            "Created new tiddler: ${template.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    if (context is androidx.activity.ComponentActivity) {
                        Toast.makeText(
                            context,
                            "Error creating tiddler: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * Update a wiki with new information, useful for replacing with downloaded files
     */
    fun updateWiki(oldWiki: WikiInstance, newWiki: WikiInstance) {
        viewModelScope.launch {
            // First update the wiki list
            val newList = _allWikis.value.map { 
                if (it.url == oldWiki.url) newWiki else it 
            }
            _allWikis.value = newList
            
            // Save the updated list
            saveWikis(newList)
            
            // If this was the current wiki, update that reference
            if (_currentWiki.value?.url == oldWiki.url) {
                // Clear the old WebView
                cleanupWebView(oldWiki.url)
                WebViewCache.removeCachedWebView(oldWiki.url)
                
                // Set new wiki as current
                _currentWiki.value = newWiki
                
                // Load the new wiki
                ThreadManager.runOnMain {
                    // Create a new WebView for the updated wiki
                    val webView = getOrCreateWebView(newWiki, context)
                    
                    // Reset the prevent reload tag to force loading the new content
                    webView.setTag(R.string.prevent_reload_tag, false)
                    
                    // Load the new URL
                    webView.loadUrl(newWiki.url)
                    
                    // Save as current wiki in preferences
                    viewModelScope.launch {
                        context.dataStore.edit { preferences ->
                            preferences[PreferencesKeys.CURRENT_WIKI] = wikiToJson(newWiki)
                        }
                    }
                }
            }
        }
    }

    /**
     * Import a single file TiddlyWiki from a content URI
     */
    fun importLocalWikiFile(contentUri: Uri, fileName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Get the input stream from the content URI
                val inputStream = context.contentResolver.openInputStream(contentUri)
                
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to open file", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // Create directory if it doesn't exist
                val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val tidWebDir = File(documentsDir, "TidWeb")
                if (!tidWebDir.exists()) {
                    tidWebDir.mkdirs()
                }
                
                // Generate a unique file name if not provided
                val safeFileName = fileName?.takeIf { it.isNotBlank() }
                    ?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    ?: "imported_wiki_${System.currentTimeMillis()}.html"
                
                // If the filename doesn't end with .html, add it
                val finalFileName = if (safeFileName.endsWith(".html", ignoreCase = true)) {
                    safeFileName
                } else {
                    "$safeFileName.html"
                }
                
                // Create the output file
                val outputFile = File(tidWebDir, finalFileName)
                val fileOutputStream = outputFile.outputStream()
                
                // Copy the file
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    fileOutputStream.write(buffer, 0, bytesRead)
                }
                
                // Clean up
                inputStream.close()
                fileOutputStream.close()
                
                // Create a file URI for the new file
                val fileUri = Uri.fromFile(outputFile)
                
                // Extract a name from the filename
                val displayName = fileName?.substringBeforeLast('.') 
                    ?: contentUri.lastPathSegment
                    ?: "Imported Wiki"
                
                // Add the wiki to the list
                withContext(Dispatchers.Main) {
                    addWiki(displayName, fileUri.toString())
                    Toast.makeText(context, "Wiki imported successfully", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error importing wiki", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to import wiki: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}