package com.baraa.terminal2

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogStore {
    private const val FILE_NAME = "baraa-host.log"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun append(context: Context, message: String) {
        synchronized(lock) {
            try {
                val stamp = SimpleDateFormat("HH:mm:ss", Locale("ar", "SA")).format(Date())
                val target = file(context)
                target.parentFile?.mkdirs()
                target.appendText("[$stamp] $message\n", Charsets.UTF_8)
                if (target.length() > 180_000L) {
                    val tail = target.readLines(Charsets.UTF_8).takeLast(1200).joinToString("\n", postfix = "\n")
                    target.writeText(tail, Charsets.UTF_8)
                }
            } catch (_: Exception) { }
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        try { file(context).takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: "لا توجد سجلات حتى الآن." }
        catch (_: Exception) { "تعذرت قراءة السجلات." }
    }

    fun clear(context: Context) = synchronized(lock) {
        try { file(context).writeText("", Charsets.UTF_8) } catch (_: Exception) { }
    }
}
