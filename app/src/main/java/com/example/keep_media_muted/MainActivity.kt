package com.example.keep_media_muted  // Update this to match your package name

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private var isServiceEnabled = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        
        // Check if service is already running
        isServiceEnabled = WorkManager.getInstance(this)
            .getWorkInfosByTag("volume_mute_work").get()
            .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        
        updateUI()
        
        toggleButton.setOnClickListener {
            if (isServiceEnabled) {
                stopVolumeMonitoring()
            } else {
                startVolumeMonitoring()
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
}