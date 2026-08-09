package ai.muna.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * A real reminder system (AlarmManager) that survives reboot and can repeat.
 * Reminders are SPOKEN aloud (for elderly / blind users) and shown as a
 * high-priority notification. Recurring reminders (e.g. "drink water every 2h")
 * reschedule themselves on each fire.
 */
object Reminders {

    const val CHANNEL = "matrash_reminders"
    private const val PREF = "reminders_store"
    private const val KEY = "list"
    private const val ZONE = "Asia/Dubai"

    /**
     * @param dayOffset 0 today, 1 tomorrow, …    @param hour 0-23 (-1 = "from now")
     * @param repeatHours >0 → recurring every N hours (starts N hours from now
     *                     unless an explicit time is given).
     */
    fun add(
        ctx: Context, message: String, dayOffset: Int, hour: Int, minute: Int, repeatHours: Int
    ): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(ZONE))
        val now = cal.timeInMillis
        val repeatMs = repeatHours.coerceAtLeast(0) * 3600_000L

        val triggerAt: Long
        if (hour in 0..23) {
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, if (minute in 0..59) minute else 0)
            cal.set(Calendar.SECOND, 0)
            cal.add(Calendar.DAY_OF_YEAR, dayOffset.coerceAtLeast(0))
            if (cal.timeInMillis <= now && dayOffset <= 0) cal.add(Calendar.DAY_OF_YEAR, 1)
            triggerAt = cal.timeInMillis
        } else if (repeatMs > 0) {
            triggerAt = now + repeatMs           // recurring with no explicit time
        } else {
            triggerAt = now + 3600_000L          // fallback: in an hour
        }

        val id = nextId(ctx)
        val list = load(ctx)
        list.put(JSONObject().put("id", id).put("msg", message)
            .put("at", triggerAt).put("repeat", repeatMs))
        save(ctx, list)
        setAlarm(ctx, id, message, triggerAt, repeatMs)
        return confirm(message, triggerAt, repeatHours)
    }

    private fun nextId(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val id = sp.getInt("seq", 1000) + 1
        sp.edit().putInt("seq", id).apply()
        return id
    }

    private fun confirm(message: String, at: Long, repeatHours: Int): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone(ZONE)).apply { timeInMillis = at }
        val hh = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        return if (repeatHours > 0) "زين، بذكّرچ بـ«$message» كل $repeatHours ساعة ✅"
        else "زين، بذكّرچ بـ«$message» الساعة $hh ✅"
    }

    fun rescheduleAll(ctx: Context) {
        val list = load(ctx)
        val now = System.currentTimeMillis()
        val kept = JSONArray()
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            var at = r.getLong("at")
            val repeat = r.optLong("repeat", 0)
            if (at <= now && repeat > 0) {
                // Advance to the next future slot.
                while (at <= now) at += repeat
                r.put("at", at)
            }
            if (at > now) {
                setAlarm(ctx, r.getInt("id"), r.getString("msg"), at, repeat)
                kept.put(r)
            }
        }
        save(ctx, kept)
    }

    /** Called by ReminderReceiver after a one-shot fires (drop it) or a repeat (advance). */
    fun onFired(ctx: Context, id: Int) {
        val list = load(ctx)
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            if (r.getInt("id") != id) { out.put(r); continue }
            val repeat = r.optLong("repeat", 0)
            if (repeat > 0) {
                val next = r.getLong("at") + repeat
                r.put("at", next)
                setAlarm(ctx, id, r.getString("msg"), next, repeat)
                out.put(r)
            } // else: one-shot → drop
        }
        save(ctx, out)
    }

    private fun setAlarm(ctx: Context, id: Int, message: String, at: Long, repeat: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("id", id).putExtra("msg", message).putExtra("repeat", repeat)
        val pi = PendingIntent.getBroadcast(
            ctx, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exactOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
        val scheduled = runCatching {
            if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.isSuccess
        if (!scheduled) runCatching { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, "التذكيرات", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "تذكيرات مطراش"
            nm.createNotificationChannel(ch)
        }
    }

    fun notify(ctx: Context, id: Int, message: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("تذكير من مطراش")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_mic_dark)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    private fun load(ctx: Context): JSONArray {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return runCatching { JSONArray(sp.getString(KEY, "[]")) }.getOrDefault(JSONArray())
    }

    private fun save(ctx: Context, list: JSONArray) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, list.toString()).apply()
    }
}

/** Fires the reminder: speaks it aloud + shows a notification, reschedules repeats. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val id = intent.getIntExtra("id", 0)
        val message = intent.getStringExtra("msg") ?: "عندچ تذكير"
        Reminders.notify(ctx, id, message)
        Reminders.onFired(ctx, id)
        // Speak it aloud (important for blind/elderly users), keeping the
        // receiver alive until speech finishes.
        val pending = goAsync()
        ReminderSpeaker.speak(ctx, "تذكير: $message") { pending.finish() }
    }
}

/** Re-arm all reminders after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Reminders.rescheduleAll(context.applicationContext)
        }
    }
}
