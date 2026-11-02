cmake_minimum_required(VERSION 3.22.1)
project(pawse_llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

# llama.cpp v0.8.0 (b11960), the first release that runs LFM3. The archive is
# addressed by commit, so the source cannot move under a build.
FetchContent_Declare(
    llama
    URL https://github.com/ggml-org/llama.cpp/archive/8642541b255bfb5dc39cd351a178be09013a55d6.tar.gz
)

# llama and ggml linked statically into libpawse_llama.so: no tools, no network
# code, no backends loaded at runtime.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# ponytail: one CPU target, ARMv8.2 with dot product and fp16, every phone since
# about 2018. Llama checks /proc/cpuinfo before loading this. Per-core variants
# need GGML_BACKEND_DL and extracted native libs; add them if older phones matter.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

FetchContent_MakeAvailable(llama)

add_library(pawse_llama SHARED pawse_llama.cpp)
target_link_libraries(pawse_llama PRIVATE llama android log)
# Android 15 devices may use 16 KB pages.
target_link_options(pawse_llama PRIVATE "-Wl,-z,max-page-size=16384")
