package com.tiddlywikibrowser

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView

class OptimizedWebViewSetupConfig(private val wiki: WikiInstance, private val context: Context) {
    
    fun applyOptimizedSettings(settings: WebSettings) {
        ThreadManager.runOnMain {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                
                // Memory and performance optimizations
                databasePath = context.getDir("databases", Context.MODE_PRIVATE).path
                
                // Aggressive performance settings for media-heavy content
                loadsImagesAutomatically = false  // Load images only when needed
                blockNetworkImage = true // Initially block images
                cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                  // Reduce memory usage for media playback
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false // Disable if not needed to save memory
                }
                
                // Optimize for media playback
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                
                // Memory management for better performance on hot devices
                setRenderPriority(WebSettings.RenderPriority.HIGH)
                
                // Note: setAppCacheMaxSize is deprecated, using alternative approach
                cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                
                // Optimize for battery and thermal management
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    forceDark = WebSettings.FORCE_DARK_AUTO
                }
                
                // Disable features that consume resources unnecessarily
                setNeedInitialFocus(false)
                setSupportZoom(false) // Disable if not needed
                setBuiltInZoomControls(false)
                setDisplayZoomControls(false)
                
                // Optimize text rendering for performance
                textZoom = 100
                minimumFontSize = 8
                minimumLogicalFontSize = 8
                defaultFixedFontSize = 13
                defaultFontSize = 16
            }
        }
    }
      
    fun applyLowMemoryOptimizations(webView: WebView) {
        ThreadManager.runOnMain {
            try {
                // Reduce memory footprint for media playback
                webView.settings.apply {
                    // Conservative cache settings - using cache mode instead of deprecated methods
                    cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    
                    // Disable unnecessary features that consume memory
                    setGeolocationEnabled(false)
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    
                    // Optimize for single-threaded performance
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                    }
                }
                
                // Clear any existing cache periodically during heavy usage
                webView.clearCache(false) // Don't clear everything, just temporary files
                
            } catch (e: Exception) {
                android.util.Log.w("OptimizedWebView", "Failed to apply low memory optimizations", e)
            }
        }
    }
    
    fun getThermalOptimizationScripts(): List<String> = listOf(
        // Reduce JavaScript execution frequency during thermal throttling
        """
        (function() {
            if (navigator.deviceMemory && navigator.deviceMemory < 4) {
                // Reduce update frequency on low-memory devices
                console.log('Low memory device detected, applying optimizations');
                
                // Reduce animation frame requests
                const originalRAF = window.requestAnimationFrame;
                let rafThrottle = false;
                window.requestAnimationFrame = function(callback) {
                    if (rafThrottle) return;
                    rafThrottle = true;
                    setTimeout(() => rafThrottle = false, 32); // ~30fps instead of 60fps
                    return originalRAF(callback);
                };
            }
        })();
        """.trimIndent(),
        
        // Optimize media loading based on network and device conditions
        """
        (function() {
            // Monitor device performance and adjust media loading
            const isLowEndDevice = navigator.deviceMemory <= 2 || 
                                  navigator.hardwareConcurrency <= 2 ||
                                  /Android [4-6]/.test(navigator.userAgent);
                                  
            if (isLowEndDevice) {
                console.log('Low-end device detected, applying media optimizations');
                
                // Aggressively limit media preloading
                document.addEventListener('DOMContentLoaded', function() {
                    document.querySelectorAll('audio, video').forEach(media => {
                        media.preload = 'none';
                        
                        // Only load metadata when user shows interest
                        const prepareMedia = () => {
                            media.preload = 'metadata';
                        };
                        
                        media.addEventListener('mouseenter', prepareMedia, { once: true });
                        media.addEventListener('touchstart', prepareMedia, { once: true });
                        media.addEventListener('focus', prepareMedia, { once: true });
                    });
                });
            }
        })();
        """.trimIndent(),
        
        // Implement intelligent garbage collection hints
        """
        (function() {
            let lastGCHint = 0;
            const gcInterval = 30000; // 30 seconds
            
            // Suggest garbage collection during idle periods
            function suggestGC() {
                const now = Date.now();
                if (now - lastGCHint > gcInterval) {
                    lastGCHint = now;
                    
                    // Clean up unused objects
                    if (window.gc && typeof window.gc === 'function') {
                        try {
                            window.gc();
                        } catch (e) {
                            // GC not available
                        }
                    }
                    
                    // Clean up media elements that are no longer visible
                    document.querySelectorAll('audio, video').forEach(media => {
                        const rect = media.getBoundingClientRect();
                        const isVisible = rect.top < window.innerHeight && rect.bottom > 0;
                        
                        if (!isVisible && media.paused && media.currentTime === 0) {
                            // Reset src to free memory for unused media
                            const originalSrc = media.src;
                            media.removeAttribute('src');
                            media.load();
                            
                            // Restore src when element becomes visible again
                            const restoreOnVisible = () => {
                                const rect = media.getBoundingClientRect();
                                if (rect.top < window.innerHeight && rect.bottom > 0) {
                                    media.src = originalSrc;
                                    window.removeEventListener('scroll', restoreOnVisible);
                                }
                            };
                            
                            window.addEventListener('scroll', restoreOnVisible, { passive: true });
                        }
                    });
                }
            }
            
            // Run GC suggestion during idle time
            if (window.requestIdleCallback) {
                window.requestIdleCallback(suggestGC);
                setInterval(() => {
                    window.requestIdleCallback(suggestGC);
                }, gcInterval);
            } else {
                setInterval(suggestGC, gcInterval);
            }
        })();
        """.trimIndent(),
        
        // Monitor and adapt to thermal conditions
        """
        (function() {
            // Reduce processing when device might be thermal throttling
            let performanceMode = 'normal';
            
            // Simple heuristic to detect potential thermal issues
            const monitorPerformance = () => {
                const now = performance.now();
                setTimeout(() => {
                    const elapsed = performance.now() - now;
                    const expectedTime = 100; // Expected ~100ms for setTimeout(100)
                    
                    if (elapsed > expectedTime * 1.5) {
                        // Significant delay suggests thermal throttling
                        if (performanceMode !== 'reduced') {
                            performanceMode = 'reduced';
                            console.log('Thermal throttling detected, reducing media processing');
                            
                            // Reduce media monitoring frequency
                            if (window.OptimizedMediaInterface) {
                                window.OptimizedMediaInterface.stopLightweightMonitoring();
                                // Restart with even longer intervals
                                if (window.OptimizedMediaInterface.isBackgroundMonitoring) {
                                    setTimeout(() => {
                                        window.OptimizedMediaInterface.startLightweightMonitoring();
                                    }, 10000); // Wait 10 seconds before resuming
                                }
                            }
                        }
                    } else if (elapsed < expectedTime * 1.2 && performanceMode === 'reduced') {
                        performanceMode = 'normal';
                        console.log('Performance normalized, resuming normal operation');
                    }
                }, 100);
            };
            
            // Monitor performance every 30 seconds
            setInterval(monitorPerformance, 30000);
        })();
        """.trimIndent()
    )
    
    fun getErrorHandler(): String = """
        window.onerror = function(msg, url, line, col, error) {
            console.log('JavaScript error: ' + msg + '\nURL: ' + url + '\nLine: ' + line);
            
            // Don't block media playback due to unrelated errors
            if (msg.indexOf('media') === -1 && msg.indexOf('audio') === -1 && msg.indexOf('video') === -1) {
                return false;
            }
            
            // For media-related errors, try to recover gracefully
            setTimeout(() => {
                if (window.OptimizedMediaInterface) {
                    const media = window.OptimizedMediaInterface.findActiveMedia();
                    if (media && media.error) {
                        console.log('Attempting to recover from media error');
                        media.load();
                    }
                }
            }, 1000);
            
            return false;
        };
    """.trimIndent()
}
