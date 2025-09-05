package com.example.keep_media_muted

import android.content.Context
import android.media.AudioManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class VolumeMuteWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    
    override fun doWork(): Result {
        val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
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
}