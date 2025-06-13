package com.tiddlywikibrowser

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * This enhances the WikiViewComposable in WikiView.kt by adding
 * scroll detection to show/hide navigation bars
 */
object WikiViewEnhancer {
    /**
     * Injects JavaScript to detect scroll direction and show/hide UI accordingly
     */
    fun injectScrollDetectionScript(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Remove any existing scroll handler to avoid duplicates
                if (window.tidScrollHandler) {
                    document.removeEventListener('scroll', window.tidScrollHandler);
                    if (window.scrollTimer) {
                        clearTimeout(window.scrollTimer);
                    }
                }
                
                // Simple scroll detection with stable behavior
                let lastScrollY = window.scrollY || 0;
                let isScrollingDown = false;
                let barState = true; // true = visible, false = hidden
                let scrollTimer = null;
                const scrollThreshold = 1; // Min pixels to trigger direction change
                let lastDirectionChangeTime = 0;
                
                // Track keyboard state
                let isKeyboardVisible = false;
                let lastWindowHeight = window.innerHeight;
                let keyboardDetectionThreshold = 100; // Minimum height change to consider keyboard appearance
                let ignoreScrollEvents = false;
                let keyboardTimer = null;
                let savedScrollPosition = 0;
                let isKeyboardTransition = false;
                
                // Function to detect keyboard visibility changes
                function checkKeyboardVisibility() {
                    const currentHeight = window.innerHeight;
                    const heightDiff = Math.abs(lastWindowHeight - currentHeight);
                    
                    // If significant height change detected, likely keyboard appearance/disappearance
                    if (heightDiff > keyboardDetectionThreshold) {
                        const wasKeyboardVisible = isKeyboardVisible;
                        const newKeyboardVisible = currentHeight < lastWindowHeight;
                        
                        // If keyboard state is changing
                        if (newKeyboardVisible !== wasKeyboardVisible) {
                            isKeyboardTransition = true;
                            ignoreScrollEvents = true;
                            
                            // Save scroll position when keyboard appears
                            if (newKeyboardVisible) {
                                savedScrollPosition = window.scrollY || 0;
                                
                                // Always show bars when keyboard appears
                                if (!barState) {
                                    barState = true;
                                    window.ScrollInterface.onScroll(true);
                                }
                            }
                            
                            // Clear any existing timer
                            if (keyboardTimer) {
                                clearTimeout(keyboardTimer);
                            }
                            
                            // Update keyboard state
                            isKeyboardVisible = newKeyboardVisible;
                            
                            // Set a timer to handle post-keyboard transition
                            keyboardTimer = setTimeout(function() {
                                // If keyboard appeared, restore scroll position
                                if (isKeyboardVisible) {
                                    // Restore saved position with a slight delay to ensure it works
                                    setTimeout(function() {
                                        window.scrollTo(0, savedScrollPosition);
                                        
                                        // After restoring position, wait a bit more before re-enabling scroll detection
                                        setTimeout(function() {
                                            ignoreScrollEvents = false;
                                            isKeyboardTransition = false;
                                            lastScrollY = window.scrollY || 0;
                                        }, 100);
                                    }, 50);
                                } else {
                                    // Keyboard disappeared
                                    ignoreScrollEvents = false;
                                    isKeyboardTransition = false;
                                    lastScrollY = window.scrollY || 0;
                                }
                            }, 300);
                        }
                        
                        lastWindowHeight = currentHeight;
                    }
                }
                
                // Listen for resize events which occur when keyboard appears/disappears
                window.addEventListener('resize', checkKeyboardVisibility, { passive: true });
                
                // Function to handle scroll events
                window.tidScrollHandler = function() {
                    // Skip processing if we're ignoring scroll events due to keyboard
                    if (ignoreScrollEvents || isKeyboardTransition) return;
                    
                    // Clear any pending scroll timer
                    if (scrollTimer) {
                        clearTimeout(scrollTimer);
                    }
                    
                    // Get current position
                    const currentScrollY = window.scrollY || 0;
                    const now = Date.now();
                    
                    // Special case: always show bars when at the top
                    if (currentScrollY <= 5) {
                        if (!barState) {
                            barState = true;
                            window.ScrollInterface.onScroll(true);
                        }
                        lastScrollY = currentScrollY;
                        return;
                    }
                    
                    // Only process significant movements to avoid jitter
                    if (Math.abs(currentScrollY - lastScrollY) > scrollThreshold) {
                        // Determine direction
                        const currentIsScrollingDown = currentScrollY > lastScrollY;
                        
                        // If direction has changed and we're scrolling UP, immediately show bars
                        if (isScrollingDown && !currentIsScrollingDown) {
                            if (!barState) {
                                barState = true;
                                window.ScrollInterface.onScroll(true);
                                lastDirectionChangeTime = now;
                            }
                        } 
                        // If scrolling DOWN and bars are visible, hide them
                        // Don't hide bars if keyboard is visible
                        else if (!isScrollingDown && currentIsScrollingDown && !isKeyboardVisible) {
                            if (barState) {
                                barState = false;
                                window.ScrollInterface.onScroll(false);
                                lastDirectionChangeTime = now;
                            }
                        }
                        
                        // Update tracking state
                        isScrollingDown = currentIsScrollingDown;
                        lastScrollY = currentScrollY;
                    }
                    
                    // Set a timer to stabilize state after scrolling stops
                    scrollTimer = setTimeout(function() {
                        // If at top when scroll stops, always show bars
                        const finalScrollY = window.scrollY || 0;
                        if (finalScrollY <= 5 && !barState) {
                            barState = true;
                            window.ScrollInterface.onScroll(true);
                        }
                        
                        // Always show bars when keyboard is visible
                        if (isKeyboardVisible && !barState) {
                            barState = true;
                            window.ScrollInterface.onScroll(true);
                        }
                    }, 200);
                };
                
                // Add the event listener with passive flag for better performance
                document.addEventListener('scroll', window.tidScrollHandler, { passive: true });
                
                // Store reference to the timer
                window.scrollTimer = scrollTimer;
                
                // Initial state - always show UI bars
                barState = true;
                window.ScrollInterface.onScroll(true);
                
                // Handle touch events for smoother transitions
                document.addEventListener('touchstart', function() {
                    // Clear any pending timer
                    if (scrollTimer) {
                        clearTimeout(scrollTimer);
                    }
                    
                    // Save current scroll position on touch start
                    if (!isKeyboardTransition) {
                        savedScrollPosition = window.scrollY || 0;
                    }
                }, { passive: true });
                
                // When touch ends, stabilize scroll state
                document.addEventListener('touchend', function() {
                    const currentScrollY = window.scrollY || 0;
                    
                    // If at the top when touch ends, show bars
                    if (currentScrollY <= 5 && !barState) {
                        barState = true;
                        window.ScrollInterface.onScroll(true);
                    }
                    
                    // If scrolling up when touch ends, show bars
                    if (!isScrollingDown && !barState) {
                        barState = true;
                        window.ScrollInterface.onScroll(true);
                    }
                    
                    // Always show bars when keyboard is visible
                    if (isKeyboardVisible && !barState) {
                        barState = true;
                        window.ScrollInterface.onScroll(true);
                    }
                }, { passive: true });
                
                // Check for input focus events to ensure bars are visible when typing
                document.addEventListener('focusin', function(e) {
                    // If a text input element gets focus, likely keyboard will appear
                    if (e.target.tagName === 'INPUT' || 
                        e.target.tagName === 'TEXTAREA' || 
                        e.target.isContentEditable) {
                        
                        // Save scroll position before keyboard appears
                        savedScrollPosition = window.scrollY || 0;
                        
                        // Mark keyboard as likely visible
                        isKeyboardVisible = true;
                        
                        // Always show bars when keyboard is visible
                        if (!barState) {
                            barState = true;
                            window.ScrollInterface.onScroll(true);
                        }
                    }
                }, { passive: true });
                
                // Check for input blur events
                document.addEventListener('focusout', function(e) {
                    // If a text input element loses focus, keyboard might disappear
                    if (e.target.tagName === 'INPUT' || 
                        e.target.tagName === 'TEXTAREA' || 
                        e.target.isContentEditable) {
                        
                        // Set a timer to reset keyboard visibility after a delay
                        // (to avoid immediate bar hiding)
                        setTimeout(function() {
                            isKeyboardVisible = false;
                        }, 500);
                    }
                }, { passive: true });
                
                // Initial check for any focused elements
                if (document.activeElement && 
                    (document.activeElement.tagName === 'INPUT' || 
                     document.activeElement.tagName === 'TEXTAREA' || 
                     document.activeElement.isContentEditable)) {
                    isKeyboardVisible = true;
                }
                
                // Add a mutation observer to watch for input fields that might appear dynamically
                const mutationObserver = new MutationObserver(function(mutations) {
                    for (const mutation of mutations) {
                        if (mutation.type === 'childList') {
                            for (const node of mutation.addedNodes) {
                                if (node.nodeType === 1) { // Element node
                                    // Check if the added node is an input or contains inputs
                                    if (node.tagName === 'INPUT' || 
                                        node.tagName === 'TEXTAREA' || 
                                        node.isContentEditable ||
                                        node.querySelector('input, textarea, [contenteditable="true"]')) {
                                        
                                        // If the active element is an input, save position
                                        if (document.activeElement && 
                                            (document.activeElement.tagName === 'INPUT' || 
                                             document.activeElement.tagName === 'TEXTAREA' || 
                                             document.activeElement.isContentEditable)) {
                                            savedScrollPosition = window.scrollY || 0;
                                        }
                                    }
                                }
                            }
                        }
                    }
                });
                
                // Start observing the document with the configured parameters
                mutationObserver.observe(document.body, { childList: true, subtree: true });
                
                return true;
            })();
        """, null)
    }
    
    /**
     * Injects CSS specifically for very small screens to ensure optimal display
     */
    fun injectSmallScreenOptimizations(webView: WebView, context: android.content.Context) {
        // Only apply optimizations if we're on a very small screen
        if (!ScreenUtils.isVerySmallScreen(context)) return
        
        webView.evaluateJavascript("""
            (function() {
                // Add CSS optimizations for small screens
                const style = document.createElement('style');
                style.textContent = `
                    .tc-tiddler-frame { padding: 0.5em !important; }
                    .tc-tiddler-title { font-size: 1.2em !important; }
                    .tc-story-river { margin: 0 !important; }
                    .tc-sidebar-lists { font-size: 0.9em !important; }
                    .tc-tiddler-controls { font-size: 0.85em !important; }
                    .tc-drop-down { font-size: 0.9em !important; }
                    .tc-block-dropdown { max-width: 95vw !important; }
                    input, select { height: 2em !important; }
                    button { min-height: 2em !important; }
                `;
                document.head.appendChild(style);
                return true;
            })();
        """, null)
        
        // Also set WebView zoom level based on screen size
        webView.settings.textZoom = ScreenUtils.getWebViewTextZoom(context)
    }
    
    /**
     * Injects script to handle TiddlyWiki prompts and dropdown menus
     * Uses a more direct approach to fix popup and dialog issues
     */
    fun injectPromptAndDropdownHandling(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Add CSS to fix visibility of popups and dialogs
                const style = document.createElement('style');
                style.textContent = `
                    /* Force popups to be visible and properly positioned */
                    .tc-popup, .tc-drop-down, .tc-block-dropdown {
                        opacity: 1 !important;
                        visibility: visible !important;
                        z-index: 9999 !important;
                        max-width: 95vw !important;
                        max-height: 80vh !important;
                        overflow-y: auto !important;
                        transform: none !important;
                    }
                    
                    /* Make modal wrappers visible */
                    .tc-modal-wrapper {
                        display: flex !important;
                        align-items: center !important;
                        justify-content: center !important;
                        z-index: 10000 !important;
                    }
                    
                    /* Make modals properly sized and visible */
                    .tc-modal {
                        visibility: visible !important;
                        opacity: 1 !important;
                        max-width: 95vw !important;
                        max-height: 90vh !important;
                        overflow-y: auto !important;
                        transform: none !important;
                    }
                    
                    /* Style dialog buttons to be touch-friendly */
                    .tc-modal button {
                        min-height: 44px !important;
                        min-width: 44px !important;
                        padding: 10px !important;
                        margin: 5px !important;
                    }
                    
                    /* Make dropdown items more tappable */
                    .tc-drop-down a, .tc-drop-down button, 
                    .tc-block-dropdown a, .tc-block-dropdown button {
                        min-height: 44px !important;
                        padding: 10px !important;
                        display: block !important;
                    }
                    
                    /* Make sure dialog buttons appear correctly */
                    .tc-modal-footer {
                        display: flex !important;
                        justify-content: flex-end !important;
                        gap: 10px !important;
                    }
                `;
                document.head.appendChild(style);
                
                // Override TiddlyWiki's popup display method if it exists
                if (window.${'$'}tw && ${'$'}tw.utils && ${'$'}tw.utils.popup && typeof ${'$'}tw.utils.popup.display === 'function') {
                    console.log("TiddlyWiki popup utility found - overriding display method");
                    
                    // Save original method
                    const originalPopupDisplay = ${'$'}tw.utils.popup.display;
                    
                    // Override with our own implementation
                    ${'$'}tw.utils.popup.display = function(popupName) {
                        // Call original implementation first
                        const result = originalPopupDisplay.apply(this, arguments);
                        
                        // Now ensure the popup is actually visible
                        setTimeout(function() {
                            const popup = document.getElementById(popupName);
                            if (popup) {
                                console.log("Fixing popup visibility for: " + popupName);
                                popup.style.display = "block";
                                popup.style.visibility = "visible";
                                popup.style.opacity = "1";
                            }
                        }, 50);
                        
                        return result;
                    };
                }
                
                // Override TiddlyWiki's modal mechanism if it exists
                if (window.${'$'}tw && ${'$'}tw.modal) {
                    console.log("TiddlyWiki modal utility found");
                    
                    // Make sure modal display works
                    const originalDisplay = ${'$'}tw.modal.display;
                    if (typeof originalDisplay === 'function') {
                        ${'$'}tw.modal.display = function(title, options) {
                            // Call original implementation
                            const result = originalDisplay.apply(this, arguments);
                            
                            // Force visibility of modal wrapper
                            setTimeout(function() {
                                const modalWrappers = document.querySelectorAll('.tc-modal-wrapper');
                                modalWrappers.forEach(function(wrapper) {
                                    wrapper.style.display = "flex";
                                    wrapper.style.visibility = "visible";
                                    wrapper.style.opacity = "1";
                                    
                                    // Also fix the modal itself
                                    const modal = wrapper.querySelector('.tc-modal');
                                    if (modal) {
                                        modal.style.display = "block";
                                        modal.style.visibility = "visible";
                                        modal.style.opacity = "1";
                                    }
                                });
                            }, 50);
                            
                            return result;
                        };
                    }
                }
                
                // Direct fix for delete button functionality
                function fixDeleteButtons() {
                    const deleteButtons = document.querySelectorAll('.tc-tiddler-controls [aria-label^="Delete"], .tc-tiddler-controls [title^="Delete"]');
                    
                    deleteButtons.forEach(function(button) {
                        // Skip if already processed
                        if (button.hasAttribute('data-fixed')) return;
                        
                        // Mark as processed
                        button.setAttribute('data-fixed', 'true');
                        
                        // Add our own click handler
                        button.addEventListener('click', function(event) {
                            // Prevent default action
                            event.preventDefault();
                            event.stopPropagation();
                            
                            // Get tiddler title
                            const tiddlerFrame = this.closest('.tc-tiddler-frame');
                            if (!tiddlerFrame) return;
                            
                            const title = tiddlerFrame.getAttribute('data-tiddler-title');
                            if (!title) return;
                            
                            // Create simple dialog
                            const dialog = document.createElement('div');
                            dialog.style.cssText = "position:fixed; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,0.5); z-index:10000; display:flex; align-items:center; justify-content:center;";
                            dialog.innerHTML = `
                                <div style="background:white; padding:20px; border-radius:8px; width:80%; max-width:400px;">
                                    <h3 style="margin-top:0;">Delete Tiddler</h3>
                                    <p>Are you sure you want to delete "${'$'}{title}"?</p>
                                    <div style="display:flex; justify-content:flex-end; gap:10px;">
                                        <button id="cancel-delete" style="min-height:44px; padding:10px; min-width:80px;">Cancel</button>
                                        <button id="confirm-delete" style="min-height:44px; padding:10px; min-width:80px; background:#f44336; color:white; border:none;">Delete</button>
                                    </div>
                                </div>
                            `;
                            
                            document.body.appendChild(dialog);
                            
                            // Handle cancel
                            dialog.querySelector('#cancel-delete').addEventListener('click', function() {
                                document.body.removeChild(dialog);
                            });
                            
                            // Handle confirm
                            dialog.querySelector('#confirm-delete').addEventListener('click', function() {
                                if (window.${'$'}tw && ${'$'}tw.wiki && typeof ${'$'}tw.wiki.deleteTiddler === 'function') {
                                    ${'$'}tw.wiki.deleteTiddler(title);
                                    console.log("Deleted tiddler: " + title);
                                } else {
                                    console.error("Could not access TiddlyWiki's API to delete tiddler");
                                    // Try to call original click handler as fallback
                                    if (typeof button.onclick === 'function') {
                                        button.onclick();
                                    }
                                }
                                document.body.removeChild(dialog);
                            });
                            
                            // Allow clicking outside to cancel
                            dialog.addEventListener('click', function(e) {
                                if (e.target === dialog) {
                                    document.body.removeChild(dialog);
                                }
                            });
                        });
                    });
                }
                
                // Observe DOM changes to fix newly added elements
                const observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        if (mutation.addedNodes.length > 0) {
                            // Fix any new delete buttons
                            fixDeleteButtons();
                            
                            // Check for newly added popups and fix them
                            for (let i = 0; i < mutation.addedNodes.length; i++) {
                                const node = mutation.addedNodes[i];
                                if (node.nodeType === 1) { // Element node
                                    // Fix popups
                                    if (node.classList && (
                                        node.classList.contains('tc-popup') || 
                                        node.classList.contains('tc-drop-down') ||
                                        node.classList.contains('tc-block-dropdown')
                                    )) {
                                        node.style.display = "block";
                                        node.style.visibility = "visible";
                                        node.style.opacity = "1";
                                    }
                                    
                                    // Fix modals
                                    if (node.classList && node.classList.contains('tc-modal-wrapper')) {
                                        node.style.display = "flex";
                                        node.style.visibility = "visible";
                                        node.style.opacity = "1";
                                        
                                        const modal = node.querySelector('.tc-modal');
                                        if (modal) {
                                            modal.style.display = "block";
                                            modal.style.visibility = "visible";
                                            modal.style.opacity = "1";
                                        }
                                    }
                                    
                                    // Also check children
                                    const popups = node.querySelectorAll('.tc-popup, .tc-drop-down, .tc-block-dropdown');
                                    popups.forEach(function(popup) {
                                        popup.style.display = "block";
                                        popup.style.visibility = "visible";
                                        popup.style.opacity = "1";
                                    });
                                    
                                    const modals = node.querySelectorAll('.tc-modal-wrapper');
                                    modals.forEach(function(wrapper) {
                                        wrapper.style.display = "flex";
                                        wrapper.style.visibility = "visible";
                                        wrapper.style.opacity = "1";
                                        
                                        const modal = wrapper.querySelector('.tc-modal');
                                        if (modal) {
                                            modal.style.display = "block";
                                            modal.style.visibility = "visible";
                                            modal.style.opacity = "1";
                                        }
                                    });
                                }
                            }
                        }
                    });
                });
                
                // Start observing
                observer.observe(document.body, {
                    childList: true,
                    subtree: true
                });
                
                // Fix existing delete buttons
                fixDeleteButtons();
                
                // Fix existing popups and modals
                document.querySelectorAll('.tc-popup, .tc-drop-down, .tc-block-dropdown').forEach(function(popup) {
                    popup.style.display = "block";
                    popup.style.visibility = "visible";
                    popup.style.opacity = "1";
                });
                
                document.querySelectorAll('.tc-modal-wrapper').forEach(function(wrapper) {
                    wrapper.style.display = "flex";
                    wrapper.style.visibility = "visible";
                    wrapper.style.opacity = "1";
                    
                    const modal = wrapper.querySelector('.tc-modal');
                    if (modal) {
                        modal.style.display = "block";
                        modal.style.visibility = "visible";
                        modal.style.opacity = "1";
                    }
                });
                
                // Add global click handler for dropdown toggle buttons to ensure they work
                document.addEventListener('click', function(event) {
                    // Check if the click was on a dropdown button
                    let target = event.target;
                    while (target && target !== document) {
                        // Look for buttons that typically trigger dropdowns
                        if (target.getAttribute('aria-expanded') === 'true' || 
                            target.getAttribute('aria-expanded') === 'false' ||
                            target.classList.contains('tc-btn-invisible') ||
                            target.classList.contains('tc-drop-down-button')) {
                            
                            // Wait a moment then ensure any popups are visible
                            setTimeout(function() {
                                document.querySelectorAll('.tc-popup, .tc-drop-down, .tc-block-dropdown').forEach(function(popup) {
                                    popup.style.display = "block";
                                    popup.style.visibility = "visible";
                                    popup.style.opacity = "1";
                                });
                            }, 100);
                            
                            break;
                        }
                        target = target.parentNode;
                    }
                });
                
                // Also run fixes when TiddlyWiki refreshes the page
                if (window.${'$'}tw) {
                    ${'$'}tw.hook.addHook("th-page-refreshed", function() {
                        setTimeout(function() {
                            fixDeleteButtons();
                            
                            document.querySelectorAll('.tc-popup, .tc-drop-down, .tc-block-dropdown').forEach(function(popup) {
                                popup.style.display = "block";
                                popup.style.visibility = "visible";
                                popup.style.opacity = "1";
                            });
                            
                            document.querySelectorAll('.tc-modal-wrapper').forEach(function(wrapper) {
                                wrapper.style.display = "flex";
                                wrapper.style.visibility = "visible";
                                wrapper.style.opacity = "1";
                                
                                const modal = wrapper.querySelector('.tc-modal');
                                if (modal) {
                                    modal.style.display = "block";
                                    modal.style.visibility = "visible";
                                    modal.style.opacity = "1";
                                }
                            });
                        }, 100);
                    });
                }
                
                // Create a global helper function to force-show all dialogs and popups
                window.forceShowDialogsAndPopups = function() {
                    document.querySelectorAll('.tc-popup, .tc-drop-down, .tc-block-dropdown').forEach(function(popup) {
                        popup.style.display = "block";
                        popup.style.visibility = "visible";
                        popup.style.opacity = "1";
                    });
                    
                    document.querySelectorAll('.tc-modal-wrapper').forEach(function(wrapper) {
                        wrapper.style.display = "flex";
                        wrapper.style.visibility = "visible";
                        wrapper.style.opacity = "1";
                        
                        const modal = wrapper.querySelector('.tc-modal');
                        if (modal) {
                            modal.style.display = "block";
                            modal.style.visibility = "visible";
                            modal.style.opacity = "1";
                        }
                    });
                    return true;
                };
                
                // Run the force show function periodically to catch any popups that might be missed
                setInterval(window.forceShowDialogsAndPopups, 1000);
                
                console.log("TiddlyWiki prompt and dropdown handler loaded (simplified approach)");
                return true;
            })();
        """, null)
    }
    
    /**
     * Inject optimized media functionality with reduced resource consumption
     * This replaces the complex media system with a lightweight, thermal-aware version
     */
    fun injectOptimizedMediaFunctionalityScript(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Early exit if already initialized
                if (window.OptimizedMediaInterface?.initialized) return;
                
                // Create optimized MediaInterface with minimal background processing
                window.OptimizedMediaInterface = {
                    initialized: true,
                    activeElement: null,
                    lastKnownState: { playing: false, position: 0, title: "" },
                    isBackgroundMonitoring: false,
                    monitoringInterval: null,
                    updateTimer: null,
                    
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
                        
                        // Flag that this is a media session command
                        window.__mediaSessionCommandInProgress = true;
                        
                        const media = this.activeElement || this.findActiveMedia();
                        if (media && !media.paused) {
                            console.log('OptimizedMedia: Pausing playback');
                            this.lastKnownState.playing = false;
                            media.pause();
                            this.updateState(media);
                            this.stopLightweightMonitoring();
                        }
                        
                        // Clear the flag after a delay
                        setTimeout(() => { window.__mediaSessionCommandInProgress = false; }, 100);
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
                            this.updateState(media);
                        }, { passive: true });
                        
                        // Handle network issues gracefully
                        media.addEventListener('stalled', () => {
                            console.log('Media stalled, reducing quality expectations');
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
                    
                    console.log('Optimized media setup complete: ' + mediaCount + ' elements processed');
                    
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
                
                // Expose control methods for Android compatibility
                window.MediaInterface = {
                    play: () => window.OptimizedMediaInterface.play(),
                    pause: () => window.OptimizedMediaInterface.pause(),
                    seekTo: (ms) => window.OptimizedMediaInterface.seekTo(ms),
                    skipForward: () => window.OptimizedMediaInterface.skipForward(),
                    skipBackward: () => window.OptimizedMediaInterface.skipBackward(),
                    onMediaStateChange: (title, artist, duration, position, isPlaying) => {
                        // Android interface compatibility
                    }
                };
                
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
    
    /**
     * Inject original media functionality support scripts (kept for fallback)
     * This connects TiddlyWiki's audio parser with Android's media session API
     */
    fun injectMediaFunctionalityScript(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Create global MediaInterface object if it doesn't exist
                if (!window.MediaInterface) {
                    window.MediaInterface = {};
                }
                
                // Media state tracking
                const mediaState = {
                    playing: false,
                    title: "TiddlyWiki Audio",
                    artist: "TiddlyWiki",
                    duration: 0,
                    position: 0,
                    activeMediaElement: null
                };
                
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
                        
                        mediaState.playing = isPlaying;
                        mediaState.title = title;
                        mediaState.duration = duration;
                        mediaState.position = position;
                        mediaState.activeMediaElement = audio;
                        
                        // Update Android's MediaSession
                        if (window.Android && typeof window.Android.updateMediaMetadata === 'function') {
                            window.Android.updateMediaMetadata(
                                title,
                                "TiddlyWiki",
                                Math.round(duration * 1000)
                            );
                        }
                        
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
                
                // Add methods to the MediaInterface object
                MediaInterface.play = function() {
                    try {
                        const audio = mediaState.activeMediaElement;
                        if (audio && audio.paused) {
                            audio.play();
                        }
                    } catch (e) {
                        console.error("Error in MediaInterface.play", e);
                    }
                };
                  MediaInterface.pause = function() {
                    try {
                        // Flag that this is a media session command
                        window.__mediaSessionCommandInProgress = true;
                        
                        const audio = mediaState.activeMediaElement;
                        if (audio && !audio.paused) {
                            audio.pause();
                        }
                        
                        // Clear the flag after a delay
                        setTimeout(() => { window.__mediaSessionCommandInProgress = false; }, 100);
                    } catch (e) {
                        console.error("Error in MediaInterface.pause", e);
                        // Clear the flag on error
                        window.__mediaSessionCommandInProgress = false;
                    }
                };
                
                MediaInterface.seekTo = function(positionMs) {
                    try {
                        const audio = mediaState.activeMediaElement;
                        if (audio) {
                            // Convert ms to seconds for HTML5 audio
                            audio.currentTime = positionMs / 1000;
                        }
                    } catch (e) {
                        console.error("Error in MediaInterface.seekTo", e);
                    }
                };
                
                MediaInterface.skipForward = function() {
                    try {
                        const audio = mediaState.activeMediaElement;
                        if (audio) {
                            audio.currentTime = Math.min(audio.duration, audio.currentTime + 15);
                        }
                    } catch (e) {
                        console.error("Error in MediaInterface.skipForward", e);
                    }
                };
                
                MediaInterface.skipBackward = function() {
                    try {
                        const audio = mediaState.activeMediaElement;
                        if (audio) {
                            audio.currentTime = Math.max(0, audio.currentTime - 15);
                        }
                    } catch (e) {
                        console.error("Error in MediaInterface.skipBackward", e);
                    }
                };
                
                // Setup mutation observer to watch for audio elements
                const setupAudioObserver = function() {
                    // Function to process audio elements
                    const processAudioElements = function() {
                        const audioElements = document.querySelectorAll('.tw-audio-element');
                        
                        audioElements.forEach(function(audio) {
                            if (audio.getAttribute('data-media-observer-attached') === 'true') {
                                return;
                            }
                            
                            // Mark as processed
                            audio.setAttribute('data-media-observer-attached', 'true');
                              // Add event listeners to track playback state
                            ['play', 'pause', 'timeupdate', 'seeked', 'ended', 'loadedmetadata'].forEach(function(eventName) {
                                audio.addEventListener(eventName, function() {
                                    // Set as active media element when it plays
                                    if (eventName === 'play') {
                                        mediaState.activeMediaElement = this;
                                    }
                                    
                                    // Update overlay button state immediately on play/pause events
                                    if ((eventName === 'play' || eventName === 'pause') && window.AudioControls) {
                                        const isPlaying = eventName === 'play';
                                        // Use setTimeout to ensure the event has fully processed
                                        setTimeout(() => {
                                            if (typeof window.AudioControls.updateOverlayButton === 'function') {
                                                window.AudioControls.updateOverlayButton(this, isPlaying);
                                            }
                                        }, 10);
                                    }
                                    
                                    // Update Android with media state
                                    updateAndroidMetadata(this);
                                    
                                    // Also send event-specific notification
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
                    };
                    
                    // Run initial processing
                    processAudioElements();
                    
                    // Set up mutation observer to catch new audio elements
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
                };
                
                // Start observing when document is ready
                if (document.readyState === 'complete' || document.readyState === 'interactive') {
                    setupAudioObserver();
                } else {
                    document.addEventListener('DOMContentLoaded', setupAudioObserver);
                }
                
                // Create the AudioControls interface expected by TiddlyWiki
                window.AudioControls = function() {
                    this.currentAudio = null;
                    this.elements = {};
                    
                    // Initialize overlay
                    this.initOverlay = function() {
                        // Only create if it doesn't exist
                        if (document.getElementById('audio-controls-overlay')) {
                            return;
                        }
                        
                        const overlay = document.createElement('div');
                        overlay.id = 'audio-controls-overlay';
                        overlay.style.cssText = `
                            position: fixed;
                            bottom: 0;
                            left: 0;
                            right: 0;
                            background: rgba(33, 33, 33, 0.9);
                            color: white;
                            padding: 10px;
                            z-index: 9999;
                            display: flex;
                            align-items: center;
                            justify-content: space-between;
                            transform: translateY(100%);
                            transition: transform 0.3s ease;
                            box-shadow: 0 -2px 10px rgba(0, 0, 0, 0.3);
                        `;
                        
                        // Title div
                        const titleDiv = document.createElement('div');
                        titleDiv.id = 'audio-controls-title';
                        titleDiv.style.cssText = `
                            flex: 1;
                            white-space: nowrap;
                            overflow: hidden;
                            text-overflow: ellipsis;
                            margin-right: 10px;
                        `;
                        
                        // Controls div
                        const controlsDiv = document.createElement('div');
                        controlsDiv.style.cssText = `
                            display: flex;
                            align-items: center;
                            gap: 10px;
                        `;
                        
                        // Add buttons
                        const rewindButton = document.createElement('button');
                        rewindButton.innerHTML = '⏪';
                        rewindButton.style.cssText = `
                            background: none;
                            border: none;
                            color: white;
                            font-size: 1.5em;
                            cursor: pointer;
                        `;
                        rewindButton.onclick = function() {
                            if (window.MediaInterface.skipBackward) {
                                window.MediaInterface.skipBackward();
                            }
                        };
                        
                        const playButton = document.createElement('button');
                        playButton.innerHTML = '▶️';
                        playButton.style.cssText = `
                            background: none;
                            border: none;
                            color: white;
                            font-size: 1.5em;
                            cursor: pointer;
                        `;
                        playButton.onclick = function() {
                            const audio = mediaState.activeMediaElement;
                            if (audio) {
                                if (audio.paused) {
                                    audio.play();
                                    this.innerHTML = '⏸️';
                                } else {
                                    audio.pause();
                                    this.innerHTML = '▶️';
                                }
                            }
                        };
                        
                        const forwardButton = document.createElement('button');
                        forwardButton.innerHTML = '⏩';
                        forwardButton.style.cssText = `
                            background: none;
                            border: none;
                            color: white;
                            font-size: 1.5em;
                            cursor: pointer;
                        `;
                        forwardButton.onclick = function() {
                            if (window.MediaInterface.skipForward) {
                                window.MediaInterface.skipForward();
                            }
                        };
                        
                        const closeButton = document.createElement('button');
                        closeButton.innerHTML = '✕';
                        closeButton.style.cssText = `
                            background: none;
                            border: none;
                            color: white;
                            font-size: 1em;
                            cursor: pointer;
                            margin-left: 10px;
                        `;
                        closeButton.onclick = function() {
                            const audio = mediaState.activeMediaElement;
                            if (audio) {
                                audio.pause();
                            }
                            document.getElementById('audio-controls-overlay').classList.remove('active');
                        };
                        
                        // Add everything to the DOM
                        controlsDiv.appendChild(rewindButton);
                        controlsDiv.appendChild(playButton);
                        controlsDiv.appendChild(forwardButton);
                        controlsDiv.appendChild(closeButton);
                        
                        overlay.appendChild(titleDiv);
                        overlay.appendChild(controlsDiv);
                        
                        document.body.appendChild(overlay);
                        
                        // Store references
                        this.elements.overlay = overlay;
                        this.elements.titleDiv = titleDiv;
                        this.elements.playButton = playButton;
                    };
                    
                    // Function to update just the overlay button state
                    this.updateOverlayButton = function(audioElement, isPlaying) {
                        try {
                            // Only update if this is the current audio element
                            if (audioElement === this.currentAudio && this.elements.playButton) {
                                // Use the explicit isPlaying parameter if provided, otherwise check paused state
                                const shouldShowPause = (isPlaying !== undefined) ? isPlaying : !audioElement.paused;
                                this.elements.playButton.innerHTML = shouldShowPause ? '⏸️' : '▶️';
                                
                                // Also update any other play buttons in the overlay
                                const allPlayButtons = this.elements.overlay?.querySelectorAll('button[onclick*="play"], .play-button');
                                if (allPlayButtons) {
                                    allPlayButtons.forEach(btn => {
                                        if (btn !== this.elements.playButton) {
                                            btn.innerHTML = shouldShowPause ? '⏸️' : '▶️';
                                        }
                                    });
                                }
                            }
                        } catch (e) {
                            console.error("Error updating overlay button:", e);
                        }
                    };
                    
                    // Function to update the overlay
                    this.updateOverlay = function() {
                        this.initOverlay();
                        
                        if (!this.currentAudio) return;
                        
                        // Show the overlay
                        this.elements.overlay.classList.add('active');
                        this.elements.overlay.style.transform = 'translateY(0)';
                        
                        // Update title
                        const tiddlerElement = this.currentAudio.closest('[data-tiddler-title]');
                        const title = tiddlerElement ? 
                            tiddlerElement.getAttribute('data-tiddler-title') : 
                            "TiddlyWiki Audio";
                        
                        this.elements.titleDiv.textContent = title;
                        
                        // Update play/pause button
                        this.updateOverlayButton(this.currentAudio);
                    };
                    
                    // Initialize
                    this.initOverlay();
                };
                
                // Create CSS for active state
                const style = document.createElement('style');
                style.textContent = `
                    #audio-controls-overlay.active {
                        transform: translateY(0) !important;
                    }                `;
                document.head.appendChild(style);
                
                console.log("🎵 TiddlyWiki Media Integration loaded");
                  // Set up periodic button state sync to prevent desync issues
                if (!window.mediaButtonSyncInterval) {
                    window.mediaButtonSyncInterval = setInterval(function() {
                        try {
                            // Check if we have an active audio element and overlay
                            if (window.AudioControls && window.AudioControls.currentAudio && 
                                window.AudioControls.elements && window.AudioControls.elements.playButton) {
                                
                                const audio = window.AudioControls.currentAudio;
                                const playButton = window.AudioControls.elements.playButton;
                                const shouldShowPause = !audio.paused;
                                const currentIcon = playButton.innerHTML;
                                const expectedIcon = shouldShowPause ? '⏸️' : '▶️';
                                
                                // Only update if there's a mismatch
                                if (currentIcon !== expectedIcon) {
                                    console.log('[MediaSync] Correcting button state from "' + currentIcon + '" to "' + expectedIcon + '"');
                                    playButton.innerHTML = expectedIcon;
                                }
                            }
                        } catch (e) {
                            // Silently ignore errors to avoid log spam
                        }
                    }, 2000); // Check every 2 seconds
                }
                
                return true;
            })();
        """, null)
    }
    
    /**
     * Inject script to keep the WebView running in the background and handle offline/online syncing
     * with prevention of automatic page refreshes
     */
    fun injectBackgroundRunningScript(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Keep TiddlyWiki active in the background
                
                // Set up a periodic task to ensure TiddlyWiki stays active
                if (window.tidBackgroundTimer) {
                    clearInterval(window.tidBackgroundTimer);
                }
                
                // Track online/offline status
                let wasOffline = !navigator.onLine;
                
                // Function to sync without causing page refresh
                function syncWithoutRefresh() {
                    try {
                        if (window.${'$'}tw && window.${'$'}tw.syncer) {
                            // First save any pending changes
                            if (typeof window.${'$'}tw.wiki.saveWiki === 'function') {
                                window.${'$'}tw.wiki.saveWiki();
                            }
                            
                            // Check if wiki has unsaved/dirty tiddlers
                            let hasDirtyTiddlers = false;
                            if (window.${'$'}tw.syncer.isDirty && window.${'$'}tw.syncer.isDirty()) {
                                hasDirtyTiddlers = true;
                            }
                            
                            // Temporarily override the page refresh function
                            const originalRefreshPageFunction = window.${'$'}tw.syncer.refreshPage;
                            let refreshRequested = false;
                            
                            window.${'$'}tw.syncer.refreshPage = function() {
                                console.log("Page refresh requested during sync - suppressing automatic refresh");
                                refreshRequested = true;
                                // We don't call the original function, preventing refresh
                            };
                            
                            // First push local changes to server
                            if (typeof window.${'$'}tw.syncer.syncToServer === 'function') {
                                console.log("Syncing changes to server");
                                window.${'$'}tw.syncer.syncToServer();
                            }
                            
                            // Then pull server changes
                            if (typeof window.${'$'}tw.syncer.syncFromServer === 'function') {
                                console.log("Syncing changes from server"); 
                                window.${'$'}tw.syncer.syncFromServer();
                            }
                            
                            // Restore original refresh function
                            window.${'$'}tw.syncer.refreshPage = originalRefreshPageFunction;
                            
                            // Instead of automatic refresh, selectively refresh tiddlers if needed
                            if (refreshRequested) {
                                console.log("Performing selective tiddler refresh instead of page refresh");
                                if (typeof window.${'$'}tw.wiki.refreshTiddlers === 'function') {
                                    window.${'$'}tw.wiki.refreshTiddlers();
                                }
                                
                                // Notify user of changes with a non-disruptive message
                                if (window.${'$'}tw.notifier && typeof window.${'$'}tw.notifier.display === 'function') {
                                    window.${'$'}tw.notifier.display({
                                        title: "Changes synchronized",
                                        message: "Updates from the server have been synchronized",
                                        refreshMessage: "Refresh now",
                                        refresh: function() {
                                            // Only if user clicks refresh, do a full page reload
                                            window.location.reload();
                                        }
                                    });
                                }
                            }
                            
                            return { refreshRequested: refreshRequested };
                        }
                    } catch (e) {
                        console.error("Error in syncWithoutRefresh:", e);
                        return { error: e.toString() };
                    }
                }
                
                // Create a background timer that pings every 20 seconds
                window.tidBackgroundTimer = setInterval(function() {
                    try {
                        // Execute a simple operation to keep TiddlyWiki active
                        if (window.${'$'}tw && window.${'$'}tw.wiki) {
                            const timestamp = new Date().toISOString();
                            console.log("TiddlyWiki background task ping: " + timestamp);
                            
                            // Check if we've come back online
                            if (wasOffline && navigator.onLine) {
                                console.log("Network connectivity restored - initiating gentle sync");
                                syncWithoutRefresh();
                            }
                            
                            // Update offline status tracking
                            wasOffline = !navigator.onLine;
                            
                            // Regular sync process if online (but non-disruptive)
                            if (navigator.onLine) {
                                // Run periodic gentle sync
                                syncWithoutRefresh();
                            } else {
                                // Make sure to save any pending changes locally when offline
                                if (typeof window.${'$'}tw.wiki.saveWiki === 'function') {
                                    window.${'$'}tw.wiki.saveWiki();
                                }
                            }
                        }
                    } catch (e) {
                        console.error("Error in background task:", e);
                    }
                }, 20000);
                
                // Add event listener for online status
                window.addEventListener('online', function() {
                    console.log("Device is now online - initiating gentle sync");
                    syncWithoutRefresh();
                    wasOffline = false;
                });
                
                // Track when we go offline
                window.addEventListener('offline', function() {
                    console.log("Device is now offline - local changes will be saved");
                    wasOffline = true;
                    
                    // Make sure to save any pending changes locally
                    if (window.${'$'}tw && window.${'$'}tw.wiki && typeof window.${'$'}tw.wiki.saveWiki === 'function') {
                        window.${'$'}tw.wiki.saveWiki();
                    }
                });
                
                // Add event handlers for visibility changes
                document.addEventListener("visibilitychange", function() {
                    if (document.visibilityState === "hidden") {
                        console.log("TiddlyWiki went to background");
                        // Save when app goes to background
                        if (window.${'$'}tw && window.${'$'}tw.wiki) {
                            if (typeof window.${'$'}tw.wiki.saveWiki === 'function') {
                                window.${'$'}tw.wiki.saveWiki();
                            }
                        }
                    } else {
                        console.log("TiddlyWiki returned to foreground");
                        // When returning to foreground, check if we need to sync
                        if (navigator.onLine) {
                            // Sync with non-disruptive approach
                            syncWithoutRefresh();
                        }
                    }
                });
                
                // Add a global sync function that can be called manually if needed
                window.tiddlyBrowserSync = function(options) {
                    options = options || {};
                    const force = !!options.force;
                    
                    const result = syncWithoutRefresh();
                    
                    if (force && result && result.refreshRequested) {
                        // Only force refresh if explicitly requested
                        window.location.reload();
                    }
                    
                    return result;
                };
                
                return true;
            })();
        """, null)
    }
    
    /**
     * Enhances the WikiViewComposable with scroll detection
     */
    @Composable
    fun enhanceWithScrollDetection(wiki: WikiInstance, viewModel: WikiViewModel) {
        val context = LocalContext.current
        val webView = remember(wiki.url) { viewModel.getOrCreateWebView(wiki, context) }
        val mainActivity = context as? MainActivity
        val isBackgroundEnabled by mainActivity?.isBackgroundEnabled?.collectAsState() ?: remember { mutableStateOf(false) }
        
        DisposableEffect(wiki.url, isBackgroundEnabled) {
            // Wait a short moment for WebView to be ready
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Inject scroll detection script to handle showing/hiding navigation bars
                injectScrollDetectionScript(webView)
                
                // Apply small screen optimizations if needed
                injectSmallScreenOptimizations(webView, context)
                
                // Inject prompt and dropdown handling script
                injectPromptAndDropdownHandling(webView)
                
                // Inject optimized media functionality script
                injectOptimizedMediaFunctionalityScript(webView)
                
                // If background mode is enabled, inject the background running script
                if (isBackgroundEnabled) {
                    injectBackgroundRunningScript(webView)
                }
                
                // Force initial state to visible
                webView.evaluateJavascript("""
                    window.ScrollInterface.onScroll(true);
                """, null)
            }, 100)
            
            onDispose {
                // Show bars when disposing and clean up handlers
                webView.evaluateJavascript("""
                    if (window.tidScrollHandler) {
                        document.removeEventListener('scroll', window.tidScrollHandler);
                        if (window.scrollTimer) {
                            clearTimeout(window.scrollTimer);
                        }
                    }
                    
                    // Clear background timer if it exists
                    if (window.tidBackgroundTimer) {
                        clearInterval(window.tidBackgroundTimer);
                    }
                    
                    window.ScrollInterface.onScroll(true);
                """, null)
                
                // If in background mode, register this WebView with the background service
                if (isBackgroundEnabled && mainActivity != null) {
                    val key = wiki.idFromUrl ?: wiki.url
                    mainActivity.backgroundWebViewManager.registerWebView(key, webView)
                }
            }
        }
    }
}