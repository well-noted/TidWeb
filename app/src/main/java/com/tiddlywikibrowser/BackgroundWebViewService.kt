package com.tiddlywikibrowser

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that maintains WebView instances in the background
 * This allows continued execution of JavaScript and processing even when
 * the app is backgrounded
 */
class BackgroundWebViewService : Service() {
    private val TAG = "BackgroundWebViewService"
    private val NOTIFICATION_ID = 1001
    private val NOTIFICATION_CHANNEL_ID = "webview_background"
    
    // Map to store active WebViews by their key
    private val activeWebViews = ConcurrentHashMap<String, WebView>()
    private var isServiceRunning = false
    
    // Binder for activity to communicate with service
    inner class LocalBinder : Binder() {
        fun getService(): BackgroundWebViewService = this@BackgroundWebViewService
    }
    
    private val binder = LocalBinder()
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "BackgroundWebViewService created")
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isServiceRunning) {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            isServiceRunning = true
            Log.d(TAG, "BackgroundWebViewService started as foreground service")
        }
        
        // Handle actions from the intent
        intent?.action?.let { action ->
            when (action) {
                ACTION_REGISTER_WEBVIEW -> {
                    val key = intent.getStringExtra(EXTRA_WEBVIEW_KEY) ?: return@let
                    // WebViews can't be passed through intents
                    // They will be registered via direct calls from bound activities
                    Log.d(TAG, "Received register WebView intent for key: $key")
                }
                ACTION_UNREGISTER_WEBVIEW -> {
                    val key = intent.getStringExtra(EXTRA_WEBVIEW_KEY) ?: return@let
                    unregisterWebView(key)
                }
                ACTION_STOP_SERVICE -> {
                    stopForeground(true)
                    stopSelf()
                    isServiceRunning = false
                }
            }
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up all WebViews
        for (key in activeWebViews.keys()) {
            unregisterWebView(key)
        }
        activeWebViews.clear()
        isServiceRunning = false
        Log.d(TAG, "BackgroundWebViewService destroyed")
    }
    
    /**
     * Register a WebView to be kept alive in the background
     */
    fun registerWebView(key: String, webView: WebView) {
        // If WebView is already registered, remove the old one
        unregisterWebView(key)
        
        // Store the WebView in our map
        activeWebViews[key] = webView
        
        // Make sure it's resumed
        webView.onResume()
        
        // Update notification to show active WebView count
        updateNotification()
        
        Log.d(TAG, "WebView registered with key: $key, total active: ${activeWebViews.size}")
    }
    
    /**
     * Unregister a WebView from background processing
     */
    fun unregisterWebView(key: String) {
        activeWebViews.remove(key)?.let { webView ->
            // Save state and pause the WebView
            WebViewCache.cacheWebView(key, webView)
            webView.onPause()
            Log.d(TAG, "WebView unregistered with key: $key")
        }
        
        // Update notification
        updateNotification()
    }
    
    /**
     * Get a registered WebView by key
     */
    fun getWebView(key: String): WebView? {
        return activeWebViews[key]
    }
    
    /**
     * Check if the service has a registered WebView for the given key
     */
    fun hasWebView(key: String): Boolean {
        return activeWebViews.containsKey(key)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Background Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps TiddlyWiki content running in background"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TiddlyWiki Browser")
            .setContentText("Running in background (${activeWebViews.size} wikis active)")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    companion object {
        const val ACTION_REGISTER_WEBVIEW = "com.tiddlywikibrowser.ACTION_REGISTER_WEBVIEW"
        const val ACTION_UNREGISTER_WEBVIEW = "com.tiddlywikibrowser.ACTION_UNREGISTER_WEBVIEW"
        const val ACTION_STOP_SERVICE = "com.tiddlywikibrowser.ACTION_STOP_SERVICE"
        const val EXTRA_WEBVIEW_KEY = "webview_key"
    }
} 