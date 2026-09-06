package com.galigalimusic.whisper

/** whisper.cpp JNI 绑定（libwhisperjni.so，CMake 从本地源码构建） */
object WhisperNative {
    init {
        System.loadLibrary("whisperjni")
    }

    /** 加载模型到内存，返回上下文指针（0 = 失败）。用完务必 [freeContext]。 */
    external fun initContext(modelPath: String): Long

    external fun freeContext(ptr: Long)

    /**
     * 对常驻上下文转写一段 float PCM（16kHz 单声道，-1~1），
     * 返回该段分段 JSON：`{"segments":[{"t0":ms,"t1":ms,"text":"..."},...]}`
     * （阻塞调用；进度用 [nativeProgress] 轮询）
     */
    external fun transcribeSamples(ptr: Long, samples: FloatArray, language: String, threads: Int): String

    /** 推理进度 0.0~1.0（当前段内） */
    external fun nativeProgress(): Float
}
