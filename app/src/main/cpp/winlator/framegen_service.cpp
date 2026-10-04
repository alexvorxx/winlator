#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <unistd.h>

#include "dis.h"
#include "qcom.h"

#define LOG_TAG "FrameGenSvcNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    struct WorkerState {
        EGLDisplay display = EGL_NO_DISPLAY;
        EGLContext context = EGL_NO_CONTEXT;
        EGLSurface surface = EGL_NO_SURFACE;

        AHardwareBuffer* prevAhb = nullptr;
        AHardwareBuffer* currAhb = nullptr;
        AHardwareBuffer* flowAhb = nullptr;

        DisVulkanContext disCtx = {};
        AhbTexture disPrev = {}, disCurr = {}, disFlow = {};

        int width = 0, height = 0, flowWidth = 0, flowHeight = 0;
        bool initialized = false;
        bool disReady = false;

        int apiMode = 0;               // 0=DIS, 1=QCOM
        QcomWorkerCtx qcom = {};
    };
    WorkerState g;

    bool createEglContext() {
        g.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (g.display == EGL_NO_DISPLAY) { LOGE("eglGetDisplay"); return false; }
        EGLint maj, min;
        if (!eglInitialize(g.display, &maj, &min)) { LOGE("eglInitialize"); return false; }
        LOGI("EGL %d.%d", maj, min);

        const EGLint cfgA[] = {
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                EGL_NONE };
        EGLConfig cfg; EGLint n = 0;
        if (!eglChooseConfig(g.display, cfgA, &cfg, 1, &n) || n < 1) {
            LOGE("eglChooseConfig"); return false;
        }
        eglBindAPI(EGL_OPENGL_ES_API);
        const EGLint ctxA[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
        g.context = eglCreateContext(g.display, cfg, EGL_NO_CONTEXT, ctxA);
        if (g.context == EGL_NO_CONTEXT) { LOGE("eglCreateContext"); return false; }
        const EGLint pbA[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        g.surface = eglCreatePbufferSurface(g.display, cfg, pbA);
        if (g.surface == EGL_NO_SURFACE) { LOGE("eglCreatePbufferSurface"); return false; }
        if (!eglMakeCurrent(g.display, g.surface, g.surface, g.context)) {
            LOGE("eglMakeCurrent"); return false;
        }
        LOGI("EGL ready, vendor=%s", glGetString(GL_VENDOR));
        return true;
    }

    void destroyEglContext() {
        if (g.display == EGL_NO_DISPLAY) return;
        eglMakeCurrent(g.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g.surface != EGL_NO_SURFACE) eglDestroySurface(g.display, g.surface);
        if (g.context != EGL_NO_CONTEXT) eglDestroyContext(g.display, g.context);
        eglTerminate(g.display);
        g.surface = EGL_NO_SURFACE; g.context = EGL_NO_CONTEXT; g.display = EGL_NO_DISPLAY;
    }

    void releaseAhbs() {
        if (g.prevAhb) { AHardwareBuffer_release(g.prevAhb); g.prevAhb = nullptr; }
        if (g.currAhb) { AHardwareBuffer_release(g.currAhb); g.currAhb = nullptr; }
        if (g.flowAhb) { AHardwareBuffer_release(g.flowAhb); g.flowAhb = nullptr; }
    }
} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_FrameGenService_nativeServiceInit(
        JNIEnv* env, jclass, jobject jPrev, jobject jCurr, jobject jFlow,
        jint width, jint height, jint flowWidth, jint flowHeight, jint apiMode) {

    LOGI("nativeServiceInit: %dx%d flow %dx%d api=%d",
         width, height, flowWidth, flowHeight, apiMode);

    AHardwareBuffer* prev = AHardwareBuffer_fromHardwareBuffer(env, jPrev);
    AHardwareBuffer* curr = AHardwareBuffer_fromHardwareBuffer(env, jCurr);
    AHardwareBuffer* flow = AHardwareBuffer_fromHardwareBuffer(env, jFlow);
    if (!prev || !curr || !flow) { LOGE("AHB null"); return JNI_FALSE; }

    if (g.initialized && g.apiMode == apiMode &&
        g.prevAhb == prev && g.currAhb == curr && g.flowAhb == flow) {
        LOGI("nativeServiceInit: same AHB, no-op");
        return JNI_TRUE;
    }

    if (g.initialized) {
        if (g.apiMode == 0 && g.disReady) {
            disVulkanDestroyAhbTexture(&g.disCtx, &g.disPrev);
            disVulkanDestroyAhbTexture(&g.disCtx, &g.disCurr);
            disVulkanDestroyAhbTexture(&g.disCtx, &g.disFlow);
            g.disReady = false;
        } else if (g.apiMode == 1) {
            qcomWorkerDestroy(&g.qcom);
        }
        releaseAhbs();
        g.initialized = false;
    }

    AHardwareBuffer_acquire(prev);
    AHardwareBuffer_acquire(curr);
    AHardwareBuffer_acquire(flow);
    g.prevAhb = prev; g.currAhb = curr; g.flowAhb = flow;
    g.width = width; g.height = height;
    g.flowWidth = flowWidth; g.flowHeight = flowHeight;
    g.apiMode = apiMode;

    if (g.display == EGL_NO_DISPLAY) {
        if (!createEglContext()) { releaseAhbs(); return JNI_FALSE; }
    } else {
        eglMakeCurrent(g.display, g.surface, g.surface, g.context);
    }

    if (apiMode == 0) {
        // DIS/Vulkan
        if (!g.disCtx.initialized) {
            if (!disVulkanInit(&g.disCtx, g.display)) {
                LOGE("disVulkanInit failed");
                releaseAhbs();
                return JNI_FALSE;
            }
        }
        if (!disVulkanImportAhbTexture(&g.disCtx, &g.disPrev, prev,
                                       width, height, VK_FORMAT_R8G8B8A8_UNORM) ||
            !disVulkanImportAhbTexture(&g.disCtx, &g.disCurr, curr,
                                       width, height, VK_FORMAT_R8G8B8A8_UNORM) ||
            !disVulkanImportAhbTexture(&g.disCtx, &g.disFlow, flow,
                                       flowWidth, flowHeight, VK_FORMAT_R16G16B16A16_SFLOAT)) {
            LOGE("DIS import AHB failed");
            releaseAhbs();
            return JNI_FALSE;
        }
        g.disReady = true;
    } else {
        if (!qcomWorkerInit(&g.qcom, width, height, flowWidth, flowHeight)) {
            LOGE("qcomWorkerInit failed");
            releaseAhbs();
            return JNI_FALSE;
        }
        if (!qcomWorkerBindAhb(&g.qcom, prev, curr, flow)) {
            LOGE("qcomWorkerBindAhb failed");
            qcomWorkerDestroy(&g.qcom);
            releaseAhbs();
            return JNI_FALSE;
        }
    }

    g.initialized = true;
    LOGI("nativeServiceInit done");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_FrameGenService_nativeServiceComputeFlow(
        JNIEnv* env, jclass, jobject jFencePfd) {

    if (!g.initialized) return JNI_FALSE;

    int fenceFd = -1;
    if (jFencePfd != nullptr) {
        jclass pfdClass = env->FindClass("android/os/ParcelFileDescriptor");
        jmethodID detachFd = env->GetMethodID(pfdClass, "detachFd", "()I");
        fenceFd = env->CallIntMethod(jFencePfd, detachFd);
        env->DeleteLocalRef(pfdClass);
    }

    eglMakeCurrent(g.display, g.surface, g.surface, g.context);

    bool ok = false;
    if (g.apiMode == 0) {
        ok = disVulkanComputeFlow(&g.disCtx, &g.disPrev, &g.disCurr, &g.disFlow,
                                  g.flowWidth, g.flowHeight, /*useVR=*/true, fenceFd);
    } else {
        if (fenceFd >= 0) close(fenceFd);
        ok = qcomWorkerComputeFlow(&g.qcom);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_FrameGenService_nativeServiceShutdown(JNIEnv*, jclass) {
    if (!g.initialized) return;

    if (g.apiMode == 0 && g.disReady) {
        disVulkanDestroyAhbTexture(&g.disCtx, &g.disPrev);
        disVulkanDestroyAhbTexture(&g.disCtx, &g.disCurr);
        disVulkanDestroyAhbTexture(&g.disCtx, &g.disFlow);
        g.disReady = false;
    } else if (g.apiMode == 1) {
        qcomWorkerDestroy(&g.qcom);
    }

    releaseAhbs();
    g.initialized = false;
    LOGI("shutdown done (context kept)");
}

JNIEXPORT void JNICALL
Java_com_winlator_renderer_FrameGenService_nativeServiceDestroy(JNIEnv*, jclass) {
    if (g.disCtx.initialized) disVulkanCleanup(&g.disCtx);
    qcomWorkerDestroy(&g.qcom);
    destroyEglContext();
    LOGI("service destroyed");
}

} // extern "C"