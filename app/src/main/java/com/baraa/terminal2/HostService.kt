package com.baraa.terminal2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.content.pm.ServiceInfo
import android.database.Cursor
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class HostService : Service() {
    private val prefs by lazy { getSharedPreferences("host", Context.MODE_PRIVATE) }
    private var wakeLock: PowerManager.WakeLock? = null
    private val preparing = AtomicBoolean(false)

    external fun startNodeWithArguments(arguments: Array<String>): Int

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: START) {
            START -> {
                ensureForeground("تهيئة محرك Node.js")
                when {
                    runtimeStarted -> sendControl("start")
                    runtimeAttempted -> LogStore.append(this, "محرك Node.js سبق تشغيله ثم انتهى؛ لا يمكن إعادة تشغيله داخل العملية نفسها. أوقف التطبيق إجباريًا من إعدادات Android ثم افتحه من جديد.")
                    else -> startRuntime()
                }
            }
            STOP -> {
                if (runtimeStarted) sendControl("stop")
                else LogStore.append(this, "الخادم متوقف بالفعل.")
            }
        }
        return START_STICKY
    }

    private fun ensureForeground(message: String) {
        val notification = makeNotification(message)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRuntime() {
        if (!preparing.compareAndSet(false, true)) return
        Thread({
            try {
                acquireWakeLock()
                val treeText = prefs.getString("folder_uri", null)
                    ?: throw IllegalStateException("اختر مجلد BotDock أولًا.")
                val tree = Uri.parse(treeText)
                val project = importBotDock(tree)
                val nodeProject = File(filesDir, "nodejs-project")
                copyAssetsIfNeeded(assets, "nodejs-project", nodeProject)
                val mainJs = File(nodeProject, "main.js")
                val logFile = File(filesDir, "baraa-host.log")
                if (!mainJs.exists()) throw IllegalStateException("ملف تشغيل Node.js غير موجود داخل التطبيق.")

                LogStore.append(this, "تم تجهيز ملفات BotDock داخل مساحة التطبيق.")
                LogStore.append(this, "تشغيل محرك Node.js المدمج (18.20.4)…")
                prefs.edit().putBoolean("engine_started", true).apply()
                runtimeAttempted = true
                runtimeStarted = true
                Thread({
                    try {
                        val code = startNodeWithArguments(arrayOf("node", mainJs.absolutePath, project.absolutePath, logFile.absolutePath))
                        LogStore.append(this, "انتهى محرك Node.js برمز $code.")
                    } catch (e: Throwable) {
                        LogStore.append(this, "تعطل محرك Node.js: ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        runtimeStarted = false
                        prefs.edit().putBoolean("engine_started", false).apply()
                        releaseWakeLock()
                    }
                }, "Baraa-Node-Runtime").apply { isDaemon = false; start() }
                val ready = waitForControl(35_000)
                if (ready) {
                    LogStore.append(this, "BotDock جاهز على http://127.0.0.1:3000")
                    updateNotification("محرك Node.js يعمل • لوحة BotDock جاهزة")
                } else {
                    LogStore.append(this, "لم يستجب محرك BotDock خلال المهلة. راجع السجل.")
                    updateNotification("تعذر بدء لوحة BotDock")
                }
            } catch (e: Exception) {
                LogStore.append(this, "فشل التشغيل: ${e.message ?: e.javaClass.simpleName}")
                releaseWakeLock()
                updateNotification("تعذر تشغيل BotDock")
            } finally {
                preparing.set(false)
            }
        }, "Baraa-Host-Setup").start()
    }

    private fun acquireWakeLock() {
        try {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BaraaTerminal2:NodeHost").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            LogStore.append(this, "تعذر تفعيل قفل المعالج: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
    }

    private data class DocItem(val id: String, val name: String, val mime: String)

    private fun children(tree: Uri, parentId: String): List<DocItem> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val result = ArrayList<DocItem>()
        val cursor: Cursor = contentResolver.query(childUri, projection, null, null, null) ?: return result
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (it.moveToNext()) result.add(DocItem(it.getString(idIndex), it.getString(nameIndex) ?: "", it.getString(mimeIndex) ?: ""))
        }
        return result
    }

    private fun findBotDockRoot(tree: Uri): String {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val queue = ArrayDeque<Pair<String, Int>>()
        val seen = HashSet<String>()
        queue.add(rootId to 0)
        var inspected = 0
        while (queue.isNotEmpty() && inspected < 2500) {
            val (id, depth) = queue.removeFirst()
            if (!seen.add(id)) continue
            inspected++
            val items = children(tree, id)
            val names = items.map { it.name }.toSet()
            if ("server.js" in names && items.any { it.name == "public" && it.mime == DocumentsContract.Document.MIME_TYPE_DIR }) return id
            if (depth < 6) {
                items.filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR && it.name !in SKIP_DIRS }
                    .forEach { queue.add(it.id to depth + 1) }
            }
        }
        throw IllegalStateException("لم أجد server.js ومجلد public. اختر مجلد BotDock الذي يحتوي ملفات المشروع.")
    }

    private fun importBotDock(tree: Uri): File {
        val rootId = findBotDockRoot(tree)
        val destination = File(filesDir, "BotDock")
        val firstImport = !File(destination, "server.js").exists()
        destination.mkdirs()
        copySafDirectory(tree, rootId, destination, "", firstImport)
        if (!File(destination, "server.js").exists() || !File(destination, "public/index.html").exists()) {
            throw IllegalStateException("نسخ المشروع غير مكتمل؛ تأكد من اختيار مجلد BotDock الصحيح.")
        }
        prefs.edit().putString("project_path", destination.absolutePath).apply()
        return destination
    }

    private fun copySafDirectory(tree: Uri, documentId: String, destination: File, relative: String, firstImport: Boolean) {
        for (item in children(tree, documentId)) {
            if (item.name.isBlank() || item.name in SKIP_ENTRIES) continue
            val isDirectory = item.mime == DocumentsContract.Document.MIME_TYPE_DIR
            val rel = if (relative.isEmpty()) item.name else "$relative/${item.name}"
            val target = File(destination, rel)
            if (!firstImport && relative.isEmpty() && item.name in setOf("data", "bots")) continue
            if (isDirectory) {
                target.mkdirs()
                copySafDirectory(tree, item.id, destination, rel, firstImport)
            } else {
                target.parentFile?.mkdirs()
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, item.id)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
                } ?: throw IllegalStateException("تعذرت قراءة الملف ${item.name}")
            }
        }
    }

    private fun copyAssetsIfNeeded(manager: AssetManager, assetPath: String, destination: File) {
        val version = getSharedPreferences("host", Context.MODE_PRIVATE).getInt("assets_version", 0)
        if (destination.exists() && version == ASSETS_VERSION) return
        destination.deleteRecursively()
        copyAssetFolder(manager, assetPath, destination)
        prefs.edit().putInt("assets_version", ASSETS_VERSION).apply()
    }

    private fun copyAssetFolder(manager: AssetManager, assetPath: String, destination: File) {
        destination.mkdirs()
        val entries = manager.list(assetPath) ?: emptyArray()
        for (entry in entries) {
            val childPath = "$assetPath/$entry"
            val child = File(destination, entry)
            val children = manager.list(childPath)
            if (children != null && children.isNotEmpty()) {
                copyAssetFolder(manager, childPath, child)
            } else {
                child.parentFile?.mkdirs()
                try {
                    manager.open(childPath).use { input -> FileOutputStream(child).use { output -> input.copyTo(output) } }
                } catch (_: java.io.FileNotFoundException) {
                    child.mkdirs()
                }
            }
        }
    }

    private fun waitForControl(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val response = requestControl("GET", "/status")
            if (response != null) {
                try { if (org.json.JSONObject(response).optBoolean("running", false)) return true }
                catch (_: Exception) { }
            }
            Thread.sleep(350)
        }
        return false
    }

    private fun sendControl(action: String) {
        Thread({
            val path = if (action == "stop") "/stop" else "/start"
            var response: String? = null
            for (attempt in 0 until 30) {
                response = requestControl("POST", path)
                if (response != null) break
                Thread.sleep(350)
            }
            if (response != null) {
                LogStore.append(this, if (action == "stop") "تم إيقاف خادم لوحة BotDock." else "تم تشغيل خادم لوحة BotDock.")
                updateNotification(if (action == "stop") "محرك Node.js جاهز • لوحة BotDock متوقفة" else "محرك Node.js يعمل • لوحة BotDock جاهزة")
            } else {
                LogStore.append(this, "تعذر الاتصال بوحدة التحكم بمحرك Node.js. أعد تشغيل التطبيق.")
            }
        }, "Baraa-Host-Control").start()
    }

    private fun requestControl(method: String, path: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://127.0.0.1:3001$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 900
                readTimeout = 1200
                if (method == "POST") doOutput = true
            }
            if (method == "POST") connection.outputStream.use { it.write(ByteArray(0)) }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) { null }
        finally { connection?.disconnect() }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, makeNotification(text))
    }

    private fun makeNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Baraa Terminal 2")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Baraa Node Host", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "com.baraa.terminal2.START"
        const val STOP = "com.baraa.terminal2.STOP"
        const val CHANNEL = "baraa_terminal_host"
        const val NOTIFICATION_ID = 8
        const val ASSETS_VERSION = 1
        @Volatile var runtimeStarted = false
        @Volatile var runtimeAttempted = false
        init {
            System.loadLibrary("node")
            System.loadLibrary("native-lib")
        }
        private val SKIP_DIRS = setOf("node_modules", ".git", ".gradle", "build", "android-shell", ".idea")
        private val SKIP_ENTRIES = SKIP_DIRS + setOf("BotDock.zip", "server.js.bak", "examples")
    }
}
