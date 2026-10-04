#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <unistd.h>
#include <sys/socket.h>

#define LOG_TAG "FrameGenJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_winlator_renderer_effects_FrameGenerationEffect_nativeCreateHardwareBuffer(
        JNIEnv* env, jclass, jint w, jint h, jint format) {
    AHardwareBuffer_Desc d = {};
    d.width = w; d.height = h; d.layers = 1;
    d.format = (uint32_t)format;
    d.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
              | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    AHardwareBuffer* ahb = nullptr;
    if (AHardwareBuffer_allocate(&d, &ahb) != 0) return nullptr;
    jobject j = AHardwareBuffer_toHardwareBuffer(env, ahb);
    AHardwareBuffer_release(ahb);
    return j;
}

JNIEXPORT jint JNICALL
Java_com_winlator_renderer_effects_FrameGenerationEffect_nativeAhbToGlTexture(
        JNIEnv* env, jclass clazz, jobject jAhb) {
    (void)env; (void)clazz;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jAhb);
    if (!ahb) return 0;

    auto getNativeClientBuffer =
            (EGLClientBuffer(*)(const AHardwareBuffer*))
                    eglGetProcAddress("eglGetNativeClientBufferANDROID");
    auto createImage =
            (EGLImageKHR(*)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint*))
                    eglGetProcAddress("eglCreateImageKHR");
    auto targetTexture =
            (void(*)(GLenum, GLeglImageOES))
                    eglGetProcAddress("glEGLImageTargetTexture2DOES");

    if (!getNativeClientBuffer || !createImage || !targetTexture) {
        LOGE("EGL AHB extensions unavailable");
        return 0;
    }

    EGLClientBuffer buf = getNativeClientBuffer(ahb);
    if (!buf) { LOGE("getNativeClientBuffer returned null"); return 0; }

    EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR img = createImage(eglGetCurrentDisplay(), EGL_NO_CONTEXT,
                                  EGL_NATIVE_BUFFER_ANDROID, buf, attrs);
    if (img == EGL_NO_IMAGE_KHR) { LOGE("eglCreateImageKHR failed"); return 0; }

    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    targetTexture(GL_TEXTURE_2D, img);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    return (jint)tex;
}

typedef EGLSyncKHR (EGLAPIENTRYP PFNEGLCREATESYNCKHRPROC)(EGLDisplay, EGLenum, const EGLint*);
typedef EGLBoolean (EGLAPIENTRYP PFNEGLDESTROYSYNCKHRPROC)(EGLDisplay, EGLSyncKHR);
typedef EGLint     (EGLAPIENTRYP PFNEGLDUPNATIVEFENCEFDANDROIDPROC)(EGLDisplay, EGLSyncKHR);

JNIEXPORT jint JNICALL
Java_com_winlator_renderer_effects_FrameGenerationEffect_nativeCreateEglFenceFd(
        JNIEnv* env, jclass) {
    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy == EGL_NO_DISPLAY) dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY) { LOGE("no EGL display"); return -1; }

    auto createSync = (PFNEGLCREATESYNCKHRPROC)
            eglGetProcAddress("eglCreateSyncKHR");
    auto dupFenceFd = (PFNEGLDUPNATIVEFENCEFDANDROIDPROC)
            eglGetProcAddress("eglDupNativeFenceFDANDROID");
    auto destroySync = (PFNEGLDESTROYSYNCKHRPROC)
            eglGetProcAddress("eglDestroySyncKHR");

    if (!createSync || !dupFenceFd || !destroySync) {
        LOGE("EGL native fence extensions unavailable");
        return -1;
    }

    const EGLint attrs[] = {
            EGL_SYNC_NATIVE_FENCE_SIGNALED_ANDROID, EGL_FALSE,
            EGL_NONE
    };
    EGLSyncKHR sync = createSync(dpy, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
    if (sync == EGL_NO_SYNC_KHR) {
        LOGE("eglCreateSyncKHR failed err=0x%x", eglGetError());
        return -1;
    }

    glFlush();

    int fd = dupFenceFd(dpy, sync);
    destroySync(dpy, sync);

    if (fd < 0) {
        LOGE("eglDupNativeFenceFDANDROID failed err=0x%x", eglGetError());
    }
    return fd;
}

} // extern "C"