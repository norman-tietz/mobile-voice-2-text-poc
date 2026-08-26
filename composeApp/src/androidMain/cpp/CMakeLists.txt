cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
add_subdirectory(${WHISPER_CPP_DIR} whisper_build)

add_library(whisper_jni SHARED whisper_jni.cpp whisper_vad_jni.cpp)

find_library(log-lib log)

target_link_libraries(whisper_jni whisper ${log-lib} android)