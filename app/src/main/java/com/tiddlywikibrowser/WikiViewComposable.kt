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
     * Inject script to keep the WebView running in the background
     */
    fun injectBackgroundRunningScript(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                // Keep TiddlyWiki active in the background
                
                // Set up a periodic task to ensure TiddlyWiki stays active
                if (window.tidBackgroundTimer) {
                    clearInterval(window.tidBackgroundTimer);
                }
                
                // Create a background timer that pings every 20 seconds
                window.tidBackgroundTimer = setInterval(function() {
                    try {
                        // Execute a simple operation to keep TiddlyWiki active
                        // This keeps any auto-save or sync processes running
                        if (window.'${'$'}tw' && window.'${'$'}tw'.wiki) {
                            // Just access something in the wiki to keep it alive
                            const timestamp = new Date().toISOString();
                            console.log("TiddlyWiki background task ping: " + timestamp);
                            
                            // Run any pending tasks (like auto-saves) if they exist
                            if (typeof window.'${'$'}tw'.syncer?.syncFromServer === 'function') {
                                window.'${'$'}tw'.syncer.syncFromServer();
                            }
                            
                            // Run any auto-save operations if they exist
                            if (typeof window.'${'$'}tw'.wiki?.autosave?.save === 'function') {
                                window.'${'$'}tw'.wiki.autosave.save();
                            }
                        }
                    } catch (e) {
                        console.error("Error in background task: ", e);
                    }
                }, 20000);
                
                // Add event handlers for visibility changes
                document.addEventListener("visibilitychange", function() {
                    if (document.visibilityState === "hidden") {
                        console.log("TiddlyWiki went to background");
                        // You could trigger a save here
                        if (window.'${'$'}tw' && window.'${'$'}tw'.wiki) {
                            if (typeof window.'${'$'}tw'.wiki.saveWiki === 'function') {
                                window.'${'$'}tw'.wiki.saveWiki();
                            }
                        }
                    } else {
                        console.log("TiddlyWiki returned to foreground");
                        // Trigger a sync when the page becomes visible again
                        if (window.'${'$'}tw' && window.'${'$'}tw'.syncer && typeof window.'${'$'}tw'.syncer.syncFromServer === 'function') {
                            window.'${'$'}tw'.syncer.syncFromServer();
                        }
                    }
                });
                
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