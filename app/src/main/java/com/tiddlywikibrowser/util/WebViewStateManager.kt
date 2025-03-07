package com.tiddlywikibrowser.util

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import com.tiddlywikibrowser.model.WikiLoadState
import com.tiddlywikibrowser.model.WikiLoadStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class WebViewStateManager(private val context: Context) {
    private val stateMap = ConcurrentHashMap<String, WikiLoadState>()
    private val bundleMap = ConcurrentHashMap<String, Bundle>()
    private val _loadState = MutableStateFlow<WikiLoadState>(WikiLoadState.Initializing)
    val loadState: StateFlow<WikiLoadState> = _loadState

    companion object {
        private const val STATE_TIMEOUT = 10000L // 10 seconds
        private const val TAG = "WebViewStateManager"
    }

    suspend fun saveState(key: String, webView: WebView): Boolean {
        return try {
            withTimeout(STATE_TIMEOUT) {
                val bundle = Bundle()
                webView.saveState(bundle)
                bundleMap[key] = bundle
                setLoadState(key, WikiLoadState.Ready)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving state for $key", e)
            setLoadState(key, WikiLoadState.Error(e))
            false
        }
    }

    suspend fun restoreState(key: String, webView: WebView): Boolean {
        return withTimeoutOrNull(STATE_TIMEOUT) {
            try {
                bundleMap[key]?.let { bundle ->
                    setLoadState(key, WikiLoadState.Loading)
                    webView.restoreState(bundle)
                    setLoadState(key, WikiLoadState.Ready)
                    true
                } ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring state for $key", e)
                setLoadState(key, WikiLoadState.Error(e))
                false
            }
        } ?: false
    }

    fun getState(key: String): WikiLoadState {
        return stateMap[key] ?: WikiLoadState.Initializing
    }

    private fun setLoadState(key: String, state: WikiLoadState) {
        stateMap[key] = state
        _loadState.value = state
    }

    fun clearState(key: String) {
        bundleMap.remove(key)
        stateMap.remove(key)
    }

    fun clearAll() {
        bundleMap.clear()
        stateMap.clear()
        _loadState.value = WikiLoadState.Initializing
    }

    fun optimizeMemory() {
        // Clear bundles for non-ready states
        stateMap.forEach { (key, state) ->
            if (state !is WikiLoadState.Ready) {
                bundleMap.remove(key)
            }
        }
        System.gc()
    }
}