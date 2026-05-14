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
        if (messageEvent.path.equals(DND_SYNC_MESSAGE_PATH, ignoreCase = true)) {
            Log.d(TAG, "收到手机同步消息")
            try {
                isSyncingFromPhone = true
                val data = messageEvent.data
                val phoneSignal = SerializationUtils.deserialize<PhoneSignal>(data)
                
                // 1. 提取信号并初始化局部控制变量
                var dndState = phoneSignal.dndState
                var bedtimeState = phoneSignal.bedtimeState

                // 🔥 核心修复：拦截并转换手机发来的非标准勿扰值 5 和 6
                if (dndState == 5) {
                    bedtimeState = 1
                    dndState = null 
                } else if (dndState == 6) {
                    bedtimeState = 0
                    dndState = null 
                }

                Log.d(TAG, "解析转换后：dndStatePhone: $dndState, bedtimeStatePhone: $bedtimeState")

                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val currentDndState = nm.currentInterruptionFilter

                // ====================== 1. 原生 DND 同步（仅处理 1~4 的合法系统值） ======================
                if (dndState != null && dndState != currentDndState && dndState in 1..4) {
                    changeDndSetting(nm, dndState)
                }

                // ====================== 2. 睡眠模式与外延配置同步 ======================
                val currentBedtimeState = Settings.Global.getInt(
                    applicationContext.contentResolver, getBedtimeSettingName(), -1
                )

                if (bedtimeState != null && bedtimeState != currentBedtimeState) {
                    Log.d(TAG, "bedtimeStatePhone != currentBedtimeState: $bedtimeState != $currentBedtimeState")

                    // 💡 安全同步勿扰：开启睡眠时强制开启勿扰(2: PRIORITY)，关闭睡眠时恢复正常(1: ALL)
                    val targetDnd = if (bedtimeState == 1) 2 else 1
                    changeDndSetting(nm, targetDnd)

                    changeBedtimeSetting(bedtimeState)
                    Log.d(TAG, "Bedtime mode value toggled")

                    // 独立 try-catch 保护外延开关，避免反序列化旧数据异常影响核心全屏提醒
                    try {
                        if (phoneSignal.powersavePref) {
                            changePowerModeSetting(bedtimeState)
                        }
                        if (phoneSignal.vibratePref) {
                            vibrate()
                        }
                    } catch (extEx: Exception) {
                        Log.e(TAG, "设置省电或振动失败", extEx)
                    }
                }

                // ====================== 3. 全屏幕循环提醒功能（安全且精准触发） ======================
                if (bedtimeState == 1) {
                    startBedtimeCycle()
                } else if (bedtimeState == 0) {
                    stopBedtimeCycle()
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理消息整体失败", e)
            } finally {
                isSyncingFromPhone = false
            }
        } else {
            super.onMessageReceived(messageEvent)
        }
    }

    private fun startBedtimeCycle() {
        if (bedtimeCycleRunning) return
        bedtimeCycleRunning = true
        Log.d(TAG, "启动全屏幕循环提醒逻辑...")

        launchFullscreenActivity()

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    Log.d(TAG, "监听到手錶屏幕亮起，准备弹出全屏幕提醒")
                    launchFullscreenActivity()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        registerReceiver(screenReceiver, filter)
    }

    private fun stopBedtimeCycle() {
        if (!bedtimeCycleRunning) return
        bedtimeCycleRunning = false
        Log.d(TAG, "停止全屏幕循环提醒逻辑")
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "注销广播接收器失败", e)
        }
        screenReceiver = null
        
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }

    private fun launchFullscreenActivity() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFullscreenLaunch < FULLSCREEN_COOLDOWN_MS) {
            Log.d(TAG, "全屏幕弹窗冷卻中，跳过本次触发")
            return
        }
        lastFullscreenLaunch = now

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Bedtime Sync", NotificationManager.IMPORTANCE_HIGH)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(channel)
        }

        val fullScreenIntent = Intent()
        fullScreenIntent.setComponent(ComponentName("it.silleellie.dndsync", "it.silleellie.dndsync.MainActivity"))
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(this, 0, fullScreenIntent, flags)

        // ✅ 彻底洗白修复：不使用容易在网页端引发链式错误的方法，采用逐行显式配置
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setSmallIcon(applicationContext.applicationInfo.icon)
        builder.setContentTitle("睡眠模式已同步")
        builder.setContentText("正在保持睡眠同步中...")
        builder.setPriority(Notification.PRIORITY_HIGH)
        builder.setCategory(Notification.CATEGORY_CALL)
        builder.setFullScreenIntent(fullScreenPendingIntent, true)
        builder.setVisibility(Notification.VISIBILITY_PUBLIC)

        nm.notify(NOTIF_ID, builder.build())
        Log.d(TAG, "全屏幕提醒通知已发送")
    }

    private fun changeDndSetting(nm: NotificationManager, newSetting: Int) {
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(newSetting)
            Log.d(TAG, "DND set to $newSetting")
        }
    }

    private fun getBedtimeSettingName(): String =
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) "setting_bedtime_mode_running_state" else "bedtime_mode"

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
