# Bluetooth Media Pause Feature

This document describes the new Bluetooth media pause functionality added to TidWeb.

## Overview

The app now automatically pauses media playback when Bluetooth devices are disconnected. This is particularly useful for audio playback scenarios where users expect media to pause when their Bluetooth headphones or speakers lose connection.

## How It Works

### Components

1. **BluetoothConnectionManager** - Monitors Bluetooth connection state
2. **MediaSessionManager** - Integrates with Bluetooth manager for media control
3. **MediaPlaybackOptimizer** - Provides optimized pause methods for WebView media

### Implementation Details

1. **Bluetooth Monitoring**: The `BluetoothConnectionManager` listens for Bluetooth connection/disconnection events using Android's `BroadcastReceiver`.

2. **Audio Device Detection**: The system specifically monitors for audio-capable devices (headphones, speakers, etc.) rather than all Bluetooth devices.

3. **Media Pause Logic**: When the last audio device disconnects, the system:
   - Calls the existing media pause functionality through `MediaSessionManager`
   - Also applies a JavaScript-based pause through the WebView for additional reliability
   - Updates any UI controls to reflect the paused state

### Permissions

The following permissions are required and have been added to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:maxSdkVersion="30" />
```

### Bluetooth Device Types Detected

The system monitors these types of Bluetooth events:
- `BluetoothDevice.ACTION_ACL_CONNECTED` - General device connection
- `BluetoothDevice.ACTION_ACL_DISCONNECTED` - General device disconnection
- `BluetoothAdapter.ACTION_STATE_CHANGED` - Bluetooth on/off state
- A2DP profile connections (audio devices)
- Headset profile connections

## Usage

The feature works automatically once the app starts. No user interaction is required.

### For Developers

To disable or modify the Bluetooth pause behavior:

1. **Disable completely**: Comment out the `initializeBluetoothManager()` call in `MediaSessionManager.init()`

2. **Modify pause behavior**: Edit the `pauseMediaPlayback()` method in `BluetoothConnectionManager`

3. **Change device detection**: Modify the `isAudioDevice()` method to change which devices trigger pause

### Debugging

Enable debug logging by setting log level to DEBUG. Look for these log tags:
- `BluetoothConnectionManager` - Bluetooth connection events
- `MediaSessionManager` - Media session management
- `MediaOptimizer` - WebView media optimization

## Error Handling

The system is designed to fail gracefully:
- Missing Bluetooth permissions will log warnings but won't crash
- Devices without Bluetooth support will skip monitoring
- JavaScript execution errors in WebView are caught and logged
- Media pause failures are logged but don't prevent other app functionality

## Testing

To test the functionality:

1. Connect Bluetooth headphones/speakers
2. Start playing audio in TidWeb
3. Turn off or disconnect the Bluetooth device
4. Verify that audio pauses automatically

## Future Enhancements

Potential improvements:
- Reconnection resume functionality
- User preference to enable/disable auto-pause
- Different behavior for different types of audio content
- Integration with Android's media session for system-wide controls
