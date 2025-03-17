package com.tiddlywikibrowser.model

/**
 * Defines different loading strategies based on wiki size and complexity
 */
enum class WikiLoadStrategy {
    INITIALIZING,     // Initial state while analyzing wiki
    SMALL_WIKI,      // < 1MB, standard loading
    MEDIUM_WIKI,     // 1-5MB, moderate optimizations
    LARGE_WIKI       // > 5MB, aggressive optimizations
}