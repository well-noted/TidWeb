package com.tiddlywikibrowser.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

object ThreadManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    
    fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
    
    fun runOnBackground(action: () -> Unit) {
        backgroundExecutor.execute(action)
    }
    
    fun shutdown() {
        backgroundExecutor.shutdown()
    }
}