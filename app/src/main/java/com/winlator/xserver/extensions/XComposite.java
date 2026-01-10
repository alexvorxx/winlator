package com.winlator.xserver.extensions;

/* Decompiled from Winlator 10
 */

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadAccess;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import java.io.IOException;

public class XComposite implements Extension {
  public static final byte MAJOR_OPCODE = -105;

  public enum UpdateMode {
    REDIRECT_AUTOMATIC, REDIRECT_MANUAL;
  }

  private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
    inputStream.skip(8);
    XStreamLock xStreamLock = outputStream.lock();
    try {
      outputStream.writeByte((byte)1);
      outputStream.writeByte((byte)0);
      outputStream.writeShort(client.getSequenceNumber());
      outputStream.writeInt(0);
      outputStream.writeInt(0);
      outputStream.writeInt(1);
      outputStream.writePad(16);
      return;
    } finally {
      if (xStreamLock != null)
        try {
          xStreamLock.close();
        } finally {
          xStreamLock = null;
        }  
    } 
  }
  
  private static void redirectWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
    int i = inputStream.readInt();
    byte b = inputStream.readByte();
    inputStream.skip(3);
    Window window = client.xServer.windowManager.getWindow(i);
    if (window != null) {
      if (window != client.xServer.windowManager.rootWindow) {
        if (b == UpdateMode.REDIRECT_MANUAL.ordinal()) {
          if (!(Boolean) window.getTag("compositeRedirectManual", Boolean.FALSE)) {
            window.setTag("compositeRedirectManual", Boolean.TRUE);
            setWindowsToOffscreenStorage(window);
            (window.getParent()).attributes.setRenderSubwindows(false);
            return;
          } 
          throw new BadAccess();
        } 
        throw new BadImplementation();
      } 
      throw new BadMatch();
    } 
    throw new BadWindow(i);
  }
  
  private static void setWindowsToOffscreenStorage(Window window) {
    if (!window.attributes.isMapped())
      return; 
    window.getContent().setOffscreenStorage(true);
    for (Window child : window.getChildren())
      setWindowsToOffscreenStorage(child);
  }
  
  public byte getFirstErrorId() {
    return 0;
  }
  
  public byte getFirstEventId() {
    return 0;
  }
  
  public byte getMajorOpcode() {
    return MAJOR_OPCODE;
  }
  
  public String getName() {
    return "Composite";
  }
  
  public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
    switch (client.getRequestData()) {
      default:
        throw new BadImplementation();
      case 1:
        redirectWindow(client, inputStream, outputStream);
        return;
      case 0:
        break;
    } 
    queryVersion(client, inputStream, outputStream);
  }

}