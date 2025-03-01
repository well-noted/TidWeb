package com.tiddlywikibrowser

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class ExoPlayerManager(private val context: Context) {
    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var _currentPosition: Long = 0
    private var wasPlaying: Boolean = false

    fun getOrCreatePlayer(): ExoPlayer {
        if (player == null) {
            player = ExoPlayer.Builder(context).build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _currentPosition = player?.currentPosition ?: 0
                        (context as? MainActivity)?.let { activity ->
                            activity.mediaSessionManager.updatePlaybackState(isPlaying, _currentPosition)
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            player?.let { exoPlayer ->
                                _currentPosition = exoPlayer.currentPosition
                            }
                        }
                    }
                })
            }
        }
        return player!!
    }

    fun playMedia(url: String) {
        if (url != currentUrl) {
            currentUrl = url
            val mediaItem = MediaItem.fromUri(url)
            getOrCreatePlayer().apply {
                setMediaItem(mediaItem)
                prepare()
            }
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
}