package app.halo.schedule

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.halo.Notifications
import app.halo.R
import app.halo.data.BuiltInPresets
import app.halo.data.Schedule
import app.halo.data.ScheduleAction
import app.halo.data.Store
import app.halo.light.Diagnostics
import app.halo.light.LightController
import app.halo.light.LightHub
import app.halo.tuya.BulbCodec
import app.halo.tuya.BulbMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Alarm fired: hand over to a foreground service so the network stays available in Doze. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Scheduler.EXTRA_ID) ?: return
        Diagnostics.log("schedule", "alarm for $id")
        val svc = Intent(context, ScheduleRunService::class.java).putExtra(Scheduler.EXTRA_ID, id)
        try {
            ContextCompat.startForegroundService(context, svc)
        } catch (e: Exception) {
            // Background start refused: do it inline (works for simple on/off).
            Diagnostics.log("schedule", "service start refused (${e.javaClass.simpleName}), running inline")
            val pending = goAsync()
            LightHub.scope.launch {
                try { ScheduleRunner.run(context, id) } finally { pending.finish() }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.rescheduleAll(context)
    }
}

class ScheduleRunService : Service() {
    private val active = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(Scheduler.EXTRA_ID)
        val schedule = Store.value.schedules.firstOrNull { it.id == id }
        val title = schedule?.let { ScheduleRunner.title(it) } ?: "Running schedule"
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_SCHEDULES)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle(title)
            .setContentText("Talking to your light…")
            .setContentIntent(Scheduler.openAppIntent(this))
            .setSilent(true)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, Notifications.ID_SCHEDULE, notification, type)
        } catch (e: Exception) {
            Diagnostics.log("schedule", "foreground refused: ${e.message}")
        }
        if (id == null) { stopSelf(startId); return START_NOT_STICKY }
        active.incrementAndGet()
        LightHub.scope.launch {
            try {
                ScheduleRunner.run(this@ScheduleRunService, id)
            } finally {
                if (active.decrementAndGet() == 0) {
                    ServiceCompat.stopForeground(this@ScheduleRunService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
}

object ScheduleRunner {
    fun title(s: Schedule): String = when (s.action) {
        ScheduleAction.WAKE_UP -> "Sunrise wake-up"
        ScheduleAction.WIND_DOWN -> "Winding down"
        ScheduleAction.TURN_ON -> "Turning on"
        ScheduleAction.TURN_OFF -> "Turning off"
        ScheduleAction.PRESET -> "Setting the mood"
    }

    suspend fun run(context: Context, id: String) {
        val data = Store.value
        val s = data.schedules.firstOrNull { it.id == id } ?: return
        val device = data.devices.firstOrNull { it.id == s.deviceId } ?: data.activeDevice ?: return
        val c = LightHub.controller(device)

        val pm = context.getSystemService(PowerManager::class.java)
        val holdMs = (s.fadeMinutes + 3) * 60_000L
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "halo:schedule").apply { acquire(holdMs) }
        val wifi = runCatching {
            @Suppress("DEPRECATION")
            context.applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "halo:schedule").apply { acquire() }
        }.getOrNull()
        LightHub.keepAlive++
        try {
            Diagnostics.log("schedule", "running ${s.action} (${s.label})")
            val ok = perform(c, s, data)
            if (!ok) notifyFailure(context, s)
        } catch (e: Exception) {
            Diagnostics.log("schedule", "failed: ${e.message}")
            notifyFailure(context, s)
        } finally {
            LightHub.keepAlive--
            runCatching { if (wifi?.isHeld == true) wifi.release() }
            runCatching { if (wake.isHeld) wake.release() }
            if (s.days.isEmpty()) Store.upsertSchedule(s.copy(enabled = false))
            Scheduler.rescheduleAll(context)
        }
    }

    /** Connect with patience: the phone's Wi-Fi may still be waking up. */
    private suspend fun connect(c: LightController): Boolean {
        repeat(6) { attempt ->
            if (c.refresh()) return true
            delay(5_000L + attempt * 5_000L)
        }
        return false
    }

    private suspend fun perform(c: LightController, s: Schedule, data: app.halo.data.AppData): Boolean {
        if (!connect(c)) return false
        val fadeMs = s.fadeMinutes.coerceAtLeast(1) * 60_000L
        return when (s.action) {
            ScheduleAction.TURN_ON -> c.send(BulbCodec.white(c.schema, s.brightness, s.warmth))
            ScheduleAction.TURN_OFF -> c.send(BulbCodec.power(c.schema, false))
            ScheduleAction.PRESET -> {
                val p = BuiltInPresets.find(s.presetId, data.presets) ?: return c.send(BulbCodec.power(c.schema, true))
                c.applyPresetNow(p)
            }
            ScheduleAction.WAKE_UP -> {
                // Start as a deep, dim amber and rise to the target brightness and warmth.
                c.send(BulbCodec.white(c.schema, 0.01f, 0f))
                c.fade(0.01f, s.brightness, s.warmth, fadeMs)
                true
            }
            ScheduleAction.WIND_DOWN -> {
                val st = c.state.value
                if (!st.on) return true
                val from = if (st.mode == BulbMode.COLOUR) st.value else st.brightness
                c.fade(from.coerceAtLeast(0.05f), 0.01f, 0f, fadeMs, offAtEnd = true)
                true
            }
        }
    }

    private fun notifyFailure(context: Context, s: Schedule) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_SCHEDULES)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle("Couldn't reach your light")
            .setContentText("\"${s.label.ifBlank { title(s) }}\" didn't run. Check the light is powered and your phone is on home Wi-Fi.")
            .setStyle(NotificationCompat.BigTextStyle())
            .setContentIntent(Scheduler.openAppIntent(context))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(Notifications.ID_SCHEDULE_FAILED, n) }
    }
}
