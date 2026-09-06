// whisper.cpp 的 JNI 桥：init → transcribe → 释放 一次完成，返回 JSON 分段。
// 进度通过全局原子变量暴露，Kotlin 侧用 getProgress() 轮询（避免 attach 线程回调）。
#include <jni.h>
#include <string>
#include <vector>
#include <cstdio>
#include <cinttypes>
#include <atomic>
#include <android/log.h>

#include "whisper.h"

static std::atomic<float> g_progress{0.f};

static void progress_cb(struct whisper_context *, struct whisper_state *, int progress, void *) {
    g_progress.store(progress / 100.0f);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_galigalimusic_whisper_WhisperNative_initContext(
        JNIEnv *env, jobject, jstring jModelPath) {
    const char *modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(modelPath, cparams);
    env->ReleaseStringUTFChars(jModelPath, modelPath);
    return (jlong) (intptr_t) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_galigalimusic_whisper_WhisperNative_freeContext(
        JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free((whisper_context *) (intptr_t) ptr);
}

// 对常驻上下文转写一段 float PCM 样本，返回该段的分段 JSON
extern "C" JNIEXPORT jstring JNICALL
Java_com_galigalimusic_whisper_WhisperNative_transcribeSamples(
        JNIEnv *env, jobject, jlong ptr, jfloatArray jSamples,
        jstring jLanguage, jint threads) {
    auto *ctx = (whisper_context *) (intptr_t) ptr;
    if (ctx == nullptr) return env->NewStringUTF("{\"error\":\"no context\"}");
    jsize n = env->GetArrayLength(jSamples);
    jfloat *data = env->GetFloatArrayElements(jSamples, nullptr);

    g_progress.store(0.f);
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime = false;
    p.print_progress = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.language = env->GetStringUTFChars(jLanguage, nullptr);
    p.n_threads = threads > 0 ? threads : 4;
    p.translate = false;
    p.progress_callback = progress_cb;

    int rc = whisper_full(ctx, p, data, n);
    env->ReleaseStringUTFChars(jLanguage, const_cast<char *>(p.language));
    env->ReleaseFloatArrayElements(jSamples, data, JNI_ABORT);
    if (rc != 0) return env->NewStringUTF("{\"error\":\"transcribe failed\"}");

    std::string out = "{\"segments\":[";
    const int count = whisper_full_n_segments(ctx);
    for (int i = 0; i < count; ++i) {
        // whisper.cpp 的 t0/t1 单位是厘秒（×10 = ms）
        int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const char *text = whisper_full_get_segment_text(ctx, i);
        std::string esc;
        for (const char *c = text; *c; ++c) {
            if (*c == '"') esc += "\\\"";
            else if (*c == '\\') esc += "\\\\";
            else if ((unsigned char) *c < 0x20) esc += ' ';
            else esc += *c;
        }
        char buf[64];
        snprintf(buf, sizeof(buf), "%s{\"t0\":%" PRId64 ",\"t1\":%" PRId64 ",\"text\":\"",
                 i ? "," : "", t0, t1);
        out += buf;
        out += esc;
        out += "\"}";
    }
    out += "]}";
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_galigalimusic_whisper_WhisperNative_nativeProgress(JNIEnv *, jobject) {
    return g_progress.load();
}
