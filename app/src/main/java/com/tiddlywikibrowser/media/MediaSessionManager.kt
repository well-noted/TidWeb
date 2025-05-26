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
import com.tiddlywikibrowser.R
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
            // Check if we already have an active media session
            if (mediaSession != null && mediaSession?.isActive == true) {
                Log.d(TAG, "Media session already initialized and active")
                return
            }
            
            Log.d(TAG, "Initializing new media session")
            
            // Create a new media session
            mediaSession = MediaSessionCompat(context, "TiddlyWikiMediaSession").apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                    MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS
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
                
                // Bind to the service first before setting the session
                // This avoids timing issues with token setting
                bindToService()
                
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing media session", e)
        }
    }
    
    fun setWebViewProvider(provider: WebViewProvider) {
        webViewProvider = provider
    }
    
    fun setWebView(webView: WebView) {
        this.webView = webView
    }
    
    fun updateMetadata(title: String?, artist: String?, duration: Long, albumArt: Bitmap? = null) {
        try {
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "Unknown Title")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "Unknown Artist")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "TiddlyWiki Media")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                
            if (albumArt != null) {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
            }
            
            val metadata = metadataBuilder.build()
            mediaSession?.setMetadata(metadata)
            
            // Update service metadata if connected
            if (isServiceBound && mediaPlaybackService != null) {
                // Service will handle notification update through MediaSession
                Log.d(TAG, "Media metadata updated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating metadata", e)
        }
    }
    
    fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        try {
            val stateActions = PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND
            
            val state = if (isPlaying) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }
            
            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(stateActions)
                .setState(state, position, 1.0f)
                // Add custom actions for skip forward/backward
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder("SKIP_FORWARD", "Skip Forward", R.drawable.ic_skip_forward_15).build()
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder("SKIP_BACKWARD", "Skip Backward", R.drawable.ic_skip_backward_15).build()
                )
            
            val newState = stateBuilder.build()
            mediaSession?.setPlaybackState(newState)
            
            // Make sure the session is active
            if (mediaSession?.isActive != true) {
                mediaSession?.isActive = true
                Log.d(TAG, "Activated media session")
            }
            
            // Update the service state if bound
            if (isServiceBound && mediaPlaybackService != null) {
                mediaPlaybackService?.updatePlaybackState(state, position)
            } else if (!isServiceBound) {
                // If we're not bound to the service, try binding now
                bindToService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating playback state", e)
        }
    }
    
    fun bindToService() {
        // Only proceed if we don't have an active service connection
        if (serviceConnection != null && isServiceBound) {
            Log.d(TAG, "Service connection already exists and is bound, not binding again")
            return
        }
        
        Log.d(TAG, "Creating new service connection")
        
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? MediaPlaybackService.LocalBinder
                mediaPlaybackService = binder?.service
                isServiceBound = true
                Log.d(TAG, "Service connected successfully")
                
                // Set callback to receive media commands
                mediaPlaybackService?.setCallback(object : MediaPlaybackService.MediaPlayerCallback {
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
                    
                    override fun onSeekTo(pos: Long) {
                        webViewProvider?.executeJavascript("""
                            (function() {
                                const media = document.querySelector('audio,video');
                                if (media) media.currentTime = ${pos / 1000.0};
                            })();
                        """.trimIndent(), null)
                    }
                    
                    override fun onSkipForward() {
                        webViewProvider?.executeJavascript("""
                            (function() {
                                if (window.skipForward) {
                                    window.skipForward();
                                } else {
                                    const media = document.querySelector('audio,video');
                                    if (media) media.currentTime = Math.min(media.duration, media.currentTime + 15);
                                }
                            })();
                        """.trimIndent(), null)
                    }
                    
                    override fun onSkipBackward() {
                        webViewProvider?.executeJavascript("""
                            (function() {
                                if (window.skipBackward) {
                                    window.skipBackward();
                                } else {
                                    const media = document.querySelector('audio,video');
                                    if (media) media.currentTime = Math.max(0, media.currentTime - 15);
                                }
                            })();
                        """.trimIndent(), null)
                    }
                })
                
                // Initialize media session with service only if we have a valid session
                if (mediaSession != null && mediaSession?.isActive == true) {
                    try {
                        Log.d(TAG, "Setting media session in service after connection")
                        mediaPlaybackService?.setMediaSession(mediaSession!!)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting media session in service", e)
                    }
                } else {
                    Log.d(TAG, "No active media session to set in service")
                }
                
                Log.d(TAG, "Connected to MediaPlaybackService")
            }
            
            override fun onServiceDisconnected(name: ComponentName?) {
                mediaPlaybackService = null
                isServiceBound = false
                Log.d(TAG, "Disconnected from MediaPlaybackService")
            }
        }
        
        // Only proceed with binding if we have a service connection
        if (serviceConnection != null) {
            try {
                // Start and bind to the service
                val intent = Intent(context, MediaPlaybackService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                val boundSuccessfully = context.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
                Log.d(TAG, "Service binding initiated, result: $boundSuccessfully")
                
                if (!boundSuccessfully) {
                    // If binding failed, try starting the service again and rebinding
                    context.startService(intent)
                    context.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
                    Log.d(TAG, "Second attempt to bind service")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error binding to service", e)
                isServiceBound = false
            }
        } else {
            Log.e(TAG, "Cannot bind to service: serviceConnection is null")
        }
    }
    
    fun unbindFromService() {
        // Don't stop service here, let it run in background
        // Only unbind the service connection
        serviceConnection?.let {
            if (isServiceBound) {
                try {
                    context.unbindService(it)
                    isServiceBound = false
                } catch (e: Exception) {
                    Log.e(TAG, "Error unbinding service", e)
                }
            }
        }
        serviceConnection = null
    }
    
    /**
     * Check if the media playback service is currently bound
     * @return true if the service is bound, false otherwise
     */
    fun isServiceBound(): Boolean {
        return isServiceBound
    }

    fun release() {
        try {
            // Stop any ongoing playback
            updatePlaybackState(false, 0)
            
            // Unbind from service
            unbindFromService()
            
            // Release media session
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
            
            // Stop the service if no media is playing
            val stopIntent = Intent(context, MediaPlaybackService::class.java)
            context.stopService(stopIntent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaSessionManager", e)
        } finally {
            // Ensure cleanup in case of any errors
            try {
                unbindFromService()
                mediaSession?.apply {
                    isActive = false
                    release()
                }
                mediaSession = null
                webViewProvider = null
                webView = null
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup in release()", e)
            }
        }
    }
    
    fun getMediaSession(): MediaSessionCompat? = mediaSession
}
