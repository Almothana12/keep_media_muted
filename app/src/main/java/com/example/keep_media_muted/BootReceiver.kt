package com.example.keep_media_muted

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restarts the watch service after reboot / app update if the user had it on.
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED are exemptions from the
 * background foreground-service-start restrictions.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!VolumeWatchService.isEnabled(context)) return
        val serviceIntent = Intent(context, VolumeWatchService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
