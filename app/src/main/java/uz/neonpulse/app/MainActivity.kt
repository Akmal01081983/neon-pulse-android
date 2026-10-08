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
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Один экран: ключ из бота → доступ к данным → автоматическая отправка каждый час. */
class MainActivity : ComponentActivity() {
    private val NEON = Color.parseColor("#00F5D4")
    private val BG = Color.parseColor("#0B0F14")
    private val CARD = Color.parseColor("#151B23")
    private val DIM = Color.parseColor("#8A94A6")

    private lateinit var stepKey: TextView
    private lateinit var stepPerm: TextView
    private lateinit var status: TextView
    private lateinit var keyInput: EditText
    private lateinit var permBtn: Button
    private lateinit var syncBtn: Button

    private val permContract: ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()
    private val permLauncher = registerForActivityResult(permContract) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handleLink(intent)
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    /** Ссылка из бота: neonpulse://connect?t=КЛЮЧ */
    private fun handleLink(i: Intent?) {
        val t = i?.data?.getQueryParameter("t") ?: return
        if (HealthSync.validToken(t)) {
            HealthSync.setToken(this, t)
            Toast.makeText(this, "Ключ сохранён ✅", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refresh() {
        val hasKey = HealthSync.token(this) != null
        stepKey.text = if (hasKey) "1. Ключ из бота — ✅ есть" else "1. Ключ из бота — нет"
        keyInput.visibility = if (hasKey) View.GONE else View.VISIBLE
        status.text = "Последняя отправка: " + HealthSync.lastStatus(this)
        if (!HealthSync.available(this)) {
            stepPerm.text = "2. Health Connect не установлен"
            permBtn.text = "Установить Health Connect"
            syncBtn.isEnabled = false
            return
        }
        lifecycleScope.launch {
            val granted = HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
            val missing = HealthSync.PERMISSIONS - granted
            val okMain = missing.isEmpty() || missing.all { it.contains("BACKGROUND") }
            stepPerm.text = if (okMain) "2. Доступ к данным — ✅ есть" else "2. Доступ к данным — нужно разрешить"
            permBtn.text = if (okMain) "Проверить доступ" else "Разрешить доступ"
            syncBtn.isEnabled = hasKey && okMain
            if (hasKey && okMain) SyncWorker.schedule(this@MainActivity)
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(24))
            setBackgroundColor(BG)
        }
        root.addView(TextView(this).apply {
            text = "Neon Pulse"; setTextColor(NEON); textSize = 30f; typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Отправляет данные с часов в бот: сон, пульс, ВСР, шаги, давление. Работает само — раз в час."
            setTextColor(DIM); textSize = 15f; setPadding(0, dp(6), 0, dp(20))
        })

        // Шаг 1 — ключ
        val c1 = card()
        stepKey = title(); c1.addView(stepKey)
        c1.addView(text("В боте нажмите «📲 Подключить Android» — ключ вставится сам. Или вставьте его сюда:"))
        keyInput = EditText(this).apply {
            hint = "Вставьте ключ из бота"; setTextColor(Color.WHITE); setHintTextColor(DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        c1.addView(keyInput)
        c1.addView(button("Сохранить ключ") {
            val t = keyInput.text.toString()
            if (HealthSync.validToken(t)) { HealthSync.setToken(this, t); keyInput.setText(""); refresh() }
            else Toast.makeText(this, "Ключ неверный — скопируйте из бота целиком", Toast.LENGTH_LONG).show()
        })
        c1.addView(button("Открыть бота") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HealthSync.BOT))) })
        root.addView(c1)

        // Шаг 2 — доступ
        val c2 = card()
        stepPerm = title(); c2.addView(stepPerm)
        c2.addView(text("Включите «Разрешить все». Мы только читаем данные — ничего не меняем."))
        permBtn = button("Разрешить доступ") {
            if (HealthSync.available(this)) permLauncher.launch(HealthSync.PERMISSIONS)
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
        }
        c2.addView(permBtn)
        root.addView(c2)

        // Шаг 3 — отправка
        val c3 = card()
        c3.addView(title().apply { text = "3. Отправка" })
        status = text(""); c3.addView(status)
        syncBtn = button("Отправить сейчас") {
            syncBtn.isEnabled = false; status.text = "Отправляю…"
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { HealthSync.run(this@MainActivity, manual = true) }.getOrDefault(false) }
                Toast.makeText(this@MainActivity, if (ok) "Готово — смотрите бота" else "Не получилось", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        c3.addView(syncBtn)
        root.addView(c3)

        return ScrollView(this).apply { setBackgroundColor(BG); addView(root) }
    }

    // ===== Маленькие помощники для интерфейса =====
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(16).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    }
    private fun title() = TextView(this).apply { setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD }
    private fun text(s: String) = TextView(this).apply { text = s; setTextColor(DIM); textSize = 14f; setPadding(0, dp(4), 0, dp(8)) }
    private fun button(s: String, on: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(BG); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(NEON); cornerRadius = dp(12).toFloat() }
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }
        setOnClickListener { on() }
    }
}
