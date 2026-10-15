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
import android.view.Gravity
import android.view.View
import android.widget.Button
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Приложение работает само по себе, без Telegram:
 * «Начать» (аккаунт на этом телефоне) → «Разрешить доступ» к данным часов → отчёт, тренер, питание, чат.
 * Вход через Google — по желанию, чтобы перенести аккаунт на другой телефон.
 * Telegram открывается только для оплаты (Stars): одноразовая ссылка, счёт на этот аккаунт.
 */
class MainActivity : ComponentActivity() {
    /** Текст на языке телефона: русский, узбекский или английский */
    private fun t(ru: String, uz: String, en: String) = HealthSync.tr(this, ru, uz, en)
    private val NEON = Color.parseColor("#00F5D4")
    private val BG = Color.parseColor("#0B0F14")
    private val CARD = Color.parseColor("#151B23")
    private val DIM = Color.parseColor("#8A94A6")
    private val prefs by lazy { getSharedPreferences("neon", MODE_PRIVATE) }
    private val googleOn get() = BuildConfig.GOOGLE_CLIENT_ID.isNotEmpty()

    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var mainBtn: Button
    private lateinit var btn2: Button
    private lateinit var btn3: Button
    private lateinit var link1: TextView
    private lateinit var link2: TextView
    private var busy = false

    // Android 13+: разрешение на уведомления (утренний отчёт и итоги недели приходят прямо на телефон)
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

    /** Ссылка neonpulse://connect?t=КЛЮЧ — подключить телефон к уже существующему аккаунту (со страницы подключения часов) */
    private fun handleLink(i: Intent?) {
        val t = i?.data?.getQueryParameter("t") ?: return
        if (HealthSync.validToken(t)) HealthSync.setToken(this, t)
    }

    /** Определяем, на каком шаге человек, и показываем нужные кнопки */
    private suspend fun step() {
        if (busy) return
        // 1. Нет аккаунта: «Начать» — сразу, без регистрации; Google — вернуть аккаунт с другого телефона
        if (HealthSync.token(this) == null) {
            show("Neon Pulse", t(
                "Сон, восстановление, нагрузка, тренер и питание — по данным ваших часов.",
                "Uyqu, tiklanish, yuklama, murabbiy va ovqatlanish — soatingiz maʼlumotlari asosida.",
                "Sleep, recovery, strain, a personal trainer and nutrition — from your watch data."))
            button(mainBtn, t("Начать", "Boshlash", "Get started")) { start() }
            if (googleOn) button(btn2, t("У меня есть аккаунт — войти через Google", "Akkauntim bor — Google orqali kirish", "I have an account — sign in with Google")) { googleSignIn() }
            textLink(link2, t("Политика конфиденциальности", "Maxfiylik siyosati", "Privacy policy")) { openUrl(privacyUrl()) }
            return
        }
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
        // 4. Готово: всё в приложении. Фоновая работа — отправка данных часов и «входящие» (итоги недели, оплата)
        SyncWorker.schedule(this)
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
        textLink(link2, t("⚙️ Аккаунт и оплата", "⚙️ Akkaunt va toʻlov", "⚙️ Account & payment")) { accountMenu() }
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
    /** «Начать»: аккаунт на этом телефоне, без регистрации */
    private fun start() {
        lifecycleScope.launch {
            busy = true
            show(t("Создаю аккаунт…", "Akkaunt yaratilmoqda…", "Creating your account…"), "")
            val err = withContext(Dispatchers.IO) { HealthSync.register(this@MainActivity) }
            busy = false
            when (err) {
                null -> {}
                "google" -> toast(t("На этом телефоне уже был аккаунт с Google — войдите через Google", "Bu telefonda Google bilan akkaunt bor edi — Google orqali kiring", "This phone already had a Google account — sign in with Google"))
                "network" -> toast(t("Нет интернета — попробуйте ещё раз", "Internet yoʻq — qayta urinib koʻring", "No internet — please try again"))
                else -> toast(t("Не получилось — попробуйте ещё раз", "Boʻlmadi — qayta urinib koʻring", "Didn’t work — please try again"))
            }
            step()
        }
    }

    /** Вход через Google: системное окно выбора аккаунта → токен → сервер выдаёт ключ приложения (или привязывает Google к текущему) */
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
                null -> toast(t("✅ Аккаунт сохранён в Google", "✅ Akkaunt Google da saqlandi", "✅ Account saved to Google"))
                "cancel" -> {}
                "network" -> toast(t("Нет интернета — попробуйте ещё раз", "Internet yoʻq — qayta urinib koʻring", "No internet — please try again"))
                "busy" -> toast(t("Этот Google уже привязан к другому аккаунту Neon Pulse", "Bu Google boshqa Neon Pulse akkauntiga ulangan", "This Google account is linked to another Neon Pulse account"))
                else -> toast(t("Не удалось войти через Google", "Google orqali kirib boʻlmadi", "Google sign-in failed"))
            }
            step()
        }
    }

    // ===== Аккаунт и оплата =====
    private fun accountMenu() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += t("⭐ Оплатить подписку (через Telegram)", "⭐ Obunani toʻlash (Telegram orqali)", "⭐ Pay for a subscription (via Telegram)") to { payMenu() }
        if (!HealthSync.hasGoogle(this) && googleOn)
            items += t("🔐 Сохранить аккаунт через Google (для нового телефона)", "🔐 Akkauntni Google orqali saqlash (yangi telefon uchun)", "🔐 Save account with Google (for a new phone)") to { googleSignIn() }
        items += t("📄 Политика конфиденциальности", "📄 Maxfiylik siyosati", "📄 Privacy policy") to { openUrl(privacyUrl()) }
        items += t("🚪 Выйти", "🚪 Chiqish", "🚪 Sign out") to { signOut() }
        items += t("🗑 Удалить аккаунт", "🗑 Akkauntni oʻchirish", "🗑 Delete account") to { deleteAccount() }
        AlertDialog.Builder(this)
            .setTitle(t("Аккаунт", "Akkaunt", "Account"))
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    /** Оплата — единственный переход в Telegram: бот выставит счёт (Stars) на этот аккаунт, после оплаты — вернуться в приложение */
    private fun payMenu() {
        val opts = listOf(
            "year" to t("📅 Neon Pulse на год", "📅 Neon Pulse bir yilga", "📅 Neon Pulse for a year"),
            "coach_m" to t("🏋️ Тренер на месяц", "🏋️ Murabbiy bir oyga", "🏋️ Trainer for a month"),
            "coach_y" to t("🏋️ Тренер на год", "🏋️ Murabbiy bir yilga", "🏋️ Trainer for a year"),
        )
        AlertDialog.Builder(this)
            .setTitle(t("Оплата в Telegram (Stars)", "Telegramda toʻlov (Stars)", "Payment in Telegram (Stars)"))
            .setItems(opts.map { it.second }.toTypedArray()) { _, i -> pay(opts[i].first) }
            .show()
    }

    private fun pay(product: String) {
        lifecycleScope.launch {
            val url = withContext(Dispatchers.IO) { HealthSync.payUrl(this@MainActivity, product) }
            if (url == null) toast(t("Нет интернета — попробуйте ещё раз", "Internet yoʻq — qayta urinib koʻring", "No internet — please try again"))
            else openUrl(url)
        }
    }

    private fun signOut() = confirm(
        if (HealthSync.hasGoogle(this)) t("Выйти из аккаунта на этом телефоне? Вернуться можно через Google.", "Bu telefonda akkauntdan chiqilsinmi? Google orqali qaytish mumkin.", "Sign out on this phone? You can come back with Google.")
        else t("Выйти? Аккаунт не сохранён в Google — вернуться можно только на этом телефоне кнопкой «Начать».", "Chiqilsinmi? Akkaunt Google da saqlanmagan — faqat shu telefonda «Boshlash» orqali qaytish mumkin.", "Sign out? Your account isn’t saved to Google — you can only come back on this phone with “Get started”.")
    ) { HealthSync.logout(this); lifecycleScope.launch { step() } }

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
            isAllCaps = false; setTextColor(NEON); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(14).toFloat(); setStroke(dp(1), NEON) }
            layoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
            minHeight = dp(56); visibility = View.GONE
        }
        btn2 = outline(); btn3 = outline()
        fun linkView(color: Int) = TextView(this).apply {
            setTextColor(color); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)); visibility = View.GONE
        }
        link1 = linkView(NEON); link2 = linkView(DIM)
        for (v in listOf(title, info, mainBtn, btn2, btn3, link1, link2)) root.addView(v)
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
