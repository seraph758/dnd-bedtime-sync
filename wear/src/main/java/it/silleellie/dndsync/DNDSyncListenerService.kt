package it.silleellie.dndsync

import android.app.NotificationManager
import android.os.Build
import android.os.CombinedVibration
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.getSystemService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import it.silleellie.dndsync.shared.PhoneSignal
import it.silleellie.dndsync.shared.PreferenceKeys
import org.apache.commons.lang3.SerializationUtils

import android.content.ComponentName
import android.content.Intent


class DNDSyncListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.getPath().equals(DND_SYNC_MESSAGE_PATH, ignoreCase = true)) {
            Log.d(TAG, "received path: " + DND_SYNC_MESSAGE_PATH)

            // data is now a PhoneSignal object, it must be deserialized
            val data = messageEvent.getData()
            val phoneSignal = SerializationUtils.deserialize<PhoneSignal>(data)

            Log.d(TAG, "dndStatePhone: " + phoneSignal.dndState)

            // get dnd state
            val mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val currentDndState = mNotificationManager.getCurrentInterruptionFilter()

            Log.d(TAG, "currentDndState: " + currentDndState)
            if (currentDndState < 0 || currentDndState > 4) {
                Log.d(TAG, "Current DND state is suspicious, should be in range [0,4]")
            }

            if (phoneSignal.dndState != null && phoneSignal.dndState == currentDndState) {
                // avoid issue that happens due to redundant signal propagation:
                // if dnd_as_bedtime and watch_sync_dnd are activated, when dnd is activated
                // from the watch, dnd is activated to the phone and then bedtime is activated
                // back on the watch. This early return avoids that.
                return
            } else if (phoneSignal.dndState != null) {
                Log.d(
                    TAG,
                    "dndStatePhone != currentDndState: " + phoneSignal.dndState + " != " + currentDndState
                )

                changeDndSetting(mNotificationManager, phoneSignal.dndState!!)

                Log.d(TAG, "vibrate: " + phoneSignal.vibratePref)
                if (phoneSignal.vibratePref) {
                    vibrate()
                }
            }

            val currentBedtimeState = Settings.Global.getInt(
                applicationContext.contentResolver, getBedtimeSettingName(), -1
            )

            if (phoneSignal.bedtimeState != null && phoneSignal.bedtimeState != currentBedtimeState) {
                Log.d(
                    TAG,
                    "bedtimeStatePhone != currentBedtimeState: " + phoneSignal.bedtimeState + " != " + currentBedtimeState
                )

                // activating/disabling bedtime also activates/disables dnd, just like
                // when activating bedtime manually from the watch.
                // dndState = 2 means it's activated, dndState = 1 means it's disabled
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

                Log.d(TAG, "vibrate: " + phoneSignal.vibratePref)
                if (phoneSignal.vibratePref) {
                    vibrate()
                }
            }
        } else {
            super.onMessageReceived(messageEvent)
        }
    }

    private fun changeDndSetting(mNotificationManager: NotificationManager, newSetting: Int) {
        if (mNotificationManager.isNotificationPolicyAccessGranted()) {
            mNotificationManager.setInterruptionFilter(newSetting)
            Log.d(TAG, "DND set to " + newSetting)
        } else {
            Log.d(TAG, "attempting to set DND but access not granted")
        }
    }

    private fun getBedtimeSettingName(): String {
        return if (Build.MANUFACTURER == "samsung") "setting_bedtime_mode_running_state" else "bedtime_mode"
    }

    private fun changeBedtimeSetting(newSetting: Int): Boolean {
    val settingBedtimeStr = getBedtimeSettingName()
    val resolver = applicationContext.contentResolver

    [span_3](start_span)// 1. 同步系统全局设置[span_3](end_span)
    val bedtimeModeSuccess = Settings.Global.putInt(resolver, settingBedtimeStr, newSetting)
    val zenModeSuccess = Settings.Global.putInt(resolver, "zen_mode", newSetting)

    [span_4](start_span)// 2. 核心逻辑：仅当 newSetting 为 2（开启状态）时触发三星特定 Activity[span_4](end_span)
    if (newSetting == 2) {
        Log.d(TAG, "检测到睡眠模式开启 (value: 2)，正在触发三星特定 Activity...")
        triggerSamsungBedtimeActivity()
    }

    return bedtimeModeSuccess && zenModeSuccess
}

/**
 * [span_5](start_span)执行特定的三星 Activity 命令[span_5](end_span)
 * 效果等同于：adb shell am start -n com.google.android.apps.wearable.settings/...
 */
    private fun triggerSamsungBedtimeActivity() {
    try {
        val intent = Intent().apply {
            component = ComponentName(
                "com.google.android.apps.wearable.settings",
                "com.samsung.android.clockwork.settings.advanced.bedtimemode.StBedtimeModeReservedActivity"
            )
            [span_6](start_span)// 从 Service 启动 Activity 必须添加此 Flag[span_6](end_span)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        [span_7](start_span)Log.d(TAG, "三星睡眠模式 Activity 启动成功[span_7](end_span)")
    } catch (e: Exception) {
        [span_8](start_span)// 防止在非三星设备或不同系统版本上运行时崩溃[span_8](end_span)
        Log.e(TAG, "无法启动三星 Activity: ${e.message}")
    }
}


    /**
     * Changes the power mode setting.
     *
     * **NOTE:** does not seem to work on non-samsung watches, like the Pixel Watch.
     */
    private fun changePowerModeSetting(newSetting: Int): Boolean {
        val lowPower = Settings.Global.putInt(
            getApplicationContext().getContentResolver(), "low_power", newSetting
        )
        val restrictedDevicePerformance = Settings.Global.putInt(
            getApplicationContext().getContentResolver(),
            "restricted_device_performance",
            newSetting
        )

        val lowPowerBackDataOff = Settings.Global.putInt(
            getApplicationContext().getContentResolver(), "low_power_back_data_off", newSetting
        )
        val smConnectivityDisable = Settings.Secure.putInt(
            getApplicationContext().getContentResolver(), "sm_connectivity_disable", newSetting
        )

        // screen timeout should be set to 10000 also, and ambient_tilt_to_wake should be set to 0
        // but previous variable states in those 2 cases must be stored and they do not seem to stick
        // and they are not so much important tbh (ambient tilt to wake is disabled anyways)
        return lowPower && restrictedDevicePerformance
                && lowPowerBackDataOff && smConnectivityDisable
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
