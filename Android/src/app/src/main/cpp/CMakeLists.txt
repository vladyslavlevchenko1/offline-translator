# Offline Translator: whisper.cpp speech recognition (JNI).
# whisper.cpp is fetched at build time (pinned tag), built as static libs and linked
# into one shared library: libwhisper_jni.so (arm64-v8a only).

cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Static whisper/ggml libs are linked into a shared .so, so they must be PIC.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

set(WHISPER_CPP_TAG "v1.9.5")

FetchContent_Declare(
  whisper
  GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
  GIT_TAG        ${WHISPER_CPP_TAG}
  GIT_SHALLOW    TRUE
)

# Build whisper + ggml as static libraries, CPU only, no extras.
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS      OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE            OFF CACHE BOOL "" FORCE)
# Every modern phone CPU (2018+) has dot-product + fp16 instructions.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

FetchContent_MakeAvailable(whisper)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_compile_options(whisper_jni PRIVATE -O3)
target_link_libraries(whisper_jni PRIVATE whisper log android)
target_link_options(whisper_jni PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
