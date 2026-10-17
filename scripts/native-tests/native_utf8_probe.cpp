#include "jni_utf8.h"

extern "C" JNIEXPORT jbyteArray JNICALL Java_NativeUtf8Probe_bytes(
    JNIEnv * env, jclass, jstring value
) {
    std::string result;
    if (!agora::jni::read_java_string(env, value, result)) return nullptr;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(result.size()));
    if (bytes && !result.empty()) {
        env->SetByteArrayRegion(
            bytes, 0, static_cast<jsize>(result.size()),
            reinterpret_cast<const jbyte *>(result.data())
        );
    }
    return bytes;
}

extern "C" JNIEXPORT jboolean JNICALL Java_NativeUtf8Probe_pathAccepted(
    JNIEnv * env, jclass, jstring value
) {
    std::string result;
    return agora::jni::read_java_path(env, value, result) ? JNI_TRUE : JNI_FALSE;
}
