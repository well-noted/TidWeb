package com.tiddlywikibrowser.model

/**
 * Represents a TiddlyWiki instance with its metadata
 */
data class WikiInstance(
    val name: String,
    val url: String,
    val isLocalFile: Boolean = false,
    val id: String? = null,
    val sourceUrl: String? = null
) {
    // Computed property to get a unique identifier from the URL
    val idFromUrl: String?
        get() = try {
            java.net.URL(url).path.substringAfterLast('/').substringBeforeLast('.')
        } catch (e: Exception) {
            null
        }

    // Computed property to handle file paths correctly
    val formattedUrl: String
        get() = if (isLocalFile && !url.startsWith("file://")) {
            "file://$url"
        } else {
            url
        }
}