#include <jni.h>
#ifdef DIPLAY_EXTERNAL_APAC
#include "apac_channel_decoder.h"
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shilapi_xcertplay_media_SoftwareApacDecoder_nativeAvailable(JNIEnv*, jclass) {
#ifdef DIPLAY_EXTERNAL_APAC
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

#ifdef DIPLAY_EXTERNAL_APAC
extern "C" JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_SoftwareApacDecoder_nativeCreate(JNIEnv* env, jclass, jbyteArray cookie) {
    jsize size = env->GetArrayLength(cookie);
    if (size < 15 || size > 4096) return 0;
    std::vector<uint8_t> bytes(size);
    env->GetByteArrayRegion(cookie, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    auto decoder = std::unique_ptr<DiPlayApacChannelDecoder>(new DiPlayApacChannelDecoder());
    return decoder->configure(bytes.data(), bytes.size()) ? reinterpret_cast<jlong>(decoder.release()) : 0;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_media_SoftwareApacDecoder_nativeInfo(JNIEnv*, jclass, jlong handle, jint field) {
    auto decoder = reinterpret_cast<DiPlayApacChannelDecoder*>(handle);
    return field == 0 ? decoder->channels : jint(decoder->layout);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_media_SoftwareApacDecoder_nativeDecode(JNIEnv* env, jclass, jlong handle, jbyteArray packet) {
    jsize size = env->GetArrayLength(packet);
    if (size < 1 || size > 65536) return nullptr;
    std::vector<uint8_t> bytes(size), pcm;
    env->GetByteArrayRegion(packet, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    if (!reinterpret_cast<DiPlayApacChannelDecoder*>(handle)->decode(bytes.data(), bytes.size(), pcm)) return nullptr;
    auto result = env->NewByteArray(pcm.size());
    if (result) env->SetByteArrayRegion(result, 0, pcm.size(), reinterpret_cast<const jbyte*>(pcm.data()));
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_SoftwareApacDecoder_nativeReset(JNIEnv*, jclass, jlong handle) {
    reinterpret_cast<DiPlayApacChannelDecoder*>(handle)->reset();
}
extern "C" JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_SoftwareApacDecoder_nativeClose(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<DiPlayApacChannelDecoder*>(handle);
}
#endif
