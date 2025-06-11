package com.tiddlywikibrowser

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.Build
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Manages device thermal state and adjusts media playback accordingly
 * to prevent device overheating and performance issues
 */
class ThermalMediaManager private constructor(private val context: Context) : DefaultLifecycleObserver {
    
    companion object {
        @Volatile
        private var INSTANCE: ThermalMediaManager? = null
        
        fun getInstance(context: Context): ThermalMediaManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ThermalMediaManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        private const val TAG = "ThermalMediaManager"
        private const val THERMAL_CHECK_INTERVAL = 30000L // 30 seconds
        private const val BATTERY_TEMP_WARNING = 40.0f // Celsius
        private const val BATTERY_TEMP_CRITICAL = 45.0f // Celsius
    }
    
    private var webView: WebView? = null
    private var currentThermalState = ThermalState.NORMAL
    private var batteryTemperature = 0.0f
    private var isLowPowerModeEnabled = false
    
    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    enum class ThermalState {
        NORMAL,     // Full performance
        WARNING,    // Reduced background processing
        CRITICAL    // Minimal processing, pause non-essential media
    }
    
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val temperature = intent.getIntExtra("temperature", 0) / 10.0f
                    batteryTemperature = temperature
                    evaluateThermalState()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    isLowPowerModeEnabled = powerManager.isPowerSaveMode
                    evaluateThermalState()
                }
            }
        }
    }
      init {
        // Note: Removed ProcessLifecycleOwner dependency - will be managed by MainActivity
        registerBatteryReceiver()
        startThermalMonitoring()
    }
    
    fun setWebView(webView: WebView?) {
        this.webView = webView
        if (webView != null) {
            applyCurrentOptimizations()
        }
    }
    
    private fun registerBatteryReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        context.registerReceiver(batteryReceiver, filter)
    }
    
    private fun startThermalMonitoring() {
        handler.post(object : Runnable {
            override fun run() {
                evaluateThermalState()
                handler.postDelayed(this, THERMAL_CHECK_INTERVAL)
            }
        })
    }
    
    private fun evaluateThermalState() {
        val newState = when {
            batteryTemperature >= BATTERY_TEMP_CRITICAL || isSystemUnderPressure() -> ThermalState.CRITICAL
            batteryTemperature >= BATTERY_TEMP_WARNING || isLowPowerModeEnabled -> ThermalState.WARNING
            else -> ThermalState.NORMAL
        }
        
        if (newState != currentThermalState) {
            Log.i(TAG, "Thermal state changed: $currentThermalState -> $newState (temp: ${batteryTemperature}°C)")
            currentThermalState = newState
            applyThermalOptimizations(newState)
        }
    }
      private fun isSystemUnderPressure(): Boolean {
        return try {
            // Simplified pressure detection using memory info
            val memInfo = android.app.ActivityManager.MemoryInfo()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.getMemoryInfo(memInfo)
            
            // Consider system under pressure if available memory is very low
            memInfo.availMem < memInfo.threshold * 1.5
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check system pressure", e)
            false
        }
    }
    
    private fun applyThermalOptimizations(state: ThermalState) {
        val webView = this.webView ?: return
        
        val script = when (state) {
            ThermalState.NORMAL -> getNormalModeScript()
            ThermalState.WARNING -> getWarningModeScript()
            ThermalState.CRITICAL -> getCriticalModeScript()
        }
        
        ThreadManager.runOnMain {
            webView.evaluateJavascript(script, null)
        }
    }
    
    private fun applyCurrentOptimizations() {
        applyThermalOptimizations(currentThermalState)
    }
    
    private fun getNormalModeScript(): String = """
        (function() {
            console.log('ThermalManager: Applying normal mode optimizations');
            
            if (window.OptimizedMediaInterface) {
                // Resume normal monitoring if media is playing
                const media = window.OptimizedMediaInterface.activeElement;
                if (media && !media.paused) {
                    window.OptimizedMediaInterface.startLightweightMonitoring();
                }
                
                // Restore normal preload settings
                document.querySelectorAll('audio').forEach(audio => {
                    if (audio.dataset.optimizedHandled) {
                        audio.preload = 'metadata';
                    }
                });
            }
        })();
    """.trimIndent()
    
    private fun getWarningModeScript(): String = """
        (function() {
            console.log('ThermalManager: Applying warning mode optimizations (device getting warm)');
            
            if (window.OptimizedMediaInterface) {
                // Reduce monitoring frequency
                window.OptimizedMediaInterface.stopLightweightMonitoring();
                
                // If media is playing, restart with longer intervals
                const media = window.OptimizedMediaInterface.activeElement;
                if (media && !media.paused) {
                    setTimeout(() => {
                        // Custom monitoring with 10-second intervals instead of 5
                        if (window.OptimizedMediaInterface.monitoringInterval) {
                            clearInterval(window.OptimizedMediaInterface.monitoringInterval);
                        }
                        
                        window.OptimizedMediaInterface.monitoringInterval = setInterval(() => {
                            if (media && !media.paused) {
                                window.OptimizedMediaInterface.updateState(media);
                            }
                        }, 10000);
                    }, 2000);
                }
                
                // Reduce preloading to save CPU/memory
                document.querySelectorAll('audio, video').forEach(media => {
                    if (media.preload !== 'none' && media.paused) {
                        media.preload = 'none';
                    }
                });
            }
            
            // Reduce visual updates
            const elements = document.querySelectorAll('[style*="animation"], .animated');
            elements.forEach(el => {
                el.style.animationPlayState = 'paused';
            });
        })();
    """.trimIndent()
    
    private fun getCriticalModeScript(): String = """
        (function() {
            console.log('ThermalManager: Applying critical mode optimizations (device hot!)');
            
            if (window.OptimizedMediaInterface) {
                // Stop all monitoring to reduce CPU usage
                window.OptimizedMediaInterface.stopLightweightMonitoring();
                
                // Pause non-essential media playback
                const media = window.OptimizedMediaInterface.activeElement;
                if (media && !media.paused) {
                    // Only pause if it's background or non-user-initiated playback
                    if (document.visibilityState === 'hidden' || 
                        !media.dataset.userInitiated) {
                        console.log('Pausing media due to thermal protection');
                        media.pause();
                        
                        // Show user notification about thermal pause
                        if (window.Android && window.Android.showToast) {
                            window.Android.showToast('Media paused - device cooling down');
                        }
                    }
                }
                
                // Aggressively disable preloading
                document.querySelectorAll('audio, video').forEach(media => {
                    media.preload = 'none';
                    
                    // Clear buffered data for paused media
                    if (media.paused && media.currentTime === 0) {
                        const src = media.src;
                        media.removeAttribute('src');
                        media.load();
                        // Don't restore src automatically - wait for user interaction
                        media.dataset.originalSrc = src;
                    }
                });
            }
            
            // Stop all animations and transitions
            const style = document.createElement('style');
            style.textContent = '* { animation-play-state: paused !important; transition: none !important; }';
            document.head.appendChild(style);
            style.dataset.thermalOverride = 'true';
            
            // Suggest garbage collection
            if (window.gc && typeof window.gc === 'function') {
                try {
                    window.gc();
                } catch (e) {}
            }
        })();
    """.trimIndent()
    
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        startThermalMonitoring()
    }
    
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        handler.removeCallbacksAndMessages(null)
    }
    
    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister battery receiver", e)
        }
        handler.removeCallbacksAndMessages(null)
        INSTANCE = null
    }
    
    /**
     * Get current thermal state for external monitoring
     */
    fun getCurrentThermalState(): ThermalState = currentThermalState
    
    /**
     * Get current battery temperature
     */
    fun getBatteryTemperature(): Float = batteryTemperature
    
    /**
     * Force a thermal state evaluation (useful for testing)
     */
    fun forceThermalEvaluation() {
        evaluateThermalState()
    }
    
    /**
     * Check if device is currently in thermal protection mode
     */
    fun isInThermalProtection(): Boolean = currentThermalState != ThermalState.NORMAL
}
