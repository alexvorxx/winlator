#include "dis.h"

#include <android/asset_manager.h>
#include <android/log.h>
#include <cstring>
#include <cmath>

#define LOG_TAG "DisVulkan"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ── EGL extension function pointer types ──
typedef EGLImageKHR  (EGLAPIENTRYP FnEglCreateImageKHR)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint*);
typedef EGLBoolean   (EGLAPIENTRYP FnEglDestroyImageKHR)(EGLDisplay, EGLImageKHR);
typedef EGLClientBuffer (EGLAPIENTRYP FnEglGetNativeClientBufferANDROID)(const AHardwareBuffer*);
typedef void (EGLAPIENTRYP FnGlEGLImageTargetTexture2DOES)(GLenum, GLeglImageOES);

typedef EGLSyncKHR (EGLAPIENTRYP FnEglCreateSyncKHR)(EGLDisplay, EGLenum, const EGLint*);
typedef EGLBoolean  (EGLAPIENTRYP FnEglDestroySyncKHR)(EGLDisplay, EGLSyncKHR);
typedef EGLint      (EGLAPIENTRYP FnEglClientWaitSyncKHR)(EGLDisplay, EGLSyncKHR, EGLint, EGLTimeKHR);
typedef EGLint      (EGLAPIENTRYP FnEglDupNativeFenceFDANDROID)(EGLDisplay, EGLSyncKHR);
typedef VkResult    (VKAPI_PTR *PFN_vkImportFenceFdKHR)(VkDevice, const VkImportFenceFdInfoKHR*);
typedef VkResult    (VKAPI_PTR *PFN_vkGetFenceFdKHR)(VkDevice, const VkFenceGetFdInfoKHR*, int*);

// ════════════════════════════════════════════════════════════
//  Helpers
// ════════════════════════════════════════════════════════════

static uint32_t findMemoryType(VkPhysicalDevice dev, uint32_t typeBits,
                               VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(dev, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) &&
            (memProps.memoryTypes[i].propertyFlags & props) == props)
            return i;
    }
    return UINT32_MAX;
}

static bool createImage(DisVulkanContext* ctx, ImageResource* res,
                        int w, int h, VkFormat fmt,
                        VkImageUsageFlags usage) {
    res->format = fmt;
    res->width  = w;
    res->height = h;

    VkImageCreateInfo ci = {};
    ci.sType       = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ci.imageType   = VK_IMAGE_TYPE_2D;
    ci.format      = fmt;
    ci.extent      = { (uint32_t)w, (uint32_t)h, 1 };
    ci.mipLevels   = 1;
    ci.arrayLayers = 1;
    ci.samples     = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling      = VK_IMAGE_TILING_OPTIMAL;
    ci.usage       = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(ctx->device, &ci, nullptr, &res->image) != VK_SUCCESS) {
        LOGE("vkCreateImage failed %dx%d", w, h);
        return false;
    }

    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(ctx->device, res->image, &req);

    VkMemoryAllocateInfo ai = {};
    ai.sType          = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = findMemoryType(ctx->physicalDevice, req.memoryTypeBits,
                                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) {
        LOGE("findMemoryType failed");
        vkDestroyImage(ctx->device, res->image, nullptr);
        res->image = VK_NULL_HANDLE;
        return false;
    }

    if (vkAllocateMemory(ctx->device, &ai, nullptr, &res->memory) != VK_SUCCESS) {
        LOGE("vkAllocateMemory failed");
        vkDestroyImage(ctx->device, res->image, nullptr);
        res->image = VK_NULL_HANDLE;
        return false;
    }
    vkBindImageMemory(ctx->device, res->image, res->memory, 0);

    // Create view (usable for both sampled and storage)
    VkImageViewCreateInfo vi = {};
    vi.sType      = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image      = res->image;
    vi.viewType   = VK_IMAGE_VIEW_TYPE_2D;
    vi.format      = fmt;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount  = 1;

    if (vkCreateImageView(ctx->device, &vi, nullptr, &res->view) != VK_SUCCESS) {
        LOGE("vkCreateImageView failed");
        vkDestroyImage(ctx->device, res->image, nullptr);
        vkFreeMemory(ctx->device, res->memory, nullptr);
        res->image  = VK_NULL_HANDLE;
        res->memory = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

static void destroyImage(DisVulkanContext* ctx, ImageResource* res) {
    if (res->view)    vkDestroyImageView(ctx->device, res->view, nullptr);
    if (res->image)  vkDestroyImage(ctx->device, res->image, nullptr);
    if (res->memory) vkFreeMemory(ctx->device, res->memory, nullptr);
    res->image  = VK_NULL_HANDLE;
    res->memory = VK_NULL_HANDLE;
    res->view   = VK_NULL_HANDLE;
}

static std::vector<uint32_t> loadShader(AAssetManager* mgr, const char* path) {
    AAsset* a = AAssetManager_open(mgr, path, AASSET_MODE_BUFFER);
    if (!a) {
        LOGE("Shader not found: %s", path);
        return {};
    }
    size_t sz = AAsset_getLength(a);
    std::vector<uint32_t> code(sz / 4);
    AAsset_read(a, code.data(), sz);
    AAsset_close(a);
    LOGI("Loaded shader: %s (%zu bytes)", path, sz);
    return code;
}

static VkShaderModule createShaderModule(DisVulkanContext* ctx,
                                          const std::vector<uint32_t>& code) {
    if (code.empty()) return VK_NULL_HANDLE;
    VkShaderModuleCreateInfo ci = {};
    ci.sType    = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize = code.size() * 4;
    ci.pCode    = code.data();
    VkShaderModule mod;
    if (vkCreateShaderModule(ctx->device, &ci, nullptr, &mod) != VK_SUCCESS) {
        LOGE("vkCreateShaderModule failed");
        return VK_NULL_HANDLE;
    }
    return mod;
}

static VkPipeline createComputePipe(DisVulkanContext* ctx,
                                      VkShaderModule shader,
                                      VkPipelineLayout layout,
                                      const char* name) {
    VkComputePipelineCreateInfo ci = {};
    ci.sType  = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    ci.stage.sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    ci.stage.module = shader;
    ci.stage.pName  = "main";
    ci.layout = layout;

    VkPipeline pipe;
    if (vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &ci, nullptr, &pipe) != VK_SUCCESS) {
        LOGE("vkCreateComputePipelines failed: %s", name);
        return VK_NULL_HANDLE;
    }
    vkDestroyShaderModule(ctx->device, shader, nullptr);
    LOGI("Pipeline created: %s", name);
    return pipe;
}

// ════════════════════════════════════════════════════════════
//  Descriptor set layouts
// ════════════════════════════════════════════════════════════

static VkDescriptorSetLayout createDslMain(VkDevice dev) {
    // bindings 0-4: combined image sampler
    // binding 5:   storage image
    VkDescriptorSetLayoutBinding b[6];
    for (int i = 0; i < 5; i++) {
        b[i].binding         = i;
        b[i].descriptorType   = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        b[i].descriptorCount  = 1;
        b[i].stageFlags       = VK_SHADER_STAGE_COMPUTE_BIT;
        b[i].pImmutableSamplers = nullptr;
    }
    b[5].binding         = 5;
    b[5].descriptorType   = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[5].descriptorCount  = 1;
    b[5].stageFlags       = VK_SHADER_STAGE_COMPUTE_BIT;
    b[5].pImmutableSamplers = nullptr;

    VkDescriptorSetLayoutCreateInfo ci = {};
    ci.sType        = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    ci.bindingCount = 6;
    ci.pBindings    = b;
    VkDescriptorSetLayout dsl;
    vkCreateDescriptorSetLayout(dev, &ci, nullptr, &dsl);
    return dsl;
}

static VkDescriptorSetLayout createDslVR(VkDevice dev) {
    // bindings 0-3: combined image sampler
    // bindings 8-9: storage image
    VkDescriptorSetLayoutBinding b[6];
    for (int i = 0; i < 4; i++) {
        b[i].binding         = i;
        b[i].descriptorType   = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        b[i].descriptorCount  = 1;
        b[i].stageFlags       = VK_SHADER_STAGE_COMPUTE_BIT;
        b[i].pImmutableSamplers = nullptr;
    }
    b[4].binding         = 8;
    b[4].descriptorType   = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[4].descriptorCount  = 1;
    b[4].stageFlags       = VK_SHADER_STAGE_COMPUTE_BIT;
    b[4].pImmutableSamplers = nullptr;
    b[5].binding         = 9;
    b[5].descriptorType   = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[5].descriptorCount  = 1;
    b[5].stageFlags       = VK_SHADER_STAGE_COMPUTE_BIT;
    b[5].pImmutableSamplers = nullptr;

    VkDescriptorSetLayoutCreateInfo ci = {};
    ci.sType        = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    ci.bindingCount = 6;
    ci.pBindings    = b;
    VkDescriptorSetLayout dsl;
    vkCreateDescriptorSetLayout(dev, &ci, nullptr, &dsl);
    return dsl;
}

static VkDescriptorSetLayout createDslVRCoef(VkDevice dev) {
    // bindings 0-5: combined image sampler
    // bindings 8-9: storage image
    VkDescriptorSetLayoutBinding b[8];
    for (int i = 0; i < 6; i++) {
        b[i].binding         = i;
        b[i].descriptorType   = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        b[i].descriptorCount  = 1;
        b[i].stageFlags       = VK_SHADER_STAGE_COMPUTE_BIT;
        b[i].pImmutableSamplers = nullptr;
    }
    b[6].binding         = 8;
    b[6].descriptorType   = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[6].descriptorCount  = 1;
    b[6].stageFlags       = VK_SHADER_STAGE_COMPUTE_BIT;
    b[6].pImmutableSamplers = nullptr;
    b[7].binding         = 9;
    b[7].descriptorType   = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    b[7].descriptorCount  = 1;
    b[7].stageFlags       = VK_SHADER_STAGE_COMPUTE_BIT;
    b[7].pImmutableSamplers = nullptr;

    VkDescriptorSetLayoutCreateInfo ci = {};
    ci.sType        = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    ci.bindingCount = 8;
    ci.pBindings    = b;
    VkDescriptorSetLayout dsl;
    vkCreateDescriptorSetLayout(dev, &ci, nullptr, &dsl);
    return dsl;
}

static VkPipelineLayout createPipelineLayout(VkDevice dev,
                                              VkDescriptorSetLayout dsl,
                                              size_t pcSize) {
    VkPushConstantRange pc = {};
    pc.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pc.offset     = 0;
    pc.size       = (uint32_t)pcSize;

    VkPipelineLayoutCreateInfo ci = {};
    ci.sType          = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    ci.setLayoutCount = 1;
    ci.pSetLayouts    = &dsl;
    if (pcSize > 0) {
        ci.pushConstantRangeCount = 1;
        ci.pPushConstantRanges    = &pc;
    }
    VkPipelineLayout pl;
    vkCreatePipelineLayout(dev, &ci, nullptr, &pl);
    return pl;
}

// ════════════════════════════════════════════════════════════
//  Init
// ════════════════════════════════════════════════════════════

bool disVulkanInit(DisVulkanContext* ctx, EGLDisplay eglDisplay, void* assetMgr) {
    if (ctx->initialized) return true;

    // ── Create VkInstance ──
    VkInstanceCreateInfo ici = {};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    if (vkCreateInstance(&ici, nullptr, &ctx->instance) != VK_SUCCESS) {
        LOGE("vkCreateInstance failed");
        return false;
    }

    // ── Pick physical device ──
    uint32_t gpuCount = 0;
    vkEnumeratePhysicalDevices(ctx->instance, &gpuCount, nullptr);
    if (gpuCount == 0) { LOGE("No GPUs"); return false; }
    std::vector<VkPhysicalDevice> gpus(gpuCount);
    vkEnumeratePhysicalDevices(ctx->instance, &gpuCount, gpus.data());
    ctx->physicalDevice = gpus[0]; // first available

    // ── Find compute queue ──
    uint32_t qfCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(ctx->physicalDevice, &qfCount, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qfCount);
    vkGetPhysicalDeviceQueueFamilyProperties(ctx->physicalDevice, &qfCount, qf.data());
    for (uint32_t i = 0; i < qfCount; i++) {
        if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            ctx->queueFamily = (int)i;
            break;
        }
    }
    if (ctx->queueFamily < 0) { LOGE("No compute queue"); return false; }

    // ── Enable AHB extension ──
    const char* devExt = VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME;

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo qci = {};
    qci.sType            = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = (uint32_t)ctx->queueFamily;
    qci.queueCount       = 1;
    qci.pQueuePriorities = &queuePriority;

    VkDeviceCreateInfo dci = {};
    dci.sType                   = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount    = 1;
    dci.pQueueCreateInfos       = &qci;
    dci.enabledExtensionCount   = 1;
    dci.ppEnabledExtensionNames = &devExt;

    if (vkCreateDevice(ctx->physicalDevice, &dci, nullptr, &ctx->device) != VK_SUCCESS) {
        LOGE("vkCreateDevice failed");
        return false;
    }

    // ── Load EGL extensions ──
    ctx->eglDisplay = eglDisplay;
    ctx->eglCreateImageKHR = (PFN_eglCreateImageKHR)eglGetProcAddress("eglCreateImageKHR");
    ctx->eglDestroyImageKHR = (PFN_eglDestroyImageKHR)eglGetProcAddress("eglDestroyImageKHR");
    ctx->eglGetNativeClientBufferANDROID = (PFN_eglGetNativeClientBufferANDROID)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    ctx->glEGLImageTargetTexture2DOES = (PFN_glEGLImageTargetTexture2DOES)eglGetProcAddress("glEGLImageTargetTexture2DOES");

    ctx->vkGetAndroidHardwareBufferPropertiesANDROID =
            (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
                    vkGetDeviceProcAddr(ctx->device, "vkGetAndroidHardwareBufferPropertiesANDROID");

    if (!ctx->eglCreateImageKHR || !ctx->eglGetNativeClientBufferANDROID) {
        LOGE("EGL Android native buffer extensions not available");
        return false;
    }

    LOGI("EGL extensions loaded");

    vkGetDeviceQueue(ctx->device, (uint32_t)ctx->queueFamily, 0, &ctx->queue);

    // ── Command pool ──
    VkCommandPoolCreateInfo pci = {};
    pci.sType            = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pci.flags            = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = (uint32_t)ctx->queueFamily;
    vkCreateCommandPool(ctx->device, &pci, nullptr, &ctx->cmdPool);

    // Create fence (reused every frame)
    VkFenceCreateInfo fci = {};
    fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    vkCreateFence(ctx->device, &fci, nullptr, &ctx->frameFence);

    // Allocate command buffer (reused every frame)
    VkCommandBufferAllocateInfo cbi = {};
    cbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbi.commandPool = ctx->cmdPool;
    cbi.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbi.commandBufferCount = 1;
    vkAllocateCommandBuffers(ctx->device, &cbi, &ctx->frameCmd);

    if (ctx->frameFence == VK_NULL_HANDLE) {
        LOGE("Failed to create fence");
        return false;
    }
    if (ctx->frameCmd == VK_NULL_HANDLE) {
        LOGE("Failed to allocate command buffer");
        return false;
    }

    // ── Sampler ──
    VkSamplerCreateInfo sci = {};
    sci.sType      = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sci.magFilter  = VK_FILTER_LINEAR;
    sci.minFilter  = VK_FILTER_LINEAR;
    sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    vkCreateSampler(ctx->device, &sci, nullptr, &ctx->sampler);

    // ── Descriptor set layouts ──
    ctx->dslMain   = createDslMain(ctx->device);
    ctx->dslVR     = createDslVR(ctx->device);
    ctx->dslVRCoef = createDslVRCoef(ctx->device);

    // ── Pipeline layouts (16 bytes push constants max) ──
    ctx->plMain   = createPipelineLayout(ctx->device, ctx->dslMain,   16);
    ctx->plVR     = createPipelineLayout(ctx->device, ctx->dslVR,     16);
    ctx->plVRCoef = createPipelineLayout(ctx->device, ctx->dslVRCoef, 16);

    // ── Load shaders and create pipelines ──
    AAssetManager* mgr = (AAssetManager*)assetMgr;
    auto loadPipe = [&](const char* path, VkPipelineLayout pl, const char* name) -> VkPipeline {
        auto code = loadShader(mgr, path);
        if (code.empty()) { LOGE("Failed to load: %s", path); return VK_NULL_HANDLE; }
        VkShaderModule sm = createShaderModule(ctx, code);
        if (!sm) { LOGE("Failed to create module: %s", path); return VK_NULL_HANDLE; }
        return createComputePipe(ctx, sm, pl, name);
    };

    ctx->pipeDownscaleRgba8 = loadPipe(DIS_SHADER_DOWNSCALE_RGBA8, ctx->plMain, "downscaleRgba8");
    ctx->pipeDownscaleR32   = loadPipe(DIS_SHADER_DOWNSCALE_R32,   ctx->plMain, "downscaleR32");
    ctx->pipeLumaR32         = loadPipe(DIS_SHADER_LUMA_R32,         ctx->plMain, "lumaR32");
    ctx->pipeGradient        = loadPipe(DIS_SHADER_GRADIENT,         ctx->plMain, "gradient");
    ctx->pipeInverseSearch   = loadPipe(DIS_SHADER_INVERSE_SEARCH,   ctx->plMain, "inverseSearch");
    ctx->pipePropagate       = loadPipe(DIS_SHADER_PROPAGATE,        ctx->plMain, "propagate");
    ctx->pipeDensify         = loadPipe(DIS_SHADER_DENSIFY,          ctx->plMain, "densify");
    ctx->pipeFlowToAhb       = loadPipe(DIS_SHADER_FLOW_TO_AHB,       ctx->plMain, "flowToAhb");
    ctx->pipeInterpolate     = loadPipe(DIS_SHADER_INTERPOLATE,       ctx->plMain, "interpolate");
    ctx->pipeDebugCopy       = loadPipe(DIS_SHADER_DEBUG_COPY,        ctx->plMain, "debugCopy");
    ctx->pipeVRPrep          = loadPipe(DIS_SHADER_VR_PREP,           ctx->plVR,   "vrPrep");
    ctx->pipeVRD1            = loadPipe(DIS_SHADER_VR_D1,             ctx->plVR,   "vrD1");
    ctx->pipeVRD2            = loadPipe(DIS_SHADER_VR_D2,             ctx->plVR,   "vrD2");
    ctx->pipeVRW             = loadPipe(DIS_SHADER_VR_W,              ctx->plVR,   "vrW");
    ctx->pipeVRCoef          = loadPipe(DIS_SHADER_VR_COEF,           ctx->plVRCoef, "vrCoef");
    ctx->pipeVRSor           = loadPipe(DIS_SHADER_VR_SOR,            ctx->plVR,   "vrSor");
    ctx->pipeVRAdd           = loadPipe(DIS_SHADER_VR_ADD,            ctx->plVR,   "vrAdd");

    // Check essential pipelines
    if (!ctx->pipeDownscaleRgba8 || !ctx->pipeLumaR32 || !ctx->pipeGradient ||
        !ctx->pipeInverseSearch || !ctx->pipePropagate || !ctx->pipeDensify ||
        !ctx->pipeFlowToAhb) {
        LOGE("Essential pipeline(s) missing");
        return false;
    }

    // ── Descriptor pool ──
    VkDescriptorPoolSize poolSizes[2] = {};
    poolSizes[0].type            = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[0].descriptorCount = 300;
    poolSizes[1].type            = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 150;

    VkDescriptorPoolCreateInfo dpi = {};
    dpi.sType         = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpi.maxSets       = 60;
    dpi.poolSizeCount = 2;
    dpi.pPoolSizes    = poolSizes;
    dpi.flags         = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    vkCreateDescriptorPool(ctx->device, &dpi, nullptr, &ctx->descPool);

    ctx->initialized = true;
    LOGI("DisVulkan initialized successfully");
    return true;
}

// ════════════════════════════════════════════════════════════
//  AHB texture creation / destruction
// ════════════════════════════════════════════════════════════

static uint32_t vkToAhbFormat(VkFormat fmt) {
    switch (fmt) {
        case VK_FORMAT_R8G8B8A8_UNORM:        return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        case VK_FORMAT_R16G16B16A16_SFLOAT:   return AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
        default: return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    }
}

bool disVulkanCreateAhbTexture(DisVulkanContext* ctx, AhbTexture* tex,
                               int width, int height, VkFormat vkFormat) {
    tex->vkDevice = ctx->device;
    tex->width    = width;
    tex->height   = height;
    tex->format   = vkFormat;

    // ── 1. Allocate AHardwareBuffer ──
    AHardwareBuffer_Desc desc = {};
    desc.width  = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = vkToAhbFormat(vkFormat);
    desc.usage  = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
                  AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;

    if (AHardwareBuffer_allocate(&desc, &tex->ahb) != 0) {
        LOGE("AHardwareBuffer_allocate failed %dx%d", width, height);
        return false;
    }

    // ── 2. Create EGL image from AHB ──
    auto fnGetBuf = (FnEglGetNativeClientBufferANDROID)ctx->eglGetNativeClientBufferANDROID;
    auto fnCreateImg = (FnEglCreateImageKHR)ctx->eglCreateImageKHR;

    EGLClientBuffer clientBuf = fnGetBuf(tex->ahb);
    if (!clientBuf) {
        LOGE("eglGetNativeClientBufferANDROID failed");
        AHardwareBuffer_release(tex->ahb);
        tex->ahb = nullptr;
        return false;
    }

    EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    tex->eglImage = fnCreateImg(ctx->eglDisplay, EGL_NO_CONTEXT,
                                 EGL_NATIVE_BUFFER_ANDROID, clientBuf, attrs);
    if (tex->eglImage == EGL_NO_IMAGE_KHR) {
        LOGE("eglCreateImageKHR failed");
        AHardwareBuffer_release(tex->ahb);
        tex->ahb = nullptr;
        return false;
    }

    // ── 3. Create GLES texture from EGL image ──
    glGenTextures(1, &tex->glTexture);
    glBindTexture(GL_TEXTURE_2D, tex->glTexture);
    ctx->glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, tex->eglImage);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    // ── 4. Import AHB into Vulkan ──
    VkExternalMemoryImageCreateInfo extInfo = {};
    extInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo imgInfo = {};
    imgInfo.sType       = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.pNext       = &extInfo;
    imgInfo.imageType   = VK_IMAGE_TYPE_2D;
    imgInfo.format      = vkFormat;
    imgInfo.extent      = { (uint32_t)width, (uint32_t)height, 1 };
    imgInfo.mipLevels   = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples     = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling      = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage       = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_GENERAL;

    if (vkCreateImage(ctx->device, &imgInfo, nullptr, &tex->vkImage) != VK_SUCCESS) {
        LOGE("vkCreateImage (AHB) failed");
        return false;
    }

    // Get AHB memory properties
    VkAndroidHardwareBufferPropertiesANDROID ahbProps = {};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    ctx->vkGetAndroidHardwareBufferPropertiesANDROID(ctx->device, tex->ahb, &ahbProps);

    // Import AHB memory
    VkImportAndroidHardwareBufferInfoANDROID importInfo = {};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = tex->ahb;

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.pNext           = &importInfo;
    allocInfo.allocationSize  = ahbProps.allocationSize;
    allocInfo.memoryTypeIndex = findMemoryType(ctx->physicalDevice,
                                               ahbProps.memoryTypeBits,
                                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (allocInfo.memoryTypeIndex == UINT32_MAX) {
        // Fallback: just find any compatible type
        allocInfo.memoryTypeIndex = __builtin_ctz(ahbProps.memoryTypeBits);
    }

    if (vkAllocateMemory(ctx->device, &allocInfo, nullptr, &tex->vkMemory) != VK_SUCCESS) {
        LOGE("vkAllocateMemory (AHB) failed");
        vkDestroyImage(ctx->device, tex->vkImage, nullptr);
        tex->vkImage = VK_NULL_HANDLE;
        return false;
    }
    vkBindImageMemory(ctx->device, tex->vkImage, tex->vkMemory, 0);

    // Create view
    VkImageViewCreateInfo vi = {};
    vi.sType      = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image      = tex->vkImage;
    vi.viewType   = VK_IMAGE_VIEW_TYPE_2D;
    vi.format      = vkFormat;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount  = 1;
    vkCreateImageView(ctx->device, &vi, nullptr, &tex->vkView);

    LOGI("AHB texture created: %dx%d, GL=%u", width, height, tex->glTexture);
    return true;
}

void disVulkanDestroyAhbTexture(DisVulkanContext* ctx, AhbTexture* tex) {
    if (tex->vkView)   vkDestroyImageView(tex->vkDevice, tex->vkView, nullptr);
    if (tex->vkImage)  vkDestroyImage(tex->vkDevice, tex->vkImage, nullptr);
    if (tex->vkMemory) vkFreeMemory(tex->vkDevice, tex->vkMemory, nullptr);

    auto fnDestroyImg = (FnEglDestroyImageKHR)ctx->eglDestroyImageKHR;
    if (tex->eglImage != EGL_NO_IMAGE_KHR && fnDestroyImg) {
        fnDestroyImg(ctx->eglDisplay, tex->eglImage);
    }
    if (tex->glTexture) {
        glDeleteTextures(1, &tex->glTexture);
    }
    if (tex->ahb) {
        AHardwareBuffer_release(tex->ahb);
    }

    tex->vkImage  = VK_NULL_HANDLE;
    tex->vkMemory = VK_NULL_HANDLE;
    tex->vkView   = VK_NULL_HANDLE;
    tex->eglImage = EGL_NO_IMAGE_KHR;
    tex->glTexture = 0;
    tex->ahb       = nullptr;
}

GLuint disVulkanGetGlTexture(const AhbTexture* tex) {
    return tex->glTexture;
}

// ════════════════════════════════════════════════════════════
//  Descriptor set helpers
// ════════════════════════════════════════════════════════════

static VkDescriptorSet allocSet(DisVulkanContext* ctx, VkDescriptorSetLayout dsl) {
    VkDescriptorSetAllocateInfo ai = {};
    ai.sType              = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool     = ctx->descPool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts        = &dsl;
    VkDescriptorSet ds;
    if (vkAllocateDescriptorSets(ctx->device, &ai, &ds) != VK_SUCCESS) {
        LOGE("vkAllocateDescriptorSets failed");
        return VK_NULL_HANDLE;
    }
    return ds;
}

// Write one sampler binding and one storage binding into a dslMain set
static void writeSetMain(DisVulkanContext* ctx, VkDescriptorSet ds,
                         VkImageView* samplers, int nSamplers,
                         VkImageView storage) {
    std::vector<VkWriteDescriptorSet> writes;
    VkDescriptorImageInfo sInfo[5];
    for (int i = 0; i < nSamplers && i < 5; i++) {
        sInfo[i] = {};
        sInfo[i].sampler     = ctx->sampler;
        sInfo[i].imageView   = samplers[i];
        sInfo[i].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkWriteDescriptorSet w = {};
        w.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w.dstSet          = ds;
        w.dstBinding      = i;
        w.descriptorCount = 1;
        w.descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        w.pImageInfo      = &sInfo[i];
        writes.push_back(w);
    }
    VkDescriptorImageInfo stInfo = {};
    stInfo.imageView   = storage;
    stInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet w = {};
    w.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w.dstSet          = ds;
    w.dstBinding      = 5;
    w.descriptorCount = 1;
    w.descriptorType  = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    w.pImageInfo      = &stInfo;
    writes.push_back(w);

    vkUpdateDescriptorSets(ctx->device, (uint32_t)writes.size(), writes.data(), 0, nullptr);
}

// Write a dslVR set: up to 4 samplers + 2 storage (bindings 8,9)
static void writeSetVR(DisVulkanContext* ctx, VkDescriptorSet ds,
                       VkImageView* samplers, int nSamplers,
                       VkImageView st8, VkImageView st9) {
    std::vector<VkWriteDescriptorSet> writes;
    VkDescriptorImageInfo sInfo[4];
    for (int i = 0; i < nSamplers && i < 4; i++) {
        sInfo[i] = {};
        sInfo[i].sampler     = ctx->sampler;
        sInfo[i].imageView   = samplers[i];
        sInfo[i].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkWriteDescriptorSet w = {};
        w.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w.dstSet          = ds;
        w.dstBinding      = i;
        w.descriptorCount = 1;
        w.descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        w.pImageInfo      = &sInfo[i];
        writes.push_back(w);
    }
    VkDescriptorImageInfo st8Info = {};
    st8Info.imageView   = st8;
    st8Info.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet w8 = {};
    w8.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w8.dstSet          = ds;
    w8.dstBinding      = 8;
    w8.descriptorCount = 1;
    w8.descriptorType  = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    w8.pImageInfo      = &st8Info;
    writes.push_back(w8);

    if (st9) {
        VkDescriptorImageInfo st9Info = {};
        st9Info.imageView   = st9;
        st9Info.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkWriteDescriptorSet w9 = {};
        w9.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w9.dstSet          = ds;
        w9.dstBinding      = 9;
        w9.descriptorCount = 1;
        w9.descriptorType  = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        w9.pImageInfo      = &st9Info;
        writes.push_back(w9);
    }
    vkUpdateDescriptorSets(ctx->device, (uint32_t)writes.size(), writes.data(), 0, nullptr);
}

// ════════════════════════════════════════════════════════════
//  Internal resource allocation
// ════════════════════════════════════════════════════════════

static void allocInternalResources(DisVulkanContext* ctx, int dw, int dh) {
    // Pyramid level sizes: 0=full, 1=half, 2=quarter, 3=eighth
    int lw[DIS_PYRAMID_LEVELS], lh[DIS_PYRAMID_LEVELS];
    lw[0] = dw;  lh[0] = dh;
    for (int i = 1; i < DIS_PYRAMID_LEVELS; i++) {
        lw[i] = std::max(1, lw[i-1] / 2);
        lh[i] = std::max(1, lh[i-1] / 2);
    }

    VkImageUsageFlags usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;

    // Color downscale (DIS resolution, RGBA8)
    createImage(ctx, &ctx->colorPrevDown, dw, dh, VK_FORMAT_R8G8B8A8_UNORM, usage);
    createImage(ctx, &ctx->colorCurrDown, dw, dh, VK_FORMAT_R8G8B8A8_UNORM, usage);

    // Luma pyramid (R32F)
    for (int i = 0; i < DIS_PYRAMID_LEVELS; i++) {
        createImage(ctx, &ctx->lumaPrev[i], lw[i], lh[i], VK_FORMAT_R32_SFLOAT, usage);
        createImage(ctx, &ctx->lumaCurr[i], lw[i], lh[i], VK_FORMAT_R32_SFLOAT, usage);
    }

    // Gradient pyramid (RG32F, prev only)
    for (int i = 0; i < DIS_PYRAMID_LEVELS; i++) {
        createImage(ctx, &ctx->gradPrev[i], lw[i], lh[i], VK_FORMAT_R32G32_SFLOAT, usage);
    }

    // Sparse flow per level (RGBA32F)
    // Sparse resolution is luma_res / 3
    for (int i = 0; i < DIS_PYRAMID_LEVELS; i++) {
        int sw = std::max(1, lw[i] / 3);
        int sh = std::max(1, lh[i] / 3);
        createImage(ctx, &ctx->sparseFlow[i], sw, sh, VK_FORMAT_R32G32B32A32_SFLOAT, usage);
    }

    // Propagation ping-pong (level 0 sparse res)
    int psw = std::max(1, lw[0] / 3);
    int psh = std::max(1, lh[0] / 3);
    createImage(ctx, &ctx->propA, psw, psh, VK_FORMAT_R32G32B32A32_SFLOAT, usage);
    createImage(ctx, &ctx->propB, psw, psh, VK_FORMAT_R32G32B32A32_SFLOAT, usage);

    // Dense flow (RG32F, DIS resolution)
    createImage(ctx, &ctx->denseFlow, dw, dh, VK_FORMAT_R32G32_SFLOAT, usage);

    // VR resources
    createImage(ctx, &ctx->vrPrep,        dw, dh, VK_FORMAT_R32G32_SFLOAT,        usage);
    createImage(ctx, &ctx->vrDw,          dw, dh, VK_FORMAT_R32G32_SFLOAT,        usage);
    createImage(ctx, &ctx->vrDwTmp,       dw, dh, VK_FORMAT_R32G32_SFLOAT,        usage);
    createImage(ctx, &ctx->vrD1,          dw, dh, VK_FORMAT_R32G32B32A32_SFLOAT,  usage);
    createImage(ctx, &ctx->vrD2,          dw, dh, VK_FORMAT_R32G32B32A32_SFLOAT,  usage);
    createImage(ctx, &ctx->vrWt,          dw, dh, VK_FORMAT_R32_SFLOAT,            usage);
    createImage(ctx, &ctx->vrA,           dw, dh, VK_FORMAT_R32G32B32A32_SFLOAT,  usage);
    createImage(ctx, &ctx->vrB,           dw, dh, VK_FORMAT_R32G32_SFLOAT,        usage);
    createImage(ctx, &ctx->vrFlowRefined, dw, dh, VK_FORMAT_R32G32_SFLOAT,        usage);

    ctx->resourcesAlloced = true;
    ctx->allocDisW = dw;
    ctx->allocDisH = dh;
    LOGI("Internal resources allocated for %dx%d", dw, dh);
}

static void freeInternalResources(DisVulkanContext* ctx) {
    destroyImage(ctx, &ctx->colorPrevDown);
    destroyImage(ctx, &ctx->colorCurrDown);
    for (int i = 0; i < DIS_PYRAMID_LEVELS; i++) {
        destroyImage(ctx, &ctx->lumaPrev[i]);
        destroyImage(ctx, &ctx->lumaCurr[i]);
        destroyImage(ctx, &ctx->gradPrev[i]);
        destroyImage(ctx, &ctx->sparseFlow[i]);
    }
    destroyImage(ctx, &ctx->propA);
    destroyImage(ctx, &ctx->propB);
    destroyImage(ctx, &ctx->denseFlow);
    destroyImage(ctx, &ctx->vrPrep);
    destroyImage(ctx, &ctx->vrDw);
    destroyImage(ctx, &ctx->vrDwTmp);
    destroyImage(ctx, &ctx->vrD1);
    destroyImage(ctx, &ctx->vrD2);
    destroyImage(ctx, &ctx->vrWt);
    destroyImage(ctx, &ctx->vrA);
    destroyImage(ctx, &ctx->vrB);
    destroyImage(ctx, &ctx->vrFlowRefined);

    // Free descriptor sets
    if (ctx->descPool) {
        vkResetDescriptorPool(ctx->device, ctx->descPool, 0);
    }
    ctx->dsMain.clear();
    ctx->dsVR.clear();
    ctx->dsVRCoef = VK_NULL_HANDLE;

    ctx->resourcesAlloced = false;
}

// ════════════════════════════════════════════════════════════
//  Image layout transition helper
// ════════════════════════════════════════════════════════════

static void transitionImage(VkCommandBuffer cmd, VkImage img,
                            VkImageLayout oldLayout, VkImageLayout newLayout) {
    VkImageMemoryBarrier b = {};
    b.sType               = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout           = oldLayout;
    b.newLayout           = newLayout;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image               = img;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.layerCount = 1;

    VkPipelineStageFlags srcStage, dstStage;
    VkAccessFlags srcAccess, dstAccess;

    if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED &&
        newLayout == VK_IMAGE_LAYOUT_GENERAL) {
        srcStage  = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        dstStage  = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        srcAccess = 0;
        dstAccess = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    } else if (oldLayout == VK_IMAGE_LAYOUT_GENERAL &&
               newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        srcStage  = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        dstStage  = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        srcAccess = VK_ACCESS_SHADER_WRITE_BIT;
        dstAccess = VK_ACCESS_SHADER_READ_BIT;
    } else if (oldLayout == VK_IMAGE_LAYOUT_GENERAL &&
               newLayout == VK_IMAGE_LAYOUT_GENERAL) {
        srcStage  = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        dstStage  = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        srcAccess = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        dstAccess = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    } else {
        srcStage  = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        dstStage  = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        srcAccess = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        dstAccess = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    }

    b.srcAccessMask = srcAccess;
    b.dstAccessMask = dstAccess;

    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
}

// Copy the selected internal texture in flowAhb so that GLES can display it.
static bool disDebugCopyStage(DisVulkanContext* ctx,
                              AhbTexture* flowAhb,
                              VkImageView srcView,
                              int srcW, int srcH,
                              int mode, float scale) {
    VkDescriptorSet ds = allocSet(ctx, ctx->dslMain);
    if (ds == VK_NULL_HANDLE) return false;

    VkImageView samplers[1] = { srcView };
    writeSetMain(ctx, ds, samplers, 1, flowAhb->vkView);

    VkCommandBuffer cmd = ctx->frameCmd;
    vkResetCommandBuffer(cmd, 0);

    VkCommandBufferBeginInfo begInfo = {};
    begInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &begInfo);

    transitionImage(cmd, flowAhb->vkImage, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL);

    struct { float scale; float offset; int mode; int _pad; } pc = { scale, 0.0f, mode, 0 };
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeDebugCopy);
    vkCmdPushConstants(cmd, ctx->plMain, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pc), &pc);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ds, 0, nullptr);

    int ow = flowAhb->width;
    int oh = flowAhb->height;
    vkCmdDispatch(cmd, (uint32_t)((ow + 7) / 8), (uint32_t)((oh + 7) / 8), 1);

    VkMemoryBarrier mb = {};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    vkCmdPipelineBarrier(cmd,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                         0, 1, &mb, 0, nullptr, 0, nullptr);

    vkEndCommandBuffer(cmd);

    VkSubmitInfo si = {};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    vkResetFences(ctx->device, 1, &ctx->frameFence);
    vkQueueSubmit(ctx->queue, 1, &si, ctx->frameFence);
    vkWaitForFences(ctx->device, 1, &ctx->frameFence, VK_TRUE, UINT64_MAX);
    vkResetDescriptorPool(ctx->device, ctx->descPool, 0);
    return true;
}

void disVulkanSetDebugStage(DisVulkanContext* ctx, int stage) {
    ctx->debugStage = stage;
}

// ════════════════════════════════════════════════════════════
//  Memory barrier between compute dispatches
// ════════════════════════════════════════════════════════════
static void computeBarrier(VkCommandBuffer cmd) {
    VkMemoryBarrier mb = {};
    mb.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 1, &mb, 0, nullptr, 0, nullptr);
}

// ════════════════════════════════════════════════════════════
//  computeFlow
// ════════════════════════════════════════════════════════════

bool disVulkanComputeFlow(DisVulkanContext* ctx,
                          AhbTexture* prevAhb, AhbTexture* currAhb,
                          AhbTexture* flowAhb,
                          int disWidth, int disHeight,
                          bool useVR) {
    if (!ctx->initialized) return false;

    // Allocate / reallocate internal resources if size changed
    if (!ctx->resourcesAlloced ||
        ctx->allocDisW != disWidth || ctx->allocDisH != disHeight) {
        if (ctx->resourcesAlloced) freeInternalResources(ctx);
        allocInternalResources(ctx, disWidth, disHeight);
    }

    // Pyramid sizes
    int lw[DIS_PYRAMID_LEVELS], lh[DIS_PYRAMID_LEVELS];
    lw[0] = disWidth;  lh[0] = disHeight;
    for (int i = 1; i < DIS_PYRAMID_LEVELS; i++) {
        lw[i] = std::max(1, lw[i-1] / 2);
        lh[i] = std::max(1, lh[i-1] / 2);
    }

    // ── Allocate descriptor sets ──
    // We need: 2 downscale + 2 luma + 6 luma_downscale + 4 gradient
    //        + 4 inverse_search + 4 propagate + 1 densify + 1 flow_to_ahb = 24 (dslMain)
    // VR: 1 prep + 1 d1 + 1 d2 + 1 w + 1 coef + 2 sor + 1 add = 8 (dslVR/dslVRCoef)
    int nMain = 24;
    int nVR = useVR ? 7 : 0;
    int nVRCoef = useVR ? 1 : 0;

    ctx->dsMain.resize(nMain);
    for (int i = 0; i < nMain; i++)
        ctx->dsMain[i] = allocSet(ctx, ctx->dslMain);

    if (useVR) {
        ctx->dsVR.resize(nVR);
        for (int i = 0; i < nVR; i++)
            ctx->dsVR[i] = allocSet(ctx, ctx->dslVR);
        ctx->dsVRCoef = allocSet(ctx, ctx->dslVRCoef);
    }

    int dsIdx = 0;

    // ── Update descriptor sets ──

    // 0,1: Downscale prev/curr color (AHB → DIS-res RGBA8)
    {
        VkImageView s[1] = { prevAhb->vkView };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 1, ctx->colorPrevDown.view);
    }
    {
        VkImageView s[1] = { currAhb->vkView };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 1, ctx->colorCurrDown.view);
    }

    // 2,3: Luma from color (DIS-res → R32F)
    {
        VkImageView s[1] = { ctx->colorPrevDown.view };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 1, ctx->lumaPrev[0].view);
    }
    {
        VkImageView s[1] = { ctx->colorCurrDown.view };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 1, ctx->lumaCurr[0].view);
    }

    // 4-6: Downscale luma for levels 1-3 (prev + curr)
    // 7-9: Gradient from luma for levels 0-3 (prev)
    // Actually: 6 downscale (3 prev + 3 curr) + 4 gradient = 10
    for (int i = 1; i < DIS_PYRAMID_LEVELS; i++) {
        VkImageView sp[1] = { ctx->lumaPrev[i-1].view };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], sp, 1, ctx->lumaPrev[i].view);
    }
    for (int i = 1; i < DIS_PYRAMID_LEVELS; i++) {
        VkImageView sc[1] = { ctx->lumaCurr[i-1].view };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], sc, 1, ctx->lumaCurr[i].view);
    }

    // Gradient for all 4 levels (from luma)
    for (int i = 0; i < DIS_PYRAMID_LEVELS; i++) {
        VkImageView s[1] = { ctx->lumaPrev[i].view };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 1, ctx->gradPrev[i].view);
    }

    // Inverse search: coarse → fine (levels 3,2,1,0)
    for (int lvl = DIS_PYRAMID_LEVELS - 1; lvl >= 0; lvl--) {
        VkImageView s[5];
        s[0] = ctx->lumaPrev[lvl].view;
        s[1] = ctx->lumaCurr[lvl].view;
        s[2] = ctx->gradPrev[lvl].view;
        // binding 3: flowMap (coarse result, or dummy for coarsest)
        if (lvl < DIS_PYRAMID_LEVELS - 1) {
            s[3] = ctx->sparseFlow[lvl + 1].view; // coarser level result
        } else {
            s[3] = ctx->sparseFlow[lvl].view; // self (will be zero)
        }
        // binding 4: lastFlowMap (previous frame flow, or dummy)
        s[4] = ctx->sparseFlow[lvl].view; // placeholder
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 5, ctx->sparseFlow[lvl].view);
    }

    // Propagation: 4 passes, ping-pong A↔B
    // pass 0: flowIn=sparseFlow[0], flowOut=propA
    // pass 1: flowIn=propA, flowOut=propB
    // pass 2: flowIn=propB, flowOut=propA
    // pass 3: flowIn=propA, flowOut=propB
    {
        VkImageView s[3] = { ctx->lumaPrev[0].view, ctx->lumaCurr[0].view, ctx->sparseFlow[0].view };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 3, ctx->propA.view);
    }
    {
        VkImageView s[3] = { ctx->lumaPrev[0].view, ctx->lumaCurr[0].view, ctx->propA.view };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 3, ctx->propB.view);
    }
    {
        VkImageView s[3] = { ctx->lumaPrev[0].view, ctx->lumaCurr[0].view, ctx->propB.view };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 3, ctx->propA.view);
    }
    {
        VkImageView s[3] = { ctx->lumaPrev[0].view, ctx->lumaCurr[0].view, ctx->propA.view };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 3, ctx->propB.view);
    }

    // Densify: sparseFlow(propB) → denseFlow
    {
        VkImageView s[3] = { ctx->propB.view, ctx->lumaPrev[0].view, ctx->lumaCurr[0].view };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 3, ctx->denseFlow.view);
    }

    // Flow to AHB: denseFlow → flowAhb (RGBA16F)
    {
        VkImageView s[1] = { ctx->denseFlow.view };
        writeSetMain(ctx, ctx->dsMain[dsIdx++], s, 1, flowAhb->vkView);
    }

    // VR descriptor sets
    int vrIdx = 0;
    if (useVR) {
        // vrPrep: prevColor, nextColor, flowDense → prep, dW
        VkImageView s[3] = { ctx->colorPrevDown.view, ctx->colorCurrDown.view, ctx->denseFlow.view };
        writeSetVR(ctx, ctx->dsVR[vrIdx++], s, 3, ctx->vrPrep.view, ctx->vrDw.view);

        // vrD1: prep → d1
        {
            VkImageView s[1] = { ctx->vrPrep.view };
            writeSetVR(ctx, ctx->dsVR[vrIdx++], s, 1, ctx->vrD1.view, VK_NULL_HANDLE);
        }
        // vrD2: d1 → d2
        {
            VkImageView s[1] = { ctx->vrD1.view };
            writeSetVR(ctx, ctx->dsVR[vrIdx++], s, 1, ctx->vrD2.view, VK_NULL_HANDLE);
        }
        // vrW: flowDense, dW → wt
        {
            VkImageView s[2] = { ctx->denseFlow.view, ctx->vrDw.view };
            writeSetVR(ctx, ctx->dsVR[vrIdx++], s, 2, ctx->vrWt.view, VK_NULL_HANDLE);
        }
        // vrCoef: prep, d1, d2, dW, flowDense, wt → A, B
        {
            VkImageView s[6] = { ctx->vrPrep.view, ctx->vrD1.view, ctx->vrD2.view,
                                ctx->vrDw.view, ctx->denseFlow.view, ctx->vrWt.view };
            // dslVRCoef has 6 samplers + 2 storage
            std::vector<VkWriteDescriptorSet> writes;
            VkDescriptorImageInfo sInfo[6];
            for (int i = 0; i < 6; i++) {
                sInfo[i] = {};
                sInfo[i].sampler     = ctx->sampler;
                sInfo[i].imageView   = s[i];
                sInfo[i].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
                VkWriteDescriptorSet w = {};
                w.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                w.dstSet          = ctx->dsVRCoef;
                w.dstBinding      = i;
                w.descriptorCount = 1;
                w.descriptorType  = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                w.pImageInfo      = &sInfo[i];
                writes.push_back(w);
            }
            VkDescriptorImageInfo st8 = {};
            st8.imageView   = ctx->vrA.view;
            st8.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            VkWriteDescriptorSet w8 = {};
            w8.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            w8.dstSet          = ctx->dsVRCoef;
            w8.dstBinding      = 8;
            w8.descriptorCount = 1;
            w8.descriptorType  = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            w8.pImageInfo      = &st8;
            writes.push_back(w8);

            VkDescriptorImageInfo st9 = {};
            st9.imageView   = ctx->vrB.view;
            st9.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            VkWriteDescriptorSet w9 = {};
            w9.sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            w9.dstSet          = ctx->dsVRCoef;
            w9.dstBinding      = 9;
            w9.descriptorCount = 1;
            w9.descriptorType  = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            w9.pImageInfo      = &st9;
            writes.push_back(w9);

            vkUpdateDescriptorSets(ctx->device, (uint32_t)writes.size(), writes.data(), 0, nullptr);
        }
        // vrSor: A, B, wt, dWin → dWout (2 sets for ping-pong)
        {
            VkImageView s[4] = { ctx->vrA.view, ctx->vrB.view, ctx->vrWt.view, ctx->vrDw.view };
            writeSetVR(ctx, ctx->dsVR[vrIdx++], s, 4, ctx->vrDwTmp.view, VK_NULL_HANDLE);
        }
        {
            VkImageView s[4] = { ctx->vrA.view, ctx->vrB.view, ctx->vrWt.view, ctx->vrDwTmp.view };
            writeSetVR(ctx, ctx->dsVR[vrIdx++], s, 4, ctx->vrDw.view, VK_NULL_HANDLE);
        }
        // vrAdd: flowDense, dW → flowRefined
        {
            VkImageView s[2] = { ctx->denseFlow.view, ctx->vrDw.view };
            writeSetVR(ctx, ctx->dsVR[vrIdx++], s, 2, ctx->vrFlowRefined.view, VK_NULL_HANDLE);
        }
        // flow_to_ahb (VR): flowRefined → flowAhb
        // Reuse the last dslMain set... actually we need another one
        // We'll handle this in the command buffer by reusing the flow_to_ahb set
        // but pointing to vrFlowRefined instead of denseFlow
    }

    // ── Record command buffer ──
    VkCommandBuffer cmd = ctx->frameCmd;
    vkResetCommandBuffer(cmd, 0);

    VkCommandBufferBeginInfo begInfo = {};
    begInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &begInfo);

    // Helper: if debugStage matches with specified - copy texture in flowAhb and stop.
    auto debugCheck = [&](int stage, VkImageView view, int w, int h,
                          int mode, float scale) -> bool {
        if (ctx->debugStage != stage) return false;
        vkEndCommandBuffer(cmd);
        VkSubmitInfo si = {};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        vkResetFences(ctx->device, 1, &ctx->frameFence);
        vkQueueSubmit(ctx->queue, 1, &si, ctx->frameFence);
        vkWaitForFences(ctx->device, 1, &ctx->frameFence, VK_TRUE, UINT64_MAX);
        vkDeviceWaitIdle(ctx->device); ////
        vkResetDescriptorPool(ctx->device, ctx->descPool, 0);

        disDebugCopyStage(ctx, flowAhb, view, w, h, mode, scale);
        return true;
    };

    // ── Transition AHB images ──
    // prev/curr: GENERAL→GENERAL (memory barrier only, preserves GLES-written content)
    // flow: UNDEFINED→GENERAL first time, GENERAL→GENERAL after

    // ── AHB acquires: guarantee the visibility of GLES/EGL records for compute ──
    auto acquireAhb = [&](VkImage img) {
        VkImageMemoryBarrier b = {};
        b.sType               = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout           = VK_IMAGE_LAYOUT_GENERAL;
        b.newLayout           = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image               = img;
        b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        b.subresourceRange.levelCount = 1;
        b.subresourceRange.layerCount = 1;
        b.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd,
                             VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &b);
    };
    acquireAhb(prevAhb->vkImage);
    acquireAhb(currAhb->vkImage);

    // ── Transition all internal images to GENERAL ──
    transitionImage(cmd, ctx->colorPrevDown.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    transitionImage(cmd, ctx->colorCurrDown.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    for (int i = 0; i < DIS_PYRAMID_LEVELS; i++) {
        transitionImage(cmd, ctx->lumaPrev[i].image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionImage(cmd, ctx->lumaCurr[i].image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionImage(cmd, ctx->gradPrev[i].image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionImage(cmd, ctx->sparseFlow[i].image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    }
    transitionImage(cmd, ctx->propA.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    transitionImage(cmd, ctx->propB.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    transitionImage(cmd, ctx->denseFlow.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    if (useVR) {
        transitionImage(cmd, ctx->vrPrep.image,        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionImage(cmd, ctx->vrDw.image,          VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionImage(cmd, ctx->vrDwTmp.image,       VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionImage(cmd, ctx->vrD1.image,          VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionImage(cmd, ctx->vrD2.image,          VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionImage(cmd, ctx->vrWt.image,          VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionImage(cmd, ctx->vrA.image,           VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionImage(cmd, ctx->vrB.image,           VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        transitionImage(cmd, ctx->vrFlowRefined.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    }

    dsIdx = 0;

    // ── Dispatch helpers ──
    auto dispatch = [&](int w, int h) {
        vkCmdDispatch(cmd, (uint32_t)((w + 7) / 8), (uint32_t)((h + 7) / 8), 1);
    };

    // 0: Downscale prev color
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeDownscaleRgba8);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ctx->dsMain[dsIdx++], 0, nullptr);
    dispatch(disWidth, disHeight);
    computeBarrier(cmd);
    if (debugCheck(DIS_DEBUG_COLOR_PREV, ctx->colorPrevDown.view, disWidth, disHeight, 0, 1.0f)) return true;

    // 1: Downscale curr color
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ctx->dsMain[dsIdx++], 0, nullptr);
    dispatch(disWidth, disHeight);
    computeBarrier(cmd);
    if (debugCheck(DIS_DEBUG_COLOR_CURR, ctx->colorCurrDown.view, disWidth, disHeight, 0, 1.0f)) return true;

    // 2: Luma prev level 0
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeLumaR32);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ctx->dsMain[dsIdx++], 0, nullptr);
    dispatch(lw[0], lh[0]);
    computeBarrier(cmd);
    if (debugCheck(DIS_DEBUG_LUMA_PREV, ctx->lumaPrev[0].view, lw[0], lh[0], 0, 1.0f)) return true;

    // 3: Luma curr level 0
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ctx->dsMain[dsIdx++], 0, nullptr);
    dispatch(lw[0], lh[0]);
    computeBarrier(cmd);
    if (debugCheck(DIS_DEBUG_LUMA_CURR, ctx->lumaCurr[0].view, lw[0], lh[0], 0, 1.0f)) return true;

    // 4-6: Downscale luma prev (levels 1..3)
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeDownscaleR32);
    for (int i = 1; i < DIS_PYRAMID_LEVELS; i++) {
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ctx->dsMain[dsIdx++], 0, nullptr);
        dispatch(lw[i], lh[i]);
        computeBarrier(cmd);
    }
    if (debugCheck(DIS_DEBUG_LUMA_PREV_L1, ctx->lumaPrev[1].view, lw[1], lh[1], 0, 1.0f)) return true;
    if (debugCheck(DIS_DEBUG_LUMA_PREV_L2, ctx->lumaPrev[2].view, lw[2], lh[2], 0, 1.0f)) return true;
    if (debugCheck(DIS_DEBUG_LUMA_PREV_L3, ctx->lumaPrev[3].view, lw[3], lh[3], 0, 1.0f)) return true;

    // 7-9: Downscale luma curr
    for (int i = 1; i < DIS_PYRAMID_LEVELS; i++) {
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ctx->dsMain[dsIdx++], 0, nullptr);
        dispatch(lw[i], lh[i]);
        computeBarrier(cmd);
    }

    // 10-13: Gradient
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeGradient);
    struct { float lesser; float upper; float normVal; } gradPC = { 3.0f, 10.0f, 1.0f/32.0f };
    for (int i = 0; i < DIS_PYRAMID_LEVELS; i++) {
        vkCmdPushConstants(cmd, ctx->plMain, VK_SHADER_STAGE_COMPUTE_BIT, 0, 12, &gradPC);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ctx->dsMain[dsIdx++], 0, nullptr);
        dispatch(lw[i], lh[i]);
        computeBarrier(cmd);
    }
    if (debugCheck(DIS_DEBUG_GRAD_L0, ctx->gradPrev[0].view, lw[0], lh[0], 0, 1.0f)) return true;
    if (debugCheck(DIS_DEBUG_GRAD_L3, ctx->gradPrev[3].view, lw[3], lh[3], 0, 1.0f)) return true;

    // 14-17: Inverse search
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeInverseSearch);
    for (int lvl = DIS_PYRAMID_LEVELS - 1; lvl >= 0; lvl--) {
        struct { int32_t level; int32_t coarseLevel; } pc2;
        pc2.level = lvl;
        pc2.coarseLevel = DIS_PYRAMID_LEVELS - 1;
        vkCmdPushConstants(cmd, ctx->plMain, VK_SHADER_STAGE_COMPUTE_BIT, 0, 8, &pc2);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ctx->dsMain[dsIdx++], 0, nullptr);
        int sw = std::max(1, lw[lvl] / 3);
        int sh = std::max(1, lh[lvl] / 3);
        dispatch(sw, sh);
        computeBarrier(cmd);
    }
    if (debugCheck(DIS_DEBUG_SPARSE_L0, ctx->sparseFlow[0].view, lw[0]/3, lh[0]/3, 1, 16.0f)) return true;
    if (debugCheck(DIS_DEBUG_SPARSE_L3, ctx->sparseFlow[3].view, lw[3]/3, lh[3]/3, 1, 16.0f)) return true;

    // 18-21: Propagate
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipePropagate);
    int dists[DIS_PROPAGATION_PASSES] = { 8, 4, 2, 1 };
    for (int p = 0; p < DIS_PROPAGATION_PASSES; p++) {
        int32_t dist = dists[p];
        vkCmdPushConstants(cmd, ctx->plMain, VK_SHADER_STAGE_COMPUTE_BIT, 0, 4, &dist);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ctx->dsMain[dsIdx++], 0, nullptr);
        int sw = std::max(1, lw[0] / 3);
        int sh = std::max(1, lh[0] / 3);
        dispatch(sw, sh);
        computeBarrier(cmd);
    }
    if (debugCheck(DIS_DEBUG_PROP_A, ctx->propA.view, lw[0]/3, lh[0]/3, 1, 16.0f)) return true;
    if (debugCheck(DIS_DEBUG_PROP_B, ctx->propB.view, lw[0]/3, lh[0]/3, 1, 16.0f)) return true;

    // 22: Densify
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeDensify);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ctx->dsMain[dsIdx++], 0, nullptr);
    dispatch(disWidth, disHeight);
    computeBarrier(cmd);
    if (debugCheck(DIS_DEBUG_DENSE, ctx->denseFlow.view, disWidth, disHeight, 1, 16.0f)) return true;

    // ── Variational refinement (optional) ──
    if (useVR && ctx->pipeVRPrep) {
        int vIdx = 0;
        // VR prep
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeVRPrep);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plVR, 0, 1, &ctx->dsVR[vIdx++], 0, nullptr);
        dispatch(disWidth, disHeight);

        // VR d1
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeVRD1);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plVR, 0, 1, &ctx->dsVR[vIdx++], 0, nullptr);
        dispatch(disWidth, disHeight);

        // VR d2
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeVRD2);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plVR, 0, 1, &ctx->dsVR[vIdx++], 0, nullptr);
        dispatch(disWidth, disHeight);

        // VR w
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeVRW);
        struct { float alpha2; float eps2; } wPC = { 2.0f, 1e-4f };
        vkCmdPushConstants(cmd, ctx->plVR, VK_SHADER_STAGE_COMPUTE_BIT, 0, 8, &wPC);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plVR, 0, 1, &ctx->dsVR[vIdx++], 0, nullptr);
        dispatch(disWidth, disHeight);

        // VR coef
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeVRCoef);
        struct { float delta2; float gamma2; float zeta2; float eps2; } coefPC;
        coefPC.delta2 = 5.0f; coefPC.gamma2 = 3.0f; coefPC.zeta2 = 0.2f; coefPC.eps2 = 1e-4f;
        vkCmdPushConstants(cmd, ctx->plVRCoef, VK_SHADER_STAGE_COMPUTE_BIT, 0, 16, &coefPC);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plVRCoef, 0, 1, &ctx->dsVRCoef, 0, nullptr);
        dispatch(disWidth, disHeight);

        // VR SOR (50 iterations, red-black, ping-pong)
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeVRSor);
        struct { float omega; int32_t parity; } sorPC;
        sorPC.omega = 1.8f;
        for (int iter = 0; iter < DIS_SOR_ITERATIONS; iter++) {
            sorPC.parity = iter & 1;
            vkCmdPushConstants(cmd, ctx->plVR, VK_SHADER_STAGE_COMPUTE_BIT, 0, 8, &sorPC);
            int setIdx = (iter & 1); // alternate between dsVR[vIdx] and dsVR[vIdx+1]
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plVR, 0, 1, &ctx->dsVR[vIdx + setIdx], 0, nullptr);
            dispatch(disWidth, disHeight);
        }
        vIdx += 2;

        // VR add
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeVRAdd);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plVR, 0, 1, &ctx->dsVR[vIdx++], 0, nullptr);
        dispatch(disWidth, disHeight);

        // Flow to AHB (from vrFlowRefined instead of denseFlow)
        // Need a new descriptor set for this
        VkDescriptorSet dsFinal = allocSet(ctx, ctx->dslMain);
        {
            VkImageView s[1] = { ctx->vrFlowRefined.view };
            writeSetMain(ctx, dsFinal, s, 1, flowAhb->vkView);
        }
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeFlowToAhb);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &dsFinal, 0, nullptr);
        dispatch(disWidth, disHeight);
    } else {
        // 23: Flow to AHB (from denseFlow)
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->pipeFlowToAhb);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ctx->plMain, 0, 1, &ctx->dsMain[dsIdx++], 0, nullptr);
        dispatch(disWidth, disHeight);
    }

    // ── Transition flow AHB to SHADER_READ_ONLY for GLES ──

    // ── Release flowAhb: make compute records visible for GLES ──
    {
        VkImageMemoryBarrier b = {};
        b.sType               = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout           = VK_IMAGE_LAYOUT_GENERAL;
        b.newLayout           = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image               = flowAhb->vkImage;
        b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        b.subresourceRange.levelCount = 1;
        b.subresourceRange.layerCount = 1;
        b.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        vkCmdPipelineBarrier(cmd,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &b);
    }

    vkEndCommandBuffer(cmd);

    // ── Submit & wait (reuse fence + command buffer) ──
    VkSubmitInfo si = {};
    si.sType              = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers    = &cmd;

    vkResetFences(ctx->device, 1, &ctx->frameFence);
    vkQueueSubmit(ctx->queue, 1, &si, ctx->frameFence);
    vkWaitForFences(ctx->device, 1, &ctx->frameFence, VK_TRUE, UINT64_MAX);
    vkDeviceWaitIdle(ctx->device); ////

    // Reset command buffer for next frame
    vkResetCommandBuffer(ctx->frameCmd, 0);

    // Reset descriptor pool for next frame
    vkResetDescriptorPool(ctx->device, ctx->descPool, 0);
    ctx->dsMain.clear();
    ctx->dsVR.clear();
    ctx->dsVRCoef = VK_NULL_HANDLE;

    return true;
}

// ════════════════════════════════════════════════════════════
//  Cleanup
// ════════════════════════════════════════════════════════════

void disVulkanCleanup(DisVulkanContext* ctx) {
    if (!ctx->initialized) return;

    freeInternalResources(ctx);

    // Destroy pipelines
    auto destroyPipe = [&](VkPipeline& p) {
        if (p) vkDestroyPipeline(ctx->device, p, nullptr);
        p = VK_NULL_HANDLE;
    };
    destroyPipe(ctx->pipeDownscaleRgba8);
    destroyPipe(ctx->pipeDownscaleR32);
    destroyPipe(ctx->pipeLumaR32);
    destroyPipe(ctx->pipeGradient);
    destroyPipe(ctx->pipeInverseSearch);
    destroyPipe(ctx->pipePropagate);
    destroyPipe(ctx->pipeDensify);
    destroyPipe(ctx->pipeFlowToAhb);
    destroyPipe(ctx->pipeInterpolate);
    destroyPipe(ctx->pipeVRPrep);
    destroyPipe(ctx->pipeVRD1);
    destroyPipe(ctx->pipeVRD2);
    destroyPipe(ctx->pipeVRW);
    destroyPipe(ctx->pipeVRCoef);
    destroyPipe(ctx->pipeVRSor);
    destroyPipe(ctx->pipeVRAdd);
    destroyPipe(ctx->pipeDebugCopy);

    // Destroy pipeline layouts
    if (ctx->plMain)   vkDestroyPipelineLayout(ctx->device, ctx->plMain, nullptr);
    if (ctx->plVR)     vkDestroyPipelineLayout(ctx->device, ctx->plVR, nullptr);
    if (ctx->plVRCoef) vkDestroyPipelineLayout(ctx->device, ctx->plVRCoef, nullptr);

    // Destroy descriptor set layouts
    if (ctx->dslMain)   vkDestroyDescriptorSetLayout(ctx->device, ctx->dslMain, nullptr);
    if (ctx->dslVR)     vkDestroyDescriptorSetLayout(ctx->device, ctx->dslVR, nullptr);
    if (ctx->dslVRCoef) vkDestroyDescriptorSetLayout(ctx->device, ctx->dslVRCoef, nullptr);

    // Destroy sampler
    if (ctx->sampler) vkDestroySampler(ctx->device, ctx->sampler, nullptr);

    // Destroy descriptor pool
    if (ctx->descPool) vkDestroyDescriptorPool(ctx->device, ctx->descPool, nullptr);

    if (ctx->frameFence) vkDestroyFence(ctx->device, ctx->frameFence, nullptr);

    // Destroy command pool
    if (ctx->cmdPool) vkDestroyCommandPool(ctx->device, ctx->cmdPool, nullptr);

    // Destroy device
    if (ctx->device) vkDestroyDevice(ctx->device, nullptr);

    // Destroy instance
    if (ctx->instance) vkDestroyInstance(ctx->instance, nullptr);

    ctx->initialized = false;
    LOGI("DisVulkan cleaned up");
}
