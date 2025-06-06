package com.tiddlywikibrowser

import android.app.Application
import android.util.Log
import com.tiddlywikibrowser.media.MediaSessionManager

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("MainApplication", "MainApplication onCreate: Initializing MediaSessionManager singleton.")
        MediaSessionManager.getInstance(this)
    }
} 