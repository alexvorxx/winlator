// Glue between the GLES frame-generation effect and the DIS frame generator. See dis.h.

#include "dis.h"

#include <android/log.h>
#include <dlfcn.h>
#include <time.h>
#include <unistd.h>
#include <cstring>

#define LOG_TAG "DisVulkan"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define DIS_TIMING_LOG_EVERY 240u

static double nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1e3 + (double)ts.tv_nsec * 1e-6;
}

static uint32_t findMemoryType(VkPhysicalDevice dev, uint32_t typeBits, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(dev, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & props) == props) return i;
    }
    return UINT32_MAX;
}

static bool hasDeviceExtension(VkPhysicalDevice dev, const char* name) {
    uint32_t n = 0;
    vkEnumerateDeviceExtensionProperties(dev, nullptr, &n, nullptr);
    if (n == 0) return false;
    VkExtensionProperties* props = new VkExtensionProperties[n];
    vkEnumerateDeviceExtensionProperties(dev, nullptr, &n, props);
    bool found = false;
    for (uint32_t i = 0; i < n && !found; i++) found = strcmp(props[i].extensionName, name) == 0;
    delete[] props;
    return found;
}

static void destroyDevice(DisVulkanContext* ctx) {
    if (ctx->device) vkDeviceWaitIdle(ctx->device);
    if (ctx->dis) { vkr_dis_destroy(ctx->dis); ctx->dis = nullptr; }
    for (DisSubmitSlot& s : ctx->slots) {
        if (s.fence) vkDestroyFence(ctx->device, s.fence, nullptr);
        if (s.waitSem) vkDestroySemaphore(ctx->device, s.waitSem, nullptr);
        if (s.signalSem) vkDestroySemaphore(ctx->device, s.signalSem, nullptr);
        s = DisSubmitSlot();
    }
    if (ctx->cmdPool) vkDestroyCommandPool(ctx->device, ctx->cmdPool, nullptr);
    if (ctx->device) vkDestroyDevice(ctx->device, nullptr);
    if (ctx->instance) vkDestroyInstance(ctx->instance, nullptr);
    ctx->cmdPool = VK_NULL_HANDLE;
    ctx->device = VK_NULL_HANDLE;
    ctx->instance = VK_NULL_HANDLE;
    ctx->frameWidth = ctx->frameHeight = 0;
    ctx->asyncSync = false;
}

// The fence-based GLES <-> Vulkan ordering, when both sides offer it.
static void loadAsyncSync(DisVulkanContext* ctx, bool haveVkFd) {
    const char* eglExt = eglQueryString(ctx->eglDisplay, EGL_EXTENSIONS);
    const bool haveEglFence = eglExt && strstr(eglExt, "EGL_ANDROID_native_fence_sync") &&
                              strstr(eglExt, "EGL_KHR_wait_sync");
    if (!haveVkFd || !haveEglFence) {
        LOGW("GPU-side sync unavailable (vk fd %d, egl fence %d); finishing each side on the CPU",
             haveVkFd ? 1 : 0, haveEglFence ? 1 : 0);
        return;
    }
    ctx->eglCreateSyncKHR = (PFNEGLCREATESYNCKHRPROC)eglGetProcAddress("eglCreateSyncKHR");
    ctx->eglDestroySyncKHR = (PFNEGLDESTROYSYNCKHRPROC)eglGetProcAddress("eglDestroySyncKHR");
    ctx->eglWaitSyncKHR = (PFNEGLWAITSYNCKHRPROC)eglGetProcAddress("eglWaitSyncKHR");
    ctx->eglDupNativeFenceFDANDROID =
        (PFNEGLDUPNATIVEFENCEFDANDROIDPROC)eglGetProcAddress("eglDupNativeFenceFDANDROID");
    ctx->importSemaphoreFd =
        (PFN_vkImportSemaphoreFdKHR)vkGetDeviceProcAddr(ctx->device, "vkImportSemaphoreFdKHR");
    ctx->getSemaphoreFd =
        (PFN_vkGetSemaphoreFdKHR)vkGetDeviceProcAddr(ctx->device, "vkGetSemaphoreFdKHR");
    ctx->asyncSync = ctx->eglCreateSyncKHR && ctx->eglDestroySyncKHR && ctx->eglWaitSyncKHR &&
                     ctx->eglDupNativeFenceFDANDROID && ctx->importSemaphoreFd &&
                     ctx->getSemaphoreFd;
    LOGI("GPU-side GLES <-> Vulkan sync: %s", ctx->asyncSync ? "on" : "off");
}

static bool createSlots(DisVulkanContext* ctx) {
    VkCommandBufferAllocateInfo cbi = {};
    cbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbi.commandPool = ctx->cmdPool;
    cbi.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbi.commandBufferCount = 1;
    VkFenceCreateInfo fci = {};
    fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    VkSemaphoreCreateInfo sci = {};
    sci.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkExportSemaphoreCreateInfo esi = {};
    esi.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    esi.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo exportable = sci;
    exportable.pNext = &esi;
    for (DisSubmitSlot& s : ctx->slots) {
        if (vkAllocateCommandBuffers(ctx->device, &cbi, &s.cmd) != VK_SUCCESS ||
            vkCreateFence(ctx->device, &fci, nullptr, &s.fence) != VK_SUCCESS) {
            return false;
        }
        if (ctx->asyncSync &&
            (vkCreateSemaphore(ctx->device, &sci, nullptr, &s.waitSem) != VK_SUCCESS ||
             vkCreateSemaphore(ctx->device, &exportable, nullptr, &s.signalSem) != VK_SUCCESS)) {
            LOGW("exportable semaphores unavailable; finishing each side on the CPU");
            ctx->asyncSync = false;
        }
    }
    return true;
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

    const char* devExt[8];
    uint32_t devExtCount = 0;
    devExt[devExtCount++] = VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME;
    devExt[devExtCount++] = VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME;
    devExt[devExtCount++] = VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME;
    devExt[devExtCount++] = VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME;
    const bool haveVkFd =
        hasDeviceExtension(ctx->physicalDevice, VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME) &&
        hasDeviceExtension(ctx->physicalDevice, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
    if (haveVkFd) {
        devExt[devExtCount++] = VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME;
        devExt[devExtCount++] = VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
    }

    // A game that keeps the GPU busy would otherwise delay every generated frame behind its own
    // work, so the queue asks for a high global priority where the driver allows it.
    const bool haveGlobalPriority = hasDeviceExtension(ctx->physicalDevice, VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME);
    if (haveGlobalPriority) devExt[devExtCount++] = VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME;

    float queuePriority = 1.0f;
    VkDeviceQueueGlobalPriorityCreateInfoEXT qprio = {};
    qprio.sType          = VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_EXT;
    qprio.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_HIGH_EXT;
    VkDeviceQueueCreateInfo qci = {};
    qci.sType            = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.pNext            = haveGlobalPriority ? &qprio : nullptr;
    qci.queueFamilyIndex = (uint32_t)ctx->queueFamily;
    qci.queueCount       = 1;
    qci.pQueuePriorities = &queuePriority;

    VkDeviceCreateInfo dci = {};
    dci.sType                   = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount    = 1;
    dci.pQueueCreateInfos       = &qci;
    dci.enabledExtensionCount   = devExtCount;
    dci.ppEnabledExtensionNames = devExt;
    dci.pEnabledFeatures        = &enable;
    VkResult created = vkCreateDevice(ctx->physicalDevice, &dci, nullptr, &ctx->device);
    if (created != VK_SUCCESS && haveGlobalPriority) {
        // Not permitted (or not supported for this family): fall back to the default priority.
        // The extension was added last, so dropping the count drops it.
        LOGI("Vulkan queue priority: default (high refused: %d)", (int)created);
        qci.pNext = nullptr;
        dci.enabledExtensionCount = --devExtCount;
        created = vkCreateDevice(ctx->physicalDevice, &dci, nullptr, &ctx->device);
    } else if (created == VK_SUCCESS) {
        LOGI("Vulkan queue priority: %s", haveGlobalPriority ? "high" : "default");
    }
    if (created != VK_SUCCESS) {
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
    loadAsyncSync(ctx, haveVkFd);

    VkCommandPoolCreateInfo pci = {};
    pci.sType            = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pci.flags            = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = (uint32_t)ctx->queueFamily;
    if (vkCreateCommandPool(ctx->device, &pci, nullptr, &ctx->cmdPool) != VK_SUCCESS ||
        !createSlots(ctx)) {
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
    // GLES writes through the same memory and the fences keep the two apart.
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
//  Submission and GLES <-> Vulkan ordering
// ════════════════════════════════════════════════════════════

void disVulkanSetMinSide(DisVulkanContext* ctx, uint32_t minSide) {
    ctx->minSide = minSide;
}

void disVulkanSetDebugFlow(DisVulkanContext* ctx, bool enabled) {
    ctx->debugFlow = enabled;
    if (ctx->dis) vkr_dis_set_debug_flow(ctx->dis, enabled);
}

// Next submission slot, begun. The CPU only waits here, and only if the slot's previous
// submission - DIS_SUBMIT_RING submissions ago - has somehow not finished yet.
static DisSubmitSlot* beginSlot(DisVulkanContext* ctx) {
    DisSubmitSlot* s = &ctx->slots[ctx->nextSlot];
    ctx->nextSlot = (ctx->nextSlot + 1) % DIS_SUBMIT_RING;
    if (s->submitted) {
        vkWaitForFences(ctx->device, 1, &s->fence, VK_TRUE, UINT64_MAX);
        s->submitted = false;
    }
    vkResetFences(ctx->device, 1, &s->fence);
    vkResetCommandBuffer(s->cmd, 0);
    VkCommandBufferBeginInfo bi = {};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    return vkBeginCommandBuffer(s->cmd, &bi) == VK_SUCCESS ? s : nullptr;
}

// A native fence behind every GLES command issued so far, imported as this slot's wait
// semaphore. False means the caller has to fall back to finishing GLES on the CPU.
static bool waitForGles(DisVulkanContext* ctx, DisSubmitSlot* s) {
    const EGLint attrs[] = { EGL_SYNC_NATIVE_FENCE_FD_ANDROID, EGL_NO_NATIVE_FENCE_FD_ANDROID,
                             EGL_NONE };
    EGLSyncKHR sync = ctx->eglCreateSyncKHR(ctx->eglDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
    if (sync == EGL_NO_SYNC_KHR) return false;
    glFlush();  // the fence fd only exists once the fence has been submitted
    int fd = ctx->eglDupNativeFenceFDANDROID(ctx->eglDisplay, sync);
    ctx->eglDestroySyncKHR(ctx->eglDisplay, sync);
    if (fd < 0) return false;

    VkImportSemaphoreFdInfoKHR ii = {};
    ii.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
    ii.semaphore = s->waitSem;
    ii.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
    ii.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    ii.fd = fd;
    if (ctx->importSemaphoreFd(ctx->device, &ii) != VK_SUCCESS) {
        close(fd);  // ownership only passes to Vulkan on success
        return false;
    }
    return true;
}

// Hands the finished submission's completion to GLES: later GLES commands wait for it on the
// GPU. False means the caller has to wait for the submission on the CPU instead.
static bool glesWaitsFor(DisVulkanContext* ctx, DisSubmitSlot* s) {
    VkSemaphoreGetFdInfoKHR gi = {};
    gi.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    gi.semaphore = s->signalSem;
    gi.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    if (ctx->getSemaphoreFd(ctx->device, &gi, &fd) != VK_SUCCESS || fd < 0) return false;
    const EGLint attrs[] = { EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fd, EGL_NONE };
    EGLSyncKHR sync = ctx->eglCreateSyncKHR(ctx->eglDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
    if (sync == EGL_NO_SYNC_KHR) {
        close(fd);  // EGL owns the fd only once the sync exists
        return false;
    }
    ctx->eglWaitSyncKHR(ctx->eglDisplay, sync, 0);
    ctx->eglDestroySyncKHR(ctx->eglDisplay, sync);
    return true;
}

// Ends and submits the slot. With GPU-side sync it waits on GLES before starting (when asked)
// and signals for GLES (when asked); without, GLES has already been finished by the caller and
// the CPU waits for the submission here.
static bool submitSlot(DisVulkanContext* ctx, DisSubmitSlot* s, bool waitGles, bool signalGles) {
    if (vkEndCommandBuffer(s->cmd) != VK_SUCCESS) return false;
    const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo si = {};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &s->cmd;
    if (ctx->asyncSync && waitGles) {
        si.waitSemaphoreCount = 1;
        si.pWaitSemaphores = &s->waitSem;
        si.pWaitDstStageMask = &waitStage;
    }
    if (ctx->asyncSync && signalGles) {
        si.signalSemaphoreCount = 1;
        si.pSignalSemaphores = &s->signalSem;
    }
    if (vkQueueSubmit(ctx->queue, 1, &si, s->fence) != VK_SUCCESS) return false;
    s->submitted = true;

    if (ctx->asyncSync) {
        if (!signalGles || glesWaitsFor(ctx, s)) return true;
        // The signal could not be handed over, so the semaphore may still hold it and cannot be
        // signalled again: stay on CPU-side sync from here on. The result still has to be
        // complete before GLES reads it.
        LOGW("handing the Vulkan signal to GLES failed; finishing each side on the CPU");
        ctx->asyncSync = false;
    }
    const bool ok = vkWaitForFences(ctx->device, 1, &s->fence, VK_TRUE, UINT64_MAX) == VK_SUCCESS;
    s->submitted = false;
    return ok;
}

// Orders the next submission after everything GLES has issued: a GPU-side wait when possible,
// otherwise glFinish on the CPU.
static bool orderAfterGles(DisVulkanContext* ctx, DisSubmitSlot* s) {
    if (ctx->asyncSync && waitForGles(ctx, s)) return true;
    glFinish();
    return false;
}

static void logTiming(DisVulkanContext* ctx) {
    if (ctx->generateCount < DIS_TIMING_LOG_EVERY) return;
    LOGI("CPU time on the GL thread: push %.2f ms/frame (%u), generate %.2f ms/frame (%u), sync %s",
         ctx->pushCount ? ctx->pushMs / ctx->pushCount : 0.0, ctx->pushCount,
         ctx->generateMs / ctx->generateCount, ctx->generateCount,
         ctx->asyncSync ? "GPU-side" : "CPU");
    ctx->pushMs = ctx->generateMs = 0.0;
    ctx->pushCount = ctx->generateCount = 0;
}

bool disVulkanPushFrame(DisVulkanContext* ctx, AhbTexture* frame, int generations) {
    if (!ctx->initialized || !ctx->dis || !frame || !frame->vkImage) return false;
    const double t0 = nowMs();
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

    DisSubmitSlot* s = beginSlot(ctx);
    if (!s) return false;
    // The frame was copied into the AHB by GLES just before this call.
    const bool gpuWait = orderAfterGles(ctx, s);
    vkr_dis_process(ctx->dis, s->cmd, frame->vkImage, (uint32_t)frame->width,
                    (uint32_t)frame->height, (uint32_t)generations);
    // Nothing on the GLES side reads what this produces; the generated frames that do are
    // later submissions on the same queue.
    const bool ok = submitSlot(ctx, s, gpuWait, false);
    ctx->pushMs += nowMs() - t0;
    ctx->pushCount++;
    return ok;
}

bool disVulkanGenerate(DisVulkanContext* ctx, AhbTexture* out, float t) {
    if (!ctx->initialized || !ctx->dis || !out || !out->vkImage) return false;
    const double t0 = nowMs();
    DisSubmitSlot* s = beginSlot(ctx);
    if (!s) return false;
    // GLES may still be drawing the previous generated frame out of this same texture.
    const bool gpuWait = orderAfterGles(ctx, s);
    vkr_dis_generate_at(ctx->dis, s->cmd, t, out->vkImage, (uint32_t)out->width,
                        (uint32_t)out->height);
    const bool ok = submitSlot(ctx, s, gpuWait, true);
    ctx->generateMs += nowMs() - t0;
    ctx->generateCount++;
    logTiming(ctx);
    return ok;
}
