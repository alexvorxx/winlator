package com.winlator.renderer;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.util.Log;

public class DIS {
    private static final String TAG = "DisVulkan";

    // AHB format constants (match JNI side)
    public static final int FMT_RGBA8     = 0;
    public static final int FMT_RGBA16F   = 1;

    // Quality presets: minimum side for DIS resolution
    public static final int PRESET_FAST_MIN_SIDE     = 128;
    public static final int PRESET_BALANCED_MIN_SIDE = 180;
    public static final int PRESET_QUALITY_MIN_SIDE = 252;

    private long prevAhbPtr = 0;
    private long currAhbPtr = 0;
    private long flowAhbPtr = 0;

    private int prevGlTex = 0;
    private int currGlTex = 0;
    private int flowGlTex = 0;

    private int frameWidth = 0;
    private int frameHeight = 0;
    private int disWidth = 0;
    private int disHeight = 0;
    private int minSide = PRESET_FAST_MIN_SIDE;
    private boolean useVR = false;
    private boolean initialized = false;

    // ── Native methods ──
    private static native boolean nativeInit(AssetManager assetMgr);
    private static native long    nativeCreateAhbTexture(int width, int height, int format);
    private static native void    nativeDestroyAhbTexture(long ptr);
    private static native int     nativeGetGlTexture(long ptr);
    private static native boolean nativeComputeFlow(long prev, long curr, long flow,
                                                     int disW, int disH, boolean useVR);
    private static native void    nativeCleanup();

    private static native void nativeSetDebugStage(int stage);

    public static final int DBG_OFF          = -1;
    public static final int DBG_COLOR_PREV   =  0;
    public static final int DBG_COLOR_CURR   =  1;
    public static final int DBG_LUMA_PREV    =  2;
    public static final int DBG_LUMA_CURR    =  3;
    public static final int DBG_LUMA_PREV_L1 =  4;
    public static final int DBG_LUMA_PREV_L2 =  5;
    public static final int DBG_LUMA_PREV_L3 =  6;
    public static final int DBG_GRAD_L0      =  7;
    public static final int DBG_GRAD_L3      =  8;
    public static final int DBG_SPARSE_L0    =  9;
    public static final int DBG_SPARSE_L3    = 10;
    public static final int DBG_PROP_A       = 11;
    public static final int DBG_PROP_B       = 12;
    public static final int DBG_DENSE        = 13;
    public static final int DBG_FLOW_AHB     = 14;

    public void setDebugStage(int stage) {
        nativeSetDebugStage(stage);
    }

    static {
        System.loadLibrary("winlator");
    }

    public boolean init(AssetManager assetMgr) {
        if (initialized) return true;
        initialized = nativeInit(assetMgr);
        Log.i(TAG, "init: " + (initialized ? "OK" : "FAILED"));
        return initialized;
    }

    public void setPreset(int minSide) {
        this.minSide = minSide;
    }

    public void setUseVR(boolean useVR) {
        this.useVR = useVR;
    }

    public void ensureTextures(int width, int height) {
        if (frameWidth == width && frameHeight == height && prevAhbPtr != 0) return;

        // Clean up old
        if (prevAhbPtr != 0) { nativeDestroyAhbTexture(prevAhbPtr); prevAhbPtr = 0; }
        if (currAhbPtr != 0) { nativeDestroyAhbTexture(currAhbPtr); currAhbPtr = 0; }
        if (flowAhbPtr != 0) { nativeDestroyAhbTexture(flowAhbPtr); flowAhbPtr = 0; }

        frameWidth = width;
        frameHeight = height;

        // Compute DIS resolution (downscale to minSide)
        int longer = Math.max(width, height);
        int shorter = Math.min(width, height);
        if (longer <= minSide) {
            disWidth = width;
            disHeight = height;
        } else {
            float scale = (float) minSide / longer;
            disWidth = Math.max(8, Math.round(width * scale));
            disHeight = Math.max(8, Math.round(height * scale));
        }

        // Create AHB textures
        // prev/curr at full resolution (RGBA8)
        prevAhbPtr = nativeCreateAhbTexture(width, height, FMT_RGBA8);
        currAhbPtr = nativeCreateAhbTexture(width, height, FMT_RGBA8);
        // flow at DIS resolution (RGBA16F)
        flowAhbPtr = nativeCreateAhbTexture(disWidth, disHeight, FMT_RGBA16F);

        if (prevAhbPtr == 0 || currAhbPtr == 0 || flowAhbPtr == 0) {
            Log.e(TAG, "Failed to create AHB textures");
            return;
        }

        prevGlTex = nativeGetGlTexture(prevAhbPtr);
        currGlTex = nativeGetGlTexture(currAhbPtr);
        flowGlTex = nativeGetGlTexture(flowAhbPtr);

        Log.i(TAG, String.format("Textures: %dx%d, DIS=%dx%d, prevGL=%d, currGL=%d, flowGL=%d",
                width, height, disWidth, disHeight, prevGlTex, currGlTex, flowGlTex));
    }

    public int getPrevGlTexture() { return prevGlTex; }
    public int getCurrGlTexture() { return currGlTex; }
    public int getFlowGlTexture() { return flowGlTex; }
    public int getDisWidth()  { return disWidth; }
    public int getDisHeight() { return disHeight; }

    public void copyFrameToPrev() {
        if (prevGlTex == 0) return;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevGlTex);
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                                    frameWidth, frameHeight);
    }

    public void copyFrameToCurr() {
        if (currGlTex == 0) return;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currGlTex);
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                                    frameWidth, frameHeight);
    }

    public boolean computeFlow() {
        if (prevAhbPtr == 0 || currAhbPtr == 0 || flowAhbPtr == 0) return false;
        return nativeComputeFlow(prevAhbPtr, currAhbPtr, flowAhbPtr,
                                  disWidth, disHeight, useVR);
    }

    public void cleanup() {
        if (prevAhbPtr != 0) { nativeDestroyAhbTexture(prevAhbPtr); prevAhbPtr = 0; }
        if (currAhbPtr != 0) { nativeDestroyAhbTexture(currAhbPtr); currAhbPtr = 0; }
        if (flowAhbPtr != 0) { nativeDestroyAhbTexture(flowAhbPtr); flowAhbPtr = 0; }
        nativeCleanup();
        initialized = false;
        prevGlTex = currGlTex = flowGlTex = 0;
    }

    public void swapPrevCurr() {
        long tmpPtr = prevAhbPtr;
        prevAhbPtr = currAhbPtr;
        currAhbPtr = tmpPtr;

        int tmpTex = prevGlTex;
        prevGlTex = currGlTex;
        currGlTex = tmpTex;
    }

}
