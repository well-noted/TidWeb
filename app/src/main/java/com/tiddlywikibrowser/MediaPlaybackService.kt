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
import com.tiddlywikibrowser.media.MediaSessionManager

class MediaPlaybackService : MediaBrowserServiceCompat() {
    private var sessionTokenSet = false
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "media_playback"
        private const val SEEK_INTERVAL = 15000L // 15 seconds in milliseconds
        const val ACTION_SKIP_FORWARD_SERVICE = "com.tiddlywikibrowser.ACTION_SKIP_FORWARD_SERVICE"
        const val ACTION_SKIP_BACKWARD_SERVICE = "com.tiddlywikibrowser.ACTION_SKIP_BACKWARD_SERVICE"
    }    private var mediaSession: MediaSessionCompat? = null
    private var playbackState: PlaybackStateCompat? = null
    private var mediaPlayerCallback: MediaPlayerCallback? = null
    private var isForegroundService = false
    private var isTaskRemoved = false // Track if app was dismissed from recent apps

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
    }    fun setMediaSession(session: MediaSessionCompat) {
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

            // Set up the session callback to handle media controls in the service
            mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d("MediaPlaybackService", "Service MediaSession callback: onPlay")
                    mediaPlayerCallback?.onPlay()
                }
                
                override fun onPause() {
                    Log.d("MediaPlaybackService", "Service MediaSession callback: onPause")
                    mediaPlayerCallback?.onPause()
                }
                
                override fun onSkipToNext() {
                    Log.d("MediaPlaybackService", "Service MediaSession callback: onSkipToNext")
                    mediaPlayerCallback?.onSkipForward()
                }
                
                override fun onSkipToPrevious() {
                    Log.d("MediaPlaybackService", "Service MediaSession callback: onSkipToPrevious")
                    mediaPlayerCallback?.onSkipBackward()
                }
                
                override fun onSeekTo(pos: Long) {
                    Log.d("MediaPlaybackService", "Service MediaSession callback: onSeekTo $pos")
                    mediaPlayerCallback?.onSeekTo(pos)
                }
                
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    Log.d("MediaPlaybackService", "Service MediaSession callback: onCustomAction $action")
                    when (action) {
                        "SKIP_FORWARD" -> mediaPlayerCallback?.onSkipForward()
                        "SKIP_BACKWARD" -> mediaPlayerCallback?.onSkipBackward()
                    }
                }
            })

            Log.d("MediaPlaybackService", "MediaSession set in service with callback. Notification will be updated by explicit calls from MediaSessionManager.")
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
                // In paused state, keep the service foreground but update the notification
                else if (state == PlaybackStateCompat.STATE_PAUSED) {
                    if (isForegroundService) {
                        mediaSession?.let { session ->
                            val metadata = session.controller.metadata
                            updateNotification(session, metadata, stateBuilder.build())
                        }
                    } else {
                        // If not foreground but we have paused media, make it foreground
                        mediaSession?.let { session ->
                            val metadata = session.controller.metadata
                            val notification = updateNotification(session, metadata, stateBuilder.build())
                            
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                            } else {
                                startForeground(NOTIFICATION_ID, notification)
                            }
                            isForegroundService = true
                        }
                    }
                }
                // In stopped state, we can stop foreground but keep service running
                else if (state == PlaybackStateCompat.STATE_STOPPED) {
                    if (isForegroundService) {
                        stopForeground(true) // Remove notification
                        isForegroundService = false
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
    }    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MediaPlaybackService", "onStartCommand received action: ${intent?.action}")
          // Check persistent flag to see if app was previously dismissed
        try {
            if (MainApplication.wasTaskRemoved(this)) {
                Log.d("MediaPlaybackService", "App was previously dismissed, stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: Exception) {
            Log.e("MediaPlaybackService", "Error checking task removal flag", e)
        }
        
        // If task was removed, don't process any commands and stop the service
        if (isTaskRemoved) {
            Log.d("MediaPlaybackService", "Service marked for removal, stopping immediately")
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Reset the task removed flag if service is being started normally
        if (intent?.action == null) {
            isTaskRemoved = false
        }
        
        when (intent?.action) {
            ACTION_SKIP_FORWARD_SERVICE -> {
                Log.d("MediaPlaybackService", "Processing skip forward action")
                mediaSession?.controller?.transportControls?.sendCustomAction("SKIP_FORWARD", null)
                // Also call the callback directly in case session is not responding                mediaPlayerCallback?.onSkipForward()
            }
            ACTION_SKIP_BACKWARD_SERVICE -> {
                Log.d("MediaPlaybackService", "Processing skip backward action")
                mediaSession?.controller?.transportControls?.sendCustomAction("SKIP_BACKWARD", null)
                // Also call the callback directly in case session is not responding
                mediaPlayerCallback?.onSkipBackward()
            }
            else -> {
                // Let MediaButtonReceiver handle standard media button actions
                // It's important that mediaSession is not null here
                mediaSession?.let { session ->
                    try {
                        MediaButtonReceiver.handleIntent(session, intent)
                        Log.d("MediaPlaybackService", "MediaButtonReceiver processed intent for action: ${intent?.action}")
                    } catch (e: Exception) {
                        Log.e("MediaPlaybackService", "Error handling media button intent", e)
                        // If MediaButtonReceiver fails, try to handle it ourselves
                        if (intent?.action == "android.intent.action.MEDIA_BUTTON") {
                            // Handle media button directly
                            Log.d("MediaPlaybackService", "Handling media button directly")
                        }
                        else {
                            
                        }
                    }
                } ?: Log.w("MediaPlaybackService", "MediaSession is null, cannot handle intent: ${intent?.action}")
            }
        }
        
        // Make sure we're running as a foreground service when we have media
        if (!isForegroundService && mediaSession != null) {
            try {
                val metadata = mediaSession?.controller?.metadata
                val playbackState = mediaSession?.controller?.playbackState
                
                // Only start foreground if we have valid media or are playing
                if (metadata != null && (playbackState?.state == PlaybackStateCompat.STATE_PLAYING || 
                    playbackState?.state == PlaybackStateCompat.STATE_PAUSED)) {
                    val notification = updateNotification(mediaSession!!, metadata, playbackState)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    isForegroundService = true
                    Log.d("MediaPlaybackService", "Started as foreground service from onStartCommand")
                } else {
                    // Start with initial notification if we don't have metadata yet
                    startForeground(NOTIFICATION_ID, createInitialNotification())
                    isForegroundService = true
                    Log.d("MediaPlaybackService", "Started as foreground service with initial notification")
                }
            } catch (e: Exception) {
                Log.e("MediaPlaybackService", "Failed to start as foreground service", e)
            }        }
        
        // Return START_NOT_STICKY if app was dismissed from recent apps to prevent restart
        // Otherwise return START_STICKY to ensure the service is restarted if it's killed
        return if (isTaskRemoved) {
            Log.d("MediaPlaybackService", "Returning START_NOT_STICKY due to task removal")
            START_NOT_STICKY
        } else {
            START_STICKY
        }
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
    }    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("MediaPlaybackService", "onTaskRemoved: App removed from recent apps, cleaning up")
        
        // Set flag to prevent service restart
        isTaskRemoved = true
          // Store persistent flag to prevent restart when app is relaunched
        try {
            MainApplication.markTaskRemoved(this)
        } catch (e: Exception) {
            Log.e("MediaPlaybackService", "Error storing task removal flag", e)
        }
        
        try {
            // Release the MediaSessionManager singleton to ensure complete cleanup
            try {
                MediaSessionManager.getInstance(this).release()
                Log.d("MediaPlaybackService", "MediaSessionManager released from onTaskRemoved")
            } catch (e: Exception) {
                Log.e("MediaPlaybackService", "Error releasing MediaSessionManager", e)
            }
            
            // Clear the media session and stop playback
            mediaSession?.let { session ->
                session.isActive = false
                // Set playback state to stopped
                val stoppedState = PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f)
                    .build()
                session.setPlaybackState(stoppedState)
            }
              // Stop foreground service and remove notification immediately
            if (isForegroundService) {
                stopForeground(true) // true = remove notification
                isForegroundService = false
            }
            
            // Also explicitly cancel the notification to ensure it's removed
            try {
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)
                notificationManager.cancelAll() // Cancel any other notifications from this service
                Log.d("MediaPlaybackService", "Explicitly cancelled all notifications")
            } catch (e: Exception) {
                Log.e("MediaPlaybackService", "Error cancelling notifications", e)
            }// Clear callbacks to prevent any further interaction
            mediaPlayerCallback = null
              // Stop the service completely - don't restart
            stopSelf()
            
            Log.d("MediaPlaybackService", "onTaskRemoved: Service stopped and notification removed")
            
            // Force process termination to ensure complete cleanup
            try {
                Log.d("MediaPlaybackService", "Force terminating process from service onTaskRemoved")
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (e: Exception) {
                Log.e("MediaPlaybackService", "Error terminating process from service", e)
            }
        } catch (e: Exception) {
            Log.e("MediaPlaybackService", "Error during onTaskRemoved", e)
            // Even if there's an error, try to stop the service
            try {
                stopSelf()
            } catch (ex: Exception) {
                Log.e("MediaPlaybackService", "Failed to stop service in error handler", ex)
            }
        }
        
        super.onTaskRemoved(rootIntent)
    }
}