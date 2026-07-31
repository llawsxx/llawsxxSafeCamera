cmake_minimum_required(VERSION 3.22.1)

project(safecamera_mpegts C)

add_library(
    safecamera_mpegts
    SHARED
    mpegts_muxer.c
    mpegts_jni.c
)

target_compile_features(safecamera_mpegts PRIVATE c_std_11)
target_compile_options(safecamera_mpegts PRIVATE -Wall -Wextra -Werror)
