#pragma once

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <cstdint>
#include <vector>

typedef EGLImageKHR     (EGLAPIENTRYP PFN_eglCreateImageKHR)(EGLDisplay dpy, EGLContext ctx, EGLenum target, EGLClientBuffer buffer, const EGLint *attrib_list);
typedef EGLBoolean      (EGLAPIENTRYP PFN_eglDestroyImageKHR)(EGLDisplay dpy, EGLImageKHR image);
typedef EGLClientBuffer (EGLAPIENTRYP PFN_eglGetNativeClientBufferANDROID)(AHardwareBuffer *buffer);
typedef void (EGLAPIENTRYP PFN_glEGLImageTargetTexture2DOES)(GLenum target, GLeglImageOES image);

typedef VkResult (VKAPI_PTR *PFN_vkGetAndroidHardwareBufferPropertiesANDROID)(
        VkDevice device, const AHardwareBuffer* buffer,
        VkAndroidHardwareBufferPropertiesANDROID* pProperties);
typedef VkResult (VKAPI_PTR *PFN_vkImportFenceFdKHR)(
        VkDevice device, const VkImportFenceFdInfoKHR* pImportFenceFdInfo);

#ifndef EGL_NO_IMAGE_KHR
#define EGL_NO_IMAGE_KHR ((EGLImageKHR)0)
#endif

#define DIS_PYRAMID_LEVELS 4
#define DIS_PROPAGATION_PASSES 4
#define DIS_SOR_ITERATIONS 50

enum DisDebugStage {
    DIS_DEBUG_OFF          = -1,
    DIS_DEBUG_COLOR_PREV   =  0,
    DIS_DEBUG_COLOR_CURR   =  1,
    DIS_DEBUG_LUMA_PREV    =  2,
    DIS_DEBUG_LUMA_CURR    =  3,
    DIS_DEBUG_LUMA_PREV_L1 =  4,
    DIS_DEBUG_LUMA_PREV_L2 =  5,
    DIS_DEBUG_LUMA_PREV_L3 =  6,
    DIS_DEBUG_GRAD_L0      =  7,
    DIS_DEBUG_GRAD_L3      =  8,
    DIS_DEBUG_SPARSE_L0    =  9,
    DIS_DEBUG_SPARSE_L3    = 10,
    DIS_DEBUG_PROP_A       = 11,
    DIS_DEBUG_PROP_B       = 12,
    DIS_DEBUG_DENSE        = 13,
    DIS_DEBUG_FLOW_AHB     = 14,
};

// ── Image resource (internal Vulkan image + memory + view) ──
struct ImageResource {
    VkImage       image   = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView   view    = VK_NULL_HANDLE;
    VkFormat      format  = VK_FORMAT_UNDEFINED;
    int           width   = 0;
    int           height  = 0;
};

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
    bool ownsAhb = true;   // disVulkanCreateAhbTexture → true; import → false
};

// ── Vulkan context ──
struct DisVulkanContext {
    // Core
    VkInstance       instance       = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice         device         = VK_NULL_HANDLE;
    VkQueue          queue          = VK_NULL_HANDLE;
    VkCommandPool    cmdPool        = VK_NULL_HANDLE;
    VkDescriptorPool descPool       = VK_NULL_HANDLE;
    int              queueFamily    = -1;

    // EGL extensions (loaded at runtime)
    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    PFN_eglCreateImageKHR              eglCreateImageKHR;
    PFN_eglDestroyImageKHR             eglDestroyImageKHR;
    PFN_eglGetNativeClientBufferANDROID eglGetNativeClientBufferANDROID;
    PFN_glEGLImageTargetTexture2DOES glEGLImageTargetTexture2DOES;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID vkGetAndroidHardwareBufferPropertiesANDROID;

    // Sampler
    VkSampler sampler = VK_NULL_HANDLE;

    // Descriptor set layouts
    VkDescriptorSetLayout dslMain   = VK_NULL_HANDLE; // 5 samplers + 1 storage
    VkDescriptorSetLayout dslVR     = VK_NULL_HANDLE; // 4 samplers + 2 storage
    VkDescriptorSetLayout dslVRCoef = VK_NULL_HANDLE; // 6 samplers + 2 storage

    // Pipeline layouts
    VkPipelineLayout plMain   = VK_NULL_HANDLE;
    VkPipelineLayout plVR     = VK_NULL_HANDLE;
    VkPipelineLayout plVRCoef = VK_NULL_HANDLE;

    // Pipelines
    VkPipeline pipeDownscaleRgba8     = VK_NULL_HANDLE;
    VkPipeline pipeDownscaleR32       = VK_NULL_HANDLE;
    VkPipeline pipeLumaR32            = VK_NULL_HANDLE;
    VkPipeline pipeGradient           = VK_NULL_HANDLE;
    VkPipeline pipeInverseSearch      = VK_NULL_HANDLE;
    VkPipeline pipePropagate          = VK_NULL_HANDLE;
    VkPipeline pipeDensify            = VK_NULL_HANDLE;
    VkPipeline pipeFlowToAhb          = VK_NULL_HANDLE;
    VkPipeline pipeInterpolate        = VK_NULL_HANDLE;
    VkPipeline pipeVRPrep             = VK_NULL_HANDLE;
    VkPipeline pipeVRD1               = VK_NULL_HANDLE;
    VkPipeline pipeVRD2               = VK_NULL_HANDLE;
    VkPipeline pipeVRW                = VK_NULL_HANDLE;
    VkPipeline pipeVRCoef             = VK_NULL_HANDLE;
    VkPipeline pipeVRSor              = VK_NULL_HANDLE;
    VkPipeline pipeVRAdd              = VK_NULL_HANDLE;

    // Internal resources (allocated in computeFlow)
    bool resourcesAlloced = false;
    int  allocDisW = 0;
    int  allocDisH = 0;

    // DIS-res color downscale
    ImageResource colorPrevDown; // RGBA8
    ImageResource colorCurrDown; // RGBA8

    // Luma pyramid (prev + curr)
    ImageResource lumaPrev[DIS_PYRAMID_LEVELS]; // R32F
    ImageResource lumaCurr[DIS_PYRAMID_LEVELS]; // R32F

    // Gradient pyramid (prev only)
    ImageResource gradPrev[DIS_PYRAMID_LEVELS]; // RG32F

    // Sparse flow per level
    ImageResource sparseFlow[DIS_PYRAMID_LEVELS]; // RGBA32F

    // Propagation ping-pong (level 0 sparse res)
    ImageResource propA; // RGBA32F
    ImageResource propB; // RGBA32F

    // Dense flow
    ImageResource denseFlow; // RG32F

    // VR resources
    ImageResource vrPrep;    // RG32F
    ImageResource vrDw;      // RG32F
    ImageResource vrDwTmp;   // RG32F
    ImageResource vrD1;      // RGBA32F
    ImageResource vrD2;      // RGBA32F
    ImageResource vrWt;      // R32F
    ImageResource vrA;       // RGBA32F
    ImageResource vrB;       // RG32F
    ImageResource vrFlowRefined; // RG32F

    // Descriptor sets
    std::vector<VkDescriptorSet> dsMain;  // for dslMain
    std::vector<VkDescriptorSet> dsVR;    // for dslVR
    VkDescriptorSet dsVRCoef = VK_NULL_HANDLE;

    bool initialized = false;

    VkFence frameFence = VK_NULL_HANDLE;
    VkCommandBuffer frameCmd = VK_NULL_HANDLE;

    VkPipeline pipeDebugCopy = VK_NULL_HANDLE;
    int        debugStage = DIS_DEBUG_OFF;

    PFN_vkImportFenceFdKHR vkImportFenceFdKHR = nullptr;
};

// ── C API ──
bool disVulkanInit(DisVulkanContext* ctx, EGLDisplay eglDisplay);
void disVulkanCleanup(DisVulkanContext* ctx);

bool disVulkanCreateAhbTexture(DisVulkanContext* ctx, AhbTexture* tex,
                               int width, int height, VkFormat vkFormat);
void disVulkanDestroyAhbTexture(DisVulkanContext* ctx, AhbTexture* tex);

bool disVulkanComputeFlow(DisVulkanContext* ctx,
                          AhbTexture* prevAhb, AhbTexture* currAhb,
                          AhbTexture* flowAhb,
                          int disWidth, int disHeight,
                          bool useVR,
                          int waitFenceFd);

bool disVulkanImportAhbTexture(DisVulkanContext* ctx, AhbTexture* tex,
                               AHardwareBuffer* ahb, int w, int h, VkFormat fmt);

// Helper: get GL texture ID from AhbTexture
GLuint disVulkanGetGlTexture(const AhbTexture* tex);

void disVulkanSetDebugStage(DisVulkanContext* ctx, int stage);