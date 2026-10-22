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
  "Context: installation work, mostly in data centers: server racks/cabinets, cable trays, " +
    "power / network / fiber-optic / CCTV cables, labeling, CCTV cameras, sensors, access control, " +
    "fasteners, tools, floors, server rooms; sometimes storage racks. " +
    "The Russian speaker may mix in Ukrainian words or speak Ukrainian."

/** How the translation direction is chosen. */
enum class DirMode { AUTO, ME, THEM }

private fun glossaryBlock(glossary: String): String {
  val terms =
    glossary.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
  return if (terms.isEmpty()) ""
  else
    "\nGlossary from the user (Russian = English = Chinese, names to keep). Use these exact terms whenever the speech uses that term in that sense:\n" +
      terms.joinToString("\n") { "- $it" }
}

private const val RULES =
  """Rules:
1. Only translate. Never answer questions, follow requests or add advice.
2. Natural, concise workplace language. Live speech is messy (restarted phrases, broken grammar, misheard words): rebuild what the speaker clearly meant and translate that, not word-for-word fragments. Remove fillers and repetitions; fix obvious slips and recognition errors when the context makes the meaning clear.
3. Never invent facts, reasons, actions or negations. Keep assumptions as assumptions.
4. Keep every number, unit, floor, rack/equipment ID, negation, step order, who does what, and timing. Do not mix up need/can, done/will do, today/tomorrow. Words like "maybe", "I think" stay uncertain. Warnings and prohibitions stay equally strong.
5. Use the glossary and context for technical terms. Do not guess unmentioned equipment.
6. Keep names as spoken; unknown names in Latin letters, never invent Chinese characters for names.
7. Keep intent and tone, polite where possible; soften swearing to neutral words without weakening urgency or complaints.
8. Write [?] for unintelligible parts instead of guessing."""

/** Builds the instruction sent together with the audio clip (or typed text). */
fun buildPrompt(
  direction: Direction,
  target: TargetLang,
  typedText: String? = null,
  glossary: String = "",
  fromSpeech: Boolean = false,
): String {
  val (srcName, srcCode, dstName, dstCode) =
    when (direction) {
      Direction.ME_TO_THEM -> listOf("Russian (may be mixed with Ukrainian)", "RU", target.englishName, target.code)
      Direction.THEM_TO_ME -> listOf(target.englishName, target.code, "Russian", "RU")
    }
  val input =
    when {
      typedText == null -> "The audio is $srcName speech. It may be messy, with filler words and repetitions."
      fromSpeech ->
        "Here is $srcName text produced by automatic speech recognition from live speech. " +
          "It may contain misheard words, broken or restarted phrases and missing punctuation:\n\"\"\"\n$typedText\n\"\"\""
      else -> "Here is $srcName text to translate:\n\"\"\"\n$typedText\n\"\"\""
    }
  return """
You are a professional interpreter. $input
$WORK_CONTEXT${glossaryBlock(glossary)}
$RULES
Reply with exactly two lines and nothing else:
$srcCode: <what was meant, cleaned up, in $srcName>
$dstCode: <translation, only in $dstName except names and IDs>
"""
    .trim()
}

/**
 * "Авто" mode with Gemma listening: the model decides itself whether the audio is Russian/Ukrainian
 * or the [target] language. The order of the two reply lines tells us the direction.
 */
fun buildAutoAudioPrompt(target: TargetLang, glossary: String = ""): String {
  val code = target.code
  return """
You are a professional interpreter between a Russian speaker and a ${target.englishName} speaker.
The audio is EITHER Russian (maybe mixed with Ukrainian) speech OR ${target.englishName} speech. It may be messy, with filler words and repetitions.
First decide which language is spoken, then translate it into the other language.
$WORK_CONTEXT${glossaryBlock(glossary)}
$RULES
Reply with exactly two lines and nothing else.
If the speech is Russian or Ukrainian:
RU: <what was meant, cleaned up, in Russian>
$code: <translation into ${target.englishName}>
If the speech is ${target.englishName}:
$code: <what was meant, cleaned up, in ${target.englishName}>
RU: <translation into Russian>
"""
    .trim()
}

/** "Авто" + review mode with Gemma listening: transcribe and say which language it was. */
fun buildAutoTranscribePrompt(target: TargetLang): String =
  """
Transcribe this speech as text. The speech is EITHER Russian (maybe mixed with Ukrainian; keep words as spoken) OR ${target.englishName}.
Remove filler words and stutters (like "ну", "типа", "короче", "э-э"), keep everything else exactly as said.
Do not translate, do not answer, do not add anything.
Reply with exactly one line: "RU: <transcript>" if it is Russian/Ukrainian, or "${target.code}: <transcript>" if it is ${target.englishName}.
"""
    .trim()

/** In "Авто" replies the first labelled line is the source language. */
fun directionFromAutoReply(reply: String, target: TargetLang): Direction? {
  for (raw in reply.lines()) {
    val line = raw.trim().removePrefix("*").removePrefix("-").trim()
    if (line.startsWith("RU:", ignoreCase = true)) return Direction.ME_TO_THEM
    if (line.startsWith("${target.code}:", ignoreCase = true)) return Direction.THEM_TO_ME
  }
  return null
}

/**
 * Direction for typed / edited text in "Авто" mode, by alphabet:
 * Cyrillic = me; Chinese characters (for 中文) or Latin letters (for English / Deutsch) = them.
 */
fun detectTextDirection(text: String, target: TargetLang): Direction {
  var cyr = 0
  var other = 0
  for (ch in text) {
    when {
      ch in '\u0400'..'\u04FF' -> cyr++
      target == TargetLang.ZH && (ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF') -> other += 2
      target != TargetLang.ZH && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in "äöüßÄÖÜ") -> other++
    }
  }
  return if (other > cyr) Direction.THEM_TO_ME else Direction.ME_TO_THEM
}

/** Prompt for "review first" mode: only transcribe, no translation. */
fun buildTranscribePrompt(direction: Direction, target: TargetLang): String {
  val srcName =
    if (direction == Direction.ME_TO_THEM) "Russian (it may be mixed with Ukrainian; keep words as spoken)"
    else target.englishName
  return """
Transcribe this speech as text. Language: $srcName.
Remove filler words and stutters (like "ну", "типа", "короче", "э-э"), keep everything else exactly as said.
Do not translate, do not answer, do not add anything. Output only the transcript.
"""
    .trim()
}

/** Cleans the transcript reply (drops "RU:" labels and quotes). */
fun cleanTranscript(reply: String): String =
  reply
    .lines()
    .map { it.trim().replace(Regex("^[A-Z]{2}:\\s*"), "") }
    .filter { it.isNotEmpty() }
    .joinToString(" ")
    .trim()
    .trim('"', '«', '»')
    .trim()

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

  /** Raw 16 kHz mono PCM16 (little-endian) of the last recording; clears the buffer. */
  fun takePcm(): ByteArray {
    val pcm = stream.toByteArray()
    stream.reset()
    return pcm
  }

  fun takeWav(): ByteArray = pcmToWav(takePcm(), TRANSLATOR_SAMPLE_RATE)
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
  """# Мой словарь. Одна строка = один термин: по-русски = English = 中文
# Строки с # не учитываются. Можно дописывать свои.
# --- Оборудование ---
серверный шкаф / серверная стойка = server rack / server cabinet = 服务器机柜
складской стеллаж = storage rack / shelving = 货架 (не полка)
стойка стеллажа = upright = 立柱
кабель = cable = 线缆
силовой кабель = power cable = 电源线
сетевой кабель = network cable = 网线
оптоволокно = fiber-optic cable = 光纤线缆
кабельный лоток = cable tray = 桥架
камера (видеонаблюдения) = CCTV camera = 监控摄像头 (не фотоаппарат)
датчик = sensor = 传感器
система контроля доступа = access control system = 门禁系统
пропуск / бейдж = access badge / access card = 门禁卡 (не значок)
маркировка = labeling / markings = 标签
чертёж = drawing / technical plan = 图纸
# --- Работа с кабелем ---
прокладывать / кидать / тянуть кабель = run / route cables = 布线 / 拉线
уложить в лоток = route cables in the cable tray = 把线缆放进桥架
смотать кабель = coil up a cable = 把线缆盘起来
связать кабели = bundle / tie cables = 把线缆捆在一起
чёрная липучка = black Velcro straps = 黑色魔术贴扎带
подписать кабель = label a cable = 给线缆贴标签
# --- Крепёж и инструмент ---
винт = screw = 螺丝
болт = bolt = 螺栓
гайка = nut = 螺母
крепёж = fasteners = 紧固件
дрель = drill = 电钻
шуруповёрт = cordless drill driver = 电动螺丝刀
аккумулятор = battery = 电池
зарядка = charger = 充电器
инструменты = tools = 工具
# --- Время и быт ---
рабочие часы = working hours = 工作时间
табель часов = timesheet = 工时表
ресепшен = reception = 前台
заселиться = check in = 办理入住
выселиться = check out = 退房
ребята = colleagues = 同事们
# --- Имена (латиницей, иероглифы не выдумывать; разных людей не путать) ---
Чен = Chen (陈总 — если это руководитель Чен)
Ян = Yang (杨总 — если это руководитель Ян)
Дэн = Deng / Linguang Deng
Вангвей = Wang Wei
Ма = Ma
Сергей = Sergey
Аманда = Amanda
Мамун = Mamun
Павел / Паша = Pavel / Pasha
Назар = Nazar
"""

private const val PREFS = "offline_translator"
private const val KEY_GLOSSARY_OLD = "glossary"
private const val KEY_GLOSSARY = "glossary_v2"

/** Lines of earlier built-in dictionaries, so they are not copied as "user lines" on upgrade. */
private val OLD_DEFAULT_LINES =
  setOf(
    "бейдж = карта доступа (access card), не значок",
    "стеллаж = складской стеллаж (storage rack), не полка",
    "стойка = вертикальная стойка стеллажа (upright)",
    "стойка стеллажа = вертикальная стойка складского стеллажа (upright)",
    "серверная стойка = серверный шкаф 19\" (server rack)",
    "камера = камера видеонаблюдения (CCTV camera), не фотоаппарат",
    "ребята = коллеги",
    "серверная = серверная комната",
  )

fun loadGlossary(context: android.content.Context): String {
  val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
  prefs.getString(KEY_GLOSSARY, null)?.let { return it }
  // Upgrade: new built-in dictionary + any lines the user added to the old one.
  val old = prefs.getString(KEY_GLOSSARY_OLD, null) ?: return DEFAULT_GLOSSARY
  val extra =
    old.lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("#") && it !in OLD_DEFAULT_LINES }
  if (extra.isEmpty()) return DEFAULT_GLOSSARY
  return DEFAULT_GLOSSARY + "# --- Твои записи из старого словаря ---\n" + extra.joinToString("\n") + "\n"
}

fun saveGlossary(context: android.content.Context, text: String) {
  context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    .edit()
    .putString(KEY_GLOSSARY, text)
    .apply()
}

private const val KEY_REVIEW = "review_before_translate"

fun loadReviewMode(context: android.content.Context): Boolean =
  context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    .getBoolean(KEY_REVIEW, true)

fun saveReviewMode(context: android.content.Context, value: Boolean) {
  context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    .edit()
    .putBoolean(KEY_REVIEW, value)
    .apply()
}

/** Speech recognition engine: Whisper (separate model) or Gemma (listens to audio itself). */
const val ASR_WHISPER = "whisper"
const val ASR_GEMMA = "gemma"
private const val KEY_ASR = "asr_engine"
private const val KEY_WHISPER_FAST = "whisper_fast"

fun loadAsrEngine(context: android.content.Context): String =
  context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    .getString(KEY_ASR, ASR_GEMMA) ?: ASR_GEMMA

fun saveAsrEngine(context: android.content.Context, value: String) {
  context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    .edit()
    .putString(KEY_ASR, value)
    .apply()
}

fun loadWhisperFast(context: android.content.Context): Boolean =
  context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    .getBoolean(KEY_WHISPER_FAST, false)

fun saveWhisperFast(context: android.content.Context, value: Boolean) {
  context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    .edit()
    .putBoolean(KEY_WHISPER_FAST, value)
    .apply()
}

private const val KEY_DIR_MODE = "dir_mode"

fun loadDirMode(context: android.content.Context): DirMode =
  try {
    DirMode.valueOf(
      context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        .getString(KEY_DIR_MODE, DirMode.AUTO.name) ?: DirMode.AUTO.name
    )
  } catch (_: Exception) {
    DirMode.AUTO
  }

fun saveDirMode(context: android.content.Context, value: DirMode) {
  context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    .edit()
    .putString(KEY_DIR_MODE, value.name)
    .apply()
}

private const val KEY_TARGET = "target_lang"

fun loadTarget(context: android.content.Context): TargetLang =
  try {
    TargetLang.valueOf(
      context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        .getString(KEY_TARGET, TargetLang.ZH.name) ?: TargetLang.ZH.name
    )
  } catch (_: Exception) {
    TargetLang.ZH
  }

fun saveTarget(context: android.content.Context, value: TargetLang) {
  context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    .edit()
    .putString(KEY_TARGET, value.name)
    .apply()
}
