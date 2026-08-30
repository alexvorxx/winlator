package com.winlator.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.R;
import com.winlator.core.Callback;
import com.winlator.core.UnitUtils;
import com.winlator.math.Mathf;
import com.winlator.renderer.EffectComposer;
import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.effects.FrameGenerationEffect;

public class FrameGenerationView extends FrameLayout {
    private final SharedPreferences preferences;
    private boolean restoreSavedPosition = true;
    private short lastX = 0;
    private short lastY = 0;
    private Callback<Boolean> frameGenerationCallback;
    private Runnable hideButtonCallback;
    private final GLRenderer renderer;

    // Добавляем элементы UI
    private final Spinner modeSpinner;
    private final Spinner fpsMultiplierSpinner;
    private final Spinner fpsSpinner;
    private final TextView currentFpsTextView;
    private final SeekBar blendFactorSeekBar;
    private final TextView blendFactorLabel;

    private static final String[] GENERATION_MODE_OPTIONS = {
            "Fast", "Balanced", "Quality"
    };

    private static final int[] GENERATION_MODE_VALUES = {
            FrameGenerationEffect.MODE_FAST,
            FrameGenerationEffect.MODE_BALANCED,
            FrameGenerationEffect.MODE_QUALITY
    };

    private static final String[] FPS_MULTIPLIER_OPTIONS = {
            "x2", "x3", "x4"
    };

    private static final int[] FPS_MULTIPLIER_VALUES = {
            FrameGenerationEffect.FPS_MULTIPLIER_X2,
            FrameGenerationEffect.FPS_MULTIPLIER_X3,
            FrameGenerationEffect.FPS_MULTIPLIER_X4
    };

    private static final String[] INITIAL_FPS_OPTIONS = {
            "Auto", "15", "20", "25", "30", "45", "60"
    };

    private static final int[] INITIAL_FPS_VALUES = {
            FrameGenerationEffect.FPS_AUTO,
            FrameGenerationEffect.FPS_15,
            FrameGenerationEffect.FPS_20,
            FrameGenerationEffect.FPS_25,
            FrameGenerationEffect.FPS_30,
            FrameGenerationEffect.FPS_45,
            FrameGenerationEffect.FPS_60
    };

    private int initialFPS;
    private int generationMode;
    private int fpsMultiplier;
    private float blendFactor;

    public FrameGenerationView(Context context, GLRenderer renderer) {
        this(context, null, renderer);
    }

    public FrameGenerationView(Context context, @Nullable AttributeSet attrs, GLRenderer renderer) {
        this(context, attrs, 0, renderer);
    }

    public FrameGenerationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr, GLRenderer renderer) {
        this(context, attrs, defStyleAttr, 0, renderer);
    }

    public FrameGenerationView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes, GLRenderer renderer) {
        super(context, attrs, defStyleAttr, defStyleRes);

        this.renderer = renderer;

        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View contentView = LayoutInflater.from(context).inflate(R.layout.frame_generation_layout, this, false);

        final PointF startPoint = new PointF();
        final boolean[] isActionDown = {false};
        contentView.findViewById(R.id.BTMove).setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startPoint.x = event.getX();
                    startPoint.y = event.getY();
                    isActionDown[0] = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isActionDown[0]) {
                        float newX = getX() + (event.getX() - startPoint.x);
                        float newY = getY() + (event.getY() - startPoint.y);
                        movePanel(newX, newY);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (isActionDown[0] && lastX > 0 && lastY > 0) {
                        preferences.edit().putString("frame_generation_layout", lastX+"|"+lastY).apply();
                    }
                    lastX = 0;
                    lastY = 0;
                    isActionDown[0] = false;
                    break;
            }
            return true;
        });

        contentView.findViewById(R.id.BTHide).setOnClickListener((v) -> {
            if (hideButtonCallback != null) hideButtonCallback.run();
        });

        fpsSpinner = contentView.findViewById(R.id.fps_spinner);
        modeSpinner = contentView.findViewById(R.id.generation_mode_spinner);
        fpsMultiplierSpinner = contentView.findViewById(R.id.fps_multiplier_spinner);
        currentFpsTextView = contentView.findViewById(R.id.current_fps_text);

        blendFactorSeekBar = contentView.findViewById(R.id.SBBlendFactor);
        blendFactorLabel = contentView.findViewById(R.id.TVBlendFactorLabel);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                android.R.layout.simple_spinner_item,
                INITIAL_FPS_OPTIONS
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fpsSpinner.setAdapter(adapter);

        ArrayAdapter<String> adapter2 = new ArrayAdapter<>(
                getContext(),
                android.R.layout.simple_spinner_item,
                GENERATION_MODE_OPTIONS
        );
        adapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(adapter2);


        ArrayAdapter<String> adapter3 = new ArrayAdapter<>(
                getContext(),
                android.R.layout.simple_spinner_item,
                FPS_MULTIPLIER_OPTIONS
        );
        adapter3.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fpsMultiplierSpinner.setAdapter(adapter3);

        loadSettings();

        final ToggleButton toggleButton = contentView.findViewById(R.id.ToggleButton);
        toggleButton.setVisibility(VISIBLE);
        toggleButton.setOnClickListener((v) -> {
            if (frameGenerationCallback != null) {
                frameGenerationCallback.call(toggleButton.isChecked());
                if (renderer != null && renderer.effectComposer != null) {
                    renderer.effectComposer.configureFrameGeneration(this.initialFPS, this.generationMode);
                }
            }
        });

        fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                initialFPS = INITIAL_FPS_VALUES[position];
                boolean isAuto = (initialFPS == FrameGenerationEffect.FPS_AUTO);

                applyFrameGenerationSettings(initialFPS, isAuto);

                SharedPreferences prefs = getContext().getSharedPreferences("frame_generation", Context.MODE_PRIVATE);
                prefs.edit().putInt("fps", initialFPS).putInt("fps_spinner_position", position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                generationMode = GENERATION_MODE_VALUES[position];

                if (renderer != null && renderer.effectComposer != null) {
                    renderer.effectComposer.setFrameGenerationVariables(generationMode, fpsMultiplier, blendFactor);
                }

                SharedPreferences prefs = getContext().getSharedPreferences("frame_generation", Context.MODE_PRIVATE);
                prefs.edit().putInt("mode_spinner_position", generationMode).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        fpsMultiplierSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                fpsMultiplier = FPS_MULTIPLIER_VALUES[position];

                if (renderer != null && renderer.effectComposer != null) {
                    renderer.effectComposer.setFrameGenerationVariables(generationMode, fpsMultiplier, blendFactor);
                    setFpsMultiplier(fpsMultiplier);
                }

                if (fpsMultiplier == FrameGenerationEffect.FPS_MULTIPLIER_X2) {
                    blendFactorSeekBar.setVisibility(View.VISIBLE);
                    blendFactorLabel.setText(String.format("Blend Factor: %.2f", blendFactor));
                }
                else {
                    blendFactorSeekBar.setVisibility(View.GONE);
                    blendFactorLabel.setText("Blend Factor: Auto");
                }

                SharedPreferences prefs = getContext().getSharedPreferences("frame_generation", Context.MODE_PRIVATE);
                prefs.edit().putInt("fps_multiplier", fpsMultiplier).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        blendFactorSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    blendFactor = progress / 100.0f;

                    setBlendFactor(blendFactor);
                    blendFactorLabel.setText(String.format("Blend Factor: %.2f", blendFactor));

                    SharedPreferences prefs = getContext().getSharedPreferences("frame_generation", Context.MODE_PRIVATE);
                    prefs.edit().putFloat("blend_factor", blendFactor).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        startFPSUpdateTimer();

        addView(contentView);
    }

    private void applyFrameGenerationSettings(int initialFPS, boolean autoDetect) {
        if (renderer != null && renderer.effectComposer != null) {
            renderer.effectComposer.configureFrameGeneration(initialFPS, generationMode);

            if (autoDetect) {
                updateCurrentFPSDisplay();
            }
        }
    }

    private void updateCurrentFPSDisplay() {
        if (renderer != null && renderer.effectComposer != null) {
            EffectComposer.FrameGenerationSettings settings =
                    renderer.effectComposer.getFrameGenerationSettings();

            if (settings != null) {
                int realFPS = (int)(1000 / settings.realInterval);
                int targetFPS = (int)(1000 / settings.targetInterval);

                String text = String.format("Current: %d FPS → %d FPS", realFPS, targetFPS);
                currentFpsTextView.setText(text);
            }
        }
    }

    private void startFPSUpdateTimer() {
        Handler handler = new Handler();
        Runnable updateTask = new Runnable() {
            @Override
            public void run() {
                updateCurrentFPSDisplay();
                handler.postDelayed(this, 1000);
            }
        };
        handler.postDelayed(updateTask, 1000);
    }

    private void loadSettings() {
        SharedPreferences prefs = getContext().getSharedPreferences("frame_generation", Context.MODE_PRIVATE);
        initialFPS = prefs.getInt("fps", FrameGenerationEffect.FPS_30);
        int fps_spinner_position = prefs.getInt("fps_spinner_position", 4);
        generationMode = prefs.getInt("mode_spinner_position", FrameGenerationEffect.MODE_BALANCED);
        fpsMultiplier = prefs.getInt("fps_multiplier", FrameGenerationEffect.FPS_MULTIPLIER_X2);
        blendFactor = prefs.getFloat("blend_factor", FrameGenerationEffect.DEFAULT_BLEND_FACTOR);

        int progress = Math.round(blendFactor * 100);
        setBlendFactor(blendFactor);
        setFpsMultiplier(fpsMultiplier);

        fpsSpinner.setSelection(fps_spinner_position);
        modeSpinner.setSelection(generationMode);
        fpsMultiplierSpinner.setSelection(fpsMultiplier - FrameGenerationEffect.FPS_MULTIPLIER_X2);
        blendFactorSeekBar.setProgress(progress);

        if (fpsMultiplier == FrameGenerationEffect.FPS_MULTIPLIER_X2) {
            blendFactorSeekBar.setVisibility(View.VISIBLE);
            blendFactorLabel.setText(String.format("Blend Factor: %.2f", blendFactor));
        }
        else {
            blendFactorSeekBar.setVisibility(View.GONE);
            blendFactorLabel.setText("Blend Factor: Auto");
        }
    }

    private void setFpsMultiplier(int fpsMultiplier) {
        if (renderer != null && renderer.effectComposer != null) {
            FrameGenerationEffect effect =
                    (FrameGenerationEffect) renderer.effectComposer.getEffect(FrameGenerationEffect.class);
            if (effect != null) {
                effect.setFpsMultiplier(fpsMultiplier);
            }
        }
    }

    private void setBlendFactor(float blendFactor) {
        if (renderer != null && renderer.effectComposer != null) {
            FrameGenerationEffect effect =
                    (FrameGenerationEffect) renderer.effectComposer.getEffect(FrameGenerationEffect.class);
            if (effect != null) {
                effect.setBlendFactor(blendFactor);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (restoreSavedPosition) {
            float x = 1e6f;
            float y = 1e6f;

            String config = preferences.getString("frame_generation_layout", null);
            if (config != null) {
                try {
                    String[] parts = config.split("\\|");
                    x = Short.parseShort(parts[0]);
                    y = Short.parseShort(parts[1]);
                }
                catch (NumberFormatException e) {}
            }

            movePanel(x, y);
            restoreSavedPosition = false;
        }
    }

    private void movePanel(float x, float y) {
        final int padding = (int)UnitUtils.dpToPx(8);
        ViewGroup parent = (ViewGroup)getParent();
        int width = getWidth();
        int height = getHeight();
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        x = Mathf.clamp(x, padding, parentWidth - padding - width);
        y = Mathf.clamp(y, padding, parentHeight - padding - height);
        setX(x);
        setY(y);
        lastX = (short)x;
        lastY = (short)y;
    }

    public Callback<Boolean> getFrameGenerationCallback() {
        return frameGenerationCallback;
    }

    public void setFrameGenerationCallback(Callback<Boolean> frameGenerationCallback) {
        this.frameGenerationCallback = frameGenerationCallback;
    }

    public Runnable getHideButtonCallback() {
        return hideButtonCallback;
    }

    public void setHideButtonCallback(Runnable hideButtonCallback) {
        this.hideButtonCallback = hideButtonCallback;
    }
}