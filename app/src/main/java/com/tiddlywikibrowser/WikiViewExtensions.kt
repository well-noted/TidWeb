package com.tiddlywikibrowser

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extension property to convert file paths to valid file:// URLs
 */
val String.toFileUrl: String
    get() = if (this.startsWith("file://")) this else "file://$this"

/**
 * Helper class to manage WebView local file management
 */
class WebViewFileManager(private val context: Context) {
    suspend fun getLocalFileUrl(url: String): String? = withContext(Dispatchers.IO) {
        val cache = TiddlyWikiCache(context)
        val filePath = cache.getOrCacheWikiFile(url)
        return@withContext filePath?.toFileUrl
    }
    
    fun createFileUrlFor(filename: String): String {
        val file = File(context.cacheDir, filename)
        return file.absolutePath.toFileUrl
    }
}