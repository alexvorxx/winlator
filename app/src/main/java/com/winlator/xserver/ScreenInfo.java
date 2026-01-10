package com.winlator.xserver;

public class ScreenInfo {
    public final short width;
    public final short height;
    public boolean enableCompositeExtension;

    public ScreenInfo(String value) {
        String[] parts = value.split("x");
        width = Short.parseShort(parts[0]);
        height = Short.parseShort(parts[1]);
        enableCompositeExtension = false;
    }

    public ScreenInfo(int width, int height) {
        this.width = (short)width;
        this.height = (short)height;
        this.enableCompositeExtension = false;
    }

    public short getWidthInMillimeters() {
        return (short)(width / 10);
    }

    public short getHeightInMillimeters() {
        return (short)(height / 10);
    }

    @Override
    public String toString() {
        return width+"x"+height;
    }
}
