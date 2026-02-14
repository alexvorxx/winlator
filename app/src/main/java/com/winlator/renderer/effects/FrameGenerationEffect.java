package com.winlator.renderer.effects;

import com.winlator.renderer.EffectComposer;
import com.winlator.renderer.material.ScreenMaterial;
import com.winlator.renderer.material.ShaderMaterial;
import android.opengl.GLES20;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class FrameGenerationEffect extends Effect {
    public static final int MODE_FAST = 0;
    public static final int MODE_BALANCED = 1;
    public static final int MODE_QUALITY = 2;

    private int currentMode = MODE_BALANCED;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    // Timing
    private long lastRealFrameTimeNs = 0;
    private long lastAnyFrameShownTimeNs = 0;
    private long nextFrameTimeNs = 0;

    private long currentRealFrameIntervalNs = 33333333;  // 30 FPS
    private long currentTargetFrameIntervalNs = 16666666;  // 60 FPS

    private static final long MIN_FRAME_INTERVAL_NS = 8 * NANOS_PER_MILLISECOND;  // 8ms
    private static final long MAX_FRAME_INTERVAL_NS = 1000 * NANOS_PER_MILLISECOND;  // 1000ms

    private boolean isEnabled = false;
    private float blendFactor = 0.5f;

    // Buffers for frames
    private int texturePrev = -1;
    private int textureCurr = -1;

    // Flags
    private boolean hasFirstFrame = false;
    private boolean hasSecondFrame = false;
    private boolean waitingForSecondFrame = true;

    // FPS
    public static final int FPS_AUTO = 0;
    public static final int FPS_15 = 15;
    public static final int FPS_20 = 20;
    public static final int FPS_25 = 25;
    public static final int FPS_30 = 30;
    public static final int FPS_45 = 45;
    public static final int FPS_60 = 60;

    private int targetFPS = FPS_30;
    private boolean autoDetectFPS = false;

    private List<Long> realFrameIntervals = new ArrayList<>();
    private static final int FRAME_HISTORY_SIZE = 10;

    private static final String TAG = "FrameGeneration";

    // Uniform locations
    //private boolean uniformsCached = false;
    public int uIsEnabledLoc = -1;
    private int uBlendFactorLoc = -1;
    private int uTexturePrevLoc = -1;
    private int uTextureCurrLoc = -1;
    private int uResolutionLoc = -1;

    // Display refresh rate
    private int displayRefreshRate = 60; // 60 Hz
    private int realFrameDisplayCount = 0;
    private int generatedFrameDisplayCount = 0;

    // Current frame to display
    private int currentDisplayFrameType = 0; // 0 - real, 1 - generated
    private int currentFrameDisplayCount = 0;

    private int currentSequence = 0;

    private boolean currentRealFrameCaptured = false;
    private int currentRealFrameIndex = 0;

    private int capturedRealFrame = -1;
    private boolean hasCapturedFrame = false;
    private boolean skipFirstRealDisplay = false;

    private int currentWidth = 0;
    private int currentHeight = 0;

    private void LogString(String message) {
        if (EffectComposer.logEnabled)
            Log.d(TAG, message);
    }

    public FrameGenerationEffect() {
        super();
        updateFrameIntervals();
        calculateDisplayCounts();
        LogString("Effect created with target FPS: " + targetFPS);
    }

    @Override
    protected ShaderMaterial createMaterial() {
        switch (currentMode) {
            case MODE_FAST:
                Log.d(TAG, "Fast generation mode selected");
                return new FastFrameGenerationMaterial();
            case MODE_QUALITY:
                Log.d(TAG, "Quality generation mode selected");
                return new QualityFrameGenerationMaterial();
            case MODE_BALANCED:
            default:
                Log.d(TAG, "Balanced generation mode selected");
                return new OptimizedFrameGenerationMaterial();
        }
    }

    public void setGenerationMode(int mode) {
        if (this.currentMode != mode) {
            this.currentMode = mode;

            cleanup();
            resetState();
        }
    }

    public int getCurrentMode() {
        return currentMode;
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
        }
    }

    private void updateFrameIntervals() {
        currentRealFrameIntervalNs = NANOS_PER_SECOND / targetFPS;
        currentTargetFrameIntervalNs = currentRealFrameIntervalNs / 2;

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

        generatedFrameDisplayCount = (int) Math.max(1, currentTargetFrameIntervalNs / frameDurationNs);

        LogString(String.format("Display counts: real=%d, generated=%d (refresh rate=%d Hz)",
                realFrameDisplayCount, generatedFrameDisplayCount, displayRefreshRate));
    }

    private long calculateAverageFrameInterval() {
        if (realFrameIntervals.isEmpty()) {
            //return 67 * NANOS_PER_MILLISECOND;  // 67 ms
            return 33333333;  // 33.333333 ms
        }

        long sum = 0;
        for (long interval : realFrameIntervals) {
            sum += interval;
        }

        long average = sum / realFrameIntervals.size();
        return Math.max(MIN_FRAME_INTERVAL_NS, Math.min(average, MAX_FRAME_INTERVAL_NS));
    }

    private void clearHistory() {
        if (texturePrev != -1) {
            GLES20.glDeleteTextures(1, new int[]{texturePrev}, 0);
        }
        if (textureCurr != -1) {
            GLES20.glDeleteTextures(1, new int[]{textureCurr}, 0);
        }
        if (capturedRealFrame != -1) {
            GLES20.glDeleteTextures(1, new int[]{capturedRealFrame}, 0);
        }
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
                if (texturePrev != -1) {
                    GLES20.glDeleteTextures(1, new int[]{texturePrev}, 0);
                }
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

        this.targetFPS = fps;
        updateFrameIntervals();
    }

    public void setTargetFPS(int fps) {
        if (fps == FPS_AUTO) {
            autoDetectFPS = true;
            LogString("Auto FPS detection enabled");
        } else {
            autoDetectFPS = false;
            this.targetFPS = fps;
            LogString("Target FPS set to: " + fps);
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

    public int getTargetFPS() {
        return targetFPS;
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
                /*blendFactor = Math.min(1.0f, Math.max(0.0f,
                        (float)timeSinceRealFrameNs / currentRealFrameIntervalNs));*/

                lastAnyFrameShownTimeNs = currentTimeNs;

                LogString(String.format("Generated: prev=%d, curr=%d, blend=%.3f (time since real: %.2fms)",
                        texturePrev, textureCurr, blendFactor,
                        timeSinceRealFrameNs / (double)NANOS_PER_MILLISECOND));
            } else {
                LogString("Not enough frames for generation yet");
            }
        }
    }

    public void setupShaderUniforms() {
        ShaderMaterial material = getMaterial();
        if (material == null || material.getProgram() == 0) return;

        int program = material.getProgram();

        if (uIsEnabledLoc == -1) {
            //if (!uniformsCached) {
                uIsEnabledLoc = GLES20.glGetUniformLocation(program, "uIsEnabled");
                uBlendFactorLoc = GLES20.glGetUniformLocation(program, "uBlendFactor");
                uTexturePrevLoc = GLES20.glGetUniformLocation(program, "uTexturePrev");
                uTextureCurrLoc = GLES20.glGetUniformLocation(program, "uTextureCurr");
                uResolutionLoc = GLES20.glGetUniformLocation(program, "resolution");
                //uniformsCached = true;
            //}
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        if (texturePrev != -1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texturePrev);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glUniform1i(uTexturePrevLoc, 1);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        if (textureCurr != -1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureCurr);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glUniform1i(uTextureCurrLoc, 2);

        if (uResolutionLoc != -1 && currentWidth > 0 && currentHeight > 0) {
            GLES20.glUniform2f(uResolutionLoc, currentWidth, currentHeight);
        }

        GLES20.glUniform1i(uIsEnabledLoc, 0);
        GLES20.glUniform1f(uBlendFactorLoc, 0.0f);

        if (isEnabled && currentSequence == 1) {
            boolean canShowGenerated = texturePrev != -1 && textureCurr != -1 && !waitingForSecondFrame;

            if (canShowGenerated) {
                GLES20.glUniform1i(uIsEnabledLoc, 1);
                GLES20.glUniform1f(uBlendFactorLoc, blendFactor);

                long currentTimeNs = System.nanoTime();
                long timeSinceRealNs = currentTimeNs - lastRealFrameTimeNs;
                LogString(String.format("Showing GENERATED frame, resolution=%dx%d, blend=%.3f (%.2fms since real)",
                        currentWidth, currentHeight, blendFactor, timeSinceRealNs / (double)NANOS_PER_MILLISECOND));
            } else {
                LogString("Cannot show generated, showing REAL frame instead");
                GLES20.glUniform1i(uIsEnabledLoc, 0);
                GLES20.glUniform1f(uBlendFactorLoc, 0.0f);
            }
        } else {
            GLES20.glUniform1i(uIsEnabledLoc, 0);
            GLES20.glUniform1f(uBlendFactorLoc, 0.0f);
            LogString(String.format("Showing REAL frame, resolution=%dx%d (sequence=%d, enabled=%b)",
                    currentWidth, currentHeight, currentSequence, isEnabled));
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    public void cleanup() {
        clearHistory();
        LogString("Effect cleaned up");
    }

    public boolean isEnabled() { return isEnabled; }

    private int captureCurrentFrameSimple(int width, int height) {
        LogString("Using SIMPLE capture method");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int newTexture = textures[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, newTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null);

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0,
                0, 0,
                0, 0,
                width, height);

        LogString("Simple capture completed, texture: " + newTexture);

        return newTexture;
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

    private class FastFrameGenerationMaterial extends ScreenMaterial {
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
                    "uniform int uIsEnabled;",
                    "uniform float uBlendFactor;",
                    "",
                    "void main() {",
                    "    if (uIsEnabled == 1) {",
                    "        // Simple linear mixing",
                    "        vec4 prev = texture2D(uTexturePrev, vUV);",
                    "        vec4 curr = texture2D(uTextureCurr, vUV);",
                    "        vec4 result = mix(prev, curr, uBlendFactor);",
                    "",
                    "        // Minimal post-processing",
                    "        float contrast = 1.04;",
                    "        result.rgb = ((result.rgb - 0.5) * contrast) + 0.5;",
                    "",
                    "        gl_FragColor = result;",
                    "    } else {",
                    "        //gl_FragColor = texture2D(uTextureCurr, vUV);",
                    "        vec4 prev = texture2D(uTexturePrev, vUV);",
                    "        // Minimal post-processing",
                    "        float contrast = 1.04;",
                    "        prev.rgb = ((prev.rgb - 0.5) * contrast) + 0.5;",
                    "        gl_FragColor = prev;",
                    "    }",
                    "}"
            });
        }
    }

    private class OptimizedFrameGenerationMaterial extends ScreenMaterial {
        public OptimizedFrameGenerationMaterial() {
            super();
        }

        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                    "precision mediump float;",
                    "varying vec2 vUV;",
                    "uniform sampler2D uTexturePrev;",
                    "uniform sampler2D uTextureCurr;",
                    "uniform int uIsEnabled;",
                    "uniform float uBlendFactor;",
                    "uniform vec2 resolution;",
                    "",
                    "// Quick motion assessment (simplified version)",
                    "vec2 fastMotionEstimate(vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "    float minDiff = 1.0;",
                    "    vec2 bestMotion = vec2(0.0);",
                    "",
                    "    // We check only 4 main directions (right, left, up, down)",
                    "    vec2 offsets[4];",
                    "    offsets[0] = vec2(1.0, 0.0) * texel;",
                    "    offsets[1] = vec2(-1.0, 0.0) * texel;",
                    "    offsets[2] = vec2(0.0, 1.0) * texel;",
                    "    offsets[3] = vec2(0.0, -1.0) * texel;",
                    "",
                    "    vec3 centerColor = texture2D(uTextureCurr, uv).rgb;",
                    "",
                    "    for (int i = 0; i < 4; i++) {",
                    "        vec2 sampleUV = uv + offsets[i];",
                    "        vec3 sampleColor = texture2D(uTexturePrev, sampleUV).rgb;",
                    "        float diff = distance(centerColor, sampleColor);",
                    "",
                    "        if (diff < minDiff) {",
                    "            minDiff = diff;",
                    "            bestMotion = offsets[i];",
                    "        }",
                    "    }",
                    "",
                    "    // If the difference is too big, we assume that there is no movement",
                    "    if (minDiff > 0.2) {",
                    "        return vec2(0.0);",
                    "    }",
                    "",
                    "    return bestMotion;",
                    "}",
                    "",
                    "// Fast edge detector (brightness only)",
                    "float fastEdgeDetection(vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "",
                    "    // We only use brightness for speed",
                    "    float center = texture2D(uTextureCurr, uv).r;",
                    "    float right = texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)).r;",
                    "    float left = texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)).r;",
                    "    float up = texture2D(uTextureCurr, uv + vec2(0.0, texel.y)).r;",
                    "    float down = texture2D(uTextureCurr, uv - vec2(0.0, texel.y)).r;",
                    "",
                    "    // Simple Sobel",
                    "    float gx = right - left;",
                    "    float gy = up - down;",
                    "",
                    "    return sqrt(gx * gx + gy * gy);",
                    "}",
                    "",
                    "// The main function of frame generation",
                    "vec4 generateFrame(vec2 uv) {",
                    "    if (uIsEnabled != 1) {",
                    "        return texture2D(uTextureCurr, uv);",
                    "    }",
                    "",
                    "    // 1. Textures",
                    "    vec4 colorPrev = texture2D(uTexturePrev, uv);",
                    "    vec4 colorCurr = texture2D(uTextureCurr, uv);",
                    "",
                    "    // 2. Quick motion assessment",
                    "    vec2 motion = fastMotionEstimate(uv);",
                    "    float motionLength = length(motion);",
                    "",
                    "    // 3. Edge detection",
                    "    float edgeStrength = fastEdgeDetection(uv);",
                    "",
                    "    // 4. Frame feneration",
                    "    if (motionLength > 0.001) {",
                    "        // There is movement - adjust the UV coordinates",
                    "        float motionScale = 0.5;",
                    "        vec2 adjustedUV = uv + motion * motionScale * uBlendFactor;",
                    "        ",
                    "        // Checking the boundaries",
                    "        if (adjustedUV.x < 0.0 || adjustedUV.x > 1.0 || ",
                    "            adjustedUV.y < 0.0 || adjustedUV.y > 1.0) {",
                    "            adjustedUV = uv;",
                    "        }",
                    "        ",
                    "        vec4 motionAdjusted = texture2D(uTexturePrev, adjustedUV);",
                    "        return mix(motionAdjusted, colorCurr, 0.5);",
                    "    } else if (edgeStrength > 0.1) {",
                    "        // Sharp edges - less interpolation",
                    "        return mix(colorPrev, colorCurr, uBlendFactor * 0.5);",
                    "    } else {",
                    "        // Regular interpolation for smooth areas",
                    "        return mix(colorPrev, colorCurr, uBlendFactor);",
                    "    }",
                    "}",
                    "",
                    "// Simple sharpness improvement",
                    "vec4 applySharpen(vec4 color, vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "    vec4 blurred = vec4(0.0);",
                    "    blurred += texture2D(uTextureCurr, uv) * 0.5;",
                    "    blurred += texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)) * 0.125;",
                    "    blurred += texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)) * 0.125;",
                    "    blurred += texture2D(uTextureCurr, uv + vec2(0.0, texel.y)) * 0.125;",
                    "    blurred += texture2D(uTextureCurr, uv - vec2(0.0, texel.y)) * 0.125;",
                    "",
                    "    // Unsharp mask",
                    "    return color + (color - blurred) * 0.3;",
                    "}",
                    "",
                    "void main() {",
                    "    vec2 uv = vUV;",
                    "",
                    "    if (uIsEnabled == 1) {",
                    "        // Generate frame",
                    "        vec4 generated = generateFrame(uv);",
                    "",
                    "        // Easy post-processing",
                    "        float luminance = dot(generated.rgb, vec3(0.299, 0.587, 0.114));",
                    "        ",
                    "        // Auto-contrast",
                    "        float contrast = 1.05;",
                    "        generated.rgb = ((generated.rgb - 0.5) * contrast) + 0.5;",
                    "        ",
                    "        // Light saturation",
                    "        generated.rgb = mix(vec3(luminance), generated.rgb, 1.05);",
                    "        ",
                    "        // Slight sharpening (only if there are edges)",
                    "        float edge = fastEdgeDetection(uv);",
                    "        if (edge > 0.05) {",
                    "            generated = applySharpen(generated, uv);",
                    "        }",
                    "        ",
                    "        gl_FragColor = clamp(generated, 0.0, 1.0);",
                    "    } else {",
                    "        // Real frame",
                    "        //gl_FragColor = texture2D(uTextureCurr, uv);",
                    "        vec4 prev = texture2D(uTexturePrev, uv);",
                    "",
                    "        // Easy post-processing",
                    "        float luminance = dot(prev.rgb, vec3(0.299, 0.587, 0.114));",
                    "        ",
                    "        // Auto-contrast",
                    "        float contrast = 1.05;",
                    "        prev.rgb = ((prev.rgb - 0.5) * contrast) + 0.5;",
                    "        ",
                    "        // Light saturation",
                    "        prev.rgb = mix(vec3(luminance), prev.rgb, 1.05);",
                    "        gl_FragColor = prev;",
                    "    }",
                    "}"
            });
        }
    }

    private class QualityFrameGenerationMaterial extends ScreenMaterial {
        public QualityFrameGenerationMaterial() {
            super();
        }

        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                    "precision mediump float;",
                    "varying vec2 vUV;",
                    "uniform sampler2D uTexturePrev;",
                    "uniform sampler2D uTextureCurr;",
                    "uniform int uIsEnabled;",
                    "uniform float uBlendFactor;",
                    "uniform vec2 resolution;",
                    "",
                    "// Improved motion detector (3x3 search)",
                    "vec2 enhancedMotionEstimate(vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "    float minDiff = 1.0;",
                    "    vec2 bestMotion = vec2(0.0);",
                    "",
                    "    // Search within a radius of 1.5 pixels (9 directions)",
                    "    for (float dy = -1.0; dy <= 1.0; dy += 1.0) {",
                    "        for (float dx = -1.0; dx <= 1.0; dx += 1.0) {",
                    "            vec2 offset = vec2(dx, dy) * texel;",
                    "",
                    "            // Comparing the central pixel and 4 neighbors",
                    "            float diff = 0.0;",
                    "            diff += distance(texture2D(uTextureCurr, uv).rgb, ",
                    "                             texture2D(uTexturePrev, uv + offset).rgb);",
                    "            ",
                    "            // Adding 4 adjacent pixels for better accuracy",
                    "            diff += distance(texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)).rgb, ",
                    "                             texture2D(uTexturePrev, uv + vec2(texel.x, 0.0) + offset).rgb) * 0.5;",
                    "            diff += distance(texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)).rgb, ",
                    "                             texture2D(uTexturePrev, uv - vec2(texel.x, 0.0) + offset).rgb) * 0.5;",
                    "            diff += distance(texture2D(uTextureCurr, uv + vec2(0.0, texel.y)).rgb, ",
                    "                             texture2D(uTexturePrev, uv + vec2(0.0, texel.y) + offset).rgb) * 0.5;",
                    "            diff += distance(texture2D(uTextureCurr, uv - vec2(0.0, texel.y)).rgb, ",
                    "                             texture2D(uTexturePrev, uv - vec2(0.0, texel.y) + offset).rgb) * 0.5;",
                    "",
                    "            // Taking into account the length of the offset",
                    "            float penalty = length(offset) * 0.15;",
                    "            diff = diff + penalty;",
                    "",
                    "            if (diff < minDiff) {",
                    "                minDiff = diff;",
                    "                bestMotion = offset;",
                    "            }",
                    "        }",
                    "    }",
                    "",
                    "    // Noise filtering",
                    "    if (minDiff > 0.3) {",
                    "        return vec2(0.0);",
                    "    }",
                    "",
                    "    return bestMotion;",
                    "}",
                    "",
                    "// Improved Edge Detector (simplified Sobel)",
                    "float enhancedEdgeDetection(vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "",
                    "    // Simple Sobel 3x3 (only 4 neighbors for speed)",
                    "    float center = texture2D(uTextureCurr, uv).r;",
                    "    float right = texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)).r;",
                    "    float left = texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)).r;",
                    "    float up = texture2D(uTextureCurr, uv + vec2(0.0, texel.y)).r;",
                    "    float down = texture2D(uTextureCurr, uv - vec2(0.0, texel.y)).r;",
                    "",
                    "    float gx = (right - left) * 2.0;",
                    "    float gy = (up - down) * 2.0;",
                    "",
                    "    float edge = sqrt(gx * gx + gy * gy);",
                    "    return clamp(edge * 2.0, 0.0, 1.0);",
                    "}",
                    "",
                    "// Quick texture assessment (local variation)",
                    "float fastTextureDetection(vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "",
                    "    // We take 4 neighboring pixels",
                    "    vec3 c1 = texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)).rgb;",
                    "    vec3 c2 = texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)).rgb;",
                    "    vec3 c3 = texture2D(uTextureCurr, uv + vec2(0.0, texel.y)).rgb;",
                    "    vec3 c4 = texture2D(uTextureCurr, uv - vec2(0.0, texel.y)).rgb;",
                    "",
                    "    // Evaluating the variation",
                    "    float variation = 0.0;",
                    "    variation += distance(c1, c2);",
                    "    variation += distance(c3, c4);",
                    "",
                    "    return clamp(variation * 2.0, 0.0, 1.0);",
                    "}",
                    "",
                    "// Adaptive mixing",
                    "vec4 adaptiveBlending(vec2 uv, vec2 motion) {",
                    "    vec4 prev = texture2D(uTexturePrev, uv);",
                    "    vec4 curr = texture2D(uTextureCurr, uv);",
                    "",
                    "    float motionLength = length(motion);",
                    "    float edgeStrength = enhancedEdgeDetection(uv);",
                    "",
                    "    // For moving objects",
                    "    if (motionLength > 0.001) {",
                    "        vec2 motionDir = normalize(motion);",
                    "        float motionScale = 0.5;",
                    "",
                    "        // Adjusting the UV to reflect the movement",
                    "        vec2 adjustedUV = uv + motionDir * motionScale * uBlendFactor;",
                    "",
                    "        // Checking boundaries",
                    "        if (adjustedUV.x < 0.0 || adjustedUV.x > 1.0 || ",
                    "            adjustedUV.y < 0.0 || adjustedUV.y > 1.0) {",
                    "            adjustedUV = uv;",
                    "        }",
                    "",
                    "        vec4 motionAdjusted = texture2D(uTexturePrev, adjustedUV);",
                    "",
                    "        // Adaptive blend factor for edges",
                    "        float adaptiveBlend = uBlendFactor;",
                    "        if (edgeStrength > 0.2) {",
                    "            adaptiveBlend = mix(uBlendFactor, 0.5, edgeStrength);",
                    "        }",
                    "",
                    "        return mix(motionAdjusted, curr, adaptiveBlend);",
                    "    }",
                    "",
                    "    // For static scenes",
                    "    float textureDetail = fastTextureDetection(uv);",
                    "",
                    "    if (textureDetail < 0.1) {",
                    "        // Homogeneous areas - smooth interpolation",
                    "        return mix(prev, curr, uBlendFactor);",
                    "    } else {",
                    "        // Texture areas - improved interpolation",
                    "        // Add some neighboring pixels for smoothing",
                    "        vec2 texel = 1.0 / resolution;",
                    "        vec4 result = mix(prev, curr, uBlendFactor) * 0.5;",
                    "        result += texture2D(uTexturePrev, uv + vec2(texel.x * 0.5, 0.0)) * 0.125;",
                    "        result += texture2D(uTexturePrev, uv - vec2(texel.x * 0.5, 0.0)) * 0.125;",
                    "        result += texture2D(uTextureCurr, uv + vec2(0.0, texel.y * 0.5)) * 0.125;",
                    "        result += texture2D(uTextureCurr, uv - vec2(0.0, texel.y * 0.5)) * 0.125;",
                    "",
                    "        return result;",
                    "    }",
                    "}",
                    "",
                    "// Improved sharpness filter (fast unsharp mask)",
                    "vec4 fastSharpen(vec4 color, vec2 uv) {",
                    "    vec2 texel = 1.0 / resolution;",
                    "",
                    "    // Simple blur (cross)",
                    "    vec4 blurred = color * 0.4;",
                    "    blurred += texture2D(uTextureCurr, uv + vec2(texel.x, 0.0)) * 0.15;",
                    "    blurred += texture2D(uTextureCurr, uv - vec2(texel.x, 0.0)) * 0.15;",
                    "    blurred += texture2D(uTextureCurr, uv + vec2(0.0, texel.y)) * 0.15;",
                    "    blurred += texture2D(uTextureCurr, uv - vec2(0.0, texel.y)) * 0.15;",
                    "",
                    "    // Unsharp mask",
                    "    float amount = 0.4;",
                    "    vec4 sharpened = color + (color - blurred) * amount;",
                    "",
                    "    return clamp(sharpened, 0.0, 1.0);",
                    "}",
                    "",
                    "// Improved color correction",
                    "vec4 enhancedColorCorrection(vec4 color) {",
                    "    // Automatic contrast",
                    "    float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));",
                    "",
                    "    // Adaptive contrast",
                    "    float adaptiveContrast = 1.08;",
                    "    if (luminance > 0.8) adaptiveContrast = 1.04;",
                    "    if (luminance < 0.2) adaptiveContrast = 1.12;",
                    "",
                    "    color.rgb = ((color.rgb - 0.5) * adaptiveContrast) + 0.5;",
                    "",
                    "    // Light saturation",
                    "    float saturation = 1.06;",
                    "    vec3 gray = vec3(luminance);",
                    "    color.rgb = mix(gray, color.rgb, saturation);",
                    "",
                    "    // Light gamma correction",
                    "    color.rgb = pow(color.rgb, vec3(0.98));",
                    "",
                    "    return color;",
                    "}",
                    "",
                    "// Main function",
                    "void main() {",
                    "    if (uIsEnabled != 1) {",
                    "        //gl_FragColor = texture2D(uTextureCurr, vUV);",
                    "        vec4 prev = texture2D(uTexturePrev, vUV);",
                    "        // 3. Post-processing",
                    "        prev = enhancedColorCorrection(prev);",
                    "",
                    "        // 4. Conditional sharpening",
                    "        float edgeStrength = enhancedEdgeDetection(vUV);",
                    "        if (edgeStrength > 0.15) {",
                    "            prev = fastSharpen(prev, vUV);",
                    "        }",
                    "",
                    "        // 5. Light noise reduction for homogeneous areas",
                    "        float textureDetail = fastTextureDetection(vUV);",
                    "        if (textureDetail < 0.08) {",
                    "            vec2 texel = 1.0 / resolution;",
                    "            prev = prev * 0.6;",
                    "            prev += texture2D(uTexturePrev, vUV + vec2(texel.x, 0.0)) * 0.1;",
                    "            prev += texture2D(uTexturePrev, vUV - vec2(texel.x, 0.0)) * 0.1;",
                    "            prev += texture2D(uTexturePrev, vUV + vec2(0.0, texel.y)) * 0.1;",
                    "            prev += texture2D(uTexturePrev, vUV - vec2(0.0, texel.y)) * 0.1;",
                    "        }",
                    "",
                    "        gl_FragColor = clamp(prev, 0.0, 1.0);",
                    "        return;",
                    "    }",
                    "",
                    "    // 1. Motion assessment",
                    "    vec2 motion = enhancedMotionEstimate(vUV);",
                    "",
                    "    // 2. Adaptive mixing",
                    "    vec4 generated = adaptiveBlending(vUV, motion);",
                    "",
                    "    // 3. Post-processing",
                    "    generated = enhancedColorCorrection(generated);",
                    "",
                    "    // 4. Conditional sharpening",
                    "    float edgeStrength = enhancedEdgeDetection(vUV);",
                    "    if (edgeStrength > 0.15) {",
                    "        generated = fastSharpen(generated, vUV);",
                    "    }",
                    "",
                    "    // 5. Light noise reduction for homogeneous areas",
                    "    float textureDetail = fastTextureDetection(vUV);",
                    "    if (textureDetail < 0.08) {",
                    "        vec2 texel = 1.0 / resolution;",
                    "        generated = generated * 0.6;",
                    "        generated += texture2D(uTextureCurr, vUV + vec2(texel.x, 0.0)) * 0.1;",
                    "        generated += texture2D(uTextureCurr, vUV - vec2(texel.x, 0.0)) * 0.1;",
                    "        generated += texture2D(uTextureCurr, vUV + vec2(0.0, texel.y)) * 0.1;",
                    "        generated += texture2D(uTextureCurr, vUV - vec2(0.0, texel.y)) * 0.1;",
                    "    }",
                    "",
                    "    gl_FragColor = clamp(generated, 0.0, 1.0);",
                    "}"
            });
        }
    }

}