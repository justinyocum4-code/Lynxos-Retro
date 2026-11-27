# Lynxos Retro — native Nintendo 64 bridge (milestone M1).
# One C file, plain C11, no C++. Warnings are not errors on the first
# build: the NDK is the only compiler for this file, so a warning must
# never sink a build the way an error would.
cmake_minimum_required(VERSION 3.22.1)
project("lynxos_n64_jni")

set(CMAKE_C_STANDARD 11)

add_library(lynxos_n64_jni SHARED lynxos_n64_jni.c)

# v1.4.9: keep debug info in the bridge .so so the crash recorder's
# backtrace offsets resolve with addr2line. AGP would otherwise strip
# it in the release build (see doNotStrip in the workflow's
# build.gradle). The prebuilt cores ship stripped and stay that way.
target_compile_options(lynxos_n64_jni PRIVATE -g)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)

target_link_libraries(lynxos_n64_jni
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${gles-lib}
    dl)
