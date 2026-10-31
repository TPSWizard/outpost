cmake_minimum_required(VERSION 3.22)
project(outpost_llm C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp, pinned to the commit Outpost was built and tested against.
# Override with -DLLAMA_SRC=/path/to/llama.cpp to build from a local checkout.
set(LLAMA_COMMIT 0ec4027a2a0183de87c007069adddc06e665f7de)
if(NOT LLAMA_SRC)
    include(FetchContent)
    FetchContent_Declare(llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG ${LLAMA_COMMIT}
        GIT_SHALLOW FALSE)
    FetchContent_GetProperties(llama)
    if(NOT llama_POPULATED)
        FetchContent_Populate(llama)
    endif()
    set(LLAMA_SRC ${llama_SOURCE_DIR})
endif()

# Portable CPU build: one libggml-cpu-*.so per instruction-set level, picked at runtime,
# so the same APK runs fast on new phones and still works on older ones and emulators.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
if(ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
endif()
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(outpost_llm SHARED outpost_llm.cpp)
target_include_directories(outpost_llm PRIVATE
    ${LLAMA_SRC}/include ${LLAMA_SRC}/common ${LLAMA_SRC}/ggml/include)
if(ANDROID)
    target_link_libraries(outpost_llm llama llama-common android log)
else()
    # Host build for the JVM test harness (tools/llm-harness).
    find_package(JNI REQUIRED)
    target_include_directories(outpost_llm PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(outpost_llm llama llama-common)
endif()
