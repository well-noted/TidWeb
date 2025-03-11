package com.tiddlywikibrowser

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Utility class to help with adapting the UI to different screen sizes,
 * with special handling for Very Small Screens (VSS) like flip phones.
 */
class ScreenUtils {
    companion object {
        /**
         * Check if the device has a very small screen (like flip phones)
         */
        fun isVerySmallScreen(context: Context): Boolean {
            return context.resources.getBoolean(R.bool.is_compact_layout)
        }

        /**
         * Get screen width in dp
         */
        fun getScreenWidthDp(context: Context): Float {
            val metrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getMetrics(metrics)
            return metrics.widthPixels / metrics.density
        }
        
        /**
         * Get screen height in dp
         */
        fun getScreenHeightDp(context: Context): Float {
            val metrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getMetrics(metrics)
            return metrics.heightPixels / metrics.density
        }
        
        /**
         * Should we use compact UI elements?
         */
        fun shouldUseCompactUi(context: Context): Boolean {
            return context.resources.getBoolean(R.bool.use_simplified_ui)
        }
        
        /**
         * Should we use minimal padding?
         */
        fun useMinimalPadding(context: Context): Boolean {
            return context.resources.getBoolean(R.bool.use_minimal_padding)
        }
        
        /**
         * Get appropriate padding for the current screen size
         */
        fun getAdaptivePadding(context: Context, basePadding: Int): Int {
            val screenWidth = getScreenWidthDp(context)
            return when {
                screenWidth < 200 -> (basePadding * 0.5).toInt() // 50% less padding for VSS
                screenWidth < 300 -> (basePadding * 0.7).toInt() // 30% less padding for small screens
                else -> basePadding // default padding
            }
        }
        
        /**
         * Is the device in landscape mode?
         */
        fun isLandscape(context: Context): Boolean {
            return context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        
        /**
         * Get adaptive text size based on screen dimensions
         */
        fun getAdaptiveTextSize(context: Context, baseSize: Float): Float {
            val screenWidth = getScreenWidthDp(context)
            return when {
                screenWidth < 200 -> baseSize * 0.75f // 25% smaller text for VSS
                screenWidth < 300 -> baseSize * 0.85f // 15% smaller text for small screens
                else -> baseSize
            }
        }
        
        /**
         * Get WebView text zoom level based on screen size
         */
        fun getWebViewTextZoom(context: Context): Int {
            return context.resources.getInteger(R.integer.webview_text_zoom)
        }
        
        /**
         * Should WebView force enable zoom controls?
         */
        fun shouldForceWebViewZoom(context: Context): Boolean {
            return context.resources.getBoolean(R.bool.webview_force_zoom)
        }
        
        /**
         * Get a scaling factor for UI elements based on screen size
         */
        fun getUiScaleFactor(context: Context): Float {
            val width = getScreenWidthDp(context)
            return when {
                width < 200 -> 0.75f  // Very small screens like flip phones
                width < 300 -> 0.85f  // Small phones
                width < 400 -> 0.95f  // Medium phones
                else -> 1.0f         // Normal or larger screens
            }
        }
    }
}