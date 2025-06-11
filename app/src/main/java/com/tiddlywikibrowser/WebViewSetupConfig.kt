package com.tiddlywikibrowser

import android.content.Context
import android.os.Build
import android.webkit.WebSettings

class WebViewSetupConfig(private val wiki: WikiInstance, private val context: Context) {
    fun applySettings(settings: WebSettings) {
        ThreadManager.runOnMain {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                
                // Cache settings - avoid deprecated methods
                databasePath = context.getDir("databases", Context.MODE_PRIVATE).path
                
                // Performance optimizations for media playback
                loadsImagesAutomatically = false  // We'll enable this after initial load
                blockNetworkImage = true // Initially block images to speed up first render
                cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                
                // Optimize for media playback with reduced resource usage
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                
                // Memory management for better performance on hot devices
                setRenderPriority(WebSettings.RenderPriority.HIGH)
                
                // Reduce memory usage for media
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false // Disable if not needed to save memory
                }
                
                // Enable hardware acceleration if available
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    forceDark = WebSettings.FORCE_DARK_AUTO
                }
                
                // Disable features that consume resources unnecessarily
                setNeedInitialFocus(false)
                setSupportZoom(false) // Disable if not needed for media apps
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
    
    fun getInitialScripts(): List<String> = listOf(
        // Optimize scroll performance
        """
        (function() {
            document.addEventListener('scroll', function(e) {
                if (!e.target.hasAttribute('data-scroll-optimized')) {
                    e.target.style.willChange = 'transform';
                    e.target.setAttribute('data-scroll-optimized', 'true');
                }
            }, { passive: true });
        })();
        """.trimIndent(),
        
        // Defer non-critical resources
        """
        (function() {
            document.querySelectorAll('img[loading]').forEach(img => {
                img.loading = 'lazy';
                img.decoding = 'async';
            });
        })();
        """.trimIndent()
    )
    
    fun getErrorHandler(): String = """
        window.onerror = function(msg, url, line) {
            console.log('JavaScript error: ' + msg + '\nURL: ' + url + '\nLine: ' + line);
            return false;
        };
    """.trimIndent()
}