#include <jni.h>
#include "weights.h"
#include "core_version.h"
#include <stdexcept>

namespace {
std::string string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (!text) throw std::runtime_error("Cannot read native string");
    std::string result(text);
    env->ReleaseStringUTFChars(value, text);
    return result;
}
std::vector<std::string> strings(JNIEnv* env, jobjectArray array) {
    if (!array) throw std::invalid_argument("Missing key array");
    std::vector<std::string> result;
    for (jsize i = 0; i < env->GetArrayLength(array); ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        result.push_back(string(env, item));
        env->DeleteLocalRef(item);
    }
    return result;
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_dev_ignorantshuffle_companion_ShuffleNative_coreVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(ignorant::core_version);
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_dev_ignorantshuffle_companion_ShuffleNative_plan(JNIEnv* env, jobject,
    jobjectArray keys, jdoubleArray weights, jobjectArray recent, jobjectArray played,
    jint cooldown, jboolean finite, jstring avoid, jdouble unit) {
    try {
        if (!weights || cooldown < 0 || cooldown > 10000) throw std::invalid_argument("Invalid plan inputs");
        std::vector<double> numbers(env->GetArrayLength(weights));
        if (!numbers.empty()) env->GetDoubleArrayRegion(weights, 0, static_cast<jsize>(numbers.size()), numbers.data());
        if (env->ExceptionCheck()) return nullptr;
        const auto choice = ignorant::plan_next(strings(env, keys), numbers, strings(env, recent),
            strings(env, played), static_cast<std::size_t>(cooldown), finite, string(env, avoid), unit);
        if (env->ExceptionCheck()) return nullptr;
        const jdouble values[]{choice.index ? static_cast<double>(*choice.index) : -1.0,
            choice.selection.probability, static_cast<double>(choice.selection.eligible),
            static_cast<double>(choice.selection.excluded), static_cast<double>(choice.blocked),
            choice.cooldown_fallback ? 1.0 : 0.0, static_cast<double>(choice.remaining)};
        auto result = env->NewDoubleArray(7);
        if (result) env->SetDoubleArrayRegion(result, 0, 7, values);
        return result;
    } catch (const std::exception& e) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), e.what());
        return nullptr;
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Native shuffle error");
        return nullptr;
    }
}
