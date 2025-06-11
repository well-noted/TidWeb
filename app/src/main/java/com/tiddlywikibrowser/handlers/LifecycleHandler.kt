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
import org.json.JSONObject
import kotlinx.coroutines.flow.StateFlow

/**
 * Handles activity lifecycle callbacks and related operations
 */
class LifecycleHandler(
    private val activity: MainActivity,
    private val viewModelProvider: () -> WikiViewModel?,
    private val backgroundModeManager: BackgroundModeManager,
    private val backgroundWebViewManager: BackgroundWebViewManager,
    private val mediaSessionManager: MediaSessionManager
) {
    private val TAG = "LifecycleHandler"
    private var webViewPaused = false
    
    val isBackgroundEnabled: StateFlow<Boolean> get() = backgroundModeManager.isBackgroundEnabled
      fun onPause() {
        webViewPaused = true
        
        Log.d("MEDIA_TRANSITION", "=== APP BACKGROUNDED ===")
        Log.d("MEDIA_TRANSITION", "Background mode enabled: ${backgroundModeManager.isBackgroundEnabled.value}")
        Log.d("MEDIA_TRANSITION", "MediaSessionManager service bound: ${mediaSessionManager.isServiceBound()}")
        Log.d("MEDIA_TRANSITION", "MediaSession active: ${mediaSessionManager.getMediaSession()?.isActive}")
        Log.d("MEDIA_TRANSITION", "Current media state: ${mediaSessionManager.getCurrentMediaState()}")
          if (!backgroundModeManager.isBackgroundEnabled.value) {
            Log.d("MEDIA_TRANSITION", "Background mode disabled - performing standard pause")
            Log.d(TAG, "onPause - Background mode disabled, performing standard pause.")
            viewModelProvider()?.let { vm ->
                vm.currentWiki.value?.let { wiki ->
                    val key = wiki.idFromUrl ?: wiki.url
                    WebViewCache.cacheWebView(key, vm.getOrCreateWebView(wiki, activity))
                }
                vm.pauseAllWebViews()
            }        } else {            Log.d("MEDIA_TRANSITION", "Background mode enabled - maintaining background state")
            Log.d(TAG, "onPause - Background mode enabled, skipping standard pause actions.")
            viewModelProvider()?.currentWiki?.value?.let { wiki ->
                Log.d("MEDIA_TRANSITION", "Current wiki: ${wiki.name}")
                android.util.Log.d("WEBVIEW_LIFECYCLE", "Getting WebView for wiki: ${wiki.name} (${wiki.idFromUrl ?: wiki.url})")
                viewModelProvider()?.getOrCreateWebView(wiki, activity)?.let { webView ->
                    Log.d("MEDIA_TRANSITION", "Current WebView: ${webView.hashCode()}")
                    android.util.Log.d("WEBVIEW_LIFECYCLE", "Got WebView: ${webView.hashCode()} for ${wiki.name}")
                    val key = wiki.idFromUrl ?: wiki.url
                    if (!backgroundWebViewManager.hasWebView(key)) {
                        Log.w("MEDIA_TRANSITION", "WebView not registered in background manager, re-registering")
                        Log.w(TAG, "Re-registering WebView for background mode on pause")
                        backgroundModeManager.registerWebViewForBackground(wiki, webView, activity)
                    } else {
                        Log.d("MEDIA_TRANSITION", "WebView already registered in background manager")
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
                        })();                    """.trimIndent(), null)
                }
            }
        }
        
        Log.d("MEDIA_TRANSITION", "Final state after pause:")
        Log.d("MEDIA_TRANSITION", "MediaSession active: ${mediaSessionManager.getMediaSession()?.isActive}")
        Log.d("MEDIA_TRANSITION", "Service bound: ${mediaSessionManager.isServiceBound()}")
        Log.d("MEDIA_TRANSITION", "=== BACKGROUND TRANSITION COMPLETE ===")    }
      fun onResume() {
        android.util.Log.d("MEDIA_TRANSITION", "=== APP FOREGROUNDED === (${System.currentTimeMillis()})")
        android.util.Log.d("MEDIA_TRANSITION", "Background mode enabled: ${backgroundModeManager.isBackgroundEnabled.value}")
        android.util.Log.d("MEDIA_TRANSITION", "MediaSessionManager service bound: ${mediaSessionManager.isServiceBound()}")
        android.util.Log.d("MEDIA_TRANSITION", "MediaSession active: ${mediaSessionManager.getMediaSession()?.isActive}")
        android.util.Log.d("MEDIA_TRANSITION", "Current media state: ${mediaSessionManager.getCurrentMediaState()}")
        
        webViewPaused = false
        
        Log.d("MEDIA_TRANSITION", "=== APP FOREGROUNDED ===")
        Log.d("MEDIA_TRANSITION", "Background mode enabled: ${backgroundModeManager.isBackgroundEnabled.value}")
        Log.d("MEDIA_TRANSITION", "MediaSessionManager service bound: ${mediaSessionManager.isServiceBound()}")
        Log.d("MEDIA_TRANSITION", "Current media state: ${mediaSessionManager.getCurrentMediaState()}")
        
        Log.d(TAG, "onResume - Background mode is ${if (backgroundModeManager.isBackgroundEnabled.value) "ENABLED" else "DISABLED"}")
        
        if (webViewPaused) {
            activity.getCurrentWebView()?.onResume()
            activity.getCurrentWebView()?.resumeTimers()
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
                    android.util.Log.w("WEBVIEW_LIFECYCLE", "WebView not found in BackgroundWebViewManager on resume - creating new one")
                    Log.w(TAG, "WebView not found in BackgroundWebViewManager on resume")
                    webView = viewModelProvider()?.getOrCreateWebView(currentWiki, activity)
                    
                    if (webView != null && !backgroundWebViewManager.hasWebView(key)) {
                        android.util.Log.d("WEBVIEW_LIFECYCLE", "Re-registering NEW WebView for background mode: ${webView.hashCode()}")
                        Log.d(TAG, "Re-registering WebView for background mode on resume")
                        backgroundModeManager.registerWebViewForBackground(currentWiki, webView, activity)
                    }
                } else {
                    android.util.Log.d("WEBVIEW_LIFECYCLE", "Using existing WebView from BackgroundWebViewManager: ${webView.hashCode()}")
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
        }        // Update media session - but only ensure connection, don't disrupt working state
        try {
            Log.d("MEDIA_TRANSITION", "Checking media session state before potential changes...")
            Log.d("MEDIA_TRANSITION", "MediaSession active: ${mediaSessionManager.getMediaSession()?.isActive}")
            Log.d("MEDIA_TRANSITION", "Service bound: ${mediaSessionManager.isServiceBound()}")
            
            activity.getCurrentWebView()?.let { webView ->
                Log.d("MEDIA_TRANSITION", "Current WebView: ${webView.hashCode()}")
                if (!isWebViewDestroyed(webView)) {
                    // Only bind to service if not already bound - don't disrupt working state
                    if (!mediaSessionManager.isServiceBound()) {
                        Log.d("MEDIA_TRANSITION", "Service not bound, attempting to rebind...")
                        mediaSessionManager.bindToService()
                        Log.d(TAG, "Re-bound to media service on resume")
                    } else {
                        Log.d("MEDIA_TRANSITION", "Service already bound, leaving state intact")
                        Log.d(TAG, "Media service already bound, leaving state intact")
                    }
                }
            }
            
            Log.d("MEDIA_TRANSITION", "Final media session state after resume:")
            Log.d("MEDIA_TRANSITION", "MediaSession active: ${mediaSessionManager.getMediaSession()?.isActive}")
            Log.d("MEDIA_TRANSITION", "Service bound: ${mediaSessionManager.isServiceBound()}")
            Log.d("MEDIA_TRANSITION", "Current media state: ${mediaSessionManager.getCurrentMediaState()}")
            Log.d("MEDIA_TRANSITION", "=== FOREGROUND TRANSITION COMPLETE ===")
        } catch (e: Exception) {
            Log.e("MEDIA_TRANSITION", "Error during media session update: ${e.message}", e)
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