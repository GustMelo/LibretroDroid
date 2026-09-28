#include <jni.h>
#include "netpacket.h"
using libretrodroid::Netpacket;

// All calls are confined to the GL/emulation thread, including stop.
static JavaVM* vm;
static jobject listener;
static jmethodID sendMethod, pollMethod;
static JNIEnv* currentEnv() {
    JNIEnv* env = nullptr;
    if (!vm || vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return nullptr;
    return env;
}
static void send(void*, int flags, const void* bytes, size_t size, uint16_t id, bool broadcast) {
    auto* env = currentEnv();
    if (!env || !listener || env->ExceptionCheck()) return;
    auto array = env->NewByteArray(static_cast<jsize>(size));
    if (!array) return;
    if (size) env->SetByteArrayRegion(array, 0, size, static_cast<const jbyte*>(bytes));
    if (!env->ExceptionCheck()) env->CallVoidMethod(listener, sendMethod, flags, array,
        static_cast<jint>(broadcast ? 65535 : id));
    env->DeleteLocalRef(array);
}
static void poll(void*) {
    auto* env = currentEnv();
    if (env && listener && !env->ExceptionCheck()) env->CallVoidMethod(listener, pollMethod);
}
extern "C" {
JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_LibretroDroid_stopNetpacket(JNIEnv* env, jclass) {
    Netpacket::getInstance().stop();
    if (listener) env->DeleteGlobalRef(listener);
    listener = nullptr;
}
JNIEXPORT jboolean JNICALL Java_com_swordfish_libretrodroid_LibretroDroid_startNetpacket(
    JNIEnv* env, jclass cls, jint id, jobject callbacks) {
    if (listener || !callbacks || id < 0 || id > 3) return false;
    env->GetJavaVM(&vm);
    auto type = env->GetObjectClass(callbacks);
    sendMethod = env->GetMethodID(type, "send", "(I[BI)V");
    pollMethod = env->GetMethodID(type, "pollReceive", "()V");
    env->DeleteLocalRef(type);
    if (env->ExceptionCheck() || !sendMethod || !pollMethod) return false;
    listener = env->NewGlobalRef(callbacks);
    if (!listener) return false;
    auto& core = Netpacket::getInstance();
    core.setTransport(nullptr, send);
    core.setPoll(poll);
    if (!core.start(id)) {
        Java_com_swordfish_libretrodroid_LibretroDroid_stopNetpacket(env, cls);
        return false;
    }
    return true;
}
JNIEXPORT jboolean JNICALL Java_com_swordfish_libretrodroid_LibretroDroid_connectNetpacket(
    JNIEnv*, jclass, jint id) {
    return id >= 0 && id <= 3 && Netpacket::getInstance().connected(id);
}
JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_LibretroDroid_pollNetpacket(JNIEnv*, jclass) {
    Netpacket::getInstance().poll();
}
JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_LibretroDroid_receiveNetpacket(
    JNIEnv* env, jclass, jbyteArray data, jint size, jint id) {
    if (!data || size <= 0 || size > 65536 || id < 0 || id > 3 ||
        size > env->GetArrayLength(data)) return;
    auto* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return;
    Netpacket::getInstance().receive(bytes, size, id);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}
}
