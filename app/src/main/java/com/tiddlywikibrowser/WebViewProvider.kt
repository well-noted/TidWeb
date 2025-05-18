package com.tiddlywikibrowser

interface WebViewProvider {
    fun executeJavascript(script: String, callback: ((String) -> Unit)?)
    fun getCurrentMediaState(callback: (title: String?, artist: String?, duration: Long?, position: Long?, isPlaying: Boolean?) -> Unit)
}
