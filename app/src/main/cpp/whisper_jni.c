// JNI layer between app.olauncher.ui.recorder.WhisperNative and whisper.cpp.
#include <jni.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#include "whisper.h"

static atomic_bool g_abort;
static atomic_int g_progress;

static bool abort_callback(void *data) {
    (void) data;
    return atomic_load(&g_abort);
}

static void progress_callback(struct whisper_context *ctx, struct whisper_state *state, int progress, void *data) {
    (void) ctx;
    (void) state;
    (void) data;
    atomic_store(&g_progress, progress);
}

static struct whisper_context *context_of(jlong pointer) {
    return (struct whisper_context *) (intptr_t) pointer;
}

JNIEXPORT jlong JNICALL
Java_app_olauncher_ui_recorder_WhisperNative_init(JNIEnv *env, jobject thiz, jstring path) {
    (void) thiz;
    const char *model = (*env)->GetStringUTFChars(env, path, NULL);
    if (model == NULL) return 0;
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(model, params);
    (*env)->ReleaseStringUTFChars(env, path, model);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_app_olauncher_ui_recorder_WhisperNative_free(JNIEnv *env, jobject thiz, jlong pointer) {
    (void) env;
    (void) thiz;
    if (pointer != 0) whisper_free(context_of(pointer));
}

JNIEXPORT void JNICALL
Java_app_olauncher_ui_recorder_WhisperNative_setAbort(JNIEnv *env, jobject thiz, jboolean abort) {
    (void) env;
    (void) thiz;
    atomic_store(&g_abort, abort == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_app_olauncher_ui_recorder_WhisperNative_progress(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return atomic_load(&g_progress);
}

// Transcribes 16 kHz mono samples; 0 when done (the segments are then read one by one).
JNIEXPORT jint JNICALL
Java_app_olauncher_ui_recorder_WhisperNative_full(JNIEnv *env, jobject thiz, jlong pointer, jint threads,
                                                   jstring language, jstring prompt, jfloatArray samples) {
    (void) thiz;
    struct whisper_context *ctx = context_of(pointer);
    if (ctx == NULL) return -100;
    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);
    const char *hint = (*env)->GetStringUTFChars(env, prompt, NULL);
    jsize count = (*env)->GetArrayLength(env, samples);
    jfloat *data = (*env)->GetFloatArrayElements(env, samples, NULL);
    int result = -101;
    if (lang != NULL && hint != NULL && data != NULL) {
        struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = threads;
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.translate = false;
        params.language = lang;
        params.initial_prompt = strlen(hint) > 0 ? hint : NULL;
        params.suppress_nst = true;
        params.abort_callback = abort_callback;
        params.abort_callback_user_data = NULL;
        params.progress_callback = progress_callback;
        params.progress_callback_user_data = NULL;
        atomic_store(&g_progress, 0);
        result = whisper_full(ctx, params, data, count);
    }
    if (data != NULL) (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
    if (hint != NULL) (*env)->ReleaseStringUTFChars(env, prompt, hint);
    if (lang != NULL) (*env)->ReleaseStringUTFChars(env, language, lang);
    return result;
}

JNIEXPORT jint JNICALL
Java_app_olauncher_ui_recorder_WhisperNative_segmentCount(JNIEnv *env, jobject thiz, jlong pointer) {
    (void) env;
    (void) thiz;
    return whisper_full_n_segments(context_of(pointer));
}

// The text as UTF-8 bytes: a segment may end in the middle of a character, which a Java string refuses.
JNIEXPORT jbyteArray JNICALL
Java_app_olauncher_ui_recorder_WhisperNative_segmentText(JNIEnv *env, jobject thiz, jlong pointer, jint index) {
    (void) thiz;
    const char *text = whisper_full_get_segment_text(context_of(pointer), index);
    if (text == NULL) text = "";
    jsize length = (jsize) strlen(text);
    jbyteArray bytes = (*env)->NewByteArray(env, length);
    if (bytes != NULL) (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *) text);
    return bytes;
}

// Start and end of a segment, in hundredths of a second.
JNIEXPORT jlong JNICALL
Java_app_olauncher_ui_recorder_WhisperNative_segmentStart(JNIEnv *env, jobject thiz, jlong pointer, jint index) {
    (void) env;
    (void) thiz;
    return whisper_full_get_segment_t0(context_of(pointer), index);
}

JNIEXPORT jlong JNICALL
Java_app_olauncher_ui_recorder_WhisperNative_segmentEnd(JNIEnv *env, jobject thiz, jlong pointer, jint index) {
    (void) env;
    (void) thiz;
    return whisper_full_get_segment_t1(context_of(pointer), index);
}
