package com.tiddlywikibrowser

import android.webkit.WebView

/**
 * Example showing how to replace the existing complex media system 
 * with the optimized version in WikiViewComposable.kt
 * 
 * This demonstrates a simple drop-in replacement approach
 */
object MediaOptimizationExample {
    
    /**
     * OPTION 1: Replace the existing injectMediaFunctionalityScript method
     * 
     * In WikiViewComposable.kt, replace the existing method with this optimized version:
     */
    fun injectOptimizedMediaFunctionalityScript(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Early exit if already initialized to prevent duplicate setup
                if (window.TidWebOptimizedMedia?.initialized) return;
                
                // Create optimized media interface
                window.TidWebOptimizedMedia = {
                    initialized: true,
                    activeMedia: null,
                    
                    // Core control methods - simplified and efficient
                    play() {
                        const media = this.getActiveMedia();
                        if (media?.paused) {
                            media.play().catch(e => console.warn('Play failed:', e));
                            this.notifyAndroid(media);
                        }
                    },
                    
                    pause() {
                        const media = this.getActiveMedia();
                        if (media && !media.paused) {
                            media.pause();
                            this.notifyAndroid(media);
                        }
                    },
                    
                    seekTo(positionMs) {
                        const media = this.getActiveMedia();
                        if (media) {
                            media.currentTime = Math.max(0, Math.min(media.duration || 0, positionMs / 1000));
                            this.notifyAndroid(media);
                        }
                    },
                    
                    skipForward() { this.skip(15); },
                    skipBackward() { this.skip(-15); },
                    
                    skip(seconds) {
                        const media = this.getActiveMedia();
                        if (media) {
                            const newTime = Math.max(0, Math.min(media.duration || 0, media.currentTime + seconds));
                            media.currentTime = newTime;
                            this.notifyAndroid(media);
                        }
                    },
                    
                    // Smart media detection
                    getActiveMedia() {
                        return this.activeMedia || 
                               document.querySelector('audio:not([paused]), video:not([paused])') ||
                               document.querySelector('.tw-audio-element:not([paused])') ||
                               document.querySelector('audio, video, .tw-audio-element');
                    },
                    
                    // Optimized Android notification with throttling
                    notifyAndroid(media) {
                        if (!media || !window.Android) return;
                        
                        clearTimeout(this.notifyTimer);
                        this.notifyTimer = setTimeout(() => {
                            try {
                                const title = this.getMediaTitle(media);
                                const state = this.getMediaState(media);
                                
                                // Update media session
                                window.Android.onMediaStateChange?.(
                                    title, 'TiddlyWiki', 
                                    Math.round(state.duration * 1000),
                                    Math.round(state.position * 1000),
                                    state.isPlaying
                                );
                                
                                // Update metadata if needed
                                window.Android.updateMediaMetadata?.(title, 'TiddlyWiki', Math.round(state.duration * 1000));
                            } catch (e) {
                                console.warn('Android notification failed:', e);
                            }
                        }, 100); // Throttle updates
                    },
                    
                    getMediaTitle(media) {
                        const tiddler = media.closest('[data-tiddler-title]');
                        return tiddler?.getAttribute('data-tiddler-title') || 
                               media.title || 
                               media.getAttribute('data-title') || 
                               'TiddlyWiki Audio';
                    },
                    
                    getMediaState(media) {
                        return {
                            duration: media.duration || 0,
                            position: media.currentTime || 0,
                            isPlaying: !media.paused
                        };
                    },
                    
                    // Efficient event setup for media elements
                    setupMediaElement(media) {
                        if (media.dataset.optimizedSetup) return;
                        media.dataset.optimizedSetup = 'true';
                        
                        const events = ['play', 'pause', 'ended', 'loadedmetadata'];
                        const timeUpdateHandler = this.throttle(() => {
                            if (!media.paused) this.notifyAndroid(media);
                        }, 1000);
                        
                        events.forEach(event => {
                            media.addEventListener(event, (e) => {
                                if (e.type === 'play') this.activeMedia = media;
                                else if (e.type === 'ended') this.activeMedia = null;
                                
                                this.notifyAndroid(media);
                                
                                // Send event notification
                                try {
                                    window.Android.onMediaEvent?.(
                                        e.type, media.id || 'media',
                                        media.currentTime || 0, media.duration || 0,
                                        media.currentSrc || media.src || '',
                                        this.getMediaTitle(media)
                                    );
                                } catch (err) {
                                    console.warn('Event notification failed:', err);
                                }
                            }, { passive: true });
                        });
                        
                        // Add throttled timeupdate
                        media.addEventListener('timeupdate', timeUpdateHandler, { passive: true });
                    },
                    
                    // Utility: throttle function
                    throttle(func, delay) {
                        let timeoutId;
                        return function(...args) {
                            clearTimeout(timeoutId);
                            timeoutId = setTimeout(() => func.apply(this, args), delay);
                        };
                    }
                };
                
                // Auto-setup existing and new media elements
                const setupAllMedia = () => {
                    document.querySelectorAll('audio, video, .tw-audio-element').forEach(media => {
                        window.TidWebOptimizedMedia.setupMediaElement(media);
                    });
                };
                
                // Optimized mutation observer
                const observer = new MutationObserver(mutations => {
                    const hasMediaChanges = mutations.some(mutation => 
                        Array.from(mutation.addedNodes).some(node => 
                            node.nodeType === 1 && (
                                node.matches?.('audio, video, .tw-audio-element') ||
                                node.querySelector?.('audio, video, .tw-audio-element')
                            )
                        )
                    );
                    
                    if (hasMediaChanges) {
                        clearTimeout(observer.setupTimeout);
                        observer.setupTimeout = setTimeout(setupAllMedia, 150);
                    }
                });
                
                // Initialize
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', setupAllMedia, { once: true });
                } else {
                    setupAllMedia();
                }
                
                observer.observe(document.body, { childList: true, subtree: true });
                
                // Maintain compatibility with existing MediaInterface
                window.MediaInterface = window.TidWebOptimizedMedia;
                
                console.log('TidWeb optimized media system initialized');
            })();
        """.trimIndent(), null)
    }
    
    /**
     * OPTION 2: Direct integration approach
     * 
     * Call this in your MainActivity or WebView setup instead of complex managers
     */
    fun setupOptimizedMediaForWebView(webView: WebView, context: android.content.Context) {
        try {
            // Replace complex setup with simple optimized approach
            val mediaManager = OptimizedMediaManager.getInstance(context)
            val jsInterface = MediaJavaScriptInterface(mediaManager)
            
            // Clean setup
            webView.addJavascriptInterface(jsInterface, "Android")
            webView.settings.mediaPlaybackRequiresUserGesture = false
            
            // Inject optimized script
            injectOptimizedMediaFunctionalityScript(webView)
            
            // Set WebView reference
            mediaManager.setWebView(webView)
            
            android.util.Log.d("MediaOptimization", "Optimized media setup complete")
        } catch (e: Exception) {
            android.util.Log.e("MediaOptimization", "Failed to setup optimized media", e)
        }
    }
    
    /**
     * OPTION 3: Migration helper
     * 
     * Gradual replacement - use this to test the new system alongside the old one
     */
    fun enableOptimizedMediaForTesting(webView: WebView, context: android.content.Context) {
        // This adds the optimized system without removing the existing one
        // Useful for A/B testing or gradual rollout
        
        webView.evaluateJavascript("""
            // Test optimized media alongside existing system
            if (!window.TestOptimizedMedia) {
                console.log('Enabling optimized media test mode');
                
                // Save reference to existing system
                window.OriginalMediaInterface = window.MediaInterface;
                
                // Your optimized system code here (shortened for example)
                window.TestOptimizedMedia = {
                    enabled: true,
                    // ... optimized implementation
                };
                
                // Switch to optimized system
                window.MediaInterface = window.TestOptimizedMedia;
                
                console.log('Optimized media test mode enabled');
            }
        """.trimIndent(), null)
    }
    
    /**
     * Quick performance comparison
     */
    fun comparePerformance(webView: WebView) {
        webView.evaluateJavascript("""
            console.time('MediaSetup');
            // Your media setup code here
            console.timeEnd('MediaSetup');
            
            console.log('Active listeners:', document.querySelectorAll('[data-media-observer-attached]').length);
            console.log('Memory usage:', performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1024 / 1024) + 'MB' : 'N/A');
        """.trimIndent(), null)
    }
}

/**
 * Extension for easy integration
 */
fun WebView.enableOptimizedMedia(context: android.content.Context) {
    MediaOptimizationExample.setupOptimizedMediaForWebView(this, context)
}
