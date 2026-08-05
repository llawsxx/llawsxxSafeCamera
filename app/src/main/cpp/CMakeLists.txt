cmake_minimum_required(VERSION 3.22.1)

project(safecamera_mpegts C)

add_library(
    safecamera_mpegts
    SHARED
    h26x_vui_rewriter.c
    h26x_vui_jni.c
    mpegts_muxer.c
    mpegts_jni.c
)

target_compile_features(safecamera_mpegts PRIVATE c_std_11)
target_compile_options(safecamera_mpegts PRIVATE -Wall -Wextra -Werror)
target_link_libraries(safecamera_mpegts m)
