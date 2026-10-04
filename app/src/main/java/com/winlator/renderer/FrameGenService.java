package com.winlator.renderer;

import android.app.Service;
import android.content.Intent;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.winlator.IFrameGenService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FrameGenService extends Service {
    private static final String TAG = "FrameGenService";

    private static native boolean nativeServiceInit(
            HardwareBuffer prev, HardwareBuffer curr, HardwareBuffer flow,
            int width, int height, int flowWidth, int flowHeight, int apiMode);
    private static native boolean nativeServiceComputeFlow(ParcelFileDescriptor fenceFd);
    private static native void    nativeServiceShutdown();
    private static native void nativeServiceDestroy();

    static { System.loadLibrary("winlator"); }

    private boolean initialized = false;
    private HandlerThread workerThread;
    private Handler workerHandler;

    private final IFrameGenService.Stub binder = new IFrameGenService.Stub() {
        @Override
        public boolean isReady() { return initialized; }

        @Override
        public boolean init(HardwareBuffer prev, HardwareBuffer curr, HardwareBuffer flow,
                            int width, int height, int flowWidth, int flowHeight, int apiMode) {
            Log.i(TAG, "init pid=" + android.os.Process.myPid() + " apiMode=" + apiMode);
            Boolean r = runOnWorker(() -> nativeServiceInit(prev, curr, flow,
                    width, height, flowWidth, flowHeight, apiMode));
            initialized = (r != null && r);
            Log.i(TAG, "nativeServiceInit result=" + initialized);
            return initialized;
        }

        @Override
        public void computeFlow(ParcelFileDescriptor fenceFd) {
            if (!initialized) {
                if (fenceFd != null) try { fenceFd.close(); } catch (Exception ignored) {}
                return;
            }
            workerHandler.post(() -> {
                try {
                    nativeServiceComputeFlow(fenceFd);
                } finally {
                    if (fenceFd != null) try { fenceFd.close(); } catch (Exception ignored) {}
                }
            });
        }

        @Override
        public void shutdown() {
            if (!initialized) return;
            runOnWorker(() -> { nativeServiceShutdown(); return true; });
            initialized = false;
        }

        @Override public void onDestroy() {
            if (initialized) {
                runOnWorker(() -> { nativeServiceShutdown(); return true; });
                initialized = false;
            }
            runOnWorker(() -> { nativeServiceDestroy(); return true; });
            if (workerThread != null) {
                workerThread.quitSafely();
                workerThread = null;
                workerHandler = null;
            }
        }
    };

    private interface WorkerTask { boolean run(); }

    private Boolean runOnWorker(WorkerTask task) {
        if (workerHandler == null) return false;
        final boolean[] result = { false };
        final CountDownLatch latch = new CountDownLatch(1);
        workerHandler.post(() -> {
            try { result[0] = task.run(); }
            finally { latch.countDown(); }
        });
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                Log.e(TAG, "worker task timeout");
                return false;
            }
        } catch (InterruptedException e) { return false; }
        return result[0];
    }

    @Override public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("FrameGenWorker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        Log.i(TAG, "worker thread started");
    }

    @Override public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind pid=" + android.os.Process.myPid());
        return binder;
    }

    @Override public void onDestroy() {
        if (initialized) {
            runOnWorker(() -> { nativeServiceShutdown(); return true; });
            initialized = false;
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }
        super.onDestroy();
    }
}