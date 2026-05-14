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
import androidx.core.content.getSystemService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import it.silleellie.dndsync.shared.PhoneSignal
import org.apache.commons.lang3.SerializationUtils

class DNDSyncListenerService : WearableListenerService() {

    private val handler = Handler(Looper.getMainLooper())

    private var screenReceiver: BroadcastReceiver? = null
    private var bedtimeCycleRunning = false
    private var lastFullscreenLaunch = 0L

    companion object {
        private const val TAG = "DNDSyncListenerService"
        private const val DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync"
        private const val CHANNEL_ID = "samsung_bedtime_sync"
        private const val NOTIF_ID = 888
        private const val FULLSCREEN_COOLDOWN_MS = 1500L

        @Volatile
        var isSyncingFromPhone = false
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (!messageEvent.path.equals(DND_SYNC_MESSAGE_PATH, ignoreCase = true)) {
            super.onMessageReceived(messageEvent)
            return
        }

        Log.d(TAG, "收到手机同步消息")

        try {
            val phoneSignal = SerializationUtils.deserialize<PhoneSignal>(messageEvent.data)

            val mNotificationManager = getSystemService<NotificationManager>()!!

            // DND 处理
            phoneSignal.dndState?.let { targetDnd ->
                if (isSyncingFromPhone) {
                    Log.d(TAG, "DND 正在同步中，忽略回环")
                    return@let
                }

                val currentDndState = mNotificationManager.currentInterruptionFilter
                if (targetDnd != currentDndState) {
                    Log.d(TAG, "同步 DND: $currentDndState -> $targetDnd")
                    isSyncingFromPhone = true
                    changeDndSetting(mNotificationManager, targetDnd)

                    handler.postDelayed({ isSyncingFromPhone = false }, 2500)
                }
            }

            // Bedtime 处理
            phoneSignal.bedtimeState?.let { targetBedtime ->
                val currentBedtimeState = Settings.Global.getInt(
                    contentResolver, getBedtimeSettingName(), -1
                )

                if (targetBedtime != currentBedtimeState) {
                    Log.d(TAG, "同步 Bedtime: $currentBedtimeState -> $targetBedtime")

                    val dndState = if (targetBedtime == 1) 2 else 1
                    changeDndSetting(mNotificationManager, dndState)

                    val bedtimeSuccess = changeBedtimeSetting(targetBedtime)
                    if (bedtimeSuccess) Log.d(TAG, "Bedtime mode toggled successfully")

                    if (phoneSignal.powersavePref) {
                        changePowerModeSetting(targetBedtime)
                    }

                    if (phoneSignal.vibratePref) vibrate()

                    if (targetBedtime == 1) {
                        startBedtimeCycle()
                    } else {
                        stopBedtimeCycle()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理同步消息失败", e)
        }
    }

    // ====================== Bedtime 全屏循环 ======================
    private fun startBedtimeCycle() {
        if (bedtimeCycleRunning) return
        bedtimeCycleRunning = true
        Log.d(TAG, "启动 Bedtime 全屏循环模式")
        showFullScreenUI()
        registerScreenReceiver()
    }

    private fun stopBedtimeCycle() {
        bedtimeCycleRunning = false
        handler.removeCallbacks(fullscreenRunnable)
        unregisterScreenReceiver()
        getSystemService<NotificationManager>()?.cancel(NOTIF_ID)
        Log.d(TAG, "停止 Bedtime 全屏循环模式")
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d(TAG, "屏幕点亮 → 显示 Bedtime 全屏")
                        handler.post(fullscreenRunnable)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "屏幕熄灭 → 准备下次点亮")
                        handler.removeCallbacks(fullscreenRunnable)
                        handler.postDelayed(fullscreenRunnable, 800)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
    }

    private val fullscreenRunnable = Runnable {
        if (bedtimeCycleRunning) showFullScreenUI()
    }

    private fun showFullScreenUI() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFullscreenLaunch < FULLSCREEN_COOLDOWN_MS) {
            Log.d(TAG, "Fullscreen 冷却中，跳过")
            return
        }

        lastFullscreenLaunch = now
        createNotificationChannel()

        val nm = getSystemService<NotificationManager>()!!

        val intent = Intent().apply {
            component = ComponentName(
                "com.google.android.apps.wearable.settings",
                "com.samsung.android.clockwork.settings.advanced.bedtimemode.StBedtimeModeReservedActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("睡眠模式已同步")
            .setContentText("按返回键退出 • 熄屏后再抬手将再次显示")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setSilent(true)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(false)
            .build()

        nm.notify(NOTIF_ID, notification)
        Log.d(TAG, "全屏 Bedtime 通知已触发")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService<NotificationManager>()!!
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bedtime Sync",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    // ====================== 原有功能方法 ======================
    private fun changeDndSetting(mNotificationManager: NotificationManager, newSetting: Int) {
        if (mNotificationManager.isNotificationPolicyAccessGranted()) {
            mNotificationManager.setInterruptionFilter(newSetting)
            Log.d(TAG, "DND set to $newSetting")
        } else {
            Log.d(TAG, "attempting to set DND but access not granted")
        }
    }

    private fun getBedtimeSettingName(): String {
        return if (Build.MANUFACTURER == "samsung") "setting_bedtime_mode_running_state" else "bedtime_mode"
    }

    private fun changeBedtimeSetting(newSetting: Int): Boolean {
        val settingBedtimeStr = getBedtimeSettingName()
        val bedtimeModeSuccess = Settings.Global.putInt(contentResolver, settingBedtimeStr, newSetting)
        val zenModeSuccess = Settings.Global.putInt(contentResolver, "zen_mode", newSetting)
        return bedtimeModeSuccess && zenModeSuccess
    }

    private fun changePowerModeSetting(newSetting: Int): Boolean {
        val lowPower = Settings.Global.putInt(contentResolver, "low_power", newSetting)
        val restrictedDevicePerformance = Settings.Global.putInt(contentResolver, "restricted_device_performance", newSetting)
        val lowPowerBackDataOff = Settings.Global.putInt(contentResolver, "low_power_back_data_off", newSetting)
        val smConnectivityDisable = Settings.Secure.putInt(contentResolver, "sm_connectivity_disable", newSetting)

        return lowPower && restrictedDevicePerformance
    }

    private fun vibrate() {
        val vibrator = getSystemService<Vibrator>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(500)
        }
    }

    override fun onDestroy() {
        stopBedtimeCycle()
        super.onDestroy()
    }
}
