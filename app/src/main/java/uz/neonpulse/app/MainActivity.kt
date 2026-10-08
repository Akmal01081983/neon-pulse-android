package uz.neonpulse.app

import android.content.Intent
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
    private val NEON = Color.parseColor("#00F5D4")
    private val BG = Color.parseColor("#0B0F14")
    private val CARD = Color.parseColor("#151B23")
    private val DIM = Color.parseColor("#8A94A6")

    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var mainBtn: Button
    private lateinit var extra: LinearLayout
    private var busy = false

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
            show("Подключаю…", "Проверяю Telegram", null)
            busy = true
            repeat(8) { if (HealthSync.token(this) == null && !withContext(Dispatchers.IO) { HealthSync.claim(this@MainActivity) }) delay(1500) }
            busy = false
        }
        if (HealthSync.token(this) == null) {
            show("Neon Pulse", "Сон, восстановление и нагрузка с ваших часов — каждое утро в Telegram.\n\nНажмите кнопку, в Telegram нажмите «Старт» (START) и вернитесь сюда.", "Подключить через Telegram") {
                val n = HealthSync.newNonce(this)
                openUrl("https://t.me/WHOOP_Neon_bot?start=app_$n")
            }
            extra.visibility = View.VISIBLE
            return
        }
        extra.visibility = View.GONE
        // 2. Health Connect
        if (!HealthSync.available(this)) {
            show("Нужен Health Connect", "Это бесплатное приложение Google, через него часы делятся данными.", "Установить Health Connect") {
                openUrl("market://details?id=com.google.android.apps.healthdata")
            }
            return
        }
        // 3. Доступ к данным
        if (!hasAccess()) {
            show("Последний шаг", "Разрешите читать данные часов. Нажмите «Разрешить все» — мы только читаем, ничего не меняем.", "Разрешить доступ") {
                permLauncher.launch(HealthSync.PERMISSIONS)
            }
            return
        }
        // 4. Готово
        SyncWorker.schedule(this)
        show("✅ Всё готово", "Данные отправляются сами. Отчёт приходит в Telegram каждое утро.\n\n" + HealthSync.lastStatus(this), "Открыть бота") {
            openUrl(HealthSync.BOT)
        }
    }

    private suspend fun hasAccess(): Boolean {
        val granted = HealthConnectClient.getOrCreate(this).permissionController.getGrantedPermissions()
        return (HealthSync.PERMISSIONS - granted).all { it.contains("BACKGROUND") }
    }

    /** Сразу после разрешения — первая отправка, чтобы человек увидел отчёт в боте */
    private suspend fun afterPermissions() {
        busy = true
        if (!hasAccess()) { busy = false; step(); return }
        show("Отправляю данные…", "Несколько секунд", null)
        withContext(Dispatchers.IO) { runCatching { HealthSync.run(this@MainActivity, manual = true) } }
        busy = false
        step()
    }

    // ===== Интерфейс =====
    private fun show(t: String, i: String, btn: String?, on: (() -> Unit)? = null) {
        title.text = t; info.text = i
        mainBtn.visibility = if (btn == null) View.GONE else View.VISIBLE
        if (btn != null) { mainBtn.text = btn; mainBtn.setOnClickListener { on?.invoke() } }
    }

    private fun openUrl(u: String) = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) {
        Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show()
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
        root.addView(title); root.addView(info); root.addView(mainBtn)

        // Запасной вариант — ключ вручную (скрыт под ссылкой)
        extra = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(28), 0, 0) }
        val manual = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat() }
        }
        val input = EditText(this).apply {
            hint = "Ключ из бота"; setTextColor(Color.WHITE); setHintTextColor(DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        manual.addView(input)
        manual.addView(TextView(this).apply {
            text = "Сохранить"; setTextColor(NEON); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                if (HealthSync.validToken(input.text.toString())) { HealthSync.setToken(this@MainActivity, input.text.toString()); lifecycleScope.launch { step() } }
                else Toast.makeText(this@MainActivity, "Ключ неверный", Toast.LENGTH_SHORT).show()
            }
        })
        extra.addView(TextView(this).apply {
            text = "Ввести ключ вручную"; setTextColor(DIM); textSize = 14f; gravity = Gravity.CENTER
            setOnClickListener { manual.visibility = View.VISIBLE; visibility = View.GONE }
        })
        extra.addView(manual)
        root.addView(extra)
        return ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; addView(root) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
