package com.tiddlywikibrowser

import android.content.Context
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class TiddlyWikiCache(private val context: Context) {
    private val cacheDir = File(context.cacheDir, "wiki_cache")
    
    init {
        cacheDir.mkdirs()
    }
    
    suspend fun getOrCacheWikiFile(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val cacheFile = getCacheFile(url)
            if (cacheFile.exists() && !isExpired(cacheFile)) {
                return@withContext cacheFile.absolutePath
            }
            
            // Download and cache the file
            val connection = URL(url).openConnection()
            connection.connect()
            
            connection.getInputStream().use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            return@withContext cacheFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
    
    private fun getCacheFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, hash)
    }
    
    private fun isExpired(file: File): Boolean {
        val maxAge = 24 * 60 * 60 * 1000 // 24 hours
        return System.currentTimeMillis() - file.lastModified() > maxAge
    }
    
    fun clearCache() {
        ThreadManager.runOnBackground {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }
}