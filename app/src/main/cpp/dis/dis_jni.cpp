#include "dis.h"

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "DisVulkanJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static DisVulkanContext g_ctx;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_DIS_nativeInit(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return JNI_FALSE;
    }
    bool ok = disVulkanInit(&g_ctx, dpy);
    LOGI("DisVulkan native init %s", ok ? "OK" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_DIS_nativeCreateAhbTexture(
        JNIEnv* env, jclass cls, jint width, jint height, jint format) {
    (void)env; (void)cls;
    AhbTexture* tex = new AhbTexture();
    VkFormat vkFmt = format == 1 ? VK_FORMAT_R16G16B16A16_SFLOAT : VK_FORMAT_R8G8B8A8_UNORM;
    if (!disVulkanCreateAhbTexture(&g_ctx, tex, width, height, vkFmt)) {
        LOGE("disVulkanCreateAhbTexture failed");
        delete tex;
        return 0;
    }
    return (jlong)tex;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_DIS_nativeDestroyAhbTexture(JNIEnv* env, jclass cls, jlong ptr) {
    (void)env; (void)cls;
    AhbTexture* tex = (AhbTexture*)ptr;
    if (!tex) return;
    disVulkanDestroyAhbTexture(&g_ctx, tex);
    delete tex;
}

JNIEXPORT jint JNICALL
Java_com_winlator_renderer_DIS_nativeGetGlTexture(JNIEnv* env, jclass cls, jlong ptr) {
    (void)env; (void)cls;
    AhbTexture* tex = (AhbTexture*)ptr;
    return tex ? (jint)disVulkanGetGlTexture(tex) : 0;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_DIS_nativeSetMinSide(JNIEnv* env, jclass cls, jint minSide) {
    (void)env; (void)cls;
    disVulkanSetMinSide(&g_ctx, (uint32_t)minSide);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_DIS_nativePushFrame(
        JNIEnv* env, jclass cls, jlong framePtr, jint generations) {
    (void)env; (void)cls;
    return disVulkanPushFrame(&g_ctx, (AhbTexture*)framePtr, generations) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_DIS_nativeGenerate(
        JNIEnv* env, jclass cls, jlong outPtr, jfloat t) {
    (void)env; (void)cls;
    return disVulkanGenerate(&g_ctx, (AhbTexture*)outPtr, t) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_DIS_nativeCleanup(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    disVulkanCleanup(&g_ctx);
    LOGI("DisVulkan native cleanup done");
}

// Kept for the existing debug hook: any stage other than off shows the estimated flow.
JNIEXPORT void JNICALL
Java_com_winlator_renderer_DIS_nativeSetDebugStage(JNIEnv* env, jclass cls, jint stage) {
    (void)env; (void)cls;
    disVulkanSetDebugFlow(&g_ctx, stage >= 0);
}

} // extern "C"
