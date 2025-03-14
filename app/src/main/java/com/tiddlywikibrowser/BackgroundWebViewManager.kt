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
    private var backgroundService: BackgroundWebViewService? = null
    private var isBound = false
    
    // State flow to track if background processing is enabled
    private val _isBackgroundEnabled = MutableStateFlow(false)
    val isBackgroundEnabled: StateFlow<Boolean> = _isBackgroundEnabled
    
    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BackgroundWebViewService.LocalBinder
            backgroundService = binder.getService()
            isBound = true
            Log.d(TAG, "Connected to BackgroundWebViewService")
            
            // Update state
            _isBackgroundEnabled.value = true
            
            // Re-register any pending WebViews
            refreshCurrentWebView()
        }
        
        override fun onServiceDisconnected(className: ComponentName) {
            backgroundService = null
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
        if (!isBound || backgroundService == null) {
            Log.d(TAG, "Service not bound, cannot register WebView")
            return
        }
        
        backgroundService?.registerWebView(key, webView)
    }
    
    /**
     * Unregister a WebView from background processing
     */
    fun unregisterWebView(key: String) {
        if (!isBound || backgroundService == null) {
            Log.d(TAG, "Service not bound, cannot unregister WebView")
            return
        }
        
        backgroundService?.unregisterWebView(key)
    }
    
    /**
     * Get a WebView that's being kept alive in the background
     */
    fun getWebView(key: String): WebView? {
        return backgroundService?.getWebView(key)
    }
    
    /**
     * Check if a WebView is registered with the background service
     */
    fun hasWebView(key: String): Boolean {
        return backgroundService?.hasWebView(key) ?: false
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
        
        if (currentWiki != null && isBound && backgroundService != null) {
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