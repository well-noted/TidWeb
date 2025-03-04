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
import android.webkit.WebView
import android.util.Log

class MediaSessionManager(private val context: Context) {
    companion object {
        private const val TAG = "MediaSessionMgr"
    }
    
    private val mediaSession: MediaSessionCompat by lazy {
        MediaSessionCompat(context, "TidWebMediaSession").apply {
            setCallback(sessionCallback)
            isActive = true
        }
    }

    private var mediaPlaybackService: MediaPlaybackService? = null
    private var serviceConnection: ServiceConnection? = null
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentWebView: WebView? = null
    private var activeMediaElementId: String? = null
    private var isAudioFocusGranted = false
    
    // Audio focus callback
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Resume playback
                if (wasPlayingBeforeFocusLoss) {
                    (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.play()
                    executeMediaAction("play")
                    wasPlayingBeforeFocusLoss = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Lost focus for an unbounded amount of time: stop playback and release media player
                wasPlayingBeforeFocusLoss = (context as? MainActivity)?.exoPlayerManager?.isPlaying() == true
                if (wasPlayingBeforeFocusLoss) {
                    (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.pause()
                    executeMediaAction("pause")
                }
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Lost focus for a short time: pause playback
                wasPlayingBeforeFocusLoss = (context as? MainActivity)?.exoPlayerManager?.isPlaying() == true
                if (wasPlayingBeforeFocusLoss) {
                    (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.pause()
                    executeMediaAction("pause")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Lost focus for a short time, but we can duck (keep playing at lower volume)
                (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.volume = 0.3f
            }
        }
    }
    
    private var wasPlayingBeforeFocusLoss = false
    
    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            Log.d("MediaSession", "MediaSession onPlay")
            requestAudioFocus()
            if (isAudioFocusGranted) {
                (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.play()
                executeMediaAction("play")
                mediaPlaybackService?.updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, getCurrentPosition())
                
                // Ensure the service is started when playing
                (context as? MainActivity)?.startMediaService()
            }
        }
        
        override fun onPause() {
            Log.d("MediaSession", "MediaSession onPause")
            (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.pause()
            executeMediaAction("pause")
            mediaPlaybackService?.updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, getCurrentPosition())
        }
        
        override fun onSeekTo(pos: Long) {
            Log.d("MediaSession", "MediaSession onSeekTo: $pos")
            (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.seekTo(pos)
            executeMediaAction("seekTo", pos)
            mediaPlaybackService?.updatePlaybackState(
                if ((context as? MainActivity)?.exoPlayerManager?.isPlaying() == true) 
                    PlaybackStateCompat.STATE_PLAYING 
                else 
                    PlaybackStateCompat.STATE_PAUSED, 
                pos
            )
        }
        
        override fun onSkipToNext() {
            Log.d("MediaSession", "MediaSession onSkipToNext")
            // Used for skip forward functionality (15 seconds)
            val currentPosition = getCurrentPosition()
            val newPosition = currentPosition + 15000 // 15 seconds in milliseconds
            onSeekTo(newPosition)
        }
        
        override fun onSkipToPrevious() {
            Log.d("MediaSession", "MediaSession onSkipToPrevious")
            // Used for skip backward functionality (15 seconds)
            val currentPosition = getCurrentPosition()
            val newPosition = maxOf(0, currentPosition - 15000) // 15 seconds, not going below 0
            onSeekTo(newPosition)
        }
        
        override fun onStop() {
            Log.d("MediaSession", "MediaSession onStop")
            (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()?.stop()
            executeMediaAction("stop")
            mediaPlaybackService?.updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0)
            abandonAudioFocus()
        }
        
        override fun onCustomAction(action: String, extras: Bundle?) {
            Log.d("MediaSession", "MediaSession onCustomAction: $action")
            when (action) {
                "SKIP_FORWARD" -> onSkipToNext()
                "SKIP_BACKWARD" -> onSkipToPrevious()
            }
        }
    }
    
    /**
     * Request audio focus for media playback
     */
    private fun requestAudioFocus(): Boolean {
        isAudioFocusGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
                
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
                
            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        
        return isAudioFocusGranted
    }
    
    /**
     * Abandon audio focus when playback is complete or stopped
     */
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        isAudioFocusGranted = false
    }
    
    fun updatePlaybackState(isPlaying: Boolean, position: Long) {
        Log.d("MediaSession", "updatePlaybackState: isPlaying=$isPlaying, position=$position")
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(state, position, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            
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
        
        mediaSession.setPlaybackState(stateBuilder.build())
        
        // Make sure the session is active when playing
        if (isPlaying) {
            mediaSession.isActive = true
            // Make sure the service is started for foreground notification
            (context as? MainActivity)?.startMediaService()
        }
        
        // Update service with new state if it's bound
        mediaPlaybackService?.updatePlaybackState(state, position)
    }
    
    fun updateMetadata(title: String?, artist: String?, duration: Long, bitmap: Bitmap? = null) {
        Log.d("MediaSession", "updateMetadata: title=$title, duration=$duration")
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "Unknown Title")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "Unknown Artist")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "TiddlyWiki Player")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title ?: "Unknown Title")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist ?: "Unknown Artist")
        
        if (duration > 0) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
        }
        
        bitmap?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
        }
        
        val metadata = metadataBuilder.build()
        mediaSession.setMetadata(metadata)
        
        // Update service with new metadata if it's bound
        mediaPlaybackService?.let { service ->
            service.updateNotification(mediaSession, metadata, mediaSession.controller.playbackState, bitmap)
        }
    }
    
    fun bindToService() {
        Log.d("MediaSession", "bindToService called")
        if (serviceConnection == null) {
            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    Log.d("MediaSession", "onServiceConnected")
                    val service = (binder as? MediaPlaybackService.LocalBinder)?.service
                    mediaPlaybackService = service
                    service?.setMediaSession(mediaSession)
                    
                    // Immediately update playback state to ensure notification
                    (context as? MainActivity)?.let { activity ->
                        val isPlaying = activity.exoPlayerManager.isPlaying()
                        val position = activity.exoPlayerManager.getCurrentPosition()
                        updatePlaybackState(isPlaying, position)
                    }
                }
                
                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.d("MediaSession", "onServiceDisconnected")
                    mediaPlaybackService = null
                }
            }
            
            val intent = Intent(context, MediaPlaybackService::class.java)
            // Start the service first to ensure it's in foreground state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            // Then bind to it
            context.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        }
    }
    
    fun unbindFromService() {
        Log.d("MediaSession", "unbindFromService called")
        serviceConnection?.let {
            try {
                context.unbindService(it)
            } catch (e: Exception) {
                Log.e("MediaSession", "Error unbinding from service", e)
            }
            serviceConnection = null
            mediaPlaybackService = null
        }
    }
    
    /**
     * Sets the current WebView to be controlled
     */
    fun setWebView(webView: WebView) {
        Log.d("MediaSession", "setWebView called")
        currentWebView = webView
        injectMediaMonitoringScripts()
    }
    
    /**
     * Inject JavaScript code to monitor and control media elements in the WebView
     */
    private fun injectMediaMonitoringScripts() {
        currentWebView?.evaluateJavascript("""
            (function() {
                // Make sure we don't initialize this script multiple times
                if (window.tidWebMediaMonitorInitialized) return;
                window.tidWebMediaMonitorInitialized = true;
                
                // Store references to all audio and video elements
                window.tidWebActiveMediaElements = {};
                
                // Watch for all media elements and register listeners
                function setupMediaElement(element) {
                    const id = 'tid-media-' + Math.random().toString(36).substr(2, 9);
                    element.setAttribute('data-tid-media-id', id);
                    window.tidWebActiveMediaElements[id] = element;
                    
                    // Get the best possible title for the media
                    function getMediaTitle() {
                        let title = '';
                        
                        // First try to get title from closest tiddler title
                        try {
                            const tiddlerElement = element.closest('.tc-tiddler-frame');
                            if (tiddlerElement) {
                                const tiddlerTitle = tiddlerElement.querySelector('.tc-title');
                                if (tiddlerTitle && tiddlerTitle.textContent) {
                                    return tiddlerTitle.textContent.trim();
                                }
                            }
                        } catch(e) {}
                        
                        // Try to get track title from data attributes
                        try {
                            if (element.hasAttribute('data-title')) {
                                return element.getAttribute('data-title');
                            }
                            if (element.hasAttribute('title')) {
                                return element.getAttribute('title');
                            }
                            if (element.hasAttribute('aria-label')) {
                                return element.getAttribute('aria-label');
                            }
                        } catch(e) {}
                        
                        // Try to get filename from the src attribute
                        try {
                            if (element.src) {
                                const filename = element.src.split('/').pop();
                                if (filename) {
                                    // Remove the extension and replace underscores/hyphens with spaces
                                    return filename.split('.')[0].replace(/[_-]/g, ' ');
                                }
                            }
                        } catch(e) {}
                        
                        // If we're really stuck, use the page title
                        return document.title !== 'TiddlyWiki' ? document.title : 'Audio';
                    }
                    
                    // Add event listeners for media events
                    element.addEventListener('play', function() {
                        Android.onMediaEvent('play', id, this.currentTime, this.duration, this.getAttribute('src'), getMediaTitle());
                    });
                    
                    element.addEventListener('pause', function() {
                        Android.onMediaEvent('pause', id, this.currentTime, this.duration, this.getAttribute('src'), getMediaTitle());
                    });
                    
                    element.addEventListener('timeupdate', function() {
                        Android.onMediaEvent('timeupdate', id, this.currentTime, this.duration, this.getAttribute('src'), getMediaTitle());
                    });
                    
                    element.addEventListener('seeking', function() {
                        Android.onMediaEvent('seeking', id, this.currentTime, this.duration, this.getAttribute('src'), getMediaTitle());
                    });
                    
                    element.addEventListener('seeked', function() {
                        Android.onMediaEvent('seeked', id, this.currentTime, this.duration, this.getAttribute('src'), getMediaTitle());
                    });
                    
                    element.addEventListener('ended', function() {
                        Android.onMediaEvent('ended', id, this.currentTime, this.duration, this.getAttribute('src'), getMediaTitle());
                    });
                    
                    return id;
                }
                
                // Function to find all media elements
                function findAllMediaElements() {
                    const mediaElements = document.querySelectorAll('audio, video');
                    mediaElements.forEach(function(element) {
                        if (!element.hasAttribute('data-tid-media-id')) {
                            setupMediaElement(element);
                        }
                    });
                }
                
                // Custom functions for controlling media elements
                window.tidWebPlayMedia = function(id) {
                    const element = window.tidWebActiveMediaElements[id];
                    if (element) element.play();
                }
                
                window.tidWebPauseMedia = function(id) {
                    const element = window.tidWebActiveMediaElements[id];
                    if (element) element.pause();
                }
                
                window.tidWebSeekMedia = function(id, position) {
                    const element = window.tidWebActiveMediaElements[id];
                    if (element) element.currentTime = position;
                }
                
                window.tidWebSkipForward = function(id) {
                    const element = window.tidWebActiveMediaElements[id];
                    if (element) element.currentTime = Math.min(element.duration, element.currentTime + 15);
                }
                
                window.tidWebSkipBackward = function(id) {
                    const element = window.tidWebActiveMediaElements[id];
                    if (element) element.currentTime = Math.max(0, element.currentTime - 15);
                }
                
                // Watch for dynamically added media elements
                const observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        if (mutation.addedNodes) {
                            mutation.addedNodes.forEach(function(node) {
                                // Direct matches (if node is a media element)
                                if (node.nodeName === 'AUDIO' || node.nodeName === 'VIDEO') {
                                    if (!node.hasAttribute('data-tid-media-id')) {
                                        setupMediaElement(node);
                                    }
                                }
                                
                                // Check for media elements inside added node
                                if (node.querySelectorAll) {
                                    const mediaElements = node.querySelectorAll('audio, video');
                                    mediaElements.forEach(function(element) {
                                        if (!element.hasAttribute('data-tid-media-id')) {
                                            setupMediaElement(element);
                                        }
                                    });
                                }
                            });
                        }
                    });
                });
                
                // Initial scan for media elements
                findAllMediaElements();
                
                // Start observing the document for changes
                observer.observe(document.body, {
                    childList: true,
                    subtree: true
                });
                
                // Re-scan when the page has fully loaded
                window.addEventListener('load', findAllMediaElements);
                
                return "Media monitoring initialized";
            })();
        """, null)
    }
    
    /**
     * Execute a media action on the active media element in the WebView
     */
    fun executeMediaAction(action: String, position: Long = 0) {
        val id = activeMediaElementId ?: return
        val js = when (action) {
            "play" -> "window.tidWebPlayMedia('$id')"
            "pause" -> "window.tidWebPauseMedia('$id')"
            "seekTo" -> "window.tidWebSeekMedia('$id', ${position/1000.0})" // Convert ms to seconds
            "skipForward" -> "window.tidWebSkipForward('$id')"
            "skipBackward" -> "window.tidWebSkipBackward('$id')"
            "stop" -> "window.tidWebPauseMedia('$id')"
            else -> return
        }
        
        currentWebView?.evaluateJavascript(js, null)
    }
    
    /**
     * Called from the JavascriptInterface when a media event occurs
     */
    fun onMediaEvent(
        event: String, 
        elementId: String, 
        currentTime: Float, 
        duration: Float, 
        src: String?,
        title: String?
    ) {
        // Log media events without using the TAG to avoid errors
        Log.d("MediaSession", "onMediaEvent: $event, position=$currentTime, duration=$duration, title=$title")
        
        // Update the active media element ID
        activeMediaElementId = elementId
        
        val currentPositionMs = (currentTime * 1000).toLong()
        val durationMs = (duration * 1000).toLong()
        
        when (event) {
            "play" -> {
                // Request audio focus and update UI
                if (requestAudioFocus()) {
                    // Update media session and ExoPlayer
                    (context as? MainActivity)?.exoPlayerManager?.playMedia(src ?: "")
                    
                    // Get a meaningful title - use document title or source filename
                    val mediaTitle = when {
                        // Use the document title if it's not too generic
                        !title.isNullOrEmpty() && title != "TiddlyWiki" -> title
                        // Extract filename from source URL
                        !src.isNullOrEmpty() -> src.substringAfterLast('/').substringBeforeLast('.')
                        // Fallback
                        else -> "TiddlyWiki Audio"
                    }
                    
                    Log.d("MediaSession", "Setting media metadata: title=$mediaTitle, duration=$durationMs")
                    updateMetadata(mediaTitle, "TiddlyWiki Audio", durationMs)
                    updatePlaybackState(true, currentPositionMs)
                    
                    // Ensure service is started
                    (context as? MainActivity)?.startMediaService()
                }
            }
            "pause", "ended" -> {
                updatePlaybackState(false, currentPositionMs)
                if (event == "ended") {
                    abandonAudioFocus()
                }
            }
            "timeupdate" -> {
                // Update playback position periodically (not on every timeupdate event)
                if (currentPositionMs % 1000 < 50) { // Update roughly every second
                    updatePlaybackState(true, currentPositionMs)
                }
            }
            "seeking", "seeked" -> {
                updatePlaybackState(
                    (context as? MainActivity)?.exoPlayerManager?.isPlaying() == true,
                    currentPositionMs
                )
            }
        }
    }
    
    /**
     * Get the current media playback position
     */
    private fun getCurrentPosition(): Long {
        return (context as? MainActivity)?.exoPlayerManager?.getCurrentPosition() ?: 0
    }
    
    // Method renamed to avoid clash with the property getter
    fun retrieveMediaSession(): MediaSessionCompat {
        return mediaSession
    }
    
    fun release() {
        Log.d("MediaSession", "release called")
        abandonAudioFocus()
        unbindFromService()
        mediaSession.release()
    }
}