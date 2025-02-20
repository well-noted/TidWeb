package com.tiddlywikibrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.ViewModelFactoryDsl
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.webkit.WebSettings.FORCE_DARK_ON
import android.webkit.WebSettings.FORCE_DARK_OFF
import android.webkit.WebSettings.FORCE_DARK_AUTO
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Description  // Add this import
import java.io.ByteArrayInputStream
import java.net.URL
import androidx.webkit.WebViewAssetLoader
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SwipeToDismiss
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.graphics.Bitmap
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.Snackbar
import android.net.NetworkCapabilities
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.abs
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import android.webkit.JavascriptInterface
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.view.View
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Divider

private val Context.dataStore by preferencesDataStore(name = "settings")

data class WikiInstance(
    val name: String,
    val url: String
)

private object PreferencesKeys {
    val WIKI_LIST = stringPreferencesKey("wiki_list")
    val CURRENT_WIKI = stringPreferencesKey("current_wiki")
    val IS_DARK_MODE = stringPreferencesKey("is_dark_mode")
    val FAVICONS = stringPreferencesKey("favicons")
    val QUICK_TAGS = stringPreferencesKey("quick_tags") // Add new key for quick tags
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

    init {
        // Load saved wikis and theme on initialization
        viewModelScope.launch {
            context.dataStore.data.collect { preferences ->
                preferences[PreferencesKeys.IS_DARK_MODE]?.toBoolean()?.let { darkMode ->
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
                preferences[PreferencesKeys.IS_DARK_MODE] = enabled.toString()
            }
            _isDarkMode.value = enabled
            AppCompatDelegate.setDefaultNightMode(
                if (enabled) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
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

    // New helper function that detaches (but does not destroy) the WebView
    private fun detachWebView(url: String) {
        webViewCache[url]?.let { webView ->
            // Remove from its parent so it's no longer in the view hierarchy
            (webView.parent as? ViewGroup)?.removeView(webView)
            // Pause WebView processing so it remains in the background
            webView.onPause()
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
                                    Handler(Looper.getMainLooper()).post {
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

    fun getOrCreateWebView(wiki: WikiInstance, context: Context): WebView {
        return webViewCache.getOrPut(wiki.url) {
            // If cache size exceeds, evict an old WebView not in use.
            if (webViewCache.size >= MAX_WEBVIEW_CACHE) {
                val urlToRemove = webViewCache.keys
                    .filter { it != _currentWiki.value?.url }
                    .firstOrNull()
                urlToRemove?.let { url ->
                    cleanupWebView(url)
                }
            }
            MainActivity.createWebView(context).apply {
                settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                loadUrl(wiki.url)
            }
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
            webView.clearCache(true)
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
}

class ViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WikiViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WikiViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class ExoPlayerManager(private val context: Context) {
    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var _currentPosition: Long = 0
    private var wasPlaying: Boolean = false

    fun getOrCreatePlayer(): ExoPlayer {
        if (player == null) {
            player = ExoPlayer.Builder(context).build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _currentPosition = player?.currentPosition ?: 0
                        (context as? MainActivity)?.let { activity ->
                            activity.mediaSessionManager.updatePlaybackState(isPlaying, _currentPosition)
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            player?.let { exoPlayer ->
                                _currentPosition = exoPlayer.currentPosition
                            }
                        }
                    }
                })
            }
        }
        return player!!
    }

    fun playMedia(url: String) {
        if (url != currentUrl) {
            currentUrl = url
            val mediaItem = MediaItem.fromUri(url)
            getOrCreatePlayer().apply {
                setMediaItem(mediaItem)
                prepare()
            }
        }
    }

    fun onPause() {
        player?.let {
            wasPlaying = it.isPlaying
            _currentPosition = it.currentPosition
            it.pause()
        }
    }

    fun onResume() {
        if (wasPlaying) {
            player?.seekTo(_currentPosition)
            player?.play()
        }
    }

    fun release() {
        _currentPosition = 0
        wasPlaying = false
        player?.release()
        player = null
        currentUrl = null
    }

    fun getCurrentPosition(): Long {
        return player?.currentPosition ?: _currentPosition
    }

    fun isPlaying(): Boolean = player?.isPlaying == true
}

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
    private var serviceConnection: ServiceConnection? = null  // Add this line
    private var showTagManagement by mutableStateOf(false)

    private var pendingSharedText by mutableStateOf<String?>(null)
    private var showWikiSelector by mutableStateOf(false)
    private var showAddDialog by mutableStateOf(false)

    // Add method to access current WebView
    fun getCurrentWebView(): WebView? {
        return viewModel?.currentWiki?.value?.let { wiki ->
            viewModel?.getOrCreateWebView(wiki, this)
        }
    }

    companion object {
        private var viewModelInstance: WikiViewModel? = null

        internal fun getViewModel(context: Context): WikiViewModel {
            return viewModelInstance ?: synchronized(this) {
                viewModelInstance ?: ViewModelProvider(
                    context as ComponentActivity,
                    ViewModelFactory(context.applicationContext)
                )[WikiViewModel::class.java].also { viewModelInstance = it }
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        internal fun createWebView(context: Context): WebView {
            return WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    
                    // Modern caching configuration for large wikis
                    cacheMode = WebSettings.LOAD_DEFAULT
                    allowFileAccess = true
                    domStorageEnabled = true // Enable DOM storage
                    databaseEnabled = true   // Enable database storage
                    
                    // Set generous cache sizes
                    setDatabasePath(context.getDir("database", Context.MODE_PRIVATE).path)
                    
                    when (AppCompatDelegate.getDefaultNightMode()) {
                        AppCompatDelegate.MODE_NIGHT_YES -> forceDark = WebSettings.FORCE_DARK_ON
                        AppCompatDelegate.MODE_NIGHT_NO -> forceDark = WebSettings.FORCE_DARK_OFF
                        else -> forceDark = WebSettings.FORCE_DARK_AUTO
                    }
                    saveFormData = true
                    allowContentAccess = true
                    allowFileAccess = true
                }
                
                // Add JavaScript interface for scroll detection
                class ScrollInterface(private val context: Context) {
                    @JavascriptInterface
                    fun onScroll(showBars: Boolean) {
                        Handler(Looper.getMainLooper()).post {
                            MainActivity.getViewModel(context).setFrameVisible(showBars)
                        }
                    }
                }
                addJavascriptInterface(ScrollInterface(context), "ScrollInterface")

                // Add JavaScript interface for media control
                class MediaInterface(private val context: Context) {
                    @JavascriptInterface
                    fun onMediaStateChange(title: String?, artist: String?, duration: Long, position: Long, isPlaying: Boolean) {
                        Handler(Looper.getMainLooper()).post {
                            (context as? MainActivity)?.let { activity ->
                                activity.mediaSessionManager.updateMetadata(title, artist, duration)
                                activity.mediaSessionManager.updatePlaybackState(isPlaying, position)
                            }
                        }
                    }
                }
                addJavascriptInterface(MediaInterface(context), "MediaInterface")

                addJavascriptInterface(object : Any() {
                    @JavascriptInterface
                    fun playMedia(url: String) {
                        (context as? MainActivity)?.let { activity ->
                            activity.runOnUiThread {
                                activity.exoPlayerManager.playMedia(url)
                            }
                        }
                    }
                }, "ExoPlayerInterface")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        
                        // Inject scroll monitoring script
                        view?.evaluateJavascript("""
                            (function() {
                                let lastScrollY = window.scrollY;
                                window.addEventListener('scroll', function() {
                                    const currentScrollY = window.scrollY;
                                    const isScrollingUp = currentScrollY < lastScrollY;
                                    lastScrollY = currentScrollY;
                                    window.ScrollInterface.onScroll(isScrollingUp || currentScrollY === 0);
                                }, { passive: true });
                            })();
                        """.trimIndent(), null)

                        // Inject updated media monitoring script with suspended search when playing
                        view?.evaluateJavascript("""
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
                        """.trimIndent(), null)

                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        val currentUrl = view?.url ?: return
                        MainActivity.getViewModel(view.context).setFavicon(currentUrl, favicon)
                    }
                }
                
                webChromeClient = object : WebChromeClient() {
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
                }

                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaSessionManager = MediaSessionManager(this)
        exoPlayerManager = ExoPlayerManager(this)
        viewModel = getViewModel(this)

        handleIntent(intent)

        // Start the MediaPlaybackService
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

        // Fix ServiceConnection implementation
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
                val mediaService = (binder as? MediaPlaybackService.LocalBinder)?.service
                mediaService?.setCallback(object : MediaPlaybackService.MediaPlayerCallback {
                    override fun onPlay() {
                        exoPlayerManager.getOrCreatePlayer().play()
                    }

                    override fun onPause() {
                        exoPlayerManager.getOrCreatePlayer().pause()
                    }

                    override fun onSeekTo(pos: Long) {
                        exoPlayerManager.getOrCreatePlayer().seekTo(pos)
                    }

                    override fun onSkipForward() {
                        val currentPosition = exoPlayerManager.getOrCreatePlayer().currentPosition
                        exoPlayerManager.getOrCreatePlayer().seekTo(currentPosition + 15000)
                    }

                    override fun onSkipBackward() {
                        val currentPosition = exoPlayerManager.getOrCreatePlayer().currentPosition
                        exoPlayerManager.getOrCreatePlayer().seekTo(maxOf(0, currentPosition - 15000))
                    }
                })
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                // Handle service disconnection if needed
            }
        }.also { connection ->
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        }

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        setupNetworkCallback()
        
        setContent {
            val viewModel = getViewModel(LocalContext.current as ComponentActivity)
            val isDarkMode by viewModel.isDarkMode.collectAsState()

            MaterialTheme(
                colorScheme = if (isDarkMode) {
                    darkColorScheme()
                } else {
                    lightColorScheme()
                }
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

                        // Dialogs layer
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
        viewModel?.setCurrentWiki(selectedWiki)
        Handler(Looper.getMainLooper()).postDelayed({
            getCurrentWebView()?.evaluateJavascript("""
                (function() {
                    try {
                        console.log('Checking TiddlyWiki state...');
                        var tiddlywiki = window['${'$'}tw'];
                        if (!tiddlywiki || !tiddlywiki.wiki) {
                            console.log('TiddlyWiki object not found');
                            return 'not_ready';
                        }
                        
                        var title = 'Shared Content ' + new Date().toISOString();
                        var processedText = ${JSONObject.quote(textToShare ?: "")}.replace(/\\\\n/g, "\n").replace(/\\\\t/g, "\t").replace(/\\n/g, "\n").replace(/\\t/g, "\t");
                        processedText = processedText.replace(/(\n\s*){5,}/g, "\n".repeat(5));
                        var tags = ${JSONArray(selectedTags).toString()};
                        
                        tiddlywiki.wiki.addTiddler({
                            title: title,
                            text: processedText,
                            tags: tags
                        });
                        
                        console.log('Tiddler created with title:', title);
                        console.log('Tags added:', tags);
                        
                        if (tiddlywiki.story && typeof tiddlywiki.story.navigateTiddler === 'function') {
                            tiddlywiki.story.navigateTiddler(title);
                            console.log('Navigated to tiddler');
                        } else {
                            console.log('Navigation not available');
                        }
                        
                        return title;
                    } catch (e) {
                        console.error('Error creating tiddler:', e);
                        return null;
                    }
                })();
            """.trimIndent()) { result ->
                if (result == "not_ready") {
                    // Try again after a longer delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        handleWikiSelection(selectedWiki, textToShare, selectedTags)
                    }, 2000)
                } else if (result != "null") {
                    Toast.makeText(this, "Content added as new tiddler", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not create tiddler - Make sure wiki is loaded", Toast.LENGTH_SHORT).show()
                }
            }
        }, 1000)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
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
        viewModel?.currentWiki?.value?.let { wiki ->
            viewModel?.getOrCreateWebView(wiki, this)?.let { webView ->
                webView.onPause()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webViewPaused = false
        exoPlayerManager.onResume()
        viewModel?.currentWiki?.value?.let { wiki ->
            viewModel?.getOrCreateWebView(wiki, this)?.let { webView ->
                webView.onResume()
            }
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
                currentWiki?.let { wiki ->
                    WikiView(wiki = wiki, viewModel = viewModel)
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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    tags.forEachIndexed { index, tag ->
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

@Composable
fun WikiView(wiki: WikiInstance, viewModel: WikiViewModel) {
    val context = LocalContext.current
    var isKeyboardVisible by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        AndroidView(
            factory = { ctx ->
                viewModel.getOrCreateWebView(wiki, ctx).apply {
                    visibility = View.VISIBLE
                }
            },
            update = { webView ->
                if (webView.url != wiki.url) {
                    webView.loadUrl(wiki.url)
                }
                webView.visibility = View.VISIBLE
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
