package com.winlator.xenvironment.components;

/* Decompiled from Winlator 10 Final
 * https://github.com/brunodev85/winlator/releases/tag/v10.0.0
*/

import androidx.annotation.Keep;
import com.winlator.contentdialog.VortekConfigDialog;
import com.winlator.core.KeyValueSet;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.widget.XServerView;
import com.winlator.xconnector.Client;
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
  public static final int VK_MAX_VERSION = vkMakeVersion(1, 3, 128);
  
  private XConnectorEpoll connector;
  
  private final Options options;
  
  private final UnixSocketConfig socketConfig;
  
  private final XServer xServer;

  private final String renderName;

  /*static {
    System.loadLibrary("vortekrenderer");
  }*/

  public VortekRendererComponent(XServer paramXServer, UnixSocketConfig paramUnixSocketConfig, Options paramOptions, String paramRenderName) {
    this.xServer = paramXServer;
    this.socketConfig = paramUnixSocketConfig;
    this.options = paramOptions;
    this.renderName = paramRenderName;
    System.loadLibrary(this.renderName);
    if (!(renderName.equals("vortekrenderer-d"))) {
      initVulkanWrapper(null, paramOptions.libvulkanPath);
    }
  }

  private native long createVkContext(int paramInt, Options paramOptions);
  
  private native void destroyVkContext(long paramLong);

  private native void initVulkanWrapper(String paramString1, String paramString2);

  static int vkMakeVersion(int paramInt1, int paramInt2, int paramInt3) {
    return paramInt1 << 22 | paramInt2 << 12 | paramInt3;
  }
  
  @Keep
  private long getWindowHardwareBuffer(int paramInt) {
    Window window = this.xServer.windowManager.getWindow(paramInt);
    if (window != null) {
      Drawable drawable = window.getContent();
      Texture texture = drawable.getTexture();
      if (!(texture instanceof GPUImage)) {
        XServerView xServerView = (this.xServer.getRenderer()).xServerView;
        Objects.requireNonNull(texture);
        xServerView.queueEvent(texture::destroy);
        drawable.setTexture((Texture)new GPUImage(drawable.width, drawable.height, false, false));
      } 
      return ((GPUImage)drawable.getTexture()).getHardwareBufferPtr();
    } 
    return 0L;
  }
  
  @Keep
  private int getWindowHeight(int paramInt) {
    Window window = this.xServer.windowManager.getWindow(paramInt);
    if (window != null) {
      paramInt = window.getHeight();
    } else {
      paramInt = 0;
    } 
    return paramInt;
  }
  
  @Keep
  private int getWindowWidth(int paramInt) {
    Window window = this.xServer.windowManager.getWindow(paramInt);
    if (window != null) {
      paramInt = window.getWidth();
    } else {
      paramInt = 0;
    } 
    return paramInt;
  }
  
  @Keep
  private void updateWindowContent(int paramInt) {
    Window window = this.xServer.windowManager.getWindow(paramInt);
    if (window != null) {
      Drawable drawable = window.getContent();
      synchronized (drawable.renderLock) {
        drawable.forceUpdate();
      } 
    } 
  }
  
  public void handleConnectionShutdown(Client paramClient) {
    if (paramClient.getTag() != null)
      destroyVkContext(((Long)paramClient.getTag()).longValue()); 
  }
  
  public void handleNewConnection(Client paramClient) {
    paramClient.createIOStreams();
  }
  
  public boolean handleRequest(Client paramClient) throws IOException {
    XInputStream xInputStream = paramClient.getInputStream();
    if (xInputStream.available() < 1)
      return false; 
    if (xInputStream.readByte() == 1) {
      long l = createVkContext(paramClient.clientSocket.fd, this.options);
      if (l > 0L) {
        paramClient.setTag(Long.valueOf(l));
      } else {
        this.connector.killConnection(paramClient);
      } 
    } 
    return true;
  }
  
  public void start() {
    if (this.connector != null)
      return; 
    XConnectorEpoll xConnectorEpoll = new XConnectorEpoll(this.socketConfig, this, this);
    this.connector = xConnectorEpoll;
    xConnectorEpoll.setInitialInputBufferCapacity(1);
    this.connector.setInitialOutputBufferCapacity(0);
    this.connector.start();
  }
  
  public void stop() {
    XConnectorEpoll xConnectorEpoll = this.connector;
    if (xConnectorEpoll != null) {
      xConnectorEpoll.stop();
      this.connector = null;
    } 
  }
  
  public static class Options {
    public String[] exposedDeviceExtensions = null;

    public short imageCacheSize = 256;

    public String libvulkanPath = null;
    
    public short maxDeviceMemory = 4096;

    public byte resourceMemoryType = 0;
    
    public int vkMaxVersion = VortekRendererComponent.VK_MAX_VERSION;

    public short renderVersion = 0;
    
    public static Options fromKeyValueSet(KeyValueSet param1KeyValueSet) {
      if (param1KeyValueSet == null || param1KeyValueSet.isEmpty())
        return new Options();

      Options options = new Options();
      String str1 = param1KeyValueSet.get("exposedDeviceExtensions", "all");
      if (!str1.isEmpty() && !str1.equals("all"))
        options.exposedDeviceExtensions = str1.split("\\|"); 
      str1 = VortekConfigDialog.DEFAULT_VK_MAX_VERSION;
      String str2 = param1KeyValueSet.get("vkMaxVersion", str1);
      if (!str2.equals(str1)) {
        String[] arrayOfString = str2.split("\\.");
        options.vkMaxVersion = vkMakeVersion(Integer.parseInt(arrayOfString[0]), Integer.parseInt(arrayOfString[1]), 128);
      } 
      options.maxDeviceMemory = (short)param1KeyValueSet.getInt("maxDeviceMemory", 512);
      options.imageCacheSize = (short)param1KeyValueSet.getInt("imageCacheSize", 256);
      options.resourceMemoryType = (byte)param1KeyValueSet.getInt("resourceMemoryType", 0);
      options.renderVersion = (short)param1KeyValueSet.getInt("renderVersion", 0);
      return options;
    }
  }
}
