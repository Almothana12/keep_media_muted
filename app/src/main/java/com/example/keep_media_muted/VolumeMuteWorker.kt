package com.example.keep_media_muted

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters

class VolumeMuteWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    
    override fun doWork(): Result {
        val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Check if headphones or Bluetooth audio is connected
        if (isAudioDeviceConnected(audioManager)) {
            // Skip muting if headphones/Bluetooth connected
            return Result.success()
        }
        
        // Get current media volume
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        // If volume is greater than 0, mute it
        if (currentVolume > 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            
            // Optional: You can add a notification here to inform the user
            // that the volume was muted
        }
        
        return Result.success()
    }
    
    private fun isAudioDeviceConnected(audioManager: AudioManager): Boolean {
        // For API 23+ (Android 6.0+), use the newer API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> return true
                }
            }
        } else {
            // Fallback for older devices
            @Suppress("DEPRECATION")
            return audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn || 
                   audioManager.isBluetoothScoOn
        }
        
        return false
    }
}