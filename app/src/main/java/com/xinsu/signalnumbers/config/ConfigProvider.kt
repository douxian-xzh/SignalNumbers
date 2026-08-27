package com.xinsu.signalnumbers.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        return when (method) {
            ConfigContract.METHOD_GET -> ConfigStore.read(ctx).toBundle()
            ConfigContract.METHOD_LOG -> {
                appendLog(ctx.createDeviceProtectedStorageContext().filesDir, extras?.getString(ConfigContract.EXTRA_MESSAGE).orEmpty())
                Bundle.EMPTY
            }
            ConfigContract.METHOD_FAILURE -> {
                recordFailure()
                ConfigStore.read(ctx).toBundle()
            }
            ConfigContract.METHOD_CLEAR_SAFE_MODE -> {
                ConfigStore.prefs(ctx).edit().remove(Keys.SAFE_UNTIL).remove("failure_count").remove("failure_window").apply()
                ConfigStore.notifyChanged(ctx)
                ConfigStore.read(ctx).toBundle()
            }
            else -> super.call(method, arg, extras) ?: Bundle.EMPTY
        }
    }

    private fun recordFailure() {
        val ctx = requireNotNull(context)
        val prefs = ConfigStore.prefs(ctx)
        val now = SystemClock.elapsedRealtime()
        val window = prefs.getLong("failure_window", 0L)
        val count = if (now - window <= 120_000L) prefs.getInt("failure_count", 0) + 1 else 1
        val edit = prefs.edit().putLong("failure_window", if (count == 1) now else window).putInt("failure_count", count)
        if (count >= 5) {
            edit.putLong(Keys.SAFE_UNTIL, System.currentTimeMillis() + 30 * 60_000L)
            edit.putInt("failure_count", 0)
        }
        edit.apply()
        ConfigStore.notifyChanged(ctx)
    }

    private fun appendLog(dir: File, message: String) {
        if (message.isBlank()) return
        runCatching {
            val file = File(dir, LOG_FILE)
            if (file.exists() && file.length() > 512 * 1024) {
                File(dir, "$LOG_FILE.1").delete()
                file.renameTo(File(dir, "$LOG_FILE.1"))
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            file.appendText("$stamp $message\n", Charsets.UTF_8)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val LOG_FILE = "signalnumbers-debug.log"
        fun logFile(context: android.content.Context) = File(context.createDeviceProtectedStorageContext().filesDir, LOG_FILE)
    }
}
