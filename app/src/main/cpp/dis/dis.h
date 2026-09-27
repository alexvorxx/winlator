#pragma once

// Glue between the GLES frame-generation effect and the DIS frame generator (include/vkr_dis.h).
//
// Frames reach DIS through AHardwareBuffers shared between GLES and Vulkan: the effect copies
// each captured real frame into one, DIS computes the optical flow of the pair once, and every
// generated frame is rendered by DIS into another AHB that the effect then draws.
//
// Both directions are ordered on the GPU with native fences (EGL_ANDROID_native_fence_sync on the
// GLES side, VK_KHR_external_semaphore_fd on the Vulkan side), so the GL thread never waits for
// either API to finish. Without those extensions it falls back to finishing each side on the CPU.

#include "vkr_dis.h"

#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <cstdint>

typedef EGLImageKHR     (EGLAPIENTRYP PFN_eglCreateImageKHR)(EGLDisplay dpy, EGLContext ctx, EGLenum target, EGLClientBuffer buffer, const EGLint *attrib_list);
typedef EGLBoolean      (EGLAPIENTRYP PFN_eglDestroyImageKHR)(EGLDisplay dpy, EGLImageKHR image);
typedef EGLClientBuffer (EGLAPIENTRYP PFN_eglGetNativeClientBufferANDROID)(const AHardwareBuffer *buffer);
typedef void (EGLAPIENTRYP PFN_glEGLImageTargetTexture2DOES)(GLenum target, GLeglImageOES image);

#ifndef EGL_NO_IMAGE_KHR
#define EGL_NO_IMAGE_KHR ((EGLImageKHR)0)
#endif

// ── AHB-backed texture (shared between GLES and Vulkan) ──
struct AhbTexture {
    AHardwareBuffer* ahb       = nullptr;
    EGLImageKHR      eglImage  = EGL_NO_IMAGE_KHR;
    GLuint           glTexture = 0;
    VkImage          vkImage   = VK_NULL_HANDLE;
    VkDeviceMemory   vkMemory  = VK_NULL_HANDLE;
    VkImageView      vkView    = VK_NULL_HANDLE;
    VkDevice         vkDevice  = VK_NULL_HANDLE;
    int              width     = 0;
    int              height    = 0;
    VkFormat         format    = VK_FORMAT_UNDEFINED;
};

// One in-flight Vulkan submission: its command buffer, the fence that says it is done, the
// semaphore it waits on (a GLES fence imported for this submit) and the one it signals (exported
// to GLES).
#define DIS_SUBMIT_RING 4
struct DisSubmitSlot {
    VkCommandBuffer cmd       = VK_NULL_HANDLE;
    VkFence         fence     = VK_NULL_HANDLE;
    VkSemaphore     waitSem   = VK_NULL_HANDLE;
    VkSemaphore     signalSem = VK_NULL_HANDLE;
    bool            submitted = false;
};

// ── Vulkan context ──
struct DisVulkanContext {
    VkInstance       instance       = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice         device         = VK_NULL_HANDLE;
    VkQueue          queue          = VK_NULL_HANDLE;
    VkCommandPool    cmdPool        = VK_NULL_HANDLE;
    int              queueFamily    = -1;

    DisSubmitSlot    slots[DIS_SUBMIT_RING];
    uint32_t         nextSlot = 0;

    // EGL extensions (loaded at runtime)
    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    PFN_eglCreateImageKHR               eglCreateImageKHR = nullptr;
    PFN_eglDestroyImageKHR              eglDestroyImageKHR = nullptr;
    PFN_eglGetNativeClientBufferANDROID eglGetNativeClientBufferANDROID = nullptr;
    PFN_glEGLImageTargetTexture2DOES    glEGLImageTargetTexture2DOES = nullptr;

    // GPU-side synchronisation between the two APIs; all set, or asyncSync is false.
    bool asyncSync = false;
    PFNEGLCREATESYNCKHRPROC            eglCreateSyncKHR = nullptr;
    PFNEGLDESTROYSYNCKHRPROC           eglDestroySyncKHR = nullptr;
    PFNEGLWAITSYNCKHRPROC              eglWaitSyncKHR = nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC  eglDupNativeFenceFDANDROID = nullptr;
    PFN_vkImportSemaphoreFdKHR         importSemaphoreFd = nullptr;
    PFN_vkGetSemaphoreFdKHR            getSemaphoreFd = nullptr;

    // The frame generator and the frame size it was last prepared for.
    VkrDis*  dis = nullptr;
    uint32_t minSide = 180;
    int      frameWidth = 0;
    int      frameHeight = 0;
    bool     debugFlow = false;

    // CPU time spent in the two calls, logged now and then.
    double   pushMs = 0.0, generateMs = 0.0;
    uint32_t pushCount = 0, generateCount = 0;

    bool initialized = false;
};

// ── C API ──
bool disVulkanInit(DisVulkanContext* ctx, EGLDisplay eglDisplay);
void disVulkanCleanup(DisVulkanContext* ctx);

bool disVulkanCreateAhbTexture(DisVulkanContext* ctx, AhbTexture* tex,
                               int width, int height, VkFormat vkFormat);
void disVulkanDestroyAhbTexture(DisVulkanContext* ctx, AhbTexture* tex);
GLuint disVulkanGetGlTexture(const AhbTexture* tex);

// Flow resolution: pixels on the frame's shorter side.
void disVulkanSetMinSide(DisVulkanContext* ctx, uint32_t minSide);

// A new real frame has just been written into `frame` by GLES (the commands may still be in
// flight). Computes the flow from the previous real frame to this one; `generations` is how many
// frames will be generated for the pair (1..3), which sizes the refinement budget.
bool disVulkanPushFrame(DisVulkanContext* ctx, AhbTexture* frame, int generations);

// Renders the frame at time t in [0, 1] between the last two real frames into `out`. GLES
// commands issued after this call see the finished frame.
bool disVulkanGenerate(DisVulkanContext* ctx, AhbTexture* out, float t);

void disVulkanSetDebugFlow(DisVulkanContext* ctx, bool enabled);
