cmake_minimum_required(VERSION 3.22.1)
project(whisperjni C CXX)

set(CMAKE_CXX_STANDARD 17)
include(FetchContent)

# whisper.cpp: offline multilingual speech recognition with automatic language detection.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# Same target as the official whisper.android example (phones from ~2018 onwards).
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16" CACHE STRING "" FORCE)

FetchContent_Declare(whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.8.7.tar.gz)
FetchContent_MakeAvailable(whisper)

add_library(whisperjni SHARED whisper_jni.cpp)
target_compile_options(whisperjni PRIVATE -O3)
target_link_libraries(whisperjni PRIVATE whisper log)
