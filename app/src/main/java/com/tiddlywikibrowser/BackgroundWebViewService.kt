package com.tiddlywikibrowser

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
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
    private var videoWakeLock: PowerManager.WakeLock? = null
    
    // Keep track of whether we're playing video
    private var hasActiveVideo = false
    
    private var screenStateReceiver: BroadcastReceiver? = null
    
    private var silentAudioTrack: AudioTrack? = null
    private var batteryOptimizationRequested = false
    
    // Binder for activity to communicate with service
    inner class LocalBinder : Binder() {
        fun getService(): BackgroundWebViewService = this@BackgroundWebViewService
    }
    
    private val binder = LocalBinder()
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        acquireVideoWakeLock() // Always acquire video wake lock at start
        
        // Request ignore battery optimization
        requestIgnoreBatteryOptimization()
        
        // Configure WebView for background media
        WebView.setWebContentsDebuggingEnabled(true)
        
        // Register for lifecycle changes using broadcast receivers
        try {
            registerScreenStateReceiver()
            
            Log.d(TAG, "BackgroundWebViewService will monitor video state periodically")
            // Start a more frequent check for video playback status
            ThreadManager.runOnBackgroundWithDelay(1000) {
                if (isServiceRunning.get()) {
                    forceResumeVideos() // Do an initial force-resume
                    startVideoCheckLoop() // Start periodic checks
                    startSilentAudio() // Start silent audio to keep CPU active
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up video monitoring: ${e.message}")
        }
        
        Log.d(TAG, "BackgroundWebViewService created")
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isServiceRunning.get()) {
            val notification = createNotification()
            
            // Start as a foreground service with the appropriate type
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            
            isServiceRunning.set(true)
            Log.d(TAG, "BackgroundWebViewService started as foreground service")
            
            // Start periodic health check
            startHealthCheck()
            
            // Ensure videos are playing if this was triggered by app going to background
            if (intent?.action == ACTION_APP_BACKGROUND) {
                Log.d(TAG, "App went to background, ensuring videos continue playing")
                forceResumeVideos()
            }
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
                ACTION_FORCE_RESUME_VIDEOS -> {
                    Log.d(TAG, "Received explicit request to force resume videos")
                    forceResumeVideos()
                }
                ACTION_APP_BACKGROUND -> {
                    Log.d(TAG, "App went to background, ensuring videos continue playing")
                    forceResumeVideos()
                }
            }
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Unregister receivers
        unregisterScreenStateReceiver()
        
        // Stop silent audio
        stopSilentAudio()
        
        // Clean up all WebViews
        for (key in activeWebViews.keys()) {
            unregisterWebView(key)
        }
        activeWebViews.clear()
        isServiceRunning.set(false)
        releaseWakeLock()
        releaseVideoWakeLock()
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
                // These settings are crucial for video playback
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    
                    // Critical setting for background video playback
                    if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    
                    // More explicit setting to ensure background playback works
                    try {
                        javaClass.getMethod("setMediaPlaybackRequiresUserGesture", Boolean::class.java)
                            .invoke(this, false)
                    } catch (e: Exception) {
                        // Method not available, ignore
                    }
                    
                    // Set data saver off to prevent throttling
                    if (VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
                        try {
                            javaClass.getMethod("setDataSaverEnabled", Boolean::class.java)
                                .invoke(this, false)
                        } catch (e: Exception) {
                            // Method not available, ignore
                        }
                    }
                    
                    // Set process priority
                    try {
                        javaClass.getMethod("setProcessPriority", Int::class.java)
                            .invoke(this, 1) // HIGH
                    } catch (e: Exception) {
                        // Method not available, ignore
                    }
                }
                
                // Force hardware acceleration - critical for video
                webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                
                // Keep WebView from pausing when not visible
                webView.setWillNotDraw(false)
                
                // Ensure WebView is resumed
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
                
                // Enable video background playback
                enableBackgroundVideoPlayback(webView)
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring WebView: ${e.message}")
            }
        }
        
        // Update notification to show active WebView count
        updateNotification()
        
        Log.d(TAG, "WebView registered with key: $key, total active: ${activeWebViews.size}")
    }
    
    /**
     * Simpler and more direct approach to enable background video playback
     */
    private fun enableBackgroundVideoPlayback(webView: WebView) {
        // First inject a script to prevent automatic pausing
        webView.evaluateJavascript("""
            (function() {
                // First ensure we don't re-initialize if already set up
                if (window.__backgroundVideoHackApplied) return true;
                
                console.log("[BackgroundVideo] Applying critical background video hack");
                
                // HACK: Override the HTMLMediaElement.pause method to prevent auto-pausing
                const originalPause = HTMLMediaElement.prototype.pause;
                HTMLMediaElement.prototype.pause = function() {
                    // If this is a system initiated pause (page hidden), prevent it
                    if (document.visibilityState === 'hidden' && !this.ended && !this.userPaused) {
                        console.log('[BackgroundVideo] Prevented pause in background');
                        return undefined; // Must return undefined to prevent error
                    }
                    
                    // Otherwise, allow the pause
                    return originalPause.apply(this, arguments);
                };
                
                // Track play events to know which videos should be playing
                const originalPlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function() {
                    // Mark that user wants this video to play
                    this.userPaused = false;
                    this.__shouldBePlaying = true;
                    
                    console.log('[BackgroundVideo] Video play requested');
                    
                    // Return the original play
                    return originalPlay.apply(this, arguments);
                };
                
                // Monitor visibility changes directly
                document.addEventListener('visibilitychange', function() {
                    if (document.visibilityState === 'visible') {
                        console.log('[BackgroundVideo] Page became visible, checking videos');
                        document.querySelectorAll('video').forEach(function(video) {
                            // If video should be playing but got paused by the system
                            if (video.__shouldBePlaying && video.paused && !video.ended && !video.userPaused) {
                                console.log('[BackgroundVideo] Restarting video after visibility change');
                                // Try to resume with a slight delay to let the system stabilize
                                setTimeout(function() {
                                    try {
                                        video.play().catch(function(e) {
                                            console.log('[BackgroundVideo] Error restarting: ' + e);
                                        });
                                    } catch(e) {
                                        console.log('[BackgroundVideo] Exception restarting: ' + e);
                                    }
                                }, 50);
                            }
                        });
                    } else {
                        console.log('[BackgroundVideo] Page hidden, flagging playing videos');
                        // Mark currently playing videos when page hides
                        document.querySelectorAll('video').forEach(function(video) {
                            if (!video.paused && !video.ended) {
                                video.__shouldBePlaying = true;
                                console.log('[BackgroundVideo] Video should keep playing in background');
                            }
                        });
                    }
                }, false);
                
                // Set up a more aggressive monitoring system with multiple strategies
                setInterval(function() {
                    if (document.visibilityState === 'hidden') {
                        document.querySelectorAll('video').forEach(function(video) {
                            // Check if video should be playing but got paused by the system
                            if (video.__shouldBePlaying && 
                                video.paused && !video.ended && !video.userPaused) {
                                console.log('[BackgroundVideo] Restarting background video (interval check)');
                                try {
                                    const playPromise = video.play();
                                    if (playPromise !== undefined) {
                                        playPromise.catch(function(e) {
                                            console.log('[BackgroundVideo] Error restarting: ' + e);
                                            // If autoplay was prevented, try again with muted
                                            if (e.name === 'NotAllowedError') {
                                                console.log('[BackgroundVideo] Retry with muted');
                                                video.muted = true;
                                                video.play().catch(function(e2) {
                                                    console.log('[BackgroundVideo] Even muted failed: ' + e2);
                                                });
                                            }
                                        });
                                    }
                                } catch(e) {
                                    console.log('[BackgroundVideo] Exception during play: ' + e);
                                }
                            }
                        });
                    }
                }, 500);  // Check more frequently
                
                // Listen for native pause clicks
                document.addEventListener('click', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        // If user clicked on video while playing, they might want to pause
                        if (!e.target.paused) {
                            console.log('[BackgroundVideo] User may be pausing video');
                            // Mark as user-paused after a short delay
                            setTimeout(function() {
                                if (e.target.paused) {
                                    e.target.userPaused = true;
                                    e.target.__shouldBePlaying = false;
                                    console.log('[BackgroundVideo] Confirmed user pause');
                                }
                            }, 100);
                        } else {
                            // User might be restarting
                            console.log('[BackgroundVideo] User may be unpausing video');
                            setTimeout(function() {
                                if (!e.target.paused) {
                                    e.target.userPaused = false;
                                    e.target.__shouldBePlaying = true;
                                    console.log('[BackgroundVideo] Confirmed user unpaused');
                                }
                            }, 100);
                        }
                    }
                }, true);
                
                // Detect when videos naturally end
                document.addEventListener('ended', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        e.target.__shouldBePlaying = false;
                        console.log('[BackgroundVideo] Video ended naturally');
                    }
                }, true);
                
                // Mark as applied to prevent multiple initialization
                window.__backgroundVideoHackApplied = true;
                
                return true;
            })();
        """.trimIndent(), null)
        
        // Continue with regular setup
        webView.evaluateJavascript("""
            (function() {
                // Skip if already initialized
                if (window.__backgroundVideoFullSetup) return true;
                
                console.log("[BackgroundVideo] Setting up full background video support");
                
                // Force all videos to have required attributes
                function setupVideo(video) {
                    if (video.__setupComplete) return;
                    
                    // Set critical attributes
                    video.setAttribute('playsinline', 'true');
                    video.setAttribute('webkit-playsinline', 'true');
                    video.setAttribute('x-webkit-airplay', 'allow');
                    
                    // Hardware acceleration
                    video.style.transform = 'translateZ(0)';
                    
                    // Special attributes for fullscreen and background support
                    video.setAttribute('data-background-enabled', 'true');
                    
                    // Add stronger CSS to enforce hardware acceleration and prevent hiding
                    const videoElementId = 'video-' + Math.floor(Math.random() * 100000);
                    video.id = videoElementId;
                    
                    // Create a style tag to add critical CSS
                    const style = document.createElement('style');
                    style.innerHTML = '#' + videoElementId + ' { will-change: transform; transform: translateZ(0); backface-visibility: hidden; }';
                    document.head.appendChild(style);
                    
                    // Mark as set up
                    video.__setupComplete = true;
                    video.__shouldBePlaying = !video.paused && !video.ended;
                    
                    // Notify Android when video starts playing
                    video.addEventListener('play', function() {
                        video.__shouldBePlaying = true;
                        
                        if (window.MediaInterface) {
                            try {
                                window.MediaInterface.onMediaStateChange(
                                    video.getAttribute('title') || 'TiddlyWiki Video',
                                    'TiddlyWiki',
                                    Math.floor(video.duration * 1000 || 0),
                                    Math.floor(video.currentTime * 1000 || 0),
                                    true
                                );
                            } catch(e) {
                                console.error('[BackgroundVideo] MediaInterface error:', e);
                            }
                        }
                    });
                    
                    // Notify when video is done
                    video.addEventListener('ended', function() {
                        video.__shouldBePlaying = false;
                        
                        // Only notify if no other videos are playing
                        let otherPlaying = false;
                        document.querySelectorAll('video').forEach(function(v) {
                            if (v !== video && !v.paused && !v.ended) otherPlaying = true;
                        });
                        
                        if (!otherPlaying && window.MediaInterface) {
                            try {
                                window.MediaInterface.onMediaStateChange(
                                    'TiddlyWiki Video',
                                    'TiddlyWiki',
                                    0,
                                    0,
                                    false
                                );
                            } catch(e) {
                                console.error('[BackgroundVideo] MediaInterface error:', e);
                            }
                        }
                    });
                }
                
                // Set up all existing videos
                document.querySelectorAll('video').forEach(setupVideo);
                
                // Watch for new videos
                new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        mutation.addedNodes.forEach(function(node) {
                            if (node.tagName === 'VIDEO') {
                                setupVideo(node);
                            } else if (node.querySelectorAll) {
                                node.querySelectorAll('video').forEach(setupVideo);
                            }
                        });
                    });
                }).observe(document, {childList: true, subtree: true});
                
                // Periodic check for video playing status
                setInterval(function() {
                    let hasPlayingVideo = false;
                    document.querySelectorAll('video').forEach(function(video) {
                        // Apply setup to any new videos
                        if (!video.__setupComplete) {
                            setupVideo(video);
                        }
                        
                        if (!video.paused && !video.ended) {
                            hasPlayingVideo = true;
                        }
                    });
                    
                    // Update Android about playback state
                    if (hasPlayingVideo && window.MediaInterface) {
                        const playingVideo = Array.from(document.querySelectorAll('video'))
                            .find(v => !v.paused && !v.ended);
                            
                        if (playingVideo) {
                            try {
                                window.MediaInterface.onMediaStateChange(
                                    playingVideo.getAttribute('title') || 'TiddlyWiki Video',
                                    'TiddlyWiki',
                                    Math.floor(playingVideo.duration * 1000 || 0),
                                    Math.floor(playingVideo.currentTime * 1000 || 0),
                                    true
                                );
                            } catch(e) {
                                console.error('[BackgroundVideo] MediaInterface error:', e);
                            }
                        }
                    }
                }, 2000);
                
                // Add a helper function to force resume all videos that should be playing
                window.forceResumeBackgroundVideos = function() {
                    document.querySelectorAll('video').forEach(function(video) {
                        if (video.__shouldBePlaying && video.paused && !video.ended) {
                            console.log('[BackgroundVideo] Forcing resume of video');
                            try {
                                video.play().catch(e => console.log('[BackgroundVideo] Resume error:', e));
                            } catch(e) {
                                console.log('[BackgroundVideo] Resume exception:', e);
                            }
                        }
                    });
                    return true;
                };
                
                // Mark as fully set up
                window.__backgroundVideoFullSetup = true;
                
                return true;
            })();
        """.trimIndent()) { result ->
            // If setup was successful
            if (result.contains("true")) {
                hasActiveVideo = true
                acquireVideoWakeLock()
                updateNotification()
            }
        }
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
                    
                    // Only pause the WebView but don't destroy it
                    // This is critical - we're intentionally NOT destroying the WebView
                    // just caching its state so it can be resumed later
                    WebViewCache.cacheWebView(key, webView)
                    
                    // Just pause, don't destroy
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
    
    /**
     * Check if any WebView has active video playing
     */
    fun checkForActiveVideo() {
        if (activeWebViews.isEmpty()) {
            hasActiveVideo = false
            releaseVideoWakeLock()
            return
        }
        
        // A counter to track how many WebViews respond with active video
        val responseCounter = AtomicBoolean(false)
        
        // Check each WebView for active videos
        for ((_, webView) in activeWebViews) {
            ThreadManager.runOnMain {
                try {
                    webView.evaluateJavascript("""
                        (function() {
                            // Check if any videos are currently playing
                            let hasPlayingVideo = false;
                            document.querySelectorAll('video').forEach(function(video) {
                                if (!video.paused && !video.ended) {
                                    hasPlayingVideo = true;
                                }
                            });
                            return hasPlayingVideo;
                        })();
                    """.trimIndent()) { result ->
                        try {
                            if (result.contains("true") && !responseCounter.get()) {
                                responseCounter.set(true)
                                hasActiveVideo = true
                                acquireVideoWakeLock()
                                updateNotification() // Update notification to show video is playing
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing video check result: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking for videos: ${e.message}")
                }
            }
        }
        
        // If we didn't find any videos, update state after a delay
        ThreadManager.runOnBackgroundWithDelay(500) {
            if (!responseCounter.get()) {
                hasActiveVideo = false
                releaseVideoWakeLock()
                updateNotification()
            }
        }
    }
    
    /**
     * Force resume videos that should be playing. 
     * Call this when app goes to background to ensure videos keep playing
     */
    fun forceResumeVideos() {
        if (activeWebViews.isEmpty()) {
            return
        }
        
        Log.d(TAG, "Forcing resume of any videos that should be playing")
        
        // Ensure we have the necessary wake locks
        acquireWakeLock()
        acquireVideoWakeLock()
        
        // Ensure silent audio is playing
        if (silentAudioTrack == null || silentAudioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            startSilentAudio()
        }
        
        // Check each WebView and force resume any videos
        for ((_, webView) in activeWebViews) {
            ThreadManager.runOnMain {
                try {
                    // Ensure the WebView is completely resumed
                    webView.onResume()
                    
                    // Try a more aggressive approach with direct WebView configuration
                    try {
                        webView.settings.javaClass.getDeclaredMethod("setMediaPlaybackRequiresUserGesture", Boolean::class.java)
                            .invoke(webView.settings, false)
                    } catch (e: Exception) {
                        // Ignore if method not available
                    }
                    
                    // Call the helper function we added in the JavaScript
                    webView.evaluateJavascript("window.forceResumeBackgroundVideos ? window.forceResumeBackgroundVideos() : false;", { result ->
                        if (result.contains("true")) {
                            Log.d(TAG, "Successfully forced video resumption")
                            hasActiveVideo = true
                            acquireVideoWakeLock()
                            updateNotification()
                        }
                    })
                    
                    // Also try to directly play any videos marked as should be playing
                    webView.evaluateJavascript("""
                        (function() {
                            let resumed = false;
                            document.querySelectorAll('video').forEach(function(video) {
                                // Extra aggressive forcing
                                if (video.paused) {
                                    // First mark that it should be playing
                                    video.__shouldBePlaying = true;
                                    video.userPaused = false;
                                    
                                    console.log('[BackgroundService] Force resuming video');
                                    try {
                                        // Add temporary event listeners to catch any errors
                                        let errorHandler = function(e) {
                                            console.log('[BackgroundService] Video error during force resume:', e);
                                            // Try once more with muted if it's an autoplay error
                                            if (e.name === 'NotAllowedError') {
                                                video.muted = true;
                                                video.play().catch(e2 => console.log('[BackgroundService] Even muted failed:', e2));
                                            }
                                            video.removeEventListener('error', errorHandler);
                                        };
                                        video.addEventListener('error', errorHandler, {once: true});
                                        
                                        // Force video to play
                                        video.loop = true; // Add loop to prevent ending
                                        video.controls = true; // Show controls for user interaction
                                        video.currentTime = video.currentTime; // Force time update
                                        
                                        // Play with high priority
                                        const playPromise = video.play();
                                        if (playPromise !== undefined) {
                                            playPromise.then(() => {
                                                console.log('[BackgroundService] Successfully resumed video');
                                                resumed = true;
                                                
                                                // Dispatch events to trigger browser activity
                                                video.dispatchEvent(new Event('timeupdate'));
                                                video.dispatchEvent(new Event('playing'));
                                            }).catch(e => {
                                                console.log('[BackgroundService] Error resuming:', e);
                                                // Try once more with muted if autoplay was prevented
                                                if (e.name === 'NotAllowedError') {
                                                    video.muted = true;
                                                    video.play().catch(e2 => {
                                                        console.log('[BackgroundService] Even muted failed:', e2);
                                                    });
                                                }
                                            });
                                        }
                                    } catch(e) {
                                        console.log('[BackgroundService] Exception during force play:', e);
                                    }
                                } else {
                                    // Already playing, make sure it stays that way
                                    video.__shouldBePlaying = true;
                                    video.userPaused = false;
                                    resumed = true;
                                }
                            });
                            return resumed;
                        })();
                    """.trimIndent()) { result ->
                        if (result.contains("true")) {
                            Log.d(TAG, "Successfully resumed at least one video")
                            hasActiveVideo = true
                            acquireVideoWakeLock()
                            updateNotification()
                        }
                    }
                    
                    // Add a force run to restart any browser internal processes
                    webView.evaluateJavascript("""
                        (function() {
                            // Try to trigger browser activity by forcing layout and style recalculation
                            if (document.body) {
                                document.body.style.zoom = 0.99;
                                setTimeout(function() {
                                    document.body.style.zoom = 1;
                                }, 10);
                            }
                            return true;
                        })();
                    """.trimIndent(), null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error forcing video resumption: ${e.message}")
                }
            }
        }
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
        
        val notificationText = if (hasActiveVideo) {
            "Playing video in background"
        } else {
            "Running in background (${activeWebViews.size} wikis)"
        }
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TiddlyWiki Browser")
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .apply {
                // Add media control if playing video
                if (hasActiveVideo) {
                    setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                    setPriority(NotificationCompat.PRIORITY_DEFAULT)
                }
            }
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
                acquire() // Acquire indefinitely - we'll manage release manually
            }
            Log.d(TAG, "Acquired main wake lock indefinitely")
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Released main wake lock")
            }
            wakeLock = null
        }
    }
    
    private fun acquireVideoWakeLock() {
        if (videoWakeLock == null || !videoWakeLock!!.isHeld) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                videoWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$WAKELOCK_TAG:video"
                )
                videoWakeLock?.apply {
                    setReferenceCounted(false)
                    acquire() // Acquire indefinitely - we'll manage release manually
                }
                Log.d(TAG, "Video wake lock acquired indefinitely")
                
                // Start a more aggressive check when video is playing
                startVideoCheckLoop()
                
                // Start silent audio if we don't have it running
                if (silentAudioTrack == null) {
                    startSilentAudio()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire video wakelock: ${e.message}")
            }
        }
    }
    
    private fun releaseVideoWakeLock() {
        videoWakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                    Log.d(TAG, "Video wake lock released")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing video wake lock: ${e.message}")
                }
            }
        }
        videoWakeLock = null
    }
    
    private fun startVideoCheckLoop() {
        ThreadManager.runOnBackgroundWithDelay(3000) { // Check frequently for video status
            if (isServiceRunning.get() && hasActiveVideo) {
                checkForActiveVideo()
                startVideoCheckLoop() // Continue checking while we have active video
            }
        }
    }
    
    private fun startHealthCheck() {
        ThreadManager.runOnBackgroundWithDelay(30000) { // Check every 30 seconds
            if (isServiceRunning.get()) {
                checkWebViewHealth()
                checkForActiveVideo() // Also check for active video
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
                            
                            // Always check for videos that might need resuming
                            if (hasActiveVideo) {
                                forceResumeVideos()
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
                
                // Reapply video background playback
                enableBackgroundVideoPlayback(webView)
                
                // Ensure WebView is resumed
                webView.onResume()
                
                // Force resume any videos that should be playing
                forceResumeVideos()
                
                Log.d(TAG, "Successfully recovered WebView: $key")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover WebView: ${e.message}")
                // If recovery fails, unregister and re-register
                unregisterWebView(key)
                registerWebView(key, webView)
            }
        }
    }
    
    private fun registerScreenStateReceiver() {
        // Unregister any existing receiver first
        unregisterScreenStateReceiver()
        
        // Create a new receiver to detect screen state changes
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "Screen turned OFF, ensuring video playback can continue")
                        // Force wake lock when screen turns off
                        acquireWakeLock()
                        acquireVideoWakeLock()
                        // Delayed force resume to ensure it happens after the system settles
                        ThreadManager.runOnBackgroundWithDelay(1000) {
                            forceResumeVideos()
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d(TAG, "Screen turned ON, verifying video playback")
                        // Check video state after screen turns on
                        ThreadManager.runOnBackgroundWithDelay(500) {
                            checkForActiveVideo()
                            forceResumeVideos()
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        Log.d(TAG, "User present, verifying video playback")
                        // User unlocked the device, check video state
                        ThreadManager.runOnBackgroundWithDelay(500) {
                            checkForActiveVideo()
                        }
                    }
                }
            }
        }
        
        // Register the receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        
        registerReceiver(screenStateReceiver, filter)
        Log.d(TAG, "Registered screen state receiver")
    }
    
    private fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Unregistered screen state receiver")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen state receiver: ${e.message}")
            }
        }
        screenStateReceiver = null
    }
    
    /**
     * Request that the app be whitelisted from battery optimizations
     * This helps keep background processes running at full speed
     */
    private fun requestIgnoreBatteryOptimization() {
        if (batteryOptimizationRequested) return
        
        try {
            if (VERSION.SDK_INT >= VERSION_CODES.M) {
                val packageName = packageName
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    // We can't directly request this from a service, but we can show a notification
                    // that the user can click to go to the battery optimization settings
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    
                    val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setContentTitle("Improve Background Playback")
                        .setContentText("Tap to disable battery optimization for smoother video playback")
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .build()
                    
                    notificationManager.notify(1002, notification)
                    Log.d(TAG, "Displayed notification to request ignoring battery optimization")
                } else {
                    Log.d(TAG, "App is already ignoring battery optimizations")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request ignoring battery optimization: ${e.message}")
        }
        
        batteryOptimizationRequested = true
    }
    
    /**
     * Start playing a silent audio track to help keep the CPU active
     * This is a common technique to prevent Android from throttling background processes
     */
    private fun startSilentAudio() {
        stopSilentAudio() // Stop any existing track first
        
        try {
            // Create a silent audio track
            val bufferSize = AudioTrack.getMinBufferSize(
                8000, 
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            if (bufferSize <= 0) {
                Log.e(TAG, "Invalid buffer size for silent audio")
                return
            }
            
            // Create silence - all zeros
            val silentBuffer = ByteArray(bufferSize)
            
            // Create and start the AudioTrack
            if (VERSION.SDK_INT >= VERSION_CODES.M) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                
                val audioFormat = AudioFormat.Builder()
                    .setSampleRate(8000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                
                silentAudioTrack = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                silentAudioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    8000,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }
            
            silentAudioTrack?.play()
            
            // Start a thread that keeps writing silence to the AudioTrack
            ThreadManager.runOnBackground {
                try {
                    while (isServiceRunning.get() && silentAudioTrack != null) {
                        silentAudioTrack?.write(silentBuffer, 0, silentBuffer.size)
                        Thread.sleep(500) // Write every 500ms
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in silent audio thread: ${e.message}")
                }
            }
            
            Log.d(TAG, "Started silent audio track")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start silent audio: ${e.message}")
        }
    }
    
    /**
     * Stop the silent audio track
     */
    private fun stopSilentAudio() {
        try {
            silentAudioTrack?.stop()
            silentAudioTrack?.release()
            silentAudioTrack = null
            Log.d(TAG, "Stopped silent audio track")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping silent audio: ${e.message}")
        }
    }
    
    companion object {
        const val ACTION_REGISTER_WEBVIEW = "com.tiddlywikibrowser.action.REGISTER_WEBVIEW"
        const val ACTION_UNREGISTER_WEBVIEW = "com.tiddlywikibrowser.action.UNREGISTER_WEBVIEW"
        const val ACTION_STOP_SERVICE = "com.tiddlywikibrowser.action.STOP_SERVICE"
        const val ACTION_FORCE_RESUME_VIDEOS = "com.tiddlywikibrowser.action.FORCE_RESUME_VIDEOS"
        const val ACTION_APP_BACKGROUND = "com.tiddlywikibrowser.action.APP_BACKGROUND"
        const val EXTRA_WEBVIEW_KEY = "webview_key"
    }
}