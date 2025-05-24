package com.tiddlywikibrowser.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.webkit.WebSettings
import com.tiddlywikibrowser.MainActivity
import com.tiddlywikibrowser.ThreadManager
import com.tiddlywikibrowser.WikiViewModel

/**
 * Manages network connectivity monitoring and related functionality
 */
class NetworkManager(
    private val context: Context,
    private val viewModelProvider: () -> WikiViewModel
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkCheckTime = 0L
    private val NETWORK_CHECK_THROTTLE = 5000L
    private val TAG = "NetworkManager"
    
    fun setupNetworkMonitoring() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                super.onLost(network)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNetworkCheckTime > NETWORK_CHECK_THROTTLE) {
                    lastNetworkCheckTime = currentTime
                    ThreadManager.runOnMain {
                        val viewModel = viewModelProvider()
                        viewModel.setOfflineState(true)
                        Log.d(TAG, "Network lost - App is now offline")
                        
                        // Update WebView cache mode for offline operation
                        viewModel.currentWiki.value?.let { wiki ->
                            if (!wiki.isLocalFile) {
                                viewModel.getOrCreateWebView(wiki, context as MainActivity)?.let { webView ->
                                    webView.settings.cacheMode = WebSettings.LOAD_CACHE_ONLY
                                }
                            }
                        }
                    }
                }
            }
            
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNetworkCheckTime > NETWORK_CHECK_THROTTLE) {
                    lastNetworkCheckTime = currentTime
                    
                    ThreadManager.runOnMain {
                        val viewModel = viewModelProvider()
                        viewModel.setOfflineState(false)
                        Log.d(TAG, "Network available - App is now online")
                        
                        // Restore normal cache mode
                        viewModel.currentWiki.value?.let { wiki ->
                            if (!wiki.isLocalFile) {
                                viewModel.getOrCreateWebView(wiki, context as MainActivity)?.let { webView ->
                                    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                                }
                            }
                        }
                    }
                    
                    // Validate internet access in the background
                    ThreadManager.runOnBackground {
                        val capabilities = connectivityManager.getNetworkCapabilities(network)
                        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        
                        if (!hasInternet) {
                            Log.d(TAG, "Network available but internet validation failed")
                        }
                    }
                }
            }
        }
        
        // Register network callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
                
                // Initial check for network state
                ThreadManager.runOnBackground {
                    val activeNetwork = connectivityManager.activeNetwork
                    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                    val isOffline = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true ||
                            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true
                    
                    ThreadManager.runOnMain {
                        val viewModel = viewModelProvider()
                        viewModel.setOfflineState(isOffline)
                        Log.d(TAG, "Initial network state: ${if (isOffline) "OFFLINE" else "ONLINE"}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Fallback in case of exception - assume we might be offline
                val viewModel = viewModelProvider()
                viewModel.setOfflineState(true)
            }
        }
    }
    
    fun release() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
} 