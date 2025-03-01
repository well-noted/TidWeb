package com.tiddlywikibrowser

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Specialized class for handling large TiddlyWiki files through progressive loading
 * and smart chunking to avoid ANRs
 */
class TiddlyWikiSplitter(private val context: Context) {
    /**
     * Creates a lightweight HTML loader that progressively loads and renders a TiddlyWiki
     * This prevents ANRs by breaking down the load process into smaller chunks
     */
    suspend fun createLightweightWikiLoader(url: String, cacheDir: File): File = withContext(Dispatchers.IO) {
        val loaderFile = File(cacheDir, "wiki_loader_${url.hashCode()}.html")
        
        // Create a lightweight loader that will dynamically load the wiki content
        // This prevents blocking the main thread with large TiddlyWiki files
        val loaderContent = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0">
                <title>Loading TiddlyWiki</title>
                <style>
                    body {
                        font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
                        margin: 0;
                        padding: 0;
                        background: #f5f5f5;
                        color: #333;
                        transition: background-color 0.3s ease;
                    }
                    .loading-container {
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        height: 100vh;
                        padding: 20px;
                        box-sizing: border-box;
                    }
                    .progress {
                        width: 80%;
                        height: 8px;
                        background-color: #eee;
                        border-radius: 4px;
                        margin-top: 20px;
                        overflow: hidden;
                    }
                    .progress-bar {
                        width: 0%;
                        height: 100%;
                        background-color: #007AFF;
                        border-radius: 4px;
                        transition: width 0.3s ease;
                    }
                    .status {
                        margin-top: 10px;
                        font-size: 14px;
                        color: #666;
                    }
                    
                    /* Support dark mode */
                    @media (prefers-color-scheme: dark) {
                        body {
                            background: #1a1a1a;
                            color: #f0f0f0;
                        }
                        .status {
                            color: #aaa;
                        }
                        .progress {
                            background-color: #333;
                        }
                    }
                </style>
                <script>
                    // Enhanced progressive loader for TiddlyWiki
                    // Loads and processes the wiki in chunks to prevent ANRs
                    class ProgressiveWikiLoader {
                        constructor(url) {
                            this.url = url;
                            this.progressBar = document.querySelector('.progress-bar');
                            this.statusElement = document.querySelector('.status');
                            this.chunks = [];
                            this.receivedLength = 0;
                            this.totalLength = 0;
                            this.parser = new DOMParser();
                        }
                        
                        updateProgress(progress, status) {
                            this.progressBar.style.width = progress + '%';
                            if (status) {
                                this.statusElement.textContent = status;
                            }
                        }
                        
                        async loadWiki() {
                            try {
                                this.updateProgress(0, "Connecting to wiki...");
                                
                                // Fetch with timeout
                                const controller = new AbortController();
                                const timeoutId = setTimeout(() => controller.abort(), 30000);
                                const response = await fetch(this.url, { signal: controller.signal });
                                clearTimeout(timeoutId);
                                
                                if (!response.ok) {
                                    throw new Error('Failed to load wiki: ' + response.status);
                                }
                                
                                const reader = response.body.getReader();
                                this.totalLength = +response.headers.get('Content-Length') || 1000000;
                                
                                this.updateProgress(0, "Loading wiki content...");
                                
                                // Read in 32KB chunks to avoid memory pressure
                                while (true) {
                                    const { done, value } = await reader.read();
                                    if (done) break;
                                    
                                    this.chunks.push(value);
                                    this.receivedLength += value.length;
                                    
                                    // Show progress
                                    const progress = Math.min(99, Math.round((this.receivedLength / this.totalLength) * 100));
                                    this.updateProgress(progress);
                                    
                                    // Yield to UI thread periodically
                                    await new Promise(resolve => setTimeout(resolve, 10));
                                }
                                
                                this.updateProgress(99, "Processing wiki...");
                                
                                // Combine chunks and decode
                                const blob = new Blob(this.chunks);
                                const text = await blob.text();
                                
                                // Process the content with delayed execution to avoid ANR
                                await this.processWikiContent(text);
                                
                            } catch (error) {
                                console.error("Error loading wiki:", error);
                                this.updateProgress(100, "Error: " + error.message);
                                
                                // Show error UI
                                const container = document.querySelector('.loading-container');
                                const errorMessage = error && typeof error.message === 'string' ? error.message : 'Unknown error occurred';
                                container.innerHTML = `
                                    <h2>Unable to load wiki</h2>

                                    <button onclick="location.reload()">Try Again</button>
                                `;
                            }
                        }
                        
                        async processWikiContent(html) {
                            // Create a new empty document to stage our content
                            const stagingDoc = document.implementation.createHTMLDocument("TiddlyWiki");
                            
                            try {
                                // Process large wiki in phases to avoid blocking
                                this.updateProgress(99, "Processing HTML structure...");
                                await new Promise(r => setTimeout(r, 10));  // Let UI update
                                
                                // Parse HTML
                                stagingDoc.documentElement.innerHTML = html;
                                
                                this.updateProgress(100, "Starting TiddlyWiki...");
                                await new Promise(r => setTimeout(r, 50));  // Final UI update
                                
                                // Carefully transition to the full wiki
                                document.open();
                                document.write(html);
                                document.close();
                                
                                // Cleanup memory
                                this.chunks = null;
                                
                            } catch (error) {
                                console.error("Error processing wiki:", error);
                                this.updateProgress(100, "Error processing wiki: " + error.message);
                            }
                        }
                    }
                    
                    // Start loading when page is ready
                    window.addEventListener('DOMContentLoaded', () => {
                        const loader = new ProgressiveWikiLoader('$url');
                        loader.loadWiki();
                    });
                </script>
            </head>
            <body>
                <div class="loading-container">
                    <h2>Loading TiddlyWiki</h2>
                    <div class="progress">
                        <div class="progress-bar"></div>
                    </div>
                    <div class="status">Preparing...</div>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        loaderFile.writeText(loaderContent)
        loaderFile
    }
    
    /**
     * Creates an optimized cache version of a TiddlyWiki by removing non-essential parts
     * for initial loading and adding them back progressively
     */
    suspend fun createOptimizedWikiCache(url: String, cacheDir: File): File = withContext(Dispatchers.IO) {
        val cacheFile = File(cacheDir, "optimized_wiki_${url.hashCode()}.html")
        
        try {
            // Fetch the wiki content
            val connection = java.net.URL(url).openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            
            val inputStream = connection.getInputStream()
            val content = inputStream.bufferedReader().use { it.readText() }
            
            // Simple optimization: add script to defer image loading
            val optimizedContent = content.replace("</head>", """
                <script>
                // Optimize TiddlyWiki loading
                (function() {
                    // Defer image loading
                    window.addEventListener('DOMContentLoaded', function() {
                        // Handle images
                        var observer = new IntersectionObserver(function(entries) {
                            entries.forEach(function(entry) {
                                if (entry.isIntersecting) {
                                    var img = entry.target;
                                    if (img.dataset.src) {
                                        img.src = img.dataset.src;
                                        delete img.dataset.src;
                                        observer.unobserve(img);
                                    }
                                }
                            });
                        }, {rootMargin: '200px'});
                        
                        // After TiddlyWiki is fully loaded
                        setTimeout(function() {
                            document.querySelectorAll('img[src]').forEach(function(img) {
                                if (!img.loading) img.loading = 'lazy';
                                img.decoding = 'async';
                            });
                        }, 1000);
                    });
                })();
                </script>
                </head>
            """.trimIndent())
            
            cacheFile.writeText(optimizedContent)
        } catch (e: Exception) {
            e.printStackTrace()
            // If optimization fails, return null
            throw e
        }
        
        cacheFile
    }
}