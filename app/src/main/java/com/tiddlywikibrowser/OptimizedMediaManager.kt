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
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import android.webkit.WebView

/**
 * Optimized and simplified media manager that consolidates all media functionality
 * This replaces both MediaSessionManager and ExoPlayerManager with a single, efficient solution
 */
class OptimizedMediaManager private constructor(private val context: Context) {
    
    private var mediaSession: MediaSessionCompat? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    
    // Simplified state management
    private var isPlaying = false
    private var currentPosition: Long = 0
    private var hasActiveMedia = false
    private var currentTitle: String? = null
    private var currentDuration: Long = 0
    
    // Service management - lightweight
    private var playbackService: MediaPlaybackService? = null
    private var isServiceBound = false
    
    // WebView reference for direct control
    private var webView: WebView? = null
    
    companion object {
        private const val TAG = "OptimizedMediaManager"
        
        @Volatile
        private var INSTANCE: OptimizedMediaManager? = null
        
        fun getInstance(context: Context): OptimizedMediaManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OptimizedMediaManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    init {
        setupMediaSession()
        startService()
    }
    
    private fun setupMediaSession() {
        try {
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 0, mediaButtonIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) 
                    android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )
            
            mediaSession = MediaSessionCompat(context, "TidWebMedia").apply {
                setCallback(MediaSessionCallback())
                isActive = true
                setMediaButtonReceiver(pendingIntent)
                setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or 
                        MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
            }
            
            Log.d(TAG, "Media session created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup media session", e)
        }
    }
    
    private fun startService() {
        if (isServiceBound) return
        
        val intent = Intent(context, MediaPlaybackService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
        }
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackService = (service as? MediaPlaybackService.LocalBinder)?.service
            isServiceBound = true
            mediaSession?.let { playbackService?.setMediaSession(it) }
            Log.d(TAG, "Service connected")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
            Log.d(TAG, "Service disconnected")
        }
    }
    
    /**
     * Set WebView reference for direct media control
     */
    fun setWebView(webView: WebView?) {
        this.webView = webView
        if (webView != null) {
            // Inject optimized media functionality
            injectMediaScript(webView)
        }
    }
    
    /**
     * Streamlined media script injection
     */
    private fun injectMediaScript(webView: WebView) {
        webView.evaluateJavascript("""
            if (!window.OptimizedMedia) {
                window.OptimizedMedia = {
                    currentMedia: null,
                    
                    // Find and control active media
                    play() { 
                        const media = this.findMedia();
                        if (media?.paused) media.play().catch(() => {});
                    },
                    
                    pause() {
                        const media = this.findMedia(); 
                        if (media && !media.paused) media.pause();
                    },
                    
                    seekTo(ms) {
                        const media = this.findMedia();
                        if (media) media.currentTime = ms / 1000;
                    },
                    
                    skip(seconds) {
                        const media = this.findMedia();
                        if (media) {
                            media.currentTime = Math.max(0, 
                                Math.min(media.duration || 0, media.currentTime + seconds));
                        }
                    },
                    
                    findMedia() {
                        return this.currentMedia || 
                               document.querySelector('audio:not([paused]), video:not([paused])') ||
                               document.querySelector('audio, video, .tw-audio-element');
                    },
                    
                    updateAndroid(media) {
                        if (!media || !window.Android) return;
                        const title = media.closest('[data-tiddler-title]')?.getAttribute('data-tiddler-title') || 'TiddlyWiki Media';
                        try {
                            window.Android.onMediaStateChange(
                                title, 'TiddlyWiki',
                                Math.round((media.duration || 0) * 1000),
                                Math.round((media.currentTime || 0) * 1000),
                                !media.paused
                            );
                        } catch(e) {}
                    },
                    
                    // Auto-setup new media elements
                    setupMedia(media) {
                        if (media.dataset.optimizedSetup) return;
                        media.dataset.optimizedSetup = 'true';
                        
                        ['play', 'pause', 'ended', 'timeupdate'].forEach(evt => {
                            media.addEventListener(evt, (e) => {
                                if (evt === 'play') this.currentMedia = media;
                                if (evt === 'ended') this.currentMedia = null;
                                if (evt !== 'timeupdate' || Date.now() - (media.lastUpdate || 0) > 1000) {
                                    this.updateAndroid(media);
                                    if (evt === 'timeupdate') media.lastUpdate = Date.now();
                                }
                            }, { passive: true });
                        });
                    }
                };
                
                // Auto-detect and setup media
                const observer = new MutationObserver(() => {
                    document.querySelectorAll('audio, video, .tw-audio-element').forEach(m => 
                        window.OptimizedMedia.setupMedia(m));
                });
                
                observer.observe(document.body, { childList: true, subtree: true });
                document.querySelectorAll('audio, video, .tw-audio-element').forEach(m => 
                    window.OptimizedMedia.setupMedia(m));
            }
        """.trimIndent(), null)
    }
    
    /**
     * Update media metadata efficiently
     */
    fun updateMetadata(title: String?, duration: Long) {
        if (title == currentTitle && duration == currentDuration) return // Skip if no change
        
        currentTitle = title
        currentDuration = duration
        hasActiveMedia = title != null && duration > 0
        
        if (hasActiveMedia) {
            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "TiddlyWiki Media")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "TiddlyWiki")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build()
                
            mediaSession?.setMetadata(metadata)
            mediaSession?.isActive = true
            
            playbackService?.updateNotification(mediaSession!!, metadata, 
                mediaSession?.controller?.playbackState, null)
        }
    }
      /**
     * Enhanced playback state update with background reliability
     */
    fun updatePlaybackState(playing: Boolean, position: Long) {
        if (isPlaying == playing && Math.abs(currentPosition - position) < 1000) return
        
        Log.d(TAG, "Updating playback state: playing=$playing, position=$position")
        
        isPlaying = playing
        currentPosition = position
        
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or 
                       PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                       PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP)
            .setState(state, position, if (playing) 1f else 0f)
            .build()
            
        mediaSession?.setPlaybackState(playbackState)
        
        if (playing) {
            requestAudioFocus()
            // Ensure service is running for background playback
            if (!isServiceBound) {
                startService()
            }
            playbackService?.updateNotification(mediaSession!!, 
                mediaSession?.controller?.metadata, playbackState, null)
        } else {
            abandonAudioFocus()
        }
        
        // If we don't have a valid WebView reference, try to refresh it
        if (webView == null) {
            refreshWebViewReference()
        }
    }
    
    /**
     * Refresh WebView reference for background playback
     */
    fun refreshWebViewReference() {
        try {
            // Try to get fresh WebView reference from MainActivity
            val mainActivity = context as? MainActivity
            if (mainActivity != null) {
                val currentWebView = mainActivity.getCurrentWebView()
                if (currentWebView != null && currentWebView != webView) {
                    Log.d(TAG, "Updating WebView reference for background playback")
                    setWebView(currentWebView)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh WebView reference", e)
        }
    }
    
    /**
     * Force sync state for background scenarios
     */
    fun forceSyncState() {
        try {
            executeWebViewCommand("updateState(window.MediaInterface.activeElement)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force sync state", e)
        }
    }
    
    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setOnAudioFocusChangeListener(::handleAudioFocusChange)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(::handleAudioFocusChange,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }
    
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(::handleAudioFocusChange)
        }
    }
    
    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> executeWebViewCommand("pause")
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> executeWebViewCommand("pause")
            AudioManager.AUDIOFOCUS_GAIN -> if (isPlaying) executeWebViewCommand("play")
        }
    }
      private fun executeWebViewCommand(command: String) {
        try {
            val currentWebView = webView
            if (currentWebView != null) {
                // Check if WebView is still valid
                currentWebView.settings // This will throw if destroyed
                
                Log.d(TAG, "Executing WebView command: $command")
                currentWebView.evaluateJavascript("window.MediaInterface?.$command?.()", null)
            } else {
                Log.w(TAG, "No WebView available for command: $command")
                // Try to find active WebView through MainActivity
                tryExecuteViaMainActivity(command)
            }        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute WebView command: $command", e)
            // Fallback to MainActivity approach
            tryExecuteViaMainActivity(command)
        }
    }
    
    private fun tryExecuteViaMainActivity(command: String) {
        try {
            val mainActivity = context as? MainActivity
            val currentWebView = mainActivity?.getCurrentWebView()
            if (currentWebView != null) {
                Log.d(TAG, "Executing command via MainActivity: $command")
                currentWebView.evaluateJavascript("window.MediaInterface?.$command?.()", null)
            } else {
                Log.w(TAG, "No active WebView found via MainActivity for command: $command")
            }        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute via MainActivity: $command", e)
        }
    }
    
    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            Log.d(TAG, "MediaSession.onPlay() - Background play requested")
            isPlaying = true
            updatePlaybackState(true, currentPosition)
            requestAudioFocus()
            executeWebViewCommand("play")
        }
        
        override fun onPause() {
            Log.d(TAG, "MediaSession.onPause() - Background pause requested")
            isPlaying = false
            updatePlaybackState(false, currentPosition)
            executeWebViewCommand("pause")
        }
        
        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "MediaSession.onSeekTo($pos) - Background seek requested")
            currentPosition = pos
            executeWebViewCommand("seekTo($pos)")
            updatePlaybackState(isPlaying, pos)
        }
        
        override fun onSkipToNext() {
            Log.d(TAG, "MediaSession.onSkipToNext() - Background skip forward")
            executeWebViewCommand("skipForward")
        }
        
        override fun onSkipToPrevious() {
            Log.d(TAG, "MediaSession.onSkipToPrevious() - Background skip backward")
            executeWebViewCommand("skipBackward")
        }
        
        override fun onStop() {
            Log.d(TAG, "MediaSession.onStop() - Background stop requested")
            isPlaying = false
            hasActiveMedia = false
            mediaSession?.isActive = false
            executeWebViewCommand("pause")
            updatePlaybackState(false, 0)
        }
    }
    
    fun release() {
        mediaSession?.release()
        if (isServiceBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding service", e)
            }
        }
        abandonAudioFocus()
        INSTANCE = null
    }
}