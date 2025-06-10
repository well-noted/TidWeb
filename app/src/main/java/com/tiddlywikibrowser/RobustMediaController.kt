package com.tiddlywikibrowser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView

/**
 * Robust media control manager that handles background/foreground transitions gracefully
 * Addresses race conditions and timing issues that cause inconsistent play/pause behavior
 */
class RobustMediaController(private val context: Context) {
    
    companion object {
        private const val TAG = "RobustMediaController"
        private const val RETRY_DELAY_MS = 500L
        private const val MAX_RETRIES = 3
        
        @Volatile
        private var INSTANCE: RobustMediaController? = null
        
        fun getInstance(context: Context): RobustMediaController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RobustMediaController(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private val mainActivity get() = context as? MainActivity
    private val mediaSessionManager get() = mainActivity?.mediaSessionManager
    private val backgroundWebViewManager get() = mainActivity?.backgroundWebViewManager
    
    // State tracking
    private var lastKnownPlayState = false
    private var lastCommandTimestamp = 0L
    private var isAppInBackground = false
    
    /**
     * Execute media control with robust retry logic and fallback mechanisms
     */
    fun executeMediaControl(action: MediaAction, retryCount: Int = 0) {
        val now = System.currentTimeMillis()
        lastCommandTimestamp = now
        
        Log.d(TAG, "Executing media action: $action (retry: $retryCount, background: $isAppInBackground)")
        
        when (action) {
            MediaAction.PLAY -> executePlay(retryCount)
            MediaAction.PAUSE -> executePause(retryCount) 
            MediaAction.TOGGLE -> executeToggle(retryCount)
        }
    }
    
    private fun executePlay(retryCount: Int) {
        val script = """
            (function() {
                console.log('RobustMediaController: Executing PLAY command');
                const media = document.querySelector('audio,video');
                if (media) {
                    if (media.paused) {
                        return media.play().then(() => {
                            console.log('Play succeeded');
                            return 'play_success';
                        }).catch(e => {
                            console.error('Play failed:', e);
                            return 'play_failed';
                        });
                    } else {
                        console.log('Media already playing');
                        return Promise.resolve('already_playing');
                    }
                } else {
                    console.log('No media element found');
                    return Promise.resolve('no_media');
                }
            })();
        """.trimIndent()
        
        executeWithFallback(script, "PLAY", retryCount) { result ->
            Log.d(TAG, "Play command result: $result")
            when (result) {
                "play_success", "already_playing" -> {
                    lastKnownPlayState = true
                    mediaSessionManager?.updatePlaybackState(true, 0)
                }
                "play_failed", "no_media" -> {
                    if (retryCount < MAX_RETRIES) {
                        Log.d(TAG, "Play failed, retrying in ${RETRY_DELAY_MS}ms")
                        handler.postDelayed({
                            executePlay(retryCount + 1)
                        }, RETRY_DELAY_MS)
                    } else {
                        Log.w(TAG, "Play command failed after $MAX_RETRIES retries")
                    }
                }
            }
        }
    }
    
    private fun executePause(retryCount: Int) {
        val script = """
            (function() {
                console.log('RobustMediaController: Executing PAUSE command');
                const media = document.querySelector('audio,video');
                if (media) {
                    if (!media.paused) {
                        media.pause();
                        console.log('Pause succeeded');
                        return 'pause_success';
                    } else {
                        console.log('Media already paused');
                        return 'already_paused';
                    }
                } else {
                    console.log('No media element found');
                    return 'no_media';
                }
            })();
        """.trimIndent()
        
        executeWithFallback(script, "PAUSE", retryCount) { result ->
            Log.d(TAG, "Pause command result: $result")
            when (result) {
                "pause_success", "already_paused" -> {
                    lastKnownPlayState = false
                    mediaSessionManager?.updatePlaybackState(false, 0)
                }
                "no_media" -> {
                    if (retryCount < MAX_RETRIES) {
                        Log.d(TAG, "Pause failed, retrying in ${RETRY_DELAY_MS}ms")
                        handler.postDelayed({
                            executePause(retryCount + 1)
                        }, RETRY_DELAY_MS)
                    } else {
                        Log.w(TAG, "Pause command failed after $MAX_RETRIES retries")
                    }
                }
            }
        }
    }
    
    private fun executeToggle(retryCount: Int) {
        // First, check current state
        val checkScript = """
            (function() {
                const media = document.querySelector('audio,video');
                return media ? (!media.paused ? 'playing' : 'paused') : 'no_media';
            })();
        """.trimIndent()
        
        executeWithFallback(checkScript, "CHECK_STATE", retryCount) { result ->
            Log.d(TAG, "Current media state: $result")
            when (result) {
                "playing" -> executePause(retryCount)
                "paused" -> executePlay(retryCount)
                "no_media" -> {
                    Log.w(TAG, "No media found for toggle")
                }
            }
        }
    }
    
    /**
     * Execute JavaScript with multiple fallback mechanisms
     */
    private fun executeWithFallback(
        script: String, 
        action: String, 
        retryCount: Int,
        callback: (String?) -> Unit
    ) {
        var executed = false
        
        try {
            // Method 1: Try foreground WebView first (fastest)
            if (!isAppInBackground) {
                val webView = mainActivity?.getCurrentWebView()
                if (webView != null && !isWebViewDestroyed(webView)) {
                    Log.d(TAG, "Using foreground WebView for $action")
                    webView.evaluateJavascript(script) { result ->
                        if (!executed) {
                            executed = true
                            callback(result?.replace("\"", ""))
                        }
                    }
                    return
                }
            }
            
            // Method 2: Try background WebView service
            Log.d(TAG, "Trying background WebView service for $action")
            val bgService = backgroundWebViewManager?.service
            if (bgService != null) {
                // Execute on all WebViews in background service
                bgService.executeJavaScriptOnWebView(script, null)
                
                // Since we can't get the result directly, assume success and verify later
                handler.postDelayed({
                    if (!executed) {
                        executed = true
                        callback("background_executed")
                        
                        // Verify the action worked by checking state after a delay
                        handler.postDelayed({
                            verifyMediaState(action)
                        }, 1000)
                    }
                }, 200)
                return
            }
            
            // Method 3: Force foreground approach 
            Log.d(TAG, "Forcing foreground approach for $action")
            mainActivity?.let { activity ->
                handler.post {
                    val webView = activity.getCurrentWebView()
                    if (webView != null) {
                        webView.evaluateJavascript(script) { result ->
                            if (!executed) {
                                executed = true
                                callback(result?.replace("\"", ""))
                            }
                        }
                    } else if (!executed) {
                        executed = true
                        callback("no_webview")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in executeWithFallback", e)
            if (!executed) {
                executed = true
                callback("error: ${e.message}")
            }
        }
        
        // Safety timeout
        handler.postDelayed({
            if (!executed) {
                executed = true
                Log.w(TAG, "Timeout executing $action")
                callback("timeout")
            }
        }, 2000)
    }
    
    /**
     * Verify that the media action actually worked
     */
    private fun verifyMediaState(action: String) {
        val verifyScript = """
            (function() {
                const media = document.querySelector('audio,video');
                if (media) {
                    return JSON.stringify({
                        playing: !media.paused,
                        currentTime: media.currentTime,
                        duration: media.duration
                    });
                }
                return null;
            })();
        """.trimIndent()
        
        executeWithFallback(verifyScript, "VERIFY", 0) { result ->
            Log.d(TAG, "Verification result for $action: $result")
            // Update media session with verified state
            if (result != null && result != "null") {
                try {
                    val data = org.json.JSONObject(result)
                    val isPlaying = data.getBoolean("playing")
                    val position = (data.getDouble("currentTime") * 1000).toLong()
                    
                    mediaSessionManager?.updatePlaybackState(isPlaying, position)
                    lastKnownPlayState = isPlaying
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing verification result", e)
                }
            }
        }
    }
    
    /**
     * Check if WebView is destroyed/unusable
     */
    private fun isWebViewDestroyed(webView: WebView): Boolean {
        return try {
            webView.settings // This will throw if WebView is destroyed
            false
        } catch (e: Exception) {
            true
        }
    }
    
    /**
     * Called when app goes to background
     */
    fun onAppBackgrounded() {
        isAppInBackground = true
        Log.d(TAG, "App backgrounded - switching to background mode")
        
        // Immediately sync state with background service
        backgroundWebViewManager?.forceResumeVideos()
    }
    
    /**
     * Called when app returns to foreground
     */
    fun onAppForegrounded() {
        isAppInBackground = false
        Log.d(TAG, "App foregrounded - switching to foreground mode")
        
        // Refresh state from current WebView
        handler.postDelayed({
            verifyMediaState("FOREGROUND_REFRESH")
        }, 500) // Give WebView time to settle
    }
      enum class MediaAction {
        PLAY, PAUSE, TOGGLE
    }
}

/**
 * Media command types with their corresponding JavaScript
 */
enum class MediaCommand {
    PLAY,
    PAUSE,
    SEEK_FORWARD,
    SEEK_BACKWARD;
    
    fun getJavaScript(): String {
        return when (this) {
            PLAY -> """
                (function() {
                    try {
                        const mediaElements = document.querySelectorAll('audio, video');
                        let playedCount = 0;
                        
                        for (const media of mediaElements) {
                            if (media.paused) {
                                media.play().then(() => {
                                    console.log('Media played successfully');
                                    playedCount++;
                                }).catch(e => console.log('Play failed:', e));
                            }
                        }
                        
                        if (window.MediaInterface && window.MediaInterface.resumeMedia) {
                            window.MediaInterface.resumeMedia();
                        }
                        
                        return 'play_attempted_' + playedCount;
                    } catch (e) {
                        console.error('Play command error:', e);
                        return 'play_error';
                    }
                })();
            """.trimIndent()
            
            PAUSE -> """
                (function() {
                    try {
                        const mediaElements = document.querySelectorAll('audio, video');
                        let pausedCount = 0;
                        
                        for (const media of mediaElements) {
                            if (!media.paused) {
                                media.pause();
                                pausedCount++;
                            }
                        }
                        
                        if (window.MediaInterface && window.MediaInterface.pauseMedia) {
                            window.MediaInterface.pauseMedia();
                        }
                        
                        return 'pause_attempted_' + pausedCount;
                    } catch (e) {
                        console.error('Pause command error:', e);
                        return 'pause_error';
                    }
                })();
            """.trimIndent()
            
            SEEK_FORWARD -> """
                (function() {
                    try {
                        const mediaElements = document.querySelectorAll('audio, video');
                        let seekedCount = 0;
                        
                        for (const media of mediaElements) {
                            if (media.currentTime !== undefined && media.duration > 0) {
                                media.currentTime = Math.min(media.currentTime + 15, media.duration);
                                seekedCount++;
                            }
                        }
                        
                        return 'seek_forward_' + seekedCount;
                    } catch (e) {
                        console.error('Seek forward error:', e);
                        return 'seek_forward_error';
                    }
                })();
            """.trimIndent()
            
            SEEK_BACKWARD -> """
                (function() {
                    try {
                        const mediaElements = document.querySelectorAll('audio, video');
                        let seekedCount = 0;
                        
                        for (const media of mediaElements) {
                            if (media.currentTime !== undefined) {
                                media.currentTime = Math.max(media.currentTime - 15, 0);
                                seekedCount++;
                            }
                        }
                        
                        return 'seek_backward_' + seekedCount;
                    } catch (e) {
                        console.error('Seek backward error:', e);
                        return 'seek_backward_error';
                    }
                })();
            """.trimIndent()
        }
    }
}
