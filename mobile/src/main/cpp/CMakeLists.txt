cmake_minimum_required(VERSION 3.22)
project(wristnote C CXX)

set(CMAKE_CXX_STANDARD 17)

# whisper.cpp is cloned next to this file by the CI workflow.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Built for the S24 Ultra (Snapdragon 8 Gen 3): fp16, dotprod and i8mm matrix instructions.
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod+i8mm" CACHE STRING "" FORCE)

add_subdirectory(whisper.cpp)

add_library(wristnote SHARED jni.cpp)
target_compile_options(wristnote PRIVATE -O3)
target_link_libraries(wristnote whisper log)
