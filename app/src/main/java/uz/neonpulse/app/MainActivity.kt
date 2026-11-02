package uz.neonpulse.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import uz.neonpulse.app.ui.BenefitRow
import uz.neonpulse.app.ui.HeroRing
import uz.neonpulse.app.ui.IcChat
import uz.neonpulse.app.ui.IcCheck
import uz.neonpulse.app.ui.IcDumbbell
import uz.neonpulse.app.ui.IcHeart
import uz.neonpulse.app.ui.IcMoon
import uz.neonpulse.app.ui.IcRing
import uz.neonpulse.app.ui.IcShield
import uz.neonpulse.app.ui.IcWatch
import uz.neonpulse.app.ui.LangChip
import uz.neonpulse.app.ui.LinkButton
import uz.neonpulse.app.ui.Logo
import uz.neonpulse.app.ui.Neon
import uz.neonpulse.app.ui.NeonTheme
import uz.neonpulse.app.ui.NoticeCard
import uz.neonpulse.app.ui.PrimaryButton
import uz.neonpulse.app.ui.SecondaryButton
import uz.neonpulse.app.ui.SheetItem
import uz.neonpulse.app.ui.StepDots
import uz.neonpulse.app.ui.Tile

/**
 * Первые экраны приложения (свои, не с сервера), в стиле экранов Neon:
 * 1) вход — Google или «Начать без входа»; 2) Health Connect; 3) доступ к данным часов; 4) «Всё готово».
 * Всё остальное (Сегодня, Тренды, Тренер, Чат) — страница Neon в CoachActivity.
 * Telegram открывается только для оплаты (Stars).
 */
class MainActivity : ComponentActivity() {

    /** На каком шаге человек */
    private enum class Stage { LOADING, WELCOME, HEALTH_CONNECT, ACCESS, READY }

    /** Что показывать на экране «Всё готово» */
    private data class Ready(
        val watch: Boolean,          // часы подключены (не «позже»)
        val partial: Boolean,        // часть данных закрыта
        val background: Boolean,     // чтение в фоне разрешено
        val notifications: Boolean,  // уведомления разрешены
        val google: Boolean,         // аккаунт сохранён в Google
        val status: String,          // итог последней отправки
    )

    private enum class Sheet { NONE, LANG, ACCOUNT, PAY }

    private val prefs by lazy { getSharedPreferences("neon", MODE_PRIVATE) }
    private val googleOn get() = BuildConfig.GOOGLE_CLIENT_ID.isNotEmpty()

    // Состояние экрана
    private var stage by mutableStateOf(Stage.LOADING)
    private var lang by mutableStateOf("ru")
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var hc by mutableStateOf(HealthSync.Hc.OK)
    private var denied by mutableIntStateOf(0)
    private var ready by mutableStateOf<Ready?>(null)
    private var sheet by mutableStateOf(Sheet.NONE)
    private var confirm by mutableStateOf<Pair<String, () -> Unit>?>(null)

    /** Текст на выбранном языке */
    private fun t(ru: String, uz: String, en: String) = when (lang) { "uz" -> uz; "en" -> en; else -> ru }

    // Android 13+: разрешение на уведомления (утренний отчёт и итоги недели)
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { lifecycleScope.launch { step() } }

    // Окно Health Connect: человек выбирает, какие данные разрешить
    private val permLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        lifecycleScope.launch { afterPermissions(got) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lang = HealthSync.lang(this)
        denied = prefs.getInt("hc_denied", 0)
        handleLink(intent)
        setContent { NeonTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleLink(intent) }

    // Вернулись из настроек Health Connect или из Telegram — пересчитываем шаг
    override fun onResume() { super.onResume(); lifecycleScope.launch { step() } }

    /** Ссылка neonpulse://connect?t=КЛЮЧ — подключить телефон к уже существующему аккаунту */
    private fun handleLink(i: Intent?) {
        val t = i?.data?.getQueryParameter("t") ?: return
        if (HealthSync.validToken(t)) HealthSync.setToken(this, t)
    }

    // ===================== Логика шагов =====================

    private suspend fun granted(): Set<String> = try {
        HealthConnectClient.getOrCreate(this).permissionController.getGrantedPermissions()
    } catch (e: Exception) { emptySet() }

    /** Определяем шаг заново (после входа, разрешений, возврата из настроек) */
    private suspend fun step() {
        if (busy) return
        if (HealthSync.token(this) == null) { stage = Stage.WELCOME; return }
        val skip = prefs.getBoolean("skip_watch", false)
        hc = HealthSync.hcStatus(this)
        if (!skip && hc != HealthSync.Hc.OK) { stage = Stage.HEALTH_CONNECT; return }
        val g = if (hc == HealthSync.Hc.OK) granted() else emptySet()
        if (!skip && !g.containsAll(HealthSync.CORE)) { stage = Stage.ACCESS; return }
        // Готово: фоновая отправка данных и «входящие»
        SyncWorker.schedule(this)
        Notifier.channel(this)
        val all = HealthSync.PERMISSIONS - HealthSync.BACKGROUND_PERMISSION
        ready = Ready(
            watch = !skip,
            partial = !skip && !g.containsAll(all),
            background = skip || HealthSync.BACKGROUND_PERMISSION in g,
            notifications = Notifier.allowed(this),
            google = HealthSync.hasGoogle(this),
            status = HealthSync.lastStatus(this),
        )
        stage = Stage.READY
        // Уведомления спрашиваем один раз, сразу после подключения
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(this) && !prefs.getBoolean("notif_asked", false)) {
            prefs.edit().putBoolean("notif_asked", true).apply()
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** «Начать без входа»: аккаунт на этом телефоне за секунду (потом можно сохранить в Google) */
    private fun start() = lifecycleScope.launch {
        busy = true; error = null
        val err = withContext(Dispatchers.IO) { HealthSync.register(this@MainActivity) }
        busy = false
        error = when (err) {
            null -> null
            "google" -> t("Этот телефон уже входил через Google — войдите через Google.", "Bu telefon avval Google orqali kirgan — Google orqali kiring.", "This phone signed in with Google before — please sign in with Google.")
            "network" -> t("Нет интернета. Проверьте связь и нажмите ещё раз.", "Internet yoʻq. Aloqani tekshiring va qayta bosing.", "No internet. Check your connection and tap again.")
            else -> t("Сервер не ответил. Попробуйте через минуту.", "Server javob bermadi. Bir daqiqadan soʻng urinib koʻring.", "The server didn’t respond. Try again in a minute.")
        }
        step()
    }

    /** Вход через Google: системное окно выбора аккаунта → сервер выдаёт ключ (или привязывает Google к текущему аккаунту) */
    private fun googleSignIn() = lifecycleScope.launch {
        busy = true; error = null
        val req = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID).build())
            .build()
        val err = try {
            val cred = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, req).credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val g = GoogleIdTokenCredential.createFrom(cred.data)
                withContext(Dispatchers.IO) { HealthSync.googleLogin(this@MainActivity, g.idToken, g.givenName ?: g.displayName) }
            } else "error"
        } catch (e: GetCredentialCancellationException) { "cancel" } catch (e: Exception) { "google" }
        busy = false
        when (err) {
            null -> toast(t("Готово: аккаунт сохранён в Google", "Tayyor: akkaunt Google da saqlandi", "Done: your account is saved to Google"))
            "cancel" -> {}
            "network" -> error = t("Нет интернета. Проверьте связь и нажмите ещё раз.", "Internet yoʻq. Aloqani tekshiring va qayta bosing.", "No internet. Check your connection and tap again.")
            "busy" -> error = t("Этот Google уже привязан к другому аккаунту Neon Pulse.", "Bu Google boshqa Neon Pulse akkauntiga ulangan.", "This Google account is linked to another Neon Pulse account.")
            else -> error = t("Не удалось войти через Google. Попробуйте ещё раз или начните без входа.", "Google orqali kirib boʻlmadi. Qayta urinib koʻring yoki kirishsiz boshlang.", "Google sign-in failed. Try again or start without signing in.")
        }
        step()
    }

    /** Ответ окна Health Connect: всё нужное — сразу первая отправка; нет — объясняем, как исправить */
    private suspend fun afterPermissions(got: Set<String>) {
        if (!got.containsAll(HealthSync.CORE) && !granted().containsAll(HealthSync.CORE)) {
            denied += 1; prefs.edit().putInt("hc_denied", denied).apply()
            step(); return
        }
        denied = 0; prefs.edit().putInt("hc_denied", 0).apply()
        busy = true
        withContext(Dispatchers.IO) { runCatching { HealthSync.run(this@MainActivity, manual = true) } }
        busy = false
        step()
    }

    /** Отправить данные сейчас (кнопка «Обновить» на экране «Всё готово») */
    private fun syncNow() = lifecycleScope.launch {
        busy = true
        withContext(Dispatchers.IO) { runCatching { HealthSync.run(this@MainActivity, manual = true) } }
        busy = false
        step()
    }

    /** Язык: сразу на экране, на сервере — в фоне */
    private fun chooseLang(l: String) {
        lang = l; sheet = Sheet.NONE
        lifecycleScope.launch(Dispatchers.IO) { runCatching { HealthSync.setLang(this@MainActivity, l) } }
    }

    private fun pay(product: String) = lifecycleScope.launch {
        sheet = Sheet.NONE
        val url = withContext(Dispatchers.IO) { HealthSync.payUrl(this@MainActivity, product) }
        if (url == null) toast(t("Нет интернета — попробуйте ещё раз", "Internet yoʻq — qayta urinib koʻring", "No internet — please try again")) else openUrl(url)
    }

    private fun signOut() {
        HealthSync.logout(this)
        prefs.edit().putString("lang", lang).apply() // язык оставляем
        lifecycleScope.launch { step() }
    }

    private fun deleteAccount() = lifecycleScope.launch {
        val ok = withContext(Dispatchers.IO) { HealthSync.deleteAccount(this@MainActivity) }
        toast(if (ok) t("Аккаунт удалён", "Akkaunt oʻchirildi", "Account deleted") else t("Не удалось удалить — проверьте интернет", "Oʻchirib boʻlmadi — internetni tekshiring", "Could not delete — check your internet"))
        step()
    }

    /** Установить или обновить Health Connect (Google Play) */
    private fun openHcStore() = openUrl("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")

    /**
     * Настройки разрешений Health Connect для этого приложения.
     * После двух отказов Android больше не показывает окно — исправить можно только здесь.
     */
    private fun openHcSettings() {
        val tries = listOf(
            Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, packageName), // Android 14+
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),                                                          // приложение Health Connect
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
        for (i in tries) if (runCatching { startActivity(i) }.isSuccess) return
        toast(t("Откройте Health Connect → Разрешения приложений → Neon Pulse", "Health Connect → Ilovalar ruxsatlari → Neon Pulse ni oching", "Open Health Connect → App permissions → Neon Pulse"))
    }

    /** Уведомления выключены — открываем настройки уведомлений приложения */
    private fun openNotifSettings() {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        if (runCatching { startActivity(i) }.isFailure) openUrl("package:$packageName")
    }

    private fun privacyUrl() = HealthSync.PRIVACY + "?l=" + lang

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { toast(t("Не удалось открыть", "Ochib boʻlmadi", "Could not open")) }
    }

    // ===================== Экраны =====================

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(Neon.Bg)) {
            AnimatedContent(targetState = stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stage") { s ->
                Column(
                    Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TopBar()
                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                        when (s) {
                            Stage.LOADING -> Spacer(Modifier.height(1.dp))
                            Stage.WELCOME -> Welcome()
                            Stage.HEALTH_CONNECT -> HealthConnectStep()
                            Stage.ACCESS -> AccessStep()
                            Stage.READY -> ReadyStep()
                        }
                    }
                }
            }
            Sheets()
            confirm?.let { (q, yes) ->
                AlertDialog(
                    onDismissRequest = { confirm = null },
                    containerColor = Neon.Surface, titleContentColor = Neon.Text, textContentColor = Neon.Soft,
                    text = { Text(q, style = MaterialTheme.typography.bodyLarge) },
                    confirmButton = { TextButton(onClick = { confirm = null; yes() }) { Text(t("Да", "Ha", "Yes"), color = Neon.Red) } },
                    dismissButton = { TextButton(onClick = { confirm = null }) { Text(t("Отмена", "Bekor qilish", "Cancel"), color = Neon.Text) } },
                )
            }
        }
    }

    /** Шапка: логотип и язык — на всех экранах, как на страницах Neon */
    @Composable
    private fun TopBar() {
        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo()
            Spacer(Modifier.weight(1f))
            LangChip(lang, { sheet = Sheet.LANG }, t("Язык", "Til", "Language"))
        }
    }

    @Composable
    private fun Title(text: String, sub: String) {
        Text(text, style = MaterialTheme.typography.headlineLarge, color = Neon.Text, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(sub, style = MaterialTheme.typography.bodyLarge, color = Neon.Soft, modifier = Modifier.fillMaxWidth())
    }

    @Composable
    private fun ErrorLine() {
        val e = error ?: return
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Neon.Red.copy(alpha = 0.12f)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Neon.Red))
            Spacer(Modifier.size(10.dp))
            Text(e, color = Neon.Text, style = MaterialTheme.typography.bodyMedium)
        }
    }

    /** Шаг 1: вход */
    @Composable
    private fun Welcome() {
        Spacer(Modifier.height(18.dp))
        StepDots(0, 3)
        Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) { HeroRing() }
        Title(t("Тело на зарядке", "Tana quvvatda", "Your body, charged"),
            t("Сон, восстановление и тренировки — по данным ваших часов. Каждое утро понятно, как тренироваться сегодня.",
                "Uyqu, tiklanish va mashgʻulotlar — soatingiz maʼlumotlari asosida. Har tong bugun qanday mashq qilish aniq.",
                "Sleep, recovery and workouts from your watch data. Every morning you know how hard to train today."))
        Spacer(Modifier.height(18.dp))
        BenefitRow(t("Заряд организма и совет на день", "Organizm quvvati va kunlik maslahat", "Recovery score and a tip for the day"), IcRing)
        BenefitRow(t("Тренер подбирает план под восстановление", "Murabbiy rejani tiklanishga moslaydi", "A trainer that adapts to your recovery"), IcDumbbell)
        BenefitRow(t("ИИ-помощник и питание по фото", "AI-yordamchi va surat orqali ovqatlanish", "AI assistant and food log by photo"), IcChat)
        Spacer(Modifier.height(22.dp))
        if (googleOn) {
            PrimaryButton(t("Войти через Google", "Google orqali kirish", "Continue with Google"), { googleSignIn() }, loading = busy)
            Spacer(Modifier.height(10.dp))
            SecondaryButton(t("Начать без входа", "Kirishsiz boshlash", "Start without signing in"), { start() }, enabled = !busy)
            Spacer(Modifier.height(10.dp))
            Text(t("С Google прогресс сохраняется при смене телефона. Без входа — только на этом телефоне, сохранить можно позже.",
                "Google bilan telefon almashtirilganda ham rivoj saqlanadi. Kirishsiz — faqat shu telefonda, keyinroq saqlash mumkin.",
                "With Google your progress moves to a new phone. Without it, progress stays on this phone — you can save it later."),
                color = Neon.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            PrimaryButton(t("Начать", "Boshlash", "Get started"), { start() }, loading = busy)
            Spacer(Modifier.height(10.dp))
            Text(t("Без регистрации — аккаунт создаётся за секунду.", "Roʻyxatdan oʻtmasdan — akkaunt bir soniyada yaratiladi.", "No sign-up — your account is ready in a second."),
                color = Neon.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        ErrorLine()
        Spacer(Modifier.height(8.dp))
        LinkButton(t("Политика конфиденциальности", "Maxfiylik siyosati", "Privacy policy"), { openUrl(privacyUrl()) })
    }

    /** Шаг 2: Health Connect — установить или обновить */
    @Composable
    private fun HealthConnectStep() {
        val update = hc == HealthSync.Hc.UPDATE
        Spacer(Modifier.height(18.dp))
        StepDots(1, 3)
        Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) { HeroRing(fill = 0.45f, icon = centerIcon(IcWatch)) }
        Title(if (update) t("Обновите Health Connect", "Health Connect ni yangilang", "Update Health Connect") else t("Подключим часы", "Soatni ulaymiz", "Let’s connect your watch"),
            t("Часы передают пульс и сон через Health Connect — бесплатное приложение Google. Через него шаги, сон и калории считаются сами.",
                "Soat puls va uyquni Health Connect orqali uzatadi — bu Google ning bepul ilovasi. Shu orqali qadamlar, uyqu va kaloriyalar oʻzi hisoblanadi.",
                "Your watch shares heart rate and sleep through Health Connect, a free Google app. That’s how steps, sleep and calories are counted automatically."))
        Spacer(Modifier.height(18.dp))
        BenefitRow(t("Samsung, Xiaomi, Huawei, Honor, Pixel и другие", "Samsung, Xiaomi, Huawei, Honor, Pixel va boshqalar", "Samsung, Xiaomi, Huawei, Honor, Pixel and more"), IcWatch)
        BenefitRow(t("Только чтение — ничего не меняем и не продаём", "Faqat oʻqish — hech narsani oʻzgartirmaymiz va sotmaymiz", "Read-only — we never change or sell your data"), IcShield)
        Spacer(Modifier.height(22.dp))
        PrimaryButton(if (update) t("Обновить в Google Play", "Google Play da yangilash", "Update on Google Play") else t("Установить из Google Play", "Google Play dan oʻrnatish", "Install from Google Play"), { openHcStore() })
        Spacer(Modifier.height(10.dp))
        Text(t("После установки вернитесь сюда — продолжим автоматически.", "Oʻrnatgandan soʻng bu yerga qayting — avtomatik davom etamiz.", "Come back here after installing — we’ll continue automatically."),
            color = Neon.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SkipWatch()
    }

    /** Шаг 3: доступ к данным — зачем и как исправить отказ */
    @Composable
    private fun AccessStep() {
        Spacer(Modifier.height(18.dp))
        StepDots(2, 3)
        Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) { HeroRing(fill = 0.6f, icon = centerIcon(IcHeart)) }
        Title(t("Разрешите читать данные", "Maʼlumotlarni oʻqishga ruxsat bering", "Allow reading your data"),
            t("В следующем окне нажмите «Разрешить все». Это нужно, чтобы отчёт и тренер работали по вашим данным.",
                "Keyingi oynada «Barchasiga ruxsat» ni bosing. Bu hisobot va murabbiy maʼlumotlaringiz asosida ishlashi uchun kerak.",
                "In the next window tap “Allow all”. This lets the report and trainer work from your own data."))
        Spacer(Modifier.height(18.dp))
        BenefitRow(t("Сон — чтобы посчитать восстановление", "Uyqu — tiklanishni hisoblash uchun", "Sleep — to calculate recovery"), IcMoon)
        BenefitRow(t("Пульс и HRV — заряд организма", "Puls va HRV — organizm quvvati", "Heart rate and HRV — your recovery score"), IcHeart)
        BenefitRow(t("Шаги и калории — нагрузка за день", "Qadamlar va kaloriya — kunlik yuklama", "Steps and calories — daily strain"), IcRing)
        BenefitRow(t("Только чтение — ничего не меняем и не продаём", "Faqat oʻqish — hech narsani oʻzgartirmaymiz va sotmaymiz", "Read-only — we never change or sell your data"), IcShield)
        if (denied > 0) {
            Spacer(Modifier.height(16.dp))
            NoticeCard(
                t("Доступ не выдан", "Ruxsat berilmadi", "Access not granted"),
                if (denied >= 2) t("Android больше не показывает окно. Откройте настройки Health Connect → Neon Pulse → включите «Разрешить все».",
                    "Android oynani boshqa koʻrsatmaydi. Health Connect sozlamalari → Neon Pulse → «Barchasiga ruxsat» ni yoqing.",
                    "Android won’t show the window again. Open Health Connect settings → Neon Pulse → turn on “Allow all”.")
                else t("Без сна, пульса и шагов отчёт не посчитать. Нажмите ещё раз и выберите «Разрешить все».",
                    "Uyqu, puls va qadamlarsiz hisobotni hisoblab boʻlmaydi. Yana bosing va «Barchasiga ruxsat» ni tanlang.",
                    "Without sleep, heart rate and steps we can’t build your report. Tap again and choose “Allow all”."),
            )
        }
        Spacer(Modifier.height(22.dp))
        if (denied >= 2) PrimaryButton(t("Открыть настройки Health Connect", "Health Connect sozlamalarini ochish", "Open Health Connect settings"), { openHcSettings() })
        else PrimaryButton(t("Разрешить доступ", "Ruxsat berish", "Allow access"), { permLauncher.launch(HealthSync.PERMISSIONS) }, loading = busy)
        if (denied >= 2) { Spacer(Modifier.height(10.dp)); SecondaryButton(t("Попробовать окно ещё раз", "Oynani yana ochish", "Try the window again"), { permLauncher.launch(HealthSync.PERMISSIONS) }) }
        Spacer(Modifier.height(8.dp))
        SkipWatch()
    }

    @Composable
    private fun SkipWatch() = LinkButton(t("Без часов — подключу позже", "Soatsiz — keyinroq ulayman", "No watch — I’ll connect later"), {
        prefs.edit().putBoolean("skip_watch", true).apply(); lifecycleScope.launch { step() }
    })

    /** Шаг 4: всё готово — куда идти дальше и что ещё стоит включить */
    @Composable
    private fun ReadyStep() {
        val r = ready ?: return
        Spacer(Modifier.height(18.dp))
        Text(t("Всё готово", "Hammasi tayyor", "You’re all set"), style = MaterialTheme.typography.headlineLarge, color = Neon.Text)
        Spacer(Modifier.height(8.dp))
        Text(if (r.watch) t("Данные с часов приходят сами. Каждое утро — уведомление с отчётом.", "Soat maʼlumotlari oʻzi keladi. Har tong — hisobot bilan bildirishnoma.", "Watch data syncs on its own. Every morning you get a report.")
            else t("Тренер, питание и чат работают и без часов.", "Murabbiy, ovqatlanish va chat soatsiz ham ishlaydi.", "Trainer, nutrition and chat work without a watch too."),
            style = MaterialTheme.typography.bodyLarge, color = Neon.Soft)
        if (r.watch) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Neon.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val ok = r.status.startsWith("✅")
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Neon.Green else Neon.Amber))
                Spacer(Modifier.size(10.dp))
                Text(r.status.removePrefix("✅ ").removePrefix("⚠️ "), color = Neon.Soft, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { syncNow() }, enabled = !busy) { Text(if (busy) "…" else t("Обновить", "Yangilash", "Sync"), color = Neon.Cyan, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Tile(t("Сегодня", "Bugun", "Today"), t("Заряд, сон и нагрузка", "Quvvat, uyqu va yuklama", "Recovery, sleep and strain"),
            { startActivity(CoachActivity.open(this@MainActivity, "today")) }, Modifier.fillMaxWidth(), icon = IcRing)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Tile(t("Тренер", "Murabbiy", "Trainer"), t("План и подходы", "Reja va yondashuvlar", "Plan and sets"),
                { startActivity(CoachActivity.open(this@MainActivity, "tech")) }, Modifier.weight(1f), accent = Neon.Green, icon = IcDumbbell)
            Tile(t("Чат", "Chat", "Chat"), t("Вопросы и еда", "Savollar va ovqat", "Questions and food"),
                { startActivity(CoachActivity.open(this@MainActivity, "chat")) }, Modifier.weight(1f), accent = Neon.Pink, icon = IcChat)
        }
        // Что ещё стоит включить — по одной карточке, с кнопкой «как исправить»
        if (googleOn && !r.google) {
            Spacer(Modifier.height(12.dp))
            NoticeCard(t("Сохраните прогресс", "Rivojni saqlang", "Save your progress"),
                t("Войдите через Google — при смене телефона ничего не потеряется.", "Google orqali kiring — telefon almashtirilganda hech narsa yoʻqolmaydi.", "Sign in with Google so nothing is lost if you change phones."),
                t("Сохранить через Google", "Google orqali saqlash", "Save with Google"), { googleSignIn() }, tone = Neon.Cyan)
        }
        if (r.watch && !r.notifications) {
            Spacer(Modifier.height(12.dp))
            NoticeCard(t("Уведомления выключены", "Bildirishnomalar oʻchiq", "Notifications are off"),
                t("Утренний отчёт не придёт на экран телефона.", "Ertalabki hisobot telefon ekraniga kelmaydi.", "The morning report won’t appear on your phone."),
                t("Включить уведомления", "Bildirishnomalarni yoqish", "Turn on notifications"), { openNotifSettings() })
        }
        if (r.watch && !r.background) {
            Spacer(Modifier.height(12.dp))
            NoticeCard(t("Отчёт только при открытии", "Hisobot faqat ochilganda", "Report only when opened"),
                t("Разрешите Health Connect «чтение в фоне» — отчёт будет готов утром сам.", "Health Connect da «fonda oʻqish» ga ruxsat bering — hisobot ertalab oʻzi tayyor boʻladi.", "Allow “background read” in Health Connect so the report is ready by itself each morning."),
                t("Открыть настройки", "Sozlamalarni ochish", "Open settings"), { openHcSettings() })
        } else if (r.partial) {
            Spacer(Modifier.height(12.dp))
            NoticeCard(t("Часть данных закрыта", "Maʼlumotlarning bir qismi yopiq", "Some data is off"),
                t("Отчёт работает, но давление, вес или кислород не попадут в него.", "Hisobot ishlaydi, lekin bosim, vazn yoki kislorod kirmaydi.", "The report works, but blood pressure, weight or oxygen won’t be included."),
                t("Открыть настройки", "Sozlamalarni ochish", "Open settings"), { openHcSettings() }, tone = Neon.Muted)
        }
        if (!r.watch) {
            Spacer(Modifier.height(12.dp))
            NoticeCard(t("Часы не подключены", "Soat ulanmagan", "No watch connected"),
                t("С часами появятся заряд, сон и нагрузка.", "Soat bilan quvvat, uyqu va yuklama paydo boʻladi.", "With a watch you get recovery, sleep and strain."),
                t("Подключить часы", "Soatni ulash", "Connect a watch"), {
                    prefs.edit().putBoolean("skip_watch", false).apply(); lifecycleScope.launch { step() }
                }, tone = Neon.Cyan)
        }
        Spacer(Modifier.height(10.dp))
        LinkButton(t("Аккаунт и оплата", "Akkaunt va toʻlov", "Account & payment"), { sheet = Sheet.ACCOUNT }, color = Neon.Soft)
    }

    /** Значок в центре кольца */
    private fun centerIcon(icon: DrawScope.(Color) -> Unit): DrawScope.(Color) -> Unit = { c ->
        val s = size.minDimension * 0.28f
        val k = s / size.minDimension
        withTransform({
            translate(left = center.x - s / 2, top = center.y - s / 2)
            scale(scaleX = k, scaleY = k, pivot = Offset.Zero)
        }) { icon(c) }
    }

    /** Нижние меню: язык, аккаунт, оплата */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Sheets() {
        if (sheet == Sheet.NONE) return
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }, sheetState = state, containerColor = Neon.Surface, contentColor = Neon.Text) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
                when (sheet) {
                    Sheet.LANG -> {
                        SheetTitle(t("Язык", "Til", "Language"))
                        for ((code, name) in listOf("ru" to "Русский", "uz" to "Oʻzbekcha", "en" to "English"))
                            SheetItem(name, { chooseLang(code) }, selected = code == lang)
                    }
                    Sheet.ACCOUNT -> {
                        SheetTitle(t("Аккаунт", "Akkaunt", "Account"))
                        SheetItem(t("Оплатить подписку (Telegram)", "Obunani toʻlash (Telegram)", "Pay for a subscription (Telegram)"), { sheet = Sheet.PAY })
                        if (googleOn && !HealthSync.hasGoogle(this@MainActivity))
                            SheetItem(t("Сохранить аккаунт через Google", "Akkauntni Google orqali saqlash", "Save account with Google"), { sheet = Sheet.NONE; googleSignIn() })
                        SheetItem(t("Язык", "Til", "Language"), { sheet = Sheet.LANG })
                        SheetItem(t("Политика конфиденциальности", "Maxfiylik siyosati", "Privacy policy"), { sheet = Sheet.NONE; openUrl(privacyUrl()) })
                        SheetItem(t("Выйти", "Chiqish", "Sign out"), {
                            sheet = Sheet.NONE
                            confirm = (if (HealthSync.hasGoogle(this@MainActivity)) t("Выйти на этом телефоне? Вернуться можно через Google.", "Bu telefonda chiqilsinmi? Google orqali qaytish mumkin.", "Sign out on this phone? You can come back with Google.")
                                else t("Выйти? Аккаунт не сохранён в Google — вернуться можно только на этом телефоне.", "Chiqilsinmi? Akkaunt Google da saqlanmagan — faqat shu telefonda qaytish mumkin.", "Sign out? Your account isn’t saved to Google — you can only come back on this phone.")) to { signOut(); Unit }
                        })
                        SheetItem(t("Удалить аккаунт", "Akkauntni oʻchirish", "Delete account"), {
                            sheet = Sheet.NONE
                            confirm = t("Удалить аккаунт и все данные — отчёты, тренер, питание, переписку? Это нельзя отменить.",
                                "Akkaunt va barcha maʼlumotlar — hisobotlar, murabbiy, ovqatlanish, yozishmalar oʻchirilsinmi? Buni qaytarib boʻlmaydi.",
                                "Delete your account and all data — reports, trainer, nutrition, chats? This can’t be undone.") to { deleteAccount(); Unit }
                        }, color = Neon.Red)
                    }
                    Sheet.PAY -> {
                        SheetTitle(t("Оплата в Telegram (Stars)", "Telegramda toʻlov (Stars)", "Payment in Telegram (Stars)"))
                        SheetItem(t("Neon Pulse на год", "Neon Pulse bir yilga", "Neon Pulse for a year"), { pay("year") })
                        SheetItem(t("Тренер на месяц", "Murabbiy bir oyga", "Trainer for a month"), { pay("coach_m") })
                        SheetItem(t("Тренер на год", "Murabbiy bir yilga", "Trainer for a year"), { pay("coach_y") })
                        Text(t("Откроется бот — после оплаты вернитесь в приложение.", "Bot ochiladi — toʻlovdan soʻng ilovaga qayting.", "The bot will open — come back to the app after paying."),
                            color = Neon.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                    }
                    Sheet.NONE -> {}
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    @Composable
    private fun SheetTitle(text: String) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = Neon.Text, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 10.dp))
    }
}
