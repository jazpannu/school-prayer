package com.pannulabs.schoolprayer

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Finds the Bluetooth speaker/headphones that audio is currently routed to. No permission needed. */
object BtAudio {
    fun connectedSpeakerName(context: Context): String? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val btTypes = mutableSetOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= 31) {
            btTypes += AudioDeviceInfo.TYPE_BLE_HEADSET
            btTypes += AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in btTypes }
            ?: return null
        return device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth speaker"
    }
}
