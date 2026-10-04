#pragma once
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <cstdint>
#include <GLES2/gl2ext.h>
#include <jni.h>

typedef EGLClientBuffer (EGLAPIENTRYP PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)(const AHardwareBuffer*);
typedef EGLImageKHR    (EGLAPIENTRYP PFNEGLCREATEIMAGEKHRPROC)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint*);
typedef EGLBoolean     (EGLAPIENTRYP PFNEGLDESTROYIMAGEKHRPROC)(EGLDisplay, EGLImageKHR);
typedef void           (EGLAPIENTRYP PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)(GLenum, GLeglImageOES);
typedef void           (GL_APIENTRYP PFNGLTEXESTIMATEMOTIONQCOMPROC)(GLuint ref, GLuint target, GLuint output);

struct QcomWorkerCtx {
    // EGL extensions
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNativeClientBuffer = nullptr;
    PFNEGLCREATEIMAGEKHRPROC               createImage           = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC              destroyImage          = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC    targetTexture         = nullptr;
    PFNGLTEXESTIMATEMOTIONQCOMPROC         texEstimateMotion     = nullptr;

    // AHB-backed GL textures
    GLuint prevTex = 0;
    GLuint currTex = 0;
    GLuint flowTex = 0;
    EGLImageKHR prevImg = EGL_NO_IMAGE_KHR;
    EGLImageKHR currImg = EGL_NO_IMAGE_KHR;
    EGLImageKHR flowImg = EGL_NO_IMAGE_KHR;

    // Luma ping-pong
    GLuint lumaPrevTex = 0;
    GLuint lumaCurrTex = 0;
    GLuint lumaPrevFbo = 0;
    GLuint lumaCurrFbo = 0;
    int    lumaW = 0;
    int    lumaH = 0;

    // Luma program
    GLuint lumaProgram  = 0;
    GLint  uLumaSrcLoc  = -1;
    GLint  aPositionLoc = -1;

    // Fullscreen quad VAO+VBO
    GLuint quadVao = 0;
    GLuint quadVbo = 0;

    int width = 0, height = 0;
    int flowWidth = 0, flowHeight = 0;
    int searchBlockX = 4;
    int searchBlockY = 4;

    bool initialized = false;
};

bool qcomWorkerInit(QcomWorkerCtx* ctx, int w, int h, int fw, int fh);
void qcomWorkerDestroy(QcomWorkerCtx* ctx);

bool qcomWorkerBindAhb(QcomWorkerCtx* ctx,
                       AHardwareBuffer* prev,
                       AHardwareBuffer* curr,
                       AHardwareBuffer* flow);

bool qcomWorkerComputeFlow(QcomWorkerCtx* ctx);