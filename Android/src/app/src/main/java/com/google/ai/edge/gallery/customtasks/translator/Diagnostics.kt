/*
 * Offline Translator: crash and error log.
 *
 * - Java/Kotlin crashes: written to logs/crash-<time>.txt by an uncaught-exception handler.
 * - Native crashes, ANRs, "killed for low memory": read on the next start from Android's
 *   ApplicationExitInfo and written to logs/exit-<time>.txt (+ raw trace if Android gives one).
 * - Errors and key events while working: logs/app.log (rotated).
 * Everything ends up in the history export, so it can be sent for analysis.
 */

package com.google.ai.edge.gallery.customtasks.translator

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "OfflineTranslatorDiag"
private const val PREFS = "offline_translator_diag"
private const val KEY_LAST_EXIT_TS = "last_exit_ts"
private const val KEY_UNSEEN_CRASH = "unseen_crash"
private const val MAX_LOG_BYTES = 300L * 1024

/** A problem from a previous run, shown once to the user. */
data class DiagIssue(val time: Long, val title: String, val details: String)

object TranslatorDiagnostics {
  @Volatile private var appContext: Context? = null

  fun logDir(context: Context): File = File(context.filesDir, "logs").apply { mkdirs() }

  private fun stamp(time: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))

  private fun fileStamp(time: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(time))

  /** Call once from Application.onCreate. */
  fun install(app: Application) {
    appContext = app.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      try {
        writeCrash(app, thread, error)
      } catch (_: Throwable) {}
      previous?.uncaughtException(thread, error)
    }
    log("app start, version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
  }

  fun deviceInfo(): String =
    "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); " +
      "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}); " +
      "ROM ${Build.DISPLAY}"

  private fun writeCrash(context: Context, thread: Thread, error: Throwable) {
    val sw = StringWriter()
    error.printStackTrace(PrintWriter(sw))
    val now = System.currentTimeMillis()
    val name = "crash-${fileStamp(now)}.txt"
    val text =
      "Вылет приложения (Java/Kotlin)\n" +
        "Время: ${stamp(now)}\n" +
        "Поток: ${thread.name}\n" +
        "${deviceInfo()}\n\n" +
        sw.toString()
    File(logDir(context), name).writeText(text)
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_UNSEEN_CRASH, name).commit()
    log("CRASH ${error.javaClass.name}: ${error.message}")
  }

  /** Appends one line to logs/app.log. Safe to call from any thread. */
  fun log(message: String) {
    Log.i(TAG, message)
    val ctx = appContext ?: return
    try {
      synchronized(this) {
        val dir = logDir(ctx)
        val f = File(dir, "app.log")
        if (f.exists() && f.length() > MAX_LOG_BYTES) {
          val old = File(dir, "app.old.log")
          old.delete()
          f.renameTo(old)
        }
        f.appendText("${stamp()}  $message\n")
      }
    } catch (_: Throwable) {}
  }

  fun error(message: String, e: Throwable? = null) {
    log("ERROR $message" + (e?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
  }

  /**
   * Looks at how the app ended last times (crash, native crash, freeze, killed for memory) and at
   * unseen Java crash files. Saves details to logs/ and returns what should be shown to the user.
   */
  fun collectNewIssues(context: Context): List<DiagIssue> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val issues = mutableListOf<DiagIssue>()

    // 1. Java crash from the last run.
    prefs.getString(KEY_UNSEEN_CRASH, null)?.let { name ->
      val f = File(logDir(context), name)
      if (f.exists()) {
        val text = f.readText()
        val firstError = text.lines().drop(4).firstOrNull { it.isNotBlank() } ?: ""
        issues += DiagIssue(f.lastModified(), "Приложение вылетело", firstError.take(300))
      }
      prefs.edit().remove(KEY_UNSEEN_CRASH).apply()
    }

    // 2. Android's own records (also catches native crashes and memory kills).
    try {
      val am = context.getSystemService(ActivityManager::class.java) ?: return issues
      val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 15)
      val firstRun = !prefs.contains(KEY_LAST_EXIT_TS)
      val lastSeen = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
      var newest = lastSeen
      for (info in exits.sortedBy { it.timestamp }) {
        if (info.timestamp <= lastSeen) continue
        newest = maxOf(newest, info.timestamp)
        val title = exitTitle(info) ?: continue
        val onScreen = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        val details =
          "Что: $title\n" +
            "Время: ${stamp(info.timestamp)}\n" +
            "Причина (код): ${info.reason}, статус: ${info.status}\n" +
            "Описание Android: ${info.description ?: "-"}\n" +
            "Приложение было на экране: ${if (onScreen) "да" else "нет (в фоне)"}\n" +
            "Память при закрытии: PSS ${info.pss / 1024} МБ, RSS ${info.rss / 1024} МБ\n" +
            "${deviceInfo()}\n"
        val base = "exit-${fileStamp(info.timestamp)}"
        File(logDir(context), "$base.txt").writeText(details)
        saveTrace(context, info, base)
        log("EXIT $title (reason ${info.reason}, onScreen=$onScreen, pss=${info.pss / 1024}MB)")
        // Background kills for memory are normal on Android; show only what happened on screen.
        if (!firstRun && (onScreen || info.reason != ApplicationExitInfo.REASON_LOW_MEMORY)) {
          issues += DiagIssue(info.timestamp, title, (info.description ?: "").take(300))
        }
      }
      prefs.edit().putLong(KEY_LAST_EXIT_TS, maxOf(newest, if (firstRun) System.currentTimeMillis() else 0L)).apply()
    } catch (e: Throwable) {
      error("exit reasons", e)
    }
    return issues.sortedByDescending { it.time }
  }

  private fun exitTitle(info: ApplicationExitInfo): String? =
    when (info.reason) {
      ApplicationExitInfo.REASON_CRASH -> "Приложение вылетело"
      ApplicationExitInfo.REASON_CRASH_NATIVE -> "Вылет в нативной части (модель / Whisper)"
      ApplicationExitInfo.REASON_ANR -> "Приложение зависло и было закрыто"
      ApplicationExitInfo.REASON_LOW_MEMORY -> "Система закрыла приложение: не хватило памяти"
      ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Система закрыла приложение: слишком большая нагрузка"
      ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Приложение не смогло запуститься"
      ApplicationExitInfo.REASON_SIGNALED ->
        if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
          "Система принудительно закрыла приложение"
        else null
      else -> null
    }

  private fun saveTrace(context: Context, info: ApplicationExitInfo, base: String) {
    if (
      info.reason != ApplicationExitInfo.REASON_ANR &&
        info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE
    ) {
      return
    }
    try {
      info.traceInputStream?.use { input ->
        val ext = if (info.reason == ApplicationExitInfo.REASON_ANR) "anr.txt" else "tombstone.pb"
        val out = File(logDir(context), "$base-$ext")
        out.outputStream().use { o ->
          val buf = ByteArray(64 * 1024)
          var total = 0
          while (true) {
            val n = input.read(buf)
            if (n < 0 || total > 2 * 1024 * 1024) break
            o.write(buf, 0, n)
            total += n
          }
        }
      }
    } catch (e: Throwable) {
      error("trace", e)
    }
  }

  /** Number of crash / exit reports saved so far. */
  fun reportCount(context: Context): Int =
    logDir(context).listFiles()?.count { it.name.startsWith("crash-") || (it.name.startsWith("exit-") && it.name.endsWith(".txt") && !it.name.contains("anr")) } ?: 0
}
