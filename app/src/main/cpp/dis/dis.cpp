// Glue between the GLES frame-generation effect and the DIS frame generator. See dis.h.

#include "dis.h"

#include <android/log.h>
#include <dlfcn.h>
#include <cstring>

#define LOG_TAG "DisVulkan"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static uint32_t findMemoryType(VkPhysicalDevice dev, uint32_t typeBits, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(dev, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & props) == props) return i;
    }
    return UINT32_MAX;
}

static void destroyDevice(DisVulkanContext* ctx) {
    if (ctx->device) vkDeviceWaitIdle(ctx->device);
    if (ctx->dis) { vkr_dis_destroy(ctx->dis); ctx->dis = nullptr; }
    if (ctx->frameFence) vkDestroyFence(ctx->device, ctx->frameFence, nullptr);
    if (ctx->cmdPool) vkDestroyCommandPool(ctx->device, ctx->cmdPool, nullptr);
    if (ctx->device) vkDestroyDevice(ctx->device, nullptr);
    if (ctx->instance) vkDestroyInstance(ctx->instance, nullptr);
    ctx->frameFence = VK_NULL_HANDLE;
    ctx->frameCmd = VK_NULL_HANDLE;
    ctx->cmdPool = VK_NULL_HANDLE;
    ctx->device = VK_NULL_HANDLE;
    ctx->instance = VK_NULL_HANDLE;
    ctx->frameWidth = ctx->frameHeight = 0;
}

bool disVulkanInit(DisVulkanContext* ctx, EGLDisplay eglDisplay) {
    if (ctx->initialized) return true;

    // DIS calls Vulkan through a dispatch table resolved from the loader, and this file goes
    // through the same table (vk_dispatch.h maps the vk* names onto it).
    void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib || !vkd_init(lib)) { LOGE("libvulkan.so unavailable"); return false; }

    VkApplicationInfo app = {};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "Winlator DIS";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici = {};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &app;
    if (vkCreateInstance(&ici, nullptr, &ctx->instance) != VK_SUCCESS || !vkd_load_instance(ctx->instance)) {
        LOGE("vkCreateInstance failed");
        destroyDevice(ctx);
        return false;
    }

    uint32_t gpuCount = 1;
    if (vkEnumeratePhysicalDevices(ctx->instance, &gpuCount, &ctx->physicalDevice) < 0 || gpuCount == 0) {
        LOGE("No GPUs");
        destroyDevice(ctx);
        return false;
    }

    // Graphics as well as compute: DIS copies and scales frames with vkCmdBlitImage.
    uint32_t qfCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(ctx->physicalDevice, &qfCount, nullptr);
    VkQueueFamilyProperties qf[16];
    if (qfCount > 16) qfCount = 16;
    vkGetPhysicalDeviceQueueFamilyProperties(ctx->physicalDevice, &qfCount, qf);
    const VkQueueFlags need = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
    for (uint32_t i = 0; i < qfCount; i++) {
        if ((qf[i].queueFlags & need) == need) { ctx->queueFamily = (int)i; break; }
    }
    if (ctx->queueFamily < 0) { LOGE("No graphics+compute queue"); destroyDevice(ctx); return false; }

    // The flow images are RG32F / RG16F storage images.
    VkPhysicalDeviceFeatures2 have = {};
    have.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    vkGetPhysicalDeviceFeatures2(ctx->physicalDevice, &have);
    if (!have.features.shaderStorageImageExtendedFormats) {
        LOGE("shaderStorageImageExtendedFormats unsupported");
        destroyDevice(ctx);
        return false;
    }
    VkPhysicalDeviceFeatures enable = {};
    enable.shaderStorageImageExtendedFormats = VK_TRUE;

    const char* devExt[] = {
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
        VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
        VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
    };

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
    dci.enabledExtensionCount   = sizeof(devExt) / sizeof(devExt[0]);
    dci.ppEnabledExtensionNames = devExt;
    dci.pEnabledFeatures        = &enable;
    if (vkCreateDevice(ctx->physicalDevice, &dci, nullptr, &ctx->device) != VK_SUCCESS) {
        LOGE("vkCreateDevice failed");
        destroyDevice(ctx);
        return false;
    }
    vkGetDeviceQueue(ctx->device, (uint32_t)ctx->queueFamily, 0, &ctx->queue);

    ctx->eglDisplay = eglDisplay;
    ctx->eglCreateImageKHR = (PFN_eglCreateImageKHR)eglGetProcAddress("eglCreateImageKHR");
    ctx->eglDestroyImageKHR = (PFN_eglDestroyImageKHR)eglGetProcAddress("eglDestroyImageKHR");
    ctx->eglGetNativeClientBufferANDROID =
        (PFN_eglGetNativeClientBufferANDROID)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    ctx->glEGLImageTargetTexture2DOES =
        (PFN_glEGLImageTargetTexture2DOES)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    if (!ctx->eglCreateImageKHR || !ctx->eglGetNativeClientBufferANDROID ||
        !ctx->glEGLImageTargetTexture2DOES || !vkd.GetAndroidHardwareBufferPropertiesANDROID) {
        LOGE("EGL / Vulkan Android native buffer entry points not available");
        destroyDevice(ctx);
        return false;
    }

    VkCommandPoolCreateInfo pci = {};
    pci.sType            = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pci.flags            = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = (uint32_t)ctx->queueFamily;
    VkFenceCreateInfo fci = {};
    fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    VkCommandBufferAllocateInfo cbi = {};
    cbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbi.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbi.commandBufferCount = 1;
    if (vkCreateCommandPool(ctx->device, &pci, nullptr, &ctx->cmdPool) != VK_SUCCESS ||
        vkCreateFence(ctx->device, &fci, nullptr, &ctx->frameFence) != VK_SUCCESS ||
        (cbi.commandPool = ctx->cmdPool,
         vkAllocateCommandBuffers(ctx->device, &cbi, &ctx->frameCmd) != VK_SUCCESS)) {
        LOGE("Command pool / fence / command buffer creation failed");
        destroyDevice(ctx);
        return false;
    }

    ctx->dis = vkr_dis_create(ctx->device, ctx->physicalDevice);
    if (!ctx->dis) {
        LOGE("DIS frame generator unavailable on this device");
        destroyDevice(ctx);
        return false;
    }
    vkr_dis_set_debug_flow(ctx->dis, ctx->debugFlow);

    ctx->initialized = true;
    LOGI("DisVulkan initialized");
    return true;
}

void disVulkanCleanup(DisVulkanContext* ctx) {
    destroyDevice(ctx);
    ctx->initialized = false;
}

// ════════════════════════════════════════════════════════════
//  AHB texture creation / destruction
// ════════════════════════════════════════════════════════════

static uint32_t vkToAhbFormat(VkFormat fmt) {
    switch (fmt) {
        case VK_FORMAT_R16G16B16A16_SFLOAT: return AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
        case VK_FORMAT_R8G8B8A8_UNORM:
        default:                            return AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    }
}

bool disVulkanCreateAhbTexture(DisVulkanContext* ctx, AhbTexture* tex,
                               int width, int height, VkFormat vkFormat) {
    if (!ctx->initialized) return false;
    tex->vkDevice = ctx->device;
    tex->width    = width;
    tex->height   = height;
    tex->format   = vkFormat;

    AHardwareBuffer_Desc desc = {};
    desc.width  = (uint32_t)width;
    desc.height = (uint32_t)height;
    desc.layers = 1;
    desc.format = vkToAhbFormat(vkFormat);
    desc.usage  = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (AHardwareBuffer_allocate(&desc, &tex->ahb) != 0) {
        LOGE("AHardwareBuffer_allocate failed %dx%d", width, height);
        return false;
    }

    // GLES side: EGL image -> texture.
    EGLClientBuffer clientBuf = ctx->eglGetNativeClientBufferANDROID(tex->ahb);
    EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    tex->eglImage = clientBuf ? ctx->eglCreateImageKHR(ctx->eglDisplay, EGL_NO_CONTEXT,
                                                       EGL_NATIVE_BUFFER_ANDROID, clientBuf, attrs)
                              : EGL_NO_IMAGE_KHR;
    if (tex->eglImage == EGL_NO_IMAGE_KHR) {
        LOGE("eglCreateImageKHR failed");
        disVulkanDestroyAhbTexture(ctx, tex);
        return false;
    }
    glGenTextures(1, &tex->glTexture);
    glBindTexture(GL_TEXTURE_2D, tex->glTexture);
    ctx->glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, tex->eglImage);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    // Vulkan side. Transfer usage because DIS copies real frames out of these images with a
    // blit and blits generated frames into them. Created in GENERAL, as every DIS access expects;
    // GLES writes through the same memory and the fence waits keep the two apart.
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
    imgInfo.usage       = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                          VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_GENERAL;
    if (vkCreateImage(ctx->device, &imgInfo, nullptr, &tex->vkImage) != VK_SUCCESS) {
        LOGE("vkCreateImage (AHB) failed");
        disVulkanDestroyAhbTexture(ctx, tex);
        return false;
    }

    VkAndroidHardwareBufferPropertiesANDROID ahbProps = {};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    vkGetAndroidHardwareBufferPropertiesANDROID(ctx->device, tex->ahb, &ahbProps);

    VkImportAndroidHardwareBufferInfoANDROID importInfo = {};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = tex->ahb;
    VkMemoryDedicatedAllocateInfo dedicated = {};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.pNext = &importInfo;
    dedicated.image = tex->vkImage;

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.pNext           = &dedicated;
    allocInfo.allocationSize  = ahbProps.allocationSize;
    allocInfo.memoryTypeIndex = findMemoryType(ctx->physicalDevice, ahbProps.memoryTypeBits,
                                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (allocInfo.memoryTypeIndex == UINT32_MAX && ahbProps.memoryTypeBits) {
        allocInfo.memoryTypeIndex = (uint32_t)__builtin_ctz(ahbProps.memoryTypeBits);
    }
    if (vkAllocateMemory(ctx->device, &allocInfo, nullptr, &tex->vkMemory) != VK_SUCCESS ||
        vkBindImageMemory(ctx->device, tex->vkImage, tex->vkMemory, 0) != VK_SUCCESS) {
        LOGE("AHB memory import failed");
        disVulkanDestroyAhbTexture(ctx, tex);
        return false;
    }

    VkImageViewCreateInfo vi = {};
    vi.sType      = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image      = tex->vkImage;
    vi.viewType   = VK_IMAGE_VIEW_TYPE_2D;
    vi.format     = vkFormat;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount = 1;
    vkCreateImageView(ctx->device, &vi, nullptr, &tex->vkView);

    LOGI("AHB texture created: %dx%d, GL=%u", width, height, tex->glTexture);
    return true;
}

void disVulkanDestroyAhbTexture(DisVulkanContext* ctx, AhbTexture* tex) {
    if (tex->vkDevice) vkDeviceWaitIdle(tex->vkDevice);
    if (tex->vkView)   vkDestroyImageView(tex->vkDevice, tex->vkView, nullptr);
    if (tex->vkImage)  vkDestroyImage(tex->vkDevice, tex->vkImage, nullptr);
    if (tex->vkMemory) vkFreeMemory(tex->vkDevice, tex->vkMemory, nullptr);
    if (tex->eglImage != EGL_NO_IMAGE_KHR && ctx->eglDestroyImageKHR) {
        ctx->eglDestroyImageKHR(ctx->eglDisplay, tex->eglImage);
    }
    if (tex->glTexture) glDeleteTextures(1, &tex->glTexture);
    if (tex->ahb) AHardwareBuffer_release(tex->ahb);
    *tex = AhbTexture();
}

GLuint disVulkanGetGlTexture(const AhbTexture* tex) {
    return tex->glTexture;
}

// ════════════════════════════════════════════════════════════
//  Frame generation
// ════════════════════════════════════════════════════════════

void disVulkanSetMinSide(DisVulkanContext* ctx, uint32_t minSide) {
    ctx->minSide = minSide;
}

void disVulkanSetDebugFlow(DisVulkanContext* ctx, bool enabled) {
    ctx->debugFlow = enabled;
    if (ctx->dis) vkr_dis_set_debug_flow(ctx->dis, enabled);
}

static bool beginFrame(DisVulkanContext* ctx) {
    vkResetFences(ctx->device, 1, &ctx->frameFence);
    vkResetCommandBuffer(ctx->frameCmd, 0);
    VkCommandBufferBeginInfo bi = {};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    return vkBeginCommandBuffer(ctx->frameCmd, &bi) == VK_SUCCESS;
}

// Submits and waits: the caller hands the images to GLES right after.
static bool endFrame(DisVulkanContext* ctx, VkCommandBuffer cmd) {
    if (vkEndCommandBuffer(cmd) != VK_SUCCESS) return false;
    VkSubmitInfo si = {};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    if (vkQueueSubmit(ctx->queue, 1, &si, ctx->frameFence) != VK_SUCCESS) return false;
    return vkWaitForFences(ctx->device, 1, &ctx->frameFence, VK_TRUE, UINT64_MAX) == VK_SUCCESS;
}

// VkrDisFlushFn for the optional hardware motion hint: DIS needs the frame's pixels mid-way.
static VkCommandBuffer flushFrame(void* user, VkCommandBuffer cmd) {
    DisVulkanContext* ctx = (DisVulkanContext*)user;
    if (!endFrame(ctx, cmd) || !beginFrame(ctx)) return VK_NULL_HANDLE;
    return ctx->frameCmd;
}

bool disVulkanPushFrame(DisVulkanContext* ctx, AhbTexture* frame, int generations) {
    if (!ctx->initialized || !ctx->dis || !frame || !frame->vkImage) return false;
    if (generations < 1) generations = 1;
    if (generations > (int)VKR_DIS_MAX_GENERATIONS) generations = (int)VKR_DIS_MAX_GENERATIONS;

    const VkrDisContentRect content = {0, 0, (uint32_t)frame->width, (uint32_t)frame->height};
    vkr_dis_configure(ctx->dis, ctx->minSide, 0, 0.0f);
    if (vkr_dis_needs_rebuild(ctx->dis, (uint32_t)frame->width, (uint32_t)frame->height,
                              frame->format, content)) {
        vkDeviceWaitIdle(ctx->device);
        if (!vkr_dis_prepare(ctx->dis, (uint32_t)frame->width, (uint32_t)frame->height,
                             frame->format, content)) {
            LOGE("DIS could not prepare for %dx%d", frame->width, frame->height);
            return false;
        }
        ctx->frameWidth = frame->width;
        ctx->frameHeight = frame->height;
    }

    if (!beginFrame(ctx)) return false;
    VkCommandBuffer cmd = vkr_dis_process_ex(ctx->dis, ctx->frameCmd, frame->vkImage,
                                             (uint32_t)frame->width, (uint32_t)frame->height,
                                             (uint32_t)generations, flushFrame, ctx);
    return cmd != VK_NULL_HANDLE && endFrame(ctx, cmd);
}

bool disVulkanGenerate(DisVulkanContext* ctx, AhbTexture* out, float t) {
    if (!ctx->initialized || !ctx->dis || !out || !out->vkImage) return false;
    if (!beginFrame(ctx)) return false;
    vkr_dis_generate_at(ctx->dis, ctx->frameCmd, t, out->vkImage,
                        (uint32_t)out->width, (uint32_t)out->height);
    return endFrame(ctx, ctx->frameCmd);
}
