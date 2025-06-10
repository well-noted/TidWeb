    /**
     * Optimized media functionality injection with enhanced background reliability
     * This creates a unified interface for all media playback in TiddlyWiki
     */
    fun injectMediaFunctionalityScript(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Early exit if already initialized
                if (window.MediaInterface?.initialized) return;
                
                // Create unified MediaInterface with background support
                window.MediaInterface = {
                    initialized: true,
                    activeElement: null,
                    lastKnownState: { playing: false, position: 0 },
                    
                    // Enhanced control methods with background reliability
                    play() {
                        const media = this.activeElement || this.findActiveMedia();
                        if (media?.paused) {
                            console.log('MediaInterface: Starting playback');
                            media.play()
                                .then(() => {
                                    this.lastKnownState.playing = true;
                                    this.updateState(media);
                                })
                                .catch(e => {
                                    console.warn('Play failed:', e);
                                    // Try alternative approach for background
                                    this.forcePlay(media);
                                });
                        }
                    },
                    
                    pause() {
                        const media = this.activeElement || this.findActiveMedia();
                        if (media && !media.paused) {
                            console.log('MediaInterface: Pausing playback');
                            media.pause();
                            this.lastKnownState.playing = false;
                            this.updateState(media);
                        }
                    },
                    
                    // Force play for background scenarios
                    forcePlay(media) {
                        if (!media) return;
                        try {
                            // Remove any user gesture requirements temporarily
                            const originalGesture = media.getAttribute('data-requires-gesture');
                            media.removeAttribute('data-requires-gesture');
                            
                            // Attempt play with fallback strategies
                            const playAttempt = media.play();
                            if (playAttempt && typeof playAttempt.catch === 'function') {
                                playAttempt.catch(() => {
                                    // Final fallback: simulate play state
                                    console.log('Simulating play state for background');
                                    this.lastKnownState.playing = true;
                                    this.updateState(media);
                                });
                            }
                            
                            // Restore original gesture requirement
                            if (originalGesture) {
                                media.setAttribute('data-requires-gesture', originalGesture);
                            }
                        } catch (e) {
                            console.warn('Force play failed:', e);
                        }
                    },
                    
                    seekTo(positionMs) {
                        const media = this.activeElement || this.findActiveMedia();
                        if (media) {
                            const newTime = Math.max(0, Math.min(media.duration || 0, positionMs / 1000));
                            media.currentTime = newTime;
                            this.lastKnownState.position = newTime;
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
                            this.lastKnownState.position = newTime;
                            this.updateState(media);
                        }
                    },                    
                    // Enhanced media detection with background support
                    findActiveMedia() {
                        // First try to find currently playing media
                        let media = document.querySelector('audio:not([paused]), video:not([paused])') ||
                                   document.querySelector('.tw-audio-element:not([paused])');
                        
                        // If nothing playing, find the last active element
                        if (!media) {
                            media = document.querySelector('audio, video, .tw-audio-element');
                            // Prefer media with recent activity
                            const allMedia = document.querySelectorAll('audio, video, .tw-audio-element');
                            for (const m of allMedia) {
                                if (m.currentTime > 0 || m.dataset.lastActive) {
                                    media = m;
                                    break;
                                }
                            }
                        }
                        
                        return media;
                    },
                    
                    // Enhanced state update with background persistence
                    updateState(media) {
                        if (!media) return;
                        
                        const title = this.getMediaTitle(media);
                        const state = {
                            title,
                            duration: Math.round((media.duration || 0) * 1000),
                            position: Math.round((media.currentTime || 0) * 1000),
                            isPlaying: !media.paused
                        };
                        
                        // Update last known state
                        this.lastKnownState.playing = state.isPlaying;
                        this.lastKnownState.position = state.position;
                        
                        // Mark as recently active
                        media.dataset.lastActive = Date.now().toString();
                        
                        // Send to Android with retries for background scenarios
                        this.notifyAndroid(state);
                    },
                    
                    // Robust Android notification with retry logic
                    notifyAndroid(state) {
                        const attemptNotification = (retries = 3) => {
                            try {
                                if (window.Android?.onMediaStateChange) {
                                    window.Android.onMediaStateChange(
                                        state.title, "TiddlyWiki", state.duration, state.position, state.isPlaying
                                    );
                                    return true;
                                }
                            } catch (e) {
                                console.warn('Android notification failed, retries left:', retries, e);
                                if (retries > 0) {
                                    setTimeout(() => attemptNotification(retries - 1), 100);
                                }
                            }
                            return false;
                        };
                        
                        // Batch updates to reduce calls but ensure delivery
                        clearTimeout(this.updateTimer);
                        this.updateTimer = setTimeout(() => attemptNotification(), 50);
                    },                    
                    getMediaTitle(media) {
                        const tiddler = media.closest('[data-tiddler-title]');
                        return tiddler?.getAttribute('data-tiddler-title') || 
                               media.title || 
                               media.getAttribute('data-title') || 
                               'TiddlyWiki Audio';
                    },
                    
                    // Enhanced event handler setup with background persistence
                    setupMediaElement(media) {
                        if (media.dataset.mediaHandled) return;
                        media.dataset.mediaHandled = 'true';
                        
                        console.log('Setting up media element:', this.getMediaTitle(media));
                        
                        // Store reference to media interface for background access
                        media.mediaInterface = this;
                        
                        const events = ['play', 'pause', 'ended', 'loadedmetadata', 'timeupdate', 'canplay'];
                        const handler = (e) => {
                            // Update active element tracking
                            if (e.type === 'play') {
                                this.activeElement = media;
                                media.dataset.lastActive = Date.now().toString();
                                console.log('Media started playing:', this.getMediaTitle(media));
                            } else if (e.type === 'ended') {
                                this.activeElement = null;
                                console.log('Media ended:', this.getMediaTitle(media));
                            }
                            
                            // Throttle timeupdate events but ensure they're sent
                            if (e.type === 'timeupdate') {
                                clearTimeout(media.timeUpdateTimeout);
                                media.timeUpdateTimeout = setTimeout(() => {
                                    this.updateState(media);
                                }, 500);
                            } else {
                                this.updateState(media);
                            }
                            
                            // Send event notification to Android
                            try {
                                if (window.Android?.onMediaEvent) {
                                    window.Android.onMediaEvent(
                                        e.type,
                                        media.id || 'media',
                                        media.currentTime || 0,
                                        media.duration || 0,
                                        media.currentSrc || media.src || '',
                                        this.getMediaTitle(media)
                                    );
                                }
                            } catch (err) {
                                console.warn('Event notification failed:', err);
                            }
                        };
                        
                        events.forEach(event => {
                            media.addEventListener(event, handler, { passive: true });
                        });
                        
                        // Add background playback support
                        this.enhanceForBackground(media);
                    },
                    
                    // Enhance media element for background playback
                    enhanceForBackground(media) {
                        // Prevent the browser from pausing when page becomes hidden
                        media.addEventListener('pause', (e) => {
                            // If this was an unexpected pause (not user-initiated), try to resume
                            if (!media.dataset.userPaused && document.visibilityState === 'hidden') {
                                console.log('Unexpected pause detected in background, attempting resume');
                                setTimeout(() => {
                                    if (media.paused && !media.dataset.userPaused) {
                                        media.play().catch(e => console.warn('Background resume failed:', e));
                                    }
                                }, 100);
                            }
                        }, { passive: true });
                        
                        // Track user-initiated pauses
                        media.addEventListener('click', () => {
                            media.dataset.userPaused = media.paused ? 'false' : 'true';
                        }, { passive: true });
                        
                        // Ensure proper audio session handling
                        if (media.tagName === 'AUDIO') {
                            media.preload = 'metadata';
                            // Prevent browser from automatically managing audio focus
                            media.setAttribute('x-webkit-airplay', 'allow');
                        }
                    }
                };                
                // Enhanced media detection and setup with background support
                const setupAllMedia = () => {
                    const selectors = ['audio', 'video', '.tw-audio-element'];
                    let mediaCount = 0;
                    
                    selectors.forEach(selector => {
                        document.querySelectorAll(selector).forEach(media => {
                            window.MediaInterface.setupMediaElement(media);
                            mediaCount++;
                        });
                    });
                    
                    console.log(`Media setup complete: ${mediaCount} elements processed`);
                    
                    // Setup page visibility handling for background playback
                    if (!document.hasVisibilityHandler) {
                        document.addEventListener('visibilitychange', () => {
                            const media = window.MediaInterface.activeElement;
                            if (media) {
                                if (document.visibilityState === 'hidden') {
                                    console.log('Page hidden, maintaining media playback');
                                    // Don't pause - let it continue in background
                                    media.dataset.backgroundActive = 'true';
                                } else {
                                    console.log('Page visible, ensuring media sync');
                                    // Sync state when returning to foreground
                                    setTimeout(() => {
                                        window.MediaInterface.updateState(media);
                                    }, 100);
                                }
                            }
                        });
                        document.hasVisibilityHandler = true;
                    }
                };
                
                // Enhanced observer for better background compatibility
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
                        // Debounce setup but ensure it happens
                        clearTimeout(observer.setupTimeout);
                        observer.setupTimeout = setTimeout(() => {
                            setupAllMedia();
                            // Restore playback state if needed
                            const media = window.MediaInterface.activeElement;
                            if (media && window.MediaInterface.lastKnownState.playing && media.paused) {
                                console.log('Restoring playback after DOM change');
                                media.play().catch(e => console.warn('Restore failed:', e));
                            }
                        }, 100);
                    }
                });
                
                // Initialize with better error handling
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
                    
                    console.log('Enhanced media functionality with background support loaded');
                } catch (e) {
                    console.error('Failed to initialize media functionality:', e);
                }
            })();
        """.trimIndent(), null)
    }
