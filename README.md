# SmartChips Plugin Development Guide

Welcome to the SmartChips plugin development guide! This document explains how you can create your own external plugins for ClockDesk, allowing users to display custom data chips on their home screen.

# How It Works

SmartChips plugins are separate Android applications that ClockDesk discovers and communicates with using BroadcastReceivers. This architecture allows any developer to create and distribute a plugin as a standard APK.

The communication flow is as follows:

1. Discovery (ACTION_QUERY_SMART_CHIPS): When ClockDesk starts, it queries the system for any apps that can receive this broadcast. This is how it finds your plugin.

2. Metadata: ClockDesk reads the meta-data from your receiver in the AndroidManifest.xml to find your plugin_info.xml file. This file provides the plugin's name (e.g., "Weather Plugin") to display in ClockDesk's settings.

3. User Enablement: The user must go into ClockDesk's settings and enable your plugin.

4. Data Request (ACTION_REQUEST_CHIP_DATA): When ClockDesk needs data from your (now enabled) plugin, it sends this broadcast. This intent may include useful data, such as the user's last known Location.

5. Data Response (ACTION_UPDATE_DATA): Your plugin performs its work (e.g., makes an API call) in the background and then sends a broadcast back to ClockDesk with the data to be displayed.

### Step 1: Create Your Android Project

Start by creating a new, standard Android application project in Android Studio. This will be your plugin.

You will need to implement three core files:

1. AndroidManifest.xml (to declare the receiver)

2. res/xml/plugin_info.xml (to provide plugin metadata)

3. YourChipReceiver.kt (to handle data requests)

### Step 2: Configure the AndroidManifest.xml

Your plugin's AndroidManifest.xml must declare the BroadcastReceiver that ClockDesk will communicate with. It must be exported="true" and contain the correct intent filters and meta-data tag.

File: app/src/main/AndroidManifest.xml

<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="[http://schemas.android.com/apk/res/android](http://schemas.android.com/apk/res/android)"
    package="com.example.yourplugin">

    <!-- Request any permissions your plugin needs. -->
    <!-- For example, an internet permission for API calls. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <!--
    If you plan to use the Location object from ClockDesk
    to make your own location-aware calls, it's good practice
    to declare location permissions as well.
    -->
    <!-- <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" /> -->

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.YourPlugin">

        <!--
        Your plugin can have its own Activity, for example,
        to let the user log in or configure plugin-specific settings.
        This is optional.
        -->
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!--
        ===================================================================
        THIS IS THE MOST IMPORTANT PART
        ===================================================================
        -->
        <receiver
            android:name=".YourChipReceiver"
            android:exported="true">

            <!--
            This filter allows ClockDesk to discover your plugin
            when it scans for all available SmartChips.
            -->
            <intent-filter>
                <action android:name="com.nxd1frnt.clockdesk2.ACTION_QUERY_SMART_CHIPS" />
            </intent-filter>

            <!--
            This filter allows your plugin to receive requests
            for data from ClockDesk.
            -->
            <intent-filter>
                <action android:name="com.nxd1frnt.clockdesk2.ACTION_REQUEST_CHIP_DATA" />
            </intent-filter>

            <!--
            This meta-data tag is CRITICAL. It tells ClockDesk
            where to find your plugin_info.xml file, which contains
            the name of your plugin for the settings screen.
            -->
            <meta-data
                android:name="com.nxd1frnt.clockdesk2.smartchip.PLUGIN_RECEIVER"
                android:resource="@xml/plugin_info" />
        </receiver>

    </application>
</manifest>


### Step 3: Create the Plugin Info File

This small XML file provides the metadata ClockDesk needs to display your plugin in its settings list, allowing the user to toggle it on or off.

Create a new resource directory res/xml/ and add the following file:

File: app/src/main/res/xml/plugin_info.xml

<?xml version="1.0" encoding="utf-8"?>
<!--
This file provides metadata about your plugin.
ClockDesk will read this to show your plugin in its settings.
-->
    <smart-chip-plugin
        xmlns:android="[http://schemas.android.com/apk/res/android](http://schemas.android.com/apk/res/android)"
    
        <!--
        [REQUIRED]
        This is the key ClockDesk will use to store whether your
        plugin is enabled or disabled in its own SharedPreferences.
        Make this unique to your plugin.
        -->
        preferenceKey="show_my_unique_plugin_key"
    
        <!--
        [REQUIRED]
        This is the human-readable name that will be shown
        in the ClockDesk settings screen for users to toggle.
        -->
        displayName="My Custom Plugin"
        
        <!--
        [REQUIRED]
        This is the priority for your plugin, it will allow to sort the chips by importance.
        -->
        priority="5"/>


### Step 4: Implement the BroadcastReceiver

This is the core logic of your plugin. This class is responsible for receiving the data request, doing the work (e.g., fetching from an API), and sending the result back to ClockDesk.

File: app/src/main/java/com/example/yourplugin/YourChipReceiver.kt

    package com.example.yourplugin
    
    import android.content.BroadcastReceiver
    import android.content.Context
    import android.content.Intent
    import android.location.Location
    import android.util.Log
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.GlobalScope
    import kotlinx.coroutines.launch
    
    class YourChipReceiver : BroadcastReceiver() {
    
        companion object {
            // --- Actions defined by ClockDesk API ---
            
            // You receive this action when ClockDesk wants data
            const val ACTION_REQUEST_DATA = "com.nxd1frnt.clockdesk2.ACTION_REQUEST_CHIP_DATA"
            
            // You send this action to send data back
            const val ACTION_UPDATE_DATA = "com.nxd1frnt.clockdesk2.ACTION_UPDATE_DATA"
            
            // ClockDesk's package name for sending a secure, direct intent
            const val CLOCKDESK_PACKAGE = "com.nxd1frnt.clockdesk2"
        }
    
        override fun onReceive(context: Context, intent: Intent) {
            
            // Only act on requests for data
            if (intent.action == ACTION_REQUEST_DATA) {
                Log.d("YourChipReceiver", "Received data request from ClockDesk")
    
                // --- 1. Get Data from ClockDesk (Optional) ---
                
                // ClockDesk may provide the last known location.
                // This can be null if location is disabled or not found.
                val location = intent.getParcelableExtra<Location>("location")
                if (location != null) {
                    Log.d("YourChipReceiver", "Received location: ${location.latitude}, ${location.longitude}")
                } else {
                    Log.w("YourChipReceiver", "Location data was null")
                }
    
                // --- 2. Go Async ---
                
                // A BroadcastReceiver's onReceive() has a 10-second limit
                // on the main thread. For any network or disk I/O, you MUST
                // use goAsync() to perform work on a background thread.
                val pendingResult = goAsync()
    
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        // --- 3. Do Your Work ---
                        // This is where you fetch your data (e.g., from a
                        // weather API, a stock API, your own server, etc.)
                        //
                        // EXAMPLE:
                        // val myData = MyApiClient.fetchData(location.latitude, location.longitude)
                        // val myText = myData.mainText
                        // val myIconName = myData.iconName
                        
                        // For this example, we'll just simulate a network call
                        Thread.sleep(1000) // Simulate network delay
                        
                        val myDataText = "Plugin Data!" // This is the text to display
                        val myIconName = "ic_my_plugin_icon" // The name of your drawable resource
                        
    
                        // --- 4. Send Data Back to ClockDesk ---
                        
                        // This icon *must* exist in your plugin's `res/drawable/` folder.
                        // For example: `app/src/main/res/drawable/ic_my_plugin_icon.xml`
                        // ClockDesk will load this resource by name from your package.
                        
                        val responseIntent = Intent(ACTION_UPDATE_DATA).apply {
                            // IMPORTANT: Set the package to ClockDesk's package name.
                            // This makes it a direct intent, ensuring only ClockDesk
                            // receives it. This is more secure and efficient.
                            setPackage(CLOCKDESK_PACKAGE)
    
                            // --- Add the required data as extras ---
                            
                            // [REQUIRED] Your plugin's package name.
                            putExtra("plugin_package_name", context.packageName)
                            
                            // [REQUIRED] The text to display in the chip.
                            putExtra("chip_text", myDataText)
                            
                            // [REQUIRED] The name of the drawable icon resource
                            // located inside *your* plugin's APK.
                            putExtra("chip_icon_name", myIconName)
                        }
                        
                        context.sendBroadcast(responseIntent)
                        Log.d("YourChipReceiver", "Sent data back to ClockDesk")
    
                    } catch (e: Exception) {
                        // Handle any errors (e.g., network timeout, API error)
                        Log.e("YourChipReceiver", "Error fetching plugin data", e)
                        // You could optionally send a broadcast with error info,
                        // or just fail silently and ClockDesk will time out.
                    } finally {
                        // --- 5. Finish Async Operation ---
                        
                        // CRITICAL: You must call finish() on the PendingResult
                        // when your background work is done.
                        pendingResult.finish()
                    }
                }
            }
        }
    }


### Step 5: Testing Your Plugin

1. Add an Icon: Make sure you add a drawable resource to app/src/main/res/drawable/ with the exact name you specified (e.g., ic_my_plugin_icon.xml).

2. Install Both Apps: Install both your main ClockDesk app and your new plugin app (as separate APKs) on the same device or emulator.

3. Enable the Plugin:

    - Open ClockDesk.

    - Go to the settings screen.

    - You should see your plugin's displayName (e.g., "My Custom Plugin") in the list.

    - Enable the toggle for your plugin.

4. Trigger a Refresh: The plugin should now be part of the regular refresh cycle. You can force a refresh (if your app has such a feature) or wait for the next scheduled one.

5. Check Logs: Use Logcat in Android Studio to monitor your plugin's logs. Filter by your BroadcastReceiver's tag (e.g., YourChipReceiver) to see if it's receiving requests and sending data.
