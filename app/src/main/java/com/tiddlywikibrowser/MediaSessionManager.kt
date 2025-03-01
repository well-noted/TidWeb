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
    private val mediaSession: MediaSessionCompat by lazy {
        MediaSessionCompat(context, "TidWebMediaSession").apply {
            setCallback(sessionCallback)
            isActive = true
        }
    }
    
    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.play()
        }
        
        override fun onPause() {
            (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.pause()
        }
        
        override fun onSeekTo(pos: Long) {
            (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.seekTo(pos)
        }
    }
    
    fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, position, 1.0f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .build()
        )
    }
    
    fun updateMetadata(title: String?, artist: String?, duration: Long) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "Unknown Title")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "Unknown Artist")
        
        if (duration > 0) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        }
        
        mediaSession.setMetadata(metadataBuilder.build())
    }
    
    fun release() {
        mediaSession.release()
    }
}