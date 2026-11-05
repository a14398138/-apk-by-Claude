cmake_minimum_required(VERSION 3.22.1)
project(plamo_translate LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp のバージョン。ビルドが壊れたらここを動作確認済みのタグに固定する
set(LLAMA_TAG "master" CACHE STRING "llama.cpp git tag/branch")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ${LLAMA_TAG}
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)

add_library(translator SHARED translator.cpp)
target_link_libraries(translator llama android log)
target_link_options(translator PRIVATE -Wl,-z,max-page-size=16384)
