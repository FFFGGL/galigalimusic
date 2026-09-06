cmake_minimum_required(VERSION 3.22)
project(whisperjni)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/whisper)
set(CMAKE_ANDROID_STL_TYPE c++_shared)

include_directories(
    ${WHISPER_DIR}
    ${WHISPER_DIR}/ggml-cpu
)

# 与 whisper.rn 验证过的文件清单一致（去掉 RN/JSI/parakeet 专有部分）
set(
    SOURCE_FILES
    ${WHISPER_DIR}/ggml.c
    ${WHISPER_DIR}/ggml.cpp
    ${WHISPER_DIR}/ggml-alloc.c
    ${WHISPER_DIR}/ggml-backend.cpp
    ${WHISPER_DIR}/ggml-backend-meta.cpp
    ${WHISPER_DIR}/ggml-backend-reg.cpp
    ${WHISPER_DIR}/ggml-backend-dl.cpp
    ${WHISPER_DIR}/ggml-cpu/amx/amx.cpp
    ${WHISPER_DIR}/ggml-cpu/amx/mmq.cpp
    ${WHISPER_DIR}/ggml-cpu/ggml-cpu.c
    ${WHISPER_DIR}/ggml-cpu/ggml-cpu.cpp
    ${WHISPER_DIR}/ggml-cpu/quants.c
    ${WHISPER_DIR}/ggml-cpu/traits.cpp
    ${WHISPER_DIR}/ggml-cpu/repack.cpp
    ${WHISPER_DIR}/ggml-cpu/unary-ops.cpp
    ${WHISPER_DIR}/ggml-cpu/binary-ops.cpp
    ${WHISPER_DIR}/ggml-cpu/vec.cpp
    ${WHISPER_DIR}/ggml-cpu/ops.cpp
    ${WHISPER_DIR}/ggml-opt.cpp
    ${WHISPER_DIR}/ggml-threading.cpp
    ${WHISPER_DIR}/ggml-quants.c
    ${WHISPER_DIR}/gguf.cpp
    ${WHISPER_DIR}/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.cpp
)

find_library(LOG_LIB log)

add_library(whisperjni SHARED ${SOURCE_FILES})

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    # 现代 arm64 手机：fp16 加速 + arm 架构专用的 quants/repack
    target_compile_options(whisperjni PRIVATE -march=armv8.2-a+fp16)
    target_sources(whisperjni PRIVATE
        ${WHISPER_DIR}/ggml-cpu/arch/arm/quants.c
        ${WHISPER_DIR}/ggml-cpu/arch/arm/repack.cpp
    )
else ()
    # 其它架构退回通用实现
    target_compile_options(whisperjni PRIVATE -DWSP_GGML_CPU_GENERIC)
endif ()

# ggml 在 Android 上需要 Linux CPU affinity API
target_compile_definitions(whisperjni PRIVATE _GNU_SOURCE)
target_compile_options(whisperjni PRIVATE
    -DWSP_GGML_USE_CPU -DWSP_GGML_USE_CPU_REPACK -pthread
    -O3 -DNDEBUG
    -fvisibility=hidden -fvisibility-inlines-hidden
    -ffunction-sections -fdata-sections
)
target_link_options(whisperjni PRIVATE -Wl,--gc-sections)
target_link_libraries(whisperjni ${LOG_LIB} android)
