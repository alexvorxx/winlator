#include "qcom.h"
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <__algorithm/max.h>

#define LOG_TAG "FrameGenQcom"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char* kVertSrc =
        "#version 300 es\n"
        "layout(location=0) in vec2 aPosition;\n"
        "out vec2 vUV;\n"
        "void main() {\n"
        "  vUV = aPosition * 0.5 + 0.5;\n"
        "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
        "}\n";

static const char* kLumaFragSrc =
        "#version 300 es\n"
        "precision mediump float;\n"
        "in vec2 vUV;\n"
        "uniform sampler2D uSrc;\n"
        "out vec4 fragColor;\n"
        "void main() {\n"
        "  vec3 c = texture(uSrc, vUV).rgb;\n"
        "  float y = dot(c, vec3(0.299, 0.587, 0.114));\n"
        "  fragColor = vec4(y, 0.0, 0.0, 1.0);\n"
        "}\n";

static GLuint compileShader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        LOGE("shader compile: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static GLuint buildLumaProgram(GLint* aPos, GLint* uSrc) {
    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertSrc);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kLumaFragSrc);
    if (!vs || !fs) return 0;
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glBindAttribLocation(p, 0, "aPosition");
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        LOGE("link: %s", log);
        glDeleteProgram(p);
        p = 0;
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (p) {
        if (aPos) *aPos = glGetAttribLocation(p, "aPosition");
        if (uSrc) *uSrc = glGetUniformLocation(p, "uSrc");
    }
    return p;
}

static bool makeTextureFromAhb(QcomWorkerCtx* ctx, AHardwareBuffer* ahb,
                               EGLImageKHR* outImg, GLuint* outTex) {
    EGLClientBuffer cb = ctx->getNativeClientBuffer(ahb);
    if (!cb) { LOGE("getNativeClientBuffer null"); return false; }
    EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR img = ctx->createImage(eglGetCurrentDisplay(), EGL_NO_CONTEXT,
                                       EGL_NATIVE_BUFFER_ANDROID, cb, attrs);
    if (img == EGL_NO_IMAGE_KHR) { LOGE("createImage failed"); return false; }

    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    ctx->targetTexture(GL_TEXTURE_2D, img);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    *outImg = img;
    *outTex = tex;
    return true;
}

bool qcomWorkerInit(QcomWorkerCtx* ctx, int w, int h, int fw, int fh) {
    if (ctx->initialized) return true;

    ctx->getNativeClientBuffer = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)
            eglGetProcAddress("eglGetNativeClientBufferANDROID");
    ctx->createImage = (PFNEGLCREATEIMAGEKHRPROC)
            eglGetProcAddress("eglCreateImageKHR");
    ctx->destroyImage = (PFNEGLDESTROYIMAGEKHRPROC)
            eglGetProcAddress("eglDestroyImageKHR");
    ctx->targetTexture = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)
            eglGetProcAddress("glEGLImageTargetTexture2DOES");
    ctx->texEstimateMotion = (PFNGLTEXESTIMATEMOTIONQCOMPROC)
            eglGetProcAddress("glTexEstimateMotionQCOM");

    if (!ctx->getNativeClientBuffer || !ctx->createImage ||
        !ctx->targetTexture || !ctx->texEstimateMotion) {
        LOGE("QCOM/EGL extensions unavailable");
        return false;
    }

    // Check GLES extension
    const char* exts = (const char*)glGetString(GL_EXTENSIONS);
    if (!exts || !strstr(exts, "GL_QCOM_motion_estimation")) {
        LOGE("GL_QCOM_motion_estimation not present");
        return false;
    }

    // Search block size (multiplier for luma dimensions)
    GLint blockX = 4, blockY = 4;
    glGetIntegerv(0x8C90, &blockX); // GL_MOTION_ESTIMATION_SEARCH_BLOCK_X_QCOM
    glGetIntegerv(0x8C91, &blockY); // GL_MOTION_ESTIMATION_SEARCH_BLOCK_Y_QCOM
    ctx->searchBlockX = blockX > 0 ? blockX : 4;
    ctx->searchBlockY = blockY > 0 ? blockY : 4;

    // Luma dimensions derived from the flow texture size that main allocated.
    int lw = fw * ctx->searchBlockX;
    int lh = fh * ctx->searchBlockY;
    if (lw < 8) lw = 8;
    if (lh < 8) lh = 8;
    ctx->lumaW = lw;
    ctx->lumaH = lh;

    ctx->width = w;
    ctx->height = h;
    ctx->flowWidth = fw;
    ctx->flowHeight = fh;

    // Luma textures + FBOs
    glGenTextures(1, &ctx->lumaPrevTex);
    glBindTexture(GL_TEXTURE_2D, ctx->lumaPrevTex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8, lw, lh);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenTextures(1, &ctx->lumaCurrTex);
    glBindTexture(GL_TEXTURE_2D, ctx->lumaCurrTex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R8, lw, lh);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenFramebuffers(1, &ctx->lumaPrevFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, ctx->lumaPrevFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ctx->lumaPrevTex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        LOGE("lumaPrevFbo incomplete");

    glGenFramebuffers(1, &ctx->lumaCurrFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, ctx->lumaCurrFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ctx->lumaCurrTex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        LOGE("lumaCurrFbo incomplete");

    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    // Fullscreen quad VAO+VBO
    static const GLfloat verts[8] = {
            -1.f, -1.f,
            1.f, -1.f,
            -1.f,  1.f,
            1.f,  1.f
    };

    glGenVertexArrays(1, &ctx->quadVao);
    glBindVertexArray(ctx->quadVao);
    {
        glGenBuffers(1, &ctx->quadVbo);
        glBindBuffer(GL_ARRAY_BUFFER, ctx->quadVbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
    glBindVertexArray(0);

    // Luma program
    ctx->lumaProgram = buildLumaProgram(&ctx->aPositionLoc, &ctx->uLumaSrcLoc);
    if (!ctx->lumaProgram) { LOGE("luma program build failed"); return false; }

    ctx->initialized = true;
    LOGI("QCOM worker init OK, luma=%dx%d block=%dx%d",
         lw, lh, ctx->searchBlockX, ctx->searchBlockY);
    return true;
}

bool qcomWorkerBindAhb(QcomWorkerCtx* ctx,
                       AHardwareBuffer* prev,
                       AHardwareBuffer* curr,
                       AHardwareBuffer* flow) {
    if (!ctx->initialized) return false;

    // Destroy old GL textures/images (keep luma+program)
    if (ctx->prevImg != EGL_NO_IMAGE_KHR) ctx->destroyImage(eglGetCurrentDisplay(), ctx->prevImg);
    if (ctx->currImg != EGL_NO_IMAGE_KHR) ctx->destroyImage(eglGetCurrentDisplay(), ctx->currImg);
    if (ctx->flowImg != EGL_NO_IMAGE_KHR) ctx->destroyImage(eglGetCurrentDisplay(), ctx->flowImg);
    if (ctx->prevTex) glDeleteTextures(1, &ctx->prevTex);
    if (ctx->currTex) glDeleteTextures(1, &ctx->currTex);
    if (ctx->flowTex) glDeleteTextures(1, &ctx->flowTex);
    ctx->prevImg = ctx->currImg = ctx->flowImg = EGL_NO_IMAGE_KHR;
    ctx->prevTex = ctx->currTex = ctx->flowTex = 0;

    if (!makeTextureFromAhb(ctx, prev, &ctx->prevImg, &ctx->prevTex)) return false;
    if (!makeTextureFromAhb(ctx, curr, &ctx->currImg, &ctx->currTex)) return false;
    if (!makeTextureFromAhb(ctx, flow, &ctx->flowImg, &ctx->flowTex)) return false;

    LOGI("QCOM AHB bound: prev=%u curr=%u flow=%u", ctx->prevTex, ctx->currTex, ctx->flowTex);
    return true;
}

static void drawFullscreenQuad(QcomWorkerCtx* ctx) {
    glBindVertexArray(ctx->quadVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
}

static void renderLuma(QcomWorkerCtx* ctx, GLuint srcTex, GLuint dstFbo) {
    glBindFramebuffer(GL_FRAMEBUFFER, dstFbo);
    glViewport(0, 0, ctx->lumaW, ctx->lumaH);
    glDisable(GL_BLEND);
    glUseProgram(ctx->lumaProgram);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, srcTex);
    glUniform1i(ctx->uLumaSrcLoc, 0);
    drawFullscreenQuad(ctx);
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

bool qcomWorkerComputeFlow(QcomWorkerCtx* ctx) {
    if (!ctx->initialized || !ctx->prevTex || !ctx->currTex || !ctx->flowTex)
        return false;

    renderLuma(ctx, ctx->prevTex, ctx->lumaPrevFbo);
    renderLuma(ctx, ctx->currTex, ctx->lumaCurrFbo);

    ctx->texEstimateMotion(ctx->lumaPrevTex, ctx->lumaCurrTex, ctx->flowTex);
    //glFlush();
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("texEstimateMotion failed: 0x%x", err);
        return false;
    }

    return true;
}

void qcomWorkerDestroy(QcomWorkerCtx* ctx) {
    if (!ctx->initialized) return;

    if (ctx->prevImg != EGL_NO_IMAGE_KHR) ctx->destroyImage(eglGetCurrentDisplay(), ctx->prevImg);
    if (ctx->currImg != EGL_NO_IMAGE_KHR) ctx->destroyImage(eglGetCurrentDisplay(), ctx->currImg);
    if (ctx->flowImg != EGL_NO_IMAGE_KHR) ctx->destroyImage(eglGetCurrentDisplay(), ctx->flowImg);
    if (ctx->prevTex) glDeleteTextures(1, &ctx->prevTex);
    if (ctx->currTex) glDeleteTextures(1, &ctx->currTex);
    if (ctx->flowTex) glDeleteTextures(1, &ctx->flowTex);
    if (ctx->lumaPrevTex) glDeleteTextures(1, &ctx->lumaPrevTex);
    if (ctx->lumaCurrTex) glDeleteTextures(1, &ctx->lumaCurrTex);
    if (ctx->lumaPrevFbo) glDeleteFramebuffers(1, &ctx->lumaPrevFbo);
    if (ctx->lumaCurrFbo) glDeleteFramebuffers(1, &ctx->lumaCurrFbo);
    if (ctx->quadVbo) glDeleteBuffers(1, &ctx->quadVbo);
    if (ctx->quadVao) glDeleteVertexArrays(1, &ctx->quadVao);
    if (ctx->lumaProgram) glDeleteProgram(ctx->lumaProgram);

    ctx->prevImg = ctx->currImg = ctx->flowImg = EGL_NO_IMAGE_KHR;
    ctx->prevTex = ctx->currTex = ctx->flowTex = 0;
    ctx->lumaPrevTex = ctx->lumaCurrTex = 0;
    ctx->lumaPrevFbo = ctx->lumaCurrFbo = 0;
    ctx->quadVbo = 0;
    ctx->quadVao = 0;
    ctx->lumaProgram = 0;
    ctx->initialized = false;
}

static PFNGLTEXESTIMATEMOTIONQCOMPROC glTexEstimateMotionQCOM = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_effects_FrameGenerationEffect_nativeInitQCOM(JNIEnv* env, jclass clazz) {
    const char* extensions = (const char*)glGetString(GL_EXTENSIONS);
    if (!extensions) return JNI_FALSE;

    if (strstr(extensions, "GL_QCOM_motion_estimation") != nullptr)
        glTexEstimateMotionQCOM = (PFNGLTEXESTIMATEMOTIONQCOMPROC)eglGetProcAddress("glTexEstimateMotionQCOM");

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