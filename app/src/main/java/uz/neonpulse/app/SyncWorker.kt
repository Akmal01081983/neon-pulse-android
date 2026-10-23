package uz.neonpulse.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Фоновая работа раз в час (без Telegram): данные часов — утром отчёт после сна, днём нагрузка и стресс «вживую»;
 * затем «входящие» — итоги недели, биовозраст, оплата → уведомление на телефон.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { HealthSync.run(applicationContext, manual = false) } // без доступа к часам — просто пропуск
        runCatching { Notifier.inbox(applicationContext) }
        return Result.success() // ошибки не повторяем — через час будет следующая попытка
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("neon-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
