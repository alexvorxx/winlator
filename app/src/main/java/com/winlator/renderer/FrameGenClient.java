package com.winlator.renderer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.winlator.IFrameGenService;

public class FrameGenClient {
    private static final String TAG = "FrameGenClient";

    private final Context context;
    private volatile IFrameGenService service = null;
    private volatile boolean bound = false;
    private boolean bindCalled = false;
    private ServiceConnection conn = null;

    public FrameGenClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public void bindAsync() {
        if (bindCalled) return;
        bindCalled = true;

        conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) {
                service = IFrameGenService.Stub.asInterface(b);
                bound = true;
                Log.i(TAG, "Service connected (async)");
            }
            @Override public void onServiceDisconnected(ComponentName n) {
                service = null;
                bound = false;
                Log.w(TAG, "Service disconnected");
            }
        };

        Intent intent = new Intent(context, FrameGenService.class);
        if (!context.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            Log.e(TAG, "bindService call failed");
            conn = null;
            bindCalled = false;
        }
    }

    public boolean isReady() { return bound && service != null; }

    public boolean init(HardwareBuffer prev, HardwareBuffer curr, HardwareBuffer flow,
                        int w, int h, int fw, int fh, int apiMode) {
        if (!isReady()) return false;
        try {
            return service.init(prev, curr, flow, w, h, fw, fh, apiMode);
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
            return false;
        }
    }

    public void computeFlow(int fenceFd) {
        IFrameGenService s = service;
        if (s == null) return;
        ParcelFileDescriptor pfd = null;
        try {
            if (fenceFd >= 0) pfd = ParcelFileDescriptor.fromFd(fenceFd);
            s.computeFlow(pfd);
        } catch (Exception e) {
            Log.e(TAG, "computeFlow failed", e);
        } finally {
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    public void shutdown() {
        IFrameGenService s = service;
        if (s != null) { try { s.shutdown(); } catch (Exception ignored) {} }
        unbind();
    }

    private void unbind() {
        if (bindCalled && conn != null) {
            try { context.unbindService(conn); } catch (Exception ignored) {}
            bindCalled = false;
            bound = false;
            service = null;
            conn = null;
        }
    }
}