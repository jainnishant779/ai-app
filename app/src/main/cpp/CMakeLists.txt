cmake_minimum_required(VERSION 3.22.1)
project(nishu_llama)

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# One APK, every phone: build the CPU backend several times (armv8.0, +dotprod, +fp16, +i8mm, SVE2, SME) as
# separate libraries and let ggml pick the fastest one the device actually supports when the app starts.
# A plain armv8.0 build has no dot-product instructions, which quantized (Q4_K_M) matmul depends on.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp llama_build)

add_library(nishu_llama SHARED llama_jni.cpp nishu_session.cpp)
target_compile_definitions(nishu_llama PRIVATE NISHU_LLAMA_TAG="${NISHU_LLAMA_TAG}")
target_link_libraries(nishu_llama llama ggml android log)
