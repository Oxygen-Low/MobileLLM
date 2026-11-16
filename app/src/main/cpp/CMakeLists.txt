cmake_minimum_required(VERSION 3.22.1)

project(mobilellm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# llama.cpp build configuration
#
# These options MUST be set as cache variables before add_subdirectory() so
# that llama.cpp's own CMakeLists picks them up instead of the defaults.
# ---------------------------------------------------------------------------
set(BUILD_SHARED_LIBS          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP            OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI             OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL              OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL                 OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS         OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS_3RD_PARTY OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE                OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP                OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE             OFF CACHE BOOL "" FORCE)
set(GGML_BLAS                  OFF CACHE BOOL "" FORCE)
set(GGML_RPC                   OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL            OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS           OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES        OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS          OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS_3RD_PARTY OFF CACHE BOOL "" FORCE)
set(GGML_LTO                   OFF CACHE BOOL "" FORCE)

# Disable every accelerated backend: phones use the CPU backend.
set(GGML_CUDA                  OFF CACHE BOOL "" FORCE)
set(GGML_HIP                   OFF CACHE BOOL "" FORCE)
set(GGML_MUSA                  OFF CACHE BOOL "" FORCE)
set(GGML_SYCL                  OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN                OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL                OFF CACHE BOOL "" FORCE)
set(GGML_METAL                 OFF CACHE BOOL "" FORCE)
set(GGML_OPENVINO              OFF CACHE BOOL "" FORCE)
set(GGML_ZENDNN                OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON               OFF CACHE BOOL "" FORCE)
set(GGML_VIRTGPU               OFF CACHE BOOL "" FORCE)
set(GGML_WEBGPU                OFF CACHE BOOL "" FORCE)
set(GGML_ET                    OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE            OFF CACHE BOOL "" FORCE)

# SD checkpoints use tensor names longer than ggml's default 64 characters.
# This has to be set before llama.cpp brings ggml in, so both backends agree on
# the same tensor name layout.
add_compile_definitions(GGML_MAX_NAME=160)

add_subdirectory(llama.cpp)

# ---------------------------------------------------------------------------
# stable-diffusion.cpp build configuration
#
# The image engine samples the downloaded SD checkpoints with this library.
# It reuses the ggml that llama.cpp already builds: the upstream-compatible
# mode drops the patched INT8/FP8 operators, which the SD models here do not
# need, and avoids compiling a second ggml into the same shared library.
# ---------------------------------------------------------------------------
set(SD_BUILD_EXAMPLES         OFF CACHE BOOL "" FORCE)
set(SD_WEBP                   OFF CACHE BOOL "" FORCE)
set(SD_WEBM                   OFF CACHE BOOL "" FORCE)
set(SD_USE_UPSTREAM_GGML      ON  CACHE BOOL "" FORCE)
set(SD_GGML_SOURCE_DIR        "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml" CACHE PATH "" FORCE)

add_subdirectory(stable-diffusion.cpp)

# ---------------------------------------------------------------------------
# JNI bridge
# ---------------------------------------------------------------------------
add_library(mobilellm SHARED llama_jni.cpp image_jni.cpp video_jni.cpp)

target_link_libraries(mobilellm PRIVATE llama ggml stable-diffusion)

target_compile_options(mobilellm PRIVATE -O3 -ffunction-sections -fdata-sections)

# Keep the log tag reachable from native code.
target_link_libraries(mobilellm PRIVATE log)
