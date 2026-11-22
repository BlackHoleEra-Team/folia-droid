#include <jni.h>

#include <cstdlib>
#include <cstring>

#include "node.h"

// android/app/src/main/cpp/folia_node.cpp
// 把 Node 跑起来的最小 JNI：拼 argv，然后调 node::Start。
// 不碰进程的 stdout/stderr：WebView 和 Node 在同一个进程里，重定向会把 Chromium 的日志一起劫走。
// Node 自己的输出由 main.js 写到 filesDir 下的日志文件。

extern "C" JNIEXPORT jint JNICALL
Java_top_izuna_foliamajor_shell_NodeRuntime_startNode(
        JNIEnv* env, jclass, jobjectArray arguments, jstring modulesPath, jboolean /* redirectOutput */) {
    const char* modules = env->GetStringUTFChars(modulesPath, nullptr);
    setenv("NODE_PATH", modules, 1);
    env->ReleaseStringUTFChars(modulesPath, modules);

    const jsize count = env->GetArrayLength(arguments);

    size_t total = 0;
    for (jsize i = 0; i < count; ++i) {
        auto argument = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        const char* chars = env->GetStringUTFChars(argument, nullptr);
        total += strlen(chars) + 1;
        env->ReleaseStringUTFChars(argument, chars);
        env->DeleteLocalRef(argument);
    }

    auto* buffer = static_cast<char*>(calloc(total, 1));
    auto** argv = static_cast<char**>(calloc(static_cast<size_t>(count), sizeof(char*)));
    if (buffer == nullptr || argv == nullptr) {
        free(buffer);
        free(argv);
        return -1;
    }

    char* cursor = buffer;
    for (jsize i = 0; i < count; ++i) {
        auto argument = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        const char* chars = env->GetStringUTFChars(argument, nullptr);
        const size_t length = strlen(chars);
        memcpy(cursor, chars, length);
        argv[i] = cursor;
        cursor += length + 1;
        env->ReleaseStringUTFChars(argument, chars);
        env->DeleteLocalRef(argument);
    }

    const int exitCode = node::Start(static_cast<int>(count), argv);

    free(argv);
    free(buffer);
    return exitCode;
}
