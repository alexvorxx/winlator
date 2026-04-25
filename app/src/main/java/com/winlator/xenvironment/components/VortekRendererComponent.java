package com.winlator.xenvironment.components;

import androidx.annotation.Keep;

import com.winlator.XServerDisplayActivity;
import com.winlator.contentdialog.VortekConfigDialog;
import com.winlator.contents.AdrenotoolsManager;
import com.winlator.core.KeyValueSet;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.widget.XServerView;
import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.ConnectionHandler;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Window;
import com.winlator.xserver.XServer;
import java.io.IOException;
import java.util.Objects;

public class VortekRendererComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
  private static final byte REQUEST_CODE_CREATE_CONTEXT = 1;
  private static final byte REQUEST_CODE_SEND_EXTRA_DATA = 2;
  public static final short IMAGE_CACHE_SIZE = 256;
  public static final int VK_MAX_VERSION = vkMakeVersion(1, 3, 128);
  private XConnectorEpoll connector;
  private final Options options;
  private final UnixSocketConfig socketConfig;
  private final XServerDisplayActivity activity;
  private final XServer xServer;

  static {
    System.loadLibrary("vortekrenderer");
  }

  public VortekRendererComponent(XServer xServer, UnixSocketConfig unixSocketConfig, Options options, String nativeLibraryDir) {
    this(null, xServer, unixSocketConfig, options, nativeLibraryDir);
  }

  public VortekRendererComponent(XServerDisplayActivity activity, XServer xServer, UnixSocketConfig socketConfig, Options options, String nativeLibraryDir) {
    this.activity = activity;
    this.xServer = xServer;
    this.socketConfig = socketConfig;
    this.options = options;

    initVulkanWrapper(nativeLibraryDir, options.libvulkanPath);
  }

  public static class Options {
    public int vkMaxVersion = VK_MAX_VERSION;
    public short maxDeviceMemory = 0;
    public short imageCacheSize = IMAGE_CACHE_SIZE;
    public byte resourceMemoryType = 0;
    public String[] exposedDeviceExtensions = null;
    public String libvulkanPath = null;

    public static Options fromKeyValueSet(KeyValueSet config) {
      if (config == null || config.isEmpty())
        return new Options();

      Options options = new Options();
      String str1 = config.get("exposedDeviceExtensions", "all");
      if (!str1.isEmpty() && !str1.equals("all"))
        options.exposedDeviceExtensions = str1.split("\\|");
      str1 = VortekConfigDialog.DEFAULT_VK_MAX_VERSION;
      String str2 = config.get("vkMaxVersion", str1);
      if (!str2.equals(str1)) {
        String[] arrayOfString = str2.split("\\.");
        options.vkMaxVersion = vkMakeVersion(Integer.parseInt(arrayOfString[0]), Integer.parseInt(arrayOfString[1]), 128);
      }
      options.maxDeviceMemory = (short)config.getInt("maxDeviceMemory", 512);
      options.imageCacheSize = (short)config.getInt("imageCacheSize", VortekRendererComponent.IMAGE_CACHE_SIZE);
      options.resourceMemoryType = (byte)config.getInt("resourceMemoryType", 0);

      if (config.getInt("driverVersion", 0) == 0)
        options.libvulkanPath = null;
      else
        options.libvulkanPath = config.get("libraryPath");

      return options;
    }
  }

  static int vkMakeVersion(int major, int minor, int patch) {
    return major << 22 | minor << 12 | patch;
  }

  public void start() {
    if (connector != null)
      return;
    connector = new XConnectorEpoll(socketConfig, this, this);
    connector.setInitialInputBufferCapacity(1);
    connector.setInitialOutputBufferCapacity(0);
    connector.start();
  }

  public void stop() {
    if (connector != null) {
      connector.destroy();
      connector = null;
    }
  }

  @Keep
  private int getWindowWidth(int windowId) {
    Window window = xServer.windowManager.getWindow(windowId);
    return window != null ? window.getWidth() : 0;
  }

  @Keep
  private int getWindowHeight(int windowId) {
    Window window = xServer.windowManager.getWindow(windowId);
    return window != null ? window.getHeight() : 0;
  }

  @Keep
  private long getWindowHardwareBuffer(int windowId, boolean useHALPixelFormatBGRA8888) {
    Window window = this.xServer.windowManager.getWindow(windowId);
    if (window != null) {
      Drawable drawable = window.getContent();
      Texture texture = drawable.getTexture();
      if (!(texture instanceof GPUImage)) {
        XServerView xServerView = (this.xServer.getRenderer()).xServerView;
        Objects.requireNonNull(texture);
        xServerView.queueEvent(texture::destroy);
        drawable.setTexture((Texture)new GPUImage(drawable.width, drawable.height, false, useHALPixelFormatBGRA8888));
      } 
      return ((GPUImage)drawable.getTexture()).getHardwareBufferPtr();
    } 
    return 0L;
  }

  @Keep
  private void updateWindowContent(int windowId) {
    Window window = this.xServer.windowManager.getWindow(windowId);
    if (window != null) {
      Drawable drawable = window.getContent();
      changeFrameRatingVisibility(window);
      synchronized (drawable.renderLock) {
        drawable.forceUpdate();
      } 
    } 
  }

  private void changeFrameRatingVisibility(Window window) {
    if (activity != null) {
      if (activity.frameRating != null) {
        if (activity.frameRatingWindowId == -1 && window.attributes.isMapped()) {
          activity.frameRatingWindowId = window.id;
        }
      }
    }
  }
  
  public void handleConnectionShutdown(ConnectedClient client) {
    if (client.getTag() != null)
      destroyVkContext((Long) client.getTag());
  }
  
  public void handleNewConnection(ConnectedClient client) { }
  
  public boolean handleRequest(ConnectedClient client) throws IOException {
    XInputStream inputStream = client.getInputStream();
    if (inputStream.available() < 8) return false;
    int requestCode = inputStream.readInt();
    int requestLength = inputStream.readInt();

    if (requestCode == REQUEST_CODE_CREATE_CONTEXT) {
      long contextPtr = createVkContext(client.fd, options);
      if (contextPtr > 0) {
        client.setTag(contextPtr);
      } else {
        connector.killConnection(client);
      } 
    } else if (requestCode > Short.MAX_VALUE && (requestCode >> 16) == REQUEST_CODE_SEND_EXTRA_DATA) {
      int requestId = requestCode & 0xffff;
      long contextPtr = (long)client.getTag();
      boolean success = handleExtraDataRequest(contextPtr, requestId, requestLength);
      if (!success) throw new IOException("Failed to handle extra data request.");
    }

    return true;
  }

  private native long createVkContext(int clientFd, Options options);

  private native void destroyVkContext(long contextPtr);

  private native void initVulkanWrapper(String nativeLibraryDir, String libvulkanPath);

  private native boolean handleExtraDataRequest(long contextPtr, int requestCode, int requestLength);

}
