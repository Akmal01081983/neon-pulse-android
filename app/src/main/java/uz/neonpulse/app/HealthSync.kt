package uz.neonpulse.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.reflect.KClass

/** Чтение данных из Health Connect и отправка в бот (формат совпадает с приёмом Android в n8n — WF13). */
object HealthSync {
    const val SERVER = "https://akmalparpiev.app.n8n.cloud/webhook/health-android"
    const val BOT = "https://t.me/WHOOP_Neon_bot"
    private const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /** Какие данные читаем — только чтение */
    val TYPES: List<KClass<out Record>> = listOf(
        HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
        StepsRecord::class, ActiveCaloriesBurnedRecord::class, SleepSessionRecord::class,
        RespiratoryRateRecord::class, OxygenSaturationRecord::class, Vo2MaxRecord::class,
        WeightRecord::class, ExerciseSessionRecord::class, BloodPressureRecord::class,
    )
    val PERMISSIONS: Set<String> = TYPES.map { HealthPermission.getReadPermission(it) }.toSet() + BACKGROUND

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    // ===== Настройки (ключ из бота и итог последней отправки) =====
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("neon", Context.MODE_PRIVATE)
    fun token(ctx: Context): String? = prefs(ctx).getString("token", null)
    fun setToken(ctx: Context, t: String) = prefs(ctx).edit().putString("token", t.trim().lowercase()).apply()
    fun lastStatus(ctx: Context): String = prefs(ctx).getString("last", "Ещё не отправляли") ?: ""
    private fun setStatus(ctx: Context, s: String) = prefs(ctx).edit().putString("last", s).apply()
    fun validToken(t: String) = Regex("^[a-z0-9]{32}$").matches(t.trim().lowercase())

    // ===== Вход через Telegram: одноразовый код → бот → забираем ключ =====
    const val PAIR = "https://akmalparpiev.app.n8n.cloud/webhook/app-pair"
    fun newNonce(ctx: Context): String {
        val abc = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = java.security.SecureRandom()
        val n = (1..24).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
        prefs(ctx).edit().putString("nonce", n).apply()
        return n
    }
    fun nonce(ctx: Context): String? = prefs(ctx).getString("nonce", null)
    /** Спрашиваем сервер: нажал ли человек START в боте. Да — сохраняем ключ. */
    fun claim(ctx: Context): Boolean {
        val n = nonce(ctx) ?: return false
        return try {
            val conn = URL("$PAIR?n=$n").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val t = JSONObject(txt).optString("token", "")
            if (validToken(t)) { setToken(ctx, t); prefs(ctx).edit().remove("nonce").apply(); true } else false
        } catch (e: Exception) { false }
    }

    private suspend fun <T : Record> readAll(c: HealthConnectClient, type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = mutableListOf<T>()
        var page: String? = null
        do {
            val r = c.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageToken = page))
            out += r.records
            page = r.pageToken
        } while (page != null && out.size < 20000)
        return out
    }

    /**
     * Синхронизация. manual=true — нажата кнопка: отправляем всегда.
     * Автоматически утром (до 12:00) ждём, пока закончится сон, иначе отчёт уйдёт с неполной ночью.
     */
    suspend fun run(ctx: Context, manual: Boolean): Boolean {
        val token = token(ctx) ?: run { setStatus(ctx, "Нет ключа — подключитесь из бота"); return false }
        if (!available(ctx)) { setStatus(ctx, "Health Connect не установлен"); return false }
        val c = HealthConnectClient.getOrCreate(ctx)
        val granted = c.permissionController.getGrantedPermissions()
        if (!granted.containsAll(PERMISSIONS - BACKGROUND)) { setStatus(ctx, "Нет доступа к данным — нажмите «Разрешить доступ»"); return false }

        val now = Instant.now()
        val from = now.minus(Duration.ofHours(26))
        val sleep = readAll(c, SleepSessionRecord::class, from.minus(Duration.ofHours(12)), now)
        val local = ZonedDateTime.now(ZoneId.systemDefault())
        val dayStart = local.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val sleptToday = sleep.any { it.endTime.isAfter(dayStart) && it.endTime.isBefore(now.minus(Duration.ofMinutes(20))) }
        if (!manual && local.hour < 12 && !sleptToday) { setStatus(ctx, "Жду окончания сна — ${local.toLocalTime().withNano(0)}"); return false }

        val body = JSONObject()
        body.put("heart_rate", JSONArray().apply {
            readAll(c, HeartRateRecord::class, from, now).forEach { r -> r.samples.forEach { s -> put(JSONObject().put("time", s.time.toString()).put("bpm", s.beatsPerMinute)) } }
        })
        body.put("resting_heart_rate", JSONArray().apply {
            readAll(c, RestingHeartRateRecord::class, from, now).forEach { put(JSONObject().put("time", it.time.toString()).put("bpm", it.beatsPerMinute)) }
        })
        body.put("heart_rate_variability", JSONArray().apply {
            readAll(c, HeartRateVariabilityRmssdRecord::class, from, now).forEach { put(JSONObject().put("time", it.time.toString()).put("rmssd_millis", it.heartRateVariabilityMillis)) }
        })
        body.put("steps", JSONArray().apply {
            readAll(c, StepsRecord::class, from, now).forEach { put(JSONObject().put("start_time", it.startTime.toString()).put("end_time", it.endTime.toString()).put("count", it.count)) }
        })
        body.put("active_calories", JSONArray().apply {
            readAll(c, ActiveCaloriesBurnedRecord::class, from, now).forEach { put(JSONObject().put("start_time", it.startTime.toString()).put("end_time", it.endTime.toString()).put("calories", it.energy.inKilocalories)) }
        })
        body.put("sleep", JSONArray().apply {
            sleep.forEach { s ->
                put(JSONObject().put("session_end_time", s.endTime.toString())
                    .put("duration_seconds", Duration.between(s.startTime, s.endTime).seconds)
                    .put("stages", JSONArray().apply { s.stages.forEach { st -> put(JSONObject().put("stage", st.stage).put("start_time", st.startTime.toString()).put("end_time", st.endTime.toString())) } }))
            }
        })
        body.put("respiratory_rate", JSONArray().apply {
            readAll(c, RespiratoryRateRecord::class, from, now).forEach { put(JSONObject().put("time", it.time.toString()).put("rate", it.rate)) }
        })
        body.put("oxygen_saturation", JSONArray().apply {
            readAll(c, OxygenSaturationRecord::class, from, now).forEach { put(JSONObject().put("time", it.time.toString()).put("percentage", it.percentage.value)) }
        })
        body.put("vo2_max", JSONArray().apply {
            readAll(c, Vo2MaxRecord::class, now.minus(Duration.ofDays(30)), now).forEach { put(JSONObject().put("time", it.time.toString()).put("ml_per_kg_per_min", it.vo2MillilitersPerMinuteKilogram)) }
        })
        body.put("weight", JSONArray().apply {
            readAll(c, WeightRecord::class, now.minus(Duration.ofDays(30)), now).forEach { put(JSONObject().put("time", it.time.toString()).put("kilograms", it.weight.inKilograms)) }
        })
        body.put("exercise", JSONArray().apply {
            readAll(c, ExerciseSessionRecord::class, from, now).forEach { put(JSONObject().put("start_time", it.startTime.toString()).put("end_time", it.endTime.toString()).put("duration_seconds", Duration.between(it.startTime, it.endTime).seconds)) }
        })
        body.put("blood_pressure", JSONArray().apply {
            readAll(c, BloodPressureRecord::class, from, now).forEach { put(JSONObject().put("time", it.time.toString()).put("systolic", it.systolic.inMillimetersOfMercury).put("diastolic", it.diastolic.inMillimetersOfMercury)) }
        })

        return try {
            val conn = URL("$SERVER?t=$token").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            val ok = code in 200..299
            setStatus(ctx, (if (ok) "✅ Отправлено" else "⚠️ Ошибка сервера $code") + " — ${local.toLocalDate()} ${local.toLocalTime().withNano(0)}")
            ok
        } catch (e: Exception) {
            setStatus(ctx, "⚠️ Нет интернета — повторю позже")
            false
        }
    }
}
