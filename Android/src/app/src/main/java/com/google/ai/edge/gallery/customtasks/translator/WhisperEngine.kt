/*
 * Offline Translator: Whisper speech recognition (whisper.cpp via JNI).
 *
 * Pipeline when Whisper is on: microphone -> Whisper (speech -> text) -> Gemma (text -> translation).
 */

package com.google.ai.edge.gallery.customtasks.translator

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "OfflineTranslatorWhisper"

/** Native bridge (Android/src/app/src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
  @Volatile private var loadResult: Boolean? = null
  @Volatile var loadError: String? = null
    private set

  /** Loads libwhisper_jni.so once. Returns false if this phone can't load it. */
  @Synchronized
  fun ensureLoaded(): Boolean {
    loadResult?.let { return it }
    val ok =
      try {
        System.loadLibrary("whisper_jni")
        true
      } catch (e: Throwable) {
        Log.e(TAG, "Cannot load whisper_jni", e)
        loadError = e.message ?: e.javaClass.simpleName
        false
      }
    loadResult = ok
    return ok
  }

  external fun initContext(modelPath: String): Long

  external fun freeContext(ctxPtr: Long)

  external fun transcribe(
    ctxPtr: Long,
    samples: FloatArray,
    language: String,
    initialPrompt: String,
    nThreads: Int,
    audioCtx: Int,
    beamSize: Int,
  ): ByteArray?

  external fun systemInfo(): String

  /** Separate state for language detection with a small (cheap) encoder window. */
  external fun initDetectState(ctxPtr: Long, nThreads: Int, audioCtx: Int): Long

  external fun freeState(statePtr: Long)

  /** Probabilities for every Whisper language (index = language id), or null on failure. */
  external fun detectLanguage(ctxPtr: Long, statePtr: Long, samples: FloatArray, nThreads: Int): FloatArray?

  external fun langId(code: String): Int
}

/** The model file we use. */
object WhisperModel {
  const val FILE_NAME = "ggml-large-v3-turbo-q8_0.bin"
  const val URL =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin"
  const val APPROX_MB = 874
  const val LABEL = "large-v3-turbo (q8_0)"

  /** Smallest plausible size; anything below is a broken file. */
  const val MIN_BYTES = 700L * 1024 * 1024

  fun dir(context: Context): File = File(context.filesDir, "whisper")

  fun file(context: Context): File = File(dir(context), FILE_NAME)

  fun partFile(context: Context): File = File(dir(context), "$FILE_NAME.part")

  fun isReady(context: Context): Boolean {
    val f = file(context)
    return f.exists() && f.length() >= MIN_BYTES
  }

  fun partialBytes(context: Context): Long = partFile(context).let { if (it.exists()) it.length() else 0L }
}

sealed interface WhisperDownloadState {
  data object Idle : WhisperDownloadState

  data class Running(val doneBytes: Long, val totalBytes: Long) : WhisperDownloadState

  data object Done : WhisperDownloadState

  data class Failed(val message: String) : WhisperDownloadState
}

/** Downloads the Whisper model once, with resume. Lives as long as the app process. */
object WhisperDownloader {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var job: Job? = null
  private val _state = MutableStateFlow<WhisperDownloadState>(WhisperDownloadState.Idle)
  val state: StateFlow<WhisperDownloadState> = _state.asStateFlow()

  val isRunning: Boolean
    get() = job?.isActive == true

  fun start(context: Context) {
    if (isRunning) return
    val app = context.applicationContext
    job =
      scope.launch {
        try {
          download(app)
          _state.value = WhisperDownloadState.Done
        } catch (e: CancellationException) {
          _state.value = WhisperDownloadState.Idle
        } catch (e: Throwable) {
          Log.e(TAG, "Download failed", e)
          _state.value = WhisperDownloadState.Failed(e.message ?: e.javaClass.simpleName)
        }
      }
  }

  fun stop() {
    job?.cancel()
    job = null
    _state.value = WhisperDownloadState.Idle
  }

  /** The screen has seen "Done": go back to idle so it isn't handled twice. */
  fun acknowledge() {
    if (_state.value is WhisperDownloadState.Done) _state.value = WhisperDownloadState.Idle
  }

  fun delete(context: Context) {
    stop()
    WhisperEngine.release()
    WhisperModel.file(context).delete()
    WhisperModel.partFile(context).delete()
    _state.value = WhisperDownloadState.Idle
  }

  private suspend fun download(context: Context) {
    val dir = WhisperModel.dir(context)
    dir.mkdirs()
    val part = WhisperModel.partFile(context)
    val target = WhisperModel.file(context)
    var done = if (part.exists()) part.length() else 0L
    _state.value = WhisperDownloadState.Running(done, WhisperModel.APPROX_MB * 1024L * 1024L)

    val conn = openFollowingRedirects(WhisperModel.URL, if (done > 0) done else null)
    try {
      val code = conn.responseCode
      val total: Long
      val append: Boolean
      when (code) {
        HttpURLConnection.HTTP_OK -> {
          // Server ignored the range: start over.
          done = 0L
          append = false
          total = conn.contentLengthLong
        }
        HttpURLConnection.HTTP_PARTIAL -> {
          append = true
          total = parseTotalFromContentRange(conn.getHeaderField("Content-Range")) ?: (done + conn.contentLengthLong)
        }
        416 -> {
          // Range not satisfiable: the .part file is probably already complete.
          conn.disconnect()
          finish(part, target)
          return
        }
        else -> throw IllegalStateException("Сервер ответил $code")
      }

      conn.inputStream.use { input ->
        FileOutputStream(part, append).use { output ->
          val buffer = ByteArray(1 shl 20)
          var lastReport = 0L
          while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            done += n
            if (done - lastReport >= (2L shl 20)) {
              lastReport = done
              _state.value = WhisperDownloadState.Running(done, if (total > 0) total else done)
            }
          }
        }
      }
      if (total > 0 && part.length() < total) {
        throw IllegalStateException("Скачалось не полностью (${part.length() shr 20} из ${total shr 20} МБ). Нажми «Продолжить».")
      }
    } finally {
      conn.disconnect()
    }
    finish(part, target)
  }

  private fun finish(part: File, target: File) {
    if (part.length() < WhisperModel.MIN_BYTES) {
      throw IllegalStateException("Файл модели слишком маленький (${part.length() shr 20} МБ)")
    }
    if (!hasGgmlMagic(part)) {
      part.delete()
      throw IllegalStateException("Файл модели повреждён, скачай заново")
    }
    target.delete()
    if (!part.renameTo(target)) throw IllegalStateException("Не удалось сохранить модель")
  }

  /** whisper.cpp model files start with the uint32 0x67676d6c ("lmgg" on disk). */
  private fun hasGgmlMagic(f: File): Boolean =
    try {
      RandomAccessFile(f, "r").use { raf ->
        val b = ByteArray(4)
        raf.readFully(b)
        b[0] == 0x6c.toByte() && b[1] == 0x6d.toByte() && b[2] == 0x67.toByte() && b[3] == 0x67.toByte()
      }
    } catch (_: Exception) {
      false
    }

  private fun parseTotalFromContentRange(value: String?): Long? =
    value?.substringAfterLast('/')?.trim()?.toLongOrNull()

  /** HuggingFace redirects to a CDN on another host; follow manually so the Range header is kept. */
  private fun openFollowingRedirects(startUrl: String, rangeFrom: Long?): HttpURLConnection {
    var url = URL(startUrl)
    repeat(8) {
      val conn = url.openConnection() as HttpURLConnection
      conn.instanceFollowRedirects = false
      conn.connectTimeout = 20_000
      conn.readTimeout = 60_000
      conn.setRequestProperty("User-Agent", "offline-translator")
      if (rangeFrom != null) conn.setRequestProperty("Range", "bytes=$rangeFrom-")
      val code = conn.responseCode
      if (code in 300..399) {
        val location = conn.getHeaderField("Location") ?: throw IllegalStateException("Редирект без адреса")
        conn.disconnect()
        url = URL(url, location)
      } else {
        return conn
      }
    }
    throw IllegalStateException("Слишком много редиректов")
  }
}

data class WhisperResult(val text: String, val seconds: Float, val audioSeconds: Float)

/**
 * Result of automatic direction detection.
 * [pMe] = probability the speech is mine (Russian/Ukrainian), [pThem] = the other language.
 */
data class DetectResult(
  val direction: Direction,
  val pMe: Float,
  val pThem: Float,
  val topLang: String,
  val seconds: Float,
  /** Whisper language to use for my speech: "uk" if it sounded more Ukrainian, else "ru". */
  val meLang: String = "ru",
) {
  /** Share of the winning side among the two candidates (0.5 = coin flip, 1.0 = sure). */
  val confidence: Float
    get() {
      val sum = pMe + pThem
      return if (sum <= 0f) 0f else maxOf(pMe, pThem) / sum
    }

  fun describe(target: TargetLang): String =
    String.format(
      java.util.Locale.US,
      "RU/UA %.0f%% · %s %.0f%%",
      100f * pMe / maxOf(pMe + pThem, 1e-6f),
      target.code,
      100f * pThem / maxOf(pMe + pThem, 1e-6f),
    )
}

/** Whisper language codes that count as "me" (Russian speaker, may speak Ukrainian / surzhyk). */
val ME_LANGS = listOf("ru", "uk", "be")

/** Whisper language codes that count as "the other person" for each target. */
fun themLangs(target: TargetLang): List<String> =
  when (target) {
    TargetLang.ZH -> listOf("zh", "yue")
    TargetLang.EN -> listOf("en")
    TargetLang.DE -> listOf("de")
  }

/** Encoder window for detection: 500 positions = 10 s of audio (enough to tell languages apart). */
private const val DETECT_AUDIO_CTX = 500

/** Holds the loaded Whisper model and runs recognition. */
object WhisperEngine {
  private val mutex = Mutex()
  @Volatile private var ctxPtr: Long = 0L
  @Volatile private var loadedPath: String? = null
  @Volatile private var detectStatePtr: Long = 0L

  val isLoaded: Boolean
    get() = ctxPtr != 0L

  /** Loads the model in the background so the first phrase is fast. */
  suspend fun preload(context: Context) =
    withContext(Dispatchers.Default) { mutex.withLock { ensureContext(context) } }

  fun release() {
    // Called rarely (delete model). Best effort without the mutex if it's busy.
    val ptr = ctxPtr
    if (ptr != 0L && mutex.tryLock()) {
      try {
        if (detectStatePtr != 0L) {
          WhisperLib.freeState(detectStatePtr)
          detectStatePtr = 0L
        }
        WhisperLib.freeContext(ptr)
        ctxPtr = 0L
        loadedPath = null
      } finally {
        mutex.unlock()
      }
    }
  }

  private fun ensureContext(context: Context): Long {
    if (!WhisperLib.ensureLoaded()) {
      throw IllegalStateException("Whisper не запустился на этом телефоне: ${WhisperLib.loadError}")
    }
    val f = WhisperModel.file(context)
    if (!WhisperModel.isReady(context)) throw IllegalStateException("Модель Whisper не скачана")
    if (ctxPtr != 0L && loadedPath == f.path) return ctxPtr
    if (ctxPtr != 0L) {
      if (detectStatePtr != 0L) {
        WhisperLib.freeState(detectStatePtr)
        detectStatePtr = 0L
      }
      WhisperLib.freeContext(ctxPtr)
      ctxPtr = 0L
    }
    val ptr = WhisperLib.initContext(f.path)
    if (ptr == 0L) throw IllegalStateException("Не удалось открыть модель Whisper (файл повреждён?)")
    ctxPtr = ptr
    loadedPath = f.path
    return ptr
  }

  private fun threads(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)

  private fun ensureDetectState(ptr: Long): Long {
    if (detectStatePtr != 0L) return detectStatePtr
    val st = WhisperLib.initDetectState(ptr, threads(), DETECT_AUDIO_CTX)
    if (st == 0L) throw IllegalStateException("Whisper: не удалось подготовить определение языка")
    detectStatePtr = st
    return st
  }

  /**
   * Decides who is speaking: me (Russian/Ukrainian) or the other person ([target] language).
   * Looks only at the first ~10 s of speech.
   */
  suspend fun detectDirection(context: Context, pcm16: ByteArray, target: TargetLang): DetectResult =
    withContext(Dispatchers.Default) {
      mutex.withLock {
        val start = System.currentTimeMillis()
        val ptr = ensureContext(context)
        val st = ensureDetectState(ptr)
        var samples = trimSilence(pcm16ToFloat(pcm16))
        val maxSamples = TRANSLATOR_SAMPLE_RATE * 10
        if (samples.size > maxSamples) samples = samples.copyOf(maxSamples)
        if (samples.size < TRANSLATOR_SAMPLE_RATE * 3 / 2) samples = samples.copyOf(TRANSLATOR_SAMPLE_RATE * 3 / 2)
        val probs =
          WhisperLib.detectLanguage(ptr, st, samples, threads())
            ?: throw IllegalStateException("Whisper: не удалось определить язык")
        fun probOf(codes: List<String>): Float =
          codes.sumOf { code ->
            val id = WhisperLib.langId(code)
            if (id in probs.indices) probs[id].toDouble() else 0.0
          }.toFloat()
        val pMe = probOf(ME_LANGS)
        val pThem = probOf(themLangs(target))
        var topId = 0
        for (i in probs.indices) if (probs[i] > probs[topId]) topId = i
        val topLang =
          (ME_LANGS + themLangs(target)).firstOrNull { WhisperLib.langId(it) == topId } ?: "id$topId"
        val pRu = probOf(listOf("ru"))
        val pUk = probOf(listOf("uk"))
        DetectResult(
          direction = if (pThem > pMe) Direction.THEM_TO_ME else Direction.ME_TO_THEM,
          pMe = pMe,
          pThem = pThem,
          topLang = topLang,
          seconds = (System.currentTimeMillis() - start) / 1000f,
          meLang = if (pUk > pRu) "uk" else "ru",
        )
      }
    }

  /**
   * Recognizes 16 kHz mono PCM16 audio.
   * [fast] = only process the length of the phrase instead of the full 30 s window.
   */
  suspend fun transcribe(
    context: Context,
    pcm16: ByteArray,
    language: String,
    prompt: String,
    fast: Boolean,
  ): WhisperResult =
    withContext(Dispatchers.Default) {
      mutex.withLock {
        val start = System.currentTimeMillis()
        val ptr = ensureContext(context)
        val samples = trimSilence(pcm16ToFloat(pcm16))
        val audioSeconds = samples.size / TRANSLATOR_SAMPLE_RATE.toFloat()
        // Whisper needs at least ~1 s of audio; pad short clips with silence.
        val padded =
          if (samples.size < TRANSLATOR_SAMPLE_RATE * 3 / 2) samples.copyOf(TRANSLATOR_SAMPLE_RATE * 3 / 2) else samples
        // 1500 encoder positions = 30 s, i.e. 50 per second. Keep a margin of ~3 s.
        val audioCtx =
          if (fast) (((padded.size / TRANSLATOR_SAMPLE_RATE.toFloat()) + 3f) * 50f).toInt().coerceIn(256, 1500)
          else 0
        val nThreads = threads()
        val bytes =
          WhisperLib.transcribe(ptr, padded, language, prompt, nThreads, audioCtx, 1) ?: ByteArray(0)
        val raw = String(bytes, Charsets.UTF_8)
        WhisperResult(
          text = cleanWhisperText(raw, prompt),
          seconds = (System.currentTimeMillis() - start) / 1000f,
          audioSeconds = audioSeconds,
        )
      }
    }
}

private fun pcm16ToFloat(pcm: ByteArray): FloatArray {
  val n = pcm.size / 2
  val out = FloatArray(n)
  for (i in 0 until n) {
    val s = (pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)
    out[i] = s.toShort() / 32768f
  }
  return out
}

/** Cuts silence at the start and end (keeps 0.3 s around speech). Less audio = faster, fewer hallucinations. */
private fun trimSilence(samples: FloatArray): FloatArray {
  val frame = TRANSLATOR_SAMPLE_RATE / 50 // 20 ms
  val frames = samples.size / frame
  if (frames < 10) return samples
  val rms = FloatArray(frames)
  var maxRms = 0f
  for (f in 0 until frames) {
    var sum = 0.0
    val base = f * frame
    for (i in 0 until frame) {
      val v = samples[base + i]
      sum += v * v
    }
    rms[f] = kotlin.math.sqrt(sum / frame).toFloat()
    if (rms[f] > maxRms) maxRms = rms[f]
  }
  if (maxRms < 0.003f) return samples // all quiet: let Whisper decide
  val threshold = maxOf(0.004f, maxRms * 0.08f)
  var first = rms.indexOfFirst { it >= threshold }
  var last = rms.indexOfLast { it >= threshold }
  if (first < 0 || last < 0) return samples
  val pad = 15 // 0.3 s
  first = (first - pad).coerceAtLeast(0)
  last = (last + pad).coerceAtMost(frames - 1)
  val from = first * frame
  val to = minOf(samples.size, (last + 1) * frame)
  return samples.copyOfRange(from, to)
}

/** Typical phrases Whisper "hears" in silence or noise (from subtitles it was trained on). */
private val HALLUCINATIONS =
  listOf(
    Regex("(?i)^продолжение следует\\W*$"),
    Regex("(?i)субтитр"),
    Regex("(?i)редактор субтитров"),
    Regex("(?i)^спасибо за (просмотр|внимание)\\W*$"),
    Regex("(?i)подписывайтесь|подпишитесь|ставьте лайк"),
    Regex("(?i)dimatorzok|amara\\.org"),
    Regex("字幕"),
    Regex("请不吝点赞|订阅|打赏|明镜"),
    Regex("(?i)^thank(s| you) for watching\\W*$"),
    Regex("(?i)untertitel"),
  )

/** Joins segments, drops typical hallucinations, brackets like [музыка], and an echoed prompt. */
fun cleanWhisperText(raw: String, prompt: String): String {
  val promptNorm = prompt.lowercase().filter { it.isLetterOrDigit() }
  return raw
    .lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .filterNot { line -> HALLUCINATIONS.any { it.containsMatchIn(line) } }
    .filterNot { line -> line.matches(Regex("^[\\[(（【].*[\\])）】]$")) }
    .filterNot { line ->
      val norm = line.lowercase().filter { it.isLetterOrDigit() }
      norm.length >= 12 && promptNorm.contains(norm)
    }
    .joinToString(" ")
    .replace(Regex("\\s+"), " ")
    .trim()
}

/** Whisper language code for the side that is speaking. */
fun whisperLanguage(direction: Direction, target: TargetLang): String =
  if (direction == Direction.ME_TO_THEM) "ru"
  else
    when (target) {
      TargetLang.EN -> "en"
      TargetLang.ZH -> "zh"
      TargetLang.DE -> "de"
    }

/**
 * A short hint for Whisper: the kind of words to expect. For Russian we take terms and names
 * from the user's dictionary, so it hears "лоток", "липучка", "Чен" correctly.
 */
fun whisperPrompt(direction: Direction, target: TargetLang, glossary: String): String {
  if (direction == Direction.THEM_TO_ME) {
    return when (target) {
      TargetLang.ZH -> "以下是普通话的句子，关于数据中心的安装工作：服务器机柜、桥架、光纤、网线、电源线、标签。"
      TargetLang.EN -> "Data center installation work: server racks, cable trays, fiber, network and power cables, labels."
      TargetLang.DE -> "Montagearbeiten im Rechenzentrum: Serverschränke, Kabeltrassen, Glasfaser, Netzwerk- und Stromkabel."
    }
  }
  val terms =
    glossary
      .lines()
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
      .flatMap { line -> line.substringBefore("=").split("/").map { it.trim() } }
      .map { it.replace(Regex("\\(.*?\\)"), "").trim() }
      .filter { it.isNotEmpty() && it.length <= 30 }
      .distinct()
      // Names first (they are the hardest to hear), then the terms.
      .sortedBy { if (it.first().isUpperCase()) 0 else 1 }
  val sb = StringBuilder("Работа на объекте, монтаж в дата-центре.")
  for (t in terms) {
    if (sb.length + t.length + 2 > 360) break
    sb.append(if (sb.endsWith(".")) " " else ", ").append(t)
  }
  sb.append(".")
  return sb.toString()
}
