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
                let lastScrollY = window.scrollY || 0;
                let lastScrollTime = 0;
                let scrollTimer = null;
                let isScrollingDown = false;
                let barState = true; // true = visible, false = hidden
                
                const scrollThreshold = 20; // Minimum pixels to trigger direction change
                const timeThreshold = 100; // Minimum ms between scroll events to process
                
                window.tidScrollHandler = function() {
                    const now = Date.now();
                    const scrollY = window.scrollY || 0;
                    
                    // Don't process every scroll event - throttle for performance
                    if (now - lastScrollTime < timeThreshold) return;
                    
                    // Clear any pending timer
                    clearTimeout(scrollTimer);
                    
                    // Determine scroll direction when moving significantly
                    if (Math.abs(scrollY - lastScrollY) > scrollThreshold) {
                        isScrollingDown = scrollY > lastScrollY;
                        
                        // Only change state if needed
                        if (isScrollingDown && barState) {
                            // Hide bars when scrolling down
                            barState = false;
                            window.ScrollInterface.onScroll(false);
                        } else if (!isScrollingDown && !barState) {
                            // Show bars when scrolling up
                            barState = true;
                            window.ScrollInterface.onScroll(true);
                        }
                        
                        lastScrollY = scrollY;
                        lastScrollTime = now;
                    }
                    
                    // Always show UI when at the top of the page
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
                
                // Don't automatically show on touch end
                document.addEventListener('touchend', function() {
                    // No auto-show behavior, maintain the current state
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
            // Inject scroll detection script to handle showing/hiding navigation bars
            injectScrollDetectionScript(webView)
            
            // Apply small screen optimizations if needed
            injectSmallScreenOptimizations(webView, context)
            
            onDispose {
                // Remove scroll handler when disposing
                webView.evaluateJavascript("""
                    if (window.tidScrollHandler) {
                        document.removeEventListener('scroll', window.tidScrollHandler);
                        clearTimeout(window.scrollTimer);
                        window.tidScrollHandler = null;
                    }
                """, null)
            }
        }
    }
}