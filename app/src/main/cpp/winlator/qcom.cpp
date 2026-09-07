#include <jni.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <EGL/egl.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "FrameGenQCOM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef void (*PFNGLTEXESTIMATEMOTIONQCOM)(GLuint ref, GLuint target, GLuint output);

static PFNGLTEXESTIMATEMOTIONQCOM glTexEstimateMotionQCOM = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_effects_FrameGenerationEffect_nativeInitQCOM(JNIEnv* env, jclass clazz) {
    const char* extensions = (const char*)glGetString(GL_EXTENSIONS);
    if (!extensions) return JNI_FALSE;

    if (strstr(extensions, "GL_QCOM_motion_estimation") != nullptr)
        glTexEstimateMotionQCOM = (PFNGLTEXESTIMATEMOTIONQCOM)eglGetProcAddress("glTexEstimateMotionQCOM");

    if (glTexEstimateMotionQCOM) {
        LOGI("glTexEstimateMotionQCOM ptr = %p", glTexEstimateMotionQCOM);
        return JNI_TRUE;
    } else {
        LOGE("Extension present but function not found");
    }

    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_effects_FrameGenerationEffect_nativeTexEstimateMotionQCOM(JNIEnv* env, jclass clazz,
                                                                                     jint ref, jint target, jint output) {
    if (glTexEstimateMotionQCOM) {
        //LOGI("Calling glTexEstimateMotionQCOM(ref=%d, target=%d, output=%d)", ref, target, output);
        glTexEstimateMotionQCOM(ref, target, output);
    }
    if (!glTexEstimateMotionQCOM) {
        LOGE("glTexEstimateMotionQCOM is NULL!");
        return;
    }
}