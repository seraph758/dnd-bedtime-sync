package it.silleellie.dndsync

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.content.getSystemService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import it.silleellie.dndsync.shared.PhoneSignal
import org.apache.commons.lang3.SerializationUtils

class DNDSyncListenerService : WearableListenerService() {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var pendingBedtimeRunnable: Runnable? = null

    @Volatile
    private var bedtimeTriggered = false

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (!messageEvent.path.equals(DND_SYNC_MESSAGE_PATH, true)) {
            super.onMessageReceived(messageEvent)
            return
        }

        val phoneSignal =
            SerializationUtils.deserialize<PhoneSignal>(messageEvent.data)

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // ---------------- DND ----------------
        val currentDnd = nm.currentInterruptionFilter
        phoneSignal.dndState?.let {
            if (it != currentDnd) {
                changeDndSetting(nm, it)
                if (phoneSignal.vibratePref) vibrate()
            }
        }

        // ---------------- Bedtime ----------------
        val currentBedtime = Settings.Global.getInt(
            applicationContext.contentResolver,
            getBedtimeSettingName(),
            -1
        )

        phoneSignal.bedtimeState?.let { target ->

            if (target != currentBedtime) {
                Log.d(TAG, "Bedtime change detected: $target")

                changeBedtimeSetting(target)

                if (phoneSignal.powersavePref) {
                    changePowerModeSetting(target)
                }

                if (phoneSignal.vibratePref) vibrate()
            }
        }
    }

    // ---------------- DND ----------------
    private fun changeDndSetting(nm: NotificationManager, newSetting: Int) {
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(newSetting)
        }
    }

    // ---------------- Bedtime ----------------
    private fun getBedtimeSettingName(): String {
        return if (Build.MANUFACTURER.contains("samsung", true)) {
            "setting_bedtime_mode_running_state"
        } else {
            "bedtime_mode"
        }
    }

    private fun changeBedtimeSetting(newSetting: Int): Boolean {
        val resolver = applicationContext.contentResolver
        val key = getBedtimeSettingName()

        val ok1 = Settings.Global.putInt(resolver, key, newSetting)
        val ok2 = Settings.Global.putInt(resolver, "zen_mode", newSetting)

        if (newSetting == 2) {
            scheduleSamsungBedtimeLaunch()
        } else {
            cancelScheduledBedtime()
        }

        return ok1 && ok2
    }

    // ---------------- SAFE DELAY TASK ----------------
    private fun scheduleSamsungBedtimeLaunch() {

        if (bedtimeTriggered) return

        val pm = packageManager

        val intent = Intent().apply {
            component = ComponentName(
                "com.google.android.apps.wearable.settings",
                "com.samsung.android.clockwork.settings.advanced.bedtimemode.StBedtimeModeReservedActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(pm) == null) {
            Log.w(TAG, "Bedtime activity not available")
            return
        }

        cancelScheduledBedtime()

        val runnable = Runnable {
            try {
                startActivity(intent)
                bedtimeTriggered = true
                Log.d(TAG, "Bedtime activity launched (delayed)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch bedtime activity", e)
            }
        }

        pendingBedtimeRunnable = runnable
        handler.postDelayed(runnable, 3000)
    }

    private fun cancelScheduledBedtime() {
        pendingBedtimeRunnable?.let {
            handler.removeCallbacks(it)
        }
        pendingBedtimeRunnable = null
        bedtimeTriggered = false
    }

    // ---------------- LIFECYCLE FIX (关键) ----------------
    override fun onDestroy() {
        super.onDestroy()

        // 防止内存泄漏
        cancelScheduledBedtime()

        Log.d(TAG, "Service destroyed, cleanup done")
    }

    // ---------------- POWER ----------------
    private fun changePowerModeSetting(newSetting: Int): Boolean {
        val r = applicationContext.contentResolver

        return Settings.Global.putInt(r, "low_power", newSetting) &&
                Settings.Global.putInt(r, "restricted_device_performance", newSetting) &&
                Settings.Global.putInt(r, "low_power_back_data_off", newSetting) &&
                Settings.Secure.putInt(r, "sm_connectivity_disable", newSetting)
    }

    // ---------------- VIBRATE ----------------
    private fun vibrate() {
        val vibrator = getSystemService<Vibrator>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibrator?.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            )
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
