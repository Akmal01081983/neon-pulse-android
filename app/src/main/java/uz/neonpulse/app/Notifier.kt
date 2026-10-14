package uz.neonpulse.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate

/**
 * Уведомления прямо на телефон (без Telegram).
 * Утренний отчёт: после первой удачной отправки данных за ночь — одно уведомление в день,
 * нажатие открывает Neon на вкладке «Сегодня».
 */
object Notifier {
    private const val CHANNEL = "morning"
    private const val ID_MORNING = 1

    /** Канал уведомлений (Android 8+); повторный вызов ничего не меняет */
    fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL, HealthSync.tr(ctx, "Утренний отчёт", "Ertalabki hisobot", "Morning report"), NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = HealthSync.tr(ctx, "Сон, заряд и цель нагрузки на день", "Uyqu, quvvat va kunlik yuklama maqsadi", "Sleep, recovery and today’s strain target")
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** Разрешено ли показывать уведомления (Android 13+ спрашивает отдельно) */
    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Показать «Отчёт готов» не чаще раза в день */
    fun morning(ctx: Context) {
        val prefs = ctx.getSharedPreferences("neon", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString("notified", null) == today || !allowed(ctx)) return
        channel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, CoachActivity.open(ctx, "today"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(HealthSync.tr(ctx, "☀️ Отчёт на сегодня готов", "☀️ Bugungi hisobot tayyor", "☀️ Today’s report is ready"))
            .setContentText(HealthSync.tr(ctx, "Сон, заряд и цель нагрузки — нажмите, чтобы открыть", "Uyqu, quvvat va yuklama maqsadi — ochish uchun bosing", "Sleep, recovery and strain target — tap to open"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID_MORNING, n)
            prefs.edit().putString("notified", today).apply()
        } catch (e: SecurityException) { /* разрешение отозвано — просто без уведомления */ }
    }
}
