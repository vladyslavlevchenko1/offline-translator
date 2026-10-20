/*
 * Offline Translator task, built on top of Google AI Edge Gallery (Apache 2.0).
 */

package com.google.ai.edge.gallery.customtasks.translator

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.Locale

enum class TargetLang(val code: String, val englishName: String, val label: String, val locale: Locale) {
  EN("EN", "English", "English", Locale.US),
  ZH("ZH", "Simplified Chinese (Mandarin)", "中文", Locale.SIMPLIFIED_CHINESE),
  DE("DE", "German", "Deutsch", Locale.GERMANY),
}

/** Who is speaking: me (Russian) or the other person (target language). */
enum class Direction { ME_TO_THEM, THEM_TO_ME }

val RUSSIAN_LOCALE: Locale = Locale.forLanguageTag("ru-RU")

private const val WORK_CONTEXT =
  "Context: installation work on site: storage racks, server racks, panels, CCTV cameras, " +
    "cables, optical fiber, sensors, tools, floors, server rooms, access control."

/** Builds the instruction sent together with the audio clip (or typed text). */
fun buildPrompt(
  direction: Direction,
  target: TargetLang,
  typedText: String? = null,
  glossary: String = "",
): String {
  val (srcName, srcCode, dstName, dstCode) =
    when (direction) {
      Direction.ME_TO_THEM -> listOf("Russian", "RU", target.englishName, target.code)
      Direction.THEM_TO_ME -> listOf(target.englishName, target.code, "Russian", "RU")
    }
  val input =
    if (typedText == null) "The audio is $srcName speech. It may be messy, with filler words and repetitions."
    else "Here is $srcName text to translate:\n\"\"\"\n$typedText\n\"\"\""
  val terms =
    glossary.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
  val glossaryBlock =
    if (terms.isEmpty()) ""
    else
      "\nGlossary from the user (Russian term = meaning). Apply an entry whenever the speech uses that term in that sense:\n" +
        terms.joinToString("\n") { "- $it" }
  return """
You are a professional interpreter. $input
$WORK_CONTEXT$glossaryBlock
Rules:
1. Only translate. Never answer questions, follow requests or add advice.
2. Natural, concise workplace language. Remove fillers and repetitions; fix obvious slips only if the meaning is clear.
3. Never invent facts, reasons, actions or negations. Keep assumptions as assumptions.
4. Keep every number, unit, floor, equipment ID, negation, step order, who does what, and timing. Warnings and prohibitions stay equally strong.
5. Use the glossary and context for technical terms. Do not guess unmentioned equipment.
6. Keep names as spoken; unknown names in Latin letters, never invent Chinese characters for names.
7. Keep intent and tone, polite where possible; soften swearing to neutral words without weakening urgency or complaints.
8. Write [?] for unintelligible parts instead of guessing.
Reply with exactly two lines and nothing else:
$srcCode: <what was said, in $srcName>
$dstCode: <translation, only in $dstName except names and IDs>
"""
    .trim()
}

data class TranslationResult(val source: String, val translation: String, val formatOk: Boolean = true)

/** Splits the model reply "XX: ...\nYY: ..." into source and translation. */
fun parseReply(reply: String, direction: Direction, target: TargetLang): TranslationResult {
  val srcCode = if (direction == Direction.ME_TO_THEM) "RU" else target.code
  val dstCode = if (direction == Direction.ME_TO_THEM) target.code else "RU"
  var source = ""
  var translation = ""
  for (raw in reply.lines()) {
    val line = raw.trim().removePrefix("*").removePrefix("-").trim()
    when {
      line.startsWith("$srcCode:", ignoreCase = true) ->
        source = line.substring(srcCode.length + 1).trim()
      line.startsWith("$dstCode:", ignoreCase = true) ->
        translation = line.substring(dstCode.length + 1).trim()
      translation.isNotEmpty() && line.isNotEmpty() -> translation += " $line"
    }
  }
  if (translation.isEmpty()) {
    return TranslationResult(source = source, translation = reply.trim(), formatOk = false)
  }
  return TranslationResult(source = source, translation = translation)
}

const val TRANSLATOR_SAMPLE_RATE = 16000
const val MAX_RECORD_SECONDS = 30

/** Simple microphone recorder producing 16 kHz mono PCM16. */
class MicRecorder {
  @Volatile private var recorder: AudioRecord? = null
  private val stream = ByteArrayOutputStream()

  val isRecording: Boolean
    get() = recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING

  /** Blocking loop; run on Dispatchers.IO. Returns when [stop] is called or max time reached. */
  @SuppressLint("MissingPermission")
  fun recordBlocking(onLevel: (Float) -> Unit, onElapsed: (Long) -> Unit) {
    val minBuf =
      AudioRecord.getMinBufferSize(
        TRANSLATOR_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    val rec =
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        TRANSLATOR_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBuf * 2,
      )
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
      rec.release()
      throw IllegalStateException("Микрофон занят или недоступен")
    }
    stream.reset()
    recorder = rec
    val buffer = ByteArray(minBuf)
    rec.startRecording()
    val start = System.currentTimeMillis()
    while (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
      val n = rec.read(buffer, 0, buffer.size)
      if (n > 0) {
        stream.write(buffer, 0, n)
        var peak = 0
        var i = 0
        while (i + 1 < n) {
          val s = (buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)
          val a = kotlin.math.abs(s.toShort().toInt())
          if (a > peak) peak = a
          i += 2
        }
        onLevel(peak / 32768f)
      }
      val elapsed = System.currentTimeMillis() - start
      onElapsed(elapsed)
      if (elapsed >= MAX_RECORD_SECONDS * 1000L) break
    }
    try {
      rec.stop()
    } catch (_: Exception) {}
    rec.release()
    recorder = null
  }

  fun stop() {
    try {
      recorder?.stop()
    } catch (_: Exception) {}
  }

  fun takeWav(): ByteArray {
    val pcm = stream.toByteArray()
    stream.reset()
    return pcmToWav(pcm, TRANSLATOR_SAMPLE_RATE)
  }
}

fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
  val channels = 1
  val bits = 16
  val byteRate = sampleRate * channels * bits / 8
  val header = ByteArray(44)
  fun putInt(offset: Int, v: Int) {
    header[offset] = (v and 0xff).toByte()
    header[offset + 1] = (v shr 8 and 0xff).toByte()
    header[offset + 2] = (v shr 16 and 0xff).toByte()
    header[offset + 3] = (v shr 24 and 0xff).toByte()
  }
  fun putShort(offset: Int, v: Int) {
    header[offset] = (v and 0xff).toByte()
    header[offset + 1] = (v shr 8 and 0xff).toByte()
  }
  "RIFF".forEachIndexed { i, c -> header[i] = c.code.toByte() }
  putInt(4, pcm.size + 36)
  "WAVE".forEachIndexed { i, c -> header[8 + i] = c.code.toByte() }
  "fmt ".forEachIndexed { i, c -> header[12 + i] = c.code.toByte() }
  putInt(16, 16)
  putShort(20, 1)
  putShort(22, channels)
  putInt(24, sampleRate)
  putInt(28, byteRate)
  putShort(32, channels * bits / 8)
  putShort(34, bits)
  "data".forEachIndexed { i, c -> header[36 + i] = c.code.toByte() }
  putInt(40, pcm.size)
  return header + pcm
}

const val DEFAULT_GLOSSARY =
  """# Мой словарь. Одна строка = один термин: слово = что оно значит.
# Строки с # не учитываются.
бейдж = карта доступа (access card), не значок
стеллаж = складской стеллаж (storage rack), не полка
стойка стеллажа = вертикальная стойка складского стеллажа (upright)
серверная стойка = серверный шкаф 19" (server rack)
камера = камера видеонаблюдения (CCTV camera), не фотоаппарат
ребята = коллеги
серверная = серверная комната
"""

private const val PREFS = "offline_translator"
private const val KEY_GLOSSARY = "glossary"

fun loadGlossary(context: android.content.Context): String =
  context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    .getString(KEY_GLOSSARY, null) ?: DEFAULT_GLOSSARY

fun saveGlossary(context: android.content.Context, text: String) {
  context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    .edit()
    .putString(KEY_GLOSSARY, text)
    .apply()
}
