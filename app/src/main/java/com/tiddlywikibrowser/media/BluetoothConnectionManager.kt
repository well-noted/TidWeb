package com.tiddlywikibrowser.media

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Manages Bluetooth connection state and triggers media playback pausing when connections are lost
 */
class BluetoothConnectionManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "BluetoothConnectionManager"
        
        @Volatile
        private var INSTANCE: BluetoothConnectionManager? = null
        
        fun getInstance(context: Context): BluetoothConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BluetoothConnectionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
      private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var isMonitoring = false
    private var mediaSessionManager: MediaSessionManager? = null
    private var connectedBluetoothDevices = mutableSetOf<String>()
    private var lastBluetoothCheckTime = 0L
    private var bluetoothCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bluetoothCheckRunnable: Runnable? = null
    
    interface BluetoothConnectionListener {
        fun onBluetoothDeviceConnected(device: BluetoothDevice)
        fun onBluetoothDeviceDisconnected(device: BluetoothDevice)
        fun onAllBluetoothDevicesDisconnected()
    }
    
    private var connectionListener: BluetoothConnectionListener? = null
    
    init {
        initializeBluetooth()
    }
    
    private fun initializeBluetooth() {
        try {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            
            if (bluetoothAdapter == null) {
                Log.w(TAG, "Device does not support Bluetooth")
                return
            }
            
            // Check for connected A2DP devices on initialization
            updateConnectedDevices()
            
            Log.d(TAG, "BluetoothConnectionManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Bluetooth", e)
        }
    }
    
    fun setMediaSessionManager(manager: MediaSessionManager) {
        this.mediaSessionManager = manager
    }
    
    fun setConnectionListener(listener: BluetoothConnectionListener) {
        this.connectionListener = listener
    }
    
    fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Already monitoring Bluetooth connections")
            return
        }
        
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Cannot start monitoring - Bluetooth not available")
            return
        }
        
        try {
            // Check permissions before proceeding
            if (!hasBluetoothPermissions()) {
                Log.w(TAG, "Missing Bluetooth permissions - cannot monitor connections")
                return
            }
            
            bluetoothReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    handleBluetoothIntent(intent)
                }
            }
              val filter = IntentFilter().apply {
                // Basic Bluetooth connection events
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                
                // Audio profile events - these are more reliable for audio devices
                addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
                addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
                
                // Additional audio-related actions
                addAction("android.bluetooth.device.action.ACL_CONNECTED")
                addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
                addAction("android.bluetooth.device.action.ACL_DISCONNECT_REQUESTED")
                
                // HID profile for some devices
                addAction("android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED")
            }
            
            context.registerReceiver(bluetoothReceiver, filter)
            isMonitoring = true
            
            Log.d(TAG, "Started monitoring Bluetooth connections")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Bluetooth monitoring", e)
        }
    }
    
    fun stopMonitoring() {
        if (!isMonitoring) {
            return
        }
        
        try {
            bluetoothReceiver?.let { receiver ->
                context.unregisterReceiver(receiver)
            }
            bluetoothReceiver = null
            isMonitoring = false
            connectedBluetoothDevices.clear()
            
            Log.d(TAG, "Stopped monitoring Bluetooth connections")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Bluetooth monitoring", e)
        }
    }
      private fun handleBluetoothIntent(intent: Intent?) {
        if (intent == null) return
        
        val action = intent.action ?: return
        Log.d(TAG, "Received Bluetooth intent: $action")
        
        try {
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = getBluetoothDeviceFromIntent(intent)
                    Log.d(TAG, "ACL Connected: ${device?.address}")
                    device?.let { handleDeviceConnected(it) }
                }
                
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = getBluetoothDeviceFromIntent(intent)
                    Log.d(TAG, "ACL Disconnected: ${device?.address}")
                    device?.let { handleDeviceDisconnected(it) }
                }
                
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val prevState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR)
                    Log.d(TAG, "Bluetooth state changed: $prevState -> $state")
                    handleBluetoothStateChanged(state)
                }
                
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED" -> {
                    val device = getBluetoothDeviceFromIntent(intent)
                    val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
                    val prevState = intent.getIntExtra("android.bluetooth.profile.extra.PREVIOUS_STATE", -1)
                    Log.d(TAG, "A2DP profile state changed for ${device?.address}: $prevState -> $state")
                    device?.let { handleProfileConnectionStateChanged(it, state) }
                }
                
                "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" -> {
                    val device = getBluetoothDeviceFromIntent(intent)
                    val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
                    val prevState = intent.getIntExtra("android.bluetooth.profile.extra.PREVIOUS_STATE", -1)
                    Log.d(TAG, "Headset profile state changed for ${device?.address}: $prevState -> $state")
                    device?.let { handleProfileConnectionStateChanged(it, state) }
                }
                
                // Add more specific audio-related actions
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    val device = getBluetoothDeviceFromIntent(intent)
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                    Log.d(TAG, "Bluetooth connection state changed for ${device?.address}: $state")
                    device?.let { 
                        if (state == BluetoothAdapter.STATE_DISCONNECTED) {
                            handleDeviceDisconnected(it)
                        } else if (state == BluetoothAdapter.STATE_CONNECTED) {
                            handleDeviceConnected(it)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Bluetooth intent: $action", e)
        }
    }
    
    private fun getBluetoothDeviceFromIntent(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }
    
    private fun handleDeviceConnected(device: BluetoothDevice) {
        val deviceAddress = device.address
        val deviceName = getDeviceName(device)
        
        Log.d(TAG, "Bluetooth device connected: $deviceName ($deviceAddress)")
        
        connectedBluetoothDevices.add(deviceAddress)
        connectionListener?.onBluetoothDeviceConnected(device)
    }
    
    private fun handleDeviceDisconnected(device: BluetoothDevice) {
        val deviceAddress = device.address
        val deviceName = getDeviceName(device)
        
        Log.d(TAG, "Bluetooth device disconnected: $deviceName ($deviceAddress)")
        
        connectedBluetoothDevices.remove(deviceAddress)
        connectionListener?.onBluetoothDeviceDisconnected(device)
        
        // Check if this was an audio device and pause media if no audio devices remain
        if (isAudioDevice(device)) {
            checkForAudioDevicesAndPauseIfNeeded()
        }
    }
    
    private fun handleBluetoothStateChanged(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                Log.d(TAG, "Bluetooth turned off - pausing media")
                connectedBluetoothDevices.clear()
                pauseMediaPlayback()
                connectionListener?.onAllBluetoothDevicesDisconnected()
            }
            BluetoothAdapter.STATE_ON -> {
                Log.d(TAG, "Bluetooth turned on - updating connected devices")
                updateConnectedDevices()
            }
        }
    }
    
    private fun handleProfileConnectionStateChanged(device: BluetoothDevice, state: Int) {
        when (state) {
            2 -> { // Connected
                handleDeviceConnected(device)
            }
            0 -> { // Disconnected
                handleDeviceDisconnected(device)
            }
        }
    }
    
    private fun checkForAudioDevicesAndPauseIfNeeded() {
        // Check if we still have any connected audio devices
        val hasConnectedAudioDevices = hasConnectedAudioDevices()
        
        if (!hasConnectedAudioDevices) {
            Log.d(TAG, "No audio devices connected - pausing media playback")
            pauseMediaPlayback()
            connectionListener?.onAllBluetoothDevicesDisconnected()
        }
    }    private fun pauseMediaPlayback() {
        try {
            // Pause through MediaSessionManager if available
            mediaSessionManager?.let { manager ->
                manager.pauseMediaPlayback()
                Log.d(TAG, "Media playback paused via MediaSessionManager")
                
                // Also try to pause through optimized WebView method if available
                try {
                    val webViewField = manager.javaClass.getDeclaredField("webView")
                    webViewField.isAccessible = true
                    val webView = webViewField.get(manager) as? android.webkit.WebView
                    
                    webView?.let { wv ->
                        // Use the optimized Bluetooth pause method from MediaPlaybackOptimizer
                        MediaPlaybackOptimizer.pauseMediaForBluetoothDisconnect(wv)
                        Log.d(TAG, "Additional Bluetooth-specific pause applied")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Could not apply additional WebView pause (this is normal): ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing media playback", e)
        }
    }
    
    private fun updateConnectedDevices() {
        try {
            if (!hasBluetoothPermissions()) {
                return
            }
            
            bluetoothAdapter?.let { adapter ->
                if (adapter.isEnabled) {
                    val bondedDevices = adapter.bondedDevices
                    connectedBluetoothDevices.clear()
                    
                    bondedDevices?.forEach { device ->
                        // Note: We can't easily check if bonded devices are currently connected
                        // without more complex profile management, so we'll rely on broadcast receivers
                        Log.d(TAG, "Bonded device: ${getDeviceName(device)} (${device.address})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating connected devices", e)
        }
    }
    
    private fun isAudioDevice(device: BluetoothDevice): Boolean {
        return try {
            if (!hasBluetoothPermissions()) {
                return false
            }
            
            // Check device class for audio devices
            val deviceClass = device.bluetoothClass
            deviceClass?.let { btClass ->
                val majorDeviceClass = btClass.majorDeviceClass
                val deviceClass = btClass.deviceClass
                
                // Check for audio/video devices
                majorDeviceClass == 0x0400 || // Audio/Video major class
                deviceClass == 0x040414 || // Headphones
                deviceClass == 0x040418 || // Handsfree
                deviceClass == 0x040420    // Loudspeaker
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if device is audio device", e)
            false
        }
    }
    
    private fun hasConnectedAudioDevices(): Boolean {
        // This is a simplified check - in a real implementation you might want to
        // use BluetoothA2dp or BluetoothHeadset profiles to check actual audio connections
        return connectedBluetoothDevices.isNotEmpty()
    }
    
    private fun getDeviceName(device: BluetoothDevice): String {
        return try {
            if (hasBluetoothPermissions()) {
                device.name ?: "Unknown Device"
            } else {
                "Unknown Device (No Permission)"
            }
        } catch (e: Exception) {
            "Unknown Device"
        }
    }
    
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled ?: false
    }
    
    fun hasConnectedDevices(): Boolean {
        return connectedBluetoothDevices.isNotEmpty()
    }
    
    fun getConnectedDeviceCount(): Int {
        return connectedBluetoothDevices.size
    }
    
    fun restartMonitoring() {
        Log.d(TAG, "Restarting Bluetooth monitoring")
        stopMonitoring()
        startMonitoring()
    }
}
