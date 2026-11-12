#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#define LOG_TAG "CompositorJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT void JNICALL
Java_com_droiddesk_droiddesk_compositor_CompositorService_nativeStartCompositor(
        JNIEnv* env,
        jobject /* this */,
        jobject surface) {
    
    if (surface == nullptr) {
        LOGE("Surface is null!");
        return;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        LOGE("Failed to get ANativeWindow from surface!");
        return;
    }

    LOGI("Successfully acquired ANativeWindow from Surface!");

    // TODO: Initialize Wayland/wlroots backend with this window

    ANativeWindow_release(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_droiddesk_droiddesk_compositor_CompositorService_nativeStopCompositor(
        JNIEnv* env,
        jobject /* this */) {
    LOGI("Stopping Wayland Compositor...");
    // TODO: Gracefully shut down wlroots
}

extern "C" JNIEXPORT void JNICALL
Java_com_droiddesk_droiddesk_compositor_CompositorService_nativeSendTouchEvent(
        JNIEnv* env,
        jobject /* this */,
        jint action,
        jfloat x,
        jfloat y) {
    // TODO: Inject input into wlroots
}
