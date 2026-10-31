package com.baraa.terminal2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class HostService : Service() {
    private val prefs by lazy { getSharedPreferences("r7host", Context.MODE_PRIVATE) }
    private var wakeLock: PowerManager.WakeLock? = null
    private val preparing = AtomicBoolean(false)

    external fun startNodeWithArguments(arguments: Array<String>): Int

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground("تهيئة محرك R7Host")
        when (intent?.action ?: START) {
            START -> {
                if (runtimeStarted) {
                    updateNotification("R7Host يعمل • محرك البوتات نشط")
                } else if (runtimeAttempted) {
                    LogStore.append(this, "سبق تشغيل محرك Node.js داخل هذه العملية. أعد فتح التطبيق بعد تعطل المحرك.")
                } else {
                    startRuntime()
                }
            }
            STOP_BOTS -> stopAllBots()
        }
        return START_STICKY
    }

    private fun ensureForeground(message: String) {
        val notification = makeNotification(message)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun startRuntime() {
        if (!preparing.compareAndSet(false, true)) return
        Thread({
            try {
                acquireWakeLock()
                val appRoot = File(filesDir, "R7Host")
                val runtimeRoot = File(filesDir, "nodejs-project")
                copyAssetsIfNeeded(assets, "r7host", appRoot, APP_ASSETS_VERSION)
                copyAssetsIfNeeded(assets, "nodejs-project", runtimeRoot, NODE_ASSETS_VERSION)
                File(appRoot, "data").mkdirs()
                File(appRoot, "bots").mkdirs()
                val mainJs = File(runtimeRoot, "main.js")
                val logFile = File(filesDir, "r7host-runtime.log")
                if (!mainJs.exists() || !File(appRoot, "server.js").exists()) {
                    throw IllegalStateException("ملفات محرك R7Host غير مكتملة داخل التطبيق.")
                }

                LogStore.append(this, "تم تجهيز واجهة R7Host ومحرك Worker Threads.")
                LogStore.append(this, "بدء Node.js Mobile v18.20.4…")
                runtimeAttempted = true
                runtimeStarted = true
                Thread({
                    try {
                        val code = startNodeWithArguments(arrayOf(mainJs.absolutePath, appRoot.absolutePath, logFile.absolutePath))
                        LogStore.append(this, "انتهى محرك Node.js برمز $code.")
                    } catch (error: Throwable) {
                        LogStore.append(this, "تعطل محرك Node.js: ${error.message ?: error.javaClass.simpleName}")
                    } finally {
                        runtimeStarted = false
                        releaseWakeLock()
                        updateNotification("توقف محرك R7Host")
                    }
                }, "R7Host-Node-Runtime").apply { isDaemon = false; start() }

                val ready = waitForControl(45_000)
                if (ready) {
                    LogStore.append(this, "R7Host جاهز: http://127.0.0.1:3000")
                    updateNotification("R7Host يعمل • حتى 5 بوتات")
                    prefs.edit().putBoolean("runtime_ready", true).apply()
                } else {
                    LogStore.append(this, "لم يستجب محرك R7Host خلال المهلة. أعد فتح التطبيق وراجع السجل.")
                    updateNotification("تعذر بدء محرك R7Host")
                }
            } catch (error: Exception) {
                LogStore.append(this, "فشل تجهيز R7Host: ${error.message ?: error.javaClass.simpleName}")
                releaseWakeLock()
                updateNotification("تعذر تشغيل R7Host")
            } finally {
                preparing.set(false)
            }
        }, "R7Host-Setup").start()
    }

    private fun waitForControl(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                val connection = URL("http://127.0.0.1:3001/").openConnection() as HttpURLConnection
                connection.connectTimeout = 900
                connection.readTimeout = 900
                val ok = connection.responseCode == 200
                connection.disconnect()
                if (ok) return true
            } catch (_: Exception) { Thread.sleep(450) }
        }
        return false
    }

    private fun stopAllBots() {
        Thread({
            try {
                val connection = URL("http://127.0.0.1:3000/internal/stop-all").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 2500
                connection.readTimeout = 2500
                connection.doOutput = true
                connection.outputStream.use { it.write(byteArrayOf()) }
                LogStore.append(this, "تم إرسال طلب إيقاف جميع البوتات.")
                connection.disconnect()
            } catch (error: Exception) {
                LogStore.append(this, "تعذر إيقاف البوتات: ${error.message}")
            }
        }, "R7Host-Stop-Bots").start()
    }

    private fun acquireWakeLock() {
        try {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "R7Host:BotEngine").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (error: Exception) {
            LogStore.append(this, "تعذر تفعيل قفل المعالج: ${error.message}")
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun copyAssetsIfNeeded(manager: AssetManager, assetPath: String, destination: File, version: Int) {
        val key = "assets_$assetPath"
        if (destination.exists() && prefs.getInt(key, 0) == version) return
        destination.mkdirs()
        if (assetPath == "r7host") {
            // Preserve user data and uploaded bot projects across app updates.
            destination.listFiles()?.filter { it.name !in setOf("data", "bots") }?.forEach { it.deleteRecursively() }
        } else {
            destination.deleteRecursively()
            destination.mkdirs()
        }
        copyAssetFolder(manager, assetPath, destination)
        prefs.edit().putInt(key, version).apply()
    }

    private fun copyAssetFolder(manager: AssetManager, assetPath: String, destination: File) {
        destination.mkdirs()
        val entries = manager.list(assetPath) ?: emptyArray()
        for (entry in entries) {
            val childAsset = "$assetPath/$entry"
            val childDest = File(destination, entry)
            val children = manager.list(childAsset)
            if (children != null && children.isNotEmpty()) copyAssetFolder(manager, childAsset, childDest)
            else {
                try {
                    manager.open(childAsset).use { input ->
                        childDest.parentFile?.mkdirs()
                        FileOutputStream(childDest).use { output -> input.copyTo(output, 64 * 1024) }
                    }
                } catch (_: Exception) {
                    childDest.mkdirs()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "R7Host Bot Engine", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun makeNotification(message: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("R7Host")
        .setContentText(message)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun updateNotification(message: String) {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, makeNotification(message)) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "com.baraa.terminal2.START"
        const val STOP_BOTS = "com.baraa.terminal2.STOP_BOTS"
        const val CHANNEL = "r7host_engine"
        const val NOTIFICATION_ID = 77
        const val APP_ASSETS_VERSION = 4
        const val NODE_ASSETS_VERSION = 4
        @Volatile var runtimeStarted = false
        @Volatile var runtimeAttempted = false
        init {
            System.loadLibrary("node")
            System.loadLibrary("native-lib")
        }
    }
}
