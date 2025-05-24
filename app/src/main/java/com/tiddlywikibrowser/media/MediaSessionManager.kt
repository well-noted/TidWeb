package com.tiddlywikibrowser.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.webkit.WebView
import com.tiddlywikibrowser.MediaPlaybackService
import com.tiddlywikibrowser.WebViewProvider

class MediaSessionManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "MediaSessionManager"
        
        @Volatile
        private var INSTANCE: MediaSessionManager? = null
        
        fun getInstance(context: Context): MediaSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaSessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private var mediaSession: MediaSessionCompat? = null
    private var webViewProvider: WebViewProvider? = null
    private var webView: WebView? = null
    private var serviceConnection: ServiceConnection? = null
    private var isServiceBound = false
    private var mediaPlaybackService: MediaPlaybackService? = null
    
    init {
        initializeMediaSession()
    }
    
    private fun initializeMediaSession() {
        try {
            mediaSession = MediaSessionCompat(context, "TiddlyWikiMediaSession").apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                
                val stateBuilder = PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
                    )
                setPlaybackState(stateBuilder.build())
                
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        webViewProvider?.executeJavascript("""
                            (function() {
                                const media = document.querySelector('audio,video');
                                if (media) media.play();
                            })();
                        """.trimIndent(), null)
                    }
                    
                    override fun onPause() {
                        webViewProvider?.executeJavascript("""
                            (function() {
                                const media = document.querySelector('audio,video');
                                if (media) media.pause();
                            })();
                        """.trimIndent(), null)
                    }
                    
                    override fun onSkipToNext() {
                        webViewProvider?.executeJavascript("if (window.skipForward) window.skipForward();", null)
                    }
                    
                    override fun onSkipToPrevious() {
                        webViewProvider?.executeJavascript("if (window.skipBackward) window.skipBackward();", null)
                    }
                    
                    override fun onSeekTo(pos: Long) {
                        webViewProvider?.executeJavascript("""
                            (function() {
                                const media = document.querySelector('audio,video');
                                if (media) media.currentTime = ${pos / 1000.0};
                            })();
                        """.trimIndent(), null)
                    }
                })
                
                isActive = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing media session", e)
        }
    }
    
    fun setWebViewProvider(provider: WebViewProvider) {
        this.webViewProvider = provider
    }
    
    fun setWebView(webView: WebView) {
        this.webView = webView
    }
    
    fun updateMetadata(title: String?, artist: String?, duration: Long, albumArt: Bitmap? = null) {
        Log.d(TAG, "updateMetadata called - title: $title, artist: $artist, duration: $duration")
        
        try {
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "Unknown Title")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "Unknown Artist")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            
            albumArt?.let {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            }
            
            mediaSession?.setMetadata(metadataBuilder.build())
            
            // Update service if connected
            mediaPlaybackService?.let { service ->
                // MediaPlaybackService doesn't have updateMetadata method
                // We'll let it handle metadata through the MediaSession
                Log.d(TAG, "Service connected, metadata will be handled through MediaSession")
            }
            
            Log.d(TAG, "Metadata updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating metadata", e)
        }
    }
    
    fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        Log.d(TAG, "updatePlaybackState called - isPlaying: $isPlaying, position: $position")
        
        try {
            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            val playbackSpeed = if (isPlaying) 1.0f else 0.0f
            
            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, position, playbackSpeed)
            
            mediaSession?.setPlaybackState(stateBuilder.build())
            
            // Update service if connected
            mediaPlaybackService?.let { service ->
                // Convert boolean to PlaybackStateCompat state
                val serviceState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                service.updatePlaybackState(serviceState, position)
            }
            
            Log.d(TAG, "Playback state updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating playback state", e)
        }
    }
    
    fun bindToService() {
        if (!isServiceBound) {
            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as? MediaPlaybackService.LocalBinder
                    mediaPlaybackService = binder?.service
                    isServiceBound = true
                    Log.d(TAG, "Connected to MediaPlaybackService")
                }
                
                override fun onServiceDisconnected(name: ComponentName?) {
                    mediaPlaybackService = null
                    isServiceBound = false
                    Log.d(TAG, "Disconnected from MediaPlaybackService")
                }
            }
            
            val intent = Intent(context, MediaPlaybackService::class.java)
            context.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        }
    }
    
    fun unbindFromService() {
        serviceConnection?.let {
            if (isServiceBound) {
                context.unbindService(it)
                isServiceBound = false
            }
        }
        serviceConnection = null
        mediaPlaybackService = null
    }
    
    fun isServiceBound(): Boolean = isServiceBound
    
    fun release() {
        Log.d(TAG, "Releasing MediaSessionManager")
        unbindFromService()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        webViewProvider = null
        webView = null
    }
    
    fun getMediaSession(): MediaSessionCompat? = mediaSession
} 