package com.example.keep_media_muted  // Update this to match your package name

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var audioStatusText: TextView
    private lateinit var audioManager: AudioManager

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

        checkNotificationPermission()
        checkBatteryOptimization()

        toggleButton.setOnClickListener {
            if (VolumeWatchService.isEnabled(this)) {
                stopVolumeMonitoring()
            } else {
                startVolumeMonitoring()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
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
                    Toast.makeText(this,
                        "Notification permission needed to inform you when volume is muted",
                        Toast.LENGTH_LONG).show()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun checkBatteryOptimization() {
        if (isIgnoringBatteryOptimizations()) return
        Toast.makeText(this,
            "Keep Media Muted is battery-optimized - open settings and choose Unrestricted so it can run in the background",
            Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun startVolumeMonitoring() {
        val intent = Intent(this, VolumeWatchService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateUI()
        Toast.makeText(this, "Auto-mute service started", Toast.LENGTH_SHORT).show()
    }

    private fun stopVolumeMonitoring() {
        getSharedPreferences("keep_media_muted_prefs", Context.MODE_PRIVATE)
            .edit { putBoolean("watch_enabled", false) }
        stopService(Intent(this, VolumeWatchService::class.java))
        updateUI()
        Toast.makeText(this, "Auto-mute service stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (VolumeWatchService.isEnabled(this)) {
            statusText.text = "Status: Active\nMutes ~2 minutes after you stop listening (never while audio is playing)"
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
        return false
    }
}
