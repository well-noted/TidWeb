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
        // Constants for screen classification
        private const val FLIP_PHONE_FRONT_SCREEN_HEIGHT_THRESHOLD = 400f
        private const val VERY_SMALL_SCREEN_WIDTH_THRESHOLD = 200f
        private const val SMALL_SCREEN_WIDTH_THRESHOLD = 300f
        private const val COMPACT_LAYOUT_HEIGHT_THRESHOLD = 600f

        /**
         * Check if the device is in folded state with a very small screen (like flip phones front screen)
         * This specifically checks for both height and width to detect folded state
         */
        fun isFoldedFlipPhone(context: Context): Boolean {
            val height = getScreenHeightDp(context)
            val width = getScreenWidthDp(context)
            
            // Flip phone front screens are typically very short in height but may have normal width
            return height < FLIP_PHONE_FRONT_SCREEN_HEIGHT_THRESHOLD && width < SMALL_SCREEN_WIDTH_THRESHOLD
        }

        /**
         * Check if the device has a very small screen (like flip phones)
         * This uses configuration resources but also checks screen dimensions
         */
        fun isVerySmallScreen(context: Context): Boolean {
            // First check if it's marked as compact in resources (sw180dp)
            val isCompactInResources = context.resources.getBoolean(R.bool.is_compact_layout)
            
            if (isCompactInResources) return true
            
            // If not already detected by resources, check dimensions directly
            val width = getScreenWidthDp(context)
            val height = getScreenHeightDp(context)
            
            // Very small screens: either very narrow width or very short height (folded flip phone)
            return width < VERY_SMALL_SCREEN_WIDTH_THRESHOLD || 
                   (width < SMALL_SCREEN_WIDTH_THRESHOLD && height < FLIP_PHONE_FRONT_SCREEN_HEIGHT_THRESHOLD)
        }
        
        /**
         * Check if the screen dimensions require a compact layout
         * Different from isVerySmallScreen which is more extreme
         */
        fun needsCompactLayout(context: Context): Boolean {
            // First check resource configuration
            if (context.resources.getBoolean(R.bool.is_compact_layout)) return true
            
            val width = getScreenWidthDp(context)
            val height = getScreenHeightDp(context)
            
            // Need compact layout if either dimension is constrained
            return width < SMALL_SCREEN_WIDTH_THRESHOLD || height < COMPACT_LAYOUT_HEIGHT_THRESHOLD
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
         * Gets the screen aspect ratio (width/height)
         * Useful for detecting unusual screen shapes
         */
        fun getScreenAspectRatio(context: Context): Float {
            val width = getScreenWidthDp(context)
            val height = getScreenHeightDp(context)
            return width / height
        }
        
        /**
         * Should we use compact UI elements?
         */
        fun shouldUseCompactUi(context: Context): Boolean {
            return context.resources.getBoolean(R.bool.use_simplified_ui) || isVerySmallScreen(context)
        }
        
        /**
         * Should we use minimal padding?
         */
        fun useMinimalPadding(context: Context): Boolean {
            return context.resources.getBoolean(R.bool.use_minimal_padding) || isVerySmallScreen(context)
        }
        
        /**
         * Get appropriate padding for the current screen size
         */
        fun getAdaptivePadding(context: Context, basePadding: Int): Int {
            val screenWidth = getScreenWidthDp(context)
            val screenHeight = getScreenHeightDp(context)
            
            // Consider both dimensions for proper padding
            return when {
                // Folded flip phones - extreme minimal padding
                isFoldedFlipPhone(context) -> (basePadding * 0.3).toInt() 
                
                // Very small screens - very minimal padding
                isVerySmallScreen(context) -> (basePadding * 0.5).toInt()
                
                // Small screens - reduced padding
                screenWidth < SMALL_SCREEN_WIDTH_THRESHOLD -> (basePadding * 0.7).toInt()
                
                // Default padding
                else -> basePadding
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
            // Take into account both width and height for folded screens
            if (isFoldedFlipPhone(context)) {
                return baseSize * 0.7f // 30% smaller for folded flip phones
            }
            
            val screenWidth = getScreenWidthDp(context)
            return when {
                screenWidth < VERY_SMALL_SCREEN_WIDTH_THRESHOLD -> baseSize * 0.75f // 25% smaller for VSS
                screenWidth < SMALL_SCREEN_WIDTH_THRESHOLD -> baseSize * 0.85f // 15% smaller for small screens
                else -> baseSize
            }
        }
        
        /**
         * Get WebView text zoom level based on screen size
         */
        fun getWebViewTextZoom(context: Context): Int {
            // If resources don't define this value, calculate it based on screen size
            val configZoom = try {
                context.resources.getInteger(R.integer.webview_text_zoom)
            } catch (e: Exception) {
                0 // Default to zero if not found
            }
            
            if (configZoom > 0) return configZoom
            
            // Calculate based on screen dimensions if not defined in resources
            return when {
                isFoldedFlipPhone(context) -> 70 // Very small text for flip phone front screen
                isVerySmallScreen(context) -> 80 // Small text for very small screens
                getScreenWidthDp(context) < SMALL_SCREEN_WIDTH_THRESHOLD -> 90 // Slightly reduced for small screens
                else -> 100 // Default zoom
            }
        }
        
        /**
         * Should WebView force enable zoom controls?
         */
        fun shouldForceWebViewZoom(context: Context): Boolean {
            // Check resources first
            val forceZoomInResources = try {
                context.resources.getBoolean(R.bool.webview_force_zoom)
            } catch (e: Exception) {
                false
            }
            
            // If it's configured in resources, use that value
            if (forceZoomInResources) return true
            
            // Otherwise determine based on screen size
            return isVerySmallScreen(context) || isFoldedFlipPhone(context)
        }
        
        /**
         * Get a scaling factor for UI elements based on screen size
         */
        fun getUiScaleFactor(context: Context): Float {
            // For folded flip phones, we need extreme scaling
            if (isFoldedFlipPhone(context)) {
                return 0.65f
            }
            
            val width = getScreenWidthDp(context)
            return when {
                width < VERY_SMALL_SCREEN_WIDTH_THRESHOLD -> 0.75f  // Very small screens
                width < SMALL_SCREEN_WIDTH_THRESHOLD -> 0.85f  // Small phones
                width < 400 -> 0.95f  // Medium phones
                else -> 1.0f         // Normal or larger screens
            }
        }
        
        /**
         * Check if we should use a single-column layout
         */
        fun shouldUseSingleColumn(context: Context): Boolean {
            return isVerySmallScreen(context) || isFoldedFlipPhone(context) || 
                   getScreenWidthDp(context) < SMALL_SCREEN_WIDTH_THRESHOLD
        }
        
        /**
         * Get the maximum number of navigation items to show
         * based on screen width
         */
        fun getMaxNavigationItems(context: Context): Int {
            val width = getScreenWidthDp(context)
            return when {
                isFoldedFlipPhone(context) -> 3 // Super limited for folded flip phones
                width < VERY_SMALL_SCREEN_WIDTH_THRESHOLD -> 4 // Very limited for very small screens
                width < SMALL_SCREEN_WIDTH_THRESHOLD -> 5 // Limited for small screens
                width < 400 -> 7 // Medium phones
                else -> 10 // No real limit for larger screens
            }
        }
    }
}