package com.baraa.terminal2

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val pickFolder = 410
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val prefs by lazy { getSharedPreferences("host", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 14, 29)
        window.navigationBarColor = Color.rgb(8, 14, 29)
        makeUi()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 90)
        refresh()
    }

    private fun makeUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 18)
            setBackgroundColor(Color.rgb(8, 14, 29))
        }
        root.addView(TextView(this).apply {
            text = "BARAA TERMINAL 2"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "BOTDOCK HOST  /  ANDROID"
            textSize = 12f
            setTextColor(Color.rgb(95, 160, 255))
        })
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(105, 225, 165))
            setPadding(0, 20, 0, 10)
        }
        root.addView(status)
        root.addView(makeButton("اختيار مجلد الموقع") { chooseFolder() })
        root.addView(TextView(this).apply {
            text = "المجلد: ${prefs.getString("folder_name", "لم يتم اختياره")}"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 6, 0, 10)
        })
        root.addView(makeButton("تشغيل BotDock") { startHost() })
        root.addView(makeButton("إيقاف الخادم") { stopHost() })
        root.addView(makeButton("السماح بالعمل في الخلفية") { batterySettings() })
        root.addView(makeButton("منع سكون الشاشة") {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            addLog("منع سكون الشاشة مفعّل أثناء بقاء التطبيق مفتوحاً")
        })
        root.addView(TextView(this).apply {
            text = "سجل التشغيل"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 8)
        })
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(190, 205, 225))
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.rgb(18, 27, 46))
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun chooseFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), pickFolder)
    }

    @Deprecated("Legacy result API is retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFolder && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            prefs.edit().putString("folder_uri", uri.toString()).putString("folder_name", "تم اختيار مجلد").apply()
            addLog("تم منح التطبيق إذن الوصول للمجلد")
            refresh()
        }
    }

    private fun startHost() {
        if (prefs.getString("folder_uri", null) == null) {
            Toast.makeText(this, "اختر مجلد الموقع أولاً", Toast.LENGTH_LONG).show()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, HostService::class.java).setAction(HostService.START))
        addLog("إرسال طلب تشغيل BotDock")
        refresh()
    }

    private fun stopHost() {
        startService(Intent(this, HostService::class.java).setAction(HostService.STOP))
        addLog("إرسال طلب إيقاف الخادم")
        refresh()
    }

    private fun batterySettings() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName))
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            else startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun addLog(line: String) {
        val old = prefs.getString("logs", "") ?: ""
        prefs.edit().putString("logs", (old + "\n> " + line).takeLast(6000)).apply()
        if (::logView.isInitialized) logView.text = prefs.getString("logs", "")
    }

    private fun refresh() {
        if (::status.isInitialized) status.text =
            if (HostService.running) "الحالة: يعمل • http://127.0.0.1:3000" else "الحالة: متوقف"
        if (::logView.isInitialized) logView.text = prefs.getString("logs", "جاهز. اختر مجلد الموقع.") ?: ""
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) refresh() }
}
