package dev.wissam.sshterm.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Privacy-respecting crash capture: records the last uncaught exception to a local file and
 * nothing else. No network, no third-party SDK, no automatic transmission — consistent with the
 * app's "collects nothing" stance. The user can view and (explicitly) share the log from Settings.
 */
object CrashLogger {

    private const val FILE_NAME = "last_crash.log"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun crashFile(context: Context) = File(context.filesDir, FILE_NAME)

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("SSH Term crash")
            pw.println("thread: ${thread.name}")
            pw.println("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            pw.println("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            pw.println("uptime-ms: ${android.os.SystemClock.elapsedRealtime()}")
            pw.println("---")
            throwable.printStackTrace(pw)
        }
        crashFile(context).writeText(sw.toString())
    }

    fun lastCrash(context: Context): String? =
        crashFile(context).takeIf { it.exists() }?.readText()?.ifBlank { null }

    fun clear(context: Context) {
        crashFile(context).delete()
    }
}
