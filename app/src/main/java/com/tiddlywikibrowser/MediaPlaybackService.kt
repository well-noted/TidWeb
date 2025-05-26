package com.tiddlywikibrowser

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
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
    private var sessionTokenSet = false
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
        
        // Show an initial notification to keep the service alive
        startForeground(NOTIFICATION_ID, createInitialNotification())
        isForegroundService = true // Set to true since we're starting as foreground
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
            
            // Only set the session token if it hasn't been set already
            if (!sessionTokenSet) {
                try {
                    sessionToken = session.sessionToken
                    sessionTokenSet = true
                    Log.d("MediaPlaybackService", "Session token set successfully")
                } catch (e: Exception) {
                    Log.e("MediaPlaybackService", "Error setting session token", e)
                }
            } else {
                Log.d("MediaPlaybackService", "Session token already set, not setting again")
            }

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
        try {
            synchronized(this) {
                Log.d("MediaPlaybackService", "updatePlaybackState CALLED - State: $state, Position: $position. Current mediaSession: ${mediaSession?.sessionToken}")
                val stateBuilder = PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND
                    )
                    .setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f)

                // Add custom actions for skip forward/backward
                stateBuilder.addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder("SKIP_FORWARD", "Skip Forward", R.drawable.ic_skip_forward_15)
                        .build()
                )
                stateBuilder.addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder("SKIP_BACKWARD", "Skip Backward", R.drawable.ic_skip_backward_15)
                        .build()
                )
                
                // When in the playing state, make sure the service is in foreground
                if (state == PlaybackStateCompat.STATE_PLAYING) {
                    if (!isForegroundService) {
                        // If there's a mediaSession, use it to create a notification
                        mediaSession?.let { session ->
                            Log.d("MediaPlaybackService", "Making service foreground during playback")
                            val metadata = session.controller.metadata
                            val notification = updateNotification(session, metadata, stateBuilder.build())
                            
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                            } else {
                                startForeground(NOTIFICATION_ID, notification)
                            }
                            isForegroundService = true
                        }
                    } else {
                        // Update the notification with new playback state
                        mediaSession?.let { session ->
                            val metadata = session.controller.metadata
                            updateNotification(session, metadata, stateBuilder.build())
                        }
                    }
                } 
                // In paused state, only update the notification but keep the service foreground
                else if (state == PlaybackStateCompat.STATE_PAUSED) {
                    mediaSession?.let { session ->
                        val metadata = session.controller.metadata
                        updateNotification(session, metadata, stateBuilder.build())
                    }
                }
                
                // Apply the state to the MediaSession
                val newState = stateBuilder.build()
                mediaSession?.setPlaybackState(newState)
                playbackState = newState
                
                // Make sure the session is active
                if (mediaSession?.isActive != true) {
                    mediaSession?.isActive = true
                    Log.d("MediaPlaybackService", "Activated media session during state update")
                }
            }
        } catch (e: Exception) {
            Log.e("MediaPlaybackService", "Error updating playback state", e)
        }
    }

    fun updateNotification(
        mediaSession: MediaSessionCompat,
        metadata: MediaMetadataCompat?,
        state: PlaybackStateCompat?,
        bitmap: Bitmap? = null
    ): Notification {
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
                return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID).build() // Don't show a notification if media is not effectively active
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
                
                // Only update if we're already foreground, otherwise a startForeground call will be made elsewhere
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)
                Log.d("MediaPlaybackService", "updateNotification - Updated existing notification.")
                return notification
            } catch (e: Exception) {
                Log.e("MediaPlaybackService", "Error building or displaying notification: ${e.message}", e)
                // Return a basic notification if we hit an error
                return createInitialNotification()
            }
        }
    }

    private fun createBasicNotificationBuilder(
        mediaSession: MediaSessionCompat,
        metadata: MediaMetadataCompat?,
        state: PlaybackStateCompat?,
        bitmap: Bitmap? = null
    ): NotificationCompat.Builder {
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Media" // More generic default
        val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "TiddlyWiki"
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken) // this.mediaSession should be valid here due to assignment above
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
        
        // Make sure we're running as a foreground service
        if (!isForegroundService && mediaSession != null) {
            try {
                startForeground(NOTIFICATION_ID, createInitialNotification())
                isForegroundService = true
                Log.d("MediaPlaybackService", "Started as foreground service from onStartCommand")
            } catch (e: Exception) {
                Log.e("MediaPlaybackService", "Failed to start as foreground service", e)
            }
        }
        
        // Return START_STICKY to ensure the service is restarted if it's killed
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("MediaPlaybackService", "onDestroy: Cleaning up resources")
        
        try {
            // Clear the media session
            mediaSession?.let { session ->
                session.isActive = false
                session.release()
                mediaSession = null
            }
            
            // Stop foreground service and remove notification
            stopForeground(true)
            stopSelf()
            
            // Clear callbacks
            mediaPlayerCallback = null
            
            Log.d("MediaPlaybackService", "onDestroy: Cleanup complete")
        } catch (e: Exception) {
            Log.e("MediaPlaybackService", "Error during onDestroy", e)
        }
        
        super.onDestroy()
    }
}