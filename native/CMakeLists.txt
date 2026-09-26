cmake_minimum_required(VERSION 3.13.2)

project(retroengine C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall")

set(RE_DIR ${CMAKE_CURRENT_LIST_DIR})
set(LIBRETRO_COMMON_DIR ${RE_DIR}/third_party/libretro/libretro-common)

set(RE_COMMON_SOURCES
        src/libretrodroid.cpp
        src/core.cpp
        src/audio.cpp
        src/video.cpp
        src/immersivemode.cpp
        src/videolayout.cpp
        src/renderers/renderer.cpp
        src/renderers/es3/es3utils.cpp
        src/renderers/es3/framebufferrenderer.cpp
        src/renderers/es2/imagerendereres2.cpp
        src/renderers/es3/imagerendereres3.cpp
        src/resamplers/linearresampler.cpp
        src/resamplers/sincresampler.cpp
        src/fpssync.cpp
        src/environment.cpp
        src/input.cpp
        src/netplay.cpp
        src/shadermanager.cpp
        src/rumble.cpp
        src/rumblestate.cpp
        src/utils/utils.cpp
        src/utils/libretrodroidexception.cpp
        src/utils/rect.cpp
        src/errorcodes.cpp
        src/vfs/vfs.cpp
        src/vfs/vfsfile.cpp
        src/vfs/fdwrapper.cpp
        ${LIBRETRO_COMMON_DIR}/vfs/vfs_implementation.c
        ${LIBRETRO_COMMON_DIR}/string/stdstring.c
        ${LIBRETRO_COMMON_DIR}/encodings/encoding_utf.c
        ${LIBRETRO_COMMON_DIR}/file/file_path.c
        ${LIBRETRO_COMMON_DIR}/time/rtime.c
)

if(ANDROID)
    add_subdirectory(third_party/oboe oboe)

    add_library(libretrodroid SHARED
            ${RE_COMMON_SOURCES}
            src/android/audio_oboe.cpp
            src/android/input_android.cpp
            src/android/microphone/microphone.cpp
            src/android/microphone/microphoneinterface.cpp
            bindings/jni/libretrodroidjni.cpp
            bindings/jni/utils/javautils.cpp
            bindings/jni/utils/jnistring.cpp
    )
    set(RE_TARGET libretrodroid)
    target_include_directories(libretrodroid PRIVATE
            third_party/oboe/include
            third_party/oboe/src
            bindings/jni
    )
    target_link_options(libretrodroid PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_libraries(libretrodroid android log EGL oboe GLESv3)

elseif(APPLE)
    set(ANGLE_INCLUDE_DIR "${RE_DIR}/../../.cache/angle/include" CACHE PATH "ANGLE headers (EGL/, GLES2/, GLES3/)")

    add_library(retroengine STATIC
            ${RE_COMMON_SOURCES}
            src/apple/audio_coreaudio.cpp
            src/apple/log_apple.cpp
            src/apple/microphone_unavailable.cpp
            bindings/c/retroengine.cpp
    )
    set(RE_TARGET retroengine)
    target_include_directories(retroengine PRIVATE ${ANGLE_INCLUDE_DIR})
    target_include_directories(retroengine PUBLIC bindings/c/include)
    target_compile_definitions(retroengine PRIVATE GL_GLEXT_PROTOTYPES EGL_EGLEXT_PROTOTYPES)
endif()

target_compile_definitions(${RE_TARGET} PRIVATE VFS_FRONTEND HAVE_STRL)
target_include_directories(${RE_TARGET} PRIVATE ${LIBRETRO_COMMON_DIR}/include src)
