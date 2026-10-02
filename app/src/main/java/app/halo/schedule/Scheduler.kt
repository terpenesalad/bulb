package app.halo.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.halo.MainActivity
import app.halo.data.AppSettings
import app.halo.data.Schedule
import app.halo.data.ScheduleAction
import app.halo.data.Store
import app.halo.data.TriggerType
import app.halo.light.Diagnostics
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

object Scheduler {
    private const val PREFS = "scheduler"
    private const val KEY_IDS = "armed"
    const val EXTRA_ID = "schedule_id"

    /** When the schedule's moment is (e.g. "7:00" for a wake-up that should be fully bright at 7). */
    fun nextTarget(s: Schedule, settings: AppSettings, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val zone = now.zone
        val lead = leadMinutes(s)
        for (d in 0..8) {
            val date = now.toLocalDate().plusDays(d.toLong())
            if (s.days.isNotEmpty() && date.dayOfWeek.value !in s.days) continue
            val target = when (s.trigger) {
                TriggerType.CLOCK -> date.atTime(s.hour, s.minute).atZone(zone)
                TriggerType.SUNRISE, TriggerType.SUNSET -> {
                    val lat = settings.latitude ?: return null
                    val lon = settings.longitude ?: return null
                    val (rise, set) = SunCalc.times(date, lat, lon, zone) ?: continue
                    (if (s.trigger == TriggerType.SUNRISE) rise else set).plusMinutes(s.offsetMinutes.toLong())
                }
            }
            if (target.minusMinutes(lead).isAfter(now.plusSeconds(2))) return target
        }
        return null
    }

    /** Wake-ups start early so the light is fully on at the chosen time. */
    fun leadMinutes(s: Schedule): Long = if (s.action == ScheduleAction.WAKE_UP) s.fadeMinutes.toLong() else 0L

    fun nextFire(s: Schedule, settings: AppSettings, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? =
        nextTarget(s, settings, now)?.minusMinutes(leadMinutes(s))

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun rescheduleAll(context: Context) {
        val data = Store.value
        val am = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previously = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty()
        val armed = HashSet<String>()
        for (s in data.schedules) {
            val pi = pending(context, s.id)
            am.cancel(pi)
            if (!s.enabled) continue
            val at = nextFire(s, data.settings) ?: continue
            val ms = at.toInstant().toEpochMilli()
            try {
                if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
                armed += s.id
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
                armed += s.id
            }
        }
        // cancel alarms for deleted schedules
        for (id in previously - armed) am.cancel(pending(context, id))
        prefs.edit().putStringSet(KEY_IDS, armed).apply()
        Diagnostics.log("schedule", "armed ${armed.size} schedule(s)")
    }

    private fun pending(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(context, ScheduleReceiver::class.java).putExtra(EXTRA_ID, id).setAction("app.halo.SCHEDULE.$id"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun describeNext(s: Schedule, settings: AppSettings, now: ZonedDateTime = ZonedDateTime.now()): String? {
        val t = nextTarget(s, settings, now) ?: return null
        val d = Duration.between(now, t)
        val h = d.toHours()
        val m = d.toMinutes() % 60
        return when {
            h >= 24 -> "in ${h / 24}d ${h % 24}h"
            h > 0 -> "in ${h}h ${m}m"
            else -> "in ${m.coerceAtLeast(1)}m"
        }
    }

    fun zone(): ZoneId = ZoneId.systemDefault()
}
