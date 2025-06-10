package com.tiddlywikibrowser

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Simplified JavaScript interface for media control
 * This replaces complex callback systems with direct method calls
 */
class MediaJavaScriptInterface(private val mediaManager: OptimizedMediaManager) {
    
    companion object {
        private const val TAG = "MediaJSInterface"
    }
    
    @JavascriptInterface
    fun onMediaStateChange(title: String, artist: String, duration: Long, position: Long, isPlaying: Boolean) {
        try {
            mediaManager.updateMetadata(title, duration)
            mediaManager.updatePlaybackState(isPlaying, position)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating media state", e)
        }
    }
    
    @JavascriptInterface
    fun onMediaEvent(eventType: String, mediaId: String, currentTime: Double, duration: Double, 
                     source: String, title: String) {
        try {
            Log.d(TAG, "Media event: $eventType for $title")
            
            when (eventType) {
                "play" -> {
                    mediaManager.updateMetadata(title, (duration * 1000).toLong())
                    mediaManager.updatePlaybackState(true, (currentTime * 1000).toLong())
                }
                "pause" -> {
                    mediaManager.updatePlaybackState(false, (currentTime * 1000).toLong())
                }
                "ended" -> {
                    mediaManager.updatePlaybackState(false, 0)
                }
                "loadedmetadata" -> {
                    mediaManager.updateMetadata(title, (duration * 1000).toLong())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing media event", e)
        }
    }
    
    @JavascriptInterface
    fun updateMediaMetadata(title: String, artist: String, duration: Long) {
        try {
            mediaManager.updateMetadata(title, duration)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating metadata", e)
        }
    }
}
