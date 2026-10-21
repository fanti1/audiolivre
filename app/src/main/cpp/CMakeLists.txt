# Builds libaudiolivre_translate.so: slimt (the engine that runs Firefox's translation models) with its own
# SentencePiece and ruy, plus the JNI bridge in translator_jni.cpp. slimt's sources live in
# third_party/slimt (GPL-2.0-or-later; SentencePiece and ruy are Apache-2.0, cpuinfo is BSD-2-Clause).
cmake_minimum_required(VERSION 3.22.1)
project(audiolivre_translate CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(SLIMT_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/slimt)

# Optimized in debug builds too: unoptimized matrix code translates a book many times slower, and slimt's
# internal assertions (on in Debug) abort the whole app over conditions its release builds tolerate.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
add_compile_definitions(NDEBUG)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

# Only the libraries: no tests, command-line tool or Python/Java bindings of slimt's own.
set(WITH_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_PYTHON OFF CACHE BOOL "" FORCE)
set(BUILD_JNI OFF CACHE BOOL "" FORCE)
set(USE_BUILTIN_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(RUY_MINIMAL_BUILD ON CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_MOCK_TESTS OFF CACHE BOOL "" FORCE)
set(CPUINFO_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(USE_NEON ON CACHE BOOL "" FORCE)
endif()

# Keeps only what the bridge calls, and no debug info, in the shipped library.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -g0")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden -g0")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffp-contract=off -Wno-unused-command-line-argument -Wno-nan-infinity-disabled")

# Source paths end up in the library through __FILE__ (ruy's checks, for one); the published APK must not carry
# the folders of whoever built it.
get_filename_component(REPO_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../.. ABSOLUTE)
foreach(lang C CXX)
  set(CMAKE_${lang}_FLAGS "${CMAKE_${lang}_FLAGS} -ffile-prefix-map=${REPO_ROOT}/=")
  if(ANDROID_NDK)
    set(CMAKE_${lang}_FLAGS "${CMAKE_${lang}_FLAGS} -ffile-prefix-map=${ANDROID_NDK}/=ndk/")
  endif()
endforeach()

# slimt's own targets look for "slimt/*.hh" from the top of the build.
include_directories(${SLIMT_SOURCE_DIR} ${CMAKE_BINARY_DIR}/slimt)
add_subdirectory(${SLIMT_SOURCE_DIR} slimt EXCLUDE_FROM_ALL)

# The emulator runs x86_64; a modern baseline keeps slimt's vector code paths (the phones are ARM).
if(ANDROID_ABI STREQUAL "x86_64")
  target_compile_options(slimt-static PRIVATE -march=x86-64-v2)
endif()

add_library(audiolivre_translate SHARED translator_jni.cpp)
target_include_directories(audiolivre_translate PRIVATE ${SLIMT_SOURCE_DIR} ${CMAKE_BINARY_DIR}/slimt)
target_link_libraries(audiolivre_translate PRIVATE slimt-static log)
target_link_options(audiolivre_translate PRIVATE -Wl,--gc-sections -Wl,--strip-all)
