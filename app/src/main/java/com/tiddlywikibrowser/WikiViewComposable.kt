package com.tiddlywikibrowser

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

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
                
                // Function to handle scroll events
                window.tidScrollHandler = function() {
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
                        else if (!isScrollingDown && currentIsScrollingDown) {
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
                }, { passive: true });
                
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
     * Enhances the WikiViewComposable with scroll detection
     */
    @Composable
    fun enhanceWithScrollDetection(wiki: WikiInstance, viewModel: WikiViewModel) {
        val context = LocalContext.current
        val webView = remember(wiki.url) { viewModel.getOrCreateWebView(wiki, context) }
        
        DisposableEffect(wiki.url) {
            // Wait a short moment for WebView to be ready
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Inject scroll detection script to handle showing/hiding navigation bars
                injectScrollDetectionScript(webView)
                
                // Apply small screen optimizations if needed
                injectSmallScreenOptimizations(webView, context)
                
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
                    window.ScrollInterface.onScroll(true);
                """, null)
            }
        }
    }
}