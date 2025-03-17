package com.tiddlywikibrowser.splitter

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Helper for large TiddlyWikis to implement partial loading strategies
 */
class TiddlyWikiSplitter(private val context: Context) {

    private val splitCacheDir = File(context.cacheDir, "split_wikis")
    
    init {
        if (!splitCacheDir.exists()) {
            splitCacheDir.mkdirs()
        }
    }
    
    /**
     * For very large wikis, attempt to extract and just load essential components
     */
    suspend fun createLightweightWikiLoader(wikiUrl: String, cacheDir: File): String? = 
        withContext(Dispatchers.IO) {
            try {
                // Create a split version in cache
                val wikiCacheDir = File(splitCacheDir, generateSimpleKey(wikiUrl))
                wikiCacheDir.mkdirs()
                
                // Load and inject lightweight bootstrap HTML
                val bootstrapHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>TiddlyWiki Optimized</title>
                        <style>
                            /* Minimal loading styles */
                            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial; }
                            .tc-splash-title { font-size: 1.5em; margin: 1em 0; text-align: center; }
                            .tc-progressbar { height: 1em; margin: 1em 0; background-color: #eee; }
                            .tc-progressbar-inner { height: 100%; background-color: #5778d8; width: 0%; }
                        </style>
                        <script>
                            // Custom optimized loader script
                            window.addEventListener('load', function() {
                                // Show progressive loading UI
                                document.body.innerHTML = 
                                    '<div class="tc-splash-title">Loading TiddlyWiki...</div>' +
                                    '<div class="tc-progressbar"><div class="tc-progressbar-inner"></div></div>' +
                                    '<div id="loading-message">Initializing...</div>';
                                
                                var progressBar = document.querySelector('.tc-progressbar-inner');
                                var loadingMessage = document.getElementById('loading-message');
                                
                                // Load the real wiki with progress reporting
                                var xhr = new XMLHttpRequest();
                                xhr.open('GET', '$wikiUrl');
                                xhr.onprogress = function(e) {
                                    if (e.lengthComputable) {
                                        var progress = (e.loaded / e.total) * 100;
                                        progressBar.style.width = progress + '%';
                                        loadingMessage.textContent = 'Loading: ' + Math.round(progress) + '%';
                                    }
                                };
                                xhr.onload = function() {
                                    // Parse just what we need
                                    var parser = new DOMParser();
                                    var doc = parser.parseFromString(xhr.responseText, "text/html");
                                    
                                    // Extract essential scripts and styles first
                                    var essentialScripts = Array.from(doc.querySelectorAll('script'))
                                        .filter(script => !script.src || script.src.includes('core'));
                                    var styles = doc.querySelectorAll('style');
                                    
                                    // Update loading message
                                    loadingMessage.textContent = 'Applying core functionality...';
                                    
                                    // Add essential elements to our document
                                    essentialScripts.forEach(function(script) {
                                        document.head.appendChild(script.cloneNode(true));
                                    });
                                    
                                    Array.from(styles).forEach(function(style) {
                                        document.head.appendChild(style.cloneNode(true));
                                    });
                                    
                                    // Now we can replace with the real content
                                    loadingMessage.textContent = 'Rendering content...';
                                    setTimeout(function() {
                                        document.open();
                                        document.write(xhr.responseText);
                                        document.close();
                                    }, 100);
                                };
                                xhr.onerror = function() {
                                    loadingMessage.textContent = 'Error loading wiki. Please try again.';
                                };
                                xhr.send();
                            });
                        </script>
                    </head>
                    <body>
                        <h1>TiddlyWiki Loading...</h1>
                    </body>
                    </html>
                """.trimIndent()
                
                val bootstrapFile = File(wikiCacheDir, "bootstrap.html")
                bootstrapFile.writeText(bootstrapHtml, StandardCharsets.UTF_8)
                
                return@withContext "file://${bootstrapFile.absolutePath}"
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext null
            }
        }
    
    private fun generateSimpleKey(url: String): String {
        return url.hashCode().toString().replace("-", "0")
    }
}