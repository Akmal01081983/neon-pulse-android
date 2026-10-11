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
 * Анкета и тренировка дня — в боте; ссылки на Telegram открываются в Telegram.
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
            Toast.makeText(this, "Без камеры проверка техники недоступна", Toast.LENGTH_SHORT).show()
        }
    }

    // Выбор или съёмка видео для проверки техники (если камера в странице не открылась)
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = HealthSync.appUrl(this, "tech")
        if (url == null) {
            Toast.makeText(this, "Сначала подключитесь через Telegram", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#07070F"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
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
                    return try { fileLauncher.launch(params.createIntent()); true } catch (e: Exception) { fileCallback = null; false }
                }
            }
        }
        setContentView(web)
        // «Назад»: сначала по страницам мини-приложения, потом — на главный экран
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
        if (savedInstanceState == null) web.loadUrl(url) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }
}
