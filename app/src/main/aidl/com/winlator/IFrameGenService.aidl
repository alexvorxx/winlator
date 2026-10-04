package com.winlator;

import android.hardware.HardwareBuffer;
import android.os.ParcelFileDescriptor;

interface IFrameGenService {
    oneway void init(in HardwareBuffer prev, in HardwareBuffer curr, in HardwareBuffer flow,
                     int width, int height, int flowWidth, int flowHeight);
    boolean isReady();
    oneway void computeFlow(in ParcelFileDescriptor fenceFd);
    void shutdown();
    void onDestroy();
}