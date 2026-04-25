package com.winlator.core;

/* Decompiled from Winlator 10 Final
 * https://github.com/brunodev85/winlator/releases/tag/v10.0.0
 */

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dalvik.annotation.optimization.CriticalNative;

public abstract class GPUHelper {
  static {
    System.loadLibrary("winlator");
  }

  public static int vkMakeVersion(int paramInt1, int paramInt2, int paramInt3) {
    return paramInt1 << 22 | paramInt2 << 12 | paramInt3;
  }
  
  public static int vkMakeVersion(String paramString) {
    Matcher matcher = Pattern.compile("([0-9]+)\\.([0-9]+)\\.?([0-9]+)?").matcher(paramString);
    if (matcher.find())
      try {
        byte b;
        boolean bool;
        int v1, v2, v3;
        if (matcher.group(1) != null) {
          v1 = Integer.parseInt(matcher.group(1));
        } else {
          v1 = 0;
        } 
        if (matcher.group(2) != null) {
          v2 = Integer.parseInt(matcher.group(2));
        } else {
          v2 = 0;
        } 
        if (matcher.group(3) != null) {
          v3 = Integer.parseInt(matcher.group(3));
        } else {
          v3 = 0;
        } 

        return vkMakeVersion(v1, v2, v3);
      } catch (NumberFormatException numberFormatException) {
        return 0;
      }  
    return 0;
  }
  
  public static int vkVersionMajor(int paramInt) {
    return paramInt >> 22;
  }
  
  public static int vkVersionMinor(int paramInt) {
    return paramInt >> 12 & 0x3FF;
  }

  public static native String[] vkGetDeviceExtensions();

  @CriticalNative
  public static native int vkGetApiVersion();

  public static native void setGlobalEGLContext();
}
