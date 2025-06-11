/**
 * Optimized media functionality with reduced resource consumption
 * This replaces the complex system with lighter, more efficient handling
 */
fun injectOptimizedMediaFunctionalityScript(webView: WebView) {
    webView.evaluateJavascript("""
        (function() {
            // Early exit if already initialized
            if (window.OptimizedMediaInterface?.initialized) return;
            
            // Optimized MediaInterface with minimal background processing
            window.OptimizedMediaInterface = {
                initialized: true,
                activeElement: null,
                lastKnownState: { playing: false, position: 0, title: "" },
                isBackgroundMonitoring: false,
                monitoringInterval: null,
                
                // Lightweight play method without aggressive retry logic
                play() {
                    console.log('OptimizedMedia: Play command received');
                    const media = this.activeElement || this.findActiveMedia();
                    if (media?.paused) {
                        console.log('OptimizedMedia: Starting playback');
                        
                        const playPromise = media.play();
                        if (playPromise !== undefined) {
                            playPromise
                                .then(() => {
                                    console.log('OptimizedMedia: Play succeeded');
                                    this.lastKnownState.playing = true;
                                    this.updateState(media);
                                    this.startLightweightMonitoring();
                                })
                                .catch(e => {
                                    console.warn('Play failed:', e);
                                    // Simple fallback without aggressive retry
                                    this.updateState(media);
                                });
                        } else {
                            this.lastKnownState.playing = true;
                            this.updateState(media);
                            this.startLightweightMonitoring();
                        }
                    }
                },
                
                pause() {
                    console.log('OptimizedMedia: Pause command received');
                    const media = this.activeElement || this.findActiveMedia();
                    if (media && !media.paused) {
                        console.log('OptimizedMedia: Pausing playback');
                        this.lastKnownState.playing = false;
                        media.pause();
                        this.updateState(media);
                        this.stopLightweightMonitoring();
                    }
                },
                
                // Lightweight monitoring - only when playing, less frequent checks
                startLightweightMonitoring() {
                    if (this.monitoringInterval) {
                        clearInterval(this.monitoringInterval);
                    }
                    
                    console.log('OptimizedMedia: Starting lightweight monitoring');
                    this.isBackgroundMonitoring = true;
                    
                    // Check every 5 seconds instead of 2 seconds to reduce CPU usage
                    this.monitoringInterval = setInterval(() => {
                        const media = this.activeElement || this.findActiveMedia();
                        if (media && !media.paused) {
                            // Only update state if actually playing
                            this.updateState(media);
                        } else if (media && media.paused && this.lastKnownState.playing) {
                            // Simple detection of unexpected pause
                            console.log('OptimizedMedia: Detected pause, updating state');
                            this.lastKnownState.playing = false;
                            this.updateState(media);
                        }
                    }, 5000); // Increased from 2000ms to 5000ms
                },
                
                stopLightweightMonitoring() {
                    console.log('OptimizedMedia: Stopping lightweight monitoring');
                    this.isBackgroundMonitoring = false;
                    if (this.monitoringInterval) {
                        clearInterval(this.monitoringInterval);
                        this.monitoringInterval = null;
                    }
                },
                
                seekTo(positionMs) {
                    const media = this.activeElement || this.findActiveMedia();
                    if (media) {
                        const newTime = Math.max(0, Math.min(media.duration || 0, positionMs / 1000));
                        media.currentTime = newTime;
                        this.lastKnownState.position = newTime * 1000;
                        this.updateState(media);
                    }
                },
                
                skipForward() { this.skip(15); },
                skipBackward() { this.skip(-15); },
                
                skip(seconds) {
                    const media = this.activeElement || this.findActiveMedia();
                    if (media) {
                        const newTime = Math.max(0, Math.min(media.duration || 0, media.currentTime + seconds));
                        media.currentTime = newTime;
                        this.lastKnownState.position = newTime * 1000;
                        this.updateState(media);
                    }
                },
                
                // Simplified media detection without complex priority logic
                findActiveMedia() {
                    // Find currently playing media first
                    let media = document.querySelector('audio:not([paused]), video:not([paused])');
                    if (media) {
                        return media;
                    }
                    
                    // Find any media with current time > 0
                    const allMedia = document.querySelectorAll('audio, video, .tw-audio-element');
                    for (const m of allMedia) {
                        if (m.currentTime > 0) {
                            return m;
                        }
                    }
                    
                    // Fallback to any media element
                    return document.querySelector('audio, video, .tw-audio-element');
                },
                
                // Simplified state update with throttling
                updateState(media) {
                    if (!media) return;
                    
                    // Throttle updates to reduce Android communication overhead
                    clearTimeout(this.updateTimer);
                    this.updateTimer = setTimeout(() => {
                        const title = this.getMediaTitle(media);
                        const state = {
                            title,
                            duration: Math.round((media.duration || 0) * 1000),
                            position: Math.round((media.currentTime || 0) * 1000),
                            isPlaying: !media.paused
                        };
                        
                        this.lastKnownState.playing = state.isPlaying;
                        this.lastKnownState.position = state.position;
                        this.lastKnownState.title = title;
                        
                        // Simple Android notification without retry logic
                        this.notifyAndroid(state);
                    }, 250); // Throttle to max 4 updates per second
                },
                
                // Simple Android notification without complex retry logic
                notifyAndroid(state) {
                    try {
                        if (window.Android?.onMediaStateChange) {
                            window.Android.onMediaStateChange(
                                state.title, 
                                "TiddlyWiki", 
                                state.duration, 
                                state.position, 
                                state.isPlaying
                            );
                        }
                    } catch (e) {
                        console.warn('Android notification failed:', e);
                        // No retry logic to avoid performance issues
                    }
                },
                
                getMediaTitle(media) {
                    const tiddler = media.closest('[data-tiddler-title]');
                    return tiddler?.getAttribute('data-tiddler-title') || 
                           media.title || 
                           media.getAttribute('data-title') || 
                           'TiddlyWiki Audio';
                },
                
                // Optimized media element setup with minimal event handlers
                setupMediaElement(media) {
                    if (media.dataset.optimizedHandled) return;
                    media.dataset.optimizedHandled = 'true';
                    
                    console.log('Setting up optimized media element:', this.getMediaTitle(media));
                    
                    // Apply resource-saving configurations
                    this.optimizeMediaForPerformance(media);
                    
                    // Minimal essential event handlers only
                    const events = {
                        'play': () => {
                            this.activeElement = media;
                            this.lastKnownState.playing = true;
                            this.updateState(media);
                            this.startLightweightMonitoring();
                        },
                        'pause': () => {
                            this.lastKnownState.playing = false;
                            this.updateState(media);
                        },
                        'ended': () => {
                            this.activeElement = null;
                            this.lastKnownState.playing = false;
                            this.stopLightweightMonitoring();
                            this.updateState(media);
                        },
                        'timeupdate': this.throttle(() => {
                            if (!media.paused) {
                                this.updateState(media);
                            }
                        }, 2000), // Only update every 2 seconds during playback
                        'loadedmetadata': () => {
                            this.updateState(media);
                        }
                    };
                    
                    Object.entries(events).forEach(([event, handler]) => {
                        media.addEventListener(event, handler, { passive: true });
                    });
                },
                
                // Performance optimizations for media elements
                optimizeMediaForPerformance(media) {
                    // Optimize preloading strategy based on media type and context
                    if (media.tagName === 'AUDIO') {
                        // For audio, only preload metadata initially
                        media.preload = 'metadata';
                        
                        // Only switch to 'auto' preload when user interacts or when playing
                        const enableFullPreload = () => {
                            if (media.preload !== 'auto') {
                                media.preload = 'auto';
                                console.log('Enabled full preload for active audio');
                            }
                        };
                        
                        media.addEventListener('play', enableFullPreload, { once: true });
                        media.addEventListener('canplay', enableFullPreload, { once: true });
                        
                        // Disable pitch preservation for better performance
                        media.preservesPitch = false;
                    }
                    
                    if (media.tagName === 'VIDEO') {
                        // For video, be even more conservative
                        media.preload = 'none';
                        media.setAttribute('playsinline', 'true');
                        media.setAttribute('webkit-playsinline', 'true');
                        
                        // Only start preloading when user shows intent to play
                        const prepareVideo = () => {
                            media.preload = 'metadata';
                        };
                        
                        media.addEventListener('loadstart', prepareVideo, { once: true });
                        media.addEventListener('canplay', () => {
                            media.preload = 'auto';
                        }, { once: true });
                    }
                    
                    // Remove autoplay to prevent unexpected resource usage
                    media.removeAttribute('autoplay');
                    
                    // Add error recovery without aggressive retries
                    media.addEventListener('error', (e) => {
                        console.error('Media error:', e.error);
                        // Simple error state update, no retry attempts
                        this.updateState(media);
                    }, { passive: true });
                    
                    // Handle network issues gracefully
                    media.addEventListener('stalled', () => {
                        console.log('Media stalled, reducing quality expectations');
                        // Don't attempt aggressive recovery
                    }, { passive: true });
                },
                
                // Utility function for throttling
                throttle(func, delay) {
                    let timeoutId;
                    return function(...args) {
                        clearTimeout(timeoutId);
                        timeoutId = setTimeout(() => func.apply(this, args), delay);
                    };
                }
            };
            
            // Simplified setup without complex observers
            const setupAllMedia = () => {
                const selectors = ['audio', 'video', '.tw-audio-element'];
                let mediaCount = 0;
                
                selectors.forEach(selector => {
                    document.querySelectorAll(selector).forEach(media => {
                        window.OptimizedMediaInterface.setupMediaElement(media);
                        mediaCount++;
                    });
                });
                
                console.log(`Optimized media setup complete: ${mediaCount} elements processed`);
                
                // Simple visibility handling without aggressive background recovery
                if (!document.hasOptimizedVisibilityHandler) {
                    const handleVisibilityChange = () => {
                        if (document.visibilityState === 'hidden') {
                            console.log('Page hidden, reducing monitoring frequency');
                            // Stop monitoring to save resources in background
                            window.OptimizedMediaInterface.stopLightweightMonitoring();
                        } else {
                            console.log('Page visible, resuming normal monitoring');
                            const media = window.OptimizedMediaInterface.activeElement;
                            if (media && !media.paused) {
                                window.OptimizedMediaInterface.startLightweightMonitoring();
                            }
                        }
                    };
                    
                    document.addEventListener('visibilitychange', handleVisibilityChange);
                    document.hasOptimizedVisibilityHandler = true;
                }
            };
            
            // Simple observer for new media elements
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
                    // Debounce setup to avoid excessive processing
                    clearTimeout(observer.setupTimeout);
                    observer.setupTimeout = setTimeout(setupAllMedia, 500);
                }
            });
            
            // Initialize
            try {
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', setupAllMedia, { once: true });
                } else {
                    setupAllMedia();
                }
                
                observer.observe(document.body, { 
                    childList: true, 
                    subtree: true 
                });
                
                console.log('Optimized media functionality loaded with reduced resource usage');
                
            } catch (e) {
                console.error('Failed to initialize optimized media functionality:', e);
            }
        })();
    """.trimIndent(), null)
}
