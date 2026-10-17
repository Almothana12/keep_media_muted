package com.example.keep_media_muted

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Foreground service that keeps the media (music) stream muted while no
 * headphones/Bluetooth audio device is connected.
 *
 * Policy (matching the real-world use case):
 *   - Mute ONLY after GRACE_MS of true silence: nothing playing AND no volume
 *     interaction from the user.
 *   - While a video/music/voice message is actively playing, NEVER mute —
 *     playback state is checked on every pass.
 *   - So: watch a video in the car at max volume -> stop -> it gets muted
 *     shortly after you stop listening, before you walk into the office.
 *
 * Why a foreground service and not a WorkManager worker:
 * Android 17 "background audio hardening" silently ignores volume-change APIs
 * (setStreamVolume, adjustStreamVolume, ...) for apps that are in the
 * background and NOT running a foreground service (SHORT_SERVICE doesn't
 * count). The call fails silently — no exception — which is exactly the
 * "notification says muted, but volume is untouched" symptom.
 * See: https://developer.android.com/about/versions/17/changes/bg-audio
 */
class VolumeWatchService : Service() {

    companion object {
        private const val CHANNEL_ID = "volume_mute_channel"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "keep_media_muted_prefs"
        private const val KEY_ENABLED = "watch_enabled"

        /** How often we check playback state (cheap query). */
        private const val POLL_INTERVAL_MS = 10_000L

        /** Continuous silence required before muting. */
        const val GRACE_MS = 2 * 60_000L

        // Intent action constant for volume changes. Kept as the literal string:
        // AudioManager.VOLUME_CHANGED_ACTION is no longer resolvable from the
        // public SDK stubs on newer compileSdk, but the broadcast itself is
        // protected and sent by the system, so this is safe.
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

        /** Matches "state:started" style substrings in playback config toString(). */
        private val PLAYBACK_STATE_REGEX = Regex("state:(\\w+)")

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var hardeningWarned = false

    /** Last moment we saw audio playing OR the user changed the volume. */
    private var lastActiveEpochMs = System.currentTimeMillis()

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Any volume interaction means "user is actively using audio":
            // restart the grace period.
            lastActiveEpochMs = System.currentTimeMillis()
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            enforceMuted()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setEnabled(this, true)
        lastActiveEpochMs = System.currentTimeMillis()

        val serviceInfoType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Watching audio — mutes ${GRACE_MS / 60000} min after you stop listening"),
            serviceInfoType
        )

        val filter = IntentFilter(ACTION_VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(volumeReceiver, filter)
        }
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        try {
            unregisterReceiver(volumeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enforceMuted() {
        // Headphones / Bluetooth / car audio connected: never touch volume.
        if (isAudioDeviceConnected(audioManager)) return

        // Something is playing: the user is listening. Keep the grace window open.
        if (isAnyAudioPlaying()) {
            lastActiveEpochMs = System.currentTimeMillis()
            return
        }

        // Mute only after enough continuous silence.
        if (System.currentTimeMillis() - lastActiveEpochMs < GRACE_MS) return

        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVolume <= 0) return

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        showMutedNotification(currentVolume)

        // Android 17 audio hardening makes setStreamVolume a silent no-op if this
        // service loses its foreground state. Detect and tell the user.
        handler.postDelayed({
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0 && !hardeningWarned) {
                hardeningWarned = true
                showHardeningWarning()
            }
        }, 400)
    }

    /**
     * True if any app on the device currently has audio actively playing.
     *
     * Note: AudioPlaybackConfiguration.getPlayerState() is @SystemApi/@hide and
     * throws for normal apps. The public toString() has included
     * "state:<idle|paused|started|stopped|released|unknown>" (AOSP format, stable
     * for years), so we parse that. This covers ALL players — including
     * in-feed videos in Twitter/Instagram/WhatsApp that register no media session.
     */
    private fun isAnyAudioPlaying(): Boolean {
        return try {
            audioManager.activePlaybackConfigurations.any { cfg ->
                PLAYBACK_STATE_REGEX.find(cfg.toString())?.groupValues?.get(1) == "started"
            }
        } catch (_: Exception) {
            // If we can't inspect playback, be safe: treat as playing (never auto-mute).
            true
        }
    }

    private fun isAudioDeviceConnected(audioManager: AudioManager): Boolean {
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

    private fun buildNotification(text: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun showMutedNotification(previousVolume: Int) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = (previousVolume * 100) / maxVolume.coerceAtLeast(1)
        notify(
            "Media Volume Muted",
            "volume was at $volumePercent% and has been muted",
            autoCancel = true
        )
    }

    private fun showHardeningWarning() {
        notify(
            "Auto-mute blocked by system",
            "Android blocked the volume change. Tap the app icon, then press Start Service again.",
            autoCancel = true
        )
    }

    private fun notify(title: String, text: String, autoCancel: Boolean) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(autoCancel)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID + 10, n)
        } catch (_: SecurityException) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Volume Mute Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when media volume is automatically muted"
            }
            val nm: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
