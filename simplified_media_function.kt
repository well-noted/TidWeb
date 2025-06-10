    /**
     * Inject simplified media functionality into the WebView
     * This connects TiddlyWiki's audio parser with Android's media session API
     */
    fun injectMediaFunctionalityScript(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Create global MediaInterface object if it doesn't exist
                if (!window.MediaInterface) {
                    window.MediaInterface = {};
                }
                
                // Simple media state tracking
                let activeMediaElement = null;
                
                // Helper function to update Android with media metadata
                function updateAndroidMetadata(audio) {
                    try {
                        if (!audio) return;
                        
                        // Get title from the closest tiddler
                        const tiddlerElement = audio.closest('[data-tiddler-title]');
                        const title = tiddlerElement ? 
                            tiddlerElement.getAttribute('data-tiddler-title') : 
                            "TiddlyWiki Audio";
                        
                        const duration = audio.duration || 0;
                        const position = audio.currentTime || 0;
                        const isPlaying = !audio.paused;
                        
                        // Update Android's MediaSession
                        if (window.Android && typeof window.Android.onMediaStateChange === 'function') {
                            window.Android.onMediaStateChange(
                                title,
                                "TiddlyWiki",
                                Math.round(duration * 1000),
                                Math.round(position * 1000),
                                isPlaying
                            );
                        }
                    } catch (e) {
                        console.error("Error updating Android metadata:", e);
                    }
                }
                
                // Add simple media control methods
                MediaInterface.play = function() {
                    try {
                        if (activeMediaElement && activeMediaElement.paused) {
                            activeMediaElement.play();
                        }
                    } catch (e) {
                        console.error("Error in MediaInterface.play", e);
                    }
                };
                
                MediaInterface.pause = function() {
                    try {
                        if (activeMediaElement && !activeMediaElement.paused) {
                            activeMediaElement.pause();
                        }
                    } catch (e) {
                        console.error("Error in MediaInterface.pause", e);
                    }
                };
                
                MediaInterface.seekTo = function(positionMs) {
                    try {
                        if (activeMediaElement) {
                            activeMediaElement.currentTime = positionMs / 1000;
                        }
                    } catch (e) {
                        console.error("Error in MediaInterface.seekTo", e);
                    }
                };
                
                MediaInterface.skipForward = function() {
                    try {
                        if (activeMediaElement) {
                            activeMediaElement.currentTime = Math.min(
                                activeMediaElement.duration, 
                                activeMediaElement.currentTime + 15
                            );
                        }
                    } catch (e) {
                        console.error("Error in MediaInterface.skipForward", e);
                    }
                };
                
                MediaInterface.skipBackward = function() {
                    try {
                        if (activeMediaElement) {
                            activeMediaElement.currentTime = Math.max(
                                0, 
                                activeMediaElement.currentTime - 15
                            );
                        }
                    } catch (e) {
                        console.error("Error in MediaInterface.skipBackward", e);
                    }
                };
                
                // Setup simplified audio observer
                function setupAudioObserver() {
                    function processAudioElements() {
                        const audioElements = document.querySelectorAll('.tw-audio-element');
                        
                        audioElements.forEach(function(audio) {
                            if (audio.getAttribute('data-media-observer-attached') === 'true') {
                                return;
                            }
                            
                            audio.setAttribute('data-media-observer-attached', 'true');
                            
                            // Add event listeners for key media events
                            ['play', 'pause', 'ended', 'loadedmetadata'].forEach(function(eventName) {
                                audio.addEventListener(eventName, function() {
                                    if (eventName === 'play') {
                                        activeMediaElement = this;
                                    } else if (eventName === 'ended') {
                                        activeMediaElement = null;
                                    }
                                    
                                    updateAndroidMetadata(this);
                                    
                                    // Send event to Android
                                    if (window.Android && typeof window.Android.onMediaEvent === 'function') {
                                        try {
                                            const tiddlerElement = this.closest('[data-tiddler-title]');
                                            const title = tiddlerElement ? 
                                                tiddlerElement.getAttribute('data-tiddler-title') : 
                                                "TiddlyWiki Audio";
                                            
                                            window.Android.onMediaEvent(
                                                eventName,
                                                this.id || "audio",
                                                this.currentTime || 0,
                                                this.duration || 0,
                                                this.currentSrc || "",
                                                title
                                            );
                                        } catch (e) {
                                            console.error("Error calling onMediaEvent", e);
                                        }
                                    }
                                });
                            });
                        });
                    }
                    
                    // Initial processing
                    processAudioElements();
                    
                    // Set up mutation observer for new audio elements
                    const observer = new MutationObserver(function(mutations) {
                        let shouldProcess = false;
                        
                        for (const mutation of mutations) {
                            if (mutation.type === 'childList' && mutation.addedNodes.length > 0) {
                                shouldProcess = true;
                                break;
                            }
                        }
                        
                        if (shouldProcess) {
                            processAudioElements();
                        }
                    });
                    
                    observer.observe(document.body, {
                        childList: true,
                        subtree: true
                    });
                }
                
                // Start observing when document is ready
                if (document.readyState === 'complete' || document.readyState === 'interactive') {
                    setupAudioObserver();
                } else {
                    document.addEventListener('DOMContentLoaded', setupAudioObserver);
                }
                
                console.log("Simplified media functionality injected successfully");
            })();
        """.trimIndent(), null)
    }
