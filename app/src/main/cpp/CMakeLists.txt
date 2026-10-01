cmake_minimum_required(VERSION 3.22.1)
project(nishu_llama)

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp llama_build)

add_library(nishu_llama SHARED llama_jni.cpp)
target_compile_definitions(nishu_llama PRIVATE NISHU_LLAMA_TAG="${NISHU_LLAMA_TAG}")
target_link_libraries(nishu_llama llama ggml android log)
