package com.tiddlywikibrowser.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.webkit.WebView
import com.tiddlywikibrowser.R
import org.json.JSONObject

/**
 * Simplified and efficient media session manager
 * Combines functionality of the old MediaSessionManager, MediaPlaybackService, and related classes
 * into a single, lightweight class focused on core media session functionality.
 */
class SimpleMediaManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "SimpleMediaManager"
        private const val UPDATE_INTERVAL = 2000L // 2 seconds
        
        @Volatile
        private var INSTANCE: SimpleMediaManager? = null
        
        fun getInstance(context: Context): SimpleMediaManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SimpleMediaManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private var mediaSession: MediaSessionCompat? = null
    private var webView: WebView? = null
    private var updateHandler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    
    // Simple state tracking
    private var isActive = false
    private var currentTitle: String? = null
    private var currentArtist: String? = null
    private var currentDuration: Long = 0L
    private var currentPosition: Long = 0L
    private var isPlaying = false
    
    init {
        initializeMediaSession()
    }
    
    private fun initializeMediaSession() {
        try {
            mediaSession = MediaSessionCompat(context, "TiddlyWikiMedia").apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                
                // Set up media control callbacks
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        executeMediaControl("play")
                    }
                    
                    override fun onPause() {
                        executeMediaControl("pause")
                    }
                    
                    override fun onSkipToNext() {
                        executeMediaControl("skipForward")
                    }
                    
                    override fun onSkipToPrevious() {
                        executeMediaControl("skipBackward")
                    }
                    
                    override fun onSeekTo(pos: Long) {
                        executeMediaControl("seekTo", pos)
                    }
                })
                
                // Set initial state
                setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(
                            PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO
                        )
                        .setState(PlaybackStateCompat.STATE_NONE, 0, 0f)
                        .build()
                )
                
                isActive = true
            }
            
            Log.d(TAG, "Media session initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize media session", e)
        }
    }
    
    /**
     * Set the WebView for media control
     */
    fun setWebView(webView: WebView) {
        this.webView = webView
    }
    
    /**
     * Update media metadata and start tracking if not already active
     */
    fun updateMetadata(title: String?, artist: String?, duration: Long, albumArt: Bitmap? = null) {
        try {
            // Update our state
            currentTitle = title ?: "Unknown Title"
            currentArtist = artist ?: "TiddlyWiki"
            currentDuration = duration
            
            // Update MediaSession metadata
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "TiddlyWiki Media")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                
            albumArt?.let { metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            
            mediaSession?.setMetadata(metadataBuilder.build())
            
            // Start periodic updates if we have valid media
            if (duration > 0 && !isActive) {
                startPeriodicUpdates()
                isActive = true
            }
            
            Log.d(TAG, "Updated metadata: $currentTitle by $currentArtist")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating metadata", e)
        }
    }
    
    /**
     * Update playback state
     */
    fun updatePlaybackState(playing: Boolean, position: Long) {
        try {
            isPlaying = playing
            currentPosition = position
            
            val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            
            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, position, if (playing) 1.0f else 0.0f)
            
            mediaSession?.setPlaybackState(stateBuilder.build())
            
            Log.d(TAG, "Updated playback state: playing=$playing, position=$position")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating playback state", e)
        }
    }
    
    /**
     * Start periodic updates to sync with WebView media state
     */
    private fun startPeriodicUpdates() {
        // Stop any existing updates
        stopPeriodicUpdates()
        
        updateRunnable = object : Runnable {
            override fun run() {
                if (isActive && webView != null) {
                    syncWithWebView()
                    updateHandler.postDelayed(this, UPDATE_INTERVAL)
                }
            }
        }
        updateHandler.post(updateRunnable!!)
    }
    
    /**
     * Stop periodic updates
     */
    private fun stopPeriodicUpdates() {
        updateRunnable?.let { updateHandler.removeCallbacks(it) }
        updateRunnable = null
    }
    
    /**
     * Sync state with WebView media element
     */
    private fun syncWithWebView() {
        webView?.evaluateJavascript("""
            (function() {
                const media = document.querySelector('audio, video');
                if (media && !media.paused) {
                    return JSON.stringify({
                        title: media.title || document.title || '$currentTitle',
                        position: Math.round(media.currentTime * 1000),
                        duration: Math.round(media.duration * 1000),
                        isPlaying: !media.paused && !media.ended
                    });
                }
                return null;
            })();
        """.trimIndent()) { result ->
            if (result != null && result != "null") {
                try {
                    val json = JSONObject(result)
                    val newPosition = json.optLong("position", currentPosition)
                    val newPlaying = json.optBoolean("isPlaying", isPlaying)
                    
                    // Only update if there's a significant change
                    if (Math.abs(newPosition - currentPosition) > 2000 || newPlaying != isPlaying) {
                        updatePlaybackState(newPlaying, newPosition)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebView media state", e)
                }
            }
        }
    }
    
    /**
     * Execute media control commands on WebView
     */
    private fun executeMediaControl(command: String, value: Long = 0) {
        webView?.evaluateJavascript("""
            (function() {
                const media = document.querySelector('audio, video');
                if (media) {
                    switch ('$command') {
                        case 'play':
                            media.play().catch(e => console.log('Play failed:', e));
                            break;
                        case 'pause':
                            media.pause();
                            break;
                        case 'skipForward':
                            media.currentTime = Math.min(media.duration, media.currentTime + 15);
                            break;
                        case 'skipBackward':
                            media.currentTime = Math.max(0, media.currentTime - 15);
                            break;
                        case 'seekTo':
                            media.currentTime = ${value / 1000.0};
                            break;
                    }
                    return 'executed';
                }
                return 'no_media';
            })();
        """.trimIndent()) { result ->
            Log.d(TAG, "Media control '$command' result: $result")
        }
    }
    
    /**
     * Called when media becomes inactive
     */
    fun onMediaInactive() {
        isActive = false
        stopPeriodicUpdates()
        
        // Update to stopped state
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f)
                .build()
        )
        
        Log.d(TAG, "Media became inactive")
    }
    
    /**
     * Get the MediaSession for external use (e.g., notifications)
     */
    fun getMediaSession(): MediaSessionCompat? = mediaSession
    
    /**
     * Check if media is currently active
     */
    fun isMediaActive(): Boolean = isActive
    
    /**
     * Get current media info
     */
    fun getCurrentMediaInfo(): MediaInfo {
        return MediaInfo(
            title = currentTitle,
            artist = currentArtist,
            duration = currentDuration,
            position = currentPosition,
            isPlaying = isPlaying,
            isActive = isActive
        )
    }
    
    /**
     * Release resources
     */
    fun release() {
        try {
            stopPeriodicUpdates()
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
            webView = null
            isActive = false
            
            Log.d(TAG, "Resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }
    
    /**
     * Data class to hold media information
     */
    data class MediaInfo(
        val title: String?,
        val artist: String?,
        val duration: Long,
        val position: Long,
        val isPlaying: Boolean,
        val isActive: Boolean
    )
}
