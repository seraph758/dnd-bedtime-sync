package it.silleellie.dndsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import it.silleellie.dndsync.shared.PhoneSignal
import org.apache.commons.lang3.SerializationUtils // 【修改点】换成了官方的序列化库

class DNDSyncListenerService : WearableListenerService() {

    private val handler by lazy {
        Handler(Looper.getMainLooper())
    }

    private var screenReceiver: BroadcastReceiver? = null

    private var bedtimeCycleRunning = false

    private var lastFullscreenLaunch = 0L

    /**
     * 单独的 fullscreen runnable
     */
    private val fullscreenRunnable = Runnable {
        if (bedtimeCycleRunning) {
            showFullScreenUI()
        }
    }

    companion object {

        private const val TAG = "DNDSyncListenerService"

        private const val DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync"

        private const val CHANNEL_ID = "samsung_bedtime_sync"

        private const val NOTIF_ID = 888

        private const val FULLSCREEN_COOLDOWN_MS = 3000L

        @Volatile
        var isSyncingFromPhone = false
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {

        if (!messageEvent.path.equals(DND_SYNC_MESSAGE_PATH, true)) {
            super.onMessageReceived(messageEvent)
            return
        }

        Log.d(TAG, "收到同步消息")

        try {

            // ------------------------------------------------
            // ByteArray -> PhoneSignal（通过 Apache Commons SerializationUtils）
            // ------------------------------------------------
            // 【修改点】直接反序列化字节数组，不走 JSON String，与手机端发送格式对齐
            val phoneSignal =
                SerializationUtils.deserialize<PhoneSignal>(messageEvent.data)

            val nm =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // ------------------------------------------------
            // DND 同步 + 防回环
            // ------------------------------------------------
            phoneSignal.dndState?.let { targetDnd ->

                val current = nm.currentInterruptionFilter

                if (current != targetDnd) {

                    Log.d(TAG, "同步 DND: $current -> $targetDnd")

                    isSyncingFromPhone = true

                    nm.setInterruptionFilter(targetDnd)

                    handler.postDelayed({
                        isSyncingFromPhone = false
                        Log.d(TAG, "DND 防回环锁释放")
                    }, 2000)

                } else {
                    Log.d(TAG, "DND 已是目标状态")
                }
            }

            // ------------------------------------------------
            // Bedtime 同步
            // ------------------------------------------------
            phoneSignal.bedtimeState?.let { target ->

                val samsungValue = if (target > 0) 1 else 0

                val current = Settings.Global.getInt(
                    contentResolver,
                    "setting_bedtime_mode_running_state",
                    0
                )

                if (current == samsungValue) {
                    Log.d(TAG, "Bedtime 未变化")
                    return@let
                }

                Log.d(TAG, "同步 Bedtime: $current -> $samsungValue")

                updateBedtimeSettings(samsungValue)

                if (samsungValue == 1) {
                    startBedtimeCycle()
                    if (phoneSignal.vibratePref) vibrate()
                } else {
                    stopBedtimeCycle()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理同步消息失败", e)
        }
    }

    // ------------------------------------------------
    // Bedtime Settings
    // ------------------------------------------------

    private fun updateBedtimeSettings(value: Int) {
        try {
            Settings.Global.putInt(
                contentResolver,
                "setting_bedtime_mode_running_state",
                value
            )

            Log.d(TAG, "Bedtime -> $value")

        } catch (e: Exception) {
            Log.e(TAG, "更新 Bedtime 失败", e)
        }
    }

    // ------------------------------------------------
    // Bedtime Cycle
    // ------------------------------------------------

    private fun startBedtimeCycle() {

        if (bedtimeCycleRunning) return

        bedtimeCycleRunning = true

        Log.d(TAG, "启动 Bedtime Cycle")

        showFullScreenUI()

        if (screenReceiver == null) {

            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {

                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {

                        handler.removeCallbacks(fullscreenRunnable)
                        handler.postDelayed(fullscreenRunnable, 700)
                    }
                }
            }

            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenReceiver, filter)
            }
        }
    }

    private fun stopBedtimeCycle() {

        bedtimeCycleRunning = false

        handler.removeCallbacks(fullscreenRunnable)

        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIF_ID)
    }

    // ------------------------------------------------
    // Fullscreen UI
    // ------------------------------------------------

    private fun showFullScreenUI() {

        val now = SystemClock.elapsedRealtime()

        if (now - lastFullscreenLaunch < FULLSCREEN_COOLDOWN_MS) {
            Log.d(TAG, "Cooldown skip fullscreen")
            return
        }

        lastFullscreenLaunch = now

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel(nm)

        val intent = Intent().apply {
            component = ComponentName(
                "com.google.android.apps.wearable.settings",
                "com.samsung.android.clockwork.settings.advanced.bedtimemode.StBedtimeModeReservedActivity"
            )

            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("睡眠模式已同步")
            .setContentText("点击查看")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setSilent(true)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        nm.notify(NOTIF_ID, notification)
    }

    private fun createNotificationChannel(nm: NotificationManager) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Bedtime Sync",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    // ------------------------------------------------
    // Vibrate
    // ------------------------------------------------

    private fun vibrate() {

        try {

            val vibrator = getSystemService(Vibrator::class.java) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vibrator.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }

        } catch (e: Exception) {
            Log.e(TAG, "振动失败", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopBedtimeCycle()
        super.onDestroy()
    }
}
