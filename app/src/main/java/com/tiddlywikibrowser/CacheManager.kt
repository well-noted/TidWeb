package com.tiddlywikibrowser

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.*
import java.security.MessageDigest

object CacheManager {
    private const val CACHE_DIR = "webview_cache"
    private const val MAX_AGE = 30 * 24 * 60 * 60 * 1000L // 30 days in milliseconds

    fun getCache(context: Context, url: String): WebResourceResponse? {
        try {
            val file = getCacheFile(context, url)
            if (file.exists()) {
                val mimeType = getMimeType(url)
                val inputStream = FileInputStream(file)
                return WebResourceResponse(mimeType, "UTF-8", inputStream)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun saveToCache(context: Context, url: String, data: InputStream) {
        try {
            val file = getCacheFile(context, url)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { output ->
                data.copyTo(output)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getCacheFile(context: Context, url: String): File {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        val fileName = generateCacheFileName(url)
        return File(cacheDir, fileName)
    }

    private fun generateCacheFileName(url: String): String {
        // Use MD5 hash for filename to handle long URLs and special characters
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun getMimeType(url: String): String {
        return when {
            url.endsWith(".html", true) -> "text/html"
            url.endsWith(".js", true) -> "application/javascript"
            url.endsWith(".css", true) -> "text/css"
            url.endsWith(".json", true) -> "application/json"
            url.endsWith(".png", true) -> "image/png"
            url.endsWith(".jpg", true) || url.endsWith(".jpeg", true) -> "image/jpeg"
            url.endsWith(".gif", true) -> "image/gif"
            url.endsWith(".svg", true) -> "image/svg+xml"
            url.endsWith(".xml", true) -> "application/xml"
            url.endsWith(".woff", true) -> "font/woff"
            url.endsWith(".woff2", true) -> "font/woff2"
            url.endsWith(".ttf", true) -> "font/ttf"
            url.endsWith(".eot", true) -> "application/vnd.ms-fontobject"
            else -> "text/plain"
        }
    }

    fun clearCache(context: Context) {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (cacheDir.exists()) {
            try {
                val now = System.currentTimeMillis()
                cacheDir.listFiles()?.forEach { file ->
                    val age = now - file.lastModified()
                    if (age > MAX_AGE) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}