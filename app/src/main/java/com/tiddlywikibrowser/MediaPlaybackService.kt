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
    private var isForegroundService = false

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

        // Get explicit metadata titles or use defaults
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Playing media"
        val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "TiddlyWiki"
        
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))  // Show skip back, play/pause, skip forward in compact view
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(bitmap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(state?.state == PlaybackStateCompat.STATE_PLAYING)

        // Intent to open main activity when notification is tapped
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingContentIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingContentIntent)

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

        // Update notification or start foreground service
        val notification = builder.build()
        if (state?.state == PlaybackStateCompat.STATE_PLAYING) {
            startForeground(NOTIFICATION_ID, notification)
            isForegroundService = true
        } else {
            if (isForegroundService) {
                stopForeground(false) // keep notification when paused
                isForegroundService = false
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Immediately show notification when service starts
        if (mediaSession == null) {
            val tempNotification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Media Playback")
                .setContentText("Preparing...")
                .setSmallIcon(R.drawable.ic_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
                
            startForeground(NOTIFICATION_ID, tempNotification)
            isForegroundService = true
        }

        // Handle media controls
        when (intent?.action) {
            "SKIP_FORWARD" -> mediaPlayerCallback?.onSkipForward()
            "SKIP_BACKWARD" -> mediaPlayerCallback?.onSkipBackward()
            Intent.ACTION_MEDIA_BUTTON -> {
                mediaSession?.let { session ->
                    MediaButtonReceiver.handleIntent(session, intent)
                    updatePlaybackState(playbackState?.state ?: PlaybackStateCompat.STATE_NONE)
                }
            }
            else -> mediaSession?.let { session -> MediaButtonReceiver.handleIntent(session, intent) }
        }

        return START_STICKY // So service will be restarted if killed
    }

    fun stopService() {
        if (isForegroundService) {
            stopForeground(true)
            isForegroundService = false
        }
        stopSelf()
    }

    fun stopForeground() {
        mediaSession?.isActive = false
        if (isForegroundService) {
            stopForeground(true)
            isForegroundService = false
        }
    }

    override fun onDestroy() {
        mediaSession?.apply {
            isActive = false
            release()
        }
        super.onDestroy()
    }
}