package com.tiddlywikibrowser

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver

private const val TAG = "MediaPlaybackService"

class MediaPlaybackService : MediaBrowserServiceCompat() {
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "media_playback"
        private const val SEEK_INTERVAL = 15000L // 15 seconds in milliseconds

        // Action constants for custom intents
        const val ACTION_PLAY = "com.tiddlywikibrowser.ACTION_PLAY"
        const val ACTION_PAUSE = "com.tiddlywikibrowser.ACTION_PAUSE"
        const val ACTION_SKIP_FORWARD = "com.tiddlywikibrowser.ACTION_SKIP_FORWARD"
        const val ACTION_SKIP_BACKWARD = "com.tiddlywikibrowser.ACTION_SKIP_BACKWARD"
    }

    private var mediaSession: MediaSessionCompat? = null
    private var playbackState: PlaybackStateCompat? = null
    private var mediaPlayerCallback: MediaPlayerCallback? = null
    private var lastMetadata: MediaMetadataCompat? = null
    private var lastBitmap: Bitmap? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    interface MediaPlayerCallback {
        fun onPlay()
        fun onPause()
        fun onSeekTo(pos: Long)
        fun onSkipForward()
        fun onSkipBackward()
    }

    public inner class LocalBinder : Binder() {
        val service: MediaPlaybackService
            get() = this@MediaPlaybackService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MediaPlaybackService onCreate")
        createNotificationChannel()
    }

    fun setMediaSession(session: MediaSessionCompat) {
        Log.d(TAG, "Setting media session")
        mediaSession = session
        sessionToken = session.sessionToken

        // Immediately update the notification if we have metadata
        lastMetadata?.let { metadata ->
            updateNotification(session, metadata, playbackState, lastBitmap)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (SERVICE_INTERFACE == intent?.action) {
            super.onBind(intent)
        } else {
            binder
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun setCallback(callback: MediaPlayerCallback) {
        Log.d(TAG, "Setting media player callback")
        this.mediaPlayerCallback = callback
    }

    fun updatePlaybackState(state: Int, position: Long = 0) {
        synchronized(this) {
            Log.d(TAG, "Updating playback state: state=$state, position=$position")
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

            stateBuilder.setState(state, position, 1.0f)
                .setBufferedPosition(position)

            // Add custom actions for skip forward/backward
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

            playbackState = stateBuilder.build()
            mediaSession?.apply {
                setPlaybackState(playbackState)

                // Make sure session is active if we have a non-stopped state
                if (state != PlaybackStateCompat.STATE_STOPPED) {
                    isActive = true
                }
            }

            // Update the notification with new state
            lastMetadata?.let { metadata ->
                mediaSession?.let { session ->
                    updateNotification(session, metadata, playbackState, lastBitmap)
                }
            }
        }
    }

    fun updateNotification(
        mediaSession: MediaSessionCompat,
        metadata: MediaMetadataCompat?,
        state: PlaybackStateCompat?,
        bitmap: Bitmap? = null
    ) {
        Log.d(TAG, "Updating notification with state: ${state?.state}")
        this.mediaSession = mediaSession
        this.lastMetadata = metadata
        this.lastBitmap = bitmap

        // Don't show notification if we have no metadata
        if (metadata == null) {
            stopForeground()
            return
        }

        val isPlaying = state?.state == PlaybackStateCompat.STATE_PLAYING

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))  // Show skip back, play/pause, skip forward in compact view
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
            .setContentText(metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
            .setLargeIcon(bitmap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)  // Only mark as ongoing if actually playing

        // Skip backward button with direct action
        val skipBackIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MediaPlaybackService::class.java).setAction(ACTION_SKIP_BACKWARD),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        builder.addAction(R.drawable.ic_skip_backward_15, "Skip Backward", skipBackIntent)

        // Play/Pause button with direct action
        val playPauseAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseText = if (isPlaying) "Pause" else "Play"
        val playPauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaPlaybackService::class.java).setAction(playPauseAction),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        builder.addAction(playPauseIcon, playPauseText, playPauseIntent)

        // Skip forward button with direct action
        val skipForwardIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MediaPlaybackService::class.java).setAction(ACTION_SKIP_FORWARD),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        builder.addAction(R.drawable.ic_skip_forward_15, "Skip Forward", skipForwardIntent)

        // Main activity PendingIntent for notification click
        val contentIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        builder.setContentIntent(contentIntent)

        try {
            startForeground(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        if (mediaSession == null) {
            Log.w(TAG, "Media session is null, can't handle command")
            return START_STICKY
        }

        val action = intent?.action
        if (action != null) {
            // Process our custom actions first
            when (action) {
                ACTION_PLAY -> {
                    Log.d(TAG, "Processing direct PLAY action")
                    // Make sure to call this on the main thread to avoid concurrency issues
                    mainHandler.post {
                        mediaPlayerCallback?.onPlay()
                    }
                }
                ACTION_PAUSE -> {
                    Log.d(TAG, "Processing direct PAUSE action")
                    mainHandler.post {
                        mediaPlayerCallback?.onPause()
                    }
                }
                ACTION_SKIP_FORWARD -> {
                    Log.d(TAG, "Processing direct SKIP_FORWARD action")
                    mainHandler.post {
                        mediaPlayerCallback?.onSkipForward()
                    }
                }
                ACTION_SKIP_BACKWARD -> {
                    Log.d(TAG, "Processing direct SKIP_BACKWARD action")
                    mainHandler.post {
                        mediaPlayerCallback?.onSkipBackward()
                    }
                }
                Intent.ACTION_MEDIA_BUTTON -> {
                    // Let MediaButtonReceiver handle standard media button intents
                    Log.d(TAG, "Processing MEDIA_BUTTON intent")
                    mediaSession?.let { session ->
                        MediaButtonReceiver.handleIntent(session, intent)
                    }
                }
                "INIT_MEDIA_SERVICE" -> {
                    Log.d(TAG, "Service initialization requested")
                    // Do nothing special, just keep the service running
                }
                "STOP_MEDIA_SERVICE" -> {
                    Log.d(TAG, "Service stop requested")
                    stopForeground()
                    stopSelf()
                }
                else -> {
                    // For any other intent, try processing it as a media button
                    try {
                        mediaSession?.let { session ->
                            MediaButtonReceiver.handleIntent(session, intent)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling media button intent", e)
                    }
                }
            }
        }

        // Always return START_STICKY to ensure the service keeps running
        return START_STICKY
    }

    fun stopForeground() {
        Log.d(TAG, "Stopping foreground service")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "MediaPlaybackService onDestroy")
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        mediaPlayerCallback = null
        super.onDestroy()
    }
}