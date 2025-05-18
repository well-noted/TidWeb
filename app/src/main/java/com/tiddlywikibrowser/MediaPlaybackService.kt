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
import android.util.Log

class MediaPlaybackService : MediaBrowserServiceCompat() {
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "media_playback"
        private const val SEEK_INTERVAL = 15000L // 15 seconds in milliseconds
        const val ACTION_SKIP_FORWARD_SERVICE = "com.tiddlywikibrowser.ACTION_SKIP_FORWARD_SERVICE"
        const val ACTION_SKIP_BACKWARD_SERVICE = "com.tiddlywikibrowser.ACTION_SKIP_BACKWARD_SERVICE"
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
        // startForeground(NOTIFICATION_ID, createInitialNotification()) // Removed
        isForegroundService = false // Initialize as false
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
            Log.d("MediaPlaybackService", "setMediaSession CALLED. Session: ${session.sessionToken}")
            mediaSession = session
            sessionToken = session.sessionToken

            Log.d("MediaPlaybackService", "MediaSession set in service. Notification will be updated by explicit calls from MediaSessionManager.")
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
            Log.d("MediaPlaybackService", "updatePlaybackState CALLED - State: $state, Position: $position. Current mediaSession: ${mediaSession?.sessionToken}")
            val stateBuilder = PlaybackStateCompat.Builder()

            // Correctly set playback speed: 1.0f for playing, 0.0f for paused/stopped etc.
            stateBuilder.setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f)

            // Add custom actions for skip forward/backward
            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder("SKIP_BACKWARD", "Skip Backward", R.drawable.ic_skip_backward_15)
                    .build()
            )
            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder("SKIP_FORWARD", "Skip Forward", R.drawable.ic_skip_forward_15)
                    .build()
            )

            // Ensure all supported standard actions are declared
            var actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
                          PlaybackStateCompat.ACTION_STOP or
                          PlaybackStateCompat.ACTION_SEEK_TO
            if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_PAUSED) {
                actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS // Or custom actions
            }
            stateBuilder.setActions(actions)


            playbackState = stateBuilder.build()
            mediaSession?.setPlaybackState(playbackState)
            Log.d("MediaPlaybackService", "updatePlaybackState - mediaSession.setPlaybackState CALLED with State: ${playbackState?.state}, Position: ${playbackState?.position}")
            
            mediaSession?.let { session ->
                if (state == PlaybackStateCompat.STATE_PLAYING) {
                    session.isActive = true
                }
                Log.d("MediaPlaybackService", "updatePlaybackState - Preparing to call updateNotification. Metadata: ${session.controller.metadata?.description}, PlaybackState: ${playbackState?.state}")
                updateNotification(
                    session,
                    session.controller.metadata,
                    playbackState,
                    null
                )
                Log.d("MediaPlaybackService", "updatePlaybackState - updateNotification CALLED from updatePlaybackState")
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
            Log.d("MediaPlaybackService", "updateNotification CALLED. Session: ${mediaSession.sessionToken}, Meta_IsNull: ${metadata == null}, State_IsNull: ${state == null}")
            this.mediaSession = mediaSession // Ensure this.mediaSession is updated

            val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Media" // More generic default
            val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "TiddlyWiki"
            val metaDuration = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
            val playbackStateInfo = state?.state
            val currentPositionInfo = state?.position

            // Determine if media is considered active for notification purposes
            val isMediaEffectivelyActive = metadata != null &&
                                         (state?.state == PlaybackStateCompat.STATE_PLAYING ||
                                          state?.state == PlaybackStateCompat.STATE_PAUSED ||
                                          state?.state == PlaybackStateCompat.STATE_BUFFERING) &&
                                          (metaDuration ?: 0L) > 0L


            Log.d("MediaPlaybackService", 
                "updateNotification - Building Notification: Title='$title', Artist='$artist', Duration=$metaDuration, State=$playbackStateInfo, Position=$currentPositionInfo, isMediaEffectivelyActive=$isMediaEffectivelyActive")
            
            if (!isMediaEffectivelyActive) {
                if (isForegroundService) {
                    stopForeground(true) // true = remove notification
                    isForegroundService = false
                    Log.d("MediaPlaybackService", "updateNotification - Media not active, stopping foreground service.")
                }
                // Optionally, if the service should stop itself when not active and not bound:
                // if (!isBound && !isForegroundService) stopSelf();
                return // Don't show a notification if media is not effectively active
            }
            
            try {
                Log.d("MediaPlaybackService", "updateNotification - Attempting to build notification.")
                val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setStyle(MediaStyle()
                        .setMediaSession(this.mediaSession!!.sessionToken) // this.mediaSession should be valid here due to assignment above
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
                Log.d("MediaPlaybackService", "updateNotification - Media control actions added.")

                // Update notification or start foreground service
                val notification = builder.build()
                Log.d("MediaPlaybackService", "updateNotification - Notification built successfully.")
                
                Log.d("MediaPlaybackService", "updateNotification - About to show notification. isForegroundService: $isForegroundService")
                // Only manage foreground state if media is effectively active (already checked)
                    if (!isForegroundService) {
                        startForeground(NOTIFICATION_ID, notification)
                        isForegroundService = true
                        Log.d("MediaPlaybackService", "updateNotification - Started foreground service with notification.")
                } else {
                    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(NOTIFICATION_ID, notification)
                    Log.d("MediaPlaybackService", "updateNotification - Updated existing notification.")
                }
            } catch (e: Exception) {
                Log.e("MediaPlaybackService", "Error building or displaying notification: ${e.message}", e)
            }
        }
    }

    private fun addMediaControlActions(
        builder: NotificationCompat.Builder,
        state: PlaybackStateCompat?
    ) {
        Log.d("MediaPlaybackService", "addMediaControlActions - Entered. Session token: ${this.sessionToken}")
        if (this.sessionToken == null) {
            Log.w("MediaPlaybackService", "addMediaControlActions - Media session token is null, cannot add actions.")
            return
        }

        // Skip backward 15 seconds
        val skipBackwardIntent = PendingIntent.getService(
            this,
            0, // requestCode must be unique for different intents if you want them to be distinct
            Intent(this, MediaPlaybackService::class.java).setAction(ACTION_SKIP_BACKWARD_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.addAction(R.drawable.ic_skip_backward_15, "Skip Backward", skipBackwardIntent)

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
            1, // requestCode must be unique
            Intent(this, MediaPlaybackService::class.java).setAction(ACTION_SKIP_FORWARD_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.addAction(R.drawable.ic_skip_forward_15, "Skip Forward", skipForwardIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MediaPlaybackService", "onStartCommand received action: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_SKIP_FORWARD_SERVICE -> {
                mediaSession?.controller?.transportControls?.sendCustomAction("SKIP_FORWARD", null)
                Log.d("MediaPlaybackService", "Forward skip action sent to MediaSession")
            }
            ACTION_SKIP_BACKWARD_SERVICE -> {
                mediaSession?.controller?.transportControls?.sendCustomAction("SKIP_BACKWARD", null)
                Log.d("MediaPlaybackService", "Backward skip action sent to MediaSession")
            }
            else -> {
                // Let MediaButtonReceiver handle standard media button actions
                // It's important that mediaSession is not null here
                mediaSession?.let { session ->
                    MediaButtonReceiver.handleIntent(session, intent)
                } ?: Log.w("MediaPlaybackService", "MediaSession is null, cannot handle intent: ${intent?.action}")
            }
        }
        
        return START_NOT_STICKY
    }

    // ...existing code...
}