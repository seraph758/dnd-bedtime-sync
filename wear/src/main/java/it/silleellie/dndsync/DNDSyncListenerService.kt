package it.silleellie.dndsync

import android.app.NotificationManager
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

        /**
         * 防止频繁重复拉起 Activity
         */
        private const val FULLSCREEN_COOLDOWN_MS = 1500L

        /**
         * 防止手表同步后再次回传手机造成死循环
         */
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

            isSyncingFromPhone = true

            val data = messageEvent.data

            val phoneSignal =
                SerializationUtils.deserialize<PhoneSignal>(data)

            // ======================
            // 提取同步状态
            // ======================

            var dndState = phoneSignal.dndState
            var bedtimeState = phoneSignal.bedtimeState

            /**
             * 自定义扩展协议：
             * 5 = 开启睡眠模式
             * 6 = 关闭睡眠模式
             */
            if (dndState == 5) {
                bedtimeState = 1
                dndState = null
            } else if (dndState == 6) {
                bedtimeState = 0
                dndState = null
            }

            Log.d(
                TAG,
                "解析后 -> dnd=$dndState bedtime=$bedtimeState"
            )

            val nm =
                getSystemService(Context.NOTIFICATION_SERVICE)
                        as NotificationManager

            val currentDndState =
                nm.currentInterruptionFilter

            // ======================
            // DND 同步
            // ======================

            if (
                dndState != null &&
                dndState != currentDndState &&
                dndState in 1..4
            ) {

                changeDndSetting(nm, dndState)
            }

            // ======================
            // Bedtime 同步
            // ======================

            val currentBedtimeState =
                Settings.Global.getInt(
                    applicationContext.contentResolver,
                    getBedtimeSettingName(),
                    -1
                )

            if (
                bedtimeState != null &&
                bedtimeState != currentBedtimeState
            ) {

                Log.d(
                    TAG,
                    "Bedtime 改变: $currentBedtimeState -> $bedtimeState"
                )

                /**
                 * 睡眠模式联动 DND
                 */
                val targetDnd =
                    if (bedtimeState == 1) 2 else 1

                changeDndSetting(nm, targetDnd)

                changeBedtimeSetting(bedtimeState)

                try {

                    if (phoneSignal.powersavePref) {
                        changePowerModeSetting(bedtimeState)
                    }

                    if (phoneSignal.vibratePref) {
                        vibrate()
                    }

                } catch (extEx: Exception) {

                    Log.e(
                        TAG,
                        "设置省电/振动失败",
                        extEx
                    )
                }
            }

            // ======================
            // 全屏循环逻辑
            // ======================

            if (bedtimeState == 1) {

                startBedtimeCycle()

            } else if (bedtimeState == 0) {

                stopBedtimeCycle()
            }

        } catch (e: Exception) {

            Log.e(TAG, "处理同步消息失败", e)

        } finally {

            isSyncingFromPhone = false
        }
    }

    // =========================================================
    // 睡眠循环逻辑
    // =========================================================

    private fun startBedtimeCycle() {

        if (bedtimeCycleRunning) {
            return
        }

        bedtimeCycleRunning = true

        Log.d(TAG, "启动 Bedtime 循环")

        launchFullscreenActivity()

        screenReceiver = object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (intent?.action == Intent.ACTION_SCREEN_ON) {

                    Log.d(
                        TAG,
                        "检测到亮屏，重新拉起三星睡眠页面"
                    )

                    launchFullscreenActivity()
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)

        registerReceiver(screenReceiver, filter)
    }

    private fun stopBedtimeCycle() {

        if (!bedtimeCycleRunning) {
            return
        }

        bedtimeCycleRunning = false

        Log.d(TAG, "停止 Bedtime 循环")

        try {

            screenReceiver?.let {
                unregisterReceiver(it)
            }

        } catch (e: Exception) {

            Log.e(TAG, "注销广播失败", e)
        }

        screenReceiver = null
    }

    // =========================================================
    // 核心：直接启动三星睡眠页面
    // =========================================================

    private fun launchFullscreenActivity() {

        val now = SystemClock.elapsedRealtime()

        if (
            now - lastFullscreenLaunch <
            FULLSCREEN_COOLDOWN_MS
        ) {

            Log.d(TAG, "Activity 冷却中，跳过")
            return
        }

        lastFullscreenLaunch = now

        try {

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

            startActivity(intent)

            Log.d(
                TAG,
                "已启动三星睡眠模式 Activity"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "启动三星睡眠模式失败",
                e
            )
        }
    }

    // =========================================================
    // DND
    // =========================================================

    private fun changeDndSetting(
        nm: NotificationManager,
        newSetting: Int
    ) {

        if (nm.isNotificationPolicyAccessGranted) {

            nm.setInterruptionFilter(newSetting)

            Log.d(TAG, "DND 设置为 $newSetting")
        }
    }

    // =========================================================
    // Bedtime
    // =========================================================

    private fun getBedtimeSettingName(): String {

        return if (
            Build.MANUFACTURER.equals(
                "samsung",
                ignoreCase = true
            )
        ) {
            "setting_bedtime_mode_running_state"
        } else {
            "bedtime_mode"
        }
    }

    private fun changeBedtimeSetting(
        newSetting: Int
    ) {

        val name = getBedtimeSettingName()

        Settings.Global.putInt(
            contentResolver,
            name,
            newSetting
        )

        Settings.Global.putInt(
            contentResolver,
            "zen_mode",
            newSetting
        )
    }

    // =========================================================
    // 省电模式
    // =========================================================

    private fun changePowerModeSetting(
        newSetting: Int
    ) {

        Settings.Global.putInt(
            contentResolver,
            "low_power",
            newSetting
        )

        Settings.Global.putInt(
            contentResolver,
            "restricted_device_performance",
            newSetting
        )

        Settings.Global.putInt(
            contentResolver,
            "low_power_back_data_off",
            newSetting
        )

        Settings.Secure.putInt(
            contentResolver,
            "sm_connectivity_disable",
            newSetting
        )
    }

    // =========================================================
    // 振动
    // =========================================================

    private fun vibrate() {

        val vibrator = getSystemService<Vibrator>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            vibrator?.vibrate(
                VibrationEffect.createPredefined(
                    VibrationEffect.EFFECT_CLICK
                )
            )

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
