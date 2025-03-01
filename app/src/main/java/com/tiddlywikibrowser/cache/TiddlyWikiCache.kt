package com.tiddlywikibrowser.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * Custom caching system for TiddlyWiki files
 */
class TiddlyWikiCache(private val context: Context) {
    private val cacheDir = File(context.cacheDir, "wiki_cache")
    
    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }
    
    /**
     * Get cached file path for a URL, or download if not cached
     */
    suspend fun getOrCacheWikiFile(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val cacheKey = generateCacheKey(url)
            val cachedFile = File(cacheDir, "$cacheKey.html")
            
            // Check if we have a fresh cache (less than 1 hour old)
            if (cachedFile.exists() && 
                (System.currentTimeMillis() - cachedFile.lastModified() < 3600000)) {
                Log.d("TiddlyWikiCache", "Using cached file for $url")
                return@withContext "file://${cachedFile.absolutePath}"
            }
            
            // Download fresh content
            URL(url).openStream().use { input ->
                cachedFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            return@withContext "file://${cachedFile.absolutePath}"
        } catch (e: Exception) {
            Log.e("TiddlyWikiCache", "Failed to cache: $url", e)
            return@withContext null
        }
    }
    
    /**
     * Generate a unique key for the URL
     */
    private fun generateCacheKey(url: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Clear old cache files
     */
    suspend fun clearOldCache() = withContext(Dispatchers.IO) {
        val oneWeekAgo = System.currentTimeMillis() - (7 * 24 * 3600000)
        cacheDir.listFiles()?.forEach { file ->
            if (file.lastModified() < oneWeekAgo) {
                file.delete()
            }
        }
    }
}