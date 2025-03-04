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

class ExoPlayerManager(private val context: Context) {
    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var _currentPosition: Long = 0
    private var wasPlaying: Boolean = false
    private var mediaSessionManager: MediaSessionManager? = null

    // Create a custom data source factory that handles local files and network resources
    private val dataSourceFactory: DataSource.Factory by lazy {
        DefaultDataSource.Factory(context)
    }

    init {
        // Get reference to the MediaSessionManager
        if (context is MainActivity) {
            mediaSessionManager = context.mediaSessionManager
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
                        (context as? MainActivity)?.let { activity ->
                            activity.mediaSessionManager.updatePlaybackState(isPlaying, _currentPosition)
                            
                            // Explicitly manage foreground service based on playback state
                            if (isPlaying) {
                                activity.startMediaService()
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            player?.let { exoPlayer ->
                                _currentPosition = exoPlayer.currentPosition
                                
                                // Notify media session about title and duration
                                val currentItem = exoPlayer.currentMediaItem
                                val mediaTitle = currentItem?.mediaMetadata?.title?.toString() 
                                    ?: currentItem?.mediaId 
                                    ?: "Unknown"
                                    
                                (context as? MainActivity)?.mediaSessionManager?.updateMetadata(
                                    title = mediaTitle.toString(),
                                    artist = "TiddlyWiki Media",
                                    duration = exoPlayer.duration
                                )
                                
                                // Ensure service is started when media is ready
                                if (exoPlayer.isPlaying) {
                                    (context as? MainActivity)?.startMediaService()
                                }
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: PlaybackException) {
                        // Handle player errors gracefully
                        error.printStackTrace()
                    }
                })
            }
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
    
    private fun createMediaItem(url: String): MediaItem {
        // Create a properly configured MediaItem with metadata
        val uri = Uri.parse(url)
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(url)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(getFileNameFromUrl(url))
                    .setArtist("TiddlyWiki Media")
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
    
    private fun getFileNameFromUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val path = uri.path
            path?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Unknown"
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

    fun isPlaying(): Boolean = player?.isPlaying == true
    
    var volume: Float
        get() = player?.volume ?: 1.0f
        set(value) {
            player?.volume = value.coerceIn(0f, 1f)
        }
        
    fun stop() {
        player?.stop()
        _currentPosition = 0
    }
}