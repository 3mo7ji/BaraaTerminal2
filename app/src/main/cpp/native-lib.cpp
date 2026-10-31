#include <jni.h>
#include <string>
#include <vector>
#include <cstdlib>
#include <cstring>
#include "node.h"

extern "C" JNIEXPORT jint JNICALL
Java_com_baraa_terminal2_HostService_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments) {
    const jsize argc = env->GetArrayLength(arguments);
    std::vector<std::string> storage;
    std::vector<char *> argv;
    storage.reserve(static_cast<size_t>(argc));
    argv.reserve(static_cast<size_t>(argc));

    for (jsize index = 0; index < argc; ++index) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(arguments, index));
        if (item == nullptr) {
            storage.emplace_back("");
            continue;
        }
        const char *utf = env->GetStringUTFChars(item, nullptr);
        storage.emplace_back(utf ? utf : "");
        if (utf) env->ReleaseStringUTFChars(item, utf);
        env->DeleteLocalRef(item);
    }

    for (auto &argument : storage) argv.push_back(argument.data());
    return static_cast<jint>(node::Start(static_cast<int>(argc), argv.data()));
}
