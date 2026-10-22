/*
 * Offline Translator task, built on top of Google AI Edge Gallery (Apache 2.0).
 */

package com.google.ai.edge.gallery.customtasks.translator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EPHEMERAL_KEY = "aicore_ephemeral_inference"

/** Extra facts about one translation, kept in the history (for testing quality later). */
private data class Meta(
  val auto: Boolean = false,
  val detectInfo: String? = null,
  val asrEngine: String = "text",
  val asrRaw: String? = null,
  val audio: List<String> = emptyList(),
  val asrSeconds: Float? = null,
  val pcmKey: String? = null,
)

private fun flip(d: Direction): Direction =
  if (d == Direction.ME_TO_THEM) Direction.THEM_TO_ME else Direction.ME_TO_THEM

private fun dirLabel(d: Direction, t: TargetLang): String =
  if (d == Direction.ME_TO_THEM) "RU → ${t.code}" else "${t.code} → RU"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslatorScreen(modelManagerViewModel: ModelManagerViewModel, bottomPadding: Dp) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val mmState by modelManagerViewModel.uiState.collectAsState()
  val model = mmState.selectedModel
  val initStatus by model.initStatusFlow.collectAsState()
  val ready = initStatus is Model.InitializationStatus.Initialized

  var dirMode by remember { mutableStateOf(loadDirMode(context)) }
  var target by remember { mutableStateOf(loadTarget(context)) }
  var recording by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var elapsedMs by remember { mutableLongStateOf(0L) }
  var level by remember { mutableFloatStateOf(0f) }
  var streaming by remember { mutableStateOf("") }
  var typed by remember { mutableStateOf("") }
  val history = remember { mutableStateListOf<HistoryItem>() }
  val recorder = remember { MicRecorder() }
  var glossary by remember { mutableStateOf(loadGlossary(context)) }
  var showGlossary by remember { mutableStateOf(false) }
  var reviewMode by remember { mutableStateOf(loadReviewMode(context)) }
  var busyLabel by remember { mutableStateOf("Перевожу…") }

  // What is in the text field came from speech (review mode): remember how, for the history.
  var fieldFromSpeech by remember { mutableStateOf(false) }
  var fieldEngine by remember { mutableStateOf("text") }
  var fieldAsrRaw by remember { mutableStateOf("") }
  val fieldAudio = remember { mutableStateListOf<String>() }
  var fieldDetect by remember { mutableStateOf<String?>(null) }
  var fieldAsrSeconds by remember { mutableStateOf<Float?>(null) }

  fun clearField() {
    typed = ""
    fieldFromSpeech = false
    fieldEngine = "text"
    fieldAsrRaw = ""
    fieldAudio.clear()
    fieldDetect = null
    fieldAsrSeconds = null
  }

  // History, voice and crash reports.
  var saveVoice by remember { mutableStateOf(loadSaveVoice(context)) }
  var voiceDays by remember { mutableIntStateOf(loadVoiceDays(context)) }
  var showHistory by remember { mutableStateOf(false) }
  var issues by remember { mutableStateOf<List<DiagIssue>>(emptyList()) }
  var exporting by remember { mutableStateOf(false) }
  val recentPcm = remember { LinkedHashMap<String, ByteArray>() }

  fun rememberPcm(key: String, pcm: ByteArray) {
    recentPcm[key] = pcm
    while (recentPcm.size > 10) recentPcm.remove(recentPcm.keys.first())
  }

  // Speech recognition: Whisper (separate model) or Gemma (listens itself).
  var asrEngine by remember { mutableStateOf(loadAsrEngine(context)) }
  var whisperFast by remember { mutableStateOf(loadWhisperFast(context)) }
  var whisperReady by remember { mutableStateOf(WhisperModel.isReady(context)) }
  var showWhisper by remember { mutableStateOf(false) }
  val dlState by WhisperDownloader.state.collectAsState()
  val useWhisper = asrEngine == ASR_WHISPER && whisperReady

  fun reportError(message: String, e: Throwable? = null) {
    TranslatorDiagnostics.error(message, e)
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
  }

  // Load saved history, clean old recordings, check how the app ended last time.
  LaunchedEffect(Unit) {
    val loaded =
      withContext(Dispatchers.IO) {
        HistoryStore.cleanupAudio(context, voiceDays)
        HistoryStore.load(context, 300)
      }
    history.addAll(loaded)
    issues = withContext(Dispatchers.IO) { TranslatorDiagnostics.collectNewIssues(context) }
  }

  LaunchedEffect(dlState) {
    val st = dlState
    if (st is WhisperDownloadState.Done) {
      whisperReady = WhisperModel.isReady(context)
      if (whisperReady) {
        asrEngine = ASR_WHISPER
        saveAsrEngine(context, ASR_WHISPER)
        Toast.makeText(context, "Whisper скачан и включён ✓", Toast.LENGTH_LONG).show()
        TranslatorDiagnostics.log("whisper downloaded")
      }
      WhisperDownloader.acknowledge()
    } else if (st is WhisperDownloadState.Failed) {
      TranslatorDiagnostics.error("whisper download: ${st.message}")
    }
  }
  // Load Whisper in the background so the first phrase doesn't wait for it.
  LaunchedEffect(useWhisper) {
    if (useWhisper && !WhisperEngine.isLoaded) {
      try {
        val t0 = System.currentTimeMillis()
        WhisperEngine.preload(context)
        TranslatorDiagnostics.log("whisper loaded in ${System.currentTimeMillis() - t0} ms")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        reportError("Whisper: ${e.message}", e)
      }
    }
  }
  // Keep the screen on while the model downloads.
  val view = LocalView.current
  val downloading = dlState is WhisperDownloadState.Running
  DisposableEffect(downloading) {
    view.keepScreenOn = downloading
    onDispose { view.keepScreenOn = false }
  }

  // Text-to-speech (uses the phone's installed voices; works offline if voices are downloaded).
  var ttsReady by remember { mutableStateOf(false) }
  val tts = remember {
    TextToSpeech(context.applicationContext) { status -> ttsReady = status == TextToSpeech.SUCCESS }
  }
  DisposableEffect(Unit) {
    onDispose {
      recorder.stop()
      tts.shutdown()
    }
  }

  fun speak(text: String, item: HistoryItem) {
    val locale = if (item.direction == Direction.ME_TO_THEM) item.target.locale else RUSSIAN_LOCALE
    if (!ttsReady) {
      Toast.makeText(context, "Озвучка ещё не готова", Toast.LENGTH_SHORT).show()
      return
    }
    val res = tts.setLanguage(locale)
    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
      Toast.makeText(
          context,
          "Нет голоса для языка ${locale.displayLanguage}. Установи его в настройках синтеза речи.",
          Toast.LENGTH_LONG,
        )
        .show()
      return
    }
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tr_${item.id}")
  }

  fun addHistory(item: HistoryItem) {
    history.add(0, item)
    scope.launch(Dispatchers.IO) { HistoryStore.append(context, item) }
    TranslatorDiagnostics.log(
      "translated ${dirLabel(item.direction, item.target)} via ${item.asrEngine}" +
        (if (item.auto) " auto(${item.detectInfo ?: "gemma"})" else "") +
        String.format(Locale.US, " asr=%s tr=%.1fs", item.asrSeconds?.let { "%.1fs".format(Locale.US, it) } ?: "-", item.seconds) +
        (if (!item.formatOk) " FORMAT!" else "")
    )
  }

  /**
   * Gemma translates [prompt] (+ optional audio). [dir] = null means "Авто" with Gemma listening:
   * the direction is read from the reply.
   */
  fun runTranslation(prompt: String, audio: ByteArray?, dir: Direction?, tgt: TargetLang, meta: Meta) {
    busy = true
    busyLabel = "Перевожу…"
    streaming = ""
    val start = System.currentTimeMillis()
    var buffer = ""
    val modelName = model.name
    scope.launch(Dispatchers.Default) {
      try {
        model.runtimeHelper.runInference(
          model = model,
          input = prompt,
          resultListener = { partial, done, _ ->
            buffer += partial
            val snapshot = buffer
            scope.launch(Dispatchers.Main) {
              streaming = snapshot
              if (done) {
                val d = dir ?: (directionFromAutoReply(snapshot, tgt) ?: Direction.ME_TO_THEM)
                val r = parseReply(snapshot, d, tgt)
                addHistory(
                  HistoryItem(
                    id = System.currentTimeMillis(),
                    direction = d,
                    target = tgt,
                    source = r.source,
                    translation = r.translation,
                    seconds = (System.currentTimeMillis() - start) / 1000f,
                    formatOk = r.formatOk,
                    asrSeconds = meta.asrSeconds,
                    auto = meta.auto,
                    detectInfo = meta.detectInfo,
                    asrEngine = meta.asrEngine,
                    asrRaw = meta.asrRaw,
                    audio = meta.audio,
                    model = modelName,
                    pcmKey = meta.pcmKey,
                  )
                )
                streaming = ""
                busy = false
              }
            }
          },
          cleanUpListener = { scope.launch(Dispatchers.Main) { busy = false } },
          onError = { msg ->
            scope.launch(Dispatchers.Main) {
              busy = false
              streaming = ""
              reportError("Ошибка: $msg")
            }
          },
          audioClips = if (audio != null) listOf(audio) else listOf(),
          coroutineScope = scope,
          extraContext = mapOf(EPHEMERAL_KEY to "true"),
        )
      } catch (e: Exception) {
        scope.launch(Dispatchers.Main) {
          busy = false
          streaming = ""
          reportError("Ошибка модели: ${e.message}", e)
        }
      }
    }
  }

  /** Review mode, Gemma listening: speech -> text in the field. */
  fun runTranscription(prompt: String, wav: ByteArray, auto: Boolean, tgt: TargetLang, audioNames: List<String>) {
    busy = true
    busyLabel = "Распознаю…"
    streaming = ""
    val start = System.currentTimeMillis()
    var buffer = ""
    scope.launch(Dispatchers.Default) {
      try {
        model.runtimeHelper.runInference(
          model = model,
          input = prompt,
          resultListener = { partial, done, _ ->
            buffer += partial
            val snapshot = buffer
            scope.launch(Dispatchers.Main) {
              streaming = snapshot
              if (done) {
                val text = cleanTranscript(snapshot)
                if (text.isNotEmpty()) {
                  typed = if (typed.isBlank()) text else typed.trimEnd() + " " + text
                  fieldFromSpeech = true
                  fieldEngine = "gemma"
                  fieldAsrRaw = (fieldAsrRaw + " " + text).trim()
                  fieldAudio.addAll(audioNames)
                  if (auto) {
                    val d = directionFromAutoReply(snapshot, tgt)
                    fieldDetect = d?.let { "Gemma: ${dirLabel(it, tgt)}" }
                  }
                  fieldAsrSeconds = (fieldAsrSeconds ?: 0f) + (System.currentTimeMillis() - start) / 1000f
                }
                streaming = ""
                busy = false
                if (text.isEmpty()) {
                  Toast.makeText(context, "Ничего не распознано", Toast.LENGTH_SHORT).show()
                }
              }
            }
          },
          cleanUpListener = { scope.launch(Dispatchers.Main) { busy = false } },
          onError = { msg ->
            scope.launch(Dispatchers.Main) {
              busy = false
              streaming = ""
              reportError("Ошибка: $msg")
            }
          },
          audioClips = listOf(wav),
          coroutineScope = scope,
          extraContext = mapOf(EPHEMERAL_KEY to "true"),
        )
      } catch (e: Exception) {
        scope.launch(Dispatchers.Main) {
          busy = false
          streaming = ""
          reportError("Ошибка модели: ${e.message}", e)
        }
      }
    }
  }

  /**
   * Whisper path: (detect language) -> speech -> text (Whisper) -> translation (Gemma).
   * [fixedDir] = null means "Авто": Whisper decides who is speaking.
   */
  fun runWhisper(
    pcm: ByteArray,
    fixedDir: Direction?,
    tgt: TargetLang,
    review: Boolean,
    audioNames: List<String>,
    pcmKey: String?,
  ) {
    busy = true
    busyLabel = if (fixedDir == null) "Определяю язык…" else "Распознаю (Whisper)…"
    streaming = ""
    scope.launch {
      val outcome: Triple<Direction, WhisperResult, DetectResult?> =
        try {
          val detected: DetectResult? =
            if (fixedDir != null) null else WhisperEngine.detectDirection(context, pcm, tgt)
          val d = fixedDir ?: detected!!.direction
          busyLabel = "Распознаю (Whisper)…"
          val lang =
            if (d == Direction.ME_TO_THEM && detected != null) detected.meLang else whisperLanguage(d, tgt)
          val r =
            WhisperEngine.transcribe(
              context = context,
              pcm16 = pcm,
              language = lang,
              prompt = whisperPrompt(d, tgt, glossary),
              fast = whisperFast,
            )
          Triple(d, r, detected)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          busy = false
          reportError("Whisper: ${e.message}", e)
          return@launch
        }
      val (dir, result, detect) = outcome
      val asrTotal = result.seconds + (detect?.seconds ?: 0f)
      val detectInfo = detect?.describe(tgt)
      if (result.text.isBlank()) {
        busy = false
        TranslatorDiagnostics.log("whisper: nothing recognized (${dirLabel(dir, tgt)}, ${detectInfo ?: "fixed"})")
        Toast.makeText(context, "Ничего не распознано", Toast.LENGTH_SHORT).show()
        return@launch
      }
      if (review) {
        typed = if (typed.isBlank()) result.text else typed.trimEnd() + " " + result.text
        fieldFromSpeech = true
        fieldEngine = "whisper"
        fieldAsrRaw = (fieldAsrRaw + " " + result.text).trim()
        fieldAudio.addAll(audioNames)
        if (detectInfo != null) fieldDetect = detectInfo
        fieldAsrSeconds = (fieldAsrSeconds ?: 0f) + asrTotal
        busy = false
        Toast.makeText(
            context,
            String.format(Locale.US, "Whisper: %.1f с", asrTotal) + (detectInfo?.let { " · $it" } ?: ""),
            Toast.LENGTH_SHORT,
          )
          .show()
      } else {
        runTranslation(
          buildPrompt(dir, tgt, typedText = result.text, glossary = glossary, fromSpeech = true),
          null,
          dir,
          tgt,
          Meta(
            auto = fixedDir == null,
            detectInfo = detectInfo,
            asrEngine = "whisper",
            asrRaw = result.text,
            audio = audioNames,
            asrSeconds = asrTotal,
            pcmKey = pcmKey,
          ),
        )
      }
    }
  }

  fun fixedDirection(mode: DirMode): Direction? =
    when (mode) {
      DirMode.AUTO -> null
      DirMode.ME -> Direction.ME_TO_THEM
      DirMode.THEM -> Direction.THEM_TO_ME
    }

  fun startRecording() {
    recording = true
    elapsedMs = 0L
    val fixedAtStart = fixedDirection(dirMode)
    val tgtAtStart = target
    val reviewAtStart = reviewMode
    val whisperAtStart = useWhisper
    val saveVoiceAtStart = saveVoice
    scope.launch {
      val error =
        withContext(Dispatchers.IO) {
          try {
            recorder.recordBlocking(
              onLevel = { level = it },
              onElapsed = { elapsedMs = it },
            )
            null
          } catch (e: Exception) {
            e.message ?: "ошибка микрофона"
          }
        }
      recording = false
      level = 0f
      val pcm = recorder.takePcm()
      if (error != null) {
        reportError("Не удалось записать: $error")
        return@launch
      }
      if (elapsedMs < 500) {
        Toast.makeText(context, "Слишком коротко", Toast.LENGTH_SHORT).show()
        return@launch
      }
      val id = System.currentTimeMillis()
      val key = "$id.wav"
      rememberPcm(key, pcm)
      val audioNames =
        if (saveVoiceAtStart) listOfNotNull(withContext(Dispatchers.IO) { HistoryStore.saveAudio(context, id, pcm) })
        else emptyList()
      when {
        whisperAtStart -> runWhisper(pcm, fixedAtStart, tgtAtStart, reviewAtStart, audioNames, key)
        reviewAtStart -> {
          val prompt =
            if (fixedAtStart == null) buildAutoTranscribePrompt(tgtAtStart)
            else buildTranscribePrompt(fixedAtStart, tgtAtStart)
          runTranscription(prompt, pcmToWav(pcm, TRANSLATOR_SAMPLE_RATE), fixedAtStart == null, tgtAtStart, audioNames)
        }
        else -> {
          val prompt =
            if (fixedAtStart == null) buildAutoAudioPrompt(tgtAtStart, glossary)
            else buildPrompt(fixedAtStart, tgtAtStart, glossary = glossary)
          runTranslation(
            prompt,
            pcmToWav(pcm, TRANSLATOR_SAMPLE_RATE),
            fixedAtStart,
            tgtAtStart,
            Meta(auto = fixedAtStart == null, asrEngine = "gemma", audio = audioNames, pcmKey = key),
          )
        }
      }
    }
  }

  /** "⇄": the direction was wrong — translate the same phrase the other way. */
  fun reverse(item: HistoryItem) {
    if (busy || recording || !ready) return
    val newDir = flip(item.direction)
    val tgt = item.target
    scope.launch {
      val pcm =
        item.pcmKey?.let { recentPcm[it] }
          ?: item.audio.firstOrNull()?.let { name -> withContext(Dispatchers.IO) { HistoryStore.readAudioPcm(context, name) } }
      TranslatorDiagnostics.log("reverse ${dirLabel(item.direction, tgt)} -> ${dirLabel(newDir, tgt)} (pcm=${pcm != null}, ${item.asrEngine})")
      when {
        pcm != null && item.asrEngine == "whisper" && whisperReady ->
          runWhisper(pcm, newDir, tgt, false, item.audio, item.pcmKey)
        pcm != null && (item.asrEngine == "gemma" || item.asrEngine == "whisper") ->
          runTranslation(
            buildPrompt(newDir, tgt, glossary = glossary),
            pcmToWav(pcm, TRANSLATOR_SAMPLE_RATE),
            newDir,
            tgt,
            Meta(asrEngine = "gemma", audio = item.audio, pcmKey = item.pcmKey),
          )
        else ->
          runTranslation(
            buildPrompt(
              newDir,
              tgt,
              typedText = item.asrRaw ?: item.source,
              glossary = glossary,
              fromSpeech = item.asrEngine != "text",
            ),
            null,
            newDir,
            tgt,
            Meta(asrEngine = item.asrEngine, asrRaw = item.asrRaw, audio = item.audio),
          )
      }
    }
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) startRecording()
      else reportError("Нужен доступ к микрофону")
    }

  fun onMicClick() {
    if (recording) {
      recorder.stop()
      return
    }
    if (!ready || busy) return
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    ) {
      startRecording()
    } else {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  fun exportHistory(withAudio: Boolean) {
    if (exporting) return
    exporting = true
    val info =
      "Настройки:\n" +
        "  направление: $dirMode, язык: $target\n" +
        "  проверять текст: $reviewMode\n" +
        "  распознавание: $asrEngine (Whisper скачан: $whisperReady, быстрый: $whisperFast)\n" +
        "  модель перевода: ${model.name}\n" +
        "  сохранять голос: $saveVoice, дней: $voiceDays\n\n" +
        "Словарь:\n$glossary"
    scope.launch {
      try {
        val zip = withContext(Dispatchers.IO) { HistoryStore.exportZip(context, withAudio, info) }
        TranslatorDiagnostics.log("export ${zip.name} (${zip.length() / 1024} KB, audio=$withAudio)")
        HistoryStore.share(context, zip)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        reportError("Не удалось собрать архив: ${e.message}", e)
      } finally {
        exporting = false
      }
    }
  }

  if (issues.isNotEmpty()) {
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.US) }
    AlertDialog(
      onDismissRequest = { issues = emptyList() },
      title = { Text("В прошлый раз что-то пошло не так") },
      text = {
        Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
          for (issue in issues.take(5)) {
            Text("${fmt.format(Date(issue.time))} — ${issue.title}", fontWeight = FontWeight.Medium)
            if (issue.details.isNotBlank()) {
              Text(issue.details, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
          }
          Text(
            "Подробный отчёт сохранён. Его можно отправить: «История» (значок часов) → «Отправить».",
            fontSize = 13.sp,
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            issues = emptyList()
            exportHistory(withAudio = false)
          }
        ) {
          Text("Отправить отчёт")
        }
      },
      dismissButton = { TextButton(onClick = { issues = emptyList() }) { Text("ОК") } },
    )
  }

  if (showHistory) {
    HistoryDialog(
      count = history.size,
      saveVoice = saveVoice,
      onSaveVoice = {
        saveVoice = it
        saveSaveVoice(context, it)
      },
      voiceDays = voiceDays,
      onVoiceDays = {
        voiceDays = it
        saveVoiceDays(context, it)
        scope.launch(Dispatchers.IO) { HistoryStore.cleanupAudio(context, it) }
      },
      exporting = exporting,
      onExport = { withAudio -> exportHistory(withAudio) },
      onClear = {
        history.clear()
        recentPcm.clear()
        scope.launch(Dispatchers.IO) { HistoryStore.clear(context) }
        TranslatorDiagnostics.log("history cleared")
      },
      onDismiss = { showHistory = false },
    )
  }

  if (showWhisper) {
    WhisperDialog(
      state = dlState,
      ready = whisperReady,
      partialBytes = WhisperModel.partialBytes(context),
      fast = whisperFast,
      onFastChange = {
        whisperFast = it
        saveWhisperFast(context, it)
      },
      onDownload = { WhisperDownloader.start(context) },
      onStop = { WhisperDownloader.stop() },
      onDelete = {
        WhisperDownloader.delete(context)
        whisperReady = false
        asrEngine = ASR_GEMMA
        saveAsrEngine(context, ASR_GEMMA)
      },
      onDismiss = { showWhisper = false },
    )
  }

  if (showGlossary) {
    var draft by remember { mutableStateOf(glossary) }
    AlertDialog(
      onDismissRequest = { showGlossary = false },
      title = { Text("Мой словарь") },
      text = {
        Column {
          Text(
            "Одна строка — один термин: по-русски = English = 中文. Переводчик будет использовать именно эти слова.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(8.dp))
          OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().height(320.dp),
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            glossary = draft
            saveGlossary(context, draft)
            showGlossary = false
            Toast.makeText(context, "Словарь сохранён", Toast.LENGTH_SHORT).show()
          }
        ) {
          Text("Сохранить")
        }
      },
      dismissButton = {
        Row {
          TextButton(onClick = { draft = DEFAULT_GLOSSARY }) { Text("Сброс") }
          TextButton(onClick = { showGlossary = false }) { Text("Отмена") }
        }
      },
    )
  }

  val imeVisible = WindowInsets.isImeVisible
  val controlsEnabled = !recording && !busy
  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(bottom = if (imeVisible) 0.dp else bottomPadding)
        .imePadding()
        .padding(horizontal = 16.dp)
  ) {
    // Direction: Авто / Я → X / X → Я.
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
      val options =
        listOf(
          DirMode.AUTO to "Авто",
          DirMode.ME to "Я → ${target.label}",
          DirMode.THEM to "${target.label} → Я",
        )
      options.forEachIndexed { index, (mode, label) ->
        SegmentedButton(
          selected = dirMode == mode,
          onClick = {
            dirMode = mode
            saveDirMode(context, mode)
          },
          enabled = controlsEnabled,
          shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
          icon = {},
        ) {
          Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
      }
    }

    // Language chips.
    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      for (lang in TargetLang.entries) {
        FilterChip(
          selected = target == lang,
          onClick = {
            target = lang
            saveTarget(context, lang)
          },
          enabled = controlsEnabled,
          label = { Text(lang.label) },
        )
      }
      Spacer(Modifier.weight(1f))
      TextButton(onClick = { showGlossary = true }) { Text("Словарь") }
    }

    // History.
    LazyColumn(
      modifier = Modifier.weight(1f).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (busy && streaming.isNotEmpty()) {
        item(key = "streaming") {
          Card(modifier = Modifier.fillMaxWidth()) {
            Text(streaming, modifier = Modifier.padding(12.dp), fontSize = 16.sp)
          }
        }
      }
      if (history.isEmpty() && !busy) {
        item(key = "empty") {
          Text(
            when {
              !ready -> "Сначала загрузи модель (вверху выбери Gemma 4 E4B или E2B)."
              dirMode == DirMode.AUTO ->
                "Авто: нажми микрофон и говори сам — или дай сказать собеседнику. Кто говорит, определится само. " +
                  "Если перевело не в ту сторону — нажми ⇄ на карточке."
              else ->
                "Нажми на микрофон, скажи фразу и нажми ещё раз. До 30 секунд. Можно записать несколько кусков подряд — текст допишется."
            },
            modifier = Modifier.padding(vertical = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      items(history, key = { it.id }) { item ->
        HistoryCard(
          item = item,
          onSpeak = { speak(item.translation, item) },
          onReverse = { reverse(item) },
          reverseEnabled = controlsEnabled && ready,
        )
      }
    }

    // Review-before-translate toggle.
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
      Text(
        "Проверять текст перед переводом",
        fontSize = 14.sp,
        modifier = Modifier.weight(1f),
      )
      Switch(
        checked = reviewMode,
        onCheckedChange = {
          reviewMode = it
          saveReviewMode(context, it)
        },
        enabled = controlsEnabled,
      )
    }

    // Speech recognition engine + history + Whisper settings.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Text("Распознавание:", fontSize = 14.sp)
      Spacer(Modifier.size(8.dp))
      val st = dlState
      FilterChip(
        selected = useWhisper,
        onClick = {
          if (whisperReady) {
            asrEngine = ASR_WHISPER
            saveAsrEngine(context, ASR_WHISPER)
          } else {
            showWhisper = true
          }
        },
        enabled = controlsEnabled,
        label = {
          Text(
            when {
              st is WhisperDownloadState.Running && st.totalBytes > 0 ->
                "Whisper ${(st.doneBytes * 100 / st.totalBytes).coerceIn(0, 100)}%"
              !whisperReady -> "Whisper ⬇"
              else -> "Whisper"
            }
          )
        },
      )
      Spacer(Modifier.size(8.dp))
      FilterChip(
        selected = !useWhisper,
        onClick = {
          asrEngine = ASR_GEMMA
          saveAsrEngine(context, ASR_GEMMA)
        },
        enabled = controlsEnabled,
        label = { Text("Gemma") },
      )
      Spacer(Modifier.weight(1f))
      IconButton(onClick = { showHistory = true }) {
        Icon(Icons.Outlined.History, contentDescription = "История")
      }
      IconButton(onClick = { showWhisper = true }) {
        Icon(Icons.Outlined.Settings, contentDescription = "Настройки Whisper")
      }
    }

    // Where the typed / recognized text will go (Авто decides by alphabet).
    if (typed.isNotBlank()) {
      val d = fixedDirection(dirMode) ?: detectTextDirection(typed, target)
      Text(
        "Переведу: ${dirLabel(d, target)}" + (fieldDetect?.let { "  ·  $it" } ?: ""),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    // Typed input.
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = typed,
        onValueChange = {
          typed = it
          if (it.isBlank()) clearField()
        },
        modifier = Modifier.weight(1f),
        placeholder = {
          Text(if (reviewMode) "Скажи в микрофон — текст появится тут, поправь и жми ➤" else "Или напиши текст…")
        },
        maxLines = 5,
      )
      IconButton(
        enabled = ready && !busy && !recording && typed.isNotBlank(),
        onClick = {
          val text = typed.trim()
          val dir = fixedDirection(dirMode) ?: detectTextDirection(text, target)
          val fromSpeech = fieldFromSpeech
          val meta =
            Meta(
              auto = dirMode == DirMode.AUTO,
              detectInfo = fieldDetect,
              asrEngine = if (fromSpeech) fieldEngine else "text",
              asrRaw = if (fromSpeech) fieldAsrRaw.ifBlank { null } else null,
              audio = fieldAudio.toList(),
              asrSeconds = if (fromSpeech) fieldAsrSeconds else null,
            )
          val tgt = target
          clearField()
          runTranslation(
            buildPrompt(dir, tgt, typedText = text, glossary = glossary, fromSpeech = fromSpeech),
            null,
            dir,
            tgt,
            meta,
          )
        },
      ) {
        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Перевести")
      }
    }

    // Mic button (hidden while the keyboard is open, to give the text field room).
    if (!imeVisible || busy || recording)
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
      if (busy) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          CircularProgressIndicator(modifier = Modifier.size(56.dp))
          Spacer(Modifier.height(6.dp))
          Text(busyLabel)
        }
      } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          val scale = 1f + (if (recording) level.coerceIn(0f, 1f) * 0.4f else 0f)
          FloatingActionButton(
            onClick = { onMicClick() },
            shape = CircleShape,
            containerColor =
              if (recording) Color(0xFFD32F2F)
              else if (ready) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size((72 * scale).dp),
          ) {
            Icon(
              if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic,
              contentDescription = if (recording) "Стоп" else "Говорить",
              tint = Color.White,
              modifier = Modifier.size(36.dp),
            )
          }
          Spacer(Modifier.height(6.dp))
          Text(
            if (recording) "Запись ${elapsedMs / 1000} / $MAX_RECORD_SECONDS с — нажми, чтобы закончить"
            else if (!ready) "Модель не загружена"
            else if (dirMode == DirMode.AUTO) "Говорить (авто)"
            else "Говорить",
            fontSize = 13.sp,
          )
        }
      }
    }
  }
}

@Composable
private fun HistoryCard(
  item: HistoryItem,
  onSpeak: () -> Unit,
  onReverse: () -> Unit,
  reverseEnabled: Boolean,
) {
  val context = LocalContext.current
  val srcLabel = if (item.direction == Direction.ME_TO_THEM) "RU" else item.target.code
  val dstLabel = if (item.direction == Direction.ME_TO_THEM) item.target.code else "RU"
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      if (item.auto) {
        Text(
          "авто: $srcLabel → $dstLabel" + (item.detectInfo?.let { "  ($it)" } ?: ""),
          fontSize = 11.sp,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
      }
      if (item.source.isNotEmpty()) {
        Text(
          "$srcLabel: ${item.source}",
          fontSize = 14.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
      }
      if (!item.formatOk) {
        Text(
          "⚠ Модель ответила не по формату — проверь перевод",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.error,
        )
      }
      Text("$dstLabel: ${item.translation}", fontSize = 20.sp, fontWeight = FontWeight.Medium)
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          if (item.asrSeconds != null)
            String.format(Locale.US, "распознавание %.1f с + перевод %.1f с", item.asrSeconds, item.seconds)
          else String.format(Locale.US, "%.1f с", item.seconds),
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onReverse, enabled = reverseEnabled) {
          Icon(Icons.Outlined.SwapHoriz, contentDescription = "Перевести в обратную сторону")
        }
        IconButton(onClick = onSpeak) {
          Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = "Озвучить")
        }
        IconButton(
          onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("translation", item.translation))
            Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
          }
        ) {
          Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать")
        }
      }
    }
  }
}

@Composable
private fun HistoryDialog(
  count: Int,
  saveVoice: Boolean,
  onSaveVoice: (Boolean) -> Unit,
  voiceDays: Int,
  onVoiceDays: (Int) -> Unit,
  exporting: Boolean,
  onExport: (withAudio: Boolean) -> Unit,
  onClear: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var confirmClear by remember { mutableStateOf(false) }
  val voiceMb = remember(count, saveVoice) { HistoryStore.audioBytes(context) / (1024 * 1024) }
  val reports = remember { TranslatorDiagnostics.reportCount(context) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("История и отчёты") },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text("Переводов сохранено: $count (хранятся всегда, пока не очистишь).", fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Сохранять голосовые", fontSize = 14.sp)
            Text(
              "Записи (твои и собеседника) — чтобы потом проверить распознавание. Сейчас занимают $voiceMb МБ.",
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = saveVoice, onCheckedChange = onSaveVoice)
        }
        if (saveVoice) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Хранить:", fontSize = 13.sp)
            for (d in listOf(1, 7, 30)) {
              FilterChip(
                selected = voiceDays == d,
                onClick = { onVoiceDays(d) },
                label = { Text(if (d == 1) "1 день" else "$d дней") },
              )
            }
          }
        }
        Spacer(Modifier.height(10.dp))
        Text(
          "Отчётов о сбоях: $reports. Ошибки и вылеты записываются сами и попадают в архив.",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        if (exporting) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          Text("Собираю архив…", fontSize = 12.sp)
        } else {
          Text(
            "Отправить архив (история + отчёты) — например, в чат с ИИ для разбора:",
            fontSize = 13.sp,
          )
          TextButton(onClick = { onExport(true) }) { Text("Отправить всё (с голосом)") }
          TextButton(onClick = { onExport(false) }) { Text("Отправить только текст и отчёты") }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
          onClick = {
            if (confirmClear) {
              onClear()
              confirmClear = false
            } else confirmClear = true
          }
        ) {
          Text(
            if (confirmClear) "Точно удалить всю историю и голос? Нажми ещё раз" else "Очистить историю",
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
  )
}

@Composable
private fun WhisperDialog(
  state: WhisperDownloadState,
  ready: Boolean,
  partialBytes: Long,
  fast: Boolean,
  onFastChange: (Boolean) -> Unit,
  onDownload: () -> Unit,
  onStop: () -> Unit,
  onDelete: () -> Unit,
  onDismiss: () -> Unit,
) {
  var confirmDelete by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Распознавание Whisper") },
    text = {
      Column {
        Text(
          "Отдельная модель только для распознавания речи: русский (и суржик), 中文, English, Deutsch. " +
            "Слушает Whisper, переводит Gemma. Скачивается один раз (~${WhisperModel.APPROX_MB} МБ), дальше работает без интернета.",
          fontSize = 13.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        when {
          state is WhisperDownloadState.Running -> {
            val progress =
              if (state.totalBytes > 0) (state.doneBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text("Скачиваю: ${state.doneBytes shr 20} из ${state.totalBytes shr 20} МБ. Экран не гаснет до конца.")
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onStop) { Text("Пауза") }
          }
          ready -> {
            Text("✓ Модель скачана: ${WhisperModel.LABEL}", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Быстрый режим", fontSize = 14.sp)
                Text(
                  "Обрабатывает только длину фразы, а не 30 с. Быстрее, но может ошибаться чаще.",
                  fontSize = 12.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(checked = fast, onCheckedChange = onFastChange)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
              onClick = {
                if (confirmDelete) onDelete() else confirmDelete = true
              }
            ) {
              Text(
                if (confirmDelete) "Точно удалить? Нажми ещё раз" else "Удалить модель",
                color = MaterialTheme.colorScheme.error,
              )
            }
            if (WhisperLib.ensureLoaded()) {
              Text(WhisperLib.systemInfo(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              Text(
                "⚠ Whisper не запустился: ${WhisperLib.loadError}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
              )
            }
          }
          else -> {
            if (state is WhisperDownloadState.Failed) {
              Text("⚠ ${state.message}", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
              Spacer(Modifier.height(6.dp))
            }
            if (partialBytes > 0) {
              Text("Уже скачано ${partialBytes shr 20} из ~${WhisperModel.APPROX_MB} МБ.")
            } else {
              Text("Модель: ${WhisperModel.LABEL}, ~${WhisperModel.APPROX_MB} МБ. Лучше качать по Wi-Fi.")
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onDownload) { Text(if (partialBytes > 0) "Продолжить" else "Скачать") }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
  )
}
