# Whisper (whisper.cpp) for the recorder's transcripts made on the phone, with a small JNI layer.
cmake_minimum_required(VERSION 3.22.1)
project(minima_whisper C CXX)

# Always optimised: an unoptimised build transcribes tens of times slower
foreach (config DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_C_FLAGS_${config} "-O3 -DNDEBUG")
    set(CMAKE_CXX_FLAGS_${config} "-O3 -DNDEBUG")
endforeach ()

# Static libraries that end up in a shared one
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Half floats and dot products (phones since 2018): what makes it fast enough; checked before use
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif ()

include(FetchContent)
# whisper.cpp v1.9.5
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp
    GIT_TAG d1be6fde11ac6e0407606b4e42fe72d34add8037
)
FetchContent_MakeAvailable(whisper)

add_library(minima_whisper SHARED whisper_jni.c)
target_link_libraries(minima_whisper PRIVATE whisper)
if (ANDROID)
    target_link_libraries(minima_whisper PRIVATE log)
    target_link_options(minima_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif ()
