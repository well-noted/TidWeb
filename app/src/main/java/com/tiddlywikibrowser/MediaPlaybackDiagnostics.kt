package com.tiddlywikibrowser

import android.content.Context
import android.util.Log

/**
 * Diagnostic tool to help troubleshoot background media playback issues
 */
object MediaPlaybackDiagnostics {
    private const val TAG = "MediaPlaybackDiag"
    
    fun diagnoseBackgroundPlayback(context: Context): String {
        val report = StringBuilder()
        report.appendLine("=== MEDIA PLAYBACK DIAGNOSTICS ===")
        report.appendLine("Timestamp: ${System.currentTimeMillis()}")
        
        try {
            val mainActivity = context as? MainActivity
            if (mainActivity != null) {
                report.appendLine("\n--- MainActivity Status ---")
                report.appendLine("Activity exists: true")
                
                // Check MediaSessionManager
                try {
                    val mediaManager = mainActivity.mediaSessionManager
                    report.appendLine("MediaSessionManager exists: true")
                    report.appendLine("Service bound: ${mediaManager.isServiceBound()}")
                    report.appendLine("Media session active: ${mediaManager.getMediaSession()?.isActive}")
                } catch (e: Exception) {
                    report.appendLine("MediaSessionManager error: ${e.message}")
                }
                
                // Check BackgroundWebViewManager
                try {
                    val bgManager = mainActivity.backgroundWebViewManager
                    report.appendLine("BackgroundWebViewManager exists: ${bgManager != null}")
                    if (bgManager != null) {
                        report.appendLine("Background service bound: ${bgManager.service != null}")
                        report.appendLine("Background enabled: ${bgManager.isBackgroundEnabled.value}")
                    }
                } catch (e: Exception) {
                    report.appendLine("BackgroundWebViewManager error: ${e.message}")
                }
                
                // Check WebView
                try {
                    val webView = mainActivity.getCurrentWebView()
                    report.appendLine("Current WebView exists: ${webView != null}")
                    if (webView != null) {
                        webView.evaluateJavascript("""
                            (function() {
                                const media = document.querySelector('audio,video');
                                return JSON.stringify({
                                    hasMedia: media != null,
                                    isPlaying: media ? !media.paused : false,
                                    currentTime: media ? media.currentTime : 0,
                                    duration: media ? media.duration : 0,
                                    src: media ? media.src : null
                                });
                            })();
                        """.trimIndent()) { result ->
                            Log.d(TAG, "WebView media state: $result")
                        }
                    }
                } catch (e: Exception) {
                    report.appendLine("WebView check error: ${e.message}")
                }
                
            } else {
                report.appendLine("MainActivity not available (context type: ${context.javaClass.simpleName})")
            }
            
        } catch (e: Exception) {
            report.appendLine("Overall diagnostic error: ${e.message}")
            Log.e(TAG, "Diagnostic error", e)
        }
        
        val finalReport = report.toString()
        Log.d(TAG, finalReport)
        return finalReport
    }
    
    /**
     * Test media control functionality
     */
    fun testMediaControl(context: Context, action: String): String {
        val result = StringBuilder()
        result.appendLine("=== MEDIA CONTROL TEST: $action ===")
        
        try {
            val mainActivity = context as? MainActivity
            if (mainActivity != null) {
                val mediaManager = mainActivity.mediaSessionManager
                
                when (action.toLowerCase()) {
                    "play" -> {
                        result.appendLine("Executing play command...")
                        // This will trigger the same path as media session callback
                        mediaManager.updatePlaybackState(true, 0)
                    }
                    "pause" -> {
                        result.appendLine("Executing pause command...")
                        mediaManager.updatePlaybackState(false, 0)
                    }
                    "background_js" -> {
                        result.appendLine("Testing background JavaScript execution...")
                        mediaManager.onAppBackgrounded()
                        
                        // Give it a moment to switch to background mode
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            // This should trigger background WebView execution
                            mainActivity.getCurrentWebView()?.evaluateJavascript("""
                                console.log('Testing background JS execution: ' + new Date());
                                return 'background_test_complete';
                            """.trimIndent()) { jsResult ->
                                Log.d(TAG, "Background JS test result: $jsResult")
                            }
                        }, 500)
                    }
                }
                
            } else {
                result.appendLine("MainActivity not available")
            }
            
        } catch (e: Exception) {
            result.appendLine("Test error: ${e.message}")
            Log.e(TAG, "Test error", e)
        }
        
        val finalResult = result.toString()
        Log.d(TAG, finalResult)
        return finalResult
    }
}
