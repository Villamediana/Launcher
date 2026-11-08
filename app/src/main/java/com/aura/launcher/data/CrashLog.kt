package com.aura.launcher.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash where the user can read it.
 *
 * A launcher that dies takes its logcat with it, and the person holding the
 * phone has no way to get at a stack trace — they see Android's "app keeps
 * stopping" dialog and nothing else. Writing the trace to a file before the
 * process goes means the next report can say what actually happened instead of
 * "it crashed".
 *
 * Only catches crashes on the JVM side. A segfault inside the native runtime
 * kills the process without ever reaching Java, so an empty log after a crash
 * is itself a useful signal: it says the fault was in native code.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            // Always hand back to the platform handler. Swallowing it would
            // leave a half-dead process rather than a clean restart.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val when_ = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.US).format(Date())

        file(context).writeTextAtomically(
            buildString {
                appendLine(when_)
                appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(stack.toString().take(MAX_CHARS))
            }
        )
    }

    // The reader side used to live here — a "read last crash" and "clear it"
    // pair the Settings diagnostics page used. The page went with the rest of
    // the diagnostics UI, so the file is still written for anyone plugging in
    // a cable but nothing on the phone displays it. If a real crash viewer
    // comes back the writer is here and ready.

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Enough for the frames that matter; a trace is not a log file. */
    private const val MAX_CHARS = 8_000
}
