package com.tiddlywikibrowser.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.webkit.WebView
import com.tiddlywikibrowser.BackgroundWebViewManager
import com.tiddlywikibrowser.BackgroundWebViewService
import com.tiddlywikibrowser.MediaPlaybackService
import com.tiddlywikibrowser.R
import com.tiddlywikibrowser.RobustMediaController
import com.tiddlywikibrowser.WebViewProvider
import org.json.JSONObject

class MediaSessionManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "MediaSessionManager"
        
        @Volatile
        private var INSTANCE: MediaSessionManager? = null
        
        fun getInstance(context: Context): MediaSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaSessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private var mediaSession: MediaSessionCompat? = null
    private var webViewProvider: WebViewProvider? = null
    private var webView: WebView? = null
    private var serviceConnection: ServiceConnection? = null
    private var isServiceBound = false
    private var mediaPlaybackService: MediaPlaybackService? = null
      // Persistent state to maintain across app lifecycle
    private var lastKnownMediaState = MediaState()
    private var isAppInBackground = false
    private var mediaStateUpdateHandler = Handler(Looper.getMainLooper())
    private var mediaStateUpdateRunnable: Runnable? = null
    private var backgroundWebViewManager: BackgroundWebViewManager? = null
    
    data class MediaState(
        var title: String? = null,
        var artist: String? = null,
        var duration: Long = 0L,
        var position: Long = 0L,
        var isPlaying: Boolean = false,
        var hasActiveMedia: Boolean = false
    )
    init {
        initializeMediaSession()
        startPeriodicMediaStateCheck()
    }
    
    private fun startPeriodicMediaStateCheck() {
        mediaStateUpdateRunnable = object : Runnable {
            override fun run() {
                // Only update if we have active media and WebView is available
                if (lastKnownMediaState.hasActiveMedia && webViewProvider != null) {
                    updateMediaStateFromWebView()
                }
                // Schedule next update
                mediaStateUpdateHandler.postDelayed(this, 2000) // Check every 2 seconds
            }
        }
        mediaStateUpdateHandler.post(mediaStateUpdateRunnable!!)
    }
    
    private fun updateMediaStateFromWebView() {
        webViewProvider?.executeJavascript("""
            (function() {
                const media = document.querySelector('audio,video');
                if (media) {
                    return JSON.stringify({
                        title: media.title || document.title || 'Unknown Title',
                        artist: media.getAttribute('data-artist') || 'TiddlyWiki',
                        duration: Math.round((media.duration || 0) * 1000),
                        position: Math.round((media.currentTime || 0) * 1000),
                        isPlaying: !media.paused && !media.ended,
                        hasActiveMedia: true
                    });
                }
                return JSON.stringify({hasActiveMedia: false});
            })();
        """.trimIndent()) { result ->
            try {
                if (result != null && result != "null") {
                    val jsonStr = result.replace("\"", "").replace("\\", "")
                    if (jsonStr.isNotEmpty() && jsonStr != "null") {
                        val json = JSONObject(result)
                        val newState = MediaState(
                            title = json.optString("title", "Unknown Title"),
                            artist = json.optString("artist", "TiddlyWiki"),
                            duration = json.optLong("duration", 0L),
                            position = json.optLong("position", 0L),
                            isPlaying = json.optBoolean("isPlaying", false),
                            hasActiveMedia = json.optBoolean("hasActiveMedia", false)
                        )
                        
                        // Update our persistent state
                        updateMediaState(newState)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing media state from WebView", e)
            }
        }
    }
    
    private fun updateMediaState(newState: MediaState) {
        // Check if state has changed significantly
        val stateChanged = lastKnownMediaState.title != newState.title ||
                          lastKnownMediaState.isPlaying != newState.isPlaying ||
                          Math.abs(lastKnownMediaState.position - newState.position) > 2000 || // 2 second tolerance
                          lastKnownMediaState.hasActiveMedia != newState.hasActiveMedia
        
        if (stateChanged) {
            lastKnownMediaState = newState.copy()
            
            if (newState.hasActiveMedia) {
                // Update metadata
                updateMetadata(newState.title, newState.artist, newState.duration)
                // Update playback state
                updatePlaybackState(newState.isPlaying, newState.position)
                
                // Ensure service is bound when we have active media
                if (!isServiceBound) {
                    bindToService()
                }
            } else {
                // No active media
                updatePlaybackState(false, 0)
            }
        }
    }
    
    private fun initializeMediaSession() {
        try {
            // Check if we already have an active media session
            if (mediaSession != null && mediaSession?.isActive == true) {
                Log.d(TAG, "Media session already initialized and active")
                return
            }
            
            Log.d(TAG, "Initializing new media session")
              // Create a new media session
            mediaSession = MediaSessionCompat(context, "TiddlyWikiMediaSession").apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                    MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS
                )
                
                val stateBuilder = PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
                    )
                  setPlaybackState(stateBuilder.build())
                
                // Set callback directly on MediaSession for immediate responsiveness
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        Log.d(TAG, "MediaSession direct callback: onPlay")
                        handlePlay()
                    }
                    
                    override fun onPause() {
                        Log.d(TAG, "MediaSession direct callback: onPause")
                        handlePause()
                    }
                    
                    override fun onSkipToNext() {
                        Log.d(TAG, "MediaSession direct callback: onSkipToNext")
                        handleSkipForward()
                    }
                    
                    override fun onSkipToPrevious() {
                        Log.d(TAG, "MediaSession direct callback: onSkipToPrevious")
                        handleSkipBackward()
                    }
                    
                    override fun onSeekTo(pos: Long) {
                        Log.d(TAG, "MediaSession direct callback: onSeekTo $pos")
                        handleSeekTo(pos)
                    }
                    
                    override fun onCustomAction(action: String?, extras: Bundle?) {
                        Log.d(TAG, "MediaSession direct callback: onCustomAction $action")
                        when (action) {
                            "SKIP_FORWARD" -> handleSkipForward()
                            "SKIP_BACKWARD" -> handleSkipBackward()
                        }
                    }                })
                
                // Activate the session so it can receive media button events
                isActive = true
                Log.d(TAG, "MediaSession created and activated")
            }
            
            // Bind to the service after setting up the session
            bindToService()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing media session", e)
        }
    }
      fun setWebViewProvider(provider: WebViewProvider) {
        webViewProvider = provider
    }
    
    fun setWebView(webView: WebView) {
        this.webView = webView
    }
    
    fun setBackgroundWebViewManager(manager: BackgroundWebViewManager) {
        backgroundWebViewManager = manager
    }
      /**
     * Execute JavaScript with fallback to background WebView if needed
     */
    private fun executeJavaScriptSafely(script: String, resultCallback: ((String?) -> Unit)? = null) {
        // Try foreground WebView first
        if (webViewProvider != null && !isAppInBackground) {
            webViewProvider?.executeJavascript(script, resultCallback)
        } else {
            // Fallback: try background WebView service
            Log.d(TAG, "App in background, attempting to execute JS via background service")
            
            // Try to execute via background service
            if (backgroundWebViewManager?.service != null) {
                try {
                    // Get the current WebView from background service
                    val intent = Intent(context, BackgroundWebViewService::class.java).apply {
                        action = BackgroundWebViewService.ACTION_EXECUTE_JAVASCRIPT
                        putExtra(BackgroundWebViewService.EXTRA_JAVASCRIPT_CODE, script)
                        // Execute on all WebViews since we don't know the specific key
                        // putExtra(BackgroundWebViewService.EXTRA_WEBVIEW_KEY, webViewKey)
                    }
                    context.startService(intent)
                    Log.d(TAG, "Sent JavaScript execution request to background service")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to execute JavaScript via background service", e)
                }
            } else {
                Log.w(TAG, "No available WebView context for JavaScript execution")
            }
        }
    }
    
    /**
     * Called when the app goes to background
     */
    fun onAppBackgrounded() {
        isAppInBackground = true
        Log.d(TAG, "App backgrounded - ensuring service connection and media session remain active")
        
        // Ensure we're still bound to the service
        if (!isServiceBound && lastKnownMediaState.hasActiveMedia) {
            bindToService()
        }
        
        // Make sure media session stays active
        if (mediaSession?.isActive != true && lastKnownMediaState.hasActiveMedia) {
            mediaSession?.isActive = true
        }
        
        // Continue periodic updates even in background if we have active media
        if (lastKnownMediaState.hasActiveMedia) {
            ensurePeriodicUpdatesRunning()
        }
    }
  
    
    private fun ensurePeriodicUpdatesRunning() {
        // Remove any existing callbacks
        mediaStateUpdateRunnable?.let { runnable ->
            mediaStateUpdateHandler.removeCallbacks(runnable)
        }
        
        // Start fresh periodic updates
        startPeriodicMediaStateCheck()
    }
      fun updateMetadata(title: String?, artist: String?, duration: Long, albumArt: Bitmap? = null) {
        try {
            // Update our persistent state
            lastKnownMediaState.title = title
            lastKnownMediaState.artist = artist
            lastKnownMediaState.duration = duration
            lastKnownMediaState.hasActiveMedia = duration > 0
            
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "Unknown Title")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "TiddlyWiki")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "TiddlyWiki Media")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                
            if (albumArt != null) {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
            }
            
            val metadata = metadataBuilder.build()
            mediaSession?.setMetadata(metadata)
            
            // Make sure the session is active when we have valid media
            if (duration > 0 && mediaSession?.isActive != true) {
                mediaSession?.isActive = true
                Log.d(TAG, "Activated media session due to metadata update")
            }
            
            // Update service metadata if connected
            if (isServiceBound && mediaPlaybackService != null) {
                // Service will handle notification update through MediaSession
                Log.d(TAG, "Media metadata updated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating metadata", e)
        }
    }
      fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        try {
            // Update our persistent state
            lastKnownMediaState.isPlaying = isPlaying
            lastKnownMediaState.position = position
            
            val stateActions = PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND
            
            val state = if (isPlaying) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }
            
            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(stateActions)
                .setState(state, position, if (isPlaying) 1.0f else 0.0f)
                // Add custom actions for skip forward/backward
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder("SKIP_FORWARD", "Skip Forward", R.drawable.ic_skip_forward_15).build()
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder("SKIP_BACKWARD", "Skip Backward", R.drawable.ic_skip_backward_15).build()
                )
            
            val newState = stateBuilder.build()
            mediaSession?.setPlaybackState(newState)
            
            // Make sure the session is active when we have media playing or paused
            if ((isPlaying || lastKnownMediaState.hasActiveMedia) && mediaSession?.isActive != true) {
                mediaSession?.isActive = true
                Log.d(TAG, "Activated media session due to playback state update")
            }
            
            // Update the service state if bound
            if (isServiceBound && mediaPlaybackService != null) {
                mediaPlaybackService?.updatePlaybackState(state, position)
            } else if (!isServiceBound && (isPlaying || lastKnownMediaState.hasActiveMedia)) {
                // If we're not bound to the service but have active media, try binding now
                bindToService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating playback state", e)
        }
    }
      fun bindToService() {
        // Only proceed if we don't have an active service connection
        if (serviceConnection != null && isServiceBound) {
            Log.d(TAG, "Service connection already exists and is bound, not binding again")
            return
        }
        
        Log.d(TAG, "Creating new service connection")
        
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? MediaPlaybackService.LocalBinder
                mediaPlaybackService = binder?.service
                isServiceBound = true
                Log.d(TAG, "Service connected successfully")
                  // Set callback to receive media commands
                mediaPlaybackService?.setCallback(object : MediaPlaybackService.MediaPlayerCallback {
                    override fun onPlay() {
                        Log.d(TAG, "MediaPlayerCallback: onPlay")
                        executeJavaScriptSafely("""
                            (function() {
                                const media = document.querySelector('audio,video');
                                if (media) {
                                    media.play().catch(e => console.log('Play failed:', e));
                                    return 'play_executed';
                                }
                                return 'no_media_found';
                            })();
                        """.trimIndent()) { result ->
                            Log.d(TAG, "Play JavaScript result: $result")
                            // Update our state immediately
                            lastKnownMediaState.isPlaying = true
                        }
                    }
                    
                    override fun onPause() {
                        Log.d(TAG, "MediaPlayerCallback: onPause")
                        executeJavaScriptSafely("""
                            (function() {
                                const media = document.querySelector('audio,video');
                                if (media) {
                                    media.pause();
                                    return 'pause_executed';
                                }
                                return 'no_media_found';
                            })();
                        """.trimIndent()) { result ->
                            Log.d(TAG, "Pause JavaScript result: $result")
                            // Update our state immediately
                            lastKnownMediaState.isPlaying = false
                        }
                    }
                    
                    override fun onSeekTo(pos: Long) {
                        Log.d(TAG, "MediaPlayerCallback: onSeekTo $pos")
                        executeJavaScriptSafely("""
                            (function() {
                                const media = document.querySelector('audio,video');
                                if (media) {
                                    media.currentTime = ${pos / 1000.0};
                                    return 'seek_executed';
                                }
                                return 'no_media_found';
                            })();
                        """.trimIndent()) { result ->
                            Log.d(TAG, "Seek JavaScript result: $result")
                            // Update our position immediately
                            lastKnownMediaState.position = pos
                        }
                    }
                    
                    override fun onSkipForward() {
                        Log.d(TAG, "MediaPlayerCallback: onSkipForward")
                        executeJavaScriptSafely("""
                            (function() {
                                if (window.skipForward) {
                                    window.skipForward();
                                    return 'skip_forward_custom';
                                } else {
                                    const media = document.querySelector('audio,video');
                                    if (media) {
                                        media.currentTime = Math.min(media.duration, media.currentTime + 15);
                                        return 'skip_forward_default';
                                    }
                                }
                                return 'no_media_found';
                            })();
                        """.trimIndent()) { result ->
                            Log.d(TAG, "Skip forward JavaScript result: $result")
                        }
                    }
                    
                    override fun onSkipBackward() {
                        Log.d(TAG, "MediaPlayerCallback: onSkipBackward")
                        executeJavaScriptSafely("""
                            (function() {
                                if (window.skipBackward) {
                                    window.skipBackward();
                                    return 'skip_backward_custom';
                                } else {
                                    const media = document.querySelector('audio,video');
                                    if (media) {
                                        media.currentTime = Math.max(0, media.currentTime - 15);
                                        return 'skip_backward_default';
                                    }
                                }
                                return 'no_media_found';
                            })();
                        """.trimIndent()) { result ->
                            Log.d(TAG, "Skip backward JavaScript result: $result")
                        }
                    }
                })
                
                // Initialize media session with service only if we have a valid session
                if (mediaSession != null && mediaSession?.isActive == true) {
                    try {
                        Log.d(TAG, "Setting media session in service after connection")
                        mediaPlaybackService?.setMediaSession(mediaSession!!)
                        
                        // If we have persistent media state, update the service immediately
                        if (lastKnownMediaState.hasActiveMedia) {
                            val state = if (lastKnownMediaState.isPlaying) {
                                PlaybackStateCompat.STATE_PLAYING
                            } else {
                                PlaybackStateCompat.STATE_PAUSED
                            }
                            mediaPlaybackService?.updatePlaybackState(state, lastKnownMediaState.position)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting media session in service", e)
                    }
                } else {
                    Log.d(TAG, "No active media session to set in service")
                }
                
                Log.d(TAG, "Connected to MediaPlaybackService")
            }
            
            override fun onServiceDisconnected(name: ComponentName?) {
                mediaPlaybackService = null
                isServiceBound = false
                Log.d(TAG, "Disconnected from MediaPlaybackService")
                
                // If we still have active media, try to reconnect after a delay
                if (lastKnownMediaState.hasActiveMedia) {
                    mediaStateUpdateHandler.postDelayed({
                        if (!isServiceBound && lastKnownMediaState.hasActiveMedia) {
                            Log.d(TAG, "Attempting to reconnect to service after disconnection")
                            bindToService()
                        }
                    }, 3000) // Wait 3 seconds before reconnecting
                }
            }
        }        
        // Only proceed with binding if we have a service connection
        if (serviceConnection != null) {
            try {
                // Start and bind to the service
                val intent = Intent(context, MediaPlaybackService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                
                val bindFlags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                val boundSuccessfully = context.bindService(intent, serviceConnection!!, bindFlags)
                Log.d(TAG, "Service binding initiated, result: $boundSuccessfully")
                
                if (!boundSuccessfully) {
                    // If binding failed, try starting the service again and rebinding
                    Log.w(TAG, "Initial binding failed, attempting retry")
                    mediaStateUpdateHandler.postDelayed({
                        try {
                            context.startService(intent)
                            context.bindService(intent, serviceConnection!!, bindFlags)
                            Log.d(TAG, "Second attempt to bind service")
                        } catch (e: Exception) {
                            Log.e(TAG, "Second attempt to bind service failed", e)
                        }
                    }, 1000) // Wait 1 second before retry
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error binding to service", e)
                isServiceBound = false
                
                // If there's an exception, try again after a delay
                mediaStateUpdateHandler.postDelayed({
                    if (!isServiceBound && lastKnownMediaState.hasActiveMedia) {
                        Log.d(TAG, "Retrying service bind after exception")
                        bindToService()
                    }
                }, 2000) // Wait 2 seconds before retry
            }
        } else {
            Log.e(TAG, "Cannot bind to service: serviceConnection is null")
        }
    }
    
    fun unbindFromService() {
        // Don't stop service here, let it run in background
        // Only unbind the service connection
        serviceConnection?.let {
            if (isServiceBound) {
                try {
                    context.unbindService(it)
                    isServiceBound = false
                } catch (e: Exception) {
                    Log.e(TAG, "Error unbinding service", e)
                }
            }
        }
        serviceConnection = null
    }
    
    /**
     * Check if the media playback service is currently bound
     * @return true if the service is bound, false otherwise
     */
    fun isServiceBound(): Boolean {
        return isServiceBound
    }    fun release() {
        try {
            // Stop periodic updates
            mediaStateUpdateRunnable?.let { runnable ->
                mediaStateUpdateHandler.removeCallbacks(runnable)
            }
            mediaStateUpdateRunnable = null
            
            // Pause all media in WebView before cleanup
            try {
                webView?.let { wv ->
                    Log.d(TAG, "Pausing all media in WebView before release")
                    wv.evaluateJavascript("""
                        try {
                            // Pause all audio and video elements
                            const mediaElements = document.querySelectorAll('audio, video');
                            mediaElements.forEach(element => {
                                if (!element.paused) {
                                    element.pause();
                                    console.log('Paused media element:', element.src || element.currentSrc);
                                }
                            });
                            
                            // Clear any media session if it exists
                            if (navigator.mediaSession) {
                                navigator.mediaSession.playbackState = 'none';
                            }
                        } catch (e) {
                            console.error('Error pausing media:', e);
                        }
                    """.trimIndent(), null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing WebView media during release", e)
            }
            
            // Stop any ongoing playback
            updatePlaybackState(false, 0)
            
            // Clear persistent state
            lastKnownMediaState = MediaState()
            
            // Unbind from service
            unbindFromService()
            
            // Release media session
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
            
            // Stop the service if no media is playing
            val stopIntent = Intent(context, MediaPlaybackService::class.java)
            context.stopService(stopIntent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaSessionManager", e)
        } finally {
            // Ensure cleanup in case of any errors
            try {
                mediaStateUpdateRunnable?.let { runnable ->
                    mediaStateUpdateHandler.removeCallbacks(runnable)
                }
                unbindFromService()
                mediaSession?.apply {
                    isActive = false
                    release()
                }
                mediaSession = null
                webViewProvider = null
                webView = null
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup in release()", e)
            }
        }
    }
      fun getMediaSession(): MediaSessionCompat? = mediaSession
    
    /**
     * Get the current persistent media state
     */
    fun getCurrentMediaState(): MediaState = lastKnownMediaState.copy()
    
    /**
     * Manually trigger a media state update from WebView
     * Useful when you know media state might have changed
     */
    fun refreshMediaState() {
        if (webViewProvider != null) {
            updateMediaStateFromWebView()
        }
    }
    
    /**
     * Handle play command
     */    private fun handlePlay() {
        Log.d(TAG, "Handling play command")
        
        // Use RobustMediaController for reliable execution
        val robustController = RobustMediaController.getInstance(context)
        robustController.executeMediaControl(RobustMediaController.MediaAction.PLAY)
        
        // Also update our state immediately for responsiveness
        lastKnownMediaState.isPlaying = true
        updatePlaybackState(true, lastKnownMediaState.position)
    }
      /**
     * Handle pause command
     */
    private fun handlePause() {
        Log.d(TAG, "Handling pause command")
        
        // Use RobustMediaController for reliable execution
        val robustController = RobustMediaController.getInstance(context)
        robustController.executeMediaControl(RobustMediaController.MediaAction.PAUSE)
        
        // Also update our state immediately for responsiveness
        lastKnownMediaState.isPlaying = false
        updatePlaybackState(false, lastKnownMediaState.position)
    }
      /**
     * Handle skip forward command
     */
    private fun handleSkipForward() {
        Log.d(TAG, "Handling skip forward command")
        executeJavaScriptSafely("""
            (function() {
                if (window.MediaInterface?.skipForward) {
                    window.MediaInterface.skipForward();
                    return 'skip_forward_enhanced';
                } else if (window.skipForward) {
                    window.skipForward();
                    return 'skip_forward_custom';
                } else {
                    const media = document.querySelector('audio,video');
                    if (media) {
                        media.currentTime = Math.min(media.duration, media.currentTime + 15);
                        return 'skip_forward_default';
                    }
                }
                return 'no_media_found';
            })();
        """.trimIndent()) { result ->
            Log.d(TAG, "Skip forward JavaScript result: $result")
        }
    }
    
    /**
     * Handle skip backward command
     */
    private fun handleSkipBackward() {
        Log.d(TAG, "Handling skip backward command")
        executeJavaScriptSafely("""
            (function() {
                if (window.MediaInterface?.skipBackward) {
                    window.MediaInterface.skipBackward();
                    return 'skip_backward_enhanced';
                } else if (window.skipBackward) {
                    window.skipBackward();
                    return 'skip_backward_custom';                } else {
                    const media = document.querySelector('audio,video');
                    if (media) {
                        media.currentTime = Math.max(0, media.currentTime - 15);
                        return 'skip_backward_default';
                    }
                }
                return 'no_media_found';
            })();
        """.trimIndent()) { result ->
            Log.d(TAG, "Skip backward JavaScript result: $result")
        }
    }
    
    /**
     * Handle seek to position command
     */
    private fun handleSeekTo(pos: Long) {
        Log.d(TAG, "Handling seek to command: $pos")
        executeJavaScriptSafely("""
            (function() {
                const media = document.querySelector('audio,video');
                if (media) {
                    media.currentTime = ${pos / 1000.0};
                    return 'seek_executed';
                }
                return 'no_media_found';
            })();
        """.trimIndent()) { result ->
            Log.d(TAG, "Seek JavaScript result: $result")
            // Update our position immediately
            lastKnownMediaState.position = pos
            updatePlaybackState(lastKnownMediaState.isPlaying, pos)
        }
    }
}
