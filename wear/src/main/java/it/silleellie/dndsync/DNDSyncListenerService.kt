package it.silleellie.dndsync

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.content.getSystemService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import it.silleellie.dndsync.shared.PhoneSignal
import org.apache.commons.lang3.SerializationUtils

import android.os.Handler
import android.os.Looper



class DNDSyncListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path.equals(DND_SYNC_MESSAGE_PATH, ignoreCase = true)) {
            Log.d(TAG, "received path: $DND_SYNC_MESSAGE_PATH")

            val data = messageEvent.data
            val phoneSignal = SerializationUtils.deserialize<PhoneSignal>(data)

            Log.d(TAG, "dndStatePhone: ${phoneSignal.dndState}")

            val mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val currentDndState = mNotificationManager.currentInterruptionFilter

            Log.d(TAG, "currentDndState: $currentDndState")
            
            if (currentDndState < 0 || currentDndState > 4) {
                Log.d(TAG, "Current DND state is suspicious, should be in range [0,4]")
            }

            if (phoneSignal.dndState != null && phoneSignal.dndState == currentDndState) {
                return
            } else if (phoneSignal.dndState != null) {
                Log.d(TAG, "dndStatePhone != currentDndState: ${phoneSignal.dndState} != $currentDndState")
                changeDndSetting(mNotificationManager, phoneSignal.dndState!!)
                if (phoneSignal.vibratePref) {
                    vibrate()
                }
            }

            private val handler = Handler(Looper.getMainLooper())
            private var pendingBedtimeRunnable: Runnable? = null
            private var bedtimeTriggered = false


            val currentBedtimeState = Settings.Global.getInt(
                applicationContext.contentResolver, getBedtimeSettingName(), -1
            )

            if (phoneSignal.bedtimeState != null && phoneSignal.bedtimeState != currentBedtimeState) {
                Log.d(TAG, "bedtimeStatePhone != currentBedtimeState: ${phoneSignal.bedtimeState} != $currentBedtimeState")

                // 状态转换：phoneSignal.bedtimeState == 1 通常为关闭，2 为开启
                val dndState = if (phoneSignal.bedtimeState == 1) 2 else 1
                changeDndSetting(mNotificationManager, dndState)

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
        } else {
            super.onMessageReceived(messageEvent)
        }
    }

    private fun changeDndSetting(mNotificationManager: NotificationManager, newSetting: Int) {
        if (mNotificationManager.isNotificationPolicyAccessGranted) {
            mNotificationManager.setInterruptionFilter(newSetting)
            Log.d(TAG, "DND set to $newSetting")
        } else {
            Log.d(TAG, "attempting to set DND but access not granted")
        }
    }

    private fun getBedtimeSettingName(): String {
        return if (Build.MANUFACTURER.contains("samsung", ignoreCase = true)) "setting_bedtime_mode_running_state" else "bedtime_mode"
    }

    private fun changeBedtimeSetting(newSetting: Int): Boolean {
    val settingBedtimeStr = getBedtimeSettingName()
    val resolver = applicationContext.contentResolver

    val bedtimeModeSuccess =
        Settings.Global.putInt(resolver, settingBedtimeStr, newSetting)
    val zenModeSuccess =
        Settings.Global.putInt(resolver, "zen_mode", newSetting)

    // -----------------------------
    // 仅开启 bedtime 才执行
    // -----------------------------
    if (newSetting == 2) {

        // 防止重复触发
        if (!bedtimeTriggered) {

            val pm = packageManager
            val intent = Intent().apply {
                component = ComponentName(
                    "com.google.android.apps.wearable.settings",
                    "com.samsung.android.clockwork.settings.advanced.bedtimemode.StBedtimeModeReservedActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(pm) != null) {

                Log.d(TAG, "Bedtime ON detected, scheduling launch in 5s")

                // 如果已有任务，先取消
                pendingBedtimeRunnable?.let {
                    handler.removeCallbacks(it)
                }

                val runnable = Runnable {
                    try {
                        startActivity(intent)
                        Log.d(TAG, "Bedtime activity launched after delay")
                        bedtimeTriggered = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start activity", e)
                    }
                }

                pendingBedtimeRunnable = runnable
                handler.postDelayed(runnable, 5000)
            }
        }

    } else {
        // bedtime 关闭 → 重置状态
        bedtimeTriggered = false
        pendingBedtimeRunnable?.let {
            handler.removeCallbacks(it)
        }
        pendingBedtimeRunnable = null
    }

    return bedtimeModeSuccess && zenModeSuccess
    }

        return bedtimeModeSuccess && zenModeSuccess
    }

    private fun triggerSamsungBedtimeActivity() {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.google.android.apps.wearable.settings",
                    "com.samsung.android.clockwork.settings.advanced.bedtimemode.StBedtimeModeReservedActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.d(TAG, "三星睡眠模式 Activity 启动成功")
        } catch (e: Exception) {
            Log.e(TAG, "无法启动三星 Activity: ${e.message}")
        }
    }

    private fun changePowerModeSetting(newSetting: Int): Boolean {
        val resolver = applicationContext.contentResolver
        val lowPower = Settings.Global.putInt(resolver, "low_power", newSetting)
        val restricted = Settings.Global.putInt(resolver, "restricted_device_performance", newSetting)
        val lowPowerData = Settings.Global.putInt(resolver, "low_power_back_data_off", newSetting)
        val connectivity = Settings.Secure.putInt(resolver, "sm_connectivity_disable", newSetting)

        return lowPower && restricted && lowPowerData && connectivity
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

    companion object {
        private const val TAG = "DNDSyncListenerService"
        private const val DND_SYNC_MESSAGE_PATH = "/wear-dnd-sync"
    }
}
