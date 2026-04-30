package com.winlator.widget;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.winlator.R;

import java.util.Locale;

public class FrameRating extends FrameLayout implements Runnable {
    private long lastTime = 0;
    private int frameCount = 0;
    private float lastFPS = 0;
    private final TextView textViewFPS;
    private final TextView textViewRAM;
    private final TextView textViewBatteryTemp;
    private final TextView textViewRAMName;
    private final TextView textViewBatteryTempName;
    private ActivityManager activityManager;
    private ActivityManager.MemoryInfo memoryInfo;
    private BroadcastReceiver batteryReceiver;
    private int batteryTemperature;

    public FrameRating(Context context) {
        this(context, null);
    }

    public FrameRating(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FrameRating(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        View view = LayoutInflater.from(context).inflate(R.layout.frame_rating, this, false);
        textViewFPS = view.findViewById(R.id.TVFPS);
        textViewRAM = view.findViewById(R.id.TVRAM);
        textViewBatteryTemp = view.findViewById(R.id.TVBatteryTemp);
        textViewRAMName = view.findViewById(R.id.TVRAMName);
        textViewBatteryTempName = view.findViewById(R.id.TVBatteryTempName);
        addView(view);

        activityManager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        memoryInfo = new ActivityManager.MemoryInfo();

        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null && action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                    int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                    batteryTemperature = (int)(temperature / 10.0f);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        context.registerReceiver(batteryReceiver, filter);
    }

    public void update() {
        if (lastTime == 0) lastTime = SystemClock.elapsedRealtime();
        long time = SystemClock.elapsedRealtime();
        if (time >= lastTime + 500) {
            lastFPS = ((float)(frameCount * 1000) / (time - lastTime));
            post(this);
            lastTime = time;
            frameCount = 0;
        }

        frameCount++;
    }

    @Override
    public void run() {
        if (getVisibility() == GONE) setVisibility(View.VISIBLE);
        textViewFPS.setText(String.format(Locale.ENGLISH, "%.1f", lastFPS));

        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        String ramText = formatBytes(usedMem, false)+"/"+formatBytes(memoryInfo.totalMem, true);
        textViewRAM.setText(ramText);

        textViewBatteryTemp.setText(batteryTemperature + " ºC");
    }

    private static String formatBytes(long bytes, boolean withSuffix) {
        if (bytes <= 0) return "0 bytes";
        final String[] units = new String[]{"bytes", "KB", "MB", "GB", "TB"};
        int digitGroups = (int)(Math.log10(bytes) / Math.log10(1024));
        String suffix = withSuffix ? " "+units[digitGroups] : "";
        return String.format(Locale.ENGLISH, "%.1f", bytes / Math.pow(1024, digitGroups))+suffix;
    }

    public void setShowOtherCounters(boolean showOtherCounters) {
        if (showOtherCounters) {
            textViewRAMName.setVisibility(VISIBLE);
            textViewBatteryTempName.setVisibility(VISIBLE);
            textViewRAM.setVisibility(VISIBLE);
            textViewBatteryTemp.setVisibility(VISIBLE);
        } else {
            textViewRAMName.setVisibility(GONE);
            textViewBatteryTempName.setVisibility(GONE);
            textViewRAM.setVisibility(GONE);
            textViewBatteryTemp.setVisibility(GONE);
        }
    }

}