package com.tiddlywikibrowser

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import android.util.Log

class ExoPlayerManager(private val context: Context) {
    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var _currentPosition: Long = 0
    private var wasPlaying: Boolean = false
    private var mediaSessionManager: MediaSessionManager? = null

    // Improve data source factory with better timeout and retry logic
    private val dataSourceFactory: DataSource.Factory by lazy {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)  // Longer connect timeout
            .setReadTimeoutMs(20000)     // Longer read timeout
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("TidWeb/1.0")
        
        // Combine with default source for handling both http and local files
        DefaultDataSource.Factory(context, httpDataSourceFactory)
    }

    init {
        // Get reference to the MediaSessionManager
        mediaSessionManager = if (context is MainActivity) {
            try {
                context.mediaSessionManager
            } catch (e: Exception) {
                Log.e("ExoPlayerManager", "Error getting mediaSessionManager", e)
                null
            }
        } else {
            null
        }
    }

    fun getOrCreatePlayer(): ExoPlayer {
        if (player == null) {
            player = ExoPlayer.Builder(context)
                .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory))
                .build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _currentPosition = player?.currentPosition ?: 0
                        try {
                            (context as? MainActivity)?.let { activity ->
                                // Update media session state first
                                activity.mediaSessionManager.updatePlaybackState(isPlaying, _currentPosition)
                                
                                // Explicitly manage foreground service based on playback state
                                if (isPlaying) {
                                    // Start media service via MediaSessionManager
                                    activity.mediaSessionManager.startMediaService()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ExoPlayerManager", "Error in onIsPlayingChanged", e)
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            try {
                                player?.let { exoPlayer ->
                                    _currentPosition = exoPlayer.currentPosition
                                    
                                    // Notify media session about title and duration
                                    val currentItem = exoPlayer.currentMediaItem
                                    val mediaTitle = currentItem?.mediaMetadata?.title?.toString() 
                                        ?: currentItem?.mediaId 
                                        ?: "Unknown"
                                        
                                    Log.d("ExoPlayer", "MediaItem ready: title=$mediaTitle, duration=${exoPlayer.duration}")
                                    
                                    (context as? MainActivity)?.let { activity ->
                                        activity.mediaSessionManager.updateMetadata(
                                            title = mediaTitle.toString(),
                                            artist = "TiddlyWiki Media",
                                            duration = exoPlayer.duration
                                        )
                                        // Ensure service is started when media is ready
                                        activity.mediaSessionManager.startMediaService()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("ExoPlayer", "Error in onPlaybackStateChanged", e)
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: PlaybackException) {
                        // Handle player errors gracefully
                        error.printStackTrace()
                    }
                })
            }
            return player!!
        }
        return player!!
    }

    fun playMedia(url: String) {
        if (url.isEmpty()) return
        
        if (url != currentUrl) {
            currentUrl = url
            val mediaItem = createMediaItem(url)
            getOrCreatePlayer().apply {
                setMediaItem(mediaItem)
                prepare()
            }
        } else {
            // If it's the same URL, just resume if paused
            if (!getOrCreatePlayer().isPlaying) {
                getOrCreatePlayer().play()
            }
        }
    }
    
    /**
     * Improved media item creation with better error handling
     */
    private fun createMediaItem(url: String): MediaItem {
        // Handle data URLs and local URLs better
        val uri = when {
            url.startsWith("data:") -> {
                // For data URLs, use a special handling
                Uri.parse(url)
            }
            url.startsWith("file:///") -> {
                // Handle file URLs properly
                Uri.parse(url)
            }
            url.startsWith("/") -> {
                // Convert absolute path to file URL
                Uri.parse("file://$url")
            }
            !url.contains("://") -> {
                // Add http:// if protocol missing
                Uri.parse("http://$url")
            }
            else -> Uri.parse(url)
        }
        
        // Get a meaningful filename
        val filename = getFileNameFromUrl(url)
        
        // Create a rich metadata object with all possible fields filled
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(filename)
            .setDisplayTitle(filename)
            .setArtist("TiddlyWiki Media")
            .setAlbumTitle("TiddlyWiki Player")
            .setSubtitle("Playing from TiddlyWiki")
            .setDescription(filename)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        
        Log.d("ExoPlayer", "Creating MediaItem with title=$filename")
        
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(url)
            .setMediaMetadata(metadata)
            .build()
    }
    
    private fun getFileNameFromUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val path = uri.path
            val filename = path?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Unknown"
            // Format the filename nicely - replace underscores and hyphens with spaces
            filename.replace('_', ' ').replace('-', ' ').capitalize()
        } catch (e: Exception) {
            "Unknown"
        }
    }

    fun onPause() {
        player?.let {
            wasPlaying = it.isPlaying
            _currentPosition = it.currentPosition
            it.pause()
        }
    }

    fun onResume() {
        if (wasPlaying) {
            player?.seekTo(_currentPosition)
            player?.play()
        }
    }

    fun release() {
        _currentPosition = 0
        wasPlaying = false
        player?.release()
        player = null
        currentUrl = null
    }

    fun getCurrentPosition(): Long {
        return player?.currentPosition ?: _currentPosition
    }

    fun isPlaying(): Boolean {
        return player?.isPlaying == true
    }
    
    var volume: Float
        get() = player?.volume ?: 1.0f
        set(value) {
            player?.volume = value.coerceIn(0f, 1f)
        }
    
    // Stops the player completely    
    fun stop() {
        player?.stop()
        _currentPosition = 0
    }
}