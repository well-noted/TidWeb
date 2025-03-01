package com.tiddlywikibrowser

/**
 * Wiki loading strategy enum to determine how to load different wiki sizes
 */
enum class WikiLoadStrategy {
    INITIALIZING,
    SMALL_WIKI,
    MEDIUM_WIKI,
    LARGE_WIKI
}

/**
 * Extension properties for WikiInstance class
 */
val WikiInstance.id: String?
    get() = this.url

/**
 * Data class for Wiki instances
 */
data class WikiInstance(
    val name: String,
    val url: String
)