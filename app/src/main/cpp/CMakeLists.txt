# libcamera3.so — self-owned native render core for Camera2 Magic.
#
# The engine is deliberately free of third-party code and of the C++ runtime:
# it talks to EGL/GLES3 through gl_abi.h and to the platform through the NDK
# headers only, so the resulting library has no dependency besides the system
# libs. `-nostdlib++` keeps libc++/libc++abi out even though AGP passes
# -DANDROID_STL=c++_static.

cmake_minimum_required(VERSION 3.22.1)
project(camera3 CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(camera3 SHARED
    engine.cpp
    jpeg_encoder.cpp
    native_bridge.cpp
)

target_include_directories(camera3 PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(camera3 PRIVATE
    -Wall
    -Wextra
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
)

target_link_options(camera3 PRIVATE
    -nostdlib++
    -Wl,--no-undefined
)

target_link_libraries(camera3 PRIVATE
    android
    log
    EGL
    GLESv3
)

set_target_properties(camera3 PROPERTIES
    OUTPUT_NAME camera3
    PREFIX "lib"
)
