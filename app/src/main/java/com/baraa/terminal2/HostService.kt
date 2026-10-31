package com.baraa.terminal2

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.util.ArrayDeque

class HostService : Service() {
    private var http: NanoHTTPD? = null
    private var lock: PowerManager.WakeLock? = null
    private var siteRootId: String? = null

    private val prefs by lazy {
        getSharedPreferences("host", Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        makeChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopHost()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == START) startHost()
        return START_STICKY
    }

    private fun startHost() {
        if (http != null) return

        startForeground(8, makeNotification("جاري البحث عن الموقع"))

        try {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            lock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BaraaTerminal2:Host"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            val tree = Uri.parse(prefs.getString("folder_uri", "") ?: "")

            if (tree.toString().isBlank()) {
                throw Exception("لم يتم اختيار مجلد")
            }

            // البحث عن index.html داخل المجلدات الفرعية
            siteRootId = findWebsiteRoot(tree)
                ?: throw Exception("لم يتم العثور على index.html داخل المجلد المختار")

            log("تم العثور على الموقع بنجاح")
            log("جذر الموقع: $siteRootId")

            http = object : NanoHTTPD("0.0.0.0", 3000) {
                override fun serve(session: IHTTPSession): Response {
                    val requested = session.uri
                        .trimStart('/')
                        .ifBlank { "index.html" }

                    if (requested.contains("..")) {
                        return newFixedLengthResponse(
                            Response.Status.FORBIDDEN,
                            MIME_PLAINTEXT,
                            "Forbidden"
                        )
                    }

                    if (requested == "api/health") {
                        return newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json",
                            """{"ok":true,"app":"BotDock","mode":"android-host"}"""
                        )
                    }

                    val rootId = siteRootId
                        ?: return newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            MIME_PLAINTEXT,
                            "Website root not found"
                        )

                    val fileUri = resolve(tree, rootId, requested)
                        ?: return newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            MIME_PLAINTEXT,
                            "File not found: $requested"
                        )

                    return try {
                        val input = contentResolver.openInputStream(fileUri)
                            ?: return newFixedLengthResponse(
                                Response.Status.NOT_FOUND,
                                MIME_PLAINTEXT,
                                "Cannot open file"
                            )

                        newChunkedResponse(
                            Response.Status.OK,
                            mime(requested),
                            input
                        )
                    } catch (e: Exception) {
                        newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            MIME_PLAINTEXT,
                            e.message ?: "Read error"
                        )
                    }
                }
            }

            http!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            running = true

            log("بدأ الخادم على المنفذ 3000")

            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(8, makeNotification("الموقع يعمل على المنفذ 3000"))

        } catch (e: Exception) {
            log("فشل التشغيل: ${e.message}")
            stopHost()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // يبحث عن index.html داخل المجلدات المتداخلة
    private fun findWebsiteRoot(tree: Uri): String? {
        val rootId = DocumentsContract.getTreeDocumentId(tree)

        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootId to 0)

        val ignoredFolders = setOf(
            "node_modules",
            ".git",
            ".gradle",
            "build",
            ".idea"
        )

        var checked = 0
        val maxFolders = 3000
        val maxDepth = 15

        while (queue.isNotEmpty() && checked < maxFolders) {
            val (currentId, depth) = queue.removeFirst()
            checked++

            val children = DocumentsContract
                .buildChildDocumentsUriUsingTree(tree, currentId)

            val cursor = contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            ) ?: continue

            var indexFound = false
            val folders = mutableListOf<String>()

            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1)
                    val type = it.getString(2)

                    if (name.equals("index.html", ignoreCase = true)) {
                        indexFound = true
                    }

                    if (
                        type == DocumentsContract.Document.MIME_TYPE_DIR &&
                        name !in ignoredFolders &&
                        depth < maxDepth
                    ) {
                        folders.add(id)
                    }
                }
            }

            if (indexFound) {
                return currentId
            }

            for (folderId in folders) {
                queue.add(folderId to depth + 1)
            }
        }

        return null
    }

    // يبحث عن الملفات نسبةً إلى مجلد index.html
    private fun resolve(
        tree: Uri,
        rootId: String,
        relative: String
    ): Uri? {
        var currentId = rootId

        val pieces = relative.split('/').filter { it.isNotBlank() }

        for (piece in pieces) {
            val children = DocumentsContract
                .buildChildDocumentsUriUsingTree(tree, currentId)

            val cursor = contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            ) ?: return null

            var next: String? = null

            cursor.use {
                while (it.moveToNext()) {
                    if (it.getString(1) == piece) {
                        next = it.getString(0)
                        break
                    }
                }
            }

            currentId = next ?: return null
        }

        return DocumentsContract.buildDocumentUriUsingTree(tree, currentId)
    }

    private fun mime(path: String) =
        when (path.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "txt" -> "text/plain"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }

    private fun stopHost() {
        try {
            http?.stop()
        } catch (_: Exception) {}

        http = null
        siteRootId = null
        running = false

        try {
            lock?.release()
        } catch (_: Exception) {}

        lock = null
        log("توقف الخادم")
    }

    private fun log(line: String) {
        val old = prefs.getString("logs", "") ?: ""

        prefs.edit()
            .putString("logs", (old + "\n> " + line).takeLast(6000))
            .apply()
    }

    private fun makeNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Baraa Terminal 2")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun makeChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL,
                "BotDock Host",
                NotificationManager.IMPORTANCE_LOW
            )

            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopHost()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "com.baraa.terminal2.START"
        const val STOP = "com.baraa.terminal2.STOP"
        const val CHANNEL = "baraa_terminal_host"

        @Volatile
        var running = false
    }
}
