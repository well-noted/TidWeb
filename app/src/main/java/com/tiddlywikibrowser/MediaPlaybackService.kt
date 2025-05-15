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
        android.util.Log.d("MediaPlaybackService", "MediaPlaybackService: onCreate ENTRY")
        super.onCreate()
        createNotificationChannel()
        
        // Show an initial notification to keep service alive
        startForeground(NOTIFICATION_ID, createInitialNotification())
        isForegroundService = true
    }

    private fun createInitialNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Media Playback")
            .setContentText("Preparing...")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun setMediaSession(session: MediaSessionCompat) {
        synchronized(this) {
            mediaSession = session
            sessionToken = session.sessionToken

            // Always update the notification to MediaStyle once the session is set.
            // Use current metadata/state if available, otherwise sensible defaults.
            val currentMeta = session.controller.metadata
            val currentState = session.controller.playbackState

            // Prepare placeholder metadata if real metadata isn't available yet
            val effectiveMetadata = currentMeta ?: MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Waiting for media") // Placeholder
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "TiddlyWiki")       // Placeholder
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0)
                .build()

            updateNotification(
                session,
                effectiveMetadata, // Use effective metadata
                currentState,      // currentState can be null initially, updateNotification handles it
                null
            )
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
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
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
                
                // Always update notification to ensure it stays in sync
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
        synchronized(this) {
            this.mediaSession = mediaSession

            // Get explicit metadata titles or use defaults
            val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Playing media"
            val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "TiddlyWiki"
            val metaDuration = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
            val playbackStateInfo = state?.state
            val currentPositionInfo = state?.position

            android.util.Log.d("MediaPlaybackService", 
                "updateNotification CALL: Input Metadata Title='${metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)}', " +
                "Input Artist='${metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)}', " +
                "Input Duration='$metaDuration', Input State='$playbackStateInfo', Input Position='$currentPositionInfo'\n" +
                "Notification BUILT WITH: Effective Title='$title', Artist='$artist'")
            
            val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setStyle(MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(bitmap)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setOngoing(true)  // Always set ongoing to prevent notification from being dismissed

            // Intent to open main activity when notification is tapped
            val contentIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingContentIntent = PendingIntent.getActivity(
                this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pendingContentIntent)

            // Add media control actions
            addMediaControlActions(builder, state)

            // Update notification or start foreground service
            val notification = builder.build()
            
            try {
                if (state?.state == PlaybackStateCompat.STATE_PLAYING) {
                    if (!isForegroundService) {
                        startForeground(NOTIFICATION_ID, notification)
                        isForegroundService = true
                    } else {
                        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.notify(NOTIFICATION_ID, notification)
                    }
                } else {
                    // Even when paused, keep the notification visible
                    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun addMediaControlActions(
        builder: NotificationCompat.Builder,
        state: PlaybackStateCompat?
    ) {
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        return START_STICKY
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