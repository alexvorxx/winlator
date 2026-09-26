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

include_directories(winlator/include)

set(GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")

if(NOT EXISTS "${GLSLC_EXECUTABLE}")
    message(FATAL_ERROR "glslc not found at ${GLSLC_EXECUTABLE}. Check NDK installation.")
endif()

# ── DIS frame generator ──
# The generator lives in dis/ (public header and GLSL shaders under dis/include, implementation
# under dis/src); dis/dis.cpp and dis/dis_jni.cpp connect it to the GLES frame-generation effect
# through AHardwareBuffers. Shaders are compiled to SPIR-V and embedded as C arrays.
set(DIS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/dis)
set(DIS_SHADER_DIR ${DIS_DIR}/include/shaders)
set(DIS_SHADER_OUT_DIR ${CMAKE_CURRENT_BINARY_DIR}/dis_gen/shaders)
file(MAKE_DIRECTORY ${DIS_SHADER_OUT_DIR})

set(DIS_SHADERS
        dis_luma_r16
        dis_luma_r32
        dis_gradient
        dis_inverse_search
        dis_propagate
        dis_densify
        dis_interpolate
        dis_hist
        dis_side
        dis_flow_pack
        dis_me_luma
        dis_vr_prep
        dis_vr_d1
        dis_vr_d2
        dis_vr_w
        dis_vr_coef
        dis_vr_sor
        dis_vr_add
)

set(DIS_SHADER_HEADERS)
foreach(SHADER ${DIS_SHADERS})
    set(VAR ${SHADER}_comp)
    set(SRC ${DIS_SHADER_DIR}/${SHADER}.comp)
    set(SPV ${DIS_SHADER_OUT_DIR}/${VAR}.spv)
    set(HDR ${DIS_SHADER_OUT_DIR}/${VAR}.spv.h)
    add_custom_command(
            OUTPUT ${HDR}
            COMMAND ${GLSLC_EXECUTABLE} --target-env=vulkan1.1 -O ${SRC} -o ${SPV}
            COMMAND ${CMAKE_COMMAND} -DINPUT_FILE=${SPV} -DOUTPUT_FILE=${HDR} -DVAR_NAME=${VAR}
                    -P ${DIS_DIR}/bin2c.cmake
            DEPENDS ${SRC} ${DIS_DIR}/bin2c.cmake
            COMMENT "Compiling DIS shader ${SHADER}.comp"
            VERBATIM
    )
    list(APPEND DIS_SHADER_HEADERS ${HDR})
endforeach()
add_custom_target(dis_shaders DEPENDS ${DIS_SHADER_HEADERS})

add_library(dis_vulkan STATIC
        dis/dis.cpp
        dis/dis_jni.cpp
        dis/src/vkr_dis.c
        dis/src/dis_qcom_me.c
        dis/src/vk_dispatch.c
)
add_dependencies(dis_vulkan dis_shaders)

target_compile_definitions(dis_vulkan PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
target_include_directories(dis_vulkan PRIVATE
        ${DIS_DIR}
        ${DIS_DIR}/include
        ${CMAKE_CURRENT_BINARY_DIR}/dis_gen
)
target_link_libraries(dis_vulkan
        android
        EGL
        GLESv2
        log
        dl
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