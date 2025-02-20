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
            playbackService = (service as? MediaPlaybackService.LocalBinder)?.service
            mediaSession?.let { session ->
                playbackService?.setMediaSession(session)
            }
            updateNotificationIfNeeded()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
        }
    }
    private val stateChangeLock = Object()
    private var gainCallbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingGainCallback: Runnable? = null
    // Field for handling transient loss delays
    private var pendingLossTransientCallback: Runnable? = null

    init {
        setupMediaSession()
        startPlaybackService()
    }

    private fun setupMediaSession() {
        val componentName = ComponentName(context, context.javaClass)
        mediaSession = MediaSessionCompat(context, "TiddlyWikiMediaSession", componentName, null).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    synchronized(stateChangeLock) {
                        if (!hasActiveMedia) return
                        wasPlayingBeforeFocusLoss = false
                        lastPlayTimestamp = System.currentTimeMillis()
                        if (requestAudioFocus()) {
                            // Delay playback to allow audio focus to settle
                            gainCallbackHandler.postDelayed({
                                synchronized(stateChangeLock) {
                                    isPlaying = true
                                    evaluateWebViewJavascript("document.querySelector('audio,video')?.play()")
                                    updatePlaybackState()
                                }
                            }, 500)
                        }
                    }
                }

                override fun onPause() {
                    synchronized(stateChangeLock) {
                        if (!isPlaying) return
                        
                        isPlaying = false
                        evaluateWebViewJavascript("document.querySelector('audio,video')?.pause()")
                        updatePlaybackState()
                        abandonAudioFocus()
                    }
                }

                override fun onSeekTo(pos: Long) {
                    currentPosition = pos
                    evaluateWebViewJavascript("document.querySelector('audio,video').currentTime = ${pos / 1000.0}")
                    updatePlaybackState()
                }

                override fun onSkipToNext() {
                    evaluateWebViewJavascript("document.querySelector('audio,video').currentTime += 15")
                }

                override fun onSkipToPrevious() {
                    evaluateWebViewJavascript("document.querySelector('audio,video').currentTime -= 15")
                }

                override fun onStop() {
                    isPlaying = false
                    currentPosition = 0
                    abandonAudioFocus()
                    evaluateWebViewJavascript("""
                        const media = document.querySelector('audio,video');
                        if (media) {
                            media.pause();
                            media.currentTime = 0;
                        }
                    """)
                    updatePlaybackState()
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        "SKIP_FORWARD" -> onSkipToNext()
                        "SKIP_BACKWARD" -> onSkipToPrevious()
                        else -> super.onCustomAction(action, extras)
                    }
                }
            })

            setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or 
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
        }
    }

    private fun startPlaybackService() {
        val serviceIntent = Intent(context, MediaPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        context.bindService(
            serviceIntent,
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun requestAudioFocus(): Boolean {
        // Bypassing audio focus request
        android.util.Log.d("MediaSessionManager", "Bypassing audio focus request.")
        return true
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        // Bypassing audio focus change handling
        android.util.Log.d("MediaSessionManager", "Ignoring audio focus change: $focusChange")
    }

    private fun abandonAudioFocus() {
        // Bypassing audio focus abandonment
        android.util.Log.d("MediaSessionManager", "Bypassing audio focus abandonment.")
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        android.util.Log.d("MediaSessionManager", "Updating playback state: ${if (isPlaying) "PLAYING" else "PAUSED"}")
        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(state, currentPosition, if (isPlaying) 1f else 0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .build()
            
        mediaSession?.setPlaybackState(stateBuilder)
        
        playbackService?.let { service ->
            service.updatePlaybackState(state, currentPosition)
        }
    }

    private fun updateNotificationIfNeeded() {
        if (hasActiveMedia) {
            playbackService?.updateNotification(
                mediaSession!!,
                mediaSession?.controller?.metadata,
                mediaSession?.controller?.playbackState,
                currentBitmap
            )
        } else {
            playbackService?.stopForeground()
        }
    }

    fun updateMetadata(title: String?, artist: String?, duration: Long?, bitmap: Bitmap? = null) {
        val hadMetadata = hasActiveMedia
        hasActiveMedia = title != null
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
                    service.updateNotification(session, metadata, session.controller.playbackState, bitmap)
                }
            }
        }

        if (hasActiveMedia != hadMetadata) {
            mediaSession?.isActive = hasActiveMedia
            if (!hasActiveMedia) {
                playbackService?.stopForeground()
                abandonAudioFocus()
            }
        }
    }

    fun updatePlaybackState(playing: Boolean, position: Long) {
        synchronized(stateChangeLock) {
            if (!hasActiveMedia) return

            if (isPlaying != playing) {
                isPlaying = playing
                currentPosition = position
                updatePlaybackState()
                updateNotificationIfNeeded()
            } else if (currentPosition != position) {
                currentPosition = position
                updatePlaybackState()
            }
        }
    }

    fun release() {
        hasActiveMedia = false
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        abandonAudioFocus()
        playbackService?.stopForeground()
        context.unbindService(serviceConnection)
    }

    private fun evaluateWebViewJavascript(script: String) {
        android.util.Log.d("MediaSessionManager", "Executing script: $script")
        (context as? MainActivity)?.getCurrentWebView()?.evaluateJavascript(script) { result ->
            android.util.Log.d("MediaSessionManager", "Script result: $result")
        }
    }
}