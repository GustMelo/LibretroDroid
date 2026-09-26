/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef LIBRETRODROID_LOG_H
#define LIBRETRODROID_LOG_H

#define MODULE_NAME "libretrodroid"

#define VERBOSE_LOGGING false
#define GLES_LOGGING false

#if GLES_LOGGING
#include <EGL/egl.h>
#include <GLES3/gl32.h>
#endif

#include <cstdarg>

#if defined(__ANDROID__)
#include <android/log.h>
#define RE_LOG_PRINT(level, ...) __android_log_print(level, MODULE_NAME, __VA_ARGS__)
#define RE_LOG_VPRINT(level, tag, fmt, args) __android_log_vprint(level, tag, fmt, args)
#else
namespace libretrodroid {
void logPrint(int level, const char* tag, const char* fmt, ...) __attribute__((format(printf, 3, 4)));
void logVPrint(int level, const char* tag, const char* fmt, va_list args);
}
#define RE_LOG_PRINT(level, ...) ::libretrodroid::logPrint(level, MODULE_NAME, __VA_ARGS__)
#define RE_LOG_VPRINT(level, tag, fmt, args) ::libretrodroid::logVPrint(level, tag, fmt, args)
#endif

#define RE_LOG_VERBOSE 2
#define RE_LOG_DEBUG 3
#define RE_LOG_INFO 4
#define RE_LOG_WARN 5
#define RE_LOG_ERROR 6
#define RE_LOG_FATAL 7

#if VERBOSE_LOGGING
#define LOGV(...) RE_LOG_PRINT(RE_LOG_VERBOSE, __VA_ARGS__)
#define LOGD(...) RE_LOG_PRINT(RE_LOG_DEBUG, __VA_ARGS__)
#else
#define LOGV(...)
#define LOGD(...)
#endif
#define LOGI(...) RE_LOG_PRINT(RE_LOG_INFO, __VA_ARGS__)
#define LOGW(...) RE_LOG_PRINT(RE_LOG_WARN, __VA_ARGS__)
#define LOGE(...) RE_LOG_PRINT(RE_LOG_ERROR, __VA_ARGS__)
#define LOGF(...) RE_LOG_PRINT(RE_LOG_FATAL, __VA_ARGS__)

#if GLES_LOGGING

static void MessageCallback(
    GLenum source,
    GLenum type,
    GLuint id,
    GLenum severity,
    GLsizei length,
    const GLchar* message,
    const void* userParam
) {
    if (type == GL_DEBUG_TYPE_ERROR) {
        LOGE("GL CALLBACK: \"** GL ERROR **\" type = 0x%x, severity = 0x%x, message = %s\n",
             type,
             severity,
             message);
    }
}

static bool initializeGLESLogCallbackIfNeeded() {
    auto debugCallback = (void (*)(void *, void *)) eglGetProcAddress("glDebugMessageCallback");
    if (debugCallback) {
        glEnable(GL_DEBUG_OUTPUT);
        debugCallback((void*) MessageCallback, nullptr);
    }
    return true;
}

#else

static bool initializeGLESLogCallbackIfNeeded() {
    return false;
}

#endif

#endif
