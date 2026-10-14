package uz.neonpulse.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Один экран и одна главная кнопка за раз:
 * «Войти через Google» → «Разрешить доступ» к данным часов → готово: отчёт, тренер, чат — всё в приложении.
 * Telegram не нужен; вход через Telegram и привязка бота — по желанию (оплата Stars, отчёт в боте).
 */
class MainActivity : ComponentActivity() {
    /** Текст на языке телефона: русский, узбекский или английский */
    private fun t(ru: String, uz: String, en: String) = HealthSync.tr(this, ru, uz, en)
    private val NEON = Color.parseColor("#00F5D4")
    private val BG = Color.parseColor("#0B0F14")
    private val CARD = Color.parseColor("#151B23")
    private val DIM = Color.parseColor("#8A94A6")
    private val prefs by lazy { getSharedPreferences("neon", MODE_PRIVATE) }

    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var mainBtn: Button
    private lateinit var btn2: Button
    private lateinit var btn3: Button
    private lateinit var link1: TextView
    private lateinit var link2: TextView
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

    /** Определяем, на каком шаге человек, и показываем нужные кнопки */
    private suspend fun step() {
        if (busy) return
        // Вход через Telegram начат: забираем ключ (сервер мог ответить не сразу)
        if (HealthSync.token(this) == null && HealthSync.nonce(this) != null) {
            show(t("Подключаю…", "Ulanmoqda…", "Connecting…"), t("Проверяю Telegram", "Telegramni tekshiryapman", "Checking Telegram"))
            busy = true
            repeat(8) { if (HealthSync.token(this) == null && !withContext(Dispatchers.IO) { HealthSync.claim(this@MainActivity) }) delay(1500) }
            busy = false
        }
        // 1. Вход
        if (HealthSync.token(this) == null) {
            show("Neon Pulse", t(
                "Сон, восстановление, нагрузка, тренер и питание — по данным ваших часов.\n\nВойдите, чтобы начать.",
                "Uyqu, tiklanish, yuklama, murabbiy va ovqatlanish — soatingiz maʼlumotlari asosida.\n\nBoshlash uchun kiring.",
                "Sleep, recovery, strain, a personal trainer and nutrition — from your watch data.\n\nSign in to start."))
            if (BuildConfig.GOOGLE_CLIENT_ID.isNotEmpty()) {
                button(mainBtn, t("Войти через Google", "Google orqali kirish", "Sign in with Google")) { googleSignIn() }
                button(btn2, t("Войти через Telegram", "Telegram orqali kirish", "Sign in with Telegram")) { telegramSignIn() }
            } else {
                // ID клиента Google ещё не задан в сборке — остаётся вход через Telegram
                button(mainBtn, t("Войти через Telegram", "Telegram orqali kirish", "Sign in with Telegram")) { telegramSignIn() }
            }
            textLink(link2, t("Политика конфиденциальности", "Maxfiylik siyosati", "Privacy policy")) { openUrl(privacyUrl()) }
            extra.visibility = View.VISIBLE
            return
        }
        extra.visibility = View.GONE
        val skipWatch = prefs.getBoolean("skip_watch", false)
        // 2. Health Connect (можно пропустить: тренер, питание и чат работают и без часов)
        if (!skipWatch && !HealthSync.available(this)) {
            show(t("Нужен Health Connect", "Health Connect kerak", "Health Connect needed"),
                t("Это бесплатное приложение Google, через него часы делятся данными.", "Bu Google ning bepul ilovasi, soat maʼlumotlarni u orqali ulashadi.",
                    "A free Google app that lets your watch share data."))
            button(mainBtn, t("Установить Health Connect", "Health Connect ni oʻrnatish", "Install Health Connect")) {
                openUrl("market://details?id=com.google.android.apps.healthdata")
            }
            skipLink()
            return
        }
        // 3. Доступ к данным
        if (!skipWatch && !hasAccess()) {
            show(t("Данные часов", "Soat maʼlumotlari", "Watch data"),
                t("Разрешите читать данные часов. Нажмите «Разрешить все» — мы только читаем, ничего не меняем.",
                    "Soat maʼlumotlarini oʻqishga ruxsat bering. «Barchasiga ruxsat» ni bosing — biz faqat oʻqiymiz, hech narsani oʻzgartirmaymiz.",
                    "Allow reading your watch data. Tap “Allow all” — we only read, never change anything."))
            button(mainBtn, t("Разрешить доступ", "Ruxsat berish", "Allow access")) { permLauncher.launch(HealthSync.PERMISSIONS) }
            skipLink()
            return
        }
        // 4. Готово: всё в приложении
        if (!skipWatch) SyncWorker.schedule(this)
        Notifier.channel(this)
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(this) && !prefs.getBoolean("notif_asked", false)) {
            prefs.edit().putBoolean("notif_asked", true).apply()
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        show(t("✅ Всё готово", "✅ Hammasi tayyor", "✅ All set"),
            if (skipWatch) t("Часы не подключены — тренер, питание и чат работают и так.", "Soat ulanmagan — murabbiy, ovqatlanish va chat shundoq ham ishlaydi.",
                "No watch connected — trainer, nutrition and chat still work.")
            else t("Данные отправляются сами. Каждое утро — уведомление с отчётом.", "Maʼlumotlar oʻzi yuboriladi. Har tong — hisobot bilan bildirishnoma.",
                "Data is sent automatically. Every morning you get a report notification.") + "\n\n" + HealthSync.lastStatus(this))
        button(mainBtn, t("📊 Открыть Neon", "📊 Neon ni ochish", "📊 Open Neon")) { startActivity(CoachActivity.open(this, "today")) }
        button(btn2, t("🏋️ Тренер", "🏋️ Murabbiy", "🏋️ Trainer")) { startActivity(CoachActivity.open(this, "tech")) }
        button(btn3, t("💬 Чат и ИИ-помощник", "💬 Chat va AI-yordamchi", "💬 Chat & AI assistant")) { startActivity(CoachActivity.open(this, "chat")) }
        if (skipWatch) textLink(link1, t("⌚ Подключить часы", "⌚ Soatni ulash", "⌚ Connect a watch")) {
            prefs.edit().putBoolean("skip_watch", false).apply(); lifecycleScope.launch { step() }
        }
        textLink(link2, t("⚙️ Аккаунт", "⚙️ Akkaunt", "⚙️ Account")) { accountMenu() }
    }

    /** «Позже» на шагах часов: пользоваться тренером, питанием и чатом без часов */
    private fun skipLink() = textLink(link1, t("Без часов — позже", "Soatsiz — keyinroq", "No watch — later")) {
        prefs.edit().putBoolean("skip_watch", true).apply(); lifecycleScope.launch { step() }
    }

    private suspend fun hasAccess(): Boolean {
        val granted = HealthConnectClient.getOrCreate(this).permissionController.getGrantedPermissions()
        return (HealthSync.PERMISSIONS - granted).all { it.contains("BACKGROUND") }
    }

    /** Сразу после разрешения — первая отправка, чтобы человек сразу увидел отчёт */
    private suspend fun afterPermissions() {
        busy = true
        if (!hasAccess()) { busy = false; step(); return }
        show(t("Отправляю данные…", "Maʼlumotlar yuborilmoqda…", "Sending data…"), t("Несколько секунд", "Bir necha soniya", "A few seconds"))
        withContext(Dispatchers.IO) { runCatching { HealthSync.run(this@MainActivity, manual = true) } }
        busy = false
        step()
    }

    // ===== Вход =====
    /** Вход через Google: системное окно выбора аккаунта → токен → сервер выдаёт ключ приложения */
    private fun googleSignIn() {
        lifecycleScope.launch {
            busy = true
            val req = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID).build())
                .build()
            val err = try {
                val cred = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, req).credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val g = GoogleIdTokenCredential.createFrom(cred.data)
                    show(t("Вход…", "Kirilmoqda…", "Signing in…"), g.id)
                    withContext(Dispatchers.IO) { HealthSync.googleLogin(this@MainActivity, g.idToken, g.givenName ?: g.displayName) }
                } else "error"
            } catch (e: GetCredentialCancellationException) { "cancel" } catch (e: Exception) { "google" }
            busy = false
            when (err) {
                null, "cancel" -> {}
                "network" -> toast(t("Нет интернета — попробуйте ещё раз", "Internet yoʻq — qayta urinib koʻring", "No internet — please try again"))
                "busy" -> toast(t("Этот Google уже привязан к другому аккаунту Neon Pulse", "Bu Google boshqa Neon Pulse akkauntiga ulangan", "This Google account is linked to another Neon Pulse account"))
                else -> toast(t("Не удалось войти через Google", "Google orqali kirib boʻlmadi", "Google sign-in failed"))
            }
            step()
        }
    }

    /** Вход через Telegram (для тех, кто уже пользуется ботом): бот → «Старт» → назад */
    private fun telegramSignIn() {
        val n = HealthSync.newNonce(this)
        openUrl("${HealthSync.BOT}?start=app_$n")
    }

    // ===== Аккаунт =====
    private fun accountMenu() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += t("🔗 Привязать Telegram (оплата, отчёт в боте)", "🔗 Telegramni ulash (toʻlov, botda hisobot)", "🔗 Link Telegram (payments, bot reports)") to { linkTelegram() }
        if (!HealthSync.hasGoogle(this) && BuildConfig.GOOGLE_CLIENT_ID.isNotEmpty())
            items += t("🔐 Привязать Google (вход без Telegram)", "🔐 Google ni ulash (Telegramsiz kirish)", "🔐 Link Google (sign in without Telegram)") to { googleSignIn() }
        items += t("📄 Политика конфиденциальности", "📄 Maxfiylik siyosati", "📄 Privacy policy") to { openUrl(privacyUrl()) }
        items += t("🚪 Выйти", "🚪 Chiqish", "🚪 Sign out") to { confirm(t("Выйти из аккаунта на этом телефоне?", "Bu telefonda akkauntdan chiqilsinmi?", "Sign out on this phone?")) { HealthSync.logout(this); lifecycleScope.launch { step() } } }
        items += t("🗑 Удалить аккаунт", "🗑 Akkauntni oʻchirish", "🗑 Delete account") to { deleteAccount() }
        AlertDialog.Builder(this)
            .setTitle(t("Аккаунт", "Akkaunt", "Account"))
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    /** Привязка Telegram: код с сервера → бот /start link_КОД */
    private fun linkTelegram() {
        lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) { HealthSync.linkCode(this@MainActivity) }
            when {
                code == null -> toast(t("Нет интернета — попробуйте ещё раз", "Internet yoʻq — qayta urinib koʻring", "No internet — please try again"))
                code.isEmpty() -> openUrl(HealthSync.BOT) // уже привязан — просто открываем бота
                else -> openUrl("${HealthSync.BOT}?start=link_$code")
            }
        }
    }

    private fun deleteAccount() = confirm(t(
        "Удалить аккаунт и все данные (отчёты, тренер, питание, переписку)? Это нельзя отменить.",
        "Akkaunt va barcha maʼlumotlar (hisobotlar, murabbiy, ovqatlanish, yozishmalar) oʻchirilsinmi? Buni qaytarib boʻlmaydi.",
        "Delete your account and all data (reports, trainer, nutrition, chats)? This cannot be undone.")) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { HealthSync.deleteAccount(this@MainActivity) }
            toast(if (ok) t("Аккаунт удалён", "Akkaunt oʻchirildi", "Account deleted") else t("Не удалось удалить — проверьте интернет", "Oʻchirib boʻlmadi — internetni tekshiring", "Could not delete — check your internet"))
            step()
        }
    }

    private fun privacyUrl() = HealthSync.PRIVACY + "?l=" + HealthSync.lang(this)

    // ===== Интерфейс =====
    private fun show(t: String, i: String) {
        title.text = t; info.text = i
        for (b in listOf(mainBtn, btn2, btn3)) b.visibility = View.GONE
        for (l in listOf(link1, link2)) l.visibility = View.GONE
    }

    private fun button(b: Button, text: String, on: () -> Unit) { b.text = text; b.visibility = View.VISIBLE; b.setOnClickListener { on() } }
    private fun textLink(l: TextView, text: String, on: () -> Unit) { l.text = text; l.visibility = View.VISIBLE; l.setOnClickListener { on() } }

    private fun confirm(q: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(q)
            .setPositiveButton(t("Да", "Ha", "Yes")) { _, _ -> yes() }
            .setNegativeButton(t("Отмена", "Bekor qilish", "Cancel"), null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun openUrl(u: String) = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) {
        toast(t("Не удалось открыть", "Ochib boʻlmadi", "Could not open"))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(56), dp(24), dp(24)); setBackgroundColor(BG)
        }
        root.addView(TextView(this).apply { text = "◉"; setTextColor(NEON); textSize = 64f; gravity = Gravity.CENTER })
        title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        info = TextView(this).apply { setTextColor(DIM); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(24)) }
        // Главная кнопка — залитая; вторая и третья — контурные
        mainBtn = Button(this).apply {
            isAllCaps = false; setTextColor(BG); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(NEON); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(56))
        }
        fun outline() = Button(this).apply {
            isAllCaps = false; setTextColor(NEON); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat(); setStroke(dp(1), NEON) }
            layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) }; visibility = View.GONE
        }
        btn2 = outline(); btn3 = outline()
        fun linkView(color: Int) = TextView(this).apply {
            setTextColor(color); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)); visibility = View.GONE
        }
        link1 = linkView(NEON); link2 = linkView(DIM)
        for (v in listOf(title, info, mainBtn, btn2, btn3, link1, link2)) root.addView(v)

        // Запасной вариант — ключ вручную (скрыт под ссылкой)
        extra = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(20), 0, 0) }
        val manual = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat() }
        }
        val input = EditText(this).apply {
            hint = t("Ключ из бота", "Botdagi kalit", "Key from the bot"); setTextColor(Color.WHITE); setHintTextColor(DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        manual.addView(input)
        manual.addView(TextView(this).apply {
            text = t("Сохранить", "Saqlash", "Save"); setTextColor(NEON); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                if (HealthSync.validToken(input.text.toString())) { HealthSync.setToken(this@MainActivity, input.text.toString()); lifecycleScope.launch { step() } }
                else toast(t("Ключ неверный", "Kalit notoʻgʻri", "Invalid key"))
            }
        })
        extra.addView(TextView(this).apply {
            text = t("Ввести ключ вручную", "Kalitni qoʻlda kiritish", "Enter key manually"); setTextColor(DIM); textSize = 14f; gravity = Gravity.CENTER
            setOnClickListener { manual.visibility = View.VISIBLE; visibility = View.GONE }
        })
        extra.addView(manual)
        root.addView(extra)
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; addView(root) }
        // Android 15+: отступ от строки состояния и навигации
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); WindowInsetsCompat.CONSUMED
        }
        return scroll
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
