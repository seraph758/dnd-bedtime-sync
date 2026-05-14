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
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        // ✅ 修复：正确的路径判断（和 OLD 版一致，保证收到手机信号）
        if (messageEvent.path.equals(DND_SYNC_MESSAGE_PATH, ignoreCase = true)) {
            Log.d(TAG, "收到手机同步消息")
            try {
                val data = messageEvent.data
                val phoneSignal = SerializationUtils.deserialize<PhoneSignal>(data)
                Log.d(TAG, "dndStatePhone: " + phoneSignal.dndState)
                Log.d(TAG, "bedtimeStatePhone: " + phoneSignal.bedtimeState)

                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val currentDndState = nm.currentInterruptionFilter
                Log.d(TAG, "currentDndState: $currentDndState")

                // ====================== DND 同步（OLD 文件核心，必须保留） ======================
                if (phoneSignal.dndState != null && phoneSignal.dndState != currentDndState) {
                    changeDndSetting(nm, phoneSignal.dndState!!)
                }

                // ====================== Bedtime 同步（100% 还原 OLD） ======================
                val currentBedtimeState = Settings.Global.getInt(
                    applicationContext.contentResolver, getBedtimeSettingName(), -1
                )

                if (phoneSignal.bedtimeState != null && phoneSignal.bedtimeState != currentBedtimeState) {
                    Log.d(TAG, "bedtimeStatePhone != currentBedtimeState: " + phoneSignal.bedtimeState + " != " + currentBedtimeState)

                    val dndState = if (phoneSignal.bedtimeState == 1) 2 else 1
                    changeDndSetting(nm, dndState)

                    val bedtimeModeSuccess = changeBedtimeSetting(phoneSignal.bedtimeState!!)
                    if (bedtimeModeSuccess) {
                        Log.d(TAG, "Bedtime mode value toggled")
                    } else {
                        Log.d(TAG, "Bedtime mode toggle failed")
                    }

                    if (phoneSignal.powersavePref) {
                        val powerModeSuccess = changePowerModeSetting(phoneSignal.bedtimeState!!)
                        if (powerModeSuccess) {
                            Log.d(TAG, "Power Saver mode toggled")
                        } else {
                            Log.d(TAG, "Power Saver mode toggle failed")
                        }
                    }

                    if (phoneSignal.vibratePref) {
                        vibrate()
                    }
                }

                // ====================== 你新增的全屏提醒功能（完全保留） ======================
                if (phoneSignal.dndState == 5 || phoneSignal.bedtimeState == 1) {
                    startBedtimeCycle()
                } else if (phoneSignal.dndState == 6 || phoneSignal.bedtimeState == 0) {
                    stopBedtimeCycle()
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理消息失败", e)
            }
        } else {
            super.onMessageReceived(messageEvent)
        }
    }

    // ====================== Bedtime 全屏循环提醒功能 ======================
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
            .setSound(null, null)
            .setVibrate(null)
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

    // ====================== 系统设置方法（完全保留 OLD 逻辑） ======================
    private fun changeDndSetting(nm: NotificationManager, newSetting: Int) {
        if (nm.isNotificationPolicyAccessGranted()) {
            nm.setInterruptionFilter(newSetting)
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
        val bedtimeModeSuccess = Settings.Global.putInt(
            applicationContext.contentResolver, settingBedtimeStr, newSetting
        )
        val zenModeSuccess = Settings.Global.putInt(
            applicationContext.contentResolver, "zen_mode", newSetting
        )
        return bedtimeModeSuccess && zenModeSuccess
    }

    private fun changePowerModeSetting(newSetting: Int): Boolean {
        val lowPower = Settings.Global.putInt(
            applicationContext.contentResolver, "low_power", newSetting
        )
        val restrictedDevicePerformance = Settings.Global.putInt(
            applicationContext.contentResolver, "restricted_device_performance", newSetting
        )
        val lowPowerBackDataOff = Settings.Global.putInt(
            applicationContext.contentResolver, "low_power_back_data_off", newSetting
        )
        val smConnectivityDisable = Settings.Secure.putInt(
            applicationContext.contentResolver, "sm_connectivity_disable", newSetting
        )
        return lowPower && restrictedDevicePerformance && lowPowerBackDataOff && smConnectivityDisable
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
