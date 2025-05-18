package com.tiddlywikibrowser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import android.util.Log

private const val TAG = "MediaSessionManager"

class MediaSessionManager private constructor(private val context: Context) {
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isPlaying = false
    private var currentPosition: Long = 0
    private var hasActiveMedia = false
    private var playbackService: MediaPlaybackService? = null
    private var currentBitmap: Bitmap? = null
    private var isActive = false
    private var currentMetadata: MediaMetadataCompat? = null
    private var wasPlayingBeforeFocusLoss = false
    private var lastPlayTimestamp: Long = 0
    private var isServiceBound = false
    private var currentPlaybackState: PlaybackStateCompat? = null
    private var webViewProvider: WebViewProvider? = null // Add webViewProvider variable
    private var sessionToken: MediaSessionCompat.Token? = null

    private var lastUserActionTimestamp: Long = 0
    private val USER_ACTION_DEBOUNCE_MS: Long = 500 // 0.5 seconds, moved from const val

    private val stateChangeLock = Object()
    private var gainCallbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingGainCallback: Runnable? = null
    private var pendingLossTransientCallback: Runnable? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MediaPlaybackService.LocalBinder
            playbackService = binder?.service
            isServiceBound = true
            Log.d(TAG, "Service connected")
            mediaSession?.let {
                session -> playbackService?.setMediaSession(session)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: MediaSessionManager? = null

        fun getInstance(context: Context): MediaSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaSessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        android.util.Log.d(TAG, "MediaSessionManager: init block started.")
        Log.d(TAG, "Initializing MediaSessionManager")
        try {
            setupMediaSession()
            startPlaybackService()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaSessionManager", e)
            // Don't rethrow - we want to avoid crashing the app
        }
        android.util.Log.d(TAG, "MediaSessionManager: init block FINISHED. Instance hashCode: ${this.hashCode()}")
    }

    private fun setupMediaSession() {
        try {
            // Use the MEDIA_BUTTON intent as the PendingIntent for the media session
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setClass(context, androidx.media.session.MediaButtonReceiver::class.java)
            }

            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                mediaButtonIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )

            val componentName = ComponentName(context, MediaPlaybackService::class.java)
            mediaSession = MediaSessionCompat(context, "TiddlyWikiMediaSession", componentName, pendingIntent).apply {
                setCallback(MediaSessionCallback())

                // Set flags for media session
                setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                        MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)

                // This is important to make the session active from the start
                isActive = true

                // Set media button receiver explicitly
                setMediaButtonReceiver(pendingIntent)
            }

            mediaSession?.isActive = false // Explicitly set to inactive initially

            // Force update the service with this session
            playbackService?.setMediaSession(mediaSession!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupMediaSession", e)
            // Don't rethrow - we want the manager to still be instantiated
        }
        sessionToken = mediaSession?.sessionToken
        Log.d(TAG, "MediaSession created. IsActive: ${mediaSession?.isActive}")
    }

    private fun startPlaybackService() {
        Log.d(TAG, "Starting and binding to playback service")
        
        if (isServiceBound && playbackService != null) {
            Log.d(TAG, "Service already bound, not re-initializing session on service.")
            return
        }

        val serviceIntent = Intent(context, MediaPlaybackService::class.java)
        serviceIntent.action = "INIT_MEDIA_SERVICE"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            val didBind = context.bindService(
                serviceIntent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            android.util.Log.d(TAG, "MediaSessionManager: context.bindService called. Result: $didBind")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting/binding service", e)
            // Try to recover by releasing and recreating media session
            release()
            setupMediaSession()
        }
    }

    private fun requestAudioFocus(): Boolean {
        Log.d(TAG, "Requesting audio focus")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(::handleAudioFocusChange)
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                ::handleAudioFocusChange,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        Log.d(TAG, "Audio focus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app took focus permanently, pause playback
                wasPlayingBeforeFocusLoss = isPlaying
                if (isPlaying) {
                    mediaSession?.controller?.transportControls?.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Another app took focus temporarily, pause playback
                wasPlayingBeforeFocusLoss = isPlaying

                // Cancel any pending callbacks
                pendingGainCallback?.let { gainCallbackHandler.removeCallbacks(it) }
                pendingLossTransientCallback?.let { gainCallbackHandler.removeCallbacks(it) }

                // If media is playing, set up a delayed pause to avoid immediate stop/start cycles
                if (isPlaying) {
                    pendingLossTransientCallback = Runnable {
                        mediaSession?.controller?.transportControls?.pause()
                        pendingLossTransientCallback = null
                    }.also {
                        gainCallbackHandler.postDelayed(it, 300)
                    }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // We got audio focus back, resume if we were playing before
                pendingLossTransientCallback?.let {
                    gainCallbackHandler.removeCallbacks(it)
                    pendingLossTransientCallback = null
                }

                if (wasPlayingBeforeFocusLoss) {
                    // Create a delayed callback to resume playback
                    pendingGainCallback = Runnable {
                        mediaSession?.controller?.transportControls?.play()
                        pendingGainCallback = null
                    }.also {
                        gainCallbackHandler.postDelayed(it, 300)
                    }
                }
            }
        }
    }

    private fun abandonAudioFocus() {
        Log.d(TAG, "Abandoning audio focus")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(::handleAudioFocusChange)
        }
    }

    private fun updatePlaybackState() {
        synchronized(stateChangeLock) {
            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            android.util.Log.d(TAG, "MediaSessionManager.internalUpdatePlaybackState: state=$state, isPlaying=$isPlaying. PlaybackService is ${if (playbackService == null) "NULL" else "NOT NULL"}")
            Log.d(TAG, "Updating playback state: ${if (isPlaying) "PLAYING" else "PAUSED"}")
            
            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_STOP
                )
            Log.d(TAG, "MediaSessionManager.internalUpdatePlaybackState - Setting state: $state, Position: $currentPosition, IsPlaying: $isPlaying")
            stateBuilder.setState(state, currentPosition, if (isPlaying) 1f else 0f)

            // Add custom actions for 15s forward/backward
            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    "SKIP_BACKWARD",
                    "Skip Back 15s",
                    R.drawable.ic_skip_backward_15
                ).build()
            )

            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    "SKIP_FORWARD",
                    "Skip Forward 15s",
                    R.drawable.ic_skip_forward_15
                ).build()
            )

            val playbackState = stateBuilder.build()
            mediaSession?.apply {
                setPlaybackState(playbackState)
                // isActive state is now primarily managed by updateMetadata and the public updatePlaybackState
                // However, ensure it reflects hasActiveMedia if playing, or just hasActiveMedia if paused.
                if (this@MediaSessionManager.isPlaying) {
                    isActive = true // If playing, session must be active
                } else {
                    isActive = hasActiveMedia // If paused, active only if we have media
                }
                Log.d(TAG, "MediaSessionManager.internalUpdatePlaybackState - mediaSession.setPlaybackState CALLED with: State: ${playbackState.state}, Position: ${playbackState.position}, Session Active: ${isActive}")
            }

            // Ensure service is updated with new state
            playbackService?.let { service ->
                service.updatePlaybackState(state, currentPosition)
            } ?: run {
                // If service is not available, try to start it
                if (hasActiveMedia) {
                    startPlaybackService()
                }
            }

            // Always update notification when playback state changes
            updateNotificationIfNeeded()
        }
    }

    private fun updateNotificationIfNeeded() {
        synchronized(stateChangeLock) {
            android.util.Log.d(TAG, "updateNotificationIfNeeded called. PlaybackService is ${if (playbackService == null) "NULL" else "NOT NULL"}")
            if (hasActiveMedia) {
                val metadata = currentMetadata ?: mediaSession?.controller?.metadata
                val state = mediaSession?.controller?.playbackState

                playbackService?.let { service ->
                    mediaSession?.let { session ->
                        service.updateNotification(session, metadata, state, currentBitmap)
                    }
                } ?: run {
                    // If service is not available, try to start it
                    startPlaybackService()
                }
            } else {
                playbackService?.stopForeground(true) // Pass boolean argument
            }
        }
    }

    fun updateMetadata(title: String?, artist: String?, duration: Long?, bitmap: Bitmap? = null) {
        android.util.Log.d(TAG, "MediaSessionManager: updateMetadata ENTRY. Title: '$title', Artist: '$artist', Duration: $duration")
        synchronized(stateChangeLock) {
            android.util.Log.d(TAG, "updateMetadata (synchronized) BEGIN - Title: ${title ?: "null"}, Duration: ${duration ?: "null"}. PlaybackService is ${if (playbackService == null) "NULL" else "NOT NULL"}")
            val oldHasActiveMedia = hasActiveMedia // Store old state
            hasActiveMedia = title != null && duration != null && duration > 0
            currentBitmap = bitmap
            
            val isVideo = title?.contains("video", ignoreCase = true) == true || 
                          title?.contains("TiddlyWiki Video", ignoreCase = true) == true
            
            val isVideoContent = isVideo

            if (title != null) { // Only proceed with metadata update if title is present
                Log.d(TAG, "updateMetadata: Title is not null. Building metadata. hasActiveMedia: $hasActiveMedia")
                val metadataObject = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "")
                    .apply {
                        duration?.let { putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
                        bitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
                        
                        if (isVideoContent) {
                            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Video: $title")
                            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Playing in background")
                        }
                    }
                    .build()

                mediaSession?.setMetadata(metadataObject)
                currentMetadata = metadataObject
                Log.d(TAG, "MediaSessionManager.updateMetadata - mediaSession.setMetadata CALLED with: Title: ${metadataObject.getString(MediaMetadataCompat.METADATA_KEY_TITLE)}, Duration: ${metadataObject.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)}")

                Log.d(TAG, "updateMetadata: Attempting to update notification via playbackService. PlaybackService is ${if (playbackService == null) "NULL" else "NOT NULL"}")
                playbackService?.let { service ->
                    mediaSession?.let { session ->
                        Log.d(TAG, "updateMetadata: Calling service.updateNotification with Meta Title: ${metadataObject.getString(MediaMetadataCompat.METADATA_KEY_TITLE)}, Duration: ${metadataObject.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)}")
                        service.updateNotification(
                            session,
                            metadataObject,
                            session.controller.playbackState, // Pass current state
                            bitmap
                        )
                        Log.d(TAG, "updateMetadata: Returned from service.updateNotification")
                    } ?: Log.d(TAG, "updateMetadata: playbackService was NOT null, but mediaSession WAS NULL inside 'let' block.")
                } ?: Log.d(TAG, "updateMetadata: playbackService WAS NULL.")
            } else {
                // If title is null, effectively means no valid media, clear metadata and set inactive
                mediaSession?.setMetadata(null)
                currentMetadata = null
                hasActiveMedia = false // Ensure this is false
            }
            
            // Always update mediaSession active state based on hasActiveMedia
            mediaSession?.isActive = hasActiveMedia
            Log.d(TAG, "updateMetadata: mediaSession isActive set to $hasActiveMedia")

            Log.d(TAG, "updateMetadata: Checking if hasActiveMedia ($hasActiveMedia) changed from oldHasActiveMedia ($oldHasActiveMedia)")
            if (hasActiveMedia != oldHasActiveMedia) {
                if (!hasActiveMedia) {
                    Log.d(TAG, "updateMetadata: hasActiveMedia is now false. Stopping foreground and abandoning audio focus.")
                    playbackService?.stopForeground(true) // Pass boolean argument
                    abandonAudioFocus()
                } else {
                    Log.d(TAG, "updateMetadata: hasActiveMedia is now true.")
                    // If it became active, ensure the service is started and notification updated
                    startPlaybackService() // Ensures service is running
                    // updateNotificationIfNeeded() is called by updatePlaybackState which usually follows
                }
            }
            Log.d(TAG, "updateMetadata (synchronized) END")
        }
    }

    // This is the primary method for updating the MediaSession and Service based on the current internal state.
    // It should be called AFTER 'this.isPlaying' and 'this.currentPosition' are authoritatively set.
    private fun syncMediaSessionAndService() {
        synchronized(stateChangeLock) {
            val currentIsPlaying = this.isPlaying // Use the authoritative internal state
            val currentPos = this.currentPosition

            android.util.Log.d(TAG, "MediaSessionManager.syncMediaSessionAndService: Syncing with isPlaying=$currentIsPlaying, position=$currentPos. PlaybackService is ${if (playbackService == null) "NULL" else "NOT NULL"}")

            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_STOP
                )
            
            val playbackStateValue = if (currentIsPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            stateBuilder.setState(playbackStateValue, currentPos, if (currentIsPlaying) 1f else 0f)

            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder("SKIP_BACKWARD", "Skip Back 15s", R.drawable.ic_skip_backward_15).build()
            )
            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder("SKIP_FORWARD", "Skip Forward 15s", R.drawable.ic_skip_forward_15).build()
            )

            val newPlaybackState = stateBuilder.build()
            mediaSession?.apply {
                setPlaybackState(newPlaybackState)
                isActive = if (currentIsPlaying) true else hasActiveMedia // Session active if playing, or if paused with media
                Log.d(TAG, "MediaSessionManager.syncMediaSessionAndService - mediaSession.setPlaybackState CALLED with: State: ${newPlaybackState.state}, Position: ${newPlaybackState.position}, Session Active: $isActive")
            }

            playbackService?.let { service ->
                service.updatePlaybackState(playbackStateValue, currentPos)
            } ?: run {
                if (hasActiveMedia) startPlaybackService()
            }
            updateNotificationIfNeeded() // Ensures notification reflects the new state
        }
    }

    // Public method called by WebView bridge or other non-user-action sources
    fun updatePlaybackState(playing: Boolean, position: Long) {
        android.util.Log.d(TAG, "MediaSessionManager: updatePlaybackState (WebView/External) ENTRY. isPlaying: $playing, position: $position")
        synchronized(stateChangeLock) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUserActionTimestamp < USER_ACTION_DEBOUNCE_MS) {
                // If a user action recently set 'this.isPlaying', and WebView's 'playing' contradicts it, ignore WebView.
                if (this.isPlaying != playing) {
                    Log.w(TAG, "MediaSessionManager.updatePlaybackState (WebView/External): Ignoring WebView's state ($playing) because it contradicts a recent user action (user set to ${this.isPlaying}).")
                    return // Ignore this update from WebView
                }
            }

            // If not debounced or if WebView state matches user's intent, update internal state
            val stateChanged = this.isPlaying != playing || this.currentPosition != position
            this.isPlaying = playing
            this.currentPosition = position

            if (!hasActiveMedia && playing) {
                hasActiveMedia = true
                mediaSession?.isActive = true
                Log.d(TAG, "updatePlaybackState (WebView/External): Playback started without prior metadata, setting hasActiveMedia=true, session.isActive=true")
                startPlaybackService()
            }

            if (stateChanged) {
                syncMediaSessionAndService()
            } else {
                Log.d(TAG, "MediaSessionManager.updatePlaybackState (WebView/External): No change in state or significant position, not syncing.")
            }

            if (!playing && !hasActiveMedia) {
                mediaSession?.isActive = false
                Log.d(TAG, "updatePlaybackState (WebView/External): Playback stopped and no active media, setting session.isActive=false")
            }
        }
    }

    fun updatePlaybackPosition(position: Long) {
        synchronized(stateChangeLock) {
            if (Math.abs(this.currentPosition - position) > 500) { // Update if position changed significantly
                this.currentPosition = position
                // Don't change 'this.isPlaying' here, only position.
                // Then sync. The debounce in the public updatePlaybackState won't apply here directly,
                // but syncMediaSessionAndService will use the current 'this.isPlaying'.
                Log.d(TAG, "MediaSessionManager.updatePlaybackPosition: Position updated to $position. Syncing session.")
                syncMediaSessionAndService()
            }
        }
    }

    fun release() {
        Log.d(TAG, "Releasing MediaSessionManager")
        hasActiveMedia = false
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        abandonAudioFocus()
        playbackService?.stopForeground(true) // Pass boolean argument
        if (isServiceBound) {
            try {
                context.unbindService(serviceConnection)
                isServiceBound = false
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding service", e)
            }
        }
        playbackService = null
    }

    private fun evaluateWebViewJavascript(script: String) {
        Log.d(TAG, "Executing script: $script")
        try {
            // Use webViewProvider instead of context casting
            webViewProvider?.executeJavascript(script) { result ->
                Log.d(TAG, "Script result: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing script", e)
        }
    }

    /**
     * Set the current WebView to monitor for media
     */
    fun setWebView(webView: android.webkit.WebView?) {
        Log.d(TAG, "Setting WebView reference")

        // Update the WebView reference and reset media state
        if (webView != null) {
            try {
                // Check if WebView is destroyed before using it
                webView.settings
                
                webView.evaluateJavascript("""
                    (function() {
                        // Check for existing media and update state
                        const media = document.querySelector('audio,video');
                        if (media) {
                            return JSON.stringify({
                                exists: true,
                                playing: !media.paused,
                                currentTime: media.currentTime,
                                duration: media.duration,
                                title: document.title
                            });
                        }
                        return JSON.stringify({exists: false});
                    })();
                """.trimIndent()) { result ->
                    Log.d(TAG, "WebView media check result: $result")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking WebView for media: ${e.message}")
            }
        }
    }

    /**
     * Check if the service is already bound
     */
    fun isServiceBound(): Boolean {
        return isServiceBound
    }

    fun bindToService() {
        Log.d(TAG, "Binding to service")
        try {
            // Ensure we have the media session before binding
            mediaSession?.let { session ->
                playbackService?.setMediaSession(session)

                // If we have metadata, update the notification
                currentMetadata?.let { metadata ->
                    val state = session.controller?.playbackState
                    playbackService?.updateNotification(session, metadata, state, currentBitmap)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding to service", e)
        }
    }

    fun getMediaSession(): MediaSessionCompat? {
        return mediaSession
    }

    /**
     * Start the media playback service in foreground mode
     */
    fun startMediaService() {
        startPlaybackService()
    }

    // Interface for WebView to provide current media state
    fun setWebViewProvider(provider: WebViewProvider?) {
        this.webViewProvider = provider
        if (provider != null) {
            // Optionally fetch initial state when WebView is set
            // fetchMediaStateFromWebView() 
        }
    }

    // Potentially called by MediaSessionCallback actions
    private fun fetchMediaStateFromWebView() {
        webViewProvider?.getCurrentMediaState { title, artist, duration, position, isPlaying ->
            ThreadManager.runOnMain { // Ensure UI thread for updates if they touch UI directly
                var changed = false
                if (title != null || artist != null || duration != null) {
                    updateMetadata(title, artist, duration) // Bitmap is null here, handled by updateMetadata
                    changed = true
                }
                if (isPlaying != null && position != null) {
                    updatePlaybackState(isPlaying, position)
                    changed = true
                }
                // if (changed) { // updateNotificationIfNeeded is called by the above methods
                // }
            }
        }
    }
    
    fun getSessionToken(): MediaSessionCompat.Token? {
        return sessionToken
    }

    // MediaSessionCallback: Defines how the media session responds to controller commands
    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            Log.d(TAG, "MediaSessionCallback: onPlay called")
            if (requestAudioFocus()) {
                synchronized(stateChangeLock) {
                    lastUserActionTimestamp = System.currentTimeMillis()
                    wasPlayingBeforeFocusLoss = true
                    this@MediaSessionManager.isPlaying = true
                    Log.d(TAG, "MediaSessionCallback.onPlay: Set this.isPlaying=true")
                }
                evaluateWebViewJavascript("""
                    (function() {
                        const media = document.querySelector('video, audio');
                        if (media) {
                            media.play().catch(e => console.log('Play error:', e));
                            return true;
                        }
                        return false;
                    })();
                """.trimIndent())
                syncMediaSessionAndService()
            } else {
                Log.w(TAG, "Could not gain audio focus for onPlay")
                synchronized(stateChangeLock) {
                    lastUserActionTimestamp = System.currentTimeMillis()
                    this@MediaSessionManager.isPlaying = false
                }
                syncMediaSessionAndService()
            }
        }

        override fun onPause() {
            Log.d(TAG, "MediaSessionCallback: onPause called")
            synchronized(stateChangeLock) {
                lastUserActionTimestamp = System.currentTimeMillis()
                wasPlayingBeforeFocusLoss = false
                this@MediaSessionManager.isPlaying = false
                Log.d(TAG, "MediaSessionCallback.onPause: Set this.isPlaying=false")
            }
            evaluateWebViewJavascript("""
                (function() {
                    const media = document.querySelector('video, audio');
                    if (media) {
                        media.pause();
                        return true;
                    }
                    return false;
                })();
            """.trimIndent())
            syncMediaSessionAndService()
        }

        override fun onStop() {
            Log.d(TAG, "MediaSessionCallback: onStop called")
            synchronized(stateChangeLock) {
                lastUserActionTimestamp = System.currentTimeMillis()
                wasPlayingBeforeFocusLoss = false
                this@MediaSessionManager.isPlaying = false
                this@MediaSessionManager.currentPosition = 0L
                Log.d(TAG, "MediaSessionCallback.onStop: Set this.isPlaying=false, position=0")
            }
            evaluateWebViewJavascript("""
                (function() {
                    const media = document.querySelector('video, audio');
                    if (media) {
                        media.pause();
                        media.currentTime = 0;
                        return true;
                    }
                    return false;
                })();
            """.trimIndent())
            syncMediaSessionAndService()
            abandonAudioFocus()
        }

        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "MediaSessionCallback: onSeekTo called with position: $pos")
            synchronized(stateChangeLock) {
                lastUserActionTimestamp = System.currentTimeMillis()
                this@MediaSessionManager.currentPosition = pos
                Log.d(TAG, "MediaSessionCallback.onSeekTo: Set position=$pos")
            }
            val positionInSeconds = pos / 1000.0
            evaluateWebViewJavascript("""
                (function() {
                    const media = document.querySelector('video, audio');
                    if (media) {
                        media.currentTime = $positionInSeconds;
                        return true;
                    }
                    return false;
                })();
            """.trimIndent())
            syncMediaSessionAndService()
        }

        override fun onSkipToNext() {
            Log.d(TAG, "MediaSessionCallback: onSkipToNext called")
            evaluateWebViewJavascript("""
                (function() {
                    if (typeof window.skipForward === 'function') {
                        window.skipForward();
                        return true;
                    } else {
                        const media = document.querySelector('video, audio');
                        if (media) {
                            media.currentTime = Math.min(media.duration, media.currentTime + 15);
                            media.dispatchEvent(new Event('timeupdate'));
                            return true;
                        }
                    }
                    return false;
                })();
            """.trimIndent())
            // Update the current position
            synchronized(stateChangeLock) {
                currentPosition = (currentPosition + 15000).coerceAtMost(mediaSession?.controller?.metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: Long.MAX_VALUE)
            }
            syncMediaSessionAndService()
        }

        override fun onSkipToPrevious() {
            Log.d(TAG, "MediaSessionCallback: onSkipToPrevious called")
            evaluateWebViewJavascript("""
                var media = document.querySelector('video, audio');
                if (media) {
                    media.currentTime = Math.max(0, media.currentTime - 15);
                    media.dispatchEvent(new Event('timeupdate'));
                }
            """.trimIndent())
            // Update the current position
            synchronized(stateChangeLock) {
                currentPosition = (currentPosition - 15000).coerceAtLeast(0)
            }
            syncMediaSessionAndService()
        }

        // Handle MediaButton events here if not using a separate MediaButtonReceiver
        // override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
        //     Log.d(TAG, "MediaSessionCallback: onMediaButtonEvent received")
        //     // Handle specific media button intents (e.g., ACTION_MEDIA_BUTTON)
        //     // This is usually handled by MediaButtonReceiver.handleIntent or by the system directly
        //     // if FLAG_HANDLES_MEDIA_BUTTONS is set and a MediaButtonReceiver is declared in manifest.
        //     return super.onMediaButtonEvent(mediaButtonEvent)
        // }
    }
}