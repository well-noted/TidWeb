package com.tiddlywikibrowser.handlers

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tiddlywikibrowser.*
import com.tiddlywikibrowser.cache.WebViewCache
import com.tiddlywikibrowser.managers.BackgroundModeManager
import com.tiddlywikibrowser.media.MediaSessionManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Handles activity lifecycle callbacks and related operations
 */
class LifecycleHandler(
    private val activity: MainActivity,
    private val viewModelProvider: () -> WikiViewModel?,
    private val backgroundModeManager: BackgroundModeManager,
    private val backgroundWebViewManager: BackgroundWebViewManager,
    private val mediaSessionManager: MediaSessionManager,
    private val exoPlayerManager: ExoPlayerManager
) {
    private val TAG = "LifecycleHandler"
    private var webViewPaused = false
    
    val isBackgroundEnabled: StateFlow<Boolean> get() = backgroundModeManager.isBackgroundEnabled
    
    fun onPause() {
        webViewPaused = true
        
        if (!backgroundModeManager.isBackgroundEnabled.value) {
            Log.d(TAG, "onPause - Background mode disabled, performing standard pause.")
            exoPlayerManager.onPause()
            viewModelProvider()?.let { vm ->
                vm.currentWiki.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    WebViewCache.cacheWebView(key, vm.getOrCreateWebView(wiki, activity))
                }
                vm.pauseAllWebViews()
            }
        } else {
            Log.d(TAG, "onPause - Background mode enabled, skipping standard pause actions.")
            viewModelProvider()?.currentWiki?.value?.let { wiki ->
                viewModelProvider()?.getOrCreateWebView(wiki, activity)?.let { webView ->
                    val key = wiki.idFromUrl ?: wiki.url
                    if (!backgroundWebViewManager.hasWebView(key)) {
                        Log.w(TAG, "Re-registering WebView for background mode on pause")
                        backgroundModeManager.registerWebViewForBackground(wiki, webView, activity)
                    }
                    
                    // Start media service
                    try {
                        val serviceIntent = Intent(activity, MediaPlaybackService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            activity.startForegroundService(serviceIntent)
                        } else {
                            activity.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start media service in onPause", e)
                    }
                    
                    // Trigger state preservation
                    webView.evaluateJavascript("""
                        (function() {
                            document.dispatchEvent(new Event('pause'));
                            return true;
                        })();
                    """.trimIndent(), null)
                    
                    // Force resume videos
                    backgroundWebViewManager.service?.forceResumeVideos()
                    
                    // Notify service
                    val serviceIntent = Intent(activity, BackgroundWebViewService::class.java).apply {
                        action = BackgroundWebViewService.ACTION_APP_BACKGROUND
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            activity.startForegroundService(serviceIntent)
                        } else {
                            activity.startService(serviceIntent)
                        }
                        Log.d(TAG, "Sent app background notification to service")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send background notification to service", e)
                    }
                    
                    // Mark videos to continue in background
                    webView.evaluateJavascript("""
                        (function() {
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
    
    fun onResume() {
        webViewPaused = false
        exoPlayerManager.onResume()
        
        Log.d(TAG, "onResume - Background mode is ${if (backgroundModeManager.isBackgroundEnabled.value) "ENABLED" else "DISABLED"}")
        
        // Set WebViewProvider for MediaSessionManager
        mediaSessionManager.setWebViewProvider(object : WebViewProvider {
            override fun executeJavascript(script: String, callback: ((String) -> Unit)?) {
                activity.getCurrentWebView()?.evaluateJavascript(script, callback)
            }
            
            override fun getCurrentMediaState(callback: (title: String?, artist: String?, duration: Long?, position: Long?, isPlaying: Boolean?) -> Unit) {
                activity.getCurrentWebView()?.evaluateJavascript(MainActivity.mediaMonitorScript) { result ->
                    // Implementation here
                    callback(null, null, null, null, null)
                }
            }
        })
        
        if (webViewPaused) {
            activity.currentWebView?.onResume()
            activity.currentWebView?.resumeTimers()
            webViewPaused = false
            Log.d(TAG, "WebView resumed and timers started.")
        }
        
        if (!backgroundModeManager.isBackgroundEnabled.value) {
            Log.d(TAG, "onResume - Background mode disabled, resuming standard WebView.")
            viewModelProvider()?.resumeCurrentWebView(viewModelProvider()?.currentWiki?.value)
        } else {
            Log.d(TAG, "Resuming with background mode enabled")
            val currentWiki = viewModelProvider()?.currentWiki?.value
            if (currentWiki != null) {
                val key = currentWiki.idFromUrl ?: currentWiki.url
                var webView = backgroundWebViewManager.getWebView(key)
                
                if (webView == null) {
                    Log.w(TAG, "WebView not found in BackgroundWebViewManager on resume")
                    webView = viewModelProvider()?.getOrCreateWebView(currentWiki, activity)
                    
                    if (webView != null && !backgroundWebViewManager.hasWebView(key)) {
                        Log.d(TAG, "Re-registering WebView for background mode on resume")
                        backgroundModeManager.registerWebViewForBackground(currentWiki, webView, activity)
                    }
                }
                
                webView?.let { wv ->
                    try {
                        if (!isWebViewDestroyed(wv)) {
                            wv.visibility = View.VISIBLE
                            wv.onResume()
                            
                            try {
                                wv.evaluateJavascript("""
                                    (function() {
                                        document.dispatchEvent(new Event('resume'));
                                        return true;
                                    })();
                                """.trimIndent(), null)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error dispatching resume event to WebView: ${e.message}")
                            }
                        } else {
                            Log.w(TAG, "Skipping resume operations on destroyed WebView")
                            WebViewCache.removeCachedWebView(key)
                            viewModelProvider()?.getOrCreateWebView(currentWiki, activity)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resuming WebView: ${e.message}")
                        WebViewCache.removeCachedWebView(key)
                        viewModelProvider()?.getOrCreateWebView(currentWiki, activity)
                    }
                } ?: run {
                    Log.e(TAG, "Failed to get WebView instance on resume in background mode")
                }
            }
        }
        
        // Update media session
        try {
            activity.getCurrentWebView()?.let { webView ->
                if (!isWebViewDestroyed(webView)) {
                    mediaSessionManager.setWebView(webView)
                    if (!mediaSessionManager.isServiceBound()) {
                        mediaSessionManager.bindToService()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating media session: ${e.message}")
        }
    }
    
    fun onStop(isChangingConfigurations: Boolean) {
        if (!isChangingConfigurations && !backgroundModeManager.isBackgroundEnabled.value) {
            Log.d(TAG, "onStop - Background mode disabled and not changing config, performing standard cleanup.")
            viewModelProvider()?.let { vm ->
                vm.currentWiki.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    vm.getOrCreateWebView(wiki, activity)?.let { webView ->
                        WebViewCache.cacheWebView(key, webView)
                    }
                }
            }
        } else if (backgroundModeManager.isBackgroundEnabled.value) {
            Log.d(TAG, "onStop - Background mode enabled, ensuring videos continue in background.")
            
            backgroundWebViewManager.forceResumeVideos()
            
            try {
                val serviceIntent = Intent(activity, BackgroundWebViewService::class.java).apply {
                    action = BackgroundWebViewService.ACTION_APP_BACKGROUND
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(serviceIntent)
                } else {
                    activity.startService(serviceIntent)
                }
                Log.d(TAG, "Sent explicit app background notification in onStop")
            } catch (e: Exception) {
                Log.e(TAG, "Error ensuring service is running in onStop", e)
            }
        }
    }
    
    fun onLowMemory() {
        Log.d(TAG, "onLowMemory called - cleaning up resources")
        WebViewCache.onLowMemory()
        viewModelProvider()?.onLowMemory()
    }
    
    fun onTrimMemory(level: Int) {
        Log.d(TAG, "onTrimMemory called with level: $level")
        
        if (backgroundModeManager.isBackgroundEnabled.value) {
            viewModelProvider()?.currentWiki?.value?.let { wiki ->
                val key = wiki.idFromUrl ?: wiki.url
                WebViewCache.markWebViewStateUncertain(key)
                
                if (backgroundWebViewManager.hasWebView(key)) {
                    Log.d(TAG, "WebView is registered with background service, skipping state marking")
                } else {
                    Log.d(TAG, "WebView not found in background service, attempting recovery")
                    viewModelProvider()?.getOrCreateWebView(wiki, activity)?.let { webView ->
                        backgroundWebViewManager.registerWebView(key, webView)
                    }
                }
            }
        }
        
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            ThreadManager.runOnBackground {
                viewModelProvider()?.onLowMemory()
            }
        }
    }
    
    /**
     * Safely check if a WebView is destroyed to avoid crashes
     */
    private fun isWebViewDestroyed(webView: WebView): Boolean {
        return try {
            webView.settings
            false
        } catch (e: Exception) {
            Log.w(TAG, "WebView appears to be destroyed: ${e.message}")
            true
        }
    }
} 