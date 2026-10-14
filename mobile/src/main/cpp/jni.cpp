#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"

#define TAG "WristNoteWhisper"

extern "C" JNIEXPORT jlong JNICALL
Java_com_ahmad_wristnote_WhisperEngine_nativeInit(JNIEnv *env, jobject, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) __android_log_print(ANDROID_LOG_ERROR, TAG, "failed to load model");
    return reinterpret_cast<jlong>(ctx);
}

// Returns UTF-8 bytes; decoded on the Kotlin side so partial/odd byte
// sequences can never crash NewStringUTF.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ahmad_wristnote_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject, jlong handle,
                                                        jfloatArray jsamples, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.language = "ar";
    p.translate = false;
    p.n_threads = threads;
    p.no_timestamps = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;

    std::string out;
    if (whisper_full(ctx, p, samples, n) == 0) {
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; ++i) {
            out += whisper_full_get_segment_text(ctx, i);
        }
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed");
    }
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte *>(out.data()));
    return result;
}
