package app.kd.assistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object ReminderStore {
    data class Item(val id: Int, val at: Long, val text: String)

    private const val PREFS = "kd_reminders"
    private const val KEY = "list"

    fun load(ctx: Context): MutableList<Item> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return s.split("\n").mapNotNull { line ->
            val p = line.split("|", limit = 3)
            if (p.size == 3) {
                val id = p[0].toIntOrNull()
                val at = p[1].toLongOrNull()
                if (id != null && at != null) Item(id, at, p[2]) else null
            } else null
        }.toMutableList()
    }

    private fun save(ctx: Context, items: List<Item>) {
        val s = items.joinToString("\n") { "${it.id}|${it.at}|${it.text.replace("\n", " ").replace("|", "/")}" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, s).apply()
    }

    fun add(ctx: Context, item: Item) {
        val l = load(ctx)
        l.add(item)
        save(ctx, l)
    }

    fun remove(ctx: Context, id: Int) {
        save(ctx, load(ctx).filter { it.id != id })
    }

    fun clearAll(ctx: Context) = save(ctx, emptyList())
}

object ReminderScheduler {
    fun schedule(ctx: Context, item: ReminderStore.Item) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val fire = PendingIntent.getBroadcast(
            ctx, item.id,
            Intent(ctx, ReminderReceiver::class.java).putExtra("id", item.id).putExtra("text", item.text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // setAlarmClock is exact and needs no special exact-alarm permission.
        am.setAlarmClock(AlarmManager.AlarmClockInfo(item.at, show), fire)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val text = intent.getStringExtra("text") ?: "রিমাইন্ডার"
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("kd_reminder", "KD রিমাইন্ডার", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, "kd_reminder")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("KD রিমাইন্ডার")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            // notification permission missing
        }
        ReminderStore.remove(context, id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val now = System.currentTimeMillis()
        for (item in ReminderStore.load(context)) {
            if (item.at > now) ReminderScheduler.schedule(context, item) else ReminderStore.remove(context, item.id)
        }
    }
}
