package it.silleellie.dndsync

import android.app.Notification
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
            val nm = getSystemService<NotificationManager>()!!

            // ================= 核心修复：兼容手机端的 5 和 6 =================
            val rawDndState = phoneSignal.dndState
            var targetDnd: Int? = rawDndState
            var targetBedtime = phoneSignal.bedtimeState

            // 拦截手机端发来的魔法数字 5(开启睡眠) 和 6(关闭睡眠)
            if (rawDndState == 5) {
                targetBedtime = 1
                targetDnd = null // 置空，防止把它当成普通勿扰模式去处理
            } else if (rawDndState == 6) {
                targetBedtime = 0
                targetDnd = null 
            }
            // ===============================================================

            // 1. DND 处理 (只处理合法的 1~4)
            targetDnd?.let { target ->
                if (isSyncingFromPhone) return@let
                val current = nm.currentInterruptionFilter
                if (target != current && target in 1..4) {
                    isSyncingFromPhone = true
                    changeDndSetting(nm, target)
                    handler.postDelayed({ isSyncingFromPhone = false }, 2500)
                }
            }

            // 2. Bedtime 处理
            targetBedtime?.let { target ->
                val current = Settings.Global.getInt(contentResolver, getBedtimeSettingName(), -1)
                if (target != current) {
                    // 当睡眠模式开启时，同时开启勿扰(2代表PRIORITY)，关闭时关闭勿扰(1代表ALL)
                    val dndState = if (target == 1) 2 else 1
                    changeDndSetting(nm, dndState)
                    
                    changeBedtimeSetting(target)

                    // 加 try-catch 防御一下：以防旧版 PhoneSignal 类中没有这俩字段导致崩溃
                    try {
                        if (phoneSignal.powersavePref) changePowerModeSetting(target)
                        if (phoneSignal.vibratePref) vibrate()
                    } catch (e: Exception) {
                        Log.d(TAG, "旧版对象缺少扩展字段，跳过省电/振动设置")
                    }

                    if (target == 1) startBedtimeCycle() else stopBedtimeCycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理消息失败", e)
        }
    }


    // ====================== Bedtime 全屏循环提醒 ======================
    private fun startBedtimeCycle() {
        if (bedtimeCycleRunning) return
        bedtimeCycleRunning = true
        Log.d(TAG, "启动 Bedtime 全屏循环")
        showFullScreenUI()
        registerScreenReceiver()
    }

    private fun stopBedtimeCycle() {
        bedtimeCycleRunning = false
        handler.removeCallbacks(fullscreenRunnable)
        unregisterScreenReceiver()
        getSystemService<NotificationManager>()?.cancel(NOTIF_ID)
        Log.d(TAG, "停止 Bedtime 全屏循环")
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> handler.post(fullscreenRunnable)
                    Intent.ACTION_SCREEN_OFF -> {
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
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        screenReceiver = null
    }

    private val fullscreenRunnable = Runnable {
        if (bedtimeCycleRunning) showFullScreenUI()
    }

    private fun showFullScreenUI() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFullscreenLaunch < FULLSCREEN_COOLDOWN_MS) return

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

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("睡眠模式已同步")
            .setContentText("按返回键退出 • 抬手会再次显示")
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setSound(null, null)           // 静音
            .setVibrate(null)               // 无振动
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(false)
            .build()

        nm.notify(NOTIF_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService<NotificationManager>()!!
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID, "Bedtime Sync", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    // ====================== 原有方法 ======================
    private fun changeDndSetting(nm: NotificationManager, newSetting: Int) {
        if (nm.isNotificationPolicyAccessGranted()) {
            nm.setInterruptionFilter(newSetting)
            Log.d(TAG, "DND set to $newSetting")
        }
    }

    private fun getBedtimeSettingName(): String =
        if (Build.MANUFACTURER == "samsung") "setting_bedtime_mode_running_state" else "bedtime_mode"

    private fun changeBedtimeSetting(newSetting: Int) {
        val name = getBedtimeSettingName()
        Settings.Global.putInt(contentResolver, name, newSetting)
        Settings.Global.putInt(contentResolver, "zen_mode", newSetting)
    }

    private fun changePowerModeSetting(newSetting: Int) {
        Settings.Global.putInt(contentResolver, "low_power", newSetting)
        Settings.Global.putInt(contentResolver, "restricted_device_performance", newSetting)
        Settings.Global.putInt(contentResolver, "low_power_back_data_off", newSetting)
        Settings.Secure.putInt(contentResolver, "sm_connectivity_disable", newSetting)
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
