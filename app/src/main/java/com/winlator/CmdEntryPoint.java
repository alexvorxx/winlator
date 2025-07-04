package com.winlator;

import android.content.pm.PackageManager;
import static android.system.Os.getenv;
import static android.system.Os.getuid;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Keep;

import java.io.DataInputStream;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;



@Keep @SuppressLint({"StaticFieldLeak", "UnsafeDynamicallyLoadedCode"})
public class CmdEntryPoint extends ICmdEntryInterface.Stub {
    public static final String ACTION_START = "com.winlator.CmdEntryPoint.ACTION_START";
    private static X11Activity mActivity;
    public static final int PORT = 7892;
    public static final byte[] MAGIC = "0xDEADBEEF".getBytes();
    private static final Handler handler;
    public static Context ctx = createContext();

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        handler.post(() -> new CmdEntryPoint(args));
        Looper.loop();
    }

    CmdEntryPoint(String[] args) {
        if (!start(args))
            System.exit(1);

        spawnListeningThread();
        sendBroadcastDelayed();
    }

    @SuppressLint({"WrongConstant", "PrivateApi"})
    void sendBroadcast() {
        String targetPackage = getenv("TERMUX_X11_OVERRIDE_PACKAGE");
        if (targetPackage == null)
            targetPackage = "com.winlator";
        // We should not care about multiple instances, it should be called only by `Termux:X11` app
        // which is single instance...
        Bundle bundle = new Bundle();
        bundle.putBinder("", this);

        Intent intent = new Intent(ACTION_START);
        intent.putExtra("", bundle);
        intent.setPackage(targetPackage);

        if (getuid() == 0 || getuid() == 2000)
            intent.setFlags(0x00400000 /* FLAG_RECEIVER_FROM_SHELL */);

        try {
            ctx.sendBroadcast(intent);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
            /* Requires to find a way to override performRecieve, not really necessary for our use case though */
            /*
            String packageName;
            try {
                packageName = ((PackageManager)Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null, (Object[])null)).getPackagesForUid(getuid())[0];
            } 
            catch (Exception eb) {
                throw new RuntimeException(eb);
            }
            Object am;
            try {
              //noinspection JavaReflectionMemberAccess
              am = Class.forName("android.app.ActivityManager");
              am.getClass().getMethod("getService").invoke(null, (Object[])null);
            } catch (Exception e2) {
                try {
                    am = Class.forName("android.app.ActivityManagerNative");
                    am.getClass().getMethod("getDefault").invoke(null, (Object[])null);
                } catch (Exception e3) {
                    throw new RuntimeException(e3);
                }
            }

            assert am != null;
            Object sender;
            try {
                sender = am.getClass().getMethod("getIntentSender", int.class, String.class, null, null, int.class, Intent.class, null, int.class, null, int.class).invoke(null, 1, packageName, null, null, 0, new Intent[] { intent }, null, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT, null, 0);
            }
            catch (Exception ez) {
                throw new RuntimeException(ez);
            }
            try {
                //noinspection JavaReflectionMemberAccess
                Object obj = Class.forName("android.content.IIntentReciever.Stub");
                sender.getClass().getMethod("send", int.class, Intent.class, String.class, IBinder.class, Class.forName("android.content.IIntentReciever"), String.class, Bundle.class)
                        .invoke(sender, 0, intent, null, obj.getClass()
                    .getMethod("performRecieve", Intent.class, int.class, String.class, Bundle.class, boolean.class, boolean.class, int.class).invoke(obj.getClass().newInstance()), null, null);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            */
        }
    }

    // In some cases Android Activity part can not connect opened port.
    // In this case opened port works like a lock file.
    private void sendBroadcastDelayed() {
        if (!connected())
            sendBroadcast();

        handler.postDelayed(this::sendBroadcastDelayed, 1000);
    }

    void spawnListeningThread() {
        new Thread(() -> { // New thread is needed to avoid android.os.NetworkOnMainThreadException
            /*
                The purpose of this function is simple. If the application has not been launched
                before running termux-x11, the initial sendBroadcast had no effect because no one
                received the intent. To allow the application to reconnect freely, we will listen on
                port `PORT` and when receiving a magic phrase, we will send another intent.
             */
            Log.e("CmdEntryPoint", "Listening port " + PORT);
            try (ServerSocket listeningSocket =
                         new ServerSocket(PORT, 0, InetAddress.getByName("127.0.0.1"))) {
                listeningSocket.setReuseAddress(true);
                while(true) {
                    try (Socket client = listeningSocket.accept()) {
                        Log.e("CmdEntryPoint", "Somebody connected!");
                        // We should ensure that it is some
                        byte[] b = new byte[MAGIC.length];
                        DataInputStream reader = new DataInputStream(client.getInputStream());
                        reader.readFully(b);
                        if (Arrays.equals(MAGIC, b)) {
                            Log.e("CmdEntryPoint", "New client connection!");
                            sendBroadcast();
                        }
                    } catch (Exception e) {
                        e.printStackTrace(System.err);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }).start();
    }

    public static void requestConnection() {
        System.err.println("Requesting connection...");
        new Thread(() -> { // New thread is needed to avoid android.os.NetworkOnMainThreadException
            try (Socket socket = new Socket("127.0.0.1", CmdEntryPoint.PORT)) {
                socket.getOutputStream().write(CmdEntryPoint.MAGIC);
            } catch (ConnectException e) {
                if (e.getMessage() != null && e.getMessage().contains("Connection refused")) {
                    Log.e("CmdEntryPoint", "ECONNREFUSED: Connection has been refused by the server");
                } else
                    Log.e("CmdEntryPoint", "Something went wrong when we requested connection", e);
            } catch (Exception e) {
                Log.e("CmdEntryPoint", "Something went wrong when we requested connection", e);
            }
        }).start();
    }

    /** @noinspection DataFlowIssue*/
    @SuppressLint("DiscouragedPrivateApi")
    public static Context createContext() {
        try {
            java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            Object activityThread = Class.
                    forName("sun.misc.Unsafe").
                    getMethod("allocateInstance", Class.class).
                    invoke(unsafe, Class.forName("android.app.ActivityThread"));
            return (Context)activityThread.getClass().getMethod("getSystemContext").invoke(activityThread, (Object[])null);
            
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static native boolean start(String[] args);
    public native void windowChanged(Surface surface, String name);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    private static native boolean connected();

    static
    {
        String path = "lib/" + "arm64-v8a" + "/libXlorie.so";
        ClassLoader loader = CmdEntryPoint.class.getClassLoader();
        URL res = loader != null ? loader.getResource(path) : null;
        String resPath = res.getFile();
        String resPath1 = resPath.replace("file:", "");
        String resPath2 = resPath1.replace("/base.apk!", "");
        String resPath3 = resPath2.replace("arm64-v8a", "arm64");
        String libPath = res != null ? resPath3 : null;
        if (libPath != null) {
            try {
                System.load(libPath);
            } catch (Exception e) {
                Log.e("CmdEntryPoint", "Failed to dlopen " + libPath, e);
                System.err.println("Failed to load native library. Did you install the right apk? Try the universal one.");
                System.exit(134);
            }
        } else {
// It is critical only when it is not running in Android application process
            if (mActivity.getInstance() == null) {
                System.err.println("Failed to acquire native library. Did you install the right apk? Try the universal one.");
                System.exit(134);
            }
        }

        if (Looper.getMainLooper() == null)
            Looper.prepareMainLooper();
        handler = new Handler();
    }
}
