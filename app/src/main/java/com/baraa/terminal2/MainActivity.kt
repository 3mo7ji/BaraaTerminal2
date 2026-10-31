package com.baraa.terminal2

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var loadingText: TextView
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private val filePickerRequest = 420

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(7, 11, 20)
        window.navigationBarColor = android.graphics.Color.rgb(7, 11, 20)
        window.decorView.systemUiVisibility = 0
        buildShell()
        requestNotificationPermission()
        startHostService()
        waitForHost()
    }

    private fun buildShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.rgb(7, 11, 20)) }
        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(7, 11, 20))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: WebChromeClient.FileChooserParams?
                ): Boolean {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = filePathCallback
                    return try {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                        }
                        startActivityForResult(intent, filePickerRequest)
                        true
                    } catch (_: Exception) {
                        pendingFileCallback?.onReceiveValue(null)
                        pendingFileCallback = null
                        false
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url == null) return true
                    return !url.startsWith("http://127.0.0.1:3000")
                }
            }
            addJavascriptInterface(NativeBridge(), "R7Native")
        }
        progress = ProgressBar(this).apply { isIndeterminate = true }
        loadingText = TextView(this).apply {
            text = "R7Host\n\nجاري تشغيل محرك الاستضافة…"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(dp(52), dp(52), android.view.Gravity.CENTER))
        root.addView(loadingText, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER).apply { topMargin = dp(120) })
        setContentView(root)
    }

    private fun startHostService() {
        try {
            val intent = Intent(this, HostService::class.java).setAction(HostService.START)
            ContextCompat.startForegroundService(this, intent)
        } catch (error: Exception) {
            Toast.makeText(this, "تعذر بدء خدمة R7Host: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }


    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == filePickerRequest) {
            val callback = pendingFileCallback
            pendingFileCallback = null
            val result = if (resultCode == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            callback?.onReceiveValue(result)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 90)
        }
    }

    private fun waitForHost() {
        Thread({
            val end = System.currentTimeMillis() + 60_000
            var ready = false
            while (System.currentTimeMillis() < end) {
                try {
                    val connection = URL("http://127.0.0.1:3001/").openConnection() as HttpURLConnection
                    connection.connectTimeout = 900
                    connection.readTimeout = 900
                    ready = connection.responseCode == 200
                    connection.disconnect()
                    if (ready) break
                } catch (_: Exception) { Thread.sleep(450) }
            }
            runOnUiThread {
                progress.visibility = View.GONE
                if (ready) {
                    loadingText.visibility = View.GONE
                    webView.loadUrl("http://127.0.0.1:3000")
                    showInitialPin()
                } else {
                    loadingText.text = "تعذر تشغيل R7Host\n\nأغلق التطبيق وافتحه مجددًا. إذا استمرت المشكلة، أرسل سجل التشغيل للمراجعة."
                }
            }
        }, "R7Host-Readiness").start()
    }

    private fun showInitialPin() {
        val prefs = getSharedPreferences("r7host", Context.MODE_PRIVATE)
        if (prefs.getBoolean("pin_dialog_shown", false)) return
        handler.postDelayed({
            try {
                val file = File(filesDir, "R7Host/data/admin.json")
                if (!file.exists()) return@postDelayed
                val pin = JSONObject(file.readText()).optString("pin", "")
                if (pin.matches(Regex("\\d{8}")) && !isFinishing) {
                    AlertDialog.Builder(this)
                        .setTitle("رمز دخول R7Host")
                        .setMessage("احتفظ بهذا الرمز؛ ستحتاجه لتسجيل الدخول إلى لوحة التحكم:\n\n$pin")
                        .setPositiveButton("نسخ الرمز") { _, _ -> copyText(pin); prefs.edit().putBoolean("pin_dialog_shown", true).apply() }
                        .setNegativeButton("حسنًا") { _, _ -> prefs.edit().putBoolean("pin_dialog_shown", true).apply() }
                        .show()
                }
            } catch (_: Exception) {}
        }, 600)
    }

    private fun copyText(value: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("R7Host", value))
        Toast.makeText(this, "تم النسخ.", Toast.LENGTH_SHORT).show()
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    inner class NativeBridge {
        @JavascriptInterface fun setAutoStart(enabled: Boolean) {
            getSharedPreferences("r7host", Context.MODE_PRIVATE).edit().putBoolean("auto_start_on_boot", enabled).apply()
            runOnUiThread { Toast.makeText(this@MainActivity, if (enabled) "تم تفعيل طلب التشغيل بعد إعادة تشغيل الهاتف." else "تم إلغاء التشغيل التلقائي بعد إعادة التشغيل.", Toast.LENGTH_SHORT).show() }
        }
        @JavascriptInterface fun batterySettings() { runOnUiThread { openBatterySettings() } }
        @JavascriptInterface fun copy(value: String) { runOnUiThread { copyText(value) } }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
