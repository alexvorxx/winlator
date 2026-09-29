package com.winlator.renderer;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.util.Log;

/**
 * DIS frame generator (Vulkan compute) driven from the GLES frame-generation effect.
 *
 * Real frames are copied into AHardwareBuffer-backed GL textures and handed to DIS, which computes
 * the optical flow of each pair of real frames once. Generated frames are rendered by DIS at any
 * time between the two into another AHB texture, which the effect then draws.
 */
public class DIS {
    private static final String TAG = "DisVulkan";

    // AHB format constants (match JNI side)
    public static final int FMT_RGBA8     = 0;
    public static final int FMT_RGBA16F   = 1;

    // Quality presets: flow resolution, in pixels on the frame's shorter side.
    public static final int PRESET_FAST_MIN_SIDE     = 128;
    public static final int PRESET_BALANCED_MIN_SIDE = 180;
    public static final int PRESET_QUALITY_MIN_SIDE  = 252;

    /** How many real frames can wait to be handed to DIS (see {@link #getQueueGlTexture}). */
    public static final int QUEUE_LENGTH = 2;

    private long prevAhbPtr = 0;
    private long currAhbPtr = 0;
    private final long[] queueAhbPtr = new long[QUEUE_LENGTH];
    private long outAhbPtr = 0;

    private int prevGlTex = 0;
    private int currGlTex = 0;
    private final int[] queueGlTex = new int[QUEUE_LENGTH];
    private int outGlTex = 0;

    private int frameWidth = 0;
    private int frameHeight = 0;
    private int minSide = PRESET_FAST_MIN_SIDE;
    private boolean initialized = false;

    // ── Native methods ──
    private static native boolean nativeInit();
    private static native long    nativeCreateAhbTexture(int width, int height, int format);
    private static native void    nativeDestroyAhbTexture(long ptr);
    private static native int     nativeGetGlTexture(long ptr);
    private static native void    nativeSetMinSide(int minSide);
    private static native boolean nativePushFrame(long frame, int generations);
    private static native boolean nativeGenerate(long out, float t);
    private static native void    nativeCleanup();
    private static native void    nativeSetDebugStage(int stage);

    public static final int DBG_OFF  = -1;
    public static final int DBG_FLOW =  0;

    static {
        System.loadLibrary("winlator");
    }

    /** Any stage other than {@link #DBG_OFF} makes generated frames show the estimated flow. */
    public void setDebugStage(int stage) {
        nativeSetDebugStage(stage);
    }

    public boolean init(AssetManager assetMgr) {
        if (initialized) return true;
        initialized = nativeInit();
        if (initialized) nativeSetMinSide(minSide);
        Log.i(TAG, "init: " + (initialized ? "OK" : "FAILED"));
        return initialized;
    }

    public void setPreset(int minSide) {
        this.minSide = minSide;
        if (initialized) nativeSetMinSide(minSide);
    }

    public void ensureTextures(int width, int height) {
        if (frameWidth == width && frameHeight == height && prevAhbPtr != 0) return;

        destroyTextures();
        frameWidth = width;
        frameHeight = height;

        // All at the frame's own size: the two real frames DIS reads, the ones waiting to be
        // handed to it, and the generated one.
        prevAhbPtr = nativeCreateAhbTexture(width, height, FMT_RGBA8);
        currAhbPtr = nativeCreateAhbTexture(width, height, FMT_RGBA8);
        boolean ok = prevAhbPtr != 0 && currAhbPtr != 0;
        for (int i = 0; i < QUEUE_LENGTH; i++) {
            queueAhbPtr[i] = nativeCreateAhbTexture(width, height, FMT_RGBA8);
            ok &= queueAhbPtr[i] != 0;
        }
        outAhbPtr  = nativeCreateAhbTexture(width, height, FMT_RGBA8);

        if (!ok || outAhbPtr == 0) {
            Log.e(TAG, "Failed to create AHB textures");
            destroyTextures();
            return;
        }

        prevGlTex = nativeGetGlTexture(prevAhbPtr);
        currGlTex = nativeGetGlTexture(currAhbPtr);
        for (int i = 0; i < QUEUE_LENGTH; i++) queueGlTex[i] = nativeGetGlTexture(queueAhbPtr[i]);
        outGlTex  = nativeGetGlTexture(outAhbPtr);

        Log.i(TAG, String.format("Textures: %dx%d, flow min side %d, prevGL=%d, currGL=%d, outGL=%d",
                width, height, minSide, prevGlTex, currGlTex, outGlTex));
    }

    private void destroyTextures() {
        if (prevAhbPtr != 0) { nativeDestroyAhbTexture(prevAhbPtr); prevAhbPtr = 0; }
        if (currAhbPtr != 0) { nativeDestroyAhbTexture(currAhbPtr); currAhbPtr = 0; }
        for (int i = 0; i < QUEUE_LENGTH; i++) {
            if (queueAhbPtr[i] != 0) { nativeDestroyAhbTexture(queueAhbPtr[i]); queueAhbPtr[i] = 0; }
            queueGlTex[i] = 0;
        }
        if (outAhbPtr != 0)  { nativeDestroyAhbTexture(outAhbPtr);  outAhbPtr = 0; }
        prevGlTex = currGlTex = outGlTex = 0;
        frameWidth = frameHeight = 0;
    }

    public int getPrevGlTexture() { return prevGlTex; }
    public int getCurrGlTexture() { return currGlTex; }
    public int getOutGlTexture()  { return outGlTex; }

    /** The texture for the {@code i}-th real frame waiting to be handed to DIS, oldest first. */
    public int getQueueGlTexture(int i) { return queueGlTex[i]; }

    public boolean ownsGlTexture(int glTexture) {
        if (glTexture <= 0) return false;
        if (glTexture == prevGlTex || glTexture == currGlTex || glTexture == outGlTex) return true;
        for (int tex : queueGlTex) if (tex == glTexture) return true;
        return false;
    }

    /** Copies the bound framebuffer into {@code glTexture}, one of this object's textures. */
    public void copyFrameTo(int glTexture) {
        if (glTexture == 0) return;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTexture);
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0,
                                    frameWidth, frameHeight);
    }

    /**
     * Hands the oldest waiting frame (queue texture 0) to DIS, which computes the flow from the
     * previous real frame to it; that frame becomes curr, curr becomes prev, the rest of the queue
     * moves up, and the texture prev had is reused at its end.
     */
    public boolean pushQueued(int generations) {
        if (queueAhbPtr[0] == 0) return false;
        boolean ok = nativePushFrame(queueAhbPtr[0], generations);

        long freePtr = prevAhbPtr;
        int freeTex = prevGlTex;
        prevAhbPtr = currAhbPtr;
        prevGlTex = currGlTex;
        currAhbPtr = queueAhbPtr[0];
        currGlTex = queueGlTex[0];
        for (int i = 1; i < QUEUE_LENGTH; i++) {
            queueAhbPtr[i - 1] = queueAhbPtr[i];
            queueGlTex[i - 1] = queueGlTex[i];
        }
        queueAhbPtr[QUEUE_LENGTH - 1] = freePtr;
        queueGlTex[QUEUE_LENGTH - 1] = freeTex;
        return ok;
    }

    /**
     * Hands the frame just copied into the prev (first frame) or curr texture to DIS, which
     * computes the flow from the previous real frame to it. DIS orders itself after the GLES copy
     * on the GPU, so the copy does not have to be finished first.
     * {@code generations} is how many frames will be generated for this pair (1..3).
     */
    public boolean pushFrame(boolean fromPrev, int generations) {
        long ptr = fromPrev ? prevAhbPtr : currAhbPtr;
        if (ptr == 0) return false;
        return nativePushFrame(ptr, generations);
    }

    /** Renders the frame at t in [0, 1] between the last two pushed frames into the out texture. */
    public boolean generate(float t) {
        if (outAhbPtr == 0) return false;
        return nativeGenerate(outAhbPtr, t);
    }

    public void cleanup() {
        destroyTextures();
        nativeCleanup();
        initialized = false;
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
