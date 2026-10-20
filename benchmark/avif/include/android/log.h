#pragma once
// Host benchmark only; production uses Android's logger.
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_INFO 4
inline int __android_log_print(int, const char*, const char*, ...) { return 0; }
