package com.tiddlywikibrowser

import java.net.URL

data class WikiInstance(
    val name: String,
    private val _url: String,
    val id: String? = null
) {
    val url: String = formatUrl(_url)

    companion object {
        fun formatUrl(input: String): String {
            val trimmed = input.trim()
            
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
                val formatted = formatUrl(url)
                URL(formatted) // Validate URL format
                Result.success(formatted)
            } catch (e: Exception) {
                Result.failure(IllegalArgumentException("Invalid URL format: $url"))
            }
        }
    }
}