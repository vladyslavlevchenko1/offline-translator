/*
 * Offline Translator: translation history saved on the phone, plus temporary voice recordings,
 * and export of everything (history + voice + logs) as one ZIP for testing / analysis.
 *
 * Files (app private storage):
 *   history/history.jsonl  - one translation per line (kept until the user clears it)
 *   history/audio/<id>.wav - recordings (deleted after N days)
 *   logs/                  - see Diagnostics.kt
 */

package com.google.ai.edge.gallery.customtasks.translator

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
  val id: Long,
  val direction: Direction,
  val target: TargetLang,
  val source: String,
  val translation: String,
  /** Translation time (Gemma), seconds. */
  val seconds: Float,
  val formatOk: Boolean = true,
  /** Speech recognition time (Whisper, incl. language detection), if Whisper was used. */
  val asrSeconds: Float? = null,
  /** Direction was chosen automatically ("Авто"). */
  val auto: Boolean = false,
  /** How sure the auto detection was, e.g. "RU/UA 93% · ZH 7%". */
  val detectInfo: String? = null,
  /** "whisper", "gemma" (Gemma listened) or "text" (typed). */
  val asrEngine: String = "text",
  /** What speech recognition produced before the user edited it (review mode). */
  val asrRaw: String? = null,
  /** Recording file names in history/audio (may be deleted later). */
  val audio: List<String> = emptyList(),
  /** Model used for translation. */
  val model: String = "",
  /** In-memory key of the recording for this session (not saved), used by "⇄ reverse". */
  val pcmKey: String? = null,
)

private fun HistoryItem.toJson(): JSONObject =
  JSONObject().apply {
    put("id", id)
    put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(id)))
    put("direction", direction.name)
    put("target", target.name)
    put("source", source)
    put("translation", translation)
    put("seconds", seconds.toDouble())
    put("formatOk", formatOk)
    asrSeconds?.let { put("asrSeconds", it.toDouble()) }
    put("auto", auto)
    detectInfo?.let { put("detectInfo", it) }
    put("asrEngine", asrEngine)
    asrRaw?.let { put("asrRaw", it) }
    put("audio", JSONArray(audio))
    put("model", model)
  }

private fun historyItemFromJson(o: JSONObject): HistoryItem? =
  try {
    val audio = o.optJSONArray("audio")
    HistoryItem(
      id = o.getLong("id"),
      direction = Direction.valueOf(o.getString("direction")),
      target = TargetLang.valueOf(o.getString("target")),
      source = o.optString("source"),
      translation = o.optString("translation"),
      seconds = o.optDouble("seconds", 0.0).toFloat(),
      formatOk = o.optBoolean("formatOk", true),
      asrSeconds = if (o.has("asrSeconds")) o.getDouble("asrSeconds").toFloat() else null,
      auto = o.optBoolean("auto", false),
      detectInfo = if (o.has("detectInfo")) o.getString("detectInfo") else null,
      asrEngine = o.optString("asrEngine", "text"),
      asrRaw = if (o.has("asrRaw")) o.getString("asrRaw") else null,
      audio = if (audio == null) emptyList() else List(audio.length()) { audio.getString(it) },
      model = o.optString("model"),
    )
  } catch (_: Exception) {
    null
  }

object HistoryStore {
  fun dir(context: Context): File = File(context.filesDir, "history").apply { mkdirs() }

  fun audioDir(context: Context): File = File(dir(context), "audio").apply { mkdirs() }

  private fun file(context: Context): File = File(dir(context), "history.jsonl")

  @Synchronized
  fun append(context: Context, item: HistoryItem) {
    try {
      file(context).appendText(item.toJson().toString() + "\n")
    } catch (e: Throwable) {
      TranslatorDiagnostics.error("history append", e)
    }
  }

  /** Newest first. */
  @Synchronized
  fun load(context: Context, limit: Int = 300): List<HistoryItem> {
    val f = file(context)
    if (!f.exists()) return emptyList()
    return try {
      f.readLines().asReversed().asSequence().mapNotNull { line ->
        if (line.isBlank()) null else try { historyItemFromJson(JSONObject(line)) } catch (_: Exception) { null }
      }.take(limit).toList()
    } catch (e: Throwable) {
      TranslatorDiagnostics.error("history load", e)
      emptyList()
    }
  }

  @Synchronized
  fun count(context: Context): Int =
    try {
      file(context).let { if (it.exists()) it.readLines().count { l -> l.isNotBlank() } else 0 }
    } catch (_: Throwable) {
      0
    }

  @Synchronized
  fun clear(context: Context) {
    file(context).delete()
    audioDir(context).listFiles()?.forEach { it.delete() }
  }

  /** Saves a recording as WAV; returns its file name. */
  fun saveAudio(context: Context, id: Long, pcm16: ByteArray): String? =
    try {
      val name = "$id.wav"
      File(audioDir(context), name).writeBytes(pcmToWav(pcm16, TRANSLATOR_SAMPLE_RATE))
      name
    } catch (e: Throwable) {
      TranslatorDiagnostics.error("save audio", e)
      null
    }

  /** Reads a saved recording back as raw PCM16, or null if it was deleted. */
  fun readAudioPcm(context: Context, name: String): ByteArray? {
    val f = File(audioDir(context), name)
    if (!f.exists() || f.length() <= 44) return null
    return try {
      f.readBytes().copyOfRange(44, f.length().toInt())
    } catch (_: Throwable) {
      null
    }
  }

  fun audioBytes(context: Context): Long = audioDir(context).listFiles()?.sumOf { it.length() } ?: 0L

  /** Deletes recordings older than [keepDays]. */
  fun cleanupAudio(context: Context, keepDays: Int) {
    val limit = System.currentTimeMillis() - keepDays * 24L * 3600L * 1000L
    audioDir(context).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
  }

  /** Builds a ZIP with history, logs, settings and (optionally) recordings. */
  fun exportZip(context: Context, withAudio: Boolean, extraInfo: String): File {
    val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
    outDir.listFiles()?.forEach { it.delete() }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    val zip = File(outDir, "offline-translator-$stamp.zip")
    ZipOutputStream(zip.outputStream().buffered()).use { z ->
      fun addFile(path: String, f: File) {
        z.putNextEntry(ZipEntry(path))
        FileInputStream(f).use { it.copyTo(z) }
        z.closeEntry()
      }
      fun addText(path: String, text: String) {
        z.putNextEntry(ZipEntry(path))
        z.write(text.toByteArray(Charsets.UTF_8))
        z.closeEntry()
      }
      val items = load(context, Int.MAX_VALUE).asReversed()
      file(context).takeIf { it.exists() }?.let { addFile("history.jsonl", it) }
      addText("history.txt", readable(items))
      addText("info.txt", "${TranslatorDiagnostics.deviceInfo()}\n\n$extraInfo\n")
      TranslatorDiagnostics.logDir(context).listFiles()?.sortedBy { it.name }?.forEach { addFile("logs/${it.name}", it) }
      if (withAudio) {
        audioDir(context).listFiles()?.sortedBy { it.name }?.forEach { addFile("audio/${it.name}", it) }
      }
    }
    return zip
  }

  private fun readable(items: List<HistoryItem>): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val sb = StringBuilder("История переводов (старые сверху)\n\n")
    for (it in items) {
      val src = if (it.direction == Direction.ME_TO_THEM) "RU" else it.target.code
      val dst = if (it.direction == Direction.ME_TO_THEM) it.target.code else "RU"
      sb.append("[${fmt.format(Date(it.id))}] $src → $dst")
      sb.append(" | распознавание: ${it.asrEngine}")
      if (it.auto) sb.append(" | авто (${it.detectInfo ?: "-"})")
      it.asrSeconds?.let { s -> sb.append(String.format(Locale.US, " | распознавание %.1f с", s)) }
      sb.append(String.format(Locale.US, " | перевод %.1f с", it.seconds))
      if (!it.formatOk) sb.append(" | ⚠ не по формату")
      if (it.model.isNotEmpty()) sb.append(" | ${it.model}")
      sb.append("\n")
      if (it.asrRaw != null && it.asrRaw != it.source) sb.append("  Распознано: ${it.asrRaw}\n")
      sb.append("  $src: ${it.source}\n")
      sb.append("  $dst: ${it.translation}\n")
      if (it.audio.isNotEmpty()) sb.append("  Голос: ${it.audio.joinToString(", ")}\n")
      sb.append("\n")
    }
    return sb.toString()
  }

  /** Opens Android's "share" sheet for the file (Telegram, Gmail, Drive, …). */
  fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
    val send =
      Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    context.startActivity(
      Intent.createChooser(send, "Отправить историю").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }
}

// ---- voice settings ----

private const val PREFS = "offline_translator"
private const val KEY_SAVE_VOICE = "save_voice"
private const val KEY_VOICE_DAYS = "voice_days"

fun loadSaveVoice(context: Context): Boolean =
  context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SAVE_VOICE, true)

fun saveSaveVoice(context: Context, value: Boolean) {
  context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SAVE_VOICE, value).apply()
}

fun loadVoiceDays(context: Context): Int =
  context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VOICE_DAYS, 7)

fun saveVoiceDays(context: Context, value: Int) {
  context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_VOICE_DAYS, value).apply()
}
