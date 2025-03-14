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

class MediaSessionManager(private val context: Context) {
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
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MediaPlaybackService.LocalBinder
            playbackService = binder?.service
            Log.d(TAG, "Service connected")

            // Now that service is connected, update it with our media session
            mediaSession?.let { session ->
                playbackService?.setMediaSession(session)

                // Also update the notification with current state
                if (hasActiveMedia && currentMetadata != null) {
                    val state = session.controller.playbackState
                    playbackService?.updateNotification(
                        session,
                        currentMetadata,
                        state,
                        currentBitmap
                    )
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            Log.d(TAG, "Service disconnected")
        }
    }

    private val stateChangeLock = Object()
    private var gainCallbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingGainCallback: Runnable? = null
    private var pendingLossTransientCallback: Runnable? = null

    init {
        Log.d(TAG, "Initializing MediaSessionManager")
        try {
            setupMediaSession()
            startPlaybackService()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaSessionManager", e)
            // Don't rethrow - we want to avoid crashing the app
        }
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
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        Log.d(TAG, "MediaSession.onPlay()")
                        synchronized(stateChangeLock) {
                            if (!hasActiveMedia) {
                                Log.d(TAG, "No active media to play")
                                return
                            }

                            wasPlayingBeforeFocusLoss = false
                            lastPlayTimestamp = System.currentTimeMillis()

                            if (requestAudioFocus()) {
                                // Delay playback to allow audio focus to settle
                                gainCallbackHandler.postDelayed({
                                    synchronized(stateChangeLock) {
                                        isPlaying = true
                                        Log.d(TAG, "Playing media")
                                        // Tell the ExoPlayer to play via the MainActivity callback
                                        (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.play()
                                        // Also try to play any HTML5 audio/video elements directly
                                        evaluateWebViewJavascript("document.querySelector('audio,video')?.play()")
                                        updatePlaybackState()
                                    }
                                }, 100) // Reduced delay for better responsiveness
                            }
                        }
                    }

                    override fun onPause() {
                        Log.d(TAG, "MediaSession.onPause()")
                        synchronized(stateChangeLock) {
                            if (!isPlaying) {
                                Log.d(TAG, "Media already paused")
                                return
                            }

                            isPlaying = false
                            // Pause the ExoPlayer via the MainActivity callback
                            (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.pause()
                            // Also try to pause any HTML5 audio/video elements directly
                            evaluateWebViewJavascript("document.querySelector('audio,video')?.pause()")
                            updatePlaybackState()
                            abandonAudioFocus()
                        }
                    }

                    override fun onSeekTo(pos: Long) {
                        Log.d(TAG, "MediaSession.onSeekTo($pos)")
                        currentPosition = pos
                        // Seek the ExoPlayer via the MainActivity callback
                        (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.seekTo(pos)
                        // Also try to seek HTML5 audio/video elements directly
                        evaluateWebViewJavascript("document.querySelector('audio,video').currentTime = ${pos / 1000.0}")
                        updatePlaybackState()
                    }

                    override fun onSkipToNext() {
                        Log.d(TAG, "MediaSession.onSkipToNext()")
                        // Skip forward 15 seconds
                        val newPos = currentPosition + 15000
                        // Seek the ExoPlayer via the MainActivity callback
                        (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.seekTo(newPos)
                        // Also try to seek HTML5 audio/video elements directly
                        evaluateWebViewJavascript("document.querySelector('audio,video').currentTime += 15")
                        currentPosition = newPos
                        updatePlaybackState()
                    }

                    override fun onSkipToPrevious() {
                        Log.d(TAG, "MediaSession.onSkipToPrevious()")
                        // Skip backward 15 seconds
                        val newPos = (currentPosition - 15000).coerceAtLeast(0)
                        // Seek the ExoPlayer via the MainActivity callback
                        (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.seekTo(newPos)
                        // Also try to seek HTML5 audio/video elements directly
                        evaluateWebViewJavascript("document.querySelector('audio,video').currentTime -= 15")
                        currentPosition = newPos
                        updatePlaybackState()
                    }

                    override fun onStop() {
                        Log.d(TAG, "MediaSession.onStop()")
                        synchronized(stateChangeLock) {
                            isPlaying = false
                            currentPosition = 0
                            abandonAudioFocus()

                            // Stop the ExoPlayer via the MainActivity callback
                            (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.stop()
                            // Also try to stop any HTML5 audio/video elements directly
                            evaluateWebViewJavascript("""
                                const media = document.querySelector('audio,video');
                                if (media) {
                                    media.pause();
                                    media.currentTime = 0;
                                }
                            """)
                            updatePlaybackState()
                        }
                    }

                    override fun onCustomAction(action: String?, extras: Bundle?) {
                        Log.d(TAG, "MediaSession.onCustomAction($action)")
                        when (action) {
                            "SKIP_FORWARD" -> onSkipToNext()
                            "SKIP_BACKWARD" -> onSkipToPrevious()
                            else -> super.onCustomAction(action, extras)
                        }
                    }
                })

                // Set flags for media session
                setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                        MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)

                // This is important to make the session active from the start
                isActive = true

                // Set media button receiver explicitly
                setMediaButtonReceiver(pendingIntent)
            }

            // Force update the service with this session
            playbackService?.setMediaSession(mediaSession!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupMediaSession", e)
            // Don't rethrow - we want the manager to still be instantiated
        }
    }

    private fun startPlaybackService() {
        Log.d(TAG, "Starting and binding to playback service")
        val serviceIntent = Intent(context, MediaPlaybackService::class.java)
        serviceIntent.action = "INIT_MEDIA_SERVICE"

        // Start as foreground service for Android O and later
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        try {
            // Bind to the service
            context.bindService(
                serviceIntent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error binding to service", e)
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
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
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
            .setState(state, currentPosition, if (isPlaying) 1f else 0f)

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
            // Make sure to keep the session active while we have media
            isActive = hasActiveMedia
        }

        playbackService?.let { service ->
            service.updatePlaybackState(state, currentPosition)
        }

        // Always update notification when playback state changes
        updateNotificationIfNeeded()
    }

    private fun updateNotificationIfNeeded() {
        if (hasActiveMedia) {
            val metadata = currentMetadata ?: mediaSession?.controller?.metadata
            val state = mediaSession?.controller?.playbackState

            playbackService?.let { service ->
                mediaSession?.let { session ->
                    service.updateNotification(session, metadata, state, currentBitmap)
                }
            }
        } else {
            playbackService?.stopForeground()
        }
    }

    fun updateMetadata(title: String?, artist: String?, duration: Long?, bitmap: Bitmap? = null) {
        synchronized(stateChangeLock) {
            val hadMetadata = hasActiveMedia
            hasActiveMedia = title != null && duration != null && duration > 0
            currentBitmap = bitmap

            if (title != null) {
                val metadata = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "")
                    .apply {
                        duration?.let { putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
                        bitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
                    }
                    .build()

                mediaSession?.setMetadata(metadata)
                currentMetadata = metadata

                playbackService?.let { service ->
                    mediaSession?.let { session ->
                        service.updateNotification(
                            session,
                            metadata,
                            session.controller.playbackState,
                            bitmap
                        )
                    }
                }

                // Make sure the session is active when we have metadata
                mediaSession?.isActive = hasActiveMedia
            }

            if (hasActiveMedia != hadMetadata) {
                if (!hasActiveMedia) {
                    playbackService?.stopForeground()
                    abandonAudioFocus()
                }
            }
        }
    }

    fun updatePlaybackState(playing: Boolean, position: Long) {
        synchronized(stateChangeLock) {
            if (!hasActiveMedia && playing) {
                // If media is playing but we don't have metadata yet, make it active
                hasActiveMedia = true
                mediaSession?.isActive = true
            }

            val stateChanged = isPlaying != playing
            val positionChanged = currentPosition != position

            isPlaying = playing
            currentPosition = position

            if (stateChanged || positionChanged) {
                updatePlaybackState()
            }
        }
    }

    fun updatePlaybackPosition(position: Long) {
        synchronized(stateChangeLock) {
            if (currentPosition != position) {
                currentPosition = position
                // Only update state if position changed significantly
                if (Math.abs(position - currentPosition) > 1000) {
                    updatePlaybackState()
                }
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
        playbackService?.stopForeground()
        try {
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun evaluateWebViewJavascript(script: String) {
        Log.d(TAG, "Executing script: $script")
        try {
            (context as? MainActivity)?.getCurrentWebView()?.evaluateJavascript(script) { result ->
                Log.d(TAG, "Script result: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing script", e)
        }
    }

    fun setWebView(webView: android.webkit.WebView?) {
        Log.d(TAG, "Setting WebView reference")

        // Update the WebView reference and reset media state
        webView?.evaluateJavascript("""
            (function() {
                // Check for existing media and update state
                const media = document.querySelector('audio,video');
                if (media) {
                    return JSON.stringify({
                        exists: true,
                        playing: !media.paused,
                        currentTime: media.currentTime,
                        duration: media.duration,
                        title: media.getAttribute('title') || document.title,
                        artist: media.getAttribute('artist') || 'TiddlyWiki Audio'
                    });
                }
                return JSON.stringify({ exists: false });
            })();
        """.trimIndent()) { result ->
            try {
                Log.d(TAG, "WebView media check result: $result")
                // Process the result to update media state if needed
            } catch (e: Exception) {
                Log.e(TAG, "Error processing WebView result", e)
            }
        }

        // Install media detection script that will keep reporting media state changes
        webView?.evaluateJavascript(MainActivity.mediaMonitorScript, null)
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
}