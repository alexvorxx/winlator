package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.XServer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

@SuppressLint("ViewConstructor")
public class XServerView extends GLSurfaceView {
    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
    private static final int EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100;
    private static final int EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101;

    private final GLRenderer renderer;

    public XServerView(Context context, XServer xServer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setEGLContextClientVersion(3);
        setEGLContextFactory(new PriorityContextFactory());
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer = new GLRenderer(this, xServer);
        setRenderer(renderer);
        //setRenderMode(RENDERMODE_WHEN_DIRTY);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    // A GLES 3 context that asks the GPU for a high priority where EGL_IMG_context_priority is
    // available, so composing the screen (and frame generation) is not queued behind a game that
    // keeps the GPU busy.
    private static class PriorityContextFactory implements EGLContextFactory {
        @Override
        public EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig config) {
            String extensions = egl.eglQueryString(display, EGL10.EGL_EXTENSIONS);
            if (extensions != null && extensions.contains("EGL_IMG_context_priority")) {
                int[] attribs = {EGL_CONTEXT_CLIENT_VERSION, 3,
                        EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG, EGL10.EGL_NONE};
                EGLContext context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attribs);
                if (context != null && context != EGL10.EGL_NO_CONTEXT) {
                    int[] level = new int[1];
                    egl.eglQueryContext(display, context, EGL_CONTEXT_PRIORITY_LEVEL_IMG, level);
                    Log.i("XServerView", "GL context priority: " +
                            (level[0] == EGL_CONTEXT_PRIORITY_HIGH_IMG ? "high" : "0x" + Integer.toHexString(level[0])));
                    return context;
                }
            }
            int[] attribs = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE};
            return egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attribs);
        }

        @Override
        public void destroyContext(EGL10 egl, EGLDisplay display, EGLContext context) {
            egl.eglDestroyContext(display, context);
        }
    }
}
