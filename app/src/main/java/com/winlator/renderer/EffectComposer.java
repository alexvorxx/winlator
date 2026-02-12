package com.winlator.renderer;

import android.opengl.GLES20;
import android.util.Log;

import com.winlator.renderer.effects.Effect;
import com.winlator.renderer.effects.FrameGenerationEffect;
import com.winlator.renderer.effects.ToonEffect;
import com.winlator.renderer.material.ShaderMaterial;

import java.util.ArrayList;
import java.util.List;

public class EffectComposer {
    // Constants
    private static final String TAG = "EffectComposer";
    private boolean isRendering = false;

    // Instance fields
    private final List<Effect> effects = new ArrayList<>();
    private RenderTarget readBuffer;
    private RenderTarget writeBuffer;
    private final GLRenderer renderer;

    private FrameGenerationEffect frameGenerationEffect;

    public static final boolean logEnabled = false;

    private void LogString(String message) {
        if (logEnabled)
            Log.d(TAG, message);
    }

    // Constructor
    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    // Initializes the buffers if they are not already initialized
    private void initBuffers() {
        if (readBuffer == null) {
            readBuffer = new RenderTarget();
            readBuffer.allocateFramebuffer(renderer.getSurfaceWidth(), renderer.getSurfaceHeight());
        }

        if (writeBuffer == null) {
            writeBuffer = new RenderTarget();
            writeBuffer.allocateFramebuffer(renderer.getSurfaceWidth(), renderer.getSurfaceHeight());
        }
    }

    public synchronized void addEffect(Effect effect) {
        if (frameGenerationEffect != null)
            return;
        if (!effects.contains(effect)) {
            effects.add(effect);
            if (effect instanceof FrameGenerationEffect) {
                frameGenerationEffect = (FrameGenerationEffect) effect;
                Log.d(TAG, "FrameGenerationEffect added");
            }
        }
        renderer.xServerView.requestRender();
    }

    public synchronized <T extends Effect> T getEffect(Class<T> effectClass) {
        for (Effect effect : effects) {
            if (effect.getClass() == effectClass) {
                return effectClass.cast(effect);
            }
        }
        return null;
    }

    public synchronized boolean hasEffects() {
        return !effects.isEmpty();
    }

    public synchronized void removeEffect(Effect effect) {
        if (effects.remove(effect)) {
            if (effect == frameGenerationEffect) {
                frameGenerationEffect = null;
            }
        }
        renderer.xServerView.requestRender();
    }

    private int determineFrameSequence() {
        if (frameGenerationEffect != null && frameGenerationEffect.isEnabled()) {
            // Frame type
            int frameType = frameGenerationEffect.getFrameToDisplay();

            if (frameType == 1 && !frameGenerationEffect.isReadyForGeneration()) {
                LogString("Generation not ready yet, showing real frame instead");
                return 0;
            }

            //LogString("Frame sequence determined: " + frameType);
            return frameType;
        } else {
            return 0;
        }
    }

    // Renders all the effects in the composer
    public synchronized void render() {
        if (isRendering) {
            //LogString("Already rendering, skipping");
            return;
        }

        isRendering = true;

        try {
            initBuffers();

            int currentSequence = determineFrameSequence();
            //LogString("Current sequence: " + currentSequence);

            // Set up framebuffer
            if (hasEffects()) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readBuffer.getFramebuffer());
            } else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            }

            // Clear
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Draw the initial frame
            renderer.drawFrame();

            for (int i = 0; i < effects.size(); i++) {
                Effect effect = effects.get(i);
                boolean renderToScreen = (i == effects.size() - 1);
                int targetFramebuffer = renderToScreen ? 0 : writeBuffer.getFramebuffer();

                if (effect == frameGenerationEffect && frameGenerationEffect != null) {
                    // FrameGenerationEffect only
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer);
                    GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
                    renderer.setViewportNeedsUpdate(true);

                    // Do not clear buffer

                    frameGenerationEffect.prepareFrame(
                            renderer.surfaceWidth,
                            renderer.surfaceHeight,
                            currentSequence
                    );

                    effect.getMaterial().use();

                    frameGenerationEffect.setupShaderUniforms();

                    renderEffect(effect);

                    if (!renderToScreen) {
                        swapBuffers();
                    }

                } else {
                    // Other effects
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFramebuffer);
                    GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
                    renderer.setViewportNeedsUpdate(true);

                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                    renderEffect(effect);

                    swapBuffers();
                }
            }

            renderer.xServerView.requestRender();

        } finally {
            isRendering = false;
        }
    }

    // Renders a single effect
    private void renderEffect(Effect effect) {
        ShaderMaterial material = effect.getMaterial();
        if (material == null) {
            return;
        }

        material.use();

        // FrameGenerationEffect only
        if (effect instanceof FrameGenerationEffect) {
            FrameGenerationEffect interpEffect = (FrameGenerationEffect) effect;
            interpEffect.setupShaderUniforms();

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            int[] boundTex1 = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, boundTex1, 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            int[] boundTex2 = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, boundTex2, 0);

            LogString("Textures bound - unit1: " + boundTex1[0] + ", unit2: " + boundTex2[0]);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        } else {
            // Other effects
            material.setUniformVec2("resolution", renderer.surfaceWidth, renderer.surfaceHeight);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readBuffer.getTextureId());
            material.setUniformInt("screenTexture", 0);
        }

        // Bind the quad vertices to the shader program
        renderer.getQuadVertices().bind(material.programId);

        // Draw the quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());

        // Unbind the texture
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    // Swaps the read and write buffers
    private void swapBuffers() {
        RenderTarget tmp = writeBuffer;
        writeBuffer = readBuffer;
        readBuffer = tmp;
    }

    // Add a method to add the ToonEffect
    public synchronized void toggleToonEffect() {
        ToonEffect toonEffect = getEffect(ToonEffect.class);
        if (toonEffect != null) {
            removeEffect(toonEffect);
            LogString("ToonEffect removed");
        } else {
            addEffect(new ToonEffect());
            LogString("ToonEffect added");
        }
        renderer.xServerView.requestRender();
    }

    public synchronized void configureFrameGeneration(int targetFPS, int mode) {
        if (frameGenerationEffect != null) {
            frameGenerationEffect.setTargetFPS(targetFPS);
            frameGenerationEffect.setGenerationMode(mode);
        }
        renderer.xServerView.requestRender();
    }

    public void setDisplayRefreshRate(int refreshRate) {
        if (frameGenerationEffect != null) {
            frameGenerationEffect.setDisplayRefreshRate(refreshRate);
        }
    }

    public void setGenerationMode(int mode) {
        if (frameGenerationEffect != null) {
            if (frameGenerationEffect.isEnabled()) {
                Log.d(TAG, "FrameGenerationEffect restart");
                int targetFPS = frameGenerationEffect.getTargetFPS();
                frameGenerationEffect.toggleGeneration();
                removeEffect(frameGenerationEffect);

                frameGenerationEffect = new FrameGenerationEffect();
                addEffect(frameGenerationEffect);
                frameGenerationEffect.toggleGeneration();
                frameGenerationEffect.setTargetFPS(targetFPS);
            }
            frameGenerationEffect.setGenerationMode(mode);
        }
    }

    public synchronized FrameGenerationSettings getFrameGenerationSettings() {
        if (frameGenerationEffect != null) {
            return new FrameGenerationSettings(
                    frameGenerationEffect.getTargetFPS(),
                    frameGenerationEffect.isAutoDetectFPS(),
                    frameGenerationEffect.getCurrentRealFrameInterval(),
                    frameGenerationEffect.getCurrentTargetFrameInterval()
            );
        }
        return null;
    }

    public static class FrameGenerationSettings {
        public final int targetFPS;
        public final boolean autoDetect;
        public final long realInterval;
        public final long targetInterval;

        public FrameGenerationSettings(int targetFPS, boolean autoDetect,
                                       long realInterval, long targetInterval) {
            this.targetFPS = targetFPS;
            this.autoDetect = autoDetect;
            this.realInterval = realInterval;
            this.targetInterval = targetInterval;
        }
    }
}