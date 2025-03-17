package com.tiddlywikibrowser

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import androidx.datastore.preferences.core.edit
import android.util.Log
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.widget.Toast
import java.io.File
import com.tiddlywikibrowser.model.TiddlerTemplate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

// Constants for operation priorities and timeouts
private const val NAVIGATION_THROTTLE_MS = 500L
private const val WEBVIEW_OPERATION_TIMEOUT = 5000L
private const val MAX_RETRY_ATTEMPTS = 3

class WikiViewModel(private val context: Context) : ViewModel() {
    // State flows for UI state
    private val _currentWiki = MutableStateFlow<WikiInstance?>(null)
    val currentWiki: StateFlow<WikiInstance?> = _currentWiki

    private val _allWikis = MutableStateFlow<List<WikiInstance>>(emptyList())
    val allWikis: StateFlow<List<WikiInstance>> = _allWikis

    private val themeManager = ThemeManager(context)
    private val _isDarkMode = MutableStateFlow(themeManager.isDarkModeEnabled())
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    private val _isFrameVisible = MutableStateFlow(true)
    val isFrameVisible: StateFlow<Boolean> = _isFrameVisible

    private val _faviconMap = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val faviconMap: StateFlow<Map<String, Bitmap>> = _faviconMap

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline

    private val _quickTags = MutableStateFlow<List<String>>(emptyList())
    val quickTags: StateFlow<List<String>> = _quickTags

    private val _tiddlerTemplates = MutableStateFlow<List<TiddlerTemplate>>(emptyList())
    val tiddlerTemplates: StateFlow<List<TiddlerTemplate>> = _tiddlerTemplates.asStateFlow()

    private val _useSmallScreenCSS = MutableStateFlow(false)
    val useSmallScreenCSS: StateFlow<Boolean> = _useSmallScreenCSS

        // Cache for WebViews - increased to handle larger wikis
    private val MAX_WEBVIEW_CACHE = 10
    private val webViewCache = mutableMapOf<String, WebView>()

    // WebView system state
    private val _isWebViewReady = MutableStateFlow(false)
    val isWebViewReady: StateFlow<Boolean> = _isWebViewReady

    private val _isWebViewSystemReady = MutableStateFlow(false)
    val isWebViewSystemReady: StateFlow<Boolean> = _isWebViewSystemReady

    // Loading and error states
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Loading message state
    private val _loadingMessage = MutableStateFlow<String?>(null)
    val loadingMessage: StateFlow<String?> = _loadingMessage

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // State tracking
    private var isFirstWikiLoad = true
    private var isInitializingSingleFileWiki = false

    // Concurrency control
    private val navigationMutex = Mutex()
    private val operationMutex = Mutex()
    private val operationRetryCount = ConcurrentHashMap<String, Int>()

    // Coroutine exception handler
    private val viewModelExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        handleCrash(throwable)
    }

    // Coroutine scope for background operations
    private val operationScope = CoroutineScope(
        SupervisorJob() + 
        Dispatchers.Default + 
        viewModelExceptionHandler
    )

    init {
        setupCrashHandler()
        initializeViewModel()
        viewModelScope.launch {
            context.dataStore.data.collect { preferences ->
                preferences[PreferencesKeys.USE_SMALL_SCREEN_CSS]?.let { useSmallCSS ->
                    _useSmallScreenCSS.value = useSmallCSS
                }
            }
        }
    }

    private fun initializeViewModel() {
        viewModelScope.launch(viewModelExceptionHandler) {
            try {
                initializeWebViewSubsystem()
                loadSavedState()
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error during initialization", e)
                _error.value = "Failed to initialize: ${e.message}"
                _isWebViewSystemReady.value = true // Ensure we don't block forever
            }
        }
    }

    private suspend fun initializeWebViewSubsystem() {
        withContext(Dispatchers.Main) {
            try {
                // Create a test WebView with optimized settings
                val tempWebView = WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        defaultTextEncodingName = "UTF-8"
                        mediaPlaybackRequiresUserGesture = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }
                }

                // Test WebView initialization with timeout
                withTimeoutOrNull(WEBVIEW_OPERATION_TIMEOUT) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        tempWebView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url == "about:blank" && !continuation.isCompleted) {
                                    _isWebViewSystemReady.value = true
                                    continuation.resume(Unit)
                                }
                            }
                            
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (!continuation.isCompleted) {
                                    _isWebViewSystemReady.value = true
                                    continuation.resume(Unit)
                                    Log.w("WikiViewModel", "WebView initialization error: ${error?.description}")
                                }
                            }
                        }
                        tempWebView.loadUrl("about:blank")

                        continuation.invokeOnCancellation {
                            tempWebView.destroy()
                        }
                    }
                } ?: run {
                    Log.w("WikiViewModel", "WebView initialization timed out")
                    _isWebViewSystemReady.value = true
                }

                tempWebView.destroy()
            } catch (e: Exception) {
                Log.e("WikiViewModel", "WebView initialization failed", e)
                _isWebViewSystemReady.value = true
                throw e
            }
        }
    }

    private suspend fun loadSavedState() {
        context.dataStore.data.collect { preferences ->
            try {
                // Load and apply preferences in order of importance
                preferences[PreferencesKeys.IS_DARK_MODE]?.let { 
                    _isDarkMode.value = it 
                }

                val wikiListJson = preferences[PreferencesKeys.WIKI_LIST] ?: "[]"
                val wikis = parseWikiList(wikiListJson)
                _allWikis.value = wikis

                val currentWikiJson = preferences[PreferencesKeys.CURRENT_WIKI]
                if (currentWikiJson != null) {
                    parseWikiInstance(currentWikiJson)?.let { wiki ->
                        setCurrentWiki(wiki)
                    }
                }

                // Load non-critical data
                loadFavicons(preferences[PreferencesKeys.FAVICONS] ?: "{}")
                _quickTags.value = parseQuickTags(preferences[PreferencesKeys.QUICK_TAGS] ?: "[]")
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error loading saved state", e)
                _error.value = "Failed to load saved state: ${e.message}"
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
            Log.e("WikiViewModel", "Error loading favicons", e)
        }
    }

    private fun parseQuickTags(json: String): List<String> {
        return try {
            val jsonArray = JSONArray(json)
            List(jsonArray.length()) { i -> jsonArray.getString(i) }
        } catch (e: Exception) {
            Log.e("WikiViewModel", "Error parsing quick tags", e)
            emptyList()
        }
    }

    fun addWiki(name: String, url: String) {
        viewModelScope.launch(viewModelExceptionHandler) {
            try {
                val newWiki = WikiInstance(
                    name = name,
                    url = url,
                    isLocalFile = url.startsWith("file:") || url.startsWith("content:")
                )
                
                val newList = _allWikis.value + newWiki
                _allWikis.value = newList
                
                if (_currentWiki.value == null) {
                    setCurrentWiki(newWiki)
                }
                
                saveWikis(newList)
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error adding wiki", e)
                _error.value = "Failed to add wiki: ${e.message}"
            }
        }
    }

    // Navigation handling with retry logic
    fun setCurrentWiki(wiki: WikiInstance?) {
        viewModelScope.launch(viewModelExceptionHandler) {
            navigationMutex.withLock {
                if (_currentWiki.value?.url == wiki?.url) return@launch
                
                try {
                    performWikiNavigation(wiki)
                } catch (e: Exception) {
                    Log.e("WikiViewModel", "Navigation failed", e)
                    _error.value = "Failed to navigate to wiki: ${e.message}"
                    
                    // Attempt recovery
                    val key = wiki?.idFromUrl ?: wiki?.url
                    if (key != null && shouldRetryOperation(key)) {
                        delay(1000)
                        performWikiNavigation(wiki)
                    }
                }
            }
        }
    }

    private suspend fun performWikiNavigation(wiki: WikiInstance?) {
        // Save current wiki state
        _currentWiki.value?.let { currentWiki ->
            val key = currentWiki.idFromUrl ?: currentWiki.url
            WebViewCache.cacheWebView(key, getOrCreateWebView(currentWiki, context))
        }

        if (wiki == null) {
            _currentWiki.value = null
            return
        }

        val key = wiki.idFromUrl ?: wiki.url
        WebViewCache.setCurrentActiveKey(key)
        
        // Pause other WebViews before switching
        WebViewCache.pauseAllWebViewsExcept(key)
        
        withContext(Dispatchers.Main) {
            try {
                Log.d("WikiViewModel", "Switching to wiki: ${wiki.name} at URL: ${wiki.url}")
                
                // Important: Set as current wiki before accessing WebView to ensure UI updates
                _currentWiki.value = wiki
                
                // Check if we already have a cached WebView for this wiki
                val webView = WebViewCache.getCachedWebView(key) ?: createNewWebView(wiki, context)
                
                // Make sure the WebView is visible and properly connected to the window
                webView.visibility = View.VISIBLE
                
                // Resume the WebView
                webView.onResume()
                WebViewCache.resumeWebView(key)
                
                // CRITICAL: If this WebView has never been successfully loaded, we need to load it
                val isAlreadyLoaded = webView.getTag(R.string.prevent_reload_tag) as? Boolean ?: false
                if (!isAlreadyLoaded) {
                    Log.d("WikiViewModel", "WebView not previously loaded, loading URL: ${wiki.url}")
                    webView.loadUrl(wiki.url)
                } else {
                    Log.d("WikiViewModel", "Using cached WebView for: ${wiki.name}")
                }
                
                // Save current wiki preference
                context.dataStore.edit { preferences ->
                    preferences[PreferencesKeys.CURRENT_WIKI] = wikiToJson(wiki)
                }
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error switching wiki", e)
                throw e
            }
        }
    }

    // WebView creation with optimized settings
    fun getOrCreateWebView(wiki: WikiInstance, context: Context): WebView {
        val key = wiki.idFromUrl ?: wiki.url
        
        return WebViewCache.getCachedWebView(key) ?: createNewWebView(wiki, context)
    }

    private fun createNewWebView(wiki: WikiInstance, context: Context): WebView {
        return MainActivity.createWebView(context).apply {
            setTag(R.string.prevent_reload_tag, false)
            
            webViewClient = ReloadBlockingWebViewClient(
                context = context,
                wikiUrl = wiki.url,
                onLoadingStateChanged = { isLoading -> _isLoading.value = isLoading },
                onErrorReceived = { error -> _error.value = error },
                onPageLoaded = { success ->
                    if (success) {
                        val key = wiki.idFromUrl ?: wiki.url
                        WebViewCache.cacheWebView(key, this)
                    }
                }
            )
        }.also { webView ->
            val key = wiki.idFromUrl ?: wiki.url
            WebViewCache.cacheWebView(key, webView)
        }
    }

    // Lifecycle management
    fun pauseAllWebViews() {
        viewModelScope.launch(viewModelExceptionHandler) {
            _currentWiki.value?.let { wiki ->
                val key = wiki.idFromUrl ?: wiki.url
                WebViewCache.cacheWebView(key, getOrCreateWebView(wiki, context))
            }
            WebViewCache.pauseAllWebViewsExcept(null)
        }
    }

    fun resumeCurrentWebView(wiki: WikiInstance?) {
        viewModelScope.launch(viewModelExceptionHandler) {
            wiki?.let {
                val key = it.idFromUrl ?: it.url
                WebViewCache.resumeWebView(key)
            }
        }
    }

    // Memory management
    fun onLowMemory() {
        viewModelScope.launch(Dispatchers.IO + viewModelExceptionHandler) {
            val key = _currentWiki.value?.idFromUrl ?: _currentWiki.value?.url
            if (key != null) {
                WebViewCache.onLowMemory()
                clearWebViewDiskCache()
            }
        }
    }

    private fun clearWebViewDiskCache() {
        viewModelScope.launch(Dispatchers.IO + viewModelExceptionHandler) {
            try {
                context.cacheDir.deleteRecursively()
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error clearing disk cache", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        clearWebViews()
        operationScope.cancel()
    }

    // Quick tag management
    fun addQuickTag(tag: String) {
        viewModelScope.launch(viewModelExceptionHandler) {
            val newList = _quickTags.value + tag
            _quickTags.value = newList
            saveQuickTags(newList)
        }
    }

    fun removeQuickTag(tag: String) {
        viewModelScope.launch(viewModelExceptionHandler) {
            val newList = _quickTags.value - tag
            _quickTags.value = newList
            saveQuickTags(newList)
        }
    }

    fun reorderQuickTags(from: Int, to: Int) {
        viewModelScope.launch(viewModelExceptionHandler) {
            val newList = _quickTags.value.toMutableList()
            val tag = newList.removeAt(from)
            newList.add(to, tag)
            _quickTags.value = newList
            saveQuickTags(newList)
        }
    }

    private suspend fun saveQuickTags(tags: List<String>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.QUICK_TAGS] = JSONArray(tags).toString()
        }
    }

    // Wiki management
    fun deleteWiki(wiki: WikiInstance) {
        viewModelScope.launch(viewModelExceptionHandler) {
            try {
                val newList = _allWikis.value.filter { it.url != wiki.url }
                _allWikis.value = newList

                if (_currentWiki.value?.url == wiki.url) {
                    _currentWiki.value = newList.firstOrNull()
                }

                saveWikis(newList)
                
                // Clean up WebView resources
                val key = wiki.idFromUrl ?: wiki.url
                WebViewCache.removeCachedWebView(key)
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error deleting wiki", e)
                _error.value = "Failed to delete wiki: ${e.message}"
            }
        }
    }

    fun reorderWikis(from: Int, to: Int) {
        viewModelScope.launch(viewModelExceptionHandler) {
            try {
                val newList = _allWikis.value.toMutableList()
                val wiki = newList.removeAt(from)
                newList.add(to, wiki)
                _allWikis.value = newList
                saveWikis(newList)
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error reordering wikis", e)
                _error.value = "Failed to reorder wikis: ${e.message}"
            }
        }
    }

    // Memory management - make clearWebViews public
    fun clearWebViews() {
        viewModelScope.launch(viewModelExceptionHandler) {
            try {
                // Clear favicon resources
                _faviconMap.value.values.forEach { it.recycle() }
                _faviconMap.value = emptyMap()
                
                WebViewCache.clearAll()
                System.gc()
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error clearing WebViews", e)
            }
        }
    }

    fun reduceMemoryUsage() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // Clear WebView caches first
                withContext(Dispatchers.Main) {
                    WebViewCache.clearCache(context)
                }
                
                // Trim memory on background thread
                _currentWiki.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    // Cache current WebView state before cleanup
                    WebViewCache.cacheWebView(key, getOrCreateWebView(wiki, context))
                }
                
                delay(100) // Give time for cache operations to complete
                
                // Clear non-essential caches
                WebViewCache.onLowMemory()
                
                // Force garbage collection on background thread
                System.gc()
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error reducing memory usage", e)
            }
        }
    }

    // Template management
    fun loadTiddlerTemplates() {
        viewModelScope.launch(Dispatchers.IO + viewModelExceptionHandler) {
            try {
                val templateFiles = context.assets.list("tiddler_templates") ?: emptyArray()
                val templates = mutableListOf<TiddlerTemplate>()

                for (fileName in templateFiles) {
                    if (fileName.endsWith(".html")) {
                        val inputStream = context.assets.open("tiddler_templates/$fileName")
                        val reader = inputStream.bufferedReader()
                        val firstLines = buildString {
                            repeat(10) {
                                append(reader.readLine() ?: "")
                                append("\n")
                            }
                        }

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
                Log.e("WikiViewModel", "Error loading templates", e)
                withContext(Dispatchers.Main) {
                    _tiddlerTemplates.value = emptyList()
                }
            }
        }
    }

    fun createSingleFileWiki(context: Context, template: TiddlerTemplate) {
        viewModelScope.launch(Dispatchers.IO + viewModelExceptionHandler) {
            try {
                val timestamp = System.currentTimeMillis()
                val fileName = "${template.name.replace(" ", "_")}_$timestamp.html"

                val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val tidWebDir = File(documentsDir, "TidWeb").apply { mkdirs() }
                val outputFile = File(tidWebDir, fileName)

                context.assets.open("tiddler_templates/${template.fileName}").use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val fileUri = Uri.fromFile(outputFile)
                val newWiki = WikiInstance(
                    name = template.name,
                    url = fileUri.toString(),
                    isLocalFile = true
                )

                withContext(Dispatchers.Main) {
                    addWiki(newWiki.name, newWiki.url)
                    setCurrentWiki(newWiki)
                    Toast.makeText(context, "Created new wiki: ${template.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error creating wiki", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error creating wiki: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Wiki update functionality
    fun updateWiki(oldWiki: WikiInstance, newWiki: WikiInstance) {
        viewModelScope.launch(viewModelExceptionHandler) {
            try {
                val newList = _allWikis.value.map { 
                    if (it.url == oldWiki.url) newWiki else it 
                }
                _allWikis.value = newList
                saveWikis(newList)

                if (_currentWiki.value?.url == oldWiki.url) {
                    val oldKey = oldWiki.idFromUrl ?: oldWiki.url
                    WebViewCache.removeCachedWebView(oldKey)
                    _currentWiki.value = newWiki

                    // Force reload the new wiki
                    withContext(Dispatchers.Main) {
                        val webView = getOrCreateWebView(newWiki, context)
                        webView.setTag(R.string.prevent_reload_tag, false)
                        webView.loadUrl(newWiki.url)
                    }
                }
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error updating wiki", e)
                _error.value = "Failed to update wiki: ${e.message}"
            }
        }
    }

    // UI state management
    fun setFrameVisible(visible: Boolean) {
        _isFrameVisible.value = visible
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

    fun renameWiki(wiki: WikiInstance, newName: String) {
        viewModelScope.launch(viewModelExceptionHandler) {
            try {
                // Create a new wiki with the updated name but same URL and other properties
                val updatedWiki = wiki.copy(name = newName)
                
                // Update the wiki in the list
                val newList = _allWikis.value.map { 
                    if (it.url == wiki.url) updatedWiki else it 
                }
                _allWikis.value = newList
                saveWikis(newList)

                // Update current wiki reference if needed
                if (_currentWiki.value?.url == wiki.url) {
                    _currentWiki.value = updatedWiki
                    
                    // Save current wiki preference with new name
                    context.dataStore.edit { preferences ->
                        preferences[PreferencesKeys.CURRENT_WIKI] = wikiToJson(updatedWiki)
                    }
                }
                
                // Show success message
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Wiki renamed successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error renaming wiki", e)
                _error.value = "Failed to rename wiki: ${e.message}"
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to rename wiki: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun setOfflineState(offline: Boolean) {
        if (_isOffline.value != offline) {
            _isOffline.value = offline
            if (offline) {
                viewModelScope.launch {
                    _currentWiki.value?.let { wiki ->
                        val webView = getOrCreateWebView(wiki, context)
                        webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }
                }
            }
        }
    }

    fun setUseSmallScreenCSS(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.USE_SMALL_SCREEN_CSS] = enabled
            }
            _useSmallScreenCSS.value = enabled
        }
    }

    // Helper methods for state persistence
    private suspend fun saveWikis(wikis: List<WikiInstance>) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WIKI_LIST] = wikiListToJson(wikis)
        }
    }

    private fun wikiListToJson(wikis: List<WikiInstance>): String {
        return JSONArray().apply {
            wikis.forEach { wiki ->
                put(JSONObject().apply {
                    put("name", wiki.name)
                    put("url", wiki.url)
                    put("isLocalFile", wiki.isLocalFile)
                    wiki.sourceUrl?.let { put("sourceUrl", it) }
                    wiki.id?.let { put("id", it) }
                })
            }
        }.toString()
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
                    id = obj.optString("id").takeIf { it.isNotEmpty() },
                    isLocalFile = obj.optBoolean("isLocalFile", false),
                    sourceUrl = obj.optString("sourceUrl").takeIf { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) {
            Log.e("WikiViewModel", "Error parsing wiki list", e)
            emptyList()
        }
    }

    private fun parseWikiInstance(json: String): WikiInstance? {
        return try {
            val obj = JSONObject(json)
            WikiInstance(
                name = obj.getString("name"),
                url = obj.getString("url"),
                id = obj.optString("id").takeIf { it.isNotEmpty() },
                isLocalFile = obj.optBoolean("isLocalFile", false),
                sourceUrl = obj.optString("sourceUrl").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            Log.e("WikiViewModel", "Error parsing wiki instance", e)
            null
        }
    }

    // File operations using coroutines
    fun importLocalWikiFile(contentUri: Uri, fileName: String?) {
        viewModelScope.launch(Dispatchers.IO + viewModelExceptionHandler) {
            try {
                val inputStream = context.contentResolver.openInputStream(contentUri) ?: throw Exception("Failed to open file")
                
                val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val tidWebDir = File(documentsDir, "TidWeb").apply { mkdirs() }
                
                val safeFileName = fileName?.takeIf { it.isNotBlank() }
                    ?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    ?: "imported_wiki_${System.currentTimeMillis()}.html"
                
                val finalFileName = if (safeFileName.endsWith(".html", ignoreCase = true)) {
                    safeFileName
                } else {
                    "$safeFileName.html"
                }
                
                val outputFile = File(tidWebDir, finalFileName)
                outputFile.outputStream().use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                
                val fileUri = Uri.fromFile(outputFile)
                val displayName = fileName?.substringBeforeLast('.') 
                    ?: contentUri.lastPathSegment
                    ?: "Imported Wiki"
                
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

        // Recycle WebView when it's no longer needed
    fun recycleWebView(wikiKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            WebViewCache.removeCachedWebView(wikiKey)
            webViewCache.remove(wikiKey)
            System.gc()
        }
    }

    // Error handling and recovery
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
                _error.value = throwable.message
                clearWebViews()
                _currentWiki.value = null
                
                context.dataStore.edit { preferences ->
                    preferences.remove(PreferencesKeys.CURRENT_WIKI)
                }
                
                System.gc()
            } catch (e: Exception) {
                Log.e("WikiViewModel", "Error handling crash", e)
            }
        }
    }

    private fun shouldRetryOperation(key: String): Boolean {
        val currentRetries = operationRetryCount.getOrDefault(key, 0)
        if (currentRetries < MAX_RETRY_ATTEMPTS) {
            operationRetryCount[key] = currentRetries + 1
            return true
        }
        return false
    }

    /**
     * Extract title from HTML content using regex
     */
    private fun extractTitle(htmlContent: String): String? {
        val titleRegex = "<title>(.*?)</title>".toRegex()
        return titleRegex.find(htmlContent)?.groupValues?.get(1)
    }

    /**
     * Extract description from HTML content (looks for meta description tag)
     */
    private fun extractDescription(htmlContent: String): String? {
        val descRegex = "<meta\\s+name=[\"']description[\"']\\s+content=[\"'](.*?)[\"']".toRegex()
        return descRegex.find(htmlContent)?.groupValues?.get(1)
    }

    /**
     * Set the loading state with an optional message
     */
    fun setLoading(isLoading: Boolean, message: String? = null) {
        _isLoading.value = isLoading
        _loadingMessage.value = if (isLoading) message else null
    }
}