package com.tiddlywikibrowser

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
    private var videoWakeLock: PowerManager.WakeLock? = null
    
    // Keep track of whether we're playing video
    private var hasActiveVideo = false
    
    // Binder for activity to communicate with service
    inner class LocalBinder : Binder() {
        fun getService(): BackgroundWebViewService = this@BackgroundWebViewService
    }
    
    private val binder = LocalBinder()
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        
        // Configure WebView for background media
        WebView.setWebContentsDebuggingEnabled(true)
        
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    // These additional settings help with video playback
                    allowContentAccess = true
                    allowFileAccess = true
                    blockNetworkImage = false
                    blockNetworkLoads = false
                    loadsImagesAutomatically = true
                    
                    // Performance optimization
                    setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                    setEnableSmoothTransition(true)
                    
                    // Add caching for better video playback
                    setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT)
                    
                    // Increase buffer size for media playback (if available)
                    try {
                        javaClass.getMethod("setMediaPlaybackRequiresUserGesture", Boolean::class.java)
                            .invoke(this, false)
                        
                        // Some devices have this method for buffer size
                        try {
                            javaClass.getMethod("setMediaPlaybackRequiresBuffering", Boolean::class.java)
                                .invoke(this, true)
                        } catch (e: Exception) {
                            // Method not available, ignore
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting advanced media settings: ${e.message}")
                    }
                }
                
                // Force hardware acceleration - critical for video
                webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                
                // Keep WebView from pausing when not visible
                webView.setWillNotDraw(false)
                
                // Ensure WebView is resumed
                webView.onResume()
                
                // Ensure sufficient priority for this WebView
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        webView.javaClass.getMethod("setThreadPriority", Int::class.java)
                            .invoke(webView, Thread.MAX_PRIORITY)
                    } catch (e: Exception) {
                        // Method not available, ignore
                    }
                }
                
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
     * Configure a WebView for background video playback
     */
    private fun enableBackgroundVideoPlayback(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                if (window.__backgroundVideoPlaybackInitialized) return true;
                
                // Keep track of playing videos
                window.__activeVideos = [];
                window.__globalVideoRefs = []; // Keep strong references to prevent GC
                
                // Debug logging helper
                function logDebug(msg) {
                    console.log('[BackgroundVideo] ' + msg);
                }
                
                // Override and patch key browser functions for video playback
                function patchBrowserAPIs() {
                    // Prevent automatic pausing of media elements
                    if (typeof navigator.mediaSession !== 'undefined') {
                        navigator.mediaSession.setActionHandler('pause', () => {
                            logDebug('Intercepted mediaSession pause');
                            // Don't actually pause if we're playing video in background
                            if (document.visibilityState === 'hidden' && window.__activeVideos.length > 0) {
                                return false;
                            }
                        });
                    }
                    
                    // Prevent page visibility API from affecting playback
                    const originalVisibilityState = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
                    if (originalVisibilityState && originalVisibilityState.get) {
                        Object.defineProperty(Document.prototype, 'visibilityState', {
                            get: function() {
                                // If videos are playing, pretend we're always visible
                                if (window.__activeVideos.length > 0 && 
                                    window.__activeVideos.some(v => v.__shouldBePlaying)) {
                                    return 'visible';
                                }
                                return originalVisibilityState.get.call(this);
                            }
                        });
                    }
                    
                    // Prevent requestAnimationFrame throttling in background
                    const originalRAF = window.requestAnimationFrame;
                    window.requestAnimationFrame = function(callback) {
                        if (document.visibilityState === 'hidden' && window.__activeVideos.length > 0) {
                            // Use setTimeout as fallback with similar timing
                            return setTimeout(function() {
                                callback(performance.now());
                            }, 16); // ~60fps
                        }
                        return originalRAF(callback);
                    };
                }
                
                // Try to patch browser APIs
                try {
                    patchBrowserAPIs();
                } catch(e) {
                    console.error('[BackgroundVideo] Failed to patch browser APIs:', e);
                }
                
                // Observer to watch for video elements
                function setupVideoObserver() {
                    // Create a mutation observer to detect when new videos are added
                    const observer = new MutationObserver(function(mutations) {
                        let videoAdded = false;
                        mutations.forEach(function(mutation) {
                            mutation.addedNodes.forEach(function(node) {
                                if (node.nodeName === 'VIDEO' || 
                                    (node.getElementsByTagName && node.getElementsByTagName('video').length > 0)) {
                                    videoAdded = true;
                                }
                            });
                        });
                        
                        if (videoAdded) {
                            setupAllVideos();
                        }
                    });
                    
                    // Start observing the document with the configured parameters
                    observer.observe(document.documentElement, { 
                        childList: true,
                        subtree: true
                    });
                }
                
                // Function to optimize video performance
                function optimizeVideoPerformance(video) {
                    // Set video buffer sizes
                    if (video.bufferSize !== undefined) {
                        video.bufferSize = 8388608; // 8MB buffer
                    }
                    
                    // Prevent stuttering by preloading the video
                    video.preload = "auto";
                    
                    // Video quality hint
                    if (typeof video.getVideoPlaybackQuality === 'function') {
                        try {
                            video.preservesPitch = false; // Save resources if we're just playing
                        } catch (e) {
                            // Not supported
                        }
                    }
                    
                    // Disable unneeded features
                    video.disableRemotePlayback = true;
                    
                    // On some browsers/WebViews these are supported
                    try {
                        // Non-standard attributes for better performance
                        if (typeof video.fastSeek === 'function') {
                            video.__hasFastSeek = true;
                        }
                        if (video.mozHasAudio !== undefined) {
                            // Mozilla specific optimization
                            video.mozFrameBufferLength = 4; // larger buffer
                        }
                    } catch (e) {
                        // Not supported
                    }
                    
                    // Keep a global reference to prevent garbage collection
                    window.__globalVideoRefs.push(video);
                }
                
                // Function to properly configure a video element for background playback
                function setupVideoElement(video) {
                    if (video.__backgroundPlaybackSetup) return;
                    
                    logDebug('Setting up video for background playback');
                    
                    // Set critical attributes for background playback
                    video.setAttribute('playsinline', 'true');
                    video.setAttribute('webkit-playsinline', 'true');
                    video.setAttribute('x-webkit-airplay', 'allow');
                    
                    // Improve buffering and reduce stuttering
                    video.setAttribute('autobuffer', 'true');
                    video.setAttribute('buffered', 'buffered');
                    
                    // These help with various browser/WebView implementations
                    video.setAttribute('disablePictureInPicture', 'true');
                    video.setAttribute('controlsList', 'nodownload nofullscreen noremoteplayback');
                    
                    // Force hardware acceleration
                    video.style.transform = 'translateZ(0)';
                    
                    // Check if video has a source
                    const hasSource = video.src || 
                                     (video.querySelector('source') !== null) ||
                                     (video.srcObject !== null);
                                     
                    if (hasSource) {
                        logDebug('Video has a source, optimizing playback');
                        optimizeVideoPerformance(video);
                    }
                    
                    // CRITICAL: Ensure video stays in view even when scrolled away
                    if (!video.__keepVisibleInterval) {
                        video.__keepVisibleInterval = setInterval(function() {
                            if (video.__shouldBePlaying && !video.paused) {
                                // Force element to remain in a valid state for playback
                                if (video.offsetWidth === 0 || video.offsetHeight === 0) {
                                    // Set minimum dimensions to keep it in the layout engine
                                    video.style.position = 'fixed';
                                    video.style.top = '-5px';
                                    video.style.left = '-5px';
                                    video.style.width = '10px';
                                    video.style.height = '10px';
                                    video.style.opacity = '0.01';
                                    video.style.pointerEvents = 'none';
                                    video.style.zIndex = '999999';
                                    
                                    // Keep this size for a moment to ensure rendering occurs
                                    video.__isInForcedVisibleState = true;
                                    
                                    // Reset after ensuring continuous playback
                                    setTimeout(function() {
                                        if (!video.__shouldBePlaying) return;
                                        
                                        video.style.position = '';
                                        video.style.top = '';
                                        video.style.left = '';
                                        video.style.width = '';
                                        video.style.height = '';
                                        video.style.opacity = '';
                                        video.style.pointerEvents = '';
                                        video.style.zIndex = '';
                                        video.__isInForcedVisibleState = false;
                                    }, 200);
                                }
                            }
                        }, 1000);
                    }
                    
                    // Add a buffering monitor to prevent stuttering
                    if (!video.__bufferMonitor) {
                        video.__bufferMonitor = setInterval(function() {
                            if (video.__shouldBePlaying && !video.paused) {
                                // Check buffered ranges
                                let bufferedAhead = 0;
                                if (video.buffered && video.buffered.length > 0) {
                                    for (let i = 0; i < video.buffered.length; i++) {
                                        if (video.buffered.start(i) <= video.currentTime && 
                                            video.buffered.end(i) > video.currentTime) {
                                            bufferedAhead = video.buffered.end(i) - video.currentTime;
                                            break;
                                        }
                                    }
                                }
                                
                                // If we have less than 2 seconds buffered ahead, try to improve buffering
                                if (bufferedAhead < 2 && !video.__isBuffering) {
                                    logDebug('Low buffer detected (' + bufferedAhead.toFixed(1) + 's), optimizing');
                                    video.__isBuffering = true;
                                    
                                    // Temporarily pause to allow buffer to fill
                                    const wasPlaying = !video.paused;
                                    if (wasPlaying) {
                                        // Use the original pause to avoid our override
                                        video.__originalPause();
                                    }
                                    
                                    // After a short delay, resume playback with more buffer
                                    setTimeout(function() {
                                        if (video.__shouldBePlaying && wasPlaying) {
                                            // If we were playing, resume
                                            video.play().catch(e => {
                                                logDebug('Error resuming after buffer: ' + e);
                                            });
                                        }
                                        video.__isBuffering = false;
                                    }, 500);
                                }
                            }
                        }, 2000);
                    }
                    
                    // Store the original pause method for buffer management
                    if (!video.__originalPause) {
                        video.__originalPause = video.pause;
                    }
                    
                    // Track when video is actually playing
                    video.addEventListener('playing', function() {
                        video.__isPlaying = true;
                        video.__shouldBePlaying = true;
                        notifyNativeAboutPlaybackState(true);
                        
                        logDebug('Video playing event triggered');
                        
                        // Add to active videos list if not already there
                        if (!window.__activeVideos.includes(video)) {
                            window.__activeVideos.push(video);
                        }
                    });
                    
                    // Capture and handle pause events
                    video.addEventListener('pause', function(e) {
                        // Only mark as not playing if this isn't a temporary buffer pause
                        if (!video.__isBuffering) {
                            video.__isPlaying = false;
                        } else {
                            logDebug('Ignoring pause during buffering');
                            return;
                        }
                        
                        // If video should be playing but was paused by system, resume it
                        if (video.__shouldBePlaying) {
                            logDebug('Video was paused but should be playing, resuming');
                            
                            // Small delay before resuming to avoid rapid pause/play cycles
                            setTimeout(function() {
                                if (video.__shouldBePlaying && !video.__isBuffering) {
                                    resumeVideoPlay(video);
                                }
                            }, 50);
                        }
                    });
                    
                    // Monitor playback for stuttering
                    let lastTime = 0;
                    let stuckCounter = 0;
                    
                    video.addEventListener('timeupdate', function() {
                        if (video.__shouldBePlaying && !video.paused) {
                            // Check if playback time is advancing
                            if (Math.abs(video.currentTime - lastTime) < 0.05) {
                                stuckCounter++;
                                
                                // If stuck for multiple consecutive checks, try to unstick
                                if (stuckCounter >= 3 && !video.__isBuffering) {
                                    logDebug('Playback appears stuck, attempting to unstick');
                                    
                                    // Toggle a small seek to unstick
                                    const currentTime = video.currentTime;
                                    if (video.__hasFastSeek) {
                                        try {
                                            // Try to use fast seek if available
                                            video.fastSeek(currentTime + 0.1);
                                        } catch (e) {
                                            video.currentTime = currentTime + 0.1;
                                        }
                                    } else {
                                        video.currentTime = currentTime + 0.1;
                                    }
                                    
                                    stuckCounter = 0;
                                }
                            } else {
                                // Reset counter if time is advancing normally
                                stuckCounter = 0;
                            }
                            
                            lastTime = video.currentTime;
                        }
                    });
                    
                    // When user explicitly pauses
                    video.addEventListener('userPause', function() {
                        video.__shouldBePlaying = false;
                        
                        // Remove from active videos
                        const index = window.__activeVideos.indexOf(video);
                        if (index > -1) {
                            window.__activeVideos.splice(index, 1);
                        }
                        
                        if (window.__activeVideos.length === 0) {
                            notifyNativeAboutPlaybackState(false);
                        }
                    });
                    
                    // When video ends
                    video.addEventListener('ended', function() {
                        video.__shouldBePlaying = false;
                        video.__isPlaying = false;
                        
                        // Remove from active videos
                        const index = window.__activeVideos.indexOf(video);
                        if (index > -1) {
                            window.__activeVideos.splice(index, 1);
                        }
                        
                        if (window.__activeVideos.length === 0) {
                            notifyNativeAboutPlaybackState(false);
                        }
                    });
                    
                    // Override the pause method to detect when system pauses
                    video.pause = function() {
                        // Don't intercept during buffering management
                        if (video.__isBuffering) {
                            return video.__originalPause.apply(this, arguments);
                        }
                        
                        // Detect if this is a system-initiated pause vs. user-initiated
                        const isUserAction = isUserInitiated();
                        
                        if (!isUserAction && video.__shouldBePlaying) {
                            logDebug('Intercepted system pause call');
                            // Prevent system pause by returning without calling original
                            return;
                        }
                        
                        // If this is a user pause, mark it
                        if (isUserAction) {
                            video.__shouldBePlaying = false;
                            video.dispatchEvent(new Event('userPause'));
                        }
                        
                        // Call original
                        return video.__originalPause.apply(this, arguments);
                    };
                    
                    // Mark as setup
                    video.__backgroundPlaybackSetup = true;
                }
                
                // Helper function to detect if an action is user-initiated
                function isUserInitiated() {
                    // This is a heuristic - most system operations happen during hidden state
                    return document.visibilityState === 'visible' || 
                           (typeof document.hasFocus === 'function' && document.hasFocus());
                }
                
                // Function to setup all video elements on the page
                function setupAllVideos() {
                    document.querySelectorAll('video').forEach(setupVideoElement);
                }
                
                // Function to attempt resuming video playback with multiple retries
                function resumeVideoPlay(video, attempts = 5) {
                    if (!attempts || !video.__shouldBePlaying) return;
                    
                    logDebug('Attempting to resume (' + attempts + ' attempts left)');
                    
                    try {
                        // Don't attempt resume during buffering management
                        if (video.__isBuffering) {
                            logDebug('Skipping resume during active buffering');
                            return;
                        }
                    
                        // Make sure video is visible to the system before playing
                        if (!video.__isInForcedVisibleState) {
                            video.style.position = 'fixed';
                            video.style.top = '-5px';
                            video.style.left = '-5px';
                            video.style.width = '10px';
                            video.style.height = '10px';
                            video.style.opacity = '0.01';
                            video.style.zIndex = '999999';
                            video.__isInForcedVisibleState = true;
                        }
                        
                        // First, make sure we have adequate buffer
                        let hasBuffer = false;
                        if (video.buffered && video.buffered.length > 0) {
                            for (let i = 0; i < video.buffered.length; i++) {
                                if (video.buffered.start(i) <= video.currentTime && 
                                    video.buffered.end(i) > video.currentTime + 1) {
                                    hasBuffer = true;
                                    break;
                                }
                            }
                        }
                        
                        // If we don't have buffer, wait briefly before trying play
                        if (!hasBuffer && video.readyState < 3) {
                            logDebug('Waiting for buffer before resuming');
                            setTimeout(() => resumeVideoPlay(video, attempts), 200);
                            return;
                        }
                        
                        const playPromise = video.play();
                        
                        if (playPromise !== undefined) {
                            playPromise.then(() => {
                                logDebug('Successfully resumed playback');
                                video.__isPlaying = true;
                                
                                // Reset the style after successful playback starts
                                setTimeout(() => {
                                    if (video.__isInForcedVisibleState) {
                                        video.style.position = '';
                                        video.style.top = '';
                                        video.style.left = '';
                                        video.style.width = '';
                                        video.style.height = '';
                                        video.style.opacity = '';
                                        video.style.zIndex = '';
                                        video.__isInForcedVisibleState = false;
                                    }
                                }, 200);
                            }).catch(err => {
                                logDebug('Play failed: ' + err);
                                
                                // Reset style
                                if (video.__isInForcedVisibleState) {
                                    video.style.position = '';
                                    video.style.top = '';
                                    video.style.left = '';
                                    video.style.width = '';
                                    video.style.height = '';
                                    video.style.opacity = '';
                                    video.style.zIndex = '';
                                    video.__isInForcedVisibleState = false;
                                }
                                
                                if (attempts > 1 && video.__shouldBePlaying) {
                                    // Try again after a delay with exponential backoff
                                    setTimeout(() => resumeVideoPlay(video, attempts - 1), 300 * (6-attempts));
                                }
                            });
                        }
                    } catch (e) {
                        logDebug('Exception during play: ' + e);
                        
                        // Reset style
                        if (video.__isInForcedVisibleState) {
                            video.style.position = '';
                            video.style.top = '';
                            video.style.left = '';
                            video.style.width = '';
                            video.style.height = '';
                            video.style.opacity = '';
                            video.style.zIndex = '';
                            video.__isInForcedVisibleState = false;
                        }
                        
                        if (attempts > 1 && video.__shouldBePlaying) {
                            setTimeout(() => resumeVideoPlay(video, attempts - 1), 300 * (6-attempts));
                        }
                    }
                }
                
                // Function to handle page visibility changes
                function handleVisibilityChange() {
                    if (document.visibilityState === 'hidden') {
                        // Page is now hidden, maintain playing videos
                        window.__activeVideos.forEach(video => {
                            if (video.__isPlaying) {
                                // Make sure video keeps playing in background
                                console.log('[BackgroundVideo] Page hidden, ensuring playback continues');
                                video.__shouldBePlaying = true;
                                
                                // Force continuation
                                resumeVideoPlay(video);
                            }
                        });
                        
                        // Set a recurring check while page is hidden
                        if (!window.__backgroundVideoInterval) {
                            window.__backgroundVideoInterval = setInterval(() => {
                                window.__activeVideos.forEach(video => {
                                    if (video.__shouldBePlaying && !video.__isPlaying) {
                                        resumeVideoPlay(video);
                                    }
                                });
                            }, 1000);
                        }
                    } else if (document.visibilityState === 'visible') {
                        // Page is visible again
                        if (window.__backgroundVideoInterval) {
                            clearInterval(window.__backgroundVideoInterval);
                            window.__backgroundVideoInterval = null;
                        }
                        
                        // Resume any videos that should be playing
                        window.__activeVideos.forEach(video => {
                            if (video.__shouldBePlaying && !video.__isPlaying) {
                                resumeVideoPlay(video);
                            }
                        });
                    }
                }
                
                // Function to notify native code about video playback state
                function notifyNativeAboutPlaybackState(isPlaying) {
                    if (window.MediaInterface) {
                        try {
                            if (isPlaying) {
                                // Find the first playing video to get its metadata
                                const activeVideo = window.__activeVideos.find(v => v.__isPlaying) || 
                                                  window.__activeVideos[0];
                                
                                if (activeVideo) {
                                    window.MediaInterface.onMediaStateChange(
                                        activeVideo.getAttribute('title') || 'TiddlyWiki Video',
                                        'TiddlyWiki Video',
                                        Math.floor(activeVideo.duration * 1000 || 0),
                                        Math.floor(activeVideo.currentTime * 1000 || 0),
                                        true
                                    );
                                }
                            } else {
                                window.MediaInterface.onMediaStateChange(
                                    'TiddlyWiki Video',
                                    'TiddlyWiki',
                                    0,
                                    0,
                                    false
                                );
                            }
                        } catch (e) {
                            console.error('[BackgroundVideo] Failed to notify native:', e);
                        }
                    }
                }
                
                // Periodic check for active videos
                function periodicVideoCheck() {
                    // Clean up any ended or removed videos from active list
                    window.__activeVideos = window.__activeVideos.filter(v => {
                        return document.body.contains(v) && !v.ended;
                    });
                    
                    // Check all current videos
                    document.querySelectorAll('video').forEach(video => {
                        // Setup if not already
                        setupVideoElement(video);
                        
                        // Check if playing
                        if (!video.paused && !video.ended) {
                            video.__isPlaying = true;
                            video.__shouldBePlaying = true;
                            
                            // Add to active videos if not there
                            if (!window.__activeVideos.includes(video)) {
                                window.__activeVideos.push(video);
                            }
                        }
                    });
                    
                    // Notify native if we have active videos
                    notifyNativeAboutPlaybackState(window.__activeVideos.length > 0);
                    
                    return window.__activeVideos.length > 0;
                }
                
                // Initialize tracking array
                window.__activeVideos = [];
                
                // Override Page Visibility API for video playback
                document.addEventListener('visibilitychange', handleVisibilityChange);
                
                // Run initial setup
                setupAllVideos();
                setupVideoObserver();
                
                // More frequent checks during active video playback
                setInterval(periodicVideoCheck, 2000);
                
                // Mark as initialized
                window.__backgroundVideoPlaybackInitialized = true;
                
                // Do an initial check
                return periodicVideoCheck();
            })();
        """.trimIndent()) { result ->
            // If we found active videos during initialization, update state
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
                            // Return if no tracking array or it's empty
                            if (!window.__activeVideos || window.__activeVideos.length === 0) {
                                // Do a fresh check just to be sure
                                let foundVideo = false;
                                document.querySelectorAll('video').forEach(function(video) {
                                    if (!video.paused && !video.ended) {
                                        foundVideo = true;
                                        
                                        // Try to set up the video if not already set up
                                        if (typeof setupVideoElement === 'function' && !video.__backgroundPlaybackSetup) {
                                            setupVideoElement(video);
                                        }
                                    }
                                });
                                return foundVideo;
                            }
                            
                            // Check if we have any active videos from our tracking
                            let hasActiveVideo = false;
                            window.__activeVideos.forEach(function(video) {
                                if (video.__isPlaying || (!video.paused && !video.ended)) {
                                    hasActiveVideo = true;
                                    
                                    // Make sure video is actually playing
                                    if (video.paused && video.__shouldBePlaying) {
                                        console.log('[BackgroundVideo] Video check found paused video, resuming');
                                        try {
                                            video.play().catch(e => console.error('[BackgroundVideo] Resume error:', e));
                                        } catch(e) {
                                            console.error('[BackgroundVideo] Exception during resume:', e);
                                        }
                                    }
                                }
                            });
                            
                            return hasActiveVideo;
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
                    acquire(3*60*60*1000L) // Hold for up to 3 hours max
                }
                Log.d(TAG, "Video wake lock acquired")
                
                // Start a more aggressive check when video is playing
                startVideoCheckLoop()
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