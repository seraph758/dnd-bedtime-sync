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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class DNDSyncListenerService : WearableListenerService() {

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private var screenReceiver: BroadcastReceiver? = null

    private var bedtimeCycleRunning = false

    private var lastFullscreenLaunch = 0L

    companion object {
        private const val TAG = "DNDSyncListenerService"

        private const val DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync"

        private const val CHANNEL_ID = "samsung_bedtime_sync"

        private const val NOTIF_ID = 888

        /**
         * 防止频繁重复弹 UI 的冷却时间
         */
        private const val FULLSCREEN_COOLDOWN_MS = 3000L

        /**
         * 【关键修改】全局防回环标志：
         * 当手表正在执行来自手机的同步指令时，设为 true。
         * ⚠️ 注意：你手表端负责“监听手表本地 DND 变化并发送给手机”的 Service，
         * 在发送消息前必须检查此变量：
         * if (DNDSyncListenerService.isSyncingFromPhone) return
         */
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
            // 【关键修改】显式指定 UTF_8，防止极端魔改系统乱码
            val json = String(messageEvent.data, Charsets.UTF_8)

            val phoneSignal = Json.decodeFromString<PhoneSignal>(jsonString)

            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // ---------------- DND 同步 ----------------

            phoneSignal.dndState?.let { targetDnd ->

                val current = nm.currentInterruptionFilter

                if (current != targetDnd) {

                    Log.d(TAG, "同步 DND: $current -> $targetDnd")

                    // 开启防回环锁
                    isSyncingFromPhone = true

                    nm.setInterruptionFilter(targetDnd)

                    // 2秒后释放防回环锁
                    handler.postDelayed({
                        isSyncingFromPhone = false
                    }, 2000)

                } else {
                    Log.d(TAG, "DND 已经是目标状态，跳过")
                }
            }

            // ---------------- 睡眠模式同步 ----------------

            phoneSignal.bedtimeState?.let { target ->

                val samsungValue = if (target > 0) 1 else 0

                val current = Settings.Global.getInt(
                    contentResolver,
                    "setting_bedtime_mode_running_state",
                    0
                )

                if (current == samsungValue) {
                    Log.d(TAG, "睡眠模式状态未变化，跳过")
                    return@let
                }

                Log.d(TAG, "同步睡眠模式: $current -> $samsungValue")

                updateBedtimeSettings(samsungValue)

                if (samsungValue == 1) {
                    startBedtimeCycle()

                    if (phoneSignal.vibratePref) {
                        vibrate()
                    }
                } else {
                    stopBedtimeCycle()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理同步消息失败", e)
        }
    }

    /**
     * 三星 Bedtime 状态写入
     */
    private fun updateBedtimeSettings(value: Int) {
        try {
            val r = contentResolver

            Settings.Global.putInt(r, "setting_bedtime_mode_running_state", value)
            Settings.Global.putInt(r, "zen_mode", value)
            Settings.Global.putInt(r, "low_power", value)

            Log.d(TAG, "已更新 Bedtime Settings -> $value")

        } catch (e: Exception) {
            Log.e(TAG, "更新 Bedtime Settings 失败", e)
        }
    }

    // ----------------------------------------------------
    // Bedtime 循环逻辑
    // ----------------------------------------------------

    private fun startBedtimeCycle() {

        if (bedtimeCycleRunning) {
            Log.d(TAG, "Bedtime cycle 已运行")
            return
        }

        bedtimeCycleRunning = true
        Log.d(TAG, "启动 Bedtime Cycle")

        showFullScreenUI()

        if (screenReceiver == null) {

            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        Log.d(TAG, "检测到熄屏，准备重新挂载 Fullscreen UI")
                        handler.postDelayed({
                            if (bedtimeCycleRunning) {
                                showFullScreenUI()
                            }
                        }, 700)
                    }
                }
            }

            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    screenReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenReceiver, filter)
            }
        }
    }

    private fun stopBedtimeCycle() {

        Log.d(TAG, "停止 Bedtime Cycle")

        bedtimeCycleRunning = false

        // 清理所有未执行的延时任务
        handler.removeCallbacksAndMessages(null)

        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
            screenReceiver = null
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }

    // ----------------------------------------------------
    // Fullscreen UI
    // ----------------------------------------------------

    private fun showFullScreenUI() {

        val now = SystemClock.elapsedRealtime()

        if (now - lastFullscreenLaunch < FULLSCREEN_COOLDOWN_MS) {
            Log.d(TAG, "Fullscreen cooldown 中，跳过")
            return
        }

        lastFullscreenLaunch = now
        Log.d(TAG, "显示 Fullscreen UI")

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(nm)

        val intent = Intent().apply {
            component = ComponentName(
                "com.google.android.apps.wearable.settings",
                "com.samsung.android.clockwork.settings.advanced.bedtimemode.StBedtimeModeReservedActivity"
            )
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION or
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
            .setContentText("点击查看三星睡眠模式")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        nm.notify(NOTIF_ID, notification)
    }

    private fun createNotificationChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val old = nm.getNotificationChannel(CHANNEL_ID)
            if (old != null) return

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Samsung Bedtime Sync",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Bedtime synchronization"
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }

    // ----------------------------------------------------
    // 振动
    // ----------------------------------------------------

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

