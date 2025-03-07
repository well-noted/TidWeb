package com.tiddlywikibrowser

import java.net.URL

data class WikiInstance(
    val name: String,
    val url: String,
    val id: String? = null,
    val isLocalFile: Boolean = false,
    val sourceUrl: String? = null
) {
    // Use the provided URL after formatting it, unless it's a local file
    val formattedUrl: String = if (isLocalFile) url else formatUrl(url)
    
    // For backward compatibility - some places in the code might still use the url property
    // but we want it to always return the formatted URL
    val idFromUrl: String? get() = id ?: url

    companion object {
        fun formatUrl(input: String): String {
            val trimmed = input.trim()
            
            // Skip formatting for file URLs and content URIs
            if (trimmed.startsWith("file:") || trimmed.startsWith("content:")) {
                return trimmed
            }
            
            // First fix common protocol typos
            val fixedProtocol = when {
                trimmed.startsWith("htttp://") -> "http://" + trimmed.substring(7)
                trimmed.startsWith("httpp://") -> "http://" + trimmed.substring(7)
                trimmed.startsWith("htps://") -> "https://" + trimmed.substring(7)
                trimmed.startsWith("htttps://") -> "https://" + trimmed.substring(8)
                else -> trimmed
            }
            
            // Then add protocol if missing
            return when {
                fixedProtocol.startsWith("http://") || fixedProtocol.startsWith("https://") -> fixedProtocol
                fixedProtocol.matches(Regex("^[\\w.-]+:\\d+$")) -> "http://$fixedProtocol" // host:port
                fixedProtocol.matches(Regex("^[\\w.-]+$")) -> "http://$fixedProtocol" // just hostname
                else -> "http://$fixedProtocol"
            }
        }

        fun validateUrl(url: String): Result<String> {
            return try {
                // Skip validation for file URLs and content URIs
                if (url.startsWith("file:") || url.startsWith("content:")) {
                    return Result.success(url)
                }
                
                val formatted = formatUrl(url)
                URL(formatted) // Validate URL format
                Result.success(formatted)
            } catch (e: Exception) {
                Result.failure(IllegalArgumentException("Invalid URL format: $url"))
            }
        }
    }
}