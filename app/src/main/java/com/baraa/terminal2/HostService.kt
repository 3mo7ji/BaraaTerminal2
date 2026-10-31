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

class HostService : Service() {
    private var http: NanoHTTPD? = null
    private var lock: PowerManager.WakeLock? = null
    private val prefs by lazy { getSharedPreferences("host", Context.MODE_PRIVATE) }

    override fun onCreate() { super.onCreate(); makeChannel() }

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
        startForeground(8, makeNotification("BotDock قيد التشغيل"))
        try {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BaraaTerminal2:Host").apply {
                setReferenceCounted(false)
                acquire()
            }
            val tree = Uri.parse(prefs.getString("folder_uri", "") ?: "")
            http = object : NanoHTTPD("0.0.0.0", 3000) {
                override fun serve(session: IHTTPSession): Response {
                    val requested = session.uri.trimStart('/').ifBlank { "index.html" }
                    if (requested.contains("..")) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
                    if (requested == "api/health") {
                        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true,"app":"BotDock","mode":"android-host"}""")
                    }
                    val fileUri = resolve(tree, requested)
                        ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                    return try {
                        val input = contentResolver.openInputStream(fileUri)
                            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                        newChunkedResponse(Response.Status.OK, mime(requested), input)
                    } catch (e: Exception) {
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Read error")
                    }
                }
            }
            http!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            running = true
            log("بدأ الخادم على المنفذ 3000")
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(8, makeNotification("BotDock يعمل على المنفذ 3000"))
        } catch (e: Exception) {
            log("فشل التشغيل: ${e.message}")
            stopHost()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun resolve(tree: Uri, relative: String): Uri? {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var currentId = rootId
        val pieces = relative.split('/').filter { it.isNotBlank() }
        for (piece in pieces) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, currentId)
            val cursor = contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            ) ?: return null
            var next: String? = null
            cursor.use {
                while (it.moveToNext()) {
                    if (it.getString(1) == piece) { next = it.getString(0); break }
                }
            }
            currentId = next ?: return null
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, currentId)
    }

    private fun mime(path: String) = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun stopHost() {
        try { http?.stop() } catch (_: Exception) {}
        http = null
        running = false
        try { lock?.release() } catch (_: Exception) {}
        lock = null
        log("توقف الخادم")
    }

    private fun log(line: String) {
        val old = prefs.getString("logs", "") ?: ""
        prefs.edit().putString("logs", (old + "\n> " + line).takeLast(6000)).apply()
    }

    private fun makeNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Baraa Terminal 2").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(open)
            .setOngoing(true).build()
    }

    private fun makeChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "BotDock Host", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() { stopHost(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "com.baraa.terminal2.START"
        const val STOP = "com.baraa.terminal2.STOP"
        const val CHANNEL = "baraa_terminal_host"
        @Volatile var running = false
    }
}
