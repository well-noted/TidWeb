package com.tiddlywikibrowser

/**
 * Extension functions for common operations in the app
 */

/**
 * Convert a string to Boolean strictly, or return null if the string is not "true" or "false"
 */
fun String?.toBooleanStrictOrNull(): Boolean? {
    return when (this) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/**
 * Helper function to create ThreadManager tasks with result
 */
fun <T> ThreadManager.executeWithResult(task: () -> T, onComplete: (T) -> Unit) {
    executeTask {
        val result = task()
        runOnMain { onComplete(result) }
    }
}