    /**
     * Enhanced media functionality injection with improved background reliability
     * This creates a unified interface for all media playback in TiddlyWiki
     */
    fun injectMediaFunctionalityScript(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Early exit if already initialized
                if (window.MediaInterface?.initialized) return;
                  // Create unified MediaInterface with enhanced background support
                window.MediaInterface = {
                    initialized: true,
                    activeElement: null,
                    lastKnownState: { playing: false, position: 0, title: "" },
                    backgroundModeActive: false,
                    stateVerificationInterval: null,
                    intentionallyPaused: false,
                    
                    // Enhanced control methods with background reliability                    play() {
                        console.log('MediaInterface: Play command received');
                        this.intentionallyPaused = false; // Clear any intentional pause flag
                        const media = this.activeElement || this.findActiveMedia();
                        if (media?.paused) {
                            console.log('MediaInterface: Starting playback');
                            
                            // Handle user gesture requirements
                            this.handleUserGestureRequirement(media);
                            
                            const playPromise = media.play();
                            if (playPromise !== undefined) {
                                playPromise
                                    .then(() => {
                                        console.log('MediaInterface: Play succeeded');
                                        this.lastKnownState.playing = true;
                                        this.updateState(media);
                                        this.startBackgroundMonitoring();
                                    })
                                    .catch(e => {
                                        console.warn('Play failed:', e);
                                        // Try alternative approach for background
                                        this.forcePlay(media);
                                    });
                            } else {
                                // Old browsers without promise support
                                this.lastKnownState.playing = true;
                                this.updateState(media);
                                this.startBackgroundMonitoring();
                            }
                        } else if (media && !media.paused) {
                            console.log('MediaInterface: Media already playing');
                            this.lastKnownState.playing = true;
                            this.updateState(media);
                        }
                    },
                      pause() {
                        console.log('MediaInterface: Pause command received');
                        const media = this.activeElement || this.findActiveMedia();
                        if (media && !media.paused) {
                            console.log('MediaInterface: Pausing playback');
                            
                            // Mark as intentionally paused to prevent background monitoring from resuming
                            this.intentionallyPaused = true;
                            this.lastKnownState.playing = false;
                            
                            media.pause();
                            this.updateState(media);
                            this.stopBackgroundMonitoring();
                            
                            // Clear the intentional pause flag after a short delay
                            setTimeout(() => {
                                this.intentionallyPaused = false;
                            }, 5000); // 5 second grace period
                        } else {
                            console.log('MediaInterface: Media already paused or not found');
                        }
                    },
                    
                    // Handle user gesture requirements for autoplay
                    handleUserGestureRequirement(media) {
                        try {
                            // Temporarily remove gesture requirement if possible
                            if (media.hasAttribute('data-requires-gesture')) {
                                media.removeAttribute('data-requires-gesture');
                                // Restore it after a short delay
                                setTimeout(() => {
                                    media.setAttribute('data-requires-gesture', 'true');
                                }, 100);
                            }
                            
                            // Set autoplay to help with background resumption
                            media.autoplay = true;
                            
                            // Ensure the media is ready to play
                            if (media.readyState < 2) { // HAVE_CURRENT_DATA
                                media.load();
                            }
                        } catch (e) {
                            console.warn('Error handling user gesture requirement:', e);
                        }
                    },
                    
                    // Force play for background scenarios
                    forcePlay(media) {
                        if (!media) return;
                        console.log('MediaInterface: Force playing media');
                        try {
                            // Multiple strategies for background play
                            const strategies = [
                                () => {
                                    // Strategy 1: Direct play with error catching
                                    media.play().catch(() => {});
                                },
                                () => {
                                    // Strategy 2: Set properties and trigger events
                                    media.muted = false;
                                    media.currentTime = media.currentTime; // Trigger timeupdate
                                    media.dispatchEvent(new Event('play'));
                                },
                                () => {
                                    // Strategy 3: Use Web Audio API to maintain audio context
                                    if (window.AudioContext || window.webkitAudioContext) {
                                        try {
                                            const audioContext = new (window.AudioContext || window.webkitAudioContext)();
                                            if (audioContext.state === 'suspended') {
                                                audioContext.resume();
                                            }
                                        } catch (e) {
                                            console.warn('Audio context resume failed:', e);
                                        }
                                    }
                                }
                            ];
                            
                            strategies.forEach((strategy, index) => {
                                try {
                                    strategy();
                                    console.log(`Force play strategy ${index + 1} executed`);
                                } catch (e) {
                                    console.warn(`Force play strategy ${index + 1} failed:`, e);
                                }
                            });
                            
                            // Update state optimistically
                            this.lastKnownState.playing = true;
                            this.updateState(media);
                            
                        } catch (e) {
                            console.warn('Force play failed:', e);
                        }
                    },                    
                    // Start monitoring media state for background reliability
                    startBackgroundMonitoring() {
                        if (this.stateVerificationInterval) {
                            clearInterval(this.stateVerificationInterval);
                        }
                        
                        console.log('MediaInterface: Starting background monitoring');
                        this.backgroundModeActive = true;
                          // Check media state every 2 seconds to ensure it stays playing
                        this.stateVerificationInterval = setInterval(() => {
                            const media = this.activeElement || this.findActiveMedia();
                            if (media) {
                                // Verify state and correct if needed, but respect intentional pauses
                                if (this.lastKnownState.playing && media.paused && !this.intentionallyPaused) {
                                    console.log('MediaInterface: Detected unexpected pause, attempting resume');
                                    this.forcePlay(media);
                                } else if (!this.lastKnownState.playing && !media.paused) {
                                    console.log('MediaInterface: Detected unexpected play, syncing state');
                                    this.lastKnownState.playing = true;
                                    this.updateState(media);
                                } else if (media.paused && this.intentionallyPaused) {
                                    console.log('MediaInterface: Media intentionally paused, not resuming');
                                }
                                
                                // Regular state update
                                this.updateState(media);
                            }
                        }, 2000);
                    },
                      stopBackgroundMonitoring() {
                        console.log('MediaInterface: Stopping background monitoring');
                        this.backgroundModeActive = false;
                        if (this.stateVerificationInterval) {
                            clearInterval(this.stateVerificationInterval);
                            this.stateVerificationInterval = null;
                        }
                    },
                    
                    seekTo(positionMs) {
                        const media = this.activeElement || this.findActiveMedia();
                        if (media) {
                            const newTime = Math.max(0, Math.min(media.duration || 0, positionMs / 1000));
                            media.currentTime = newTime;
                            this.lastKnownState.position = newTime * 1000;
                            this.updateState(media);
                            console.log('MediaInterface: Seeked to', newTime, 'seconds');
                        }
                    },
                        const media = this.activeElement || this.findActiveMedia();
                        if (media) {
                            const newTime = Math.max(0, Math.min(media.duration || 0, positionMs / 1000));
                            media.currentTime = newTime;
                            this.lastKnownState.position = newTime;
                            this.updateState(media);
                        }
                    },
                      skipForward() { 
                        console.log('MediaInterface: Skip forward 15s');
                        this.skip(15); 
                    },
                    skipBackward() { 
                        console.log('MediaInterface: Skip backward 15s');
                        this.skip(-15); 
                    },
                    
                    skip(seconds) {
                        const media = this.activeElement || this.findActiveMedia();
                        if (media) {
                            const newTime = Math.max(0, Math.min(media.duration || 0, media.currentTime + seconds));
                            media.currentTime = newTime;
                            this.lastKnownState.position = newTime * 1000;
                            this.updateState(media);
                            console.log('MediaInterface: Skipped', seconds, 'seconds to', newTime);
                        }
                    },                    // Enhanced media detection with background support and better prioritization
                    findActiveMedia() {
                        // Strategy 1: Find currently playing media (highest priority)
                        let media = document.querySelector('audio:not([paused]), video:not([paused])');
                        if (media) {
                            console.log('MediaInterface: Found currently playing media');
                            return media;
                        }
                        
                        // Strategy 2: Find media with recent activity or current time > 0
                        const allMedia = document.querySelectorAll('audio, video, .tw-audio-element');
                        let bestCandidate = null;
                        let highestCurrentTime = 0;
                        
                        for (const m of allMedia) {
                            // Prefer media with progress
                            if (m.currentTime > highestCurrentTime) {
                                highestCurrentTime = m.currentTime;
                                bestCandidate = m;
                            }
                            
                            // Prefer media marked as recently active
                            if (m.dataset.lastActive) {
                                const lastActiveTime = parseInt(m.dataset.lastActive);
                                const timeSinceActive = Date.now() - lastActiveTime;
                                if (timeSinceActive < 30000) { // 30 seconds
                                    bestCandidate = m;
                                    break;
                                }
                            }
                        }
                        
                        if (bestCandidate) {
                            console.log('MediaInterface: Found media with progress/activity');
                            return bestCandidate;
                        }
                        
                        // Strategy 3: Find any audio/video element
                        media = document.querySelector('audio, video, .tw-audio-element');
                        if (media) {
                            console.log('MediaInterface: Found fallback media element');
                        }
                        
                        return media;
                    },
                      // Enhanced state update with background persistence and retry logic
                    updateState(media) {
                        if (!media) return;
                        
                        const title = this.getMediaTitle(media);
                        const state = {
                            title,
                            duration: Math.round((media.duration || 0) * 1000),
                            position: Math.round((media.currentTime || 0) * 1000),
                            isPlaying: !media.paused,
                            hasError: media.error !== null,
                            networkState: media.networkState,
                            readyState: media.readyState
                        };
                        
                        // Update last known state with more details
                        this.lastKnownState.playing = state.isPlaying;
                        this.lastKnownState.position = state.position;
                        this.lastKnownState.title = title;
                        
                        // Mark as recently active with more detailed timestamp
                        media.dataset.lastActive = Date.now().toString();
                        media.dataset.lastPosition = state.position.toString();
                        
                        // Enhanced Android notification with retry and error handling
                        this.notifyAndroid(state);
                        
                        // Log state for debugging
                        console.log('MediaInterface: State updated -', {
                            title: title,
                            playing: state.isPlaying,
                            position: Math.round(state.position / 1000) + 's',
                            duration: Math.round(state.duration / 1000) + 's'
                        });
                    },
                      // Robust Android notification with enhanced retry logic and error handling
                    notifyAndroid(state) {
                        const maxRetries = 5;
                        const baseDelay = 100;
                        
                        const attemptNotification = (retries = 0) => {
                            try {
                                // Try primary media state change notification
                                if (window.Android?.onMediaStateChange) {
                                    window.Android.onMediaStateChange(
                                        state.title, 
                                        "TiddlyWiki", 
                                        state.duration, 
                                        state.position, 
                                        state.isPlaying
                                    );
                                    console.log('MediaInterface: Android notification sent successfully');
                                    return true;
                                }
                                
                                // Try alternative notification method
                                if (window.Android?.updateMediaMetadata) {
                                    window.Android.updateMediaMetadata(
                                        state.title,
                                        "TiddlyWiki",
                                        state.duration
                                    );
                                }
                                
                                // Try playback state update
                                if (window.Android?.updatePlaybackState) {
                                    window.Android.updatePlaybackState(
                                        state.isPlaying,
                                        state.position
                                    );
                                }
                                
                            } catch (e) {
                                console.warn('Android notification failed, attempt', retries + 1, ':', e);
                                
                                if (retries < maxRetries) {
                                    const delay = baseDelay * Math.pow(2, retries); // Exponential backoff
                                    setTimeout(() => {
                                        attemptNotification(retries + 1);
                                    }, delay);
                                } else {
                                    console.error('Android notification failed after', maxRetries, 'attempts');
                                }
                                return false;
                            }
                            return true;
                        };
                        
                        // Throttle notifications to prevent spam
                        clearTimeout(this.updateTimer);
                        this.updateTimer = setTimeout(() => {
                            attemptNotification();
                        }, 50);
                    },
                    getMediaTitle(media) {
                        const tiddler = media.closest('[data-tiddler-title]');
                        return tiddler?.getAttribute('data-tiddler-title') || 
                               media.title || 
                               media.getAttribute('data-title') || 
                               'TiddlyWiki Audio';
                    },
                      // Enhanced event handler setup with background persistence and better error handling
                    setupMediaElement(media) {
                        if (media.dataset.mediaHandled) return;
                        media.dataset.mediaHandled = 'true';
                        
                        console.log('Setting up media element:', this.getMediaTitle(media));
                        
                        // Store reference to media interface for background access
                        media.mediaInterface = this;
                        
                        // Enhanced event handling for better background support
                        const events = ['play', 'pause', 'ended', 'loadedmetadata', 'timeupdate', 'canplay', 'seeking', 'seeked'];
                        const handler = (e) => {
                            try {
                                // Update active element tracking
                                if (e.type === 'play') {
                                    this.activeElement = media;
                                    media.dataset.lastActive = Date.now().toString();
                                    console.log('Media started playing:', this.getMediaTitle(media));
                                    
                                    // Start background monitoring when media plays
                                    this.startBackgroundMonitoring();
                                } else if (e.type === 'pause') {
                                    console.log('Media paused:', this.getMediaTitle(media));
                                    
                                    // Check if this was an intentional pause or background issue
                                    if (document.visibilityState === 'hidden' && this.lastKnownState.playing) {
                                        console.log('Media paused while in background, attempting resume');
                                        setTimeout(() => {
                                            if (media.paused && this.lastKnownState.playing) {
                                                this.forcePlay(media);
                                            }
                                        }, 500);
                                    }
                                } else if (e.type === 'ended') {
                                    this.activeElement = null;
                                    this.stopBackgroundMonitoring();
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
                                
                                // Send event notification to Android with enhanced info
                                this.sendEventToAndroid(e, media);
                                
                            } catch (error) {
                                console.error('Error in media event handler:', error);
                            }
                        };
                        
                        events.forEach(event => {
                            media.addEventListener(event, handler, { passive: true });
                        });
                        
                        // Add enhanced background playback support
                        this.enhanceForBackground(media);
                    },
                    
                    // Send detailed event information to Android
                    sendEventToAndroid(event, media) {
                        try {
                            if (window.Android?.onMediaEvent) {
                                const eventData = {
                                    type: event.type,
                                    id: media.id || 'media',
                                    currentTime: media.currentTime || 0,
                                    duration: media.duration || 0,
                                    src: media.currentSrc || media.src || '',
                                    title: this.getMediaTitle(media),
                                    paused: media.paused,
                                    ended: media.ended,
                                    readyState: media.readyState,
                                    networkState: media.networkState
                                };
                                
                                window.Android.onMediaEvent(
                                    eventData.type,
                                    eventData.id,
                                    eventData.currentTime,
                                    eventData.duration,
                                    eventData.src,
                                    eventData.title
                                );
                            }
                        } catch (err) {
                            console.warn('Event notification to Android failed:', err);
                        }
                    },
                      // Enhanced media element configuration for robust background playback
                    enhanceForBackground(media) {
                        // Advanced pause detection and prevention
                        media.addEventListener('pause', (e) => {
                            const wasUserInitiated = media.dataset.userPaused === 'true';
                            const isPageHidden = document.visibilityState === 'hidden';
                            const shouldBePlaying = this.lastKnownState.playing;
                            
                            console.log('Media pause detected:', {
                                userInitiated: wasUserInitiated,
                                pageHidden: isPageHidden,
                                shouldBePlaying: shouldBePlaying
                            });
                            
                            // If this was an unexpected pause while in background, try to resume
                            if (!wasUserInitiated && isPageHidden && shouldBePlaying) {
                                console.log('Unexpected pause in background, scheduling resume');
                                
                                // Multiple resume attempts with increasing delays
                                const resumeAttempts = [100, 500, 1000, 2000];
                                resumeAttempts.forEach((delay, index) => {
                                    setTimeout(() => {
                                        if (media.paused && this.lastKnownState.playing) {
                                            console.log(`Resume attempt ${index + 1} after ${delay}ms`);
                                            this.forcePlay(media);
                                        }
                                    }, delay);
                                });
                            }
                        }, { passive: true });
                        
                        // Enhanced user interaction tracking
                        ['click', 'touchstart', 'keydown'].forEach(eventType => {
                            media.addEventListener(eventType, (e) => {
                                // More sophisticated user pause detection
                                if (e.target === media || media.contains(e.target)) {
                                    const willBePaused = !media.paused;
                                    media.dataset.userPaused = willBePaused ? 'true' : 'false';
                                    media.dataset.lastUserInteraction = Date.now().toString();
                                    console.log('User interaction detected, userPaused:', willBePaused);
                                }
                            }, { passive: true });
                        });
                        
                        // Enhanced media configuration for background playback
                        if (media.tagName === 'AUDIO') {
                            // Audio-specific optimizations
                            media.preload = 'auto'; // Changed from 'metadata' to 'auto'
                            media.preservesPitch = false; // Better for varying playback speeds
                            
                            // Airplay and casting support
                            if (media.setAttribute) {
                                media.setAttribute('x-webkit-airplay', 'allow');
                                media.setAttribute('controlsList', 'nodownload');
                            }
                        }
                        
                        if (media.tagName === 'VIDEO') {
                            // Video-specific optimizations
                            media.setAttribute('playsinline', 'true');
                            media.setAttribute('webkit-playsinline', 'true');
                            
                            // Prevent screen lock on mobile
                            if ('wakeLock' in navigator) {
                                media.addEventListener('play', async () => {
                                    try {
                                        if (!media.wakeLock) {
                                            media.wakeLock = await navigator.wakeLock.request('screen');
                                            console.log('Screen wake lock acquired');
                                        }
                                    } catch (err) {
                                        console.warn('Wake lock failed:', err);
                                    }
                                });
                                
                                media.addEventListener('pause', () => {
                                    if (media.wakeLock) {
                                        media.wakeLock.release();
                                        media.wakeLock = null;
                                        console.log('Screen wake lock released');
                                    }
                                });
                            }
                        }
                        
                        // Add error recovery
                        media.addEventListener('error', (e) => {
                            console.error('Media error detected:', e.error);
                            
                            // Attempt to recover from errors
                            setTimeout(() => {
                                if (media.error && this.lastKnownState.playing) {
                                    console.log('Attempting to recover from media error');
                                    media.load();
                                    setTimeout(() => {
                                        if (this.lastKnownState.playing) {
                                            this.forcePlay(media);
                                        }
                                    }, 1000);
                                }
                            }, 2000);
                        });
                        
                        // Network state monitoring
                        media.addEventListener('stalled', () => {
                            console.log('Media stalled, checking network state');
                            if (this.lastKnownState.playing && media.paused) {
                                setTimeout(() => {
                                    if (media.networkState >= 2 && media.paused) { // NETWORK_LOADING or better
                                        console.log('Network recovered, resuming playback');
                                        this.forcePlay(media);
                                    }
                                }, 3000);
                            }
                        });
                    }
                };                  // Enhanced media detection and setup with superior background support
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
                    
                    // Enhanced page visibility handling for background playback
                    if (!document.hasVisibilityHandler) {
                        const handleVisibilityChange = () => {
                            const media = window.MediaInterface.activeElement;
                            const isHidden = document.visibilityState === 'hidden';
                            
                            console.log('Page visibility changed:', document.visibilityState);
                            
                            if (isHidden) {
                                console.log('Page hidden, enabling background mode');
                                
                                // Notify Android that we're going to background
                                if (window.Android?.onAppBackgrounded) {
                                    window.Android.onAppBackgrounded();
                                }
                                
                                // If media is playing, ensure it continues
                                if (media && !media.paused) {
                                    console.log('Maintaining media playback in background');
                                    media.dataset.backgroundActive = 'true';
                                    
                                    // Start aggressive background monitoring
                                    window.MediaInterface.startBackgroundMonitoring();
                                    
                                    // Preemptive measures to maintain playback
                                    setTimeout(() => {
                                        if (media.paused && window.MediaInterface.lastKnownState.playing) {
                                            console.log('Preemptive background resume');
                                            window.MediaInterface.forcePlay(media);
                                        }
                                    }, 1000);
                                }                            } else {
                                console.log('Page visible, foreground mode active');
                                
                                // Sync state when returning to foreground
                                if (media) {
                                    setTimeout(() => {
                                        window.MediaInterface.updateState(media);
                                        
                                        // Verify playback state and correct if needed
                                        const shouldBePlaying = window.MediaInterface.lastKnownState.playing;
                                        if (shouldBePlaying && media.paused) {
                                            console.log('Correcting playback state on foreground return');
                                            window.MediaInterface.forcePlay(media);
                                        }
                                    }, 500);
                                }
                            }
                        };
                        
                        document.addEventListener('visibilitychange', handleVisibilityChange);
                        
                        // Also listen for page focus/blur as backup
                        window.addEventListener('focus', () => {
                            if (document.visibilityState === 'visible') {
                                handleVisibilityChange();
                            }
                        });
                        
                        window.addEventListener('blur', () => {
                            if (document.visibilityState === 'hidden') {
                                handleVisibilityChange();
                            }
                        });
                        
                        document.hasVisibilityHandler = true;
                    }
                };
                  // Enhanced observer with superior background compatibility and recovery
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
                        console.log('Media elements added to DOM');
                        
                        // Debounce setup but ensure it happens
                        clearTimeout(observer.setupTimeout);
                        observer.setupTimeout = setTimeout(() => {
                            setupAllMedia();
                            
                            // Enhanced state recovery after DOM changes
                            const media = window.MediaInterface.activeElement;
                            const lastKnownState = window.MediaInterface.lastKnownState;
                            
                            if (media && lastKnownState.playing) {
                                console.log('Restoring playback state after DOM change');
                                
                                // Multiple recovery strategies
                                const recoveryStrategies = [
                                    () => media.play().catch(e => console.warn('Direct play recovery failed:', e)),
                                    () => window.MediaInterface.forcePlay(media),
                                    () => {
                                        // Try to restore position if available
                                        if (lastKnownState.position > 0) {
                                            media.currentTime = lastKnownState.position / 1000;
                                        }
                                        return media.play().catch(e => console.warn('Position recovery failed:', e));
                                    }
                                ];
                                
                                recoveryStrategies.forEach((strategy, index) => {
                                    setTimeout(() => {
                                        if (media.paused && lastKnownState.playing) {
                                            console.log(`Executing recovery strategy ${index + 1}`);
                                            strategy();
                                        }
                                    }, (index + 1) * 500);
                                });
                            }
                        }, 200);
                    }
                });
                
                // Initialize with comprehensive error handling
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
                    
                    console.log('Enhanced media functionality with superior background support loaded');
                    
                    // Add global error handler for media issues
                    window.addEventListener('error', (e) => {
                        if (e.target && (e.target.tagName === 'AUDIO' || e.target.tagName === 'VIDEO')) {
                            console.error('Global media error detected:', e);
                            
                            // Attempt recovery
                            setTimeout(() => {
                                const media = e.target;
                                if (window.MediaInterface.lastKnownState.playing && media.paused) {
                                    console.log('Attempting global error recovery');
                                    window.MediaInterface.forcePlay(media);
                                }
                            }, 1000);
                        }
                    }, true);
                    
                } catch (e) {
                    console.error('Failed to initialize enhanced media functionality:', e);
                }
            })();
        """.trimIndent(), null)
    }
