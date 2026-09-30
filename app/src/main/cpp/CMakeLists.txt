cmake_minimum_required(VERSION 3.22.1)

Project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_subdirectory(gladiorenderer)
add_subdirectory(vortekrenderer)
add_subdirectory(libadrenotools)
add_subdirectory(virglrenderer)
add_subdirectory(virgl_test_server)
add_subdirectory(OpenXR-SDK)
add_subdirectory(patchelf)

include_directories(winlator/include
                    dis)

set(GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")

if(NOT EXISTS "${GLSLC_EXECUTABLE}")
    message(FATAL_ERROR "glslc not found at ${GLSLC_EXECUTABLE}. Check NDK installation.")
endif()

# ── Shader source files ──
set(DIS_SHADER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/shaders)
set(DIS_SHADER_BUILD_DIR ${CMAKE_CURRENT_BINARY_DIR}/shaders_spv)

file(MAKE_DIRECTORY ${DIS_SHADER_BUILD_DIR})

# List of all DIS shaders
set(DIS_SHADERS
        dis_downscale_rgba8
        dis_downscale_r32
        dis_luma_r32
        dis_gradient
        dis_inverse_search
        dis_propagate
        dis_densify
        dis_flow_to_ahb
        dis_interpolate
        dis_vr_prep
        dis_vr_d1
        dis_vr_d2
        dis_vr_w
        dis_vr_coef
        dis_vr_sor
        dis_vr_add
        dis_debug_copy
)

# Compile each shader
set(DIS_SPV_FILES)
foreach(SHADER ${DIS_SHADERS})
    set(SRC ${DIS_SHADER_DIR}/${SHADER}.comp)
    set(DST ${DIS_SHADER_BUILD_DIR}/${SHADER}.spv)

    add_custom_command(
            OUTPUT ${DST}
            COMMAND ${GLSLC_EXECUTABLE} -fshader-stage=compute ${SRC} -o ${DST}
            DEPENDS ${SRC}
            COMMENT "Compiling ${SHADER}.comp → ${SHADER}.spv"
            VERBATIM
    )
    list(APPEND DIS_SPV_FILES ${DST})
endforeach()

# Custom target to compile all shaders
add_custom_target(dis_shaders ALL DEPENDS ${DIS_SPV_FILES})

# ── DIS Vulkan library ──
add_library(dis_vulkan STATIC
        dis/dis.cpp
        dis/dis_jni.cpp
)

target_include_directories(dis_vulkan PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(dis_vulkan
        vulkan
        android
        EGL
        GLESv2
        log
)

# Make sure shaders are compiled before the library
add_dependencies(dis_vulkan dis_shaders)

# ── Copy .spv files to assets ──
# After build, copy spv files to assets/shaders/
add_custom_command(TARGET dis_vulkan POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory
        ${CMAKE_SOURCE_DIR}/../../../src/main/assets/shaders
        COMMAND ${CMAKE_COMMAND} -E copy_directory
        ${DIS_SHADER_BUILD_DIR}
        ${CMAKE_SOURCE_DIR}/../../../src/main/assets/shaders
        COMMENT "Copying SPIR-V shaders to assets"
)

add_library(winlator SHARED
            xr/engine.c
            xr/framebuffer.c
            xr/input.c
            xr/main.c
            xr/math.c
            xr/renderer.c
            winlator/arrays.c
            winlator/drawable.c
            winlator/gpu_helper.c
            winlator/gpu_image.c
            winlator/ring_buffer.c
            winlator/sysvshared_memory.c
            winlator/xconnector_epoll.c
            winlator/xinput_stream.c
            winlator/xoutput_stream.c
            winlator/alsa_client.c
            winlator/patchelf_wrapper.cpp
            winlator/qcom.cpp
)

add_dependencies(winlator dis_vulkan)
target_compile_options(winlator PRIVATE -Wall -Wextra)
target_compile_definitions(winlator PRIVATE
        VK_USE_PLATFORM_ANDROID_KHR
)

target_link_libraries(winlator
                      -Wl,--whole-archive
                      dis_vulkan
                      -Wl,--no-whole-archive
                      log
                      android
                      jnigraphics
                      openxr_loader
                      aaudio
                      vulkan
                      EGL
                      GLESv2
                      GLESv3
)