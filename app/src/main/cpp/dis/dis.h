#pragma once

// Glue between the GLES frame-generation effect and the DIS frame generator (include/vkr_dis.h).
//
// Frames reach DIS through AHardwareBuffers shared between GLES and Vulkan: the effect copies
// each captured real frame into one, DIS computes the optical flow of the pair once, and every
// generated frame is rendered by DIS into another AHB that the effect then draws.

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

// ── Vulkan context ──
struct DisVulkanContext {
    VkInstance       instance       = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice         device         = VK_NULL_HANDLE;
    VkQueue          queue          = VK_NULL_HANDLE;
    VkCommandPool    cmdPool        = VK_NULL_HANDLE;
    VkCommandBuffer  frameCmd       = VK_NULL_HANDLE;
    VkFence          frameFence     = VK_NULL_HANDLE;
    int              queueFamily    = -1;

    // EGL extensions (loaded at runtime)
    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    PFN_eglCreateImageKHR               eglCreateImageKHR = nullptr;
    PFN_eglDestroyImageKHR              eglDestroyImageKHR = nullptr;
    PFN_eglGetNativeClientBufferANDROID eglGetNativeClientBufferANDROID = nullptr;
    PFN_glEGLImageTargetTexture2DOES    glEGLImageTargetTexture2DOES = nullptr;

    // The frame generator and the frame size it was last prepared for.
    VkrDis*  dis = nullptr;
    uint32_t minSide = 180;
    int      frameWidth = 0;
    int      frameHeight = 0;
    bool     debugFlow = false;

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

// A new real frame is in `frame` (GLES has finished writing it). Computes the flow from the
// previous real frame to this one; `generations` is how many frames will be generated for the
// pair (1..3), which sizes the refinement budget.
bool disVulkanPushFrame(DisVulkanContext* ctx, AhbTexture* frame, int generations);

// Renders the frame at time t in [0, 1] between the last two real frames into `out`.
bool disVulkanGenerate(DisVulkanContext* ctx, AhbTexture* out, float t);

void disVulkanSetDebugFlow(DisVulkanContext* ctx, bool enabled);
