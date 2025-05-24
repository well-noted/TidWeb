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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import com.tiddlywikibrowser.util.ThreadManager

private const val TAG = "MediaSessionManager"

class MediaSessionManager private constructor(private val context: Context) {
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isPlaying = false
    private var currentPosition: Long = 0
    private var hasActiveMedia = false
    private var playbackService: MediaPlaybackService? = null
    private var currentBitmap: Bitmap? = null
    private var isActive = false
    private var currentMetadata: MediaMetadataCompat? = null
    private var wasPlayingBeforeFocusLoss = false
    private var lastPlayTimestamp: Long = 0
    private var isServiceBound = false
    private var currentPlaybackState: PlaybackStateCompat? = null
    private var webViewProvider: WebViewProvider? = null // Add webViewProvider variable
    private var sessionToken: MediaSessionCompat.Token? = null

    private var lastUserActionTimestamp: Long = 0
    private val USER_ACTION_DEBOUNCE_MS: Long = 500 // 0.5 seconds, moved from const val

    private val stateChangeLock = Object()
    private var gainCallbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingGainCallback: Runnable? = null
    private var pendingLossTransientCallback: Runnable? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MediaPlaybackService.LocalBinder
            playbackService = binder?.service
            isServiceBound = true
            Log.d(TAG, "Service connected")
            mediaSession?.let {
                session -> playbackService?.setMediaSession(session)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: MediaSessionManager? = null

        fun getInstance(context: Context): MediaSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaSessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        android.util.Log.d(TAG, "MediaSessionManager: init block started.")
        Log.d(TAG, "Initializing MediaSessionManager")
        try {
            setupMediaSession()
            startPlaybackService()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaSessionManager", e)
            // Don't rethrow - we want to avoid crashing the app
        }
        android.util.Log.d(TAG, "MediaSessionManager: init block FINISHED. Instance hashCode: ${this.hashCode()}")
    }

    private fun setupMediaSession() {
        Log.d(TAG, "Setting up media session")
        try {
            // Use the MEDIA_BUTTON intent as the PendingIntent for the media session
            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                setClass(context, androidx.media.session.MediaButtonReceiver::class.java)
                Log.d(TAG, "Created media button intent with class: ${component?.className}")
            }

            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                mediaButtonIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )

            val componentName = ComponentName(context, MediaPlaybackService::class.java)
            Log.d(TAG, "Creating MediaSession with component: ${componentName.className}")
            
            mediaSession = MediaSessionCompat(context, "TiddlyWikiMediaSession", componentName, pendingIntent).apply {
                Log.d(TAG, "MediaSession created, setting up callbacks")
                val callback = MediaSessionCallback()
                setCallback(callback)
                Log.d(TAG, "MediaSession callback set: $callback")

                // Set flags for media session
                val flags = MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
                          MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                setFlags(flags)
                Log.d(TAG, "MediaSession flags set: $flags")

                // This is important to make the session active from the start
                isActive = true
                Log.d(TAG, "MediaSession isActive set to: $isActive")

                // Set media button receiver explicitly
                setMediaButtonReceiver(pendingIntent)
                Log.d(TAG, "Media button receiver set with pending intent")
            }

            // Media session is active by default
            // Force update the service with this session
            playbackService?.setMediaSession(mediaSession!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupMediaSession", e)
            // Don't rethrow - we want the manager to still be instantiated
        }
        sessionToken = mediaSession?.sessionToken
        Log.d(TAG, "MediaSession created. IsActive: ${mediaSession?.isActive}")
    }

    private fun startPlaybackService() {
        Log.d(TAG, "Starting and binding to playback service")
        
        if (isServiceBound && playbackService != null) {
            Log.d(TAG, "Service already bound, not re-initializing session on service.")
            return
        }

        val serviceIntent = Intent(context, MediaPlaybackService::class.java)
        serviceIntent.action = "INIT_MEDIA_SERVICE"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            val didBind = context.bindService(
                serviceIntent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            android.util.Log.d(TAG, "MediaSessionManager: context.bindService called. Result: $didBind")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting/binding service", e)
            // Try to recover by releasing and recreating media session
            release()
            setupMediaSession()
        }
    }

    private fun requestAudioFocus(): Boolean {
        Log.d(TAG, "Requesting audio focus")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(::handleAudioFocusChange)
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                ::handleAudioFocusChange,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        Log.d(TAG, "Audio focus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app took focus permanently, pause playback
                wasPlayingBeforeFocusLoss = isPlaying
                if (isPlaying) {
                    Log.d(TAG, "AUDIOFOCUS_LOSS: Attempting to pause via transportControls.pause() due to permanent focus loss.")
                    mediaSession?.controller?.transportControls?.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Another app took focus temporarily, pause playback
                wasPlayingBeforeFocusLoss = isPlaying

                // Cancel any pending callbacks
                pendingGainCallback?.let { gainCallbackHandler.removeCallbacks(it) }
                pendingLossTransientCallback?.let { gainCallbackHandler.removeCallbacks(it) }

                // If media is playing, set up a delayed pause to avoid immediate stop/start cycles
                if (isPlaying) {
                    pendingLossTransientCallback = Runnable {
                        Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT: Attempting to pause via transportControls.pause() due to transient focus loss (delayed).")
                        mediaSession?.controller?.transportControls?.pause()
                        pendingLossTransientCallback = null
                    }.also {
                        gainCallbackHandler.postDelayed(it, 300)
                    }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // We got audio focus back, resume if we were playing before
                pendingLossTransientCallback?.let {
                    gainCallbackHandler.removeCallbacks(it)
                    pendingLossTransientCallback = null
                }

                if (wasPlayingBeforeFocusLoss) {
                    // Create a delayed callback to resume playback
                    pendingGainCallback = Runnable {
                        mediaSession?.controller?.transportControls?.play()
                        pendingGainCallback = null
                    }.also {
                        gainCallbackHandler.postDelayed(it, 300)
                    }
                }
            }
        }
    }

    private fun abandonAudioFocus() {
        Log.d(TAG, "Abandoning audio focus")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(::handleAudioFocusChange)
        }
    }

    private fun updatePlaybackState() {
        synchronized(stateChangeLock) {
            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            android.util.Log.d(TAG, "MediaSessionManager.internalUpdatePlaybackState: ENTRY. isPlaying=$isPlaying, currentPosition=$currentPosition, Desired state for PlaybackStateCompat: $state")
            Log.d(TAG, "Updating playback state: ${if (isPlaying) "PLAYING" else "PAUSED"}")
            
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
            Log.d(TAG, "MediaSessionManager.internalUpdatePlaybackState - Setting state: $state, Position: $currentPosition, IsPlaying: $isPlaying")
            stateBuilder.setState(state, currentPosition, if (isPlaying) 1f else 0f)

            // Add custom actions for 15s forward/backward
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

            val playbackState = stateBuilder.build()
            Log.d(TAG, "MediaSessionManager.internalUpdatePlaybackState: Built PlaybackState: actions=${playbackState.actions}, state=${playbackState.state}, position=${playbackState.position}, bufferedPosition=${playbackState.bufferedPosition}, extras=${playbackState.extras}")
            mediaSession?.apply {
                setPlaybackState(playbackState)
                // isActive state is now primarily managed by updateMetadata and the public updatePlaybackState
                // However, ensure it reflects hasActiveMedia if playing, or just hasActiveMedia if paused.
                if (this@MediaSessionManager.isPlaying) {
                    isActive = true // If playing, session must be active
                } else {
                    isActive = hasActiveMedia // If paused, active only if we have media
                }
                Log.d(TAG, "MediaSessionManager.internalUpdatePlaybackState - mediaSession.setPlaybackState CALLED with: State: ${playbackState.state}, Position: ${playbackState.position}, Session Active: ${isActive}")
            }

            // Ensure service is updated with new state
            playbackService?.let { service ->
                service.updatePlaybackState(state, currentPosition)
            } ?: run {
                // If service is not available, try to start it
                if (hasActiveMedia) {
                    startPlaybackService()
                }
            }

            // Always update notification when playback state changes
            updateNotificationIfNeeded()
        }
    }

    private fun updateNotificationIfNeeded() {
        synchronized(stateChangeLock) {
            android.util.Log.d(TAG, "updateNotificationIfNeeded called. PlaybackService is ${if (playbackService == null) "NULL" else "NOT NULL"}")
            if (hasActiveMedia) {
                val metadata = currentMetadata ?: mediaSession?.controller?.metadata
                val state = mediaSession?.controller?.playbackState

                playbackService?.let { service ->
                    mediaSession?.let { session ->
                        service.updateNotification(session, metadata, state, currentBitmap)
                    }
                } ?: run {
                    // If service is not available, try to start it
                    startPlaybackService()
                }
            } else {
                playbackService?.stopForeground(true) // Pass boolean argument
            }
        }
    }

    fun updateMetadata(title: String?, artist: String?, duration: Long?, bitmap: Bitmap? = null) {
        android.util.Log.d(TAG, "MediaSessionManager: updateMetadata ENTRY. Title: '$title', Artist: '$artist', Duration: $duration")
        synchronized(stateChangeLock) {
            android.util.Log.d(TAG, "updateMetadata (synchronized) BEGIN - Title: ${title ?: "null"}, Duration: ${duration ?: "null"}. PlaybackService is ${if (playbackService == null) "NULL" else "NOT NULL"}")
            val oldHasActiveMedia = hasActiveMedia // Store old state
            hasActiveMedia = title != null && duration != null && duration > 0
            currentBitmap = bitmap
            
            val isVideo = title?.contains("video", ignoreCase = true) == true || 
                          title?.contains("TiddlyWiki Video", ignoreCase = true) == true
            
            val isVideoContent = isVideo

            if (title != null) { // Only proceed with metadata update if title is present
                Log.d(TAG, "updateMetadata: Title is not null. Building metadata. hasActiveMedia: $hasActiveMedia")
                val metadataObject = MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "")
                    .apply {
                        duration?.let { putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it) }
                        bitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
                        
                        if (isVideoContent) {
                            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Video: $title")
                            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Playing in background")
                        }
                    }
                    .build()

                mediaSession?.setMetadata(metadataObject)
                currentMetadata = metadataObject
                Log.d(TAG, "MediaSessionManager.updateMetadata - mediaSession.setMetadata CALLED with: Title: ${metadataObject.getString(MediaMetadataCompat.METADATA_KEY_TITLE)}, Duration: ${metadataObject.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)}")

                Log.d(TAG, "updateMetadata: Attempting to update notification via playbackService. PlaybackService is ${if (playbackService == null) "NULL" else "NOT NULL"}")
                playbackService?.let { service ->
                    mediaSession?.let { session ->
                        Log.d(TAG, "updateMetadata: Calling service.updateNotification with Meta Title: ${metadataObject.getString(MediaMetadataCompat.METADATA_KEY_TITLE)}, Duration: ${metadataObject.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)}")
                        service.updateNotification(
                            session,
                            metadataObject,
                            session.controller.playbackState, // Pass current state
                            bitmap
                        )
                        Log.d(TAG, "updateMetadata: Returned from service.updateNotification")
                    } ?: Log.d(TAG, "updateMetadata: playbackService was NOT null, but mediaSession WAS NULL inside 'let' block.")
                } ?: Log.d(TAG, "updateMetadata: playbackService WAS NULL.")
            } else {
                // If title is null, effectively means no valid media, clear metadata and set inactive
                mediaSession?.setMetadata(null)
                currentMetadata = null
                hasActiveMedia = false // Ensure this is false
            }
            
            // Always update mediaSession active state based on hasActiveMedia
            mediaSession?.isActive = hasActiveMedia
            Log.d(TAG, "updateMetadata: mediaSession isActive set to $hasActiveMedia")

            Log.d(TAG, "updateMetadata: Checking if hasActiveMedia ($hasActiveMedia) changed from oldHasActiveMedia ($oldHasActiveMedia)")
            if (hasActiveMedia != oldHasActiveMedia) {
                if (!hasActiveMedia) {
                    Log.d(TAG, "updateMetadata: hasActiveMedia is now false. Stopping foreground and abandoning audio focus.")
                    playbackService?.stopForeground(true) // Pass boolean argument
                    abandonAudioFocus()
                } else {
                    Log.d(TAG, "updateMetadata: hasActiveMedia is now true.")
                    // If it became active, ensure the service is started and notification updated
                    startPlaybackService() // Ensures service is running
                    // updateNotificationIfNeeded() is called by updatePlaybackState which usually follows
                }
            }
            Log.d(TAG, "updateMetadata (synchronized) END")
        }
    }

    // This is the primary method for updating the MediaSession and Service based on the current internal state.
    // It should be called AFTER 'this.isPlaying' and 'this.currentPosition' are authoritatively set.
    private fun syncMediaSessionAndService() {
        Log.d(TAG, "MediaSessionManager.syncMediaSessionAndService: ENTRY. Current internal state: isPlaying=$isPlaying, currentPosition=$currentPosition, hasActiveMedia=$hasActiveMedia, webViewProvider null? ${webViewProvider == null}")
        synchronized(stateChangeLock) {
            val currentIsPlaying = this.isPlaying // Use the authoritative internal state
            val currentPos = this.currentPosition

            android.util.Log.d(TAG, "MediaSessionManager.syncMediaSessionAndService: Syncing with isPlaying=$currentIsPlaying, position=$currentPos. PlaybackService is ${if (playbackService == null) "NULL" else "NOT NULL"}")

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
            
            val playbackStateValue = if (currentIsPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            stateBuilder.setState(playbackStateValue, currentPos, if (currentIsPlaying) 1f else 0f)

            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder("SKIP_BACKWARD", "Skip Back 15s", R.drawable.ic_skip_backward_15).build()
            )
            stateBuilder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder("SKIP_FORWARD", "Skip Forward 15s", R.drawable.ic_skip_forward_15).build()
            )

            val newPlaybackState = stateBuilder.build()
            mediaSession?.apply {
                setPlaybackState(newPlaybackState)
                isActive = if (currentIsPlaying) true else hasActiveMedia // Session active if playing, or if paused with media
                Log.d(TAG, "MediaSessionManager.syncMediaSessionAndService - mediaSession.setPlaybackState CALLED with: State: ${newPlaybackState.state}, Position: ${newPlaybackState.position}, Session Active: $isActive")
            }

            playbackService?.let { service ->
                service.updatePlaybackState(playbackStateValue, currentPos)
            } ?: run {
                if (hasActiveMedia) startPlaybackService()
            }
            updateNotificationIfNeeded() // Ensures notification reflects the new state
        }
        Log.d(TAG, "MediaSessionManager.syncMediaSessionAndService: EXIT.")
    }

    // Public method called by WebView bridge or other non-user-action sources
    fun updatePlaybackState(playing: Boolean, position: Long) {
        android.util.Log.d(TAG, "MediaSessionManager: updatePlaybackState (WebView/External) ENTRY. isPlaying: $playing, position: $position")
        synchronized(stateChangeLock) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUserActionTimestamp < USER_ACTION_DEBOUNCE_MS) {
                // If a user action recently set 'this.isPlaying', and WebView's 'playing' contradicts it, ignore WebView.
                if (this.isPlaying != playing) {
                    Log.w(TAG, "MediaSessionManager.updatePlaybackState (WebView/External): Ignoring WebView's state ($playing) because it contradicts a recent user action (user set to ${this.isPlaying}).")
                    return // Ignore this update from WebView
                }
            }

            // If not debounced or if WebView state matches user's intent, update internal state
            val stateChanged = this.isPlaying != playing || this.currentPosition != position
            this.isPlaying = playing
            this.currentPosition = position

            if (!hasActiveMedia && playing) {
                hasActiveMedia = true
                mediaSession?.isActive = true
                Log.d(TAG, "updatePlaybackState (WebView/External): Playback started without prior metadata, setting hasActiveMedia=true, session.isActive=true")
                startPlaybackService()
            }

            if (stateChanged) {
                syncMediaSessionAndService()
            } else {
                Log.d(TAG, "MediaSessionManager.updatePlaybackState (WebView/External): No change in state or significant position, not syncing.")
            }

            if (!playing && !hasActiveMedia) {
                mediaSession?.isActive = false
                Log.d(TAG, "updatePlaybackState (WebView/External): Playback stopped and no active media, setting session.isActive=false")
            }
        }
    }

    fun updatePlaybackPosition(position: Long) {
        synchronized(stateChangeLock) {
            if (Math.abs(this.currentPosition - position) > 500) { // Update if position changed significantly
                this.currentPosition = position
                // Don't change 'this.isPlaying' here, only position.
                // Then sync. The debounce in the public updatePlaybackState won't apply here directly,
                // but syncMediaSessionAndService will use the current 'this.isPlaying'.
                Log.d(TAG, "MediaSessionManager.updatePlaybackPosition: Position updated to $position. Syncing session.")
                syncMediaSessionAndService()
            }
        }
    }

    /**
     * Release the media session when a tiddler with media is closed
     * This is a lighter version of the full release() method
     * that stops playback and clears metadata but doesn't fully destroy the session
     */
    fun releaseMediaSession() {
        Log.d(TAG, "Releasing media session for closed tiddler")
        
        synchronized(stateChangeLock) {
            // Stop any ongoing playback
            isPlaying = false
            currentPosition = 0
            hasActiveMedia = false
            
            // Clear the metadata
            updateMetadata(null, null, null)
            
            // Update the playback state
            updatePlaybackState(false, 0)
            
            // Sync the state
            syncMediaSessionAndService()
            
            // Abandon audio focus
            abandonAudioFocus()
            
            // Stop foreground service
            playbackService?.let { service ->
                try {
                    service.stopForeground(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping foreground service", e)
                }
            }
            
            Log.d(TAG, "Media session released for closed tiddler")
        }
    }

    /**
     * Inject JavaScript to detect when a tiddler with media is closed
     */
    private fun injectTiddlerCloseDetection(webView: android.webkit.WebView) {
        Log.d(TAG, "Injecting tiddler close detection")
        val script = """
            (function() {
                if (window.tiddlerCloseDetectionInjected) return;
                window.tiddlerCloseDetectionInjected = true;
                
                console.log('[TiddlerCloseDetection] Initializing tiddler close detection');
                
                // Track the currently playing media element
                window.currentPlayingMediaElement = null;
                
                // Function to check if a tiddler contains media
                function tiddlerHasMedia(title) {
                    try {
                        if (!'${'$'}tw'|| !'${'$'}tw'.wiki) return false;
                        
                        const tiddler = $'${'$'}tw'.wiki.getTiddler(title);
                        if (!tiddler) return false;
                        
                        // Check if this is an audio/video tiddler
                        if (tiddler.fields.type === 'audio/mp3' || 
                            tiddler.fields.type === 'audio' || 
                            tiddler.fields.type === 'video' ||
                            tiddler.fields.type === 'video/mp4') {
                            return true;
                        }
                        
                        // Check for embedded audio/video in content
                        const content = tiddler.fields.text || '';
                        if (content.includes('<audio') || 
                            content.includes('<video') ||
                            content.includes('tc-player-') ||
                            content.includes('tc-audio-')) {
                            return true;
                        }
                        
                        return false;
                    } catch (e) {
                        console.error('[TiddlerCloseDetection] Error checking tiddler:', e);
                        return false;
                    }
                }
                
                // Wait for TW to be fully loaded
                function setupWhenReady() {
                    if (typeof '${'$'}tw' === 'undefined' || !'${'$'}tw'.wiki) {
                        setTimeout(setupWhenReady, 500);
                        return;
                    }
                    
                    console.log('[TiddlerCloseDetection] TiddlyWiki detected, setting up listeners');
                    
                    // Set up media playback detection
                    function setupMediaMonitoring() {
                        // Create a MutationObserver to watch for new media elements
                        const observer = new MutationObserver(function(mutations) {
                            mutations.forEach(function(mutation) {
                                if (mutation.addedNodes) {
                                    Array.from(mutation.addedNodes).forEach(function(node) {
                                        // Check if the added node is an audio/video element or contains one
                                        if (node.nodeName === 'AUDIO' || node.nodeName === 'VIDEO') {
                                            handleNewMediaElement(node);
                                        } else if (node.querySelectorAll) {
                                            const mediaElements = node.querySelectorAll('audio, video');
                                            Array.from(mediaElements).forEach(handleNewMediaElement);
                                        }
                                    });
                                }
                            });
                        });
                        
                        // Start observing the document
                        observer.observe(document.body, {
                            childList: true,
                            subtree: true
                        });
                        
                        // Also add event listeners to all audio/video elements already on the page
                        document.querySelectorAll('audio, video').forEach(handleNewMediaElement);
                        
                        console.log('[TiddlerCloseDetection] Media monitoring set up');
                    }
                    
                    // Handle media element play events
                    function handleNewMediaElement(mediaElement) {
                        if (!mediaElement) return;
                        
                        // Don't re-attach listeners
                        if (mediaElement._hasPlaylisteners) return;
                        mediaElement._hasPlaylisteners = true;
                        
                        mediaElement.addEventListener('play', function() {
                            console.log('[TiddlerCloseDetection] Media element started playing:', mediaElement.src || 'embedded');
                            
                            // If it's a different media element than the one currently playing
                            if (window.currentPlayingMediaElement && 
                                window.currentPlayingMediaElement !== mediaElement && 
                                !window.currentPlayingMediaElement.paused) {
                                
                                console.log('[TiddlerCloseDetection] New media started while another is playing - releasing previous session');
                                try {
                                    if (window.Android && window.Android.notifyTiddlerClosed) {
                                        window.Android.notifyTiddlerClosed('new_media_started');
                                    }
                                } catch (e) {
                                    console.error('[TiddlerCloseDetection] Error notifying Android:', e);
                                }
                            }
                            
                            // Update current playing element
                            window.currentPlayingMediaElement = mediaElement;
                        });
                    }
                    
                    // Run the setup
                    setupMediaMonitoring();
                    
                    // Story list change detection
                    '${'$'}tw'.wiki.addEventListener('change', function(changedTiddlers) {
                        if (changedTiddlers["$:/StoryList"]) {
                            // The story list has changed, so tiddlers may have been closed
                            const currentStoryList = '${'$'}tw'.wiki.getTiddlerList("$:/StoryList");
                            
                            // Check if any media tiddlers were closed
                            const mediaElements = document.querySelectorAll('audio, video');
                            if (mediaElements.length === 0) {
                                // No media playing, notify Android
                                console.log('[TiddlerCloseDetection] No active media elements found');
                                try {
                                    if (window.Android && window.Android.notifyTiddlerClosed) {
                                        window.Android.notifyTiddlerClosed('media_removed');
                                    }
                                } catch (e) {
                                    console.error('[TiddlerCloseDetection] Error notifying Android:', e);
                                }
                            }
                        }
                        
                        // Check through changed tiddlers
                        Object.keys(changedTiddlers).forEach(function(title) {
                            if (tiddlerHasMedia(title) && !$'${'$'}tw'.wiki.tiddlerExists(title)) {
                                console.log('[TiddlerCloseDetection] Media tiddler closed:', title);
                                try {
                                    if (window.Android && window.Android.notifyTiddlerClosed) {
                                        window.Android.notifyTiddlerClosed(title);
                                    }
                                } catch (e) {
                                    console.error('[TiddlerCloseDetection] Error notifying Android:', e);
                                }
                            }
                        });
                    });
                    
                    console.log('[TiddlerCloseDetection] Setup complete');
                }
                
                setupWhenReady();
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(script, null)
    }

    fun release() {
        Log.d(TAG, "Releasing MediaSessionManager")
        
        synchronized(stateChangeLock) {
            // Update internal state
            isPlaying = false
            hasActiveMedia = false
            currentPosition = 0
            
            // Clear any pending callbacks
            gainCallbackHandler.removeCallbacksAndMessages(null)
            pendingGainCallback = null
            pendingLossTransientCallback = null
            
            // Release media session
            mediaSession?.let { session ->
                try {
                    session.isActive = false
                    session.setCallback(null)
                    session.setPlaybackState(
                        PlaybackStateCompat.Builder()
                            .setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f)
                            .build()
                    )
                    session.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing media session", e)
                }
                mediaSession = null
            }
            
            // Stop playback service
            try {
                playbackService?.let { service ->
                    service.stopForeground(true)
                    service.stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping playback service", e)
            }
            
            // Unbind service
            if (isServiceBound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unbinding service", e)
                } finally {
                    isServiceBound = false
                }
            }
            
            // Release audio focus last
            abandonAudioFocus()
            
            // Clear references
            playbackService = null
            currentBitmap = null
            currentMetadata = null
        }
        
        INSTANCE = null
    }

    // Logging helper methods
    private fun logd(message: String) {
        Log.d(TAG, message)
    }

    private fun loge(message: String, e: Exception? = null) {
        if (e != null) {
            Log.e(TAG, message, e)
        } else {
            Log.e(TAG, message)
        }
    }

    private fun detectMediaPlayerType() {
        val detectionScript = """
            (function() {
                try {
                    // Check for standard HTML5 media
                    const standardMedia = document.querySelector('video, audio');
                    if (standardMedia) {
                        return JSON.stringify({
                            type: 'standard',
                            tag: standardMedia.tagName,
                            src: standardMedia.src || '',
                            controls: standardMedia.controls
                        });
                    }
                    
                    // Check for TiddlyWiki's custom media players
                    const tiddlyWikiPlayer = document.querySelector('.tc-media-player, .tc-player, [data-tw-media]');
                    if (tiddlyWikiPlayer) {
                        return JSON.stringify({
                            type: 'tiddlywiki',
                            className: tiddlyWikiPlayer.className,
                            hasPlayButton: !!document.querySelector('.tc-player-play, .play-button'),
                            hasPauseButton: !!document.querySelector('.tc-player-pause, .pause-button')
                        });
                    }
                    
                    return JSON.stringify({ type: 'none', message: 'No media player detected' });
                } catch (e) {
                    return JSON.stringify({ type: 'error', message: e.toString() });
                }
            })();
        """.trimIndent()
        
        Log.d(TAG, "🔍 Detecting media player type...")
        evaluateWebViewJavascript(detectionScript)
    }

    private fun evaluateWebViewJavascript(script: String) {
        Log.d(TAG, "📜 Executing JavaScript in WebView")
        try {
            val provider = webViewProvider ?: run {
                Log.e(TAG, "❌ Cannot execute JavaScript: WebViewProvider is null")
                return
            }
            
            provider.executeJavascript(script) { result ->
                if (result?.contains("error", ignoreCase = true) == true) {
                    Log.e(TAG, "❌ JavaScript error: $result")
                } else {
                    Log.d(TAG, "📜 Script execution result: $result")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error executing JavaScript", e)
        }
    }

    /**
     * Set the current WebView to monitor for media
     */
    fun setWebView(webView: android.webkit.WebView?) {
        Log.d(TAG, "Setting WebView reference")

        // Update the WebView reference and reset media state
        if (webView != null) {
            try {
                // Check if WebView is destroyed before using it
                webView.settings
                
                // Setup WebViewProvider interface
                webViewProvider = object : WebViewProvider {
                    override fun executeJavascript(script: String, callback: ((String) -> Unit)?) {
                        webView.evaluateJavascript(script) { result ->
                            callback?.invoke(result ?: "")
                        }
                    }
                    
                    override fun getCurrentMediaState(callback: (title: String?, artist: String?, duration: Long?, position: Long?, isPlaying: Boolean?) -> Unit) {
                        val script = """
                            (function() {
                                try {
                                    const media = document.querySelector('audio, video');
                                    if (!media) return JSON.stringify({found: false});
                                    
                                    const isPlaying = !media.paused && !media.ended;
                                    const containerTitle = media.closest('.tc-tiddler-frame')?.querySelector('.tc-title')?.textContent || 'Media';
                                    
                                    return JSON.stringify({
                                        found: true,
                                        title: containerTitle || media.title || 'TiddlyWiki Media',
                                        artist: 'TiddlyWiki',
                                        duration: Math.round(media.duration * 1000) || 0,
                                        position: Math.round(media.currentTime * 1000) || 0,
                                        isPlaying: isPlaying
                                    });
                                } catch (e) {
                                    return JSON.stringify({found: false, error: e.message});
                                }
                            })();
                        """.trimIndent()
                        
                        webView.evaluateJavascript(script) { result ->
                            try {
                                if (result == null || result == "null") {
                                    callback(null, null, null, null, null)
                                    return@evaluateJavascript
                                }
                                
                                val jsonResult = android.util.JsonReader(java.io.StringReader(result))
                                jsonResult.beginObject()
                                
                                var found = false
                                var title: String? = null
                                var artist: String? = null
                                var duration: Long? = null
                                var position: Long? = null
                                var isPlaying: Boolean? = null
                                var error: String? = null
                                
                                while (jsonResult.hasNext()) {
                                    val name = jsonResult.nextName()
                                    when (name) {
                                        "found" -> found = jsonResult.nextBoolean()
                                        "title" -> title = jsonResult.nextString()
                                        "artist" -> artist = jsonResult.nextString()
                                        "duration" -> duration = jsonResult.nextLong()
                                        "position" -> position = jsonResult.nextLong()
                                        "isPlaying" -> isPlaying = jsonResult.nextBoolean()
                                        "error" -> error = jsonResult.nextString()
                                        else -> jsonResult.skipValue()
                                    }
                                }
                                
                                jsonResult.endObject()
                                
                                if (found) {
                                    callback(title, artist, duration, position, isPlaying)
                                } else {
                                    android.util.Log.d(TAG, "No media found in WebView, or error: $error")
                                    callback(null, null, null, null, null)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e(TAG, "Error parsing media state: ${e.message}")
                                callback(null, null, null, null, null)
                            }
                        }
                    }
                }
                
                // Add JavaScript interface for tiddler close detection
                webView.addJavascriptInterface(object : Any() {
                    @android.webkit.JavascriptInterface
                    fun notifyTiddlerClosed(title: String) {
                        Log.d(TAG, "Tiddler closed notification received for: $title")
                        ThreadManager.runOnMain {
                            releaseMediaSession()
                        }
                    }
                }, "Android")
                
                // Inject the tiddler close detection script
                injectTiddlerCloseDetection(webView)
                
                // Check for existing media and update state
                webView.evaluateJavascript("""
                    (function() {
                        // Check for existing media and update state
                        const media = document.querySelector('audio,video');
                        if (media) {
                            return JSON.stringify({
                                exists: true,
                                playing: !media.paused,
                                currentTime: media.currentTime,
                                duration: media.duration,
                                title: document.title
                            });
                        }
                        return JSON.stringify({exists: false});
                    })();
                """.trimIndent()) { result ->
                    Log.d(TAG, "WebView media check result: $result")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking WebView for media: ${e.message}")
            }
        } else {
            // Clear the WebViewProvider if webView is null
            webViewProvider = null
        }
    }

    /**
     * Check if the service is already bound
     */
    fun isServiceBound(): Boolean {
        return isServiceBound
    }

    fun bindToService() {
        Log.d(TAG, "Binding to service")
        try {
            // Ensure we have the media session before binding
            mediaSession?.let { session ->
                playbackService?.setMediaSession(session)

                // If we have metadata, update the notification
                currentMetadata?.let { metadata ->
                    val state = session.controller?.playbackState
                    playbackService?.updateNotification(session, metadata, state, currentBitmap)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding to service", e)
        }
    }

    fun getMediaSession(): MediaSessionCompat? {
        return mediaSession
    }

    /**
     * Start the media playback service in foreground mode
     */
    fun startMediaService() {
        startPlaybackService()
    }

    // Interface for WebView to provide current media state
    fun setWebViewProvider(provider: WebViewProvider?) {
        this.webViewProvider = provider
        if (provider != null) {
            // Optionally fetch initial state when WebView is set
            // fetchMediaStateFromWebView() 
        }
    }

    // Potentially called by MediaSessionCallback actions
    private fun fetchMediaStateFromWebView() {
        webViewProvider?.getCurrentMediaState { title, artist, duration, position, isPlaying ->
            ThreadManager.runOnMain { // Ensure UI thread for updates if they touch UI directly
                var changed = false
                if (title != null || artist != null || duration != null) {
                    updateMetadata(title, artist, duration) // Bitmap is null here, handled by updateMetadata
                    changed = true
                }
                if (isPlaying != null && position != null) {
                    updatePlaybackState(isPlaying, position)
                    changed = true
                }
                // if (changed) { // updateNotificationIfNeeded is called by the above methods
                // }
            }
        }
    }
    
    fun getSessionToken(): MediaSessionCompat.Token? {
        return sessionToken
    }

    // MediaSessionCallback: Defines how the media session responds to controller commands
    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {
        private val LOG_TAG = "MediaControls"
        private val JS_TAG = "MediaControls"
        
        private fun logd(message: String) {
            Log.d(LOG_TAG, "[${Thread.currentThread().name}] $message")
            // Also log to console for WebView debugging
            evaluateWebViewJavascript("console.log('$message')")
        }
        
        private fun logi(message: String) {
            Log.i(LOG_TAG, "[${Thread.currentThread().name}] $message")
        }
        
        private fun logw(message: String) {
            Log.w(LOG_TAG, "[${Thread.currentThread().name}] $message")
        }
        
        private fun loge(message: String, e: Exception? = null) {
            if (e == null) {
                Log.e(LOG_TAG, "[${Thread.currentThread().name}] $message")
            } else {
                Log.e(LOG_TAG, "[${Thread.currentThread().name}] $message", e)
            }
        }
        
        override fun onPlay() {
            logd("▶️ onPlay() called - Entry point")
            // Log the entire stack trace to see who called onPlay
            Log.d(LOG_TAG, "Stack trace:", Exception("Play called from"))
            // Log current state
            logd("Current thread: ${Thread.currentThread().name}")
            logd("Current media session: $mediaSession")
            logd("Current playback state: $currentPlaybackState")

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUserActionTimestamp < USER_ACTION_DEBOUNCE_MS) {
                logd("⚠️ Debouncing onPlay() call, last action at $lastUserActionTimestamp")
                return
            }
            lastUserActionTimestamp = currentTime

            // Request audio focus before playing
            if (!requestAudioFocus()) {
                loge("❌ Failed to gain audio focus in onPlay")
                return
            }
            
            synchronized(stateChangeLock) {
                isPlaying = true
                lastPlayTimestamp = System.currentTimeMillis()
                logd("✅ Local state updated - isPlaying: true")
            }

            // Primary method: Play via ExoPlayer which works in background
            val exoPlayer = (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()
            if (exoPlayer != null) {
                exoPlayer.play()
                logd("✅ Started playback via ExoPlayer")
            } else if (webViewProvider != null) {
                // Fallback: Use WebView if ExoPlayer not available
                logd("⚠️ ExoPlayer not available, falling back to WebView control")
                
                // First, detect the type of media player
                detectMediaPlayerType()
                
                val playScript = """
                    (function() {
                        console.log('[MediaControl] Play requested');
                        try {
                            // Try multiple selector approaches
                            const media = document.querySelector('video, audio, .tc-media-player');
                            
                            if (!media) {
                                console.log('[MediaControl] No media element found');
                                return 'no_media';
                            }
                            
                            console.log('[MediaControl] Media element found:', media.tagName);
                            
                            // Handle different types of media players
                            if (media.tagName === 'VIDEO' || media.tagName === 'AUDIO') {
                                // Standard HTML5 media
                                if (media.paused) {
                                    console.log('[MediaControl] Standard media - attempting to play');
                                    const playPromise = media.play();
                                    
                                    if (playPromise !== undefined) {
                                        playPromise.catch(e => {
                                            console.error('[MediaControl] Play error:', e);
                                            return 'error: ' + e.message;
                                        });
                                    }
                                    return 'play_attempted';
                                }
                                return 'already_playing';
                            } else {
                                // Possibly a TiddlyWiki custom player
                                console.log('[MediaControl] Non-standard media player detected');
                                
                                // Try to find play button if it's a custom player
                                const playButton = document.querySelector('.tc-player-play, .play-button');
                                if (playButton) {
                                    console.log('[MediaControl] Found play button, clicking');
                                    playButton.click();
                                    return 'play_button_clicked';
                                }
                                
                                return 'unknown_player_type';
                            }
                            return 'success';
                        } catch (e) {
                            console.error('[$JS_TAG] ❌ Play error:', e);
                            return 'error: ' + e.message;
                        }
                    })();
                """.trimIndent()

                logd("📜 Executing play script")
                evaluateWebViewJavascript(playScript)
            } else {
                loge("❌ Neither ExoPlayer nor WebViewProvider available in onPlay")
            }

            // Update the playback state immediately
            logd("🔄 Updating playback state to PLAYING")
            updatePlaybackState(true, currentPosition)
            
            // Sync the state
            logd("🔄 Syncing media session and service after play")
            syncMediaSessionAndService()
            
            // Schedule another sync to ensure state is consistent
            Handler(Looper.getMainLooper()).postDelayed({
                logd("🔄 Executing final sync after play")
                fetchMediaStateFromWebView() // Get latest state from webview
                syncMediaSessionAndService()
            }, 100)
        }

        override fun onPause() {
            Log.d(TAG, "MediaSession.onPause()")
            synchronized(stateChangeLock) {
                if (!isPlaying) {
                    Log.d(TAG, "Media already paused")
                    return
                }

                isPlaying = false
                
                // Primary method: Pause via ExoPlayer which works in background
                val exoPlayer = (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()
                if (exoPlayer != null) {
                    exoPlayer.pause()
                    Log.d(TAG, "Paused via ExoPlayer")
                } else {
                    // Fallback: Try to pause HTML5 audio/video if in foreground
                    if (webViewProvider != null) {
                        try {
                            evaluateWebViewJavascript("document.querySelector('audio,video')?.pause()")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error while trying to pause webview media", e)
                        }
                    }
                }
                
                // Update state and broadcasting
                updatePlaybackState(false, currentPosition)
                syncMediaSessionAndService()
                abandonAudioFocus()
            }
        }

        override fun onStop() {
            Log.d(TAG, "MediaSessionCallback: onStop called")
            synchronized(stateChangeLock) {
                lastUserActionTimestamp = System.currentTimeMillis()
                wasPlayingBeforeFocusLoss = false
                this@MediaSessionManager.isPlaying = false
                this@MediaSessionManager.currentPosition = 0L
                Log.d(TAG, "MediaSessionCallback.onStop: Set this.isPlaying=false, position=0")
            }
            
            // Primary method: Stop via ExoPlayer which works in background
            val exoPlayer = (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()
            if (exoPlayer != null) {
                exoPlayer.stop()
                Log.d(TAG, "Stopped via ExoPlayer")
            } else {
                // Fallback: Use WebView if ExoPlayer not available
                evaluateWebViewJavascript("""
                    (function() {
                        const media = document.querySelector('video, audio');
                        if (media) {
                            media.pause();
                            media.currentTime = 0;
                            return true;
                        }
                        return false;
                    })();
                """.trimIndent())
            }
            
            syncMediaSessionAndService()
            abandonAudioFocus()
        }

        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "MediaSessionCallback: onSeekTo called with position: $pos")
            synchronized(stateChangeLock) {
                lastUserActionTimestamp = System.currentTimeMillis()
                this@MediaSessionManager.currentPosition = pos
                Log.d(TAG, "MediaSessionCallback.onSeekTo: Set position=$pos")
            }
            
            // Primary method: Seek via ExoPlayer which works in background
            val exoPlayer = (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()
            if (exoPlayer != null) {
                exoPlayer.seekTo(pos)
                Log.d(TAG, "Seeked via ExoPlayer to position: $pos")
            } else {
                // Fallback: Use WebView if ExoPlayer not available
                val positionInSeconds = pos / 1000.0
                evaluateWebViewJavascript("""
                    (function() {
                        const media = document.querySelector('video, audio');
                        if (media) {
                            media.currentTime = $positionInSeconds;
                            return true;
                        }
                        return false;
                    })();
                """.trimIndent())
            }
            
            syncMediaSessionAndService()
        }

        override fun onSkipToNext() {
            logd("⏭️ onSkipToNext() called")
            
            synchronized(stateChangeLock) {
                lastUserActionTimestamp = System.currentTimeMillis()
            }
            
            // Primary method: Skip via ExoPlayer which works in background
            val exoPlayer = (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()
            if (exoPlayer != null) {
                val currentPos = exoPlayer.currentPosition
                val duration = exoPlayer.duration
                val newPosition = (currentPos + 15000).coerceAtMost(duration)
                exoPlayer.seekTo(newPosition)
                logd("✅ Skipped forward via ExoPlayer to position: $newPosition")
                
                synchronized(stateChangeLock) {
                    currentPosition = newPosition
                    if (!isPlaying && exoPlayer.playWhenReady) {
                        isPlaying = true
                    }
                }
            } else if (webViewProvider != null) {
                // Fallback: Use WebView if ExoPlayer not available
                val skipScript = """
                    (function() {
                        try {
                            console.log('[MediaControls] ⏭️ Skip forward requested');
                            let media = document.querySelector('video, audio');
                            if (!media) {
                                console.log('[MediaControls] ❌ No media element found for skip');
                                return false;
                            }
                            
                            console.log('[MediaControls] Current time: ' + media.currentTime.toFixed(2) + 's');
                            
                            // Try custom skip function first
                            if (typeof window.skipForward === 'function') {
                                console.log('[MediaControls] Using custom skipForward function');
                                window.skipForward();
                            } else {
                                // Fallback to direct manipulation
                                const newTime = Math.min(media.duration, media.currentTime + 15);
                                console.log('[MediaControls] ⏩ Skipping to: ' + newTime.toFixed(2) + 's');
                                media.currentTime = newTime;
                                
                                // Ensure the UI updates
                                media.dispatchEvent(new Event('timeupdate'));
                                
                                // If paused, play after seeking
                                if (media.paused) {
                                    console.log('[MediaControls] Media was paused, resuming playback');
                                    const playPromise = media.play();
                                    if (playPromise !== undefined) {
                                        playPromise
                                            .then(() => console.log('[MediaControls] ✅ Playback resumed after skip'))
                                            .catch(e => console.error('[MediaControls] ❌ Failed to resume playback after skip:', e));
                                    }
                                }
                            }
                            return true;
                        } catch (e) {
                            console.error('[MediaControls] ❌ Skip forward error:', e);
                            return false;
                        }
                    })();
                """.trimIndent()
                
                logd("📜 Executing skip forward script")
                evaluateWebViewJavascript(skipScript)
                
                // Update the current position
                synchronized(stateChangeLock) {
                    val duration = mediaSession?.controller?.metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: Long.MAX_VALUE
                    currentPosition = (currentPosition + 15000).coerceAtMost(duration)
                    logd("🔄 Updated position: $currentPosition (max: $duration)")
                    
                    // Force update the playback state
                    if (!isPlaying) {
                        isPlaying = true
                        logd("🔄 Forcing isPlaying=true after skip")
                    }
                }
            }
            
            // Update the UI
            logd("🔄 Syncing media session and service")
            syncMediaSessionAndService()
            
            // Schedule another sync to ensure state is consistent
            logd("⏱ Scheduling final sync in 100ms")
            Handler(Looper.getMainLooper()).postDelayed({
                logd("🔄 Executing final sync after skip")
                syncMediaSessionAndService()
            }, 100)
        }

        override fun onSkipToPrevious() {
            logd("⏮️ onSkipToPrevious() called")
            
            synchronized(stateChangeLock) {
                lastUserActionTimestamp = System.currentTimeMillis()
            }
            
            // Primary method: Skip via ExoPlayer which works in background
            val exoPlayer = (context as? MainActivity)?.exoPlayerManager?.getOrCreatePlayer()
            if (exoPlayer != null) {
                val currentPos = exoPlayer.currentPosition
                val newPosition = (currentPos - 15000).coerceAtLeast(0)
                exoPlayer.seekTo(newPosition)
                logd("✅ Skipped backward via ExoPlayer to position: $newPosition")
                
                synchronized(stateChangeLock) {
                    currentPosition = newPosition
                    if (!isPlaying && exoPlayer.playWhenReady) {
                        isPlaying = true
                    }
                }
            } else if (webViewProvider != null) {
                // Fallback: Use WebView if ExoPlayer not available
                evaluateWebViewJavascript("""
                    (function() {
                        try {
                            let media = document.querySelector('video, audio');
                            if (!media) return false;
                            
                            // Try custom skip function first
                            if (typeof window.skipBackward === 'function') {
                                window.skipBackward();
                            } else {
                                // Fallback to direct manipulation
                                media.currentTime = Math.max(0, media.currentTime - 15);
                                // Ensure the UI updates
                                media.dispatchEvent(new Event('timeupdate'));
                                
                                // If paused, play after seeking
                                if (media.paused) {
                                    const playPromise = media.play();
                                    if (playPromise !== undefined) {
                                        playPromise.catch(e => console.log('Auto-play after skip failed:', e));
                                    }
                                }
                            }
                            return true;
                        } catch (e) {
                            console.error('Skip backward error:', e);
                            return false;
                        }
                    })();
                """.trimIndent())
                
                // Update the current position
                synchronized(stateChangeLock) {
                    currentPosition = (currentPosition - 15000).coerceAtLeast(0)
                    // Force update the playback state
                    if (!isPlaying) {
                        isPlaying = true
                    }
                }
            }
            
            // Update the UI
            syncMediaSessionAndService()
            
            // Schedule another sync to ensure state is consistent
            Handler(Looper.getMainLooper()).postDelayed({
                syncMediaSessionAndService()
            }, 100)
        }

        // Handle MediaButton events here if not using a separate MediaButtonReceiver
        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            Log.d(LOG_TAG, "MediaSessionCallback.onMediaButtonEvent received intent: $mediaButtonEvent")
            mediaButtonEvent?.let { intent ->
                val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                keyEvent?.let {
                    Log.d(LOG_TAG, "Key event in onMediaButtonEvent: keyCode=${it.keyCode}, action=${it.action}")
                    if (it.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE && it.action == KeyEvent.ACTION_DOWN) {
                        Log.d(LOG_TAG, "--> Specific PAUSE KeyCode (ACTION_DOWN) received, manually calling onPause() <--")
                        this.onPause()
                        return true // Event handled
                    }
                    // You could add similar direct calls for onPlay, onSkipToNext etc. if needed for further diagnostics
                    // Example for play:
                    // if (it.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY && it.action == KeyEvent.ACTION_DOWN) {
                    //     Log.d(LOG_TAG, "--> Specific PLAY KeyCode (ACTION_DOWN) received, manually calling onPlay() <--")
                    //     this.onPlay()
                    //     return true // Event handled
                    // }
                }
            }
            // If not handled by our direct logic (e.g. not PAUSE), call super
            // CRUCIAL: Call super to ensure default dispatching for other events (play, next, prev etc.) occurs
            Log.d(LOG_TAG, "Pause key not detected or not ACTION_DOWN, delegating to super.onMediaButtonEvent")
            return super.onMediaButtonEvent(mediaButtonEvent)
        }
    }
}