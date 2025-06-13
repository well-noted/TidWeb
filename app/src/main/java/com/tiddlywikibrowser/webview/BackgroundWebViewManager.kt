package com.tiddlywikibrowser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manager class that handles interaction with the BackgroundWebViewService
 */
class BackgroundWebViewManager(private val context: Context) {
    private val TAG = "BackgroundWebViewManager"
    
    // Service connection
    var service: BackgroundWebViewService? = null
        private set
    private var isBound = false
    
    // State flow to track if background processing is enabled
    private val _isBackgroundEnabled = MutableStateFlow(false)
    val isBackgroundEnabled: StateFlow<Boolean> = _isBackgroundEnabled
    
    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BackgroundWebViewService.LocalBinder
            this@BackgroundWebViewManager.service = binder.getService()
            isBound = true
            Log.d(TAG, "Connected to BackgroundWebViewService")
            
            // Update state
            _isBackgroundEnabled.value = true
            
            // Re-register any pending WebViews
            refreshCurrentWebView()
        }
        
        override fun onServiceDisconnected(className: ComponentName) {
            this@BackgroundWebViewManager.service = null
            isBound = false
            _isBackgroundEnabled.value = false
            Log.d(TAG, "Disconnected from BackgroundWebViewService")
        }
    }
    
    /**
     * Start and bind to the background service
     */
    fun startBackgroundService() {
        val serviceIntent = Intent(context, BackgroundWebViewService::class.java)
        
        // Start the service as a foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        // Bind to the service
        context.bindService(
            serviceIntent,
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        
        Log.d(TAG, "Starting and binding to BackgroundWebViewService")
    }
    
    /**
     * Stop the background service
     */
    fun stopBackgroundService() {
        // Unbind first
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
                isBound = false
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding from service", e)
            }
        }
        
        // Stop the service
        val serviceIntent = Intent(context, BackgroundWebViewService::class.java).apply {
            action = BackgroundWebViewService.ACTION_STOP_SERVICE
        }
        context.startService(serviceIntent)
        
        // Update state
        _isBackgroundEnabled.value = false
        Log.d(TAG, "Stopping BackgroundWebViewService")
    }
      /**
     * Register a WebView to be kept running in the background
     */
    fun registerWebView(key: String, webView: WebView) {
        if (!isBound || service == null) {
            Log.d(TAG, "Service not bound, cannot register WebView")
            return
        }
        
        // Protect this WebView from cache trimming while in background mode
        com.tiddlywikibrowser.cache.WebViewCache.protectBackgroundWebView(key)
        
        service?.registerWebView(key, webView)
    }
      /**
     * Unregister a WebView from background processing
     */
    fun unregisterWebView(key: String) {
        if (!isBound || service == null) {
            Log.d(TAG, "Service not bound, cannot unregister WebView")
            return
        }
        
        // Unprotect this WebView from cache trimming as it's no longer in background
        com.tiddlywikibrowser.cache.WebViewCache.unprotectBackgroundWebView(key)
        
        service?.unregisterWebView(key)
    }
    
    /**
     * Get a WebView that's being kept alive in the background
     */
    fun getWebView(key: String): WebView? {
        return service?.getWebView(key)
    }
    
    /**
     * Check if a WebView is registered with the background service
     */
    fun hasWebView(key: String): Boolean {
        return service?.hasWebView(key) ?: false
    }
    
    /**
     * Force resume videos that should be playing in background
     * Call this when the app goes to background to ensure
     * videos continue playing
     */
    fun forceResumeVideos() {
        // Try direct service call first if bound
        if (isBound && service != null) {
            Log.d(TAG, "Requesting service to force resume videos via bound service")
            service?.forceResumeVideos()
        } else {
            Log.d(TAG, "Service not bound, sending intent to force resume videos")
        }
        
        // Always send the intent as well for redundancy
        try {
            val serviceIntent = Intent(context, BackgroundWebViewService::class.java).apply {
                action = BackgroundWebViewService.ACTION_FORCE_RESUME_VIDEOS
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Sent force resume videos intent to service")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending force resume intent: ${e.message}")
        }
    }
    
    /**
     * Refresh the current WebView in the background service
     * This is called when the app is resumed to ensure the service
     * has the most recent WebView instance
     */
    fun refreshCurrentWebView() {
        val mainActivity = context as? MainActivity
        val viewModel = mainActivity?.viewModel ?: return
        val currentWiki = viewModel.currentWiki.value
        
        if (currentWiki != null && isBound && service != null) {
            val key = currentWiki.idFromUrl ?: currentWiki.url
            val webView = viewModel.getOrCreateWebView(currentWiki, context)
            
            if (webView != null) {
                registerWebView(key, webView)
                Log.d(TAG, "Refreshed current WebView in background service: $key")
            }
        }
    }
    
    /**
     * Clean up resources when the manager is no longer needed
     */
    fun release() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
                isBound = false
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding from service", e)
            }
        }
    }
} 