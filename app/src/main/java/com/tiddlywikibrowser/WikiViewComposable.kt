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
     * Injects CSS specifically for very small screens to ensure optimal display
     */
    fun injectSmallScreenOptimizations(webView: WebView, context: android.content.Context) {
        // Only apply optimizations if we're on a very small screen
        if (!ScreenUtils.isVerySmallScreen(context)) return
        
        webView.evaluateJavascript("""
            (function() {
                // Remove any previous small screen styles
                let existingStyle = document.getElementById('tidweb-vss-optimizations');
                if (existingStyle) {
                    existingStyle.parentNode.removeChild(existingStyle);
                }
                
                // Create new stylesheet for flip phone optimizations
                let styleEl = document.createElement('style');
                styleEl.id = 'tidweb-vss-optimizations';
                styleEl.textContent = `
                    /* Ultra-compact optimizations for flip phones and very small screens */
                    .tc-tiddler-frame {
                        padding: 0.3em !important;
                        margin: 0.15em 0 !important;
                    }
                    
                    .tc-tiddler-title, .tc-site-title {
                        font-size: 1.1em !important;
                        line-height: 1.1 !important;
                        margin: 0 0 0.3em 0 !important;
                    }
                    
                    .tc-titlebar {
                        margin-bottom: 0.2em !important;
                    }
                    
                    .tc-subtitle {
                        font-size: 0.65em !important;
                        margin: 0 !important;
                    }
                    
                    /* Shrink the controls while keeping them usable */
                    .tc-tiddler-controls {
                        font-size: 0.7em !important;
                    }
                    
                    .tc-tiddler-controls .tc-btn-invisible {
                        padding: 0.1em !important;
                        margin: 0 !important;
                    }
                    
                    /* Tightly pack dropdown menus */
                    .tc-drop-down {
                        padding: 0.2em !important;
                        font-size: 0.85em !important;
                    }
                    
                    .tc-drop-down a {
                        padding: 0.15em 0.3em !important;
                    }
                    
                    /* Optimize body text */
                    .tc-tiddler-body {
                        margin: 0.2em 0 !important;
                        font-size: 0.9em !important;
                        line-height: 1.2 !important;
                    }
                    
                    /* Super compact sidebar */
                    .tc-sidebar-lists {
                        padding: 0.2em !important;
                    }
                    
                    .tc-sidebar-tab-open {
                        font-size: 0.8em !important;
                    }
                    
                    /* Maintain scrolling on code blocks */
                    pre, code, .tc-table-of-contents {
                        max-width: 100% !important;
                        overflow-x: auto !important;
                        font-size: 0.8em !important;
                    }
                    
                    /* Ensure touch targets remain usable */
                    button, .tc-btn-invisible, a {
                        min-height: 18px !important;
                        min-width: 18px !important;
                    }
                    
                    /* Constrain image sizes */
                    img {
                        max-width: 100% !important;
                        height: auto !important;
                    }
                    
                    /* Tighter modals */
                    .tc-modal {
                        padding: 0.2em !important;
                        max-width: 95% !important;
                    }
                    
                    /* Adjust form elements */
                    input, select, textarea {
                        font-size: 0.9em !important;
                        padding: 0.2em !important;
                    }
                    
                    /* Optimize tables for narrow screens */
                    table {
                        font-size: 0.8em !important;
                        width: auto !important;
                        max-width: 100% !important;
                        display: block !important;
                        overflow-x: auto !important;
                    }
                    
                    /* Make tab buttons more compact */
                    .tc-tab-buttons button {
                        font-size: 0.8em !important;
                        padding: 0.2em 0.4em !important;
                    }

                    /* Adjust TiddlyWiki UI panels for small screens */
                    .tc-sidebar-scrollable {
                        padding: 0.3em !important;
                    }
                `;
                
                document.head.appendChild(styleEl);
                console.log('Very Small Screen (flip phone) optimizations applied');
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
                // Clean up if needed
            }
        }
    }
}