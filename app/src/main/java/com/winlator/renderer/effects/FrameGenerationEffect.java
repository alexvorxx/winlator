package com.winlator.renderer.effects;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import com.winlator.renderer.EffectComposer;
import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class FrameGenerationEffect extends Effect {
    // Constants
    public static final int GENERATION_MODE_FAST = 0;
    public static final int GENERATION_MODE_BALANCED = 1;
    public static final int GENERATION_MODE_QUALITY = 2;

    public static final float BLEND_FACTOR_X2 = 0.50f;
    public static final float BLEND_FACTOR_X3 = 0.33f;
    public static final float BLEND_FACTOR_X4 = 0.25f;

    public static final int FPS_MULTIPLIER_X2 = 2;
    public static final int FPS_MULTIPLIER_X3 = 3;
    public static final int FPS_MULTIPLIER_X4 = 4;

    public static final int FPS_AUTO = 0;
    public static final int FPS_15 = 15;
    public static final int FPS_20 = 20;
    public static final int FPS_25 = 25;
    public static final int FPS_30 = 30;
    public static final int FPS_45 = 45;
    public static final int FPS_60 = 60;

    public static final int API_GLES20 = 0;
    public static final int API_QUALCOMM = 1;

    public static final float DEFAULT_MOTION_SCALE = 0.50f;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    private static final long MIN_FRAME_INTERVAL_NS = 8 * NANOS_PER_MILLISECOND;
    private static final long MAX_FRAME_INTERVAL_NS = 1000 * NANOS_PER_MILLISECOND;

    private static final int FRAME_HISTORY_SIZE = 10;

    private static final String TAG = "FrameGeneration";

    // Fields
    private boolean isEnabled = false;
    private int fpsMultiplier;
    private int generationMode;
    private int apiMode;
    private boolean usePostProcessing;
    private boolean blendModeAuto;
    private float blendFactor;
    private float motionScale = 0.5f;;

    private boolean hasFirstFrame = false;
    private boolean hasSecondFrame = false;
    private boolean waitingForSecondFrame = true;

    private int initialFPS = FPS_30;
    private boolean autoDetectFPS = false;

    private int currentWidth = 0;
    private int currentHeight = 0;

    // QCOM fields
    private boolean qcomInitialized = false;
    private boolean hasMotionEstimation = false;
    private boolean useHardwareMotion = false;

    private int qcomRefLuminanceFBO = -1;
    private int qcomTargetLuminanceFBO = -1;

    private int qcomLuminanceProgram = -1;

    private int qcomSearchBlockX = 1;
    private int qcomSearchBlockY = 1;
    private int lumaWidth = 0;
    private int lumaHeight = 0;

    // Uniform locations
    public int uIsEnabledLoc = -1;
    private int uBlendFactorLoc = -1;
    private int uMotionScaleLoc = -1;
    private int uTextureHistoryLoc = -1;
    private int uTexturePrevLoc = -1;
    private int uTextureCurrLoc = -1;
    private int uResolutionLoc = -1;
    private int uUsePostProc = -1;

    // Uniform locations for hardware paths
    private int uMotionTextureLoc = -1;
    private int uUseHardwareMotionLoc = -1;

    // Textures
    private int textureHistory = -1;
    private int texturePrev = -1;
    private int textureCurr = -1;

    private int qcomMotionTexture = -1;      // RGBA16F for motion vectors
    private int qcomRefLuminanceTexture = -1;   // R8 luminance of prev frame
    private int qcomTargetLuminanceTexture = -1; // R8 luminance of curr frame

    // Time
    private List<Long> realFrameIntervals = new ArrayList<>();

    private int displayRefreshRate = 60;
    private int realFrameDisplayCount = 0;
    private int generatedFrameDisplayCount = 0;

    private int currentDisplayFrameType = 0;
    private int currentFrameDisplayCount = 0;

    private int currentSequence = 0;

    private boolean currentRealFrameCaptured = false;
    private int currentRealFrameIndex = 0;

    private int capturedRealFrame = -1;
    private boolean hasCapturedFrame = false;
    private boolean skipFirstRealDisplay = false;

    private long lastRealFrameTimeNs = 0;
    private long lastAnyFrameShownTimeNs = 0;
    private long nextFrameTimeNs = 0;

    private long currentRealFrameIntervalNs = 33333333;  // 30 FPS
    private long currentTargetFrameIntervalNs = 16666666;  // 60 FPS

    private final LumaMaterial lumaMaterial = new LumaMaterial();

    private final GLRenderer renderer;

    // Native methods (return boolean for init success)
    private static native boolean nativeInitQCOM();
    private static native void nativeTexEstimateMotionQCOM(int ref, int target, int output);

    static {
        System.loadLibrary("winlator");
    }

    private void LogString(String message) {
        if (EffectComposer.logEnabled)
            Log.d(TAG, message);
    }

    public FrameGenerationEffect(GLRenderer renderer, int generationMode, int fpsMultiplier, int apiMode,
                                 boolean enablePostProcessing, boolean blendModeAuto, float motionScale) {
        super();
        this.renderer = renderer;
        this.generationMode = generationMode;
        this.fpsMultiplier = fpsMultiplier;
        this.apiMode = apiMode;
        this.usePostProcessing = enablePostProcessing;
        this.blendModeAuto = blendModeAuto;
        this.motionScale = motionScale;

        updateFrameIntervals();
        calculateDisplayCounts();
        Log.d(TAG, "FrameGenerationEffect created with generationMode = " + generationMode +
                " fpsMultiplier = " + fpsMultiplier + " apiMode = " + apiMode + " usePostProcessing" +
                usePostProcessing + " blendModeAuto = " + blendModeAuto + " motionScale = " + motionScale);
    }

    /* Initializes QCOM extensions if present on the device.
     * Must be called on the GL thread. */
    private void initQCOMIfNeeded() {
        if (apiMode == API_GLES20) {
            Log.d(TAG, "GLES 2.0 initialized");
            return;
        }

        String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        if (extensions == null) {
            Log.d(TAG, "GL_EXTENSIONS returned null, cannot check QCOM support");
            return;
        }

        boolean motionEstSupported = extensions.contains("GL_QCOM_motion_estimation");

        if (!motionEstSupported) {
            Log.d(TAG, "GL_QCOM_motion_estimation not supported, fallback to GLES 2.0");
            return;
        }

        boolean qcomInitSuccess = nativeInitQCOM();

        if (motionEstSupported && qcomInitSuccess) {
            String version = GLES20.glGetString(GLES20.GL_VERSION);
            if (version != null && version.contains("OpenGL ES 3.")) {
                hasMotionEstimation = true;
                int[] blockSize = new int[2];
                GLES20.glGetIntegerv(0x8C90, blockSize, 0); // MOTION_ESTIMATION_SEARCH_BLOCK_X_QCOM
                GLES20.glGetIntegerv(0x8C91, blockSize, 1); // MOTION_ESTIMATION_SEARCH_BLOCK_Y_QCOM
                qcomSearchBlockX = Math.max(1, blockSize[0]);
                qcomSearchBlockY = Math.max(1, blockSize[1]);
                Log.d(TAG, "QCOM_motion_estimation supported, block=" + qcomSearchBlockX + "x" + qcomSearchBlockY);
            } else {
                Log.d(TAG, "QCOM_motion_estimation found but requires GLES3, ignoring");
            }
        }
    }

    @Override
    protected ShaderMaterial createMaterial() {
        switch (generationMode) {
            case GENERATION_MODE_FAST:
                Log.d(TAG, "Fast generation mode selected");
                return new FastFrameGenerationMaterial();
            case GENERATION_MODE_QUALITY:
                Log.d(TAG, "Quality generation mode selected");
                return new QualityFrameGenerationMaterial();
            case GENERATION_MODE_BALANCED:
            default:
                Log.d(TAG, "Balanced generation mode selected");
                return new BalancedFrameGenerationMaterial();
        }
    }

    public void setGenerationMode(int mode) {
        if (this.generationMode != mode) {
            this.generationMode = mode;
            cleanup();
            resetState();
        }
    }

    public void setMotionScale(float motionScale) {
        if (this.motionScale != motionScale) {
            this.motionScale = motionScale;
            if (motionScale < 0.1)
                this.motionScale = 0.1f;
            LogString("motionScale = " + motionScale);
        }
    }

    public void setApiMode(int apiMode) {
        if (this.apiMode != apiMode) {
            this.apiMode = apiMode;
            LogString("blendModeAuto = " + apiMode);
        }
    }

    public void setUsePostProcessing(boolean usePostProcessing) {
        if (this.usePostProcessing != usePostProcessing) {
            this.usePostProcessing = usePostProcessing;
            LogString("usePostProcessing = " + usePostProcessing);
        }
    }

    public void setBlendMode(boolean blendModeAuto) {
        if (this.blendModeAuto != blendModeAuto) {
            this.blendModeAuto = blendModeAuto;
            LogString("blendModeAuto = " + blendModeAuto);
        }
    }

    public void setFpsMultiplier(int fpsMultiplier) {
        if (this.fpsMultiplier != fpsMultiplier) {
            this.fpsMultiplier = fpsMultiplier;
            cleanup();
            calculateDisplayCounts();
            resetState();
            LogString("fpsMultiplier = " + fpsMultiplier);
        }
    }

    public int getFpsMultiplier() {
        return fpsMultiplier;
    }

    public void toggleGeneration() {
        isEnabled = !isEnabled;
        LogString("Generation " + (isEnabled ? "ENABLED" : "DISABLED"));

        if (isEnabled) {
            clearHistory();
            long currentTimeNs = System.nanoTime();
            lastRealFrameTimeNs = currentTimeNs;
            lastAnyFrameShownTimeNs = currentTimeNs;
            nextFrameTimeNs = currentTimeNs;

            hasFirstFrame = false;
            hasSecondFrame = false;
            waitingForSecondFrame = true;
            currentDisplayFrameType = 0;
            currentFrameDisplayCount = 0;

            currentRealFrameCaptured = false;
            currentRealFrameIndex = 0;
        } else {
            useHardwareMotion = false;
        }
    }

    private void updateFrameIntervals() {
        currentRealFrameIntervalNs = NANOS_PER_SECOND / initialFPS;
        currentTargetFrameIntervalNs = currentRealFrameIntervalNs / fpsMultiplier;

        currentTargetFrameIntervalNs = Math.max(MIN_FRAME_INTERVAL_NS,
                Math.min(currentTargetFrameIntervalNs, currentRealFrameIntervalNs));

        calculateDisplayCounts();

        LogString(String.format("Intervals updated: real=%.1fms, target=%.1fms, auto=%b",
                currentRealFrameIntervalNs / (double)NANOS_PER_MILLISECOND,
                currentTargetFrameIntervalNs / (double)NANOS_PER_MILLISECOND,
                autoDetectFPS));
    }

    private void calculateDisplayCounts() {
        long frameDurationNs = NANOS_PER_SECOND / displayRefreshRate;

        realFrameDisplayCount = (int) Math.max(1, currentTargetFrameIntervalNs / frameDurationNs);
        generatedFrameDisplayCount = (int) Math.max(1, currentTargetFrameIntervalNs / frameDurationNs * (fpsMultiplier - 1));

        LogString(String.format("Display counts: real=%d, generated=%d (refresh rate=%d Hz)",
                realFrameDisplayCount, generatedFrameDisplayCount, displayRefreshRate));
    }

    private void clearHistory() {
        if (textureHistory != -1) {
            GLES20.glDeleteTextures(1, new int[]{textureHistory}, 0);
        }
        if (texturePrev != -1) {
            GLES20.glDeleteTextures(1, new int[]{texturePrev}, 0);
        }
        if (textureCurr != -1) {
            GLES20.glDeleteTextures(1, new int[]{textureCurr}, 0);
        }
        if (capturedRealFrame != -1) {
            GLES20.glDeleteTextures(1, new int[]{capturedRealFrame}, 0);
        }
        textureHistory = -1;
        texturePrev = -1;
        textureCurr = -1;
        capturedRealFrame = -1;
    }

    public int getFrameToDisplay() {
        if (!isEnabled) {
            LogString("Generation not enabled, showing real frame");
            return 0;
        }

        int requiredDisplayCount = (currentDisplayFrameType == 0) ?
                realFrameDisplayCount : generatedFrameDisplayCount;

        if (currentFrameDisplayCount < requiredDisplayCount) {
            currentFrameDisplayCount++;

            if (currentDisplayFrameType == 0 && currentFrameDisplayCount == 1 && skipFirstRealDisplay) {
                skipFirstRealDisplay = false;
                currentDisplayFrameType = 1;
                currentFrameDisplayCount = 0;
                LogString("Skipping first real display, switching to GENERATED");
                return getFrameToDisplay();
            }

            LogString(String.format("Continue showing %s frame (%d/%d)",
                    currentDisplayFrameType == 0 ? "REAL" : "GENERATED",
                    currentFrameDisplayCount, requiredDisplayCount));
            return currentDisplayFrameType;
        }

        currentFrameDisplayCount = 1;

        if (currentDisplayFrameType == 0) {
            currentDisplayFrameType = 1;
            LogString("Switching to GENERATED frame");
        } else {
            currentDisplayFrameType = 0;
            currentRealFrameIndex = 0;
            currentRealFrameCaptured = false;

            if (hasCapturedFrame && capturedRealFrame != -1) {
                if (textureHistory != -1) {
                    GLES20.glDeleteTextures(1, new int[]{textureHistory}, 0);
                }
                textureHistory = texturePrev;
                texturePrev = textureCurr;
                textureCurr = capturedRealFrame;
                capturedRealFrame = -1;
                hasCapturedFrame = false;
                LogString("Using captured real frame for next cycle");
            }

            LogString("Switching to REAL frame");
        }

        return currentDisplayFrameType;
    }

    public synchronized void updateFPS(int fps) {
        if (fps < 1)
            return;

        this.initialFPS = fps;
        updateFrameIntervals();
    }

    public void setInitialFPS(int fps) {
        if (fps == FPS_AUTO) {
            autoDetectFPS = true;
            LogString("Auto FPS detection enabled");
        } else {
            autoDetectFPS = false;
            this.initialFPS = fps;
            LogString("Initial FPS set to: " + fps);
        }
        updateFrameIntervals();
        resetState();
    }

    public int getDisplayRefreshRate() {
        return displayRefreshRate;
    }

    public void setDisplayRefreshRate(int refreshRate) {
        if (refreshRate != displayRefreshRate) {
            displayRefreshRate = refreshRate;
            calculateDisplayCounts();
            LogString("Display refresh rate set to: " + refreshRate + " Hz");
        }
    }

    public int getInitialFPS() {
        return initialFPS;
    }

    public boolean isAutoDetectFPS() {
        return autoDetectFPS;
    }

    public long getCurrentRealFrameInterval() {
        return currentRealFrameIntervalNs / NANOS_PER_MILLISECOND;
    }

    public long getCurrentTargetFrameInterval() {
        return currentTargetFrameIntervalNs / NANOS_PER_MILLISECOND;
    }

    public void prepareFrame(int width, int height, int sequence) {
        this.currentWidth = width;
        this.currentHeight = height;
        this.currentSequence = sequence;

        if (!isEnabled) return;

        long currentTimeNs = System.nanoTime();

        if (!qcomInitialized && width > 0 && height > 0) {
            initQCOMIfNeeded();
            qcomInitialized = true;
        }

        if (sequence == 0) {
            // Real frame
            currentRealFrameIndex++;

            LogString(String.format("Real frame display #%d/%d, captured=%b",
                    currentRealFrameIndex, realFrameDisplayCount, currentRealFrameCaptured));

            boolean shouldCapture = false;
            int capturePoint = realFrameDisplayCount / 2;

            if (!currentRealFrameCaptured && currentRealFrameIndex >= capturePoint) {
                shouldCapture = true;
                currentRealFrameCaptured = true;
                LogString("Capturing real frame at mid-point of display cycle");
            }

            if (shouldCapture) {
                if (lastRealFrameTimeNs != 0) {
                    long intervalNs = currentTimeNs - lastRealFrameTimeNs;
                    realFrameIntervals.add(intervalNs);
                    if (realFrameIntervals.size() > FRAME_HISTORY_SIZE) {
                        realFrameIntervals.remove(0);
                    }
                    if (autoDetectFPS) {
                        updateFrameIntervals();
                    }
                }

                int newTextureId = captureCurrentFrameSimple(width, height);
                if (newTextureId == -1) return;

                if (!hasFirstFrame) {
                    textureCurr = newTextureId;
                    hasFirstFrame = true;
                    waitingForSecondFrame = true;
                    LogString("Captured first real frame");
                } else if (waitingForSecondFrame) {
                    texturePrev = textureCurr;
                    textureCurr = newTextureId;
                    hasSecondFrame = true;
                    waitingForSecondFrame = false;
                    LogString("Captured second real frame, ready for generation");
                } else {
                    if (capturedRealFrame != -1) {
                        GLES20.glDeleteTextures(1, new int[]{capturedRealFrame}, 0);
                    }
                    capturedRealFrame = newTextureId;
                    hasCapturedFrame = true;
                    skipFirstRealDisplay = true;
                    LogString("Captured real frame for NEXT cycle (delayed display)");
                }

                lastRealFrameTimeNs = currentTimeNs;
                lastAnyFrameShownTimeNs = currentTimeNs;
            } else {
                lastAnyFrameShownTimeNs = currentTimeNs;
                LogString("Skipping capture - already captured this cycle");
            }

        } else if (sequence == 1) {
            // Generated frame
            LogString("Preparing GENERATED frame (sequence=1)");

            if (hasFirstFrame && hasSecondFrame &&
                    texturePrev != -1 && textureCurr != -1) {

                long timeSinceRealFrameNs = currentTimeNs - lastRealFrameTimeNs;

                if (blendModeAuto)
                    blendFactor = Math.min(1.0f, Math.max(0.0f, (float)timeSinceRealFrameNs / currentRealFrameIntervalNs));
                else
                    calculateBlendFactor();

                lastAnyFrameShownTimeNs = currentTimeNs;

                // Hardware path selection
                useHardwareMotion = false;

                if (hasMotionEstimation) {
                    ensureQCOMMotionTextures(width, height);
                    if (qcomRefLuminanceTexture != -1 && qcomTargetLuminanceTexture != -1 &&
                            qcomMotionTexture != -1) {

                        // 1. Convert to luminance
                        copyTextureToR8(renderer, texturePrev, qcomRefLuminanceFBO, lumaWidth, lumaHeight);
                        GLES20.glFinish();
                        copyTextureToR8(renderer, textureCurr, qcomTargetLuminanceFBO, lumaWidth, lumaHeight);
                        GLES20.glFinish();

                        // Reset before QCOM
                        for (int i = 0; i < 5; i++) {
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                        }
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                        while (GLES20.glGetError() != GLES20.GL_NO_ERROR);

                        // 5. QCOM motion estimation
                        nativeTexEstimateMotionQCOM(qcomRefLuminanceTexture, qcomTargetLuminanceTexture, qcomMotionTexture);

                        int err = GLES20.glGetError();
                        if (err != GLES20.GL_NO_ERROR) {
                            Log.e(TAG, "nativeTexEstimateMotionQCOM error: 0x" + Integer.toHexString(err));
                            useHardwareMotion = false;
                        } else {
                            GLES20.glFinish();
                            useHardwareMotion = true;
                            LogString("Hardware motion estimation OK");
                        }

                        // After copyTextureToR8 and glFinish:
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, qcomRefLuminanceFBO);
                        ByteBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
                        GLES20.glReadPixels(width/2, height/2, 1, 1, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, buf);
                        //Log.d(TAG, "REF_LUMA via dedicated FBO: " + (buf.get(0) & 0xFF));
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

                        useHardwareMotion = true;
                        LogString("Using hardware motion estimation");
                    }
                }

                LogString(String.format("Generated: prev=%d, curr=%d, blend=%.3f (time since real: %.2fms), hwMotion=%b",
                        texturePrev, textureCurr, blendFactor, timeSinceRealFrameNs / (double)NANOS_PER_MILLISECOND, useHardwareMotion));
            } else {
                LogString("Not enough frames for generation yet");
                useHardwareMotion = false;
            }
        }
    }

    public void setupShaderUniforms() {
        ShaderMaterial material = getMaterial();
        if (material == null || material.getProgram() == 0) return;

        int program = material.getProgram();

        // Initialize uniform locations
        if (uIsEnabledLoc == -1) {
            uIsEnabledLoc = GLES20.glGetUniformLocation(program, "uIsEnabled");
            uBlendFactorLoc = GLES20.glGetUniformLocation(program, "uBlendFactor");
            uMotionScaleLoc = GLES20.glGetUniformLocation(program, "uMotionScale");
            uTextureHistoryLoc = GLES20.glGetUniformLocation(program, "uTextureHistory");
            uTexturePrevLoc = GLES20.glGetUniformLocation(program, "uTexturePrev");
            uTextureCurrLoc = GLES20.glGetUniformLocation(program, "uTextureCurr");
            uResolutionLoc = GLES20.glGetUniformLocation(program, "resolution");
            uUsePostProc = GLES20.glGetUniformLocation(program, "uUsePostProc");
            uMotionTextureLoc = GLES20.glGetUniformLocation(program, "uMotionTexture");
            uUseHardwareMotionLoc = GLES20.glGetUniformLocation(program, "uUseHardwareMotion");
        }

        // Bind prev texture to unit 1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        if (texturePrev != -1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texturePrev);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glUniform1i(uTexturePrevLoc, 1);

        // Bind curr texture to unit 2
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        if (textureCurr != -1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureCurr);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glUniform1i(uTextureCurrLoc, 2);

        // Bind history texture to unit 3
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        if (textureHistory != -1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHistory);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glUniform1i(uTextureHistoryLoc, 3);

        // Bind motion texture if available (unit 4)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        if (useHardwareMotion && qcomMotionTexture != -1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, qcomMotionTexture);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        if (uMotionTextureLoc != -1)
            GLES20.glUniform1i(uMotionTextureLoc, 4);

        if (uResolutionLoc != -1 && currentWidth > 0 && currentHeight > 0) {
            GLES20.glUniform2f(uResolutionLoc, currentWidth, currentHeight);
        }

        if (uUsePostProc != -1)
            GLES20.glUniform1i(uUsePostProc, usePostProcessing ? 1 : 0);

        // Default flags
        GLES20.glUniform1i(uIsEnabledLoc, 0);
        GLES20.glUniform1f(uBlendFactorLoc, 0.0f);
        GLES20.glUniform1f(uMotionScaleLoc, DEFAULT_MOTION_SCALE);

        if (uUseHardwareMotionLoc != -1)
            GLES20.glUniform1i(uUseHardwareMotionLoc, 0);

        if (isEnabled && currentSequence == 1) {
            boolean canShowGenerated = texturePrev != -1 && textureCurr != -1 && !waitingForSecondFrame;

            if (canShowGenerated) {
                GLES20.glUniform1i(uIsEnabledLoc, 1);
                GLES20.glUniform1f(uBlendFactorLoc, blendFactor);
                GLES20.glUniform1f(uMotionScaleLoc, motionScale);

                if (useHardwareMotion && uUseHardwareMotionLoc != -1) {
                    GLES20.glUniform1i(uUseHardwareMotionLoc, 1);
                }

                long currentTimeNs = System.nanoTime();
                long timeSinceRealNs = currentTimeNs - lastRealFrameTimeNs;
                LogString(String.format("Showing GENERATED frame, resolution=%dx%d, blend=%.3f (%.2fms since real), hwMotion=%b",
                        currentWidth, currentHeight, blendFactor, timeSinceRealNs / (double)NANOS_PER_MILLISECOND, useHardwareMotion));
            } else {
                LogString("Cannot show generated, showing REAL frame instead");
                GLES20.glUniform1i(uIsEnabledLoc, 0);
                GLES20.glUniform1f(uBlendFactorLoc, 0.0f);
                GLES20.glUniform1f(uMotionScaleLoc, DEFAULT_MOTION_SCALE);
                GLES20.glUniform1i(uUseHardwareMotionLoc, 0);
            }
        } else {
            GLES20.glUniform1i(uIsEnabledLoc, 0);
            GLES20.glUniform1f(uBlendFactorLoc, 0.0f);
            GLES20.glUniform1f(uMotionScaleLoc, DEFAULT_MOTION_SCALE);
            GLES20.glUniform1i(uUseHardwareMotionLoc, 0);
            LogString(String.format("Showing REAL frame, resolution=%dx%d (sequence=%d, enabled=%b)",
                    currentWidth, currentHeight, currentSequence, isEnabled));
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    public void cleanup() {
        clearHistory();
        deleteQCOMResources();
        LogString("Effect cleaned up");
    }

    private void deleteQCOMResources() {
        if (qcomMotionTexture != -1) {
            GLES20.glDeleteTextures(1, new int[]{qcomMotionTexture}, 0);
            qcomMotionTexture = -1;
        }
        if (qcomRefLuminanceTexture != -1) {
            GLES20.glDeleteTextures(1, new int[]{qcomRefLuminanceTexture}, 0);
            qcomRefLuminanceTexture = -1;
        }
        if (qcomTargetLuminanceTexture != -1) {
            GLES20.glDeleteTextures(1, new int[]{qcomTargetLuminanceTexture}, 0);
            qcomTargetLuminanceTexture = -1;
        }
        if (qcomRefLuminanceFBO != -1) {
            GLES20.glDeleteFramebuffers(1, new int[]{qcomRefLuminanceFBO}, 0);
            qcomRefLuminanceFBO = -1;
        }
        if (qcomTargetLuminanceFBO != -1) {
            GLES20.glDeleteFramebuffers(1, new int[]{qcomTargetLuminanceFBO}, 0);
            qcomTargetLuminanceFBO = -1;
        }
        if (qcomLuminanceProgram != -1) {
            GLES20.glDeleteProgram(qcomLuminanceProgram);
            qcomLuminanceProgram = -1;
        }
    }

    public boolean isEnabled() { return isEnabled; }

    private int captureCurrentFrameSimple(int width, int height) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int newTexture = textures[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, newTexture);
        // glTexImage2D, not glTexStorage2D
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        return newTexture;
    }


    private void calculateBlendFactor() {
        if (fpsMultiplier == FPS_MULTIPLIER_X2) {
            blendFactor = BLEND_FACTOR_X2;
        } else {
            float frameFactor = (float) currentFrameDisplayCount / realFrameDisplayCount;
            if (fpsMultiplier == FPS_MULTIPLIER_X3) {
                if (realFrameDisplayCount > 1) {
                    if (frameFactor <= 1.0)
                        blendFactor = BLEND_FACTOR_X3;
                    else
                        blendFactor = Math.min(1.0f, BLEND_FACTOR_X3 * 2);
                } else
                    blendFactor = BLEND_FACTOR_X2;
            } else if (fpsMultiplier == FPS_MULTIPLIER_X4) {
                if (realFrameDisplayCount > 2) {
                    if (frameFactor <= 1.0)
                        blendFactor = BLEND_FACTOR_X4;
                    else if (frameFactor <= 2.0)
                        blendFactor = BLEND_FACTOR_X4 * 2;
                    else
                        blendFactor = Math.min(1.0f, BLEND_FACTOR_X4 * 3);
                } else if (realFrameDisplayCount > 1) {
                    if (frameFactor <= 1.0)
                        blendFactor = BLEND_FACTOR_X3;
                    else
                        blendFactor = Math.min(1.0f, BLEND_FACTOR_X3 * 2);
                } else
                    blendFactor = BLEND_FACTOR_X2;
            }
        }
        LogString("currentFrameDisplayCount = " + currentFrameDisplayCount +
                " realFrameDisplayCount = " + realFrameDisplayCount + " blendFactor = " + blendFactor);
    }

    public void resetState() {
        clearHistory();
        hasFirstFrame = false;
        hasSecondFrame = false;
        waitingForSecondFrame = true;
        lastRealFrameTimeNs = 0;
        lastAnyFrameShownTimeNs = 0;
        nextFrameTimeNs = 0;
        currentDisplayFrameType = 0;
        currentFrameDisplayCount = 0;

        currentRealFrameCaptured = false;
        currentRealFrameIndex = 0;

        if (capturedRealFrame != -1) {
            GLES20.glDeleteTextures(1, new int[]{capturedRealFrame}, 0);
        }
        capturedRealFrame = -1;
        hasCapturedFrame = false;
        skipFirstRealDisplay = false;
    }

    public boolean isReadyForGeneration() {
        return hasFirstFrame && hasSecondFrame && texturePrev != -1 && textureCurr != -1;
    }

    private void ensureQCOMMotionTextures(int width, int height) {
        // Align by block — QCOM requires multiplicity
        lumaWidth = (int)(width * motionScale / qcomSearchBlockX) * qcomSearchBlockX;
        lumaHeight = (int)(height * motionScale / qcomSearchBlockY) * qcomSearchBlockY;
        if (lumaWidth <= 0 || lumaHeight <= 0) return;

        if (lumaWidth < 8) lumaWidth = 8;
        if (lumaHeight < 8) lumaHeight = 8;

        int motionWidth = lumaWidth / qcomSearchBlockX;
        int motionHeight = lumaHeight / qcomSearchBlockY;

        // Ref luminance
        if (qcomRefLuminanceTexture == -1) {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            qcomRefLuminanceTexture = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, qcomRefLuminanceTexture);
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_R8, lumaWidth, lumaHeight);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // Dedicated FBO, pre-attached
            int[] fbo = new int[1];
            GLES20.glGenFramebuffers(1, fbo, 0);
            qcomRefLuminanceFBO = fbo[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, qcomRefLuminanceFBO);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, qcomRefLuminanceTexture, 0);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        // Target luminance
        if (qcomTargetLuminanceTexture == -1) {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            qcomTargetLuminanceTexture = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, qcomTargetLuminanceTexture);
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_R8, lumaWidth, lumaHeight);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            int[] fbo = new int[1];
            GLES20.glGenFramebuffers(1, fbo, 0);
            qcomTargetLuminanceFBO = fbo[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, qcomTargetLuminanceFBO);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, qcomTargetLuminanceTexture, 0);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }
        if (qcomMotionTexture == -1) {
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            qcomMotionTexture = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, qcomMotionTexture);
            GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, motionWidth, motionHeight);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        if (qcomLuminanceProgram == -1) {
            qcomLuminanceProgram = createLuminanceCopyProgram();
        }
    }

    private int createLuminanceCopyProgram() {
        String vertexShaderSrc =
                "attribute vec2 aPosition;\n" +
                        "varying vec2 vUV;\n" +
                        "void main() {\n" +
                        "    vUV = aPosition * 0.5 + 0.5;\n" +
                        "    gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
                        "}\n";

        String fragmentShaderSrc =
                "precision mediump float;\n" +
                        "varying vec2 vUV;\n" +
                        "uniform sampler2D uSrcTexture;\n" +
                        "void main() {\n" +
                        "    vec3 rgb = texture2D(uSrcTexture, vUV).rgb;\n" +
                        "    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));\n" +
                        "    gl_FragColor = vec4(luma, 0.0, 0.0, 1.0);\n" +
                        "}\n";

        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSrc);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSrc);
        if (vs == 0 || fs == 0) return -1;

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Failed to link luminance copy program");
            GLES20.glDeleteProgram(program);
            return -1;
        }

        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return program;
    }

    private int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void copyTextureToR8(GLRenderer renderer, int srcTexture, int dstFBO, int width, int height) {
        if (srcTexture == -1 || dstFBO == -1) return;

        int[] prevFBO = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFBO, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFBO);

        // Completeness
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Luma FBO incomplete: 0x" + Integer.toHexString(status));
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFBO[0]);
            return;
        }

        GLES20.glViewport(0, 0, width, height);
        renderer.setViewportNeedsUpdate(true);
        GLES20.glDisable(GLES20.GL_BLEND);

        lumaMaterial.use();
        renderer.getQuadVertices().bind(lumaMaterial.programId);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexture);
        GLES20.glUniform1i(lumaMaterial.getUniformLocation("screenTexture"), 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        GLES20.glEnable(GLES20.GL_BLEND);
        //renderer.invalidateBoundWindowMaterial();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFBO[0]);
    }

    private static class LumaMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            return String.join("\n",
                    "precision mediump float;",
                    "uniform sampler2D screenTexture;",
                    "varying vec2 vUV;",
                    "void main() {",
                    "vec3 c = texture2D(screenTexture, vUV).rgb;",
                    "gl_FragColor = vec4(dot(c, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);",
                    "}"
            );
        }
    }

    private static class FastFrameGenerationMaterial extends ScreenMaterial {
        public FastFrameGenerationMaterial() {
            super();
        }

        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                    "precision mediump float;",
                    "varying vec2 vUV;",
                    "uniform sampler2D uTexturePrev;",
                    "uniform sampler2D uTextureCurr;",
                    "uniform sampler2D uMotionTexture;",
                    "uniform int uIsEnabled;",
                    "uniform int uUseHardwareMotion;",
                    "uniform float uBlendFactor;",
                    "uniform vec2 resolution;",
                    "uniform int uUsePostProc;",
                    "uniform float uMotionScale;",
                    "",
                    "void main() {",
                    "    if (uIsEnabled == 1) {",
                    "        vec4 prev = texture2D(uTexturePrev, vUV);",
                    "        vec4 curr = texture2D(uTextureCurr, vUV);",
                    "        vec4 result;",
                    "        if (uUseHardwareMotion == 1) {",
                    "            vec2 motionPixels = texture2D(uMotionTexture, vUV).rg;",
                    "            vec2 motionUV = motionPixels / resolution / uMotionScale;",
                    "            vec2 uvPrev = clamp(vUV - motionUV * uBlendFactor, 0.0, 1.0);",
                    "            vec2 uvCurr = clamp(vUV + motionUV * (1.0 - uBlendFactor), 0.0, 1.0);",
                    "            vec4 sampledPrev = texture2D(uTexturePrev, uvPrev);",
                    "            vec4 sampledCurr = texture2D(uTextureCurr, uvCurr);",
                    "            result = mix(sampledPrev, sampledCurr, uBlendFactor);",
                    "        } else {",
                    "            result = mix(prev, curr, uBlendFactor);",
                    "        }",
                    "        if (uUsePostProc == 1) {",
                    "            float contrast = 1.04;",
                    "            result.rgb = ((result.rgb - 0.5) * contrast) + 0.5;",
                    "        }",
                    "        gl_FragColor = result;",
                    "    } else {",
                    "        vec4 prev = texture2D(uTexturePrev, vUV);",
                    "        if (uUsePostProc == 1) {",
                    "            float contrast = 1.04;",
                    "            prev.rgb = ((prev.rgb - 0.5) * contrast) + 0.5;",
                    "        }",
                    "        gl_FragColor = prev;",
                    "    }",
                    "}"
            });
        }
    }

    private static class BalancedFrameGenerationMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                    "precision mediump float;",
                    "varying vec2 vUV;",
                    "uniform sampler2D uTextureHistory;",
                    "uniform sampler2D uTexturePrev;",
                    "uniform sampler2D uTextureCurr;",
                    "uniform sampler2D uMotionTexture;",
                    "uniform int uIsEnabled;",
                    "uniform int uUseHardwareMotion;",
                    "uniform float uBlendFactor;",
                    "uniform vec2 resolution;",
                    "uniform int uUsePostProc;",
                    "uniform float uMotionScale;",

                    "vec2 fastMotionEstimate(vec2 uv) {",
                    "    if (uUseHardwareMotion == 1) {",
                    "        vec2 motionPixels = texture2D(uMotionTexture, uv).rg;",
                    "        return motionPixels / resolution / uMotionScale;",
                    "    }",
                    "    vec2 texel = 1.0 / resolution;",
                    "    float minDiff = 1.0;",
                    "    vec2 bestMotion = vec2(0.0);",
                    "    vec2 offsets[4];",
                    "    offsets[0] = vec2(1.0, 0.0) * texel;",
                    "    offsets[1] = vec2(-1.0, 0.0) * texel;",
                    "    offsets[2] = vec2(0.0, 1.0) * texel;",
                    "    offsets[3] = vec2(0.0, -1.0) * texel;",
                    "    vec3 centerColor = texture2D(uTexturePrev, uv).rgb;",
                    "    for (int i = 0; i < 4; i++) {",
                    "        vec2 sampleUV = uv + offsets[i];",
                    "        vec3 sampleColor = texture2D(uTextureHistory, sampleUV).rgb;",
                    "        float diff = distance(centerColor, sampleColor);",
                    "        if (diff < minDiff) {",
                    "            minDiff = diff;",
                    "            bestMotion = offsets[i];",
                    "        }",
                    "    }",
                    "    if (minDiff > 0.2) return vec2(0.0);",
                    "    return bestMotion;",
                    "}",

                    "float fastEdgeDetection(vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "    float center = texture2D(uTextureCurr, uv).r;",
                    "    float right = texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)).r;",
                    "    float left = texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)).r;",
                    "    float up = texture2D(uTextureCurr, uv + vec2(0.0, texel.y)).r;",
                    "    float down = texture2D(uTextureCurr, uv - vec2(0.0, texel.y)).r;",
                    "    float gx = right - left;",
                    "    float gy = up - down;",
                    "    return sqrt(gx * gx + gy * gy);",
                    "}",

                    "vec4 generateFrame(vec2 uv) {",
                    "    vec2 motionUV = fastMotionEstimate(uv);",
                    "    float motionLength = length(motionUV);",
                    "    ",
                    "    if (motionLength > 0.0001) {",
                    "        vec2 uvPrev = clamp(uv - motionUV * uBlendFactor, 0.0, 1.0);",
                    "        vec2 uvCurr = clamp(uv + motionUV * (1.0 - uBlendFactor), 0.0, 1.0);",
                    "        vec4 colorPrev = texture2D(uTexturePrev, uvPrev);",
                    "        vec4 colorCurr = texture2D(uTextureCurr, uvCurr);",
                    "        return mix(colorPrev, colorCurr, uBlendFactor);",
                    "    }",
                    "    vec4 colorPrev = texture2D(uTexturePrev, uv);",
                    "    vec4 colorCurr = texture2D(uTextureCurr, uv);",
                    "    return mix(colorPrev, colorCurr, uBlendFactor);",
                    "}",

                    "vec4 applySharpen(vec4 color, vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "    vec4 blurred = vec4(0.0);",
                    "    blurred += texture2D(uTextureCurr, uv) * 0.5;",
                    "    blurred += texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)) * 0.125;",
                    "    blurred += texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)) * 0.125;",
                    "    blurred += texture2D(uTextureCurr, uv + vec2(0.0, texel.y)) * 0.125;",
                    "    blurred += texture2D(uTextureCurr, uv - vec2(0.0, texel.y)) * 0.125;",
                    "    return color + (color - blurred) * 0.3;",
                    "}",

                    "void main() {",
                    "    vec2 uv = vUV;",
                    "    if (uIsEnabled == 1) {",
                    "        vec4 generated = generateFrame(uv);",
                    "        if (uUsePostProc == 1) {",
                    "            float luminance = dot(generated.rgb, vec3(0.299, 0.587, 0.114));",
                    "            float contrast = 1.05;",
                    "            generated.rgb = ((generated.rgb - 0.5) * contrast) + 0.5;",
                    "            generated.rgb = mix(vec3(luminance), generated.rgb, 1.05);",
                    "            float edge = fastEdgeDetection(uv);",
                    "            if (edge > 0.05) {",
                    "                generated = applySharpen(generated, uv);",
                    "            }",
                    "        }",
                    "        gl_FragColor = clamp(generated, 0.0, 1.0);",
                    "    } else {",
                    "        vec4 prev = texture2D(uTexturePrev, uv);",
                    "        if (uUsePostProc == 1) {",
                    "            float luminance = dot(prev.rgb, vec3(0.299, 0.587, 0.114));",
                    "            float contrast = 1.05;",
                    "            prev.rgb = ((prev.rgb - 0.5) * contrast) + 0.5;",
                    "            prev.rgb = mix(vec3(luminance), prev.rgb, 1.05);",
                    "        }",
                    "        gl_FragColor = prev;",
                    "    }",
                    "}"
            });
        }
    }

    private static class QualityFrameGenerationMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                    "precision mediump float;",
                    "varying vec2 vUV;",
                    "uniform sampler2D uTextureHistory;",
                    "uniform sampler2D uTexturePrev;",
                    "uniform sampler2D uTextureCurr;",
                    "uniform sampler2D uMotionTexture;",
                    "uniform int uIsEnabled;",
                    "uniform int uUseHardwareMotion;",
                    "uniform float uBlendFactor;",
                    "uniform vec2 resolution;",
                    "uniform int uUsePostProc;",
                    "uniform float uMotionScale;",

                    "vec2 enhancedMotionEstimate(vec2 uv) {",
                    "    if (uUseHardwareMotion == 1) {",
                    "        vec2 motionPixels = texture2D(uMotionTexture, uv).rg;",
                    "        return motionPixels / resolution / uMotionScale;",
                    "    }",
                    "    vec2 texel = 1.0 / resolution;",
                    "    float minDiff = 1.0;",
                    "    vec2 bestMotion = vec2(0.0);",
                    "    for (float dy = -1.0; dy <= 1.0; dy += 1.0) {",
                    "        for (float dx = -1.0; dx <= 1.0; dx += 1.0) {",
                    "            vec2 offset = vec2(dx, dy) * texel;",
                    "            float diff = 0.0;",
                    "            diff += distance(texture2D(uTexturePrev, uv).rgb, texture2D(uTextureHistory, uv + offset).rgb);",
                    "            diff += distance(texture2D(uTexturePrev, uv + vec2(texel.x, 0.0)).rgb, texture2D(uTextureHistory, uv + vec2(texel.x, 0.0) + offset).rgb) * 0.5;",
                    "            diff += distance(texture2D(uTexturePrev, uv - vec2(texel.x, 0.0)).rgb, texture2D(uTextureHistory, uv - vec2(texel.x, 0.0) + offset).rgb) * 0.5;",
                    "            diff += distance(texture2D(uTexturePrev, uv + vec2(0.0, texel.y)).rgb, texture2D(uTextureHistory, uv + vec2(0.0, texel.y) + offset).rgb) * 0.5;",
                    "            diff += distance(texture2D(uTexturePrev, uv - vec2(0.0, texel.y)).rgb, texture2D(uTextureHistory, uv - vec2(0.0, texel.y) + offset).rgb) * 0.5;",
                    "            float penalty = length(offset) * 0.15;",
                    "            diff += penalty;",
                    "            if (diff < minDiff) {",
                    "                minDiff = diff;",
                    "                bestMotion = offset;",
                    "            }",
                    "        }",
                    "    }",
                    "    if (minDiff > 0.3) return vec2(0.0);",
                    "    return bestMotion;",
                    "}",

                    "float enhancedEdgeDetection(vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "    float center = texture2D(uTextureCurr, uv).r;",
                    "    float right = texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)).r;",
                    "    float left = texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)).r;",
                    "    float up = texture2D(uTextureCurr, uv + vec2(0.0, texel.y)).r;",
                    "    float down = texture2D(uTextureCurr, uv - vec2(0.0, texel.y)).r;",
                    "    float gx = (right - left) * 2.0;",
                    "    float gy = (up - down) * 2.0;",
                    "    float edge = sqrt(gx * gx + gy * gy);",
                    "    return clamp(edge * 2.0, 0.0, 1.0);",
                    "}",

                    "float fastTextureDetection(vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "    vec3 c1 = texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)).rgb;",
                    "    vec3 c2 = texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)).rgb;",
                    "    vec3 c3 = texture2D(uTextureCurr, uv + vec2(0.0, texel.y)).rgb;",
                    "    vec3 c4 = texture2D(uTextureCurr, uv - vec2(0.0, texel.y)).rgb;",
                    "    float variation = 0.0;",
                    "    variation += distance(c1, c2);",
                    "    variation += distance(c3, c4);",
                    "    return clamp(variation * 2.0, 0.0, 1.0);",
                    "}",

                    "vec4 adaptiveBlending(vec2 uv, vec2 motion) {",
                    "    vec4 prev = texture2D(uTexturePrev, uv);",
                    "    vec4 curr = texture2D(uTextureCurr, uv);",
                    "    float motionLength = length(motion);",
                    "    float edgeStrength = enhancedEdgeDetection(uv);",
                    "    ",
                    "    if (motionLength > 0.0001) {",
                    "        vec2 uvPrev = clamp(uv - motion * uBlendFactor, vec2(0.0), vec2(1.0));",
                    "        vec2 uvCurr = clamp(uv + motion * (1.0 - uBlendFactor), vec2(0.0), vec2(1.0));",
                    "        vec4 motionPrev = texture2D(uTexturePrev, uvPrev);",
                    "        vec4 motionCurr = texture2D(uTextureCurr, uvCurr);",
                    "        float adaptiveBlend = uBlendFactor;",
                    "        if (edgeStrength > 0.2) {",
                    "            adaptiveBlend = mix(uBlendFactor, 0.5, edgeStrength);",
                    "        }",
                    "        return mix(motionPrev, motionCurr, adaptiveBlend);",
                    "    }",
                    "    float textureDetail = fastTextureDetection(uv);",
                    "    if (textureDetail < 0.1) {",
                    "        return mix(prev, curr, uBlendFactor);",
                    "    } else {",
                    "        vec2 texel = 1.0 / resolution;",
                    "        vec4 result = mix(prev, curr, uBlendFactor) * 0.5;",
                    "        result += texture2D(uTexturePrev, uv + vec2(texel.x * 0.5, 0.0)) * 0.125;",
                    "        result += texture2D(uTexturePrev, uv - vec2(texel.x * 0.5, 0.0)) * 0.125;",
                    "        result += texture2D(uTextureCurr, uv + vec2(0.0, texel.y * 0.5)) * 0.125;",
                    "        result += texture2D(uTextureCurr, uv - vec2(0.0, texel.y * 0.5)) * 0.125;",
                    "        return result;",
                    "    }",
                    "}",

                    "vec4 fastSharpen(vec4 color, vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "    vec4 blurred = color * 0.4;",
                    "    blurred += texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)) * 0.15;",
                    "    blurred += texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)) * 0.15;",
                    "    blurred += texture2D(uTextureCurr, uv + vec2(0.0, texel.y)) * 0.15;",
                    "    blurred += texture2D(uTextureCurr, uv - vec2(0.0, texel.y)) * 0.15;",
                    "    float amount = 0.4;",
                    "    vec4 sharpened = color + (color - blurred) * amount;",
                    "    return clamp(sharpened, 0.0, 1.0);",
                    "}",

                    "vec4 enhancedColorCorrection(vec4 color) {",
                    "    float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));",
                    "    float adaptiveContrast = 1.08;",
                    "    if (luminance > 0.8) adaptiveContrast = 1.04;",
                    "    if (luminance < 0.2) adaptiveContrast = 1.12;",
                    "    color.rgb = ((color.rgb - 0.5) * adaptiveContrast) + 0.5;",
                    "    float saturation = 1.06;",
                    "    vec3 gray = vec3(luminance);",
                    "    color.rgb = mix(gray, color.rgb, saturation);",
                    "    color.rgb = pow(color.rgb, vec3(0.98));",
                    "    return color;",
                    "}",

                    "void main() {",
                    "    vec2 uv = vUV;",
                    "    if (uIsEnabled != 1) {",
                    "        vec4 prev = texture2D(uTexturePrev, uv);",
                    "        if (uUsePostProc == 1) {",
                    "            prev = enhancedColorCorrection(prev);",
                    "            float edgeStrength = enhancedEdgeDetection(uv);",
                    "            if (edgeStrength > 0.15) {",
                    "                prev = fastSharpen(prev, uv);",
                    "            }",
                    "            float textureDetail = fastTextureDetection(uv);",
                    "            if (textureDetail < 0.08) {",
                    "                vec2 texel = 1.0 / resolution;",
                    "                prev = prev * 0.6;",
                    "                prev += texture2D(uTexturePrev, uv + vec2(texel.x, 0.0)) * 0.1;",
                    "                prev += texture2D(uTexturePrev, uv - vec2(texel.x, 0.0)) * 0.1;",
                    "                prev += texture2D(uTexturePrev, uv + vec2(0.0, texel.y)) * 0.1;",
                    "                prev += texture2D(uTexturePrev, uv - vec2(0.0, texel.y)) * 0.1;",
                    "            }",
                    "        }",
                    "        gl_FragColor = clamp(prev, 0.0, 1.0);",
                    "        return;",
                    "    }",
                    "    vec2 motion = enhancedMotionEstimate(uv);",
                    "    vec4 generated = adaptiveBlending(uv, motion);",
                    "    if (uUsePostProc == 1) {",
                    "        generated = enhancedColorCorrection(generated);",
                    "        float edgeStrength = enhancedEdgeDetection(uv);",
                    "        if (edgeStrength > 0.15) {",
                    "            generated = fastSharpen(generated, uv);",
                    "        }",
                    "        float textureDetail = fastTextureDetection(uv);",
                    "        if (textureDetail < 0.08) {",
                    "        vec2 texel = 1.0 / resolution;",
                    "            generated = generated * 0.6;",
                    "            generated += texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)) * 0.1;",
                    "            generated += texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)) * 0.1;",
                    "            generated += texture2D(uTextureCurr, uv + vec2(0.0, texel.y)) * 0.1;",
                    "            generated += texture2D(uTextureCurr, uv - vec2(0.0, texel.y)) * 0.1;",
                    "        }",
                    "    }",
                    "    gl_FragColor = clamp(generated, 0.0, 1.0);",
                    "}"
            });
        }
    }
}