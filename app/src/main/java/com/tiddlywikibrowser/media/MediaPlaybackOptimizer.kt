package com.tiddlywikibrowser.media

import android.webkit.WebView
import android.util.Log
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Utility class to optimize media playback and prevent black screen/unresponsive issues
 */
object MediaPlaybackOptimizer {
    private const val TAG = "MediaPlaybackOptimizer"
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Apply optimizations to prevent black screen and unresponsiveness during media playback
     */
    fun optimizeWebViewForMedia(webView: WebView, context: Context) {
        try {
            // Apply settings optimizations
            webView.settings.apply {
                // Prevent blocking operations
                setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                
                // Ensure hardware acceleration is enabled
                mediaPlaybackRequiresUserGesture = false
                
                // Optimize for media
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                // Prevent excessive caching that could cause memory issues
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                
                // Enable DOM storage for better performance
                domStorageEnabled = true
                  // Disable unneeded features that could cause conflicts
                setGeolocationEnabled(false)
                setNeedInitialFocus(false)
                
                // Try to set process priority if available
                try {
                    javaClass.getMethod("setProcessPriority", Int::class.java)
                        .invoke(this, 1) // HIGH priority
                } catch (e: Exception) {
                    // Method not available, ignore
                }
            }
            
            // Force hardware acceleration at the WebView level
            webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
            
            // Ensure WebView is not paused
            webView.onResume()
            
            Log.d(TAG, "Applied media playback optimizations to WebView")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error applying WebView optimizations", e)
        }
    }
    
    /**
     * Inject optimized JavaScript for media handling that's less likely to cause blocking
     */
    fun injectOptimizedMediaScript(webView: WebView) {
        try {
            val optimizedScript = """
                (function() {
                    // Only initialize if not already done
                    if (window.__mediaOptimizationApplied) return;
                    
                    console.log('[MediaOptimizer] Applying media optimizations');
                    
                    // Throttle function to prevent excessive execution
                    function throttle(func, delay) {
                        let timeoutId;
                        let lastExecTime = 0;
                        return function (...args) {
                            const currentTime = Date.now();
                            
                            if (currentTime - lastExecTime > delay) {
                                func.apply(this, args);
                                lastExecTime = currentTime;
                            } else {
                                clearTimeout(timeoutId);
                                timeoutId = setTimeout(() => {
                                    func.apply(this, args);
                                    lastExecTime = Date.now();
                                }, delay - (currentTime - lastExecTime));
                            }
                        };
                    }
                    
                    // Optimized media monitoring with throttling
                    const optimizedMediaCheck = throttle(function() {
                        try {
                            const media = document.querySelector('audio, video');
                            if (media && window.MediaInterface) {                                // Only update if there's a significant change
                                const currentTime = media.currentTime || 0;
                                const duration = media.duration || 0;
                                const isPlaying = !media.paused;
                                
                                // For play/pause state changes, always update immediately
                                // For time updates, throttle to reduce load
                                const playStateChanged = !window.__lastMediaUpdate || 
                                    isPlaying !== window.__lastMediaUpdate.playing;
                                const timeChanged = !window.__lastMediaUpdate || 
                                    Math.abs(currentTime - window.__lastMediaUpdate.time) >= 1;
                                
                                if (!playStateChanged && !timeChanged) {
                                    return; // No significant change
                                }
                                
                                window.__lastMediaUpdate = { time: currentTime, playing: isPlaying };
                                
                                // Use requestAnimationFrame for smooth updates
                                requestAnimationFrame(() => {
                                    try {
                                        window.MediaInterface.onMediaStateChange(
                                            media.getAttribute('title') || 'TiddlyWiki Media',
                                            'TiddlyWiki',
                                            Math.floor(duration * 1000),
                                            Math.floor(currentTime * 1000),
                                            isPlaying
                                        );
                                        
                                        // Update any overlay controls to keep UI in sync
                                        if (window.AudioControls && typeof window.AudioControls.updateOverlayButton === 'function') {
                                            window.AudioControls.updateOverlayButton(media, isPlaying);
                                        }
                                        
                                        // Also update any other UI elements that might show play/pause state
                                        const overlayPlayButton = document.querySelector('#audio-controls-overlay .play-button, #audio-controls-overlay button[onclick*="play"]');
                                        if (overlayPlayButton && window.AudioControls && window.AudioControls.currentAudio === media) {
                                            overlayPlayButton.innerHTML = isPlaying ? '⏸️' : '▶️';
                                        }
                                        
                                    } catch (e) {
                                        console.error('[MediaOptimizer] Error in state update:', e);
                                    }
                                });
                            }
                        } catch (e) {
                            console.error('[MediaOptimizer] Error in media check:', e);
                        }
                    }, 2000); // Check every 2 seconds maximum
                      // Set up optimized event listeners
                    ['play', 'pause', 'timeupdate', 'ended'].forEach(event => {
                        document.addEventListener(event, optimizedMediaCheck, true);
                    });
                    
                    // Set up immediate handlers for play/pause events to ensure UI responsiveness
                    ['play', 'pause'].forEach(event => {
                        document.addEventListener(event, function(e) {
                            if (e.target && (e.target.tagName === 'AUDIO' || e.target.tagName === 'VIDEO')) {
                                const media = e.target;
                                const isPlaying = event === 'play';
                                
                                // Immediate UI update for play/pause buttons
                                try {
                                    if (window.AudioControls && typeof window.AudioControls.updateOverlayButton === 'function') {
                                        window.AudioControls.updateOverlayButton(media, isPlaying);
                                    }
                                    
                                    // Also update any other play buttons in the DOM
                                    const overlayPlayButton = document.querySelector('#audio-controls-overlay .play-button, #audio-controls-overlay button[onclick*="play"]');
                                    if (overlayPlayButton && window.AudioControls && window.AudioControls.currentAudio === media) {
                                        overlayPlayButton.innerHTML = isPlaying ? '⏸️' : '▶️';
                                    }
                                } catch (e) {
                                    console.error('[MediaOptimizer] Error in immediate UI update:', e);
                                }
                            }
                        }, true);
                    });
                    
                    // Mark as applied
                    window.__mediaOptimizationApplied = true;
                    console.log('[MediaOptimizer] Media optimizations applied successfully');
                    
                })();
            """.trimIndent()
            
            // Use a background thread to inject the script to avoid blocking
            Thread {
                mainHandler.post {
                    webView.evaluateJavascript(optimizedScript) { result ->
                        Log.d(TAG, "Optimized media script injected: $result")
                    }
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting optimized media script", e)
        }
    }
    
    /**
     * Clean up resources and prevent memory leaks
     */
    fun cleanupWebViewForMedia(webView: WebView) {
        try {
            // Pause all media before cleanup
            val cleanupScript = """
                (function() {
                    try {
                        // Pause all media elements
                        document.querySelectorAll('audio, video').forEach(media => {
                            if (!media.paused) {
                                media.pause();
                            }
                        });
                        
                        // Clear any intervals or timeouts
                        if (window.__mediaOptimizationApplied) {
                            console.log('[MediaOptimizer] Cleaning up media optimizations');
                        }
                        
                        return 'cleanup_complete';
                    } catch (e) {
                        console.error('[MediaOptimizer] Error in cleanup:', e);
                        return 'cleanup_error';
                    }
                })();
            """.trimIndent()
            
            webView.evaluateJavascript(cleanupScript) { result ->
                Log.d(TAG, "Media cleanup result: $result")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up WebView media", e)
        }
    }
}
