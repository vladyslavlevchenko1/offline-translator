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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EPHEMERAL_KEY = "aicore_ephemeral_inference"

data class HistoryItem(
  val id: Long,
  val direction: Direction,
  val target: TargetLang,
  val source: String,
  val translation: String,
  val seconds: Float,
  val formatOk: Boolean = true,
)

@Composable
fun TranslatorScreen(modelManagerViewModel: ModelManagerViewModel, bottomPadding: Dp) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val mmState by modelManagerViewModel.uiState.collectAsState()
  val model = mmState.selectedModel
  val initStatus by model.initStatusFlow.collectAsState()
  val ready = initStatus is Model.InitializationStatus.Initialized

  var direction by remember { mutableStateOf(Direction.ME_TO_THEM) }
  var target by remember { mutableStateOf(TargetLang.ZH) }
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

  fun runTranslation(
    prompt: String,
    audio: ByteArray?,
    dir: Direction = direction,
    tgt: TargetLang = target,
  ) {
    busy = true
    busyLabel = "Перевожу…"
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
              val r = parseReply(snapshot, dir, tgt)
              history.add(
                0,
                HistoryItem(
                  id = System.currentTimeMillis(),
                  direction = dir,
                  target = tgt,
                  source = r.source,
                  translation = r.translation,
                  seconds = (System.currentTimeMillis() - start) / 1000f,
                  formatOk = r.formatOk,
                ),
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
            Toast.makeText(context, "Ошибка: $msg", Toast.LENGTH_LONG).show()
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
          Toast.makeText(context, "Ошибка модели: ${e.message}", Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  fun runTranscription(prompt: String, audio: ByteArray) {
    busy = true
    busyLabel = "Распознаю…"
    streaming = ""
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
                typed = if (typed.isBlank()) text else typed.trimEnd() + " " + text
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
              Toast.makeText(context, "Ошибка: $msg", Toast.LENGTH_LONG).show()
            }
          },
          audioClips = listOf(audio),
          coroutineScope = scope,
          extraContext = mapOf(EPHEMERAL_KEY to "true"),
        )
      } catch (e: Exception) {
        scope.launch(Dispatchers.Main) {
          busy = false
          streaming = ""
          Toast.makeText(context, "Ошибка модели: ${e.message}", Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  fun startRecording() {
    recording = true
    elapsedMs = 0L
    val dirAtStart = direction
    val tgtAtStart = target
    val reviewAtStart = reviewMode
    val promptAtStart =
      if (reviewAtStart) buildTranscribePrompt(dirAtStart, tgtAtStart)
      else buildPrompt(dirAtStart, tgtAtStart, glossary = glossary)
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
      val wav = recorder.takeWav()
      if (error != null) {
        Toast.makeText(context, "Не удалось записать: $error", Toast.LENGTH_LONG).show()
      } else if (elapsedMs < 500) {
        Toast.makeText(context, "Слишком коротко", Toast.LENGTH_SHORT).show()
      } else {
        if (reviewAtStart) runTranscription(promptAtStart, wav)
        else runTranslation(promptAtStart, wav, dirAtStart, tgtAtStart)
      }
    }
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) startRecording()
      else Toast.makeText(context, "Нужен доступ к микрофону", Toast.LENGTH_LONG).show()
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

  if (showGlossary) {
    var draft by remember { mutableStateOf(glossary) }
    AlertDialog(
      onDismissRequest = { showGlossary = false },
      title = { Text("Мой словарь") },
      text = {
        Column {
          Text(
            "Одна строка — один термин: слово = что оно значит. Переводчик всегда будет так понимать эти слова.",
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

  Column(modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding).padding(horizontal = 16.dp)) {
    // Direction selector.
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
      SegmentedButton(
        selected = direction == Direction.ME_TO_THEM,
        onClick = { direction = Direction.ME_TO_THEM },
        enabled = !recording && !busy,
        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
      ) {
        Text("Я → ${target.label}")
      }
      SegmentedButton(
        selected = direction == Direction.THEM_TO_ME,
        onClick = { direction = Direction.THEM_TO_ME },
        enabled = !recording && !busy,
        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
      ) {
        Text("${target.label} → Я")
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
          onClick = { target = lang },
          enabled = !recording && !busy,
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
            if (ready) "Нажми на микрофон, скажи фразу и нажми ещё раз. До 30 секунд. Можно записать несколько кусков подряд — текст допишется."
            else "Сначала загрузи модель (вверху выбери Gemma 4 E4B или E2B).",
            modifier = Modifier.padding(vertical = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      items(history, key = { it.id }) { item -> HistoryCard(item, onSpeak = { speak(item.translation, item) }) }
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
        enabled = !recording && !busy,
      )
    }

    // Typed input.
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
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
          typed = ""
          runTranslation(buildPrompt(direction, target, typedText = text, glossary = glossary), null)
        },
      ) {
        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Перевести")
      }
    }

    // Mic button.
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
            else if (ready) "Говорить"
            else "Модель не загружена",
            fontSize = 13.sp,
          )
        }
      }
    }
  }
}

@Composable
private fun HistoryCard(item: HistoryItem, onSpeak: () -> Unit) {
  val context = LocalContext.current
  val srcLabel = if (item.direction == Direction.ME_TO_THEM) "RU" else item.target.code
  val dstLabel = if (item.direction == Direction.ME_TO_THEM) item.target.code else "RU"
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
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
          String.format(java.util.Locale.US, "%.1f с", item.seconds),
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
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
