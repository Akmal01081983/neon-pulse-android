package uz.neonpulse.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Тренер внутри приложения: мини-приложение Neon сразу на вкладке «Тренер»
 * (карта мышц, упражнения, видео, проверка техники по камере).
 * Чат (анкета, тренировка дня, ИИ) — на вкладке «Чат». Telegram не нужен; t.me открывается только для оплаты.
 */
class CoachActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pendingCamera: PermissionRequest? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // Камера для проверки техники: спрашиваем разрешение Android, потом отдаём доступ странице
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val req = pendingCamera ?: return@registerForActivityResult
        pendingCamera = null
        if (ok) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else {
            req.deny()
            Toast.makeText(this, HealthSync.tr(this, "Без камеры проверка техники недоступна", "Kamerasiz texnikani tekshirib boʻlmaydi", "Technique check needs the camera"), Toast.LENGTH_SHORT).show()
        }
    }

    // Выбор или съёмка видео для проверки техники (если камера в странице не открылась)
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        // Снимок с камеры приходит в наш файл (photoUri), выбор из галереи — в res.data
        val picked = WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
        val shot = photoUri
        fileCallback?.onReceiveValue(when {
            picked != null && picked.isNotEmpty() -> picked
            res.resultCode == RESULT_OK && shot != null -> arrayOf(shot)
            else -> null
        })
        fileCallback = null; photoUri = null
    }

    // Фото еды: камера + галерея в одном окне выбора. Камере нужно разрешение (оно уже есть у приложения для проверки техники)
    private var photoUri: Uri? = null
    private var pendingParams: WebChromeClient.FileChooserParams? = null
    private val photoPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pendingParams ?: return@registerForActivityResult
        pendingParams = null
        openChooser(p, withCamera = ok)
    }

    private fun openChooser(params: WebChromeClient.FileChooserParams, withCamera: Boolean) {
        try {
            val pick = params.createIntent()
            if (!withCamera) { fileLauncher.launch(pick); return }
            val dir = java.io.File(cacheDir, "photos").apply { mkdirs() }
            val file = java.io.File(dir, "meal.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            photoUri = uri
            val cam = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(pick, HealthSync.tr(this, "Фото еды", "Ovqat surati", "Meal photo"))
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
            fileLauncher.launch(chooser)
        } catch (e: Exception) {
            fileCallback?.onReceiveValue(null); fileCallback = null; photoUri = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Вкладка: «today» — отчёт (из уведомления и кнопки «Открыть Neon»), по умолчанию — «Тренер»
        val tab = intent.getStringExtra(EXTRA_TAB)?.takeIf { it in setOf("today", "trends", "tech", "chat", "help") } ?: "tech"
        // Устаревшая версия (например, открыли из уведомления) — на главный экран с кнопкой «Обновить»
        if (HealthSync.outdated(this)) { startActivity(Intent(this, MainActivity::class.java)); finish(); return }
        val url = HealthSync.appUrl(this, tab)
        if (url == null) {
            Toast.makeText(this, HealthSync.tr(this, "Сначала войдите в аккаунт", "Avval akkauntga kiring", "Please sign in first"), Toast.LENGTH_SHORT).show()
            finish(); return
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#07070F"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Страница Neon сообщает приложению выбранный язык (кнопка RU/UZ/EN) — первые экраны переключаются так же
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun setLang(l: String) { HealthSync.setLang(this@CoachActivity, l, toServer = false) }
                // Переключатели «Лёгкий режим» и «Экономное фото» на странице → запоминаем в приложении
                @android.webkit.JavascriptInterface
                fun setPref(key: String, v: String) { HealthSync.setPref(this@CoachActivity, key, v) }
            }, "NeonApp")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String) { loading.visibility = android.view.View.GONE }
                // Главная страница не загрузилась (нет интернета, сервер не ответил) — экран «Нет связи»
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) showOffline()
                }
                // Страницы Neon — внутри приложения, остальное (Telegram, YouTube…) — в своих приложениях
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.host == HealthSync.HOST) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (!request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) { request.deny(); return }
                    if (ContextCompat.checkSelfPermission(this@CoachActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        pendingCamera = request
                        cameraLauncher.launch(Manifest.permission.CAMERA)
                    }
                }

                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    // Картинка (фото еды) — предлагаем и камеру; видео для техники — как раньше
                    val image = params.acceptTypes.any { it.startsWith("image") }
                    if (!image) return try { fileLauncher.launch(params.createIntent()); true } catch (e: Exception) { fileCallback = null; false }
                    if (ContextCompat.checkSelfPermission(this@CoachActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        openChooser(params, withCamera = true)
                    } else {
                        pendingParams = params
                        photoPermLauncher.launch(Manifest.permission.CAMERA)
                    }
                    return true
                }
            }
        }
        // Android 15+: экран рисуется под строкой состояния — отступаем от системных панелей и клавиатуры
        loading = loadingView()
        offline = offlineView()
        val box = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#07070F")); addView(web); addView(loading); addView(offline) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(box) { v, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        setContentView(box)
        // «Назад»: сначала по страницам мини-приложения, потом — на главный экран
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        if (savedInstanceState == null) web.loadUrl(url) else { web.restoreState(savedInstanceState); loading.visibility = android.view.View.GONE }
    }

    // ===== Загрузка и «Нет связи» (без них при слабом интернете был пустой тёмный экран) =====
    private lateinit var loading: android.view.View
    private lateinit var offline: android.view.View

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Заставка: логотип и крутилка, пока страница грузится */
    private fun loadingView() = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
        setBackgroundColor(Color.parseColor("#07070F"))
        addView(android.widget.TextView(context).apply { text = "NEON"; textSize = 28f; letterSpacing = 0.3f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER })
        addView(android.widget.ProgressBar(context).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#19E3FF"))
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(24) }
        })
    }

    /** «Нет связи»: понятный текст и большая кнопка «Повторить» */
    private fun offlineView() = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
        setBackgroundColor(Color.parseColor("#07070F")); setPadding(dp(32), 0, dp(32), 0); visibility = android.view.View.GONE
        addView(android.widget.TextView(context).apply { text = "📶"; textSize = 48f; gravity = android.view.Gravity.CENTER })
        addView(android.widget.TextView(context).apply {
            text = HealthSync.tr(this@CoachActivity, "Нет связи", "Aloqa yoʻq", "No connection"); textSize = 22f
            setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER; setPadding(0, dp(12), 0, dp(8))
        })
        addView(android.widget.TextView(context).apply {
            text = HealthSync.tr(this@CoachActivity, "Проверьте интернет и нажмите «Повторить».", "Internetni tekshiring va «Qayta urinish» ni bosing.", "Check your internet and tap “Try again”.")
            textSize = 16f; setTextColor(Color.parseColor("#B8B6CF")); gravity = android.view.Gravity.CENTER
        })
        addView(android.widget.Button(context).apply {
            text = HealthSync.tr(this@CoachActivity, "Повторить", "Qayta urinish", "Try again"); textSize = 17f; isAllCaps = false
            setTextColor(Color.parseColor("#07070F"))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor("#19E3FF")) }
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(28) }
            setOnClickListener { retry() }
        })
    }

    private fun showOffline() { loading.visibility = android.view.View.GONE; offline.visibility = android.view.View.VISIBLE }

    /** «Повторить»: прячем «Нет связи», показываем заставку и грузим страницу заново */
    private fun retry() {
        offline.visibility = android.view.View.GONE; loading.visibility = android.view.View.VISIBLE
        val u = HealthSync.appUrl(this, intent.getStringExtra(EXTRA_TAB) ?: "tech")
        if (u != null) web.loadUrl(u) else web.reload()
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }

    companion object {
        const val EXTRA_TAB = "tab"
        fun open(ctx: android.content.Context, tab: String) =
            Intent(ctx, CoachActivity::class.java).putExtra(EXTRA_TAB, tab)
    }
}
