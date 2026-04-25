cmake_minimum_required(VERSION 3.22.1)

Project(Winlator)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

#add_subdirectory(proot)
#add_subdirectory(virglrenderer)
add_subdirectory(virgl_test_server)
add_subdirectory(OpenXR-SDK)
add_subdirectory(patchelf)

include_directories(winlator/include)

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
            winlator/patchelf_wrapper.cpp)

target_link_libraries(winlator
                      log
                      android
                      jnigraphics
                      openxr_loader
                      aaudio
                      EGL
                      GLESv2
                      GLESv3)