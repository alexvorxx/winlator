#include "dis.h"

#include <jni.h>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#define LOG_TAG "DisVulkanJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static DisVulkanContext g_ctx;

// Helper: get EGL display
static EGLDisplay getEglDisplay() {
    return eglGetDisplay(EGL_DEFAULT_DISPLAY);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_DIS_nativeInit(
        JNIEnv* env, jclass cls, jobject assetMgr) {

    EGLDisplay dpy = getEglDisplay();
    if (dpy == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return JNI_FALSE;
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, assetMgr);
    if (!mgr) {
        LOGE("AAssetManager_fromJava failed");
        return JNI_FALSE;
    }

    bool ok = disVulkanInit(&g_ctx, dpy, mgr);
    if (ok) {
        LOGI("DisVulkan native init OK");
    } else {
        LOGE("DisVulkan native init FAILED");
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_DIS_nativeCreateAhbTexture(
        JNIEnv* env, jclass cls, jint width, jint height, jint format) {

    AhbTexture* tex = new AhbTexture();
    VkFormat vkFmt;
    switch (format) {
        case 0:  vkFmt = VK_FORMAT_R8G8B8A8_UNORM;       break;
        case 1:  vkFmt = VK_FORMAT_R16G16B16A16_SFLOAT;  break;
        default: vkFmt = VK_FORMAT_R8G8B8A8_UNORM;       break;
    }

    if (!disVulkanCreateAhbTexture(&g_ctx, tex, width, height, vkFmt)) {
        LOGE("disVulkanCreateAhbTexture failed");
        delete tex;
        return 0;
    }
    return (jlong)tex;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_DIS_nativeDestroyAhbTexture(
        JNIEnv* env, jclass cls, jlong ptr) {
    AhbTexture* tex = (AhbTexture*)ptr;
    if (!tex) return;
    disVulkanDestroyAhbTexture(&g_ctx, tex);
    delete tex;
}

JNIEXPORT jint JNICALL
Java_com_winlator_renderer_DIS_nativeGetGlTexture(
        JNIEnv* env, jclass cls, jlong ptr) {
    AhbTexture* tex = (AhbTexture*)ptr;
    if (!tex) return 0;
    return (jint)disVulkanGetGlTexture(tex);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_DIS_nativeComputeFlow(
        JNIEnv* env, jclass cls,
        jlong prevPtr, jlong currPtr, jlong flowPtr,
        jint disWidth, jint disHeight,
        jboolean useVR) {

    AhbTexture* prev = (AhbTexture*)prevPtr;
    AhbTexture* curr = (AhbTexture*)currPtr;
    AhbTexture* flow = (AhbTexture*)flowPtr;
    if (!prev || !curr || !flow) {
        LOGE("nativeComputeFlow: null pointer");
        return JNI_FALSE;
    }
    return disVulkanComputeFlow(&g_ctx, prev, curr, flow,
                                disWidth, disHeight,
                                useVR == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_DIS_nativeCleanup(
        JNIEnv* env, jclass cls) {
    disVulkanCleanup(&g_ctx);
    LOGI("DisVulkan native cleanup done");
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_DIS_nativeSetDebugStage(
        JNIEnv* env, jclass cls, jint stage) {
    disVulkanSetDebugStage(&g_ctx, (int)stage);
}

} // extern "C"
