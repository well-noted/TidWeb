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
                    clearTimeout(window.scrollTimer);
                }
                
                // Improved scroll detection for hiding/showing UI
                let lastScrollY = 0;
                let lastScrollTime = 0;
                let scrollTimer = null;
                const scrollThreshold = 15; // Reduced threshold for better up-scroll detection
                const timeThreshold = 80; // Slightly reduced for more responsive up-scroll detection
                let isScrollingDown = false;
                let barState = true; // true = visible, false = hidden
                let consecutiveUpScrolls = 0; // Count consecutive up-scrolls
                
                window.tidScrollHandler = function() {
                    const now = Date.now();
                    const scrollY = window.scrollY;
                    
                    // Don't process every scroll event - but be more responsive for up-scrolls
                    if (now - lastScrollTime < timeThreshold) return;
                    
                    // Clear any pending timer
                    clearTimeout(scrollTimer);
                    
                    // Determine scroll direction when moving
                    if (Math.abs(scrollY - lastScrollY) > scrollThreshold) {
                        // Determine scrolling direction
                        const currentIsScrollingDown = scrollY > lastScrollY;
                        
                        // If direction changed, reset the counter
                        if (currentIsScrollingDown !== isScrollingDown) {
                            consecutiveUpScrolls = currentIsScrollingDown ? 0 : 1;
                        } else if (!currentIsScrollingDown) {
                            // Increment counter for consecutive up-scrolls
                            consecutiveUpScrolls++;
                        }
                        
                        // Update the direction state
                        isScrollingDown = currentIsScrollingDown;
                        
                        // Show bars immediately on the first significant up-scroll
                        if (!isScrollingDown && !barState && consecutiveUpScrolls >= 1) {
                            barState = true;
                            window.ScrollInterface.onScroll(true);
                        } 
                        // Hide bars when scrolling down significantly
                        else if (isScrollingDown && barState) {
                            barState = false;
                            window.ScrollInterface.onScroll(false);
                        }
                        
                        lastScrollY = scrollY;
                        lastScrollTime = now;
                    }
                    
                    // Special case: Always show UI when at the top of the page
                    if (scrollY <= 5 && !barState) {
                        barState = true;
                        window.ScrollInterface.onScroll(true);
                    }
                };
                
                // Add the event listener with the stored handler
                document.addEventListener('scroll', window.tidScrollHandler, { passive: true });
                
                // Store reference to the timer
                window.scrollTimer = scrollTimer;
                
                // Initial state - show UI bars
                barState = true;
                window.ScrollInterface.onScroll(true);
                
                // Handle touch events to improve responsiveness
                document.addEventListener('touchstart', function() {
                    clearTimeout(scrollTimer);
                }, { passive: true });
                
                // Only show bars on touch end if at top of page
                document.addEventListener('touchend', function() {
                    const scrollY = window.scrollY;
                    // Only show bars at top of page on touch end
                    if (scrollY <= 5 && !barState) {
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
                        clearTimeout(window.scrollTimer);
                    }
                    window.ScrollInterface.onScroll(true);
                """, null)
            }
        }
    }
}