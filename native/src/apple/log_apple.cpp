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

#include <os/log.h>

#include <cstdio>

#include "log.h"

namespace libretrodroid {
void logVPrint(int level, const char *tag, const char *fmt, va_list args) {
    char message[1024];
    vsnprintf(message, sizeof(message), fmt, args);
    os_log_type_t type = level >= RE_LOG_ERROR ? OS_LOG_TYPE_ERROR
        : level == RE_LOG_WARN ? OS_LOG_TYPE_DEFAULT
        : level == RE_LOG_INFO ? OS_LOG_TYPE_INFO
        : OS_LOG_TYPE_DEBUG;
    os_log_with_type(OS_LOG_DEFAULT, type, "[%{public}s] %{public}s", tag, message);
}

void logPrint(int level, const char *tag, const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    logVPrint(level, tag, fmt, args);
    va_end(args);
}
}
