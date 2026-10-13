package uz.neonpulse.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Один экран и одна кнопка за раз:
 * «Подключить» → Telegram (START) → назад → «Разрешить все» → готово, дальше само.
 */
class MainActivity : ComponentActivity() {
    /** Текст на языке телефона: узбекский или русский */
    private fun t(ru: String, uz: String) = HealthSync.tr(this, ru, uz)
    private val NEON = Color.parseColor("#00F5D4")
    private val BG = Color.parseColor("#0B0F14")
    private val CARD = Color.parseColor("#151B23")
    private val DIM = Color.parseColor("#8A94A6")

    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var mainBtn: Button
    private lateinit var second: TextView
    private lateinit var coach: Button
    private lateinit var extra: LinearLayout
    private var busy = false

    // Android 13+: разрешение на уведомления (утренний отчёт приходит прямо на телефон)
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val permLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        lifecycleScope.launch { afterPermissions() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleLink(intent) }

    override fun onResume() { super.onResume(); lifecycleScope.launch { step() } }

    /** Запасной путь: ссылка neonpulse://connect?t=КЛЮЧ со страницы подключения */
    private fun handleLink(i: Intent?) {
        val t = i?.data?.getQueryParameter("t") ?: return
        if (HealthSync.validToken(t)) HealthSync.setToken(this, t)
    }

    /** Определяем, на каком шаге человек, и показываем ровно одну кнопку */
    private suspend fun step() {
        if (busy) return
        // 1. Нет ключа: если уже ходили в Telegram — забираем ключ (сервер мог ответить не сразу)
        if (HealthSync.token(this) == null && HealthSync.nonce(this) != null) {
            show(t("Подключаю…", "Ulanmoqda…"), t("Проверяю Telegram", "Telegramni tekshiryapman"), null)
            busy = true
            repeat(8) { if (HealthSync.token(this) == null && !withContext(Dispatchers.IO) { HealthSync.claim(this@MainActivity) }) delay(1500) }
            busy = false
        }
        if (HealthSync.token(this) == null) {
            show("Neon Pulse", t("Сон, восстановление и нагрузка с ваших часов — каждое утро в Telegram.\n\nНажмите кнопку, в Telegram нажмите «Старт» (START) и вернитесь сюда.",
                "Soatingizdan uyqu, tiklanish va yuklama — har tong Telegramda.\n\nTugmani bosing, Telegramda «Старт» (START) ni bosing va bu yerga qayting."), t("Подключить через Telegram", "Telegram orqali ulash")) {
                val n = HealthSync.newNonce(this)
                openUrl("https://t.me/Neonpuls_bot?start=app_$n")
            }
            extra.visibility = View.VISIBLE
            return
        }
        extra.visibility = View.GONE
        // 2. Health Connect
        if (!HealthSync.available(this)) {
            show(t("Нужен Health Connect", "Health Connect kerak"), t("Это бесплатное приложение Google, через него часы делятся данными.", "Bu Google ning bepul ilovasi, soat maʼlumotlarni u orqali ulashadi."), t("Установить Health Connect", "Health Connect ni oʻrnatish")) {
                openUrl("market://details?id=com.google.android.apps.healthdata")
            }
            return
        }
        // 3. Доступ к данным
        if (!hasAccess()) {
            show(t("Последний шаг", "Oxirgi qadam"), t("Разрешите читать данные часов. Нажмите «Разрешить все» — мы только читаем, ничего не меняем.", "Soat maʼlumotlarini oʻqishga ruxsat bering. «Разрешить все» ni bosing — biz faqat oʻqiymiz, hech narsani oʻzgartirmaymiz."), t("Разрешить доступ", "Ruxsat berish")) {
                permLauncher.launch(HealthSync.PERMISSIONS)
            }
            return
        }
        // 4. Готово: отчёт и тренер прямо в приложении, бот — по желанию
        SyncWorker.schedule(this)
        Notifier.channel(this)
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(this) && !getSharedPreferences("neon", MODE_PRIVATE).getBoolean("notif_asked", false)) {
            getSharedPreferences("neon", MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply()
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        show(t("✅ Всё готово", "✅ Hammasi tayyor"), t("Данные отправляются сами. Каждое утро — уведомление с отчётом.", "Maʼlumotlar oʻzi yuboriladi. Har tong — hisobot bilan bildirishnoma.") + "\n\n" + HealthSync.lastStatus(this), t("📊 Открыть Neon", "📊 Neon ni ochish")) {
            startActivity(CoachActivity.open(this, "today"))
        }
        coach.visibility = View.VISIBLE
        second.visibility = View.VISIBLE
    }

    private suspend fun hasAccess(): Boolean {
        val granted = HealthConnectClient.getOrCreate(this).permissionController.getGrantedPermissions()
        return (HealthSync.PERMISSIONS - granted).all { it.contains("BACKGROUND") }
    }

    /** Сразу после разрешения — первая отправка, чтобы человек увидел отчёт в боте */
    private suspend fun afterPermissions() {
        busy = true
        if (!hasAccess()) { busy = false; step(); return }
        show(t("Отправляю данные…", "Maʼlumotlar yuborilmoqda…"), t("Несколько секунд", "Bir necha soniya"), null)
        withContext(Dispatchers.IO) { runCatching { HealthSync.run(this@MainActivity, manual = true) } }
        busy = false
        step()
    }

    // ===== Интерфейс =====
    private fun show(t: String, i: String, btn: String?, on: (() -> Unit)? = null) {
        title.text = t; info.text = i; second.visibility = View.GONE; coach.visibility = View.GONE
        mainBtn.visibility = if (btn == null) View.GONE else View.VISIBLE
        if (btn != null) { mainBtn.text = btn; mainBtn.setOnClickListener { on?.invoke() } }
    }

    private fun openUrl(u: String) = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) {
        Toast.makeText(this, t("Не удалось открыть", "Ochib boʻlmadi"), Toast.LENGTH_SHORT).show()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(72), dp(24), dp(24)); setBackgroundColor(BG)
        }
        root.addView(TextView(this).apply { text = "◉"; setTextColor(NEON); textSize = 64f; gravity = Gravity.CENTER })
        title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        info = TextView(this).apply { setTextColor(DIM); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(24)) }
        mainBtn = Button(this).apply {
            isAllCaps = false; setTextColor(BG); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(NEON); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))
        }
        // Вторая кнопка на экране «Всё готово» — тренер
        coach = Button(this).apply {
            text = t("🏋️ Тренер", "🏋️ Murabbiy"); isAllCaps = false; setTextColor(NEON); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat(); setStroke(dp(1), NEON) }
            layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) }; visibility = View.GONE
            setOnClickListener { startActivity(CoachActivity.open(this@MainActivity, "tech")) }
        }
        // Третья — открыть бота (необязательно: всё есть в приложении)
        second = TextView(this).apply {
            text = t("Открыть бота в Telegram", "Botni Telegramda ochish"); setTextColor(NEON); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)); visibility = View.GONE
            setOnClickListener { openUrl(HealthSync.BOT) }
        }
        root.addView(title); root.addView(info); root.addView(mainBtn); root.addView(coach); root.addView(second)

        // Запасной вариант — ключ вручную (скрыт под ссылкой)
        extra = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(28), 0, 0) }
        val manual = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat() }
        }
        val input = EditText(this).apply {
            hint = t("Ключ из бота", "Botdagi kalit"); setTextColor(Color.WHITE); setHintTextColor(DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        manual.addView(input)
        manual.addView(TextView(this).apply {
            text = t("Сохранить", "Saqlash"); setTextColor(NEON); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                if (HealthSync.validToken(input.text.toString())) { HealthSync.setToken(this@MainActivity, input.text.toString()); lifecycleScope.launch { step() } }
                else Toast.makeText(this@MainActivity, t("Ключ неверный", "Kalit notoʻgʻri"), Toast.LENGTH_SHORT).show()
            }
        })
        extra.addView(TextView(this).apply {
            text = t("Ввести ключ вручную", "Kalitni qoʻlda kiritish"); setTextColor(DIM); textSize = 14f; gravity = Gravity.CENTER
            setOnClickListener { manual.visibility = View.VISIBLE; visibility = View.GONE }
        })
        extra.addView(manual)
        root.addView(extra)
        return ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; addView(root) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
