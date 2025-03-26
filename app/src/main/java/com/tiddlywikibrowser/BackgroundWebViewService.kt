package com.tiddlywikibrowser

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that maintains WebView instances in the background
 * This allows continued execution of JavaScript and processing even when
 * the app is backgrounded
 */
class BackgroundWebViewService : Service() {
    private val TAG = "BackgroundWebViewService"
    private val NOTIFICATION_ID = 1001
    private val NOTIFICATION_CHANNEL_ID = "webview_background"
    private val WAKELOCK_TAG = "com.tiddlywikibrowser:background"
    
    // Map to store active WebViews by their key
    private val activeWebViews = ConcurrentHashMap<String, WebView>()
    private val isServiceRunning = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Binder for activity to communicate with service
    inner class LocalBinder : Binder() {
        fun getService(): BackgroundWebViewService = this@BackgroundWebViewService
    }
    
    private val binder = LocalBinder()
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        Log.d(TAG, "BackgroundWebViewService created")
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isServiceRunning.get()) {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            isServiceRunning.set(true)
            Log.d(TAG, "BackgroundWebViewService started as foreground service")
            
            // Start periodic health check
            startHealthCheck()
        }
        
        // Handle actions from the intent
        intent?.action?.let { action ->
            when (action) {
                ACTION_REGISTER_WEBVIEW -> {
                    val key = intent.getStringExtra(EXTRA_WEBVIEW_KEY) ?: return@let
                    Log.d(TAG, "Received register WebView intent for key: $key")
                }
                ACTION_UNREGISTER_WEBVIEW -> {
                    val key = intent.getStringExtra(EXTRA_WEBVIEW_KEY) ?: return@let
                    unregisterWebView(key)
                }
                ACTION_STOP_SERVICE -> {
                    stopForeground(true)
                    stopSelf()
                    isServiceRunning.set(false)
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
        isServiceRunning.set(false)
        releaseWakeLock()
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
        
        // Make sure it's resumed and properly configured
        ThreadManager.runOnMain {
            try {
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                }
                webView.onResume()
                
                // Inject periodic health check script
                webView.evaluateJavascript("""
                    (function() {
                        if (!window.__healthCheckInitialized) {
                            // Set up health check ping
                            window.__lastHealthCheck = Date.now();
                            window.__healthCheckInterval = setInterval(function() {
                                window.__lastHealthCheck = Date.now();
                                console.log('[Background] Health check ping');
                            }, 30000);
                            window.__healthCheckInitialized = true;
                        }
                        return true;
                    })();
                """.trimIndent(), null)
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring WebView: ${e.message}")
            }
        }
        
        // Update notification to show active WebView count
        updateNotification()
        
        Log.d(TAG, "WebView registered with key: $key, total active: ${activeWebViews.size}")
    }
    
    /**
     * Unregister a WebView from background processing
     */
    fun unregisterWebView(key: String) {
        activeWebViews.remove(key)?.let { webView ->
            ThreadManager.runOnMain {
                try {
                    // Clean up health check
                    webView.evaluateJavascript("""
                        (function() {
                            if (window.__healthCheckInterval) {
                                clearInterval(window.__healthCheckInterval);
                                delete window.__healthCheckInterval;
                                delete window.__lastHealthCheck;
                                delete window.__healthCheckInitialized;
                            }
                            return true;
                        })();
                    """.trimIndent(), null)
                    
                    // Save state and pause the WebView
                    WebViewCache.cacheWebView(key, webView)
                    webView.onPause()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up WebView: ${e.message}")
                }
            }
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
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TiddlyWiki Browser")
            .setContentText("Running in background (${activeWebViews.size} wikis)")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }
    
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(10*60*1000L /*10 minutes*/)
            }
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
            wakeLock = null
        }
    }
    
    private fun startHealthCheck() {
        ThreadManager.runOnBackgroundWithDelay(30000) { // Check every 30 seconds
            if (isServiceRunning.get()) {
                checkWebViewHealth()
                startHealthCheck() // Schedule next check
            }
        }
    }
    
    private fun checkWebViewHealth() {
        val currentTime = System.currentTimeMillis()
        activeWebViews.forEach { (key, webView) ->
            ThreadManager.runOnMain {
                try {
                    webView.evaluateJavascript("""
                        (function() {
                            return window.__lastHealthCheck || 0;
                        })();
                    """.trimIndent()) { result ->
                        try {
                            val lastCheck = result.toLongOrNull() ?: 0
                            if (currentTime - lastCheck > 60000) { // No health check for 1 minute
                                Log.w(TAG, "WebView $key appears unresponsive, attempting recovery")
                                // Try to recover the WebView
                                recoverWebView(key, webView)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking WebView health: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during health check: ${e.message}")
                }
            }
        }
    }
    
    private fun recoverWebView(key: String, webView: WebView) {
        ThreadManager.runOnMain {
            try {
                // Re-inject health check script
                webView.evaluateJavascript("""
                    (function() {
                        if (window.__healthCheckInterval) {
                            clearInterval(window.__healthCheckInterval);
                        }
                        window.__lastHealthCheck = Date.now();
                        window.__healthCheckInterval = setInterval(function() {
                            window.__lastHealthCheck = Date.now();
                            console.log('[Background] Health check ping');
                        }, 30000);
                        window.__healthCheckInitialized = true;
                        return true;
                    })();
                """.trimIndent(), null)
                
                // Ensure WebView is resumed
                webView.onResume()
                
                Log.d(TAG, "Successfully recovered WebView: $key")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover WebView: ${e.message}")
                // If recovery fails, unregister and re-register
                unregisterWebView(key)
                registerWebView(key, webView)
            }
        }
    }
    
    companion object {
        const val ACTION_REGISTER_WEBVIEW = "com.tiddlywikibrowser.action.REGISTER_WEBVIEW"
        const val ACTION_UNREGISTER_WEBVIEW = "com.tiddlywikibrowser.action.UNREGISTER_WEBVIEW"
        const val ACTION_STOP_SERVICE = "com.tiddlywikibrowser.action.STOP_SERVICE"
        const val EXTRA_WEBVIEW_KEY = "webview_key"
    }
} 