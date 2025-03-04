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
 * Note: WikiInstance is defined in WikiInstance.kt
 */
val WikiInstance.idFromUrl: String?
    get() = this.url