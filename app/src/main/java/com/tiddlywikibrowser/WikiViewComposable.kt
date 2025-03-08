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
                }
                
                // Improved scroll detection for hiding/showing UI
                let lastScrollY = 0;
                let lastScrollTime = 0;
                let scrollTimer = null;
                const scrollThreshold = 20; // Minimum pixels to trigger direction change
                const timeThreshold = 100; // Minimum ms between scroll events to process
                
                window.tidScrollHandler = function() {
                    const now = Date.now();
                    const scrollY = window.scrollY;
                    
                    // Don't process every scroll event - throttle for performance
                    if (now - lastScrollTime < timeThreshold) return;
                    
                    // Determine scroll direction when moving significantly
                    if (Math.abs(scrollY - lastScrollY) > scrollThreshold) {
                        const isScrollingDown = scrollY > lastScrollY;
                        
                        // Show when scrolling up, hide when scrolling down
                        window.ScrollInterface.onScroll(!isScrollingDown);
                        
                        lastScrollY = scrollY;
                        lastScrollTime = now;
                    }
                    
                    // Show UI when scrolling stops
                    clearTimeout(scrollTimer);
                    scrollTimer = setTimeout(function() {
                        window.ScrollInterface.onScroll(true);
                    }, 1000);
                };
                
                // Add the event listener with the stored handler
                document.addEventListener('scroll', window.tidScrollHandler, { passive: true });
                
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
        
        DisposableEffect(wiki.url) {
            // Inject scroll detection script to handle showing/hiding navigation bars
            injectScrollDetectionScript(webView)
            
            onDispose {
                // Clean up if needed
            }
        }
    }
}