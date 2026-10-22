// Offline Translator: thin JNI bridge to whisper.cpp.
// Kotlin side: com.google.ai.edge.gallery.customtasks.translator.WhisperLib

#include <jni.h>

#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define WLOG(...) __android_log_print(ANDROID_LOG_INFO, "WhisperJNI", __VA_ARGS__)
#else
#include <cstdio>
#define WLOG(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

#include "whisper.h"

namespace {

void log_callback(enum ggml_log_level level, const char *text, void * /*user_data*/) {
  if (level >= GGML_LOG_LEVEL_WARN && text != nullptr) {
    WLOG("%s", text);
  }
}

std::string to_std_string(JNIEnv *env, jstring s) {
  if (s == nullptr) return std::string();
  const char *chars = env->GetStringUTFChars(s, nullptr);
  if (chars == nullptr) return std::string();
  std::string out(chars);
  env->ReleaseStringUTFChars(s, chars);
  return out;
}

void throw_runtime(JNIEnv *env, const char *msg) {
  jclass cls = env->FindClass("java/lang/RuntimeException");
  if (cls != nullptr) env->ThrowNew(cls, msg);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_customtasks_translator_WhisperLib_initContext(
    JNIEnv *env, jobject /*thiz*/, jstring model_path) {
  whisper_log_set(log_callback, nullptr);
  const std::string path = to_std_string(env, model_path);
  whisper_context_params cparams = whisper_context_default_params();
  cparams.use_gpu = false;
  cparams.flash_attn = true;
  whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
  if (ctx == nullptr) {
    WLOG("failed to load model: %s", path.c_str());
  }
  return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_customtasks_translator_WhisperLib_freeContext(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong ctx_ptr) {
  auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
  if (ctx != nullptr) whisper_free(ctx);
}

// Returns UTF-8 bytes: recognized segments separated by '\n'.
// Bytes (not jstring) because Whisper can emit invalid UTF-8 at token borders,
// which would crash NewStringUTF; Kotlin decodes them leniently.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_google_ai_edge_gallery_customtasks_translator_WhisperLib_transcribe(
    JNIEnv *env, jobject /*thiz*/, jlong ctx_ptr, jfloatArray samples, jstring language,
    jstring initial_prompt, jint n_threads, jint audio_ctx, jint beam_size) {
  auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
  if (ctx == nullptr) {
    throw_runtime(env, "Whisper model is not loaded");
    return nullptr;
  }

  const jsize n = env->GetArrayLength(samples);
  std::vector<float> pcm(static_cast<size_t>(n));
  if (n > 0) env->GetFloatArrayRegion(samples, 0, n, pcm.data());

  const std::string lang = to_std_string(env, language);
  const std::string prompt = to_std_string(env, initial_prompt);

  whisper_full_params params = whisper_full_default_params(
      beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
  params.n_threads = n_threads > 0 ? n_threads : 4;
  params.language = lang.empty() ? "auto" : lang.c_str();
  params.detect_language = false;
  params.translate = false;
  params.no_context = true;
  params.no_timestamps = true;
  params.single_segment = false;
  params.print_special = false;
  params.print_progress = false;
  params.print_realtime = false;
  params.print_timestamps = false;
  params.suppress_blank = true;
  params.suppress_nst = true;
  params.audio_ctx = audio_ctx > 0 ? audio_ctx : 0;
  params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
  if (beam_size > 1) params.beam_search.beam_size = beam_size;

  const int rc = whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size()));
  if (rc != 0) {
    throw_runtime(env, "whisper_full failed");
    return nullptr;
  }

  std::string out;
  const int n_segments = whisper_full_n_segments(ctx);
  for (int i = 0; i < n_segments; ++i) {
    const char *text = whisper_full_get_segment_text(ctx, i);
    if (text == nullptr) continue;
    if (!out.empty()) out.push_back('\n');
    out.append(text);
  }

  jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
  if (result != nullptr && !out.empty()) {
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte *>(out.data()));
  }
  return result;
}

// ---------------------------------------------------------------------------------------------
// Language detection (for the "Авто" direction mode).
//
// A separate whisper_state is used only for detection. It is "warmed up" once with a tiny
// whisper_full() call that sets its encoder window to `audio_ctx` (e.g. 500 = 10 s). Detection
// calls (pcm_to_mel + lang_auto_detect) never reset that window, so every detection only encodes
// ~10 s instead of the full 30 s and stays cheap.
// ---------------------------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_customtasks_translator_WhisperLib_initDetectState(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong ctx_ptr, jint n_threads, jint audio_ctx) {
  auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
  if (ctx == nullptr) return 0;
  whisper_state *st = whisper_init_state(ctx);
  if (st == nullptr) {
    WLOG("failed to init detect state");
    return 0;
  }
  // Warm-up: 1 s of silence, forced language, 1 token max. Sets the state's encoder window.
  std::vector<float> silence(16000, 0.0f);
  whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  params.n_threads = n_threads > 0 ? n_threads : 4;
  params.language = "en";
  params.detect_language = false;
  params.no_context = true;
  params.no_timestamps = true;
  params.single_segment = true;
  params.max_tokens = 1;
  params.print_special = false;
  params.print_progress = false;
  params.print_realtime = false;
  params.print_timestamps = false;
  params.audio_ctx = audio_ctx > 0 ? audio_ctx : 0;
  if (whisper_full_with_state(ctx, st, params, silence.data(), static_cast<int>(silence.size())) != 0) {
    WLOG("detect state warm-up failed");
  }
  return reinterpret_cast<jlong>(st);
}

extern "C" JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_customtasks_translator_WhisperLib_freeState(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong state_ptr) {
  auto *st = reinterpret_cast<whisper_state *>(state_ptr);
  if (st != nullptr) whisper_free_state(st);
}

// Returns probabilities for every Whisper language (index = whisper language id), or null.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_google_ai_edge_gallery_customtasks_translator_WhisperLib_detectLanguage(
    JNIEnv *env, jobject /*thiz*/, jlong ctx_ptr, jlong state_ptr, jfloatArray samples, jint n_threads) {
  auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
  auto *st = reinterpret_cast<whisper_state *>(state_ptr);
  if (ctx == nullptr || st == nullptr) return nullptr;

  const jsize n = env->GetArrayLength(samples);
  std::vector<float> pcm(static_cast<size_t>(n));
  if (n > 0) env->GetFloatArrayRegion(samples, 0, n, pcm.data());
  const int threads = n_threads > 0 ? n_threads : 4;

  if (whisper_pcm_to_mel_with_state(ctx, st, pcm.data(), static_cast<int>(pcm.size()), threads) != 0) {
    WLOG("detect: mel failed");
    return nullptr;
  }
  const int n_lang = whisper_lang_max_id() + 1;
  std::vector<float> probs(static_cast<size_t>(n_lang), 0.0f);
  if (whisper_lang_auto_detect_with_state(ctx, st, 0, threads, probs.data()) < 0) {
    WLOG("detect: lang_auto_detect failed");
    return nullptr;
  }
  jfloatArray out = env->NewFloatArray(n_lang);
  if (out != nullptr) env->SetFloatArrayRegion(out, 0, n_lang, probs.data());
  return out;
}

// Whisper language id for a code like "ru", "zh"; -1 if unknown.
extern "C" JNIEXPORT jint JNICALL
Java_com_google_ai_edge_gallery_customtasks_translator_WhisperLib_langId(
    JNIEnv *env, jobject /*thiz*/, jstring code) {
  const std::string c = to_std_string(env, code);
  return whisper_lang_id(c.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_google_ai_edge_gallery_customtasks_translator_WhisperLib_systemInfo(
    JNIEnv *env, jobject /*thiz*/) {
  const char *info = whisper_print_system_info();
  return env->NewStringUTF(info != nullptr ? info : "");
}
