package com.tiddlywikibrowser

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import androidx.media.MediaBrowserServiceCompat

class MediaPlaybackService : MediaBrowserServiceCompat() {
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "media_playback"
        private const val SEEK_INTERVAL = 15000L // 15 seconds in milliseconds
    }

    private var mediaSession: MediaSessionCompat? = null
    private var playbackState: PlaybackStateCompat? = null
    private var mediaPlayerCallback: MediaPlayerCallback? = null

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
        createNotificationChannel()
    }

    fun setMediaSession(session: MediaSessionCompat) {
        mediaSession = session
        sessionToken = session.sessionToken
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
        this.mediaPlayerCallback = callback
    }

    fun updatePlaybackState(state: Int, position: Long = 0) {
        synchronized(this) {
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
            mediaSession?.setPlaybackState(playbackState)
            
            // Update the notification with new state
            mediaSession?.let { session ->
                if (state == PlaybackStateCompat.STATE_PLAYING) {
                    session.isActive = true
                }
                updateNotification(
                    session,
                    session.controller.metadata,
                    playbackState,
                    null
                )
            }
        }
    }

    fun updateNotification(
        mediaSession: MediaSessionCompat,
        metadata: MediaMetadataCompat?,
        state: PlaybackStateCompat?,
        bitmap: Bitmap? = null
    ) {
        this.mediaSession = mediaSession

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))  // Show skip back, play/pause, skip forward in compact view
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
            .setContentText(metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
            .setLargeIcon(bitmap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(state?.state == PlaybackStateCompat.STATE_PLAYING)

        // Skip backward 15 seconds
        val skipBackIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, MediaPlaybackService::class.java).setAction("SKIP_BACKWARD"),
            PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(R.drawable.ic_skip_backward_15, "Skip Backward", skipBackIntent)

        // Play/Pause
        val playPauseIcon = if (state?.state == PlaybackStateCompat.STATE_PLAYING)
            R.drawable.ic_pause else R.drawable.ic_play
        builder.addAction(
            playPauseIcon, "Play/Pause",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)
        )

        // Skip forward 15 seconds
        val skipForwardIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaPlaybackService::class.java).setAction("SKIP_FORWARD"),
            PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(R.drawable.ic_skip_forward_15, "Skip Forward", skipForwardIntent)

        startForeground(NOTIFICATION_ID, builder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SKIP_FORWARD" -> {
                mediaSession?.controller?.transportControls?.skipToNext()
            }
            "SKIP_BACKWARD" -> {
                mediaSession?.controller?.transportControls?.skipToPrevious()
            }
            Intent.ACTION_MEDIA_BUTTON -> {
                mediaSession?.let { session ->
                    MediaButtonReceiver.handleIntent(session, intent)
                    // Ensure we maintain the session state
                    updatePlaybackState(playbackState?.state ?: PlaybackStateCompat.STATE_NONE)
                }
            }
            else -> {
                mediaSession?.let { session ->
                    MediaButtonReceiver.handleIntent(session, intent)
                }
            }
        }
        // Always return START_STICKY to ensure the service keeps running
        return START_STICKY
    }

    fun stopForeground() {
        mediaSession?.isActive = false
        stopForeground(true)
    }

    override fun onDestroy() {
        mediaSession?.apply {
            isActive = false
            release()
        }
        super.onDestroy()
    }
}