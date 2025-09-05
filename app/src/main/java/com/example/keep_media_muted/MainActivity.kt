package com.example.keep_media_muted  // Update this to match your package name

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var audioStatusText: TextView
    private var isServiceEnabled = false
    private lateinit var audioManager: AudioManager
    
    // Notification permission launcher for Android 13+
    private val notificationPermissionLauncher = 
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Notifications disabled - you won't be notified when volume is muted", 
                    Toast.LENGTH_LONG).show()
            }
        }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        audioStatusText = findViewById(R.id.audioStatusText)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Check and request notification permission for Android 13+
        checkNotificationPermission()
        
        // Check if service is already running
        isServiceEnabled = WorkManager.getInstance(this)
            .getWorkInfosByTag("volume_mute_work").get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        
        updateUI()
        updateAudioStatus()
        
        toggleButton.setOnClickListener {
            if (isServiceEnabled) {
                stopVolumeMonitoring()
            } else {
                startVolumeMonitoring()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateAudioStatus()
    }
    
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Show explanation before requesting
                    Toast.makeText(this, 
                        "Notification permission needed to inform you when volume is muted", 
                        Toast.LENGTH_LONG).show()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    // Request permission
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
    
    private fun startVolumeMonitoring() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()
        
        val volumeMuteRequest = PeriodicWorkRequestBuilder<VolumeMuteWorker>(
            5, TimeUnit.MINUTES,
            3, TimeUnit.MINUTES // Flex interval
        )
            .addTag("volume_mute_work")
            .setConstraints(constraints)
            .build()
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "volume_mute_work",
            ExistingPeriodicWorkPolicy.REPLACE,
            volumeMuteRequest
        )
        
        isServiceEnabled = true
        updateUI()
        Toast.makeText(this, "Auto-mute service started", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopVolumeMonitoring() {
        WorkManager.getInstance(this).cancelAllWorkByTag("volume_mute_work")
        isServiceEnabled = false
        updateUI()
        Toast.makeText(this, "Auto-mute service stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateUI() {
        if (isServiceEnabled) {
            statusText.text = "Status: Active\nMedia volume will be muted every 5 minutes"
            toggleButton.text = "Stop Service"
        } else {
            statusText.text = "Status: Inactive\nPress button to start monitoring"
            toggleButton.text = "Start Service"
        }
    }
    
    private fun updateAudioStatus() {
        val isConnected = isAudioDeviceConnected()
        if (isConnected) {
            audioStatusText.text = "🎧 Audio device connected - Will NOT mute"
            audioStatusText.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            audioStatusText.text = "🔊 Using speaker - Will mute when active"
            audioStatusText.setTextColor(getColor(android.R.color.holo_orange_dark))
        }
    }
    
    private fun isAudioDeviceConnected(): Boolean {
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