package com.baraa.terminal2

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class MainActivity : Activity() {
    private val pickFolderRequest = 410
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("host", Context.MODE_PRIVATE) }
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var folderText: TextView
    private lateinit var batteryText: TextView
    private lateinit var logsText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var logsScroll: ScrollView
    private var currentServerState = "unknown"
    private var busy = false
    private var pendingSince = 0L
    private var pendingAction = ""

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshLogs()
            handler.postDelayed(this, 1800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 12, 25)
        window.navigationBarColor = Color.rgb(7, 12, 25)
        window.decorView.systemUiVisibility = 0
        buildInterface()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 90)
        }
        refreshFolder()
        refreshBatteryStatus()
        refreshLogs()
    }

    private fun buildInterface() {
        val scrollRoot = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 12, 25))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(26))
        }
        scrollRoot.addView(content)

        content.addView(label("BARAA TERMINAL", 13, BLUE, true))
        content.addView(label("2", 34, WHITE, true).apply { setPadding(0, 0, 0, dp(2)) })
        content.addView(label("NODE.JS HOST  •  BOTDOCK EDITION", 11, MUTED, true).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(22))
        })

        val statusCard = card()
        statusCard.addView(label("حالة الاستضافة", 13, MUTED, true))
        statusText = label("جارٍ فحص الحالة…", 21, GREEN, true).apply { setPadding(0, dp(8), 0, 0) }
        statusCard.addView(statusText)
        statusDetail = label("محرك Node.js واللوحة المحلية", 12, MUTED, false).apply { setPadding(0, dp(5), 0, 0) }
        statusCard.addView(statusDetail)
        content.addView(statusCard, spaced())

        content.addView(sectionTitle("إدارة الخادم"))
        val folderCard = card()
        folderCard.addView(label("مجلد BotDock", 14, WHITE, true))
        folderText = label("لم يتم اختيار مجلد", 12, MUTED, false).apply { setPadding(0, dp(7), 0, dp(12)) }
        folderCard.addView(folderText)
        folderCard.addView(actionButton("اختيار مجلد BotDock", BLUE) { chooseFolder() })
        content.addView(folderCard, spaced())

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        startButton = actionButton("تشغيل BotDock", GREEN_DARK) { startHost() }
        stopButton = actionButton("إيقاف الخادم", RED_DARK) { stopHost() }
        actionRow.addView(startButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) })
        actionRow.addView(stopButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) })
        content.addView(actionRow, spaced())

        content.addView(actionButton("فتح لوحة التحكم  ↗", BLUE) { openDashboard() }, spaced())

        val batteryCard = card()
        batteryCard.addView(label("العمل في الخلفية", 14, WHITE, true))
        batteryText = label("جارٍ فحص إعداد البطارية…", 12, MUTED, false).apply { setPadding(0, dp(6), 0, dp(12)) }
        batteryCard.addView(batteryText)
        batteryCard.addView(actionButton("إعداد السماح بالعمل في الخلفية", SURFACE_BUTTON) { batterySettings() })
        content.addView(batteryCard, spaced())

        content.addView(sectionTitle("سجل التشغيل"))
        val logsCard = card().apply { setPadding(dp(13), dp(13), dp(13), dp(13)) }
        val logToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logToolbar.addView(label("LIVE LOGS", 11, BLUE, true), LinearLayout.LayoutParams(0, -2, 1f))
        val copyButton = smallButton("نسخ السجل", BLUE) { copyLogs() }
        val clearButton = smallButton("مسح", RED_DARK) { confirmClearLogs() }
        logToolbar.addView(copyButton)
        logToolbar.addView(clearButton, LinearLayout.LayoutParams(-2, dp(36)).apply { marginStart = dp(6) })
        logsCard.addView(logToolbar)
        logsText = label("لا توجد سجلات حتى الآن.", 12, LOG_TEXT, false).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
            gravity = Gravity.START
        }
        logsScroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(logsText)
        }
        logsCard.addView(logsScroll, LinearLayout.LayoutParams(-1, dp(260)))
        content.addView(logsCard, spaced())

        content.addView(label(
            "ملاحظة: هذا الإصدار يشغّل لوحة BotDock وواجهات API بمحرك Node.js مدمج. تشغيل بوتات Discord كعمليات مستقلة غير مدعوم في محرك Android المدمج.",
            11, MUTED, false
        ).apply { setPadding(dp(4), dp(12), dp(4), 0) })

        setContentView(scrollRoot)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(SURFACE, 18)
    }

    private fun sectionTitle(text: String): TextView = label(text, 16, WHITE, true).apply {
        setPadding(dp(3), dp(12), 0, dp(10))
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    private fun actionButton(text: String, color: Int, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        isAllCaps = false
        setTextColor(WHITE)
        background = rounded(color, 14)
        stateListAnimator = null
        minHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { action() }
    }

    private fun smallButton(text: String, color: Int, action: () -> Unit): Button = actionButton(text, color, action).apply {
        textSize = 11f
        minHeight = dp(36)
        minWidth = dp(65)
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun spaced(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, pickFolderRequest)
    }

    @Deprecated("Legacy SAF result API is retained for Android 13 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFolderRequest && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                val name = uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { "مجلد محدد" } ?: "مجلد محدد"
                prefs.edit().putString("folder_uri", uri.toString()).putString("folder_name", name).apply()
                LogStore.append(this, "تم حفظ إذن الوصول للمجلد المحدد: $name")
                refreshFolder()
                Toast.makeText(this, "تم اختيار المجلد. اضغط تشغيل BotDock.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر حفظ إذن المجلد: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startHost() {
        if (prefs.getString("folder_uri", null) == null) {
            Toast.makeText(this, "اختر مجلد BotDock أولًا.", Toast.LENGTH_LONG).show()
            return
        }
        if (busy || currentServerState == "running") return
        busy = true
        pendingSince = System.currentTimeMillis()
        pendingAction = "start"
        setButtonsEnabled(false)
        LogStore.append(this, "طلب تشغيل BotDock…")
        try {
            val intent = Intent(this, HostService::class.java).setAction(HostService.START)
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            busy = false
            pendingAction = ""
            pendingSince = 0L
            setButtonsEnabled(true)
            LogStore.append(this, "تعذر إرسال طلب التشغيل: ${e.message}")
            Toast.makeText(this, "تعذر تشغيل الخدمة. راجع سجل التشغيل.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopHost() {
        if (busy || currentServerState != "running") return
        busy = true
        pendingSince = System.currentTimeMillis()
        pendingAction = "stop"
        setButtonsEnabled(false)
        LogStore.append(this, "طلب إيقاف لوحة BotDock…")
        try {
            startService(Intent(this, HostService::class.java).setAction(HostService.STOP))
        } catch (e: Exception) {
            busy = false
            pendingAction = ""
            pendingSince = 0L
            setButtonsEnabled(true)
            LogStore.append(this, "تعذر إرسال طلب الإيقاف: ${e.message}")
        }
    }

    private fun openDashboard() {
        if (currentServerState != "running") {
            Toast.makeText(this, "شغّل BotDock أولًا.", Toast.LENGTH_SHORT).show()
            return
        }
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:3000"))) }
        catch (_: Exception) { Toast.makeText(this, "لم أجد متصفحًا لفتح اللوحة.", Toast.LENGTH_LONG).show() }
    }

    private fun batterySettings() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { }
        }
    }

    private fun refreshFolder() {
        val name = prefs.getString("folder_name", null)
        folderText.text = if (name.isNullOrBlank()) "لم يتم اختيار مجلد بعد" else name
    }

    private fun refreshBatteryStatus() {
        val text = try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 23 && pm.isIgnoringBatteryOptimizations(packageName))
                "تم السماح للتطبيق بتجاوز تحسين البطارية. قد تظل قيود الشركة المصنّعة مؤثرة."
            else "تحسين البطارية ما زال مفعّلًا. افتح الإعدادات واسمح للتطبيق بالعمل دون قيود."
        } catch (_: Exception) { "تعذر قراءة إعداد البطارية." }
        batteryText.text = text
    }

    private fun refreshStatus() {
        Thread({
            val response = try {
                val connection = (URL("http://127.0.0.1:3001/status").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 700
                    readTimeout = 900
                    requestMethod = "GET"
                }
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                JSONObject(text)
            } catch (_: Exception) { null }

            runOnUiThread {
                if (response != null) {
                    val running = response.optBoolean("running", false)
                    val starting = response.optBoolean("starting", false)
                    val engine = response.optBoolean("engine", true)
                    currentServerState = if (running) "running" else "stopped"
                    statusText.text = when { running -> "يعمل الآن"; starting -> "جارٍ التشغيل…"; else -> "الخادم متوقف" }
                    statusText.setTextColor(if (running) GREEN else MUTED)
                    statusDetail.text = when {
                        running -> "http://127.0.0.1:3000  •  محرك Node.js جاهز"
                        starting -> "يتم فتح منفذ لوحة BotDock…"
                        else -> "محرك Node.js ${if (engine) "جاهز" else "غير متصل"} • يمكنك تشغيل اللوحة مجددًا"
                    }
                    busy = when (pendingAction) {
                        "stop" -> running || starting
                        "start" -> !running || starting
                        else -> starting
                    }
                    if (!busy) {
                        pendingAction = ""
                        pendingSince = 0L
                    }
                } else {
                    if (currentServerState != "unknown") currentServerState = "stopped"
                    statusText.text = if (HostService.runtimeStarted || (busy && System.currentTimeMillis() - pendingSince < 40_000)) "جارٍ تهيئة الخادم…" else "متوقف"
                    statusText.setTextColor(MUTED)
                    statusDetail.text = if (HostService.runtimeStarted || busy) "يتم تجهيز Node.js أو انتظار استجابة الخادم" else "اختر مجلد BotDock ثم اضغط تشغيل"
                }
                if (busy && pendingSince > 0L && System.currentTimeMillis() - pendingSince >= 40_000) {
                    busy = false
                    pendingAction = ""
                    pendingSince = 0L
                    LogStore.append(this, "انتهت مهلة تنفيذ طلب الخادم. راجع سجل التشغيل.")
                }
                setButtonsEnabled(!busy)
                stopButton.isEnabled = !busy && currentServerState == "running"
                startButton.isEnabled = !busy && currentServerState != "running"
                refreshBatteryStatus()
            }
        }, "Baraa-Status-Check").start()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        if (::startButton.isInitialized) startButton.isEnabled = enabled && currentServerState != "running"
        if (::stopButton.isInitialized) stopButton.isEnabled = enabled && currentServerState == "running"
    }

    private fun refreshLogs() {
        if (!::logsText.isInitialized) return
        val latest = LogStore.read(this)
        if (logsText.text.toString() != latest) {
            logsText.text = latest
            logsScroll.post { logsScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyLogs() {
        val value = LogStore.read(this)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Baraa Terminal 2 logs", value))
        Toast.makeText(this, "تم نسخ السجل.", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearLogs() {
        android.app.AlertDialog.Builder(this)
            .setTitle("مسح سجل التشغيل؟")
            .setMessage("سيتم حذف السجلات المحفوظة في التطبيق.")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("مسح") { _, _ ->
                LogStore.clear(this)
                refreshLogs()
                Toast.makeText(this, "تم مسح السجل بالكامل.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshFolder()
        refreshBatteryStatus()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    companion object {
        private const val WHITE = Color.WHITE
        private const val BLUE = 0xFF2F80ED.toInt()
        private const val GREEN = 0xFF42D392.toInt()
        private const val GREEN_DARK = 0xFF167B5A.toInt()
        private const val RED_DARK = 0xFF8D3444.toInt()
        private const val SURFACE = 0xFF121D32.toInt()
        private const val SURFACE_BUTTON = 0xFF26344D.toInt()
        private const val LOG_TEXT = 0xFFCFD9EA.toInt()
        private const val MUTED = 0xFF93A5BE.toInt()
    }
}
