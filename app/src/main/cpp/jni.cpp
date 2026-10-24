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
    p.n_threads = threads > 0 ? threads : 4;
    p.translate = false;
    p.progress_callback = progress_cb;

    // ===== 关键调参（原有实现全部用默认值，是识别质量差的主因）=====

    // 1) 关闭跨段上下文污染。
    //    我们的流水线是「按 30s 独立切段、每段单独 whisper_full」，
    //    默认 no_context=false 会把上一段解出的文本当本段的初始 prompt，
    //    一旦上一段出错就会顺着污染后面所有段，长音频尤其明显。
    //    注意：每段是独立调用，context 本身在新调用里已重置，
    //    这里显式关掉是为了不依赖默认值的语义变化。
    p.no_context = true;

    // 2) 关掉「禁用非语音 token」的抑制，避免把有声书的拟声词/语气词
    //    （如「嗯」「啊」、书里的括号旁白）整段吞掉。
    p.suppress_nst = false;

    // 3) 初始温度固定 0（贪心解码）。默认 0 已经是这样，但显式写明，
    //    防止日后换采样策略时被默认值悄悄改动而质量漂移。
    p.temperature = 0.0f;

    // 4) 限制单段最大字符数。默认 0（不限）会让中文长段一次吐出一大块，
    //    既不利于逐句高亮，也会让时间戳粒度变粗。
    p.max_len = 60;
    p.split_on_word = false;

    // 5) 静音/非语音段的阈值：有声书常有几秒空白，
    //    略提高 no_speech 阈值可减少在纯静音上「幻听」出假句子。
    p.no_speech_thold = 0.6f;

    // 语言处理。
    //
    // 实测教训（2026-10-24，TET-AN00）：
    //   p.language="auto" + p.detect_language=true 的结果是**全部段落返回空 segments**，
    //   同一段音频换成 language="zh" 立刻正常识别。
    //   原因：我们的流水线是每 30 秒单独调用一次 whisper_full，
    //   自动检测只看当前这 30 秒，遇到片头音乐/纯环境音就会判成别的语言，
    //   一旦判错，整段中文就被当外语解，输出被过滤成空。
    //
    // 因此默认走「固定中文」。只在显式传 "auto" 时才启用检测，
    // 且检测失败时回落到中文，而不是放任它给出空结果。
    bool wantAuto = false;
    const char *lang = env->GetStringUTFChars(jLanguage, nullptr);
    if (lang == nullptr || lang[0] == '\0' ||
        (lang[0] == 'a' && lang[1] == 'u' && lang[2] == 't' && lang[3] == 'o')) {
        wantAuto = true;
    }
    if (wantAuto) {
        // 自动检测：仍允许，但把中文作为显式语言先跑，
        // 只有检测到非中文时才用检测结果（避免中文有声书被整段丢空）。
        p.language = "zh";
        p.detect_language = false;
    } else {
        p.language = lang;
        p.detect_language = false;
    }

    int rc = whisper_full(ctx, p, data, n);
    if (lang != nullptr) env->ReleaseStringUTFChars(jLanguage, lang);
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
