package com.winlator.inputcontrols;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

public class XKeyCodes {
    private static final int KEYBOARD = 0;
    private record ScanKeyCode(String name, int scanCode, int keyCode) {}

    private static final ScanKeyCode[] scanKeyCodes = {
            new ScanKeyCode("KEY_UP", 103, KeyEvent.KEYCODE_DPAD_UP),
            new ScanKeyCode("KEY_DOWN", 108, KeyEvent.KEYCODE_DPAD_DOWN),
            new ScanKeyCode("KEY_LEFT", 105, KeyEvent.KEYCODE_DPAD_LEFT),
            new ScanKeyCode("KEY_RIGHT", 106, KeyEvent.KEYCODE_DPAD_RIGHT),
            new ScanKeyCode("KEY_ESC", 1, KeyEvent.KEYCODE_ESCAPE),
            new ScanKeyCode("KEY_ENTER", 28, KeyEvent.KEYCODE_ENTER),
            new ScanKeyCode("KEY_SPACE", 57, KeyEvent.KEYCODE_SPACE),
            new ScanKeyCode("KEY_A", 30, KeyEvent.KEYCODE_A),
            new ScanKeyCode("KEY_B", 48, KeyEvent.KEYCODE_B),
            new ScanKeyCode("KEY_C", 46, KeyEvent.KEYCODE_C),
            new ScanKeyCode("KEY_D", 32, KeyEvent.KEYCODE_D),
            new ScanKeyCode("KEY_E", 18, KeyEvent.KEYCODE_E),
            new ScanKeyCode("KEY_F", 33, KeyEvent.KEYCODE_F),
            new ScanKeyCode("KEY_G", 34, KeyEvent.KEYCODE_G),
            new ScanKeyCode("KEY_H", 35, KeyEvent.KEYCODE_H),
            new ScanKeyCode("KEY_I", 23, KeyEvent.KEYCODE_I),
            new ScanKeyCode("KEY_J", 36, KeyEvent.KEYCODE_J),
            new ScanKeyCode("KEY_K", 37, KeyEvent.KEYCODE_K),
            new ScanKeyCode("KEY_L", 38, KeyEvent.KEYCODE_L),
            new ScanKeyCode("KEY_M", 50, KeyEvent.KEYCODE_M),
            new ScanKeyCode("KEY_N", 49, KeyEvent.KEYCODE_N),
            new ScanKeyCode("KEY_O", 24, KeyEvent.KEYCODE_O),
            new ScanKeyCode("KEY_P", 25, KeyEvent.KEYCODE_P),
            new ScanKeyCode("KEY_Q", 16, KeyEvent.KEYCODE_Q),
            new ScanKeyCode("KEY_R", 19, KeyEvent.KEYCODE_R),
            new ScanKeyCode("KEY_S", 31, KeyEvent.KEYCODE_S),
            new ScanKeyCode("KEY_T", 20, KeyEvent.KEYCODE_T),
            new ScanKeyCode("KEY_U", 22, KeyEvent.KEYCODE_U),
            new ScanKeyCode("KEY_V", 47, KeyEvent.KEYCODE_V),
            new ScanKeyCode("KEY_W", 17, KeyEvent.KEYCODE_W),
            new ScanKeyCode("KEY_X", 45, KeyEvent.KEYCODE_X),
            new ScanKeyCode("KEY_Y", 21, KeyEvent.KEYCODE_Y),
            new ScanKeyCode("KEY_Z", 44, KeyEvent.KEYCODE_Z),
            new ScanKeyCode("KEY_GRAVE", 40, KeyEvent.KEYCODE_APOSTROPHE),
            new ScanKeyCode("KEY_CTRL_L", 29, KeyEvent.KEYCODE_CTRL_LEFT),
            new ScanKeyCode("KEY_CTRL_R", 97, KeyEvent.KEYCODE_CTRL_RIGHT),
            new ScanKeyCode("KEY_SHIFT_L", 42, KeyEvent.KEYCODE_SHIFT_LEFT),
            new ScanKeyCode("KEY_SHIFT_R", 54, KeyEvent.KEYCODE_SHIFT_RIGHT),
            new ScanKeyCode("KEY_TAB", 15, KeyEvent.KEYCODE_TAB),
            new ScanKeyCode("KEY_ALT_L", 56, KeyEvent.KEYCODE_ALT_LEFT),
            new ScanKeyCode("KEY_ALT_R", 100, KeyEvent.KEYCODE_ALT_RIGHT),
            new ScanKeyCode("KEY_F1", 59, KeyEvent.KEYCODE_F1),
            new ScanKeyCode("KEY_F2", 60, KeyEvent.KEYCODE_F2),
            new ScanKeyCode("KEY_F3", 61, KeyEvent.KEYCODE_F3),
            new ScanKeyCode("KEY_F4", 62, KeyEvent.KEYCODE_F4),
            new ScanKeyCode("KEY_F5", 63, KeyEvent.KEYCODE_F5),
            new ScanKeyCode("KEY_F6", 64, KeyEvent.KEYCODE_F6),
            new ScanKeyCode("KEY_F7", 65, KeyEvent.KEYCODE_F7),
            new ScanKeyCode("KEY_F8", 66, KeyEvent.KEYCODE_F8),
            new ScanKeyCode("KEY_F9", 67, KeyEvent.KEYCODE_F9),
            new ScanKeyCode("KEY_F10", 68, KeyEvent.KEYCODE_F10),
            new ScanKeyCode("KEY_F11", 87, KeyEvent.KEYCODE_F11),
            new ScanKeyCode("KEY_F12", 88, KeyEvent.KEYCODE_F12),
            new ScanKeyCode("KEY_INSERT", 110, KeyEvent.KEYCODE_INSERT),
            new ScanKeyCode("KEY_HOME", 102, KeyEvent.KEYCODE_MOVE_HOME),
            new ScanKeyCode("KEY_PRIOR", 104, KeyEvent.KEYCODE_PAGE_UP),
            new ScanKeyCode("KEY_DEL", 111, KeyEvent.KEYCODE_FORWARD_DEL),
            new ScanKeyCode("KEY_END", 107, KeyEvent.KEYCODE_MOVE_END),
            new ScanKeyCode("KEY_NEXT", 109, KeyEvent.KEYCODE_PAGE_DOWN),
            new ScanKeyCode("KEY_BKSP", 14, KeyEvent.KEYCODE_DEL),
            new ScanKeyCode("KEY_0", 11, KeyEvent.KEYCODE_0),
            new ScanKeyCode("KEY_1", 2, KeyEvent.KEYCODE_1),
            new ScanKeyCode("KEY_2", 3, KeyEvent.KEYCODE_2),
            new ScanKeyCode("KEY_3", 4, KeyEvent.KEYCODE_3),
            new ScanKeyCode("KEY_4", 5, KeyEvent.KEYCODE_4),
            new ScanKeyCode("KEY_5", 6, KeyEvent.KEYCODE_5),
            new ScanKeyCode("KEY_6", 7, KeyEvent.KEYCODE_6),
            new ScanKeyCode("KEY_7", 8, KeyEvent.KEYCODE_7),
            new ScanKeyCode("KEY_8", 9, KeyEvent.KEYCODE_8),
            new ScanKeyCode("KEY_9", 10, KeyEvent.KEYCODE_9),
            new ScanKeyCode("KEY_MINUS", 12, KeyEvent.KEYCODE_MINUS),
            new ScanKeyCode("KEY_EQUAL", 13, KeyEvent.KEYCODE_EQUALS),
            new ScanKeyCode("KEY_SEMICOLON", 39, KeyEvent.KEYCODE_SEMICOLON),
            new ScanKeyCode("KEY_APOSTROPHE", 40, KeyEvent.KEYCODE_APOSTROPHE),
            new ScanKeyCode("KEY_BACKSLASH", 43, KeyEvent.KEYCODE_BACKSLASH),
            new ScanKeyCode("KEY_COMMA", 51, KeyEvent.KEYCODE_COMMA),
            new ScanKeyCode("KEY_PERIOD", 52, KeyEvent.KEYCODE_PERIOD),
            new ScanKeyCode("KEY_SLASH", 53, KeyEvent.KEYCODE_SLASH),
            new ScanKeyCode("KEY_CAPS_LOCK", 58, KeyEvent.KEYCODE_CAPS_LOCK),
            new ScanKeyCode("KEY_NUM_LOCK", 69, KeyEvent.KEYCODE_NUM_LOCK),
            new ScanKeyCode("KEY_SCROLL_LOCK", 70, KeyEvent.KEYCODE_SCROLL_LOCK),
            new ScanKeyCode("KEY_KP_7", 71, KeyEvent.KEYCODE_NUMPAD_7),
            new ScanKeyCode("KEY_KP_8", 72, KeyEvent.KEYCODE_NUMPAD_8),
            new ScanKeyCode("KEY_KP_9", 73, KeyEvent.KEYCODE_NUMPAD_9),
            new ScanKeyCode("KEY_KP_SUBTRACT", 74, KeyEvent.KEYCODE_NUMPAD_SUBTRACT),
            new ScanKeyCode("KEY_KP_4", 75, KeyEvent.KEYCODE_NUMPAD_4),
            new ScanKeyCode("KEY_KP_5", 76, KeyEvent.KEYCODE_NUMPAD_5),
            new ScanKeyCode("KEY_KP_6", 77, KeyEvent.KEYCODE_NUMPAD_6),
            new ScanKeyCode("KEY_KP_ADD", 78, KeyEvent.KEYCODE_NUMPAD_ADD),
            new ScanKeyCode("KEY_KP_1", 79, KeyEvent.KEYCODE_NUMPAD_1),
            new ScanKeyCode("KEY_KP_2", 80, KeyEvent.KEYCODE_NUMPAD_2),
            new ScanKeyCode("KEY_KP_3", 81, KeyEvent.KEYCODE_NUMPAD_3),
            new ScanKeyCode("KEY_KP_0", 82, KeyEvent.KEYCODE_NUMPAD_0),
            new ScanKeyCode("KEY_KP_ENTER", 96, KeyEvent.KEYCODE_NUMPAD_ENTER),
            new ScanKeyCode("KEY_KP_MULTIPLY", 55, KeyEvent.KEYCODE_NUMPAD_MULTIPLY),
            new ScanKeyCode("KEY_KP_DIVIDE", 98, KeyEvent.KEYCODE_NUMPAD_DIVIDE),
            new ScanKeyCode("KEY_BRACKET_LEFT", 26, KeyEvent.KEYCODE_LEFT_BRACKET),
            new ScanKeyCode("KEY_BRACKET_RIGHT", 27, KeyEvent.KEYCODE_RIGHT_BRACKET),
            new ScanKeyCode("KEY_PRTSCN", 210, KeyEvent.KEYCODE_SYSRQ),
            new ScanKeyCode("KEY_KP_DEL", 83, KeyEvent.KEYCODE_NUMPAD_DOT),
    };

    public static List<String> getKeyNames(boolean getMouseButtons) {
        final ArrayList<String> keyNames = new ArrayList<>();

        keyNames.add("--");

        if (getMouseButtons) {
            keyNames.add("M_Left");
            keyNames.add("M_Middle");
            keyNames.add("M_Right");
            keyNames.add("M_WheelUp");
            keyNames.add("M_WheelDown");
        } else {
            keyNames.add("Mouse");
        }

        for (ScanKeyCode scanKeyCode : scanKeyCodes) {
            keyNames.add(scanKeyCode.name);
        }

        return keyNames;
    }

    public static ButtonMapping getMapping(String key) {
        ScanKeyCode mapping = null;

        for (ScanKeyCode scanKeyCode : scanKeyCodes) {
            if (scanKeyCode.name.equals(key)) {
                mapping = scanKeyCode;
            }
        }

        if (mapping == null) {
            return new ButtonMapping(key);
        }

        return new ButtonMapping(key, mapping.scanCode, mapping.keyCode, KEYBOARD);
    }

    public static class ButtonMapping {
        public String name;
        public int scanCode;
        public int keyCode;
        public int type;

        public ButtonMapping() {
            this.name = "";
            this.scanCode = -1;
            this.keyCode = -1;
            this.type = -1;
        }

        public ButtonMapping(String name) {
            this.name = name;
            this.scanCode = -1;
            this.keyCode = -1;
            this.type = -1;
        }

        public ButtonMapping(String name, int scanCode, int keyCode, int type) {
            this.name = name;
            this.scanCode = scanCode;
            this.keyCode = keyCode;
            this.type = type;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
