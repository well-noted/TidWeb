package com.tiddlywikibrowser

import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import androidx.datastore.preferences.core.edit
import androidx.appcompat.app.AppCompatDelegate

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

    init {
        // Load saved wikis and theme on initialization
        viewModelScope.launch {
            context.dataStore.data.collect { preferences ->
                preferences[PreferencesKeys.IS_DARK_MODE]?.let { darkMode ->
                    _isDarkMode.value = darkMode
                }
                
                val wikiListJson = preferences[PreferencesKeys.WIKI_LIST] ?: "[]"
                val currentWikiJson = preferences[PreferencesKeys.CURRENT_WIKI]
                val faviconsJson = preferences[PreferencesKeys.FAVICONS] ?: "{}"
                val tagsJson = preferences[PreferencesKeys.QUICK_TAGS] ?: "[]"
                
                val wikis = parseWikiList(wikiListJson)
                _allWikis.value = wikis
                
                if (currentWikiJson != null) {
                    val current = parseWikiInstance(currentWikiJson)
                    _currentWiki.value = current
                }
                // Load cached favicons
                loadFavicons(faviconsJson)

                _quickTags.value = parseQuickTags(tagsJson)
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
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                ThreadManager.runOnBackground {
                    // Your existing JavaScript injection code
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val currentUrl = view?.url ?: return
                setFavicon(currentUrl, favicon)
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
        val newWiki = WikiInstance(name, url)
        viewModelScope.launch {
            val newList = _allWikis.value + newWiki
            _allWikis.value = newList
            if (_currentWiki.value == null) {
                _currentWiki.value = newWiki
            }
            saveWikis(newList)
        }
    }

    fun setCurrentWiki(wiki: WikiInstance) {
        viewModelScope.launch(Dispatchers.Main) {
            if (_currentWiki.value?.url != wiki.url) {
                // Update preferences first
                context.dataStore.edit { preferences ->
                    preferences[PreferencesKeys.CURRENT_WIKI] = wikiToJson(wiki)
                }
                // Create a WebView for the new wiki if not already cached
                if (!webViewCache.containsKey(wiki.url)) {
                    createAndCacheWebView(wiki)
                }
                
                // Store the current wiki URL before switching
                val oldWikiUrl = _currentWiki.value?.url
                _currentWiki.value = wiki

                // Instead of detaching the old WebView immediately, check if it's playing media
                oldWikiUrl?.let { url ->
                    if (url != wiki.url) {
                        webViewCache[url]?.let { oldWebView ->
                            // Check if the old WebView has active media
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
                                    // If no active media, detach the old WebView
                                    ThreadManager.runOnMain {
                                        detachWebView(url)
                                    }
                                }
                                // If media is playing, leave the WebView in memory
                            }
                        }
                    }
                }
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

    // Improved WebView creation with better error handling
    fun getOrCreateWebView(wiki: WikiInstance, context: Context): WebView {
        val key = wiki.id ?: wiki.url
        
        return webViewCache[key] ?: synchronized(this) {
            webViewCache[key]?.let { return it }
            
            val webView = MainActivity.createWebView(context).apply {
                // Set up WebViewClient with specific error handling
                webViewClient = createWebViewClient(wiki)
            }
            
            // Cache the WebView
            webViewCache[key] = webView
            webView
        }
    }

    // Better lifecycle management
    fun pauseAllWebViews() {
        webViewCache.values.forEach { it.onPause() }
    }

    fun resumeCurrentWebView(wiki: WikiInstance?) {
        wiki?.let {
            val key = it.id ?: it.url
            webViewCache[key]?.onResume()
        }
    }

    fun setFrameVisible(visible: Boolean) {
        viewModelScope.launch {
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
        _faviconMap.value.values.forEach { it.recycle() }
        _faviconMap.value = emptyMap()
        _favicon.value?.recycle()
        _favicon.value = null
        webViewCache.values.forEach { webView ->
            // Create a temporary WebView to clear cache statically
            WebView(context).clearCache(true)
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        webViewCache.clear()
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
            })
        }
        return jsonArray.toString()
    }

    private fun wikiToJson(wiki: WikiInstance): String {
        return JSONObject().apply {
            put("name", wiki.name)
            put("url", wiki.url)
        }.toString()
    }

    private fun parseWikiList(json: String): List<WikiInstance> {
        return try {
            val jsonArray = JSONArray(json)
            List(jsonArray.length()) { i ->
                val obj = jsonArray.getJSONObject(i)
                WikiInstance(obj.getString("name"), obj.getString("url"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseWikiInstance(json: String): WikiInstance? {
        return try {
            val obj = JSONObject(json)
            WikiInstance(obj.getString("name"), obj.getString("url"))
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
            webViewCache.clearAllExcept(currentWiki.value?.id)
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

    // Helper method to clear all WebViews except the current one
    fun MutableMap<String, WebView>.clearAllExcept(currentId: String?) {
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

    // Helper method to recycle WebView
    fun MutableMap<String, WebView>.recycle(key: String) {
        this[key]?.let { webView ->
            try {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.onPause()
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
            webViewCache.recycle(wikiKey)
            System.gc() // Request garbage collection
        }
    }
}