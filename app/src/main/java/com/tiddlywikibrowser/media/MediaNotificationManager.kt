package com.tiddlywikibrowser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.tiddlywikibrowser.MainActivity
import com.tiddlywikibrowser.R

/**
 * Simplified media notification manager
 * Handles media playback notifications without the complexity of a foreground service
 */
class MediaNotificationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "MediaNotificationManager"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "media_playback"
    }
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Show or update media notification
     */
    fun showNotification(mediaSession: MediaSessionCompat, mediaInfo: SimpleMediaManager.MediaInfo) {
        try {
            if (!mediaInfo.isActive || mediaInfo.duration <= 0) {
                hideNotification()
                return
            }
            
            val notification = buildNotification(mediaSession, mediaInfo)
            notificationManager.notify(NOTIFICATION_ID, notification)
            
            Log.d(TAG, "Updated media notification: ${mediaInfo.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }
    }
    
    /**
     * Hide media notification
     */
    fun hideNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
            Log.d(TAG, "Media notification hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding notification", e)
        }
    }
    
    private fun buildNotification(mediaSession: MediaSessionCompat, mediaInfo: SimpleMediaManager.MediaInfo): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(mediaInfo.title ?: "Media")
            .setContentText(mediaInfo.artist ?: "TiddlyWiki")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        
        // Add content intent to open app
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(contentIntent)
        
        // Add media control actions
        addMediaActions(builder, mediaInfo.isPlaying)
        
        return builder.build()
    }
    
    private fun addMediaActions(builder: NotificationCompat.Builder, isPlaying: Boolean) {
        // Skip backward
        builder.addAction(
            R.drawable.ic_skip_backward_15,
            "Skip Backward",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
        )
        
        // Play/Pause
        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseAction = if (isPlaying) "Pause" else "Play"
        builder.addAction(
            playPauseIcon,
            playPauseAction,
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
        )
        
        // Skip forward
        builder.addAction(
            R.drawable.ic_skip_forward_15,
            "Skip Forward",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            )
        )
    }
}
