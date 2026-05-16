package it.silleellie.dndsync

import android.app.NotificationManager
import android.content.*
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.content.getSystemService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import it.silleellie.dndsync.shared.PhoneSignal
import org.apache.commons.lang3.SerializationUtils

class DNDSyncListenerService : WearableListenerService() {

    companion object {

        private const val TAG =
            "DNDSyncListenerService"

        private const val DND_SYNC_MESSAGE_PATH =
            "/wear-dnd-sync"

        /**
         * 用户退出 Bedtime 后
         * 多久重新进入全屏
         */
        private const val BEDTIME_RETURN_DELAY =
            30000L

        /**
         * 防止短时间重复 launch
         */
        private const val LAUNCH_COOLDOWN =
            2000L

        @Volatile
        var isSyncingFromPhone = false

        /**
         * Bedtime Activity 当前是否可见
         *
         * 在 Activity 的 onResume/onPause
         * 中维护
         */
        @Volatile
        var isBedtimeActivityVisible = false
    }

    private val handler =
        Handler(Looper.getMainLooper())

    /**
     * 当前是否正在运行 Bedtime 守护
     */
    private var bedtimeCycleRunning = false

    /**
     * 防止重复 launch
     */
    private var lastLaunchTime = 0L

    /**
     * Bedtime 返回任务
     */
    private val bedtimeRunnable = Runnable {

        if (!bedtimeCycleRunning) {

            Log.d(
                TAG,
                "Bedtime 守护未运行"
            )

            return@Runnable
        }

        /**
         * 用户已经真正关闭睡眠模式
         */
        if (!isBedtimeEnabled()) {

            Log.d(
                TAG,
                "Bedtime 已关闭，停止恢复"
            )

            stopBedtimeCycle()

            return@Runnable
        }

        /**
         * Activity 已在前台
         */
        if (isBedtimeActivityVisible) {

            Log.d(
                TAG,
                "Bedtime Activity 已显示"
            )

            return@Runnable
        }

        Log.d(
            TAG,
            "恢复 Bedtime Activity"
        )

        launchFullscreenActivity()
    }

    /**
     * 用户离开 Bedtime 后
     * 开始 return timer
     */
    private val interactionReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (!bedtimeCycleRunning) {
                    return
                }

                when (intent?.action) {

                    Intent.ACTION_USER_PRESENT -> {

                        Log.d(
                            TAG,
                            "收到 USER_PRESENT"
                        )

                        /**
                         * 用户真正关闭了睡眠模式
                         */
                        if (!isBedtimeEnabled()) {

                            Log.d(
                                TAG,
                                "用户已关闭 Bedtime"
                            )

                            stopBedtimeCycle()

                            /**
                             * TODO:
                             * 通知手机同步退出
                             */

                            return
                        }

                        /**
                         * Bedtime 仍开启
                         * 用户只是临时退出 UI
                         */

                        handler.removeCallbacks(
                            bedtimeRunnable
                        )

                        handler.postDelayed(
                            bedtimeRunnable,
                            BEDTIME_RETURN_DELAY
                        )

                        Log.d(
                            TAG,
                            "已启动 return timer"
                        )
                    }
                }
            }
        }

    // =========================================================
    // Lifecycle
    // =========================================================

    override fun onCreate() {

        super.onCreate()

        val filter = IntentFilter().apply {

            addAction(
                Intent.ACTION_USER_PRESENT
            )
        }

        registerReceiver(
            interactionReceiver,
            filter
        )
    }

    override fun onDestroy() {

        try {

            unregisterReceiver(
                interactionReceiver
            )

        } catch (_: Exception) {
        }

        handler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }

    // =========================================================
    // Message
    // =========================================================

    override fun onMessageReceived(
        messageEvent: MessageEvent
    ) {

        if (
            !messageEvent.path.equals(
                DND_SYNC_MESSAGE_PATH,
                ignoreCase = true
            )
        ) {

            super.onMessageReceived(
                messageEvent
            )

            return
        }

        Log.d(
            TAG,
            "收到手机同步消息"
        )

        try {

            isSyncingFromPhone = true

            val phoneSignal =
                SerializationUtils.deserialize<PhoneSignal>(
                    messageEvent.data
                )

            var dndState =
                phoneSignal.dndState

            var bedtimeState =
                phoneSignal.bedtimeState

            /**
             * 扩展协议
             * 5 = 开启 Bedtime
             * 6 = 关闭 Bedtime
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
                getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as NotificationManager

            val currentDndState =
                nm.currentInterruptionFilter

            // =====================================================
            // DND
            // =====================================================

            if (
                dndState != null &&
                dndState != currentDndState &&
                dndState in 1..4
            ) {

                changeDndSetting(
                    nm,
                    dndState
                )
            }

            // =====================================================
            // Bedtime
            // =====================================================

            val currentBedtimeState =
                Settings.Global.getInt(
                    contentResolver,
                    getBedtimeSettingName(),
                    -1
                )

            if (
                bedtimeState != null &&
                bedtimeState != currentBedtimeState
            ) {

                Log.d(
                    TAG,
                    "Bedtime 改变: " +
                            "$currentBedtimeState -> $bedtimeState"
                )

                /**
                 * 睡眠模式联动 DND
                 */
                val targetDnd =
                    if (bedtimeState == 1) 2 else 1

                changeDndSetting(
                    nm,
                    targetDnd
                )

                changeBedtimeSetting(
                    bedtimeState
                )

                try {

                    if (phoneSignal.powersavePref) {

                        changePowerModeSetting(
                            bedtimeState
                        )
                    }

                    if (phoneSignal.vibratePref) {

                        vibrate()
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "扩展状态设置失败",
                        e
                    )
                }

                /**
                 * 启动 / 停止 Bedtime 守护
                 */
                if (bedtimeState == 1) {

                    startBedtimeCycle()

                } else {

                    stopBedtimeCycle()
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "处理同步失败",
                e
            )

        } finally {

            isSyncingFromPhone = false
        }
    }

    // =========================================================
    // Bedtime Cycle
    // =========================================================

    private fun startBedtimeCycle() {

        bedtimeCycleRunning = true

        Log.d(
            TAG,
            "启动 Bedtime 守护"
        )

        handler.removeCallbacks(
            bedtimeRunnable
        )

        /**
         * 避免重复 launch
         */
        if (!isBedtimeActivityVisible) {

            launchFullscreenActivity()
        }
    }

    private fun stopBedtimeCycle() {

        bedtimeCycleRunning = false

        handler.removeCallbacks(
            bedtimeRunnable
        )

        Log.d(
            TAG,
            "停止 Bedtime 守护"
        )
    }

    // =========================================================
    // Fullscreen Activity
    // =========================================================

    private fun launchFullscreenActivity() {

        val now =
            SystemClock.elapsedRealtime()

        if (
            now - lastLaunchTime <
            LAUNCH_COOLDOWN
        ) {

            Log.d(
                TAG,
                "launch cooldown"
            )

            return
        }

        /**
         * 已在前台
         */
        if (isBedtimeActivityVisible) {

            Log.d(
                TAG,
                "Activity 已在前台"
            )

            return
        }

        lastLaunchTime = now

        try {

            val intent = Intent().apply {

                component = ComponentName(
                    "com.google.android.apps.wearable.settings",
                    "com.samsung.android.clockwork.settings.advanced.bedtimemode.StBedtimeModeReservedActivity"
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )

                action =
                    Intent.ACTION_VIEW
            }

            startActivity(intent)

            Log.d(
                TAG,
                "已启动 Bedtime Activity"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "启动 Bedtime Activity 失败",
                e
            )
        }
    }

    // =========================================================
    // Bedtime State
    // =========================================================

    private fun isBedtimeEnabled(): Boolean {

        return Settings.Global.getInt(
            contentResolver,
            getBedtimeSettingName(),
            0
        ) == 1
    }

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

        val name =
            getBedtimeSettingName()

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
    // DND
    // =========================================================

    private fun changeDndSetting(
        nm: NotificationManager,
        newSetting: Int
    ) {

        if (
            nm.isNotificationPolicyAccessGranted
        ) {

            nm.setInterruptionFilter(
                newSetting
            )

            Log.d(
                TAG,
                "DND 设置为 $newSetting"
            )
        }
    }

    // =========================================================
    // Power Save
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
    // Vibrate
    // =========================================================

    private fun vibrate() {

        val vibrator =
            getSystemService<Vibrator>()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

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
}
