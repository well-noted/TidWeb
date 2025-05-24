package com.tiddlywikibrowser.managers

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.datastore.preferences.core.edit
import com.tiddlywikibrowser.*
import com.tiddlywikibrowser.cache.WebViewCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Manages background mode functionality including state, preferences, and WebView registration
 */
class BackgroundModeManager(
    private val context: Context,
    private val backgroundWebViewManager: BackgroundWebViewManager,
    private val lifecycleScope: kotlinx.coroutines.CoroutineScope
) {
    private val _isBackgroundEnabled = MutableStateFlow(false)
    val isBackgroundEnabled: StateFlow<Boolean> = _isBackgroundEnabled
    
    private val TAG = "BackgroundModeManager"
    
    /**
     * Synchronously load the background mode preference
     * This is critical to have loaded before initializing services
     */
    fun loadBackgroundModePreference() {
        try {
            val dataStore = context.dataStore
            val preferences = runBlocking {
                dataStore.data.first()
            }
            val isEnabled = preferences[PreferencesKeys.BACKGROUND_MODE_ENABLED] ?: false
            _isBackgroundEnabled.value = isEnabled
            
            Log.d(TAG, "Background mode preference loaded synchronously: $isEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading background mode preference", e)
            _isBackgroundEnabled.value = false
        }
    }
    
    /**
     * Save the background mode preference
     */
    fun saveBackgroundModePreference(isEnabled: Boolean) {
        lifecycleScope.launch {
            context.dataStore.edit { preferences ->
                preferences[PreferencesKeys.BACKGROUND_MODE_ENABLED] = isEnabled
            }
        }
    }
    
    /**
     * Toggle background mode on/off
     */
    fun toggleBackgroundMode(
        viewModel: WikiViewModel?,
        activity: MainActivity,
        startMediaService: () -> Unit,
        stopMediaService: () -> Unit
    ) {
        val newState = !_isBackgroundEnabled.value
        _isBackgroundEnabled.value = newState
        
        saveBackgroundModePreference(newState)
        
        if (newState) {
            backgroundWebViewManager.startBackgroundService()
            startMediaService()
            
            viewModel?.currentWiki?.value?.let { wiki ->
                val key = wiki.idFromUrl ?: wiki.url
                val webView = viewModel.getOrCreateWebView(wiki, activity)
                webView?.let {
                    registerWebViewForBackground(wiki, it, activity)
                }
            }
            
            Toast.makeText(context,
                "Background mode enabled. TiddlyWiki will continue running when minimized.",
                Toast.LENGTH_LONG).show()
        } else {
            // Clean up background mode
            viewModel?.currentWiki?.value?.let { wiki ->
                val key = wiki.idFromUrl ?: wiki.url
                val webView = activity.getCurrentWebView()
                webView?.let {
                    WebViewCache.cacheWebView(key, it)
                    
                    it.evaluateJavascript("""
                        (function() {
                            document.dispatchEvent(new CustomEvent('backgroundModeDisabled'));
                            return true;
                        })();
                    """.trimIndent(), null)
                }
            }
            
            backgroundWebViewManager.stopBackgroundService()
            stopMediaService()
            
            Toast.makeText(context,
                "Background mode disabled.",
                Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Set background mode enabled/disabled
     */
    fun setBackgroundEnabled(
        enabled: Boolean,
        viewModel: WikiViewModel?,
        activity: MainActivity,
        startMediaService: () -> Unit,
        stopMediaService: () -> Unit
    ) {
        if (_isBackgroundEnabled.value != enabled) {
            Log.d(TAG, "Setting background mode to: $enabled")
            _isBackgroundEnabled.value = enabled
            
            saveBackgroundModePreference(enabled)
            
            if (enabled) {
                viewModel?.currentWiki?.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    WebViewCache.clearCacheEntry(key)
                }
                
                backgroundWebViewManager.startBackgroundService()
                
                Handler(Looper.getMainLooper()).postDelayed({
                    startMediaService()
                    
                    viewModel?.currentWiki?.value?.let { wiki ->
                        Log.d(TAG, "Registering current wiki for background mode: ${wiki.name}")
                        viewModel.getOrCreateWebView(wiki, activity)?.let { webView ->
                            registerWebViewForBackground(wiki, webView, activity)
                            
                            val key = wiki.idFromUrl ?: wiki.url
                            if (!backgroundWebViewManager.hasWebView(key)) {
                                Log.d(TAG, "Force registering WebView for background mode: ${wiki.name}")
                                backgroundWebViewManager.registerWebView(key, webView)
                            }
                        }
                    }
                }, 500)
                
                Toast.makeText(context,
                    "Background mode enabled. TiddlyWiki will continue running when minimized.",
                    Toast.LENGTH_LONG).show()
            } else {
                Log.d(TAG, "Disabling background mode")
                viewModel?.currentWiki?.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    val webView = activity.getCurrentWebView()
                    webView?.let {
                        it.evaluateJavascript("""
                            (function() {
                                console.log('[Background] Disabling background mode');
                                document.dispatchEvent(new Event('pause'));
                                document.dispatchEvent(new CustomEvent('backgroundModeDisabled'));
                                
                                if (window.__stateSaveInterval) {
                                    clearInterval(window.__stateSaveInterval);
                                    delete window.__stateSaveInterval;
                                }
                                
                                return true;
                            })();
                        """.trimIndent(), null)
                        
                        WebViewCache.cacheWebView(key, it)
                    }
                }
                
                backgroundWebViewManager.stopBackgroundService()
                stopMediaService()
                
                Toast.makeText(context,
                    "Background mode disabled.",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Register a WebView for background operation with state preservation
     */
    fun registerWebViewForBackground(wiki: WikiInstance, webView: WebView, activity: MainActivity) {
        val key = wiki.idFromUrl ?: wiki.url
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(false)
        }
        
        webView.evaluateJavascript("""
            (function() {
                if (!window.__backgroundModeInitialized) {
                    window.__savedState = window.__savedState || {};
                    
                    document.addEventListener('pause', function() {
                        try {
                            window.__savedState.scrollPosition = window.scrollY;
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
                    
                    window.__stateSaveInterval = setInterval(function() {
                        if (document.visibilityState === 'hidden') {
                            document.dispatchEvent(new Event('pause'));
                        }
                    }, 30000);
                    
                    window.__backgroundModeInitialized = true;
                }
                return document.readyState;
            })();
        """.trimIndent()) { state ->
            if (state.contains("complete") || state.contains("interactive")) {
                backgroundWebViewManager.registerWebView(key, webView)
                Log.d(TAG, "Registered WebView with state preservation: ${wiki.name}")
            } else {
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (view != null && _isBackgroundEnabled.value) {
                            backgroundWebViewManager.registerWebView(key, view)
                            Log.d(TAG, "Registered WebView after load completion: ${wiki.name}")
                        }
                    }
                }
            }
        }
    }
    
    fun startServicesIfEnabled(startMediaService: () -> Unit) {
        if (_isBackgroundEnabled.value) {
            Log.d(TAG, "Starting background services from preferences")
            backgroundWebViewManager.startBackgroundService()
            
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    startMediaService()
                    Log.d(TAG, "Media service started on app initialization")
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting media service during initialization", e)
                }
            }, 500)
            
            Log.d(TAG, "Background mode initialized from preferences")
        }
    }
} 