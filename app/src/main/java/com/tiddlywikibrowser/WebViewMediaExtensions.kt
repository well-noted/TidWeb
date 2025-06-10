package com.tiddlywikibrowser

import android.webkit.WebView

/**
 * Extension functions for integrating optimized media functionality
 * This shows how to replace the existing complex system with the simplified one
 */

/**
 * Setup optimized media functionality for a WebView
 * Call this instead of the old complex setup methods
 */
fun WebView.setupOptimizedMedia(context: android.content.Context) {
    try {
        // Get the optimized media manager instance
        val mediaManager = OptimizedMediaManager.getInstance(context)
        
        // Set this WebView as the active one
        mediaManager.setWebView(this)
        
        // Add the simplified JavaScript interface
        val jsInterface = MediaJavaScriptInterface(mediaManager)
        addJavascriptInterface(jsInterface, "Android")
        
        // Enable media playback
        settings.mediaPlaybackRequiresUserGesture = false
        
        android.util.Log.d("WebViewMedia", "Optimized media setup complete")
    } catch (e: Exception) {
        android.util.Log.e("WebViewMedia", "Failed to setup optimized media", e)
    }
}

/**
 * Clean up media functionality when WebView is destroyed
 */
fun WebView.cleanupOptimizedMedia(context: android.content.Context) {
    try {
        val mediaManager = OptimizedMediaManager.getInstance(context)
        mediaManager.setWebView(null)
        removeJavascriptInterface("Android")
    } catch (e: Exception) {
        android.util.Log.e("WebViewMedia", "Failed to cleanup media", e)
    }
}

/**
 * Inject the optimized media script directly (alternative approach)
 */
fun WebView.injectOptimizedMediaScript() {
    evaluateJavascript("""
        (function() {
            if (window.TidWebMedia) return; // Already loaded
            
            window.TidWebMedia = {
                activeMedia: null,
                
                init() {
                    // Setup media detection
                    this.observeMedia();
                    this.setupExistingMedia();
                },
                
                observeMedia() {
                    const observer = new MutationObserver(mutations => {
                        mutations.forEach(mutation => {
                            mutation.addedNodes.forEach(node => {
                                if (node.nodeType === 1) {
                                    if (node.matches('audio, video, .tw-audio-element')) {
                                        this.setupMediaElement(node);
                                    } else {
                                        node.querySelectorAll('audio, video, .tw-audio-element')
                                            .forEach(media => this.setupMediaElement(media));
                                    }
                                }
                            });
                        });
                    });
                    
                    observer.observe(document.body, { childList: true, subtree: true });
                },
                
                setupExistingMedia() {
                    document.querySelectorAll('audio, video, .tw-audio-element')
                        .forEach(media => this.setupMediaElement(media));
                },
                
                setupMediaElement(media) {
                    if (media.dataset.tidwebSetup) return;
                    media.dataset.tidwebSetup = 'true';
                    
                    // Efficient event handling
                    const events = {
                        play: () => {
                            this.activeMedia = media;
                            this.notifyAndroid(media, 'play');
                        },
                        pause: () => this.notifyAndroid(media, 'pause'),
                        ended: () => {
                            this.activeMedia = null;
                            this.notifyAndroid(media, 'ended');
                        },
                        loadedmetadata: () => this.notifyAndroid(media, 'loadedmetadata'),
                        timeupdate: this.throttle(() => this.notifyAndroid(media, 'timeupdate'), 1000)
                    };
                    
                    Object.entries(events).forEach(([event, handler]) => {
                        media.addEventListener(event, handler, { passive: true });
                    });
                },
                
                notifyAndroid(media, event) {
                    if (!window.Android) return;
                    
                    const title = this.getTitle(media);
                    const state = this.getMediaState(media);
                    
                    try {
                        if (event === 'timeupdate' || event === 'play' || event === 'pause') {
                            window.Android.onMediaStateChange(
                                title, 'TiddlyWiki', 
                                Math.round(state.duration * 1000),
                                Math.round(state.position * 1000),
                                state.isPlaying
                            );
                        }
                        
                        window.Android.onMediaEvent(
                            event, media.id || 'media',
                            state.position, state.duration,
                            media.currentSrc || media.src || '', title
                        );
                    } catch (e) {
                        console.warn('Android notification failed:', e);
                    }
                },
                
                getTitle(media) {
                    const tiddler = media.closest('[data-tiddler-title]');
                    return tiddler?.getAttribute('data-tiddler-title') || 
                           media.title || 'TiddlyWiki Media';
                },
                
                getMediaState(media) {
                    return {
                        duration: media.duration || 0,
                        position: media.currentTime || 0,
                        isPlaying: !media.paused
                    };
                },
                
                throttle(func, delay) {
                    let timeoutId;
                    return function(...args) {
                        clearTimeout(timeoutId);
                        timeoutId = setTimeout(() => func.apply(this, args), delay);
                    };
                },
                
                // Control methods for Android
                play() {
                    const media = this.activeMedia || this.findPlayableMedia();
                    if (media?.paused) media.play().catch(() => {});
                },
                
                pause() {
                    const media = this.activeMedia || this.findPlayableMedia();
                    if (media && !media.paused) media.pause();
                },
                
                seekTo(ms) {
                    const media = this.activeMedia || this.findPlayableMedia();
                    if (media) media.currentTime = Math.max(0, Math.min(media.duration, ms / 1000));
                },
                
                skip(seconds) {
                    const media = this.activeMedia || this.findPlayableMedia();
                    if (media) {
                        const newTime = Math.max(0, Math.min(media.duration, media.currentTime + seconds));
                        media.currentTime = newTime;
                    }
                },
                
                findPlayableMedia() {
                    return document.querySelector('audio:not([paused]), video:not([paused])') ||
                           document.querySelector('audio, video, .tw-audio-element');
                }
            };
            
            // Auto-initialize when ready
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', () => window.TidWebMedia.init());
            } else {
                window.TidWebMedia.init();
            }
            
            // Expose control methods globally for Android access
            window.MediaInterface = {
                play: () => window.TidWebMedia.play(),
                pause: () => window.TidWebMedia.pause(),
                seekTo: (ms) => window.TidWebMedia.seekTo(ms),
                skipForward: () => window.TidWebMedia.skip(15),
                skipBackward: () => window.TidWebMedia.skip(-15)
            };
            
            console.log('TidWeb optimized media system loaded');
        })();
    """.trimIndent(), null)
}
