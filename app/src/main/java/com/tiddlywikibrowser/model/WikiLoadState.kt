package com.tiddlywikibrowser.model

sealed class WikiLoadState {
    object Initializing : WikiLoadState()
    object Loading : WikiLoadState()
    object Ready : WikiLoadState()
    data class Error(
        val error: Throwable,
        val isRecoverable: Boolean = true,
        val retryCount: Int = 0
    ) : WikiLoadState()
    
    fun isStable(): Boolean = this is Ready || (this is Error && !isRecoverable)
    fun canRetry(): Boolean = this is Error && isRecoverable && retryCount < MAX_RETRIES
    
    companion object {
        const val MAX_RETRIES = 3
    }
}