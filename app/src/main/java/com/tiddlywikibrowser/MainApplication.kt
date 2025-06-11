package com.tiddlywikibrowser

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tiddlywikibrowser.media.MediaSessionManager

class MainApplication : Application() {
    
    private var importanceMonitorHandler: Handler? = null
    private var isMonitoring = false
    private var lastImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    
    companion object {
        private const val PREFS_NAME = "app_lifecycle_state"
        private const val KEY_WAS_TASK_REMOVED = "was_task_removed"
        
        fun markTaskRemoved(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_WAS_TASK_REMOVED, true).apply()
            Log.d("MainApplication", "Marked app as task removed")
        }
        
        fun wasTaskRemoved(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_WAS_TASK_REMOVED, false)
        }
        
        fun clearTaskRemovedFlag(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_WAS_TASK_REMOVED, false).apply()
            Log.d("MainApplication", "Cleared task removed flag")
        }
    }
      override fun onCreate() {
        super.onCreate()
        Log.d("MainApplication", "MainApplication onCreate: Process starting")
        
        // Check if this is a restart after task removal
        if (wasTaskRemoved(this)) {
            Log.d("MainApplication", "App was previously dismissed, clearing all state")
            
            // Clear the flag
            clearTaskRemovedFlag(this)
            
            // Clear all cached data and force fresh start
            try {
                // Clear WebView cache and data
                android.webkit.WebView(this).clearCache(true)
                android.webkit.WebView(this).clearHistory()
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                
                // Clear any other app-specific caches
                cacheDir.deleteRecursively()
                
                Log.d("MainApplication", "Cleared all app state after dismissal")
            } catch (e: Exception) {
                Log.e("MainApplication", "Error clearing app state", e)
            }
        }
          Log.d("MainApplication", "MainApplication onCreate: Initializing MediaSessionManager singleton.")
        MediaSessionManager.getInstance(this)
        
        // Start monitoring process importance for dismissal detection
        startProcessImportanceMonitoring()
    }
    
    private fun startProcessImportanceMonitoring() {
        if (isMonitoring) return
        
        isMonitoring = true
        importanceMonitorHandler = Handler(Looper.getMainLooper())
          val monitorRunnable = object : Runnable {
            override fun run() {
                try {
                    checkProcessImportance()
                    // Check every 500ms for faster detection
                    importanceMonitorHandler?.postDelayed(this, 500)
                } catch (e: Exception) {
                    Log.e("MainApplication", "Error monitoring process importance", e)
                    // Continue monitoring despite errors
                    importanceMonitorHandler?.postDelayed(this, 500)
                }
            }
        }
        
        importanceMonitorHandler?.post(monitorRunnable)
        Log.d("MainApplication", "Started process importance monitoring")
    }
      private fun checkProcessImportance() {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = activityManager.runningAppProcesses
            
            if (runningProcesses != null) {
                val myPid = android.os.Process.myPid()
                val myProcess = runningProcesses.find { it.pid == myPid }
                
                if (myProcess != null) {
                    val currentImportance = myProcess.importance
                    
                    // Log importance changes for debugging
                    if (currentImportance != lastImportance) {
                        Log.d("MainApplication", "Process importance changed: $lastImportance -> $currentImportance")
                    }
                    
                    // Check for multiple dismissal indicators
                    val isDismissed = currentImportance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE ||
                                     currentImportance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY ||
                                     (currentImportance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED && 
                                      lastImportance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
                    
                    if (isDismissed && lastImportance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) {
                        Log.d("MainApplication", "Detected app dismissal from recent apps! Importance: $currentImportance")
                        handleAppDismissal()
                        return // Exit monitoring after dismissal detected
                    }
                    
                    lastImportance = currentImportance
                } else {
                    Log.w("MainApplication", "Could not find own process in running processes - possible dismissal")
                    // If we can't find our own process, we might have been dismissed
                    handleAppDismissal()
                    return
                }
            } else {
                Log.w("MainApplication", "No running processes found - possible dismissal")
                handleAppDismissal()
                return
            }
        } catch (e: Exception) {
            Log.e("MainApplication", "Error checking process importance", e)
            // If we can't check importance, something might be wrong - consider it a dismissal
            handleAppDismissal()
        }
    }
      private fun handleAppDismissal() {
        Log.d("MainApplication", "Handling app dismissal - triggering cleanup and process termination")
        
        try {
            // Mark as dismissed for next app start
            markTaskRemoved(this)
            
            // Force immediate cleanup
            try {
                MediaSessionManager.getInstance(this).release()
                Log.d("MainApplication", "Released MediaSessionManager")
            } catch (e: Exception) {
                Log.e("MainApplication", "Error releasing MediaSessionManager", e)
            }
            
            // Stop media service
            try {
                val serviceIntent = Intent(this, MediaPlaybackService::class.java)
                stopService(serviceIntent)
                Log.d("MainApplication", "Stopped MediaPlaybackService")
            } catch (e: Exception) {
                Log.e("MainApplication", "Error stopping MediaPlaybackService", e)
            }
            
            // Clear all notifications to ensure they're removed
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancelAll()
                Log.d("MainApplication", "Cancelled all notifications")
            } catch (e: Exception) {
                Log.e("MainApplication", "Error cancelling notifications", e)
            }
            
            // Stop monitoring since app is being dismissed
            stopProcessImportanceMonitoring()
            
            // Force terminate the process to ensure complete cleanup
            Log.d("MainApplication", "Force terminating process to ensure complete cleanup")
            try {
                // Clear any remaining app state
                cacheDir.deleteRecursively()
                
                // Kill the process immediately
                android.os.Process.killProcess(android.os.Process.myPid())
                
                // Backup: use System.exit if killProcess doesn't work
                System.exit(0)
            } catch (e: Exception) {
                Log.e("MainApplication", "Error terminating process", e)
                // Even if process kill fails, at least we cleaned up
            }
            
        } catch (e: Exception) {
            Log.e("MainApplication", "Error during dismissal cleanup", e)
            // Still try to kill process even if cleanup failed
            try {
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (ex: Exception) {
                Log.e("MainApplication", "Failed to kill process after cleanup error", ex)
            }
        }
    }
    
    private fun stopProcessImportanceMonitoring() {
        isMonitoring = false
        importanceMonitorHandler?.removeCallbacksAndMessages(null)
        importanceMonitorHandler = null
        Log.d("MainApplication", "Stopped process importance monitoring")
    }
    
    override fun onTerminate() {
        super.onTerminate()
        stopProcessImportanceMonitoring()
    }
}