/*
 * Offline Translator task, built on top of Google AI Edge Gallery (Apache 2.0).
 */

package com.google.ai.edge.gallery.customtasks.translator

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val TRANSLATOR_TASK_ID = "offline_translator"

class TranslatorTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = TRANSLATOR_TASK_ID,
      label = "Переводчик",
      category = Category.LLM,
      icon = Icons.Outlined.Translate,
      models = mutableListOf(),
      description =
        "Офлайн голосовой переводчик: русский ⇄ английский, китайский, немецкий. " +
          "Выбери модель с поддержкой аудио (Gemma 4 E4B или E2B).",
      shortDescription = "Голосовой перевод без интернета",
      supportAudio = true,
    )

  override val keepModelAlive: Boolean
    get() = true

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      model.runtimeHelper.initialize(
        context = context,
        model = model,
        taskId = task.id,
        supportImage = false,
        supportAudio = model.supportAudio,
        onDone = onDone,
        coroutineScope = coroutineScope,
      )
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    TranslatorScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      bottomPadding = myData.bottomPadding,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object TranslatorTaskModule {
  @Provides
  @IntoSet
  fun provideTranslatorTask(): CustomTask {
    return TranslatorTask()
  }
}
