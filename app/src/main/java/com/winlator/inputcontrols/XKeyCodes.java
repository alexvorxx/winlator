package com.winlator.inputcontrols;

import java.util.ArrayList;
import java.util.List;

public class XKeyCodes {
    private static final int KEYBOARD = 0;
    private record ScanKeyCode(String name, int scanCode, int keyCode) {}

    private static final ScanKeyCode[] scanKeyCodes = {
            new ScanKeyCode("KEY_UP", 103, 19),
            new ScanKeyCode("KEY_DOWN", 108, 20),
            new ScanKeyCode("KEY_LEFT", 105, 21),
            new ScanKeyCode("KEY_RIGHT", 106, 22),
            new ScanKeyCode("KEY_ESC", 1, 111),
            new ScanKeyCode("KEY_ENTER", 28, 66),
            new ScanKeyCode("KEY_SPACE", 57, 62),
            new ScanKeyCode("KEY_A", 30, 29),
            new ScanKeyCode("KEY_B", 48, 30),
            new ScanKeyCode("KEY_C", 46, 31),
            new ScanKeyCode("KEY_D", 32, 32),
            new ScanKeyCode("KEY_E", 18, 33),
            new ScanKeyCode("KEY_F", 33, 34),
            new ScanKeyCode("KEY_G", 34, 35),
            new ScanKeyCode("KEY_H", 35, 36),
            new ScanKeyCode("KEY_I", 23, 37),
            new ScanKeyCode("KEY_J", 36, 38),
            new ScanKeyCode("KEY_K", 37, 39),
            new ScanKeyCode("KEY_L", 38, 40),
            new ScanKeyCode("KEY_M", 50, 41),
            new ScanKeyCode("KEY_N", 49, 42),
            new ScanKeyCode("KEY_O", 24, 43),
            new ScanKeyCode("KEY_P", 25, 44),
            new ScanKeyCode("KEY_Q", 16, 45),
            new ScanKeyCode("KEY_R", 19, 46),
            new ScanKeyCode("KEY_S", 31, 47),
            new ScanKeyCode("KEY_T", 20, 48),
            new ScanKeyCode("KEY_U", 22, 49),
            new ScanKeyCode("KEY_V", 47, 50),
            new ScanKeyCode("KEY_W", 17, 51),
            new ScanKeyCode("KEY_X", 45, 52),
            new ScanKeyCode("KEY_Y", 21, 53),
            new ScanKeyCode("KEY_Z", 44, 54),
            new ScanKeyCode("KEY_GRAVE", 40, 75),
            new ScanKeyCode("KEY_CTRL_L", 29, 113),
            new ScanKeyCode("KEY_CTRL_R", 97, 114),
            new ScanKeyCode("KEY_SHIFT_L", 42, 59),
            new ScanKeyCode("KEY_SHIFT_R", 54, 60),
            new ScanKeyCode("KEY_TAB", 15, 61),
            new ScanKeyCode("KEY_ALT_L", 56, 57),
            new ScanKeyCode("KEY_ALT_R", 100, 58),
            new ScanKeyCode("KEY_F1", 59, 131),
            new ScanKeyCode("KEY_F2", 60, 132),
            new ScanKeyCode("KEY_F3", 61, 133),
            new ScanKeyCode("KEY_F4", 62, 134),
            new ScanKeyCode("KEY_F5", 63, 135),
            new ScanKeyCode("KEY_F6", 64, 136),
            new ScanKeyCode("KEY_F7", 65, 137),
            new ScanKeyCode("KEY_F8", 66, 138),
            new ScanKeyCode("KEY_F9", 67, 139),
            new ScanKeyCode("KEY_F10", 68, 140),
            new ScanKeyCode("KEY_F11", 87, 141),
            new ScanKeyCode("KEY_F12", 88, 142),
            new ScanKeyCode("KEY_INSERT", 110, 124),
            new ScanKeyCode("KEY_HOME", 102, 122),
            new ScanKeyCode("KEY_PRIOR", 104, 92),
            new ScanKeyCode("KEY_DEL", 111, 112),
            new ScanKeyCode("KEY_END", 107, 123),
            new ScanKeyCode("KEY_NEXT", 109, 93),
            new ScanKeyCode("KEY_BKSP", 14, 67),
            new ScanKeyCode("KEY_0", 11, 7),
            new ScanKeyCode("KEY_1", 2, 8),
            new ScanKeyCode("KEY_2", 3, 9),
            new ScanKeyCode("KEY_3", 4, 10),
            new ScanKeyCode("KEY_4", 5, 11),
            new ScanKeyCode("KEY_5", 6, 12),
            new ScanKeyCode("KEY_6", 7, 13),
            new ScanKeyCode("KEY_7", 8, 14),
            new ScanKeyCode("KEY_8", 9, 15),
            new ScanKeyCode("KEY_9", 10, 16),
            new ScanKeyCode("KEY_MINUS", 12, 69),
            new ScanKeyCode("KEY_EQUAL", 13, 70),
            new ScanKeyCode("KEY_SEMICOLON", 39, 74),
            new ScanKeyCode("KEY_APOSTROPHE", 40, 75),
            new ScanKeyCode("KEY_BACKSLASH", 43, 73),
            new ScanKeyCode("KEY_COMMA", 51, 55),
            new ScanKeyCode("KEY_PERIOD", 52, 56),
            new ScanKeyCode("KEY_SLASH", 53, 76),
            new ScanKeyCode("KEY_CAPS_LOCK", 58, 115),
            new ScanKeyCode("KEY_NUM_LOCK", 69, 143),
            new ScanKeyCode("KEY_SCROLL_LOCK", 70, 116),
            new ScanKeyCode("KEY_KP_7", 71, 151),
            new ScanKeyCode("KEY_KP_8", 72, 152),
            new ScanKeyCode("KEY_KP_9", 73, 153),
            new ScanKeyCode("KEY_KP_SUBTRACT", 74, 156),
            new ScanKeyCode("KEY_KP_4", 75, 148),
            new ScanKeyCode("KEY_KP_5", 76, 149),
            new ScanKeyCode("KEY_KP_6", 77, 150),
            new ScanKeyCode("KEY_KP_ADD", 78, 157),
            new ScanKeyCode("KEY_KP_1", 79, 145),
            new ScanKeyCode("KEY_KP_2", 80, 146),
            new ScanKeyCode("KEY_KP_3", 81, 147),
            new ScanKeyCode("KEY_KP_0", 82, 144),
            new ScanKeyCode("KEY_KP_ENTER", 96, 160),
            new ScanKeyCode("KEY_KP_MULTIPLY", 55, 155),
            new ScanKeyCode("KEY_KP_DIVIDE", 98, 154),
            new ScanKeyCode("KEY_BRACKET_LEFT", 26, 71),
            new ScanKeyCode("KEY_BRACKET_RIGHT", 27, 72),
            new ScanKeyCode("KEY_PRTSCN", 210, 120),
            new ScanKeyCode("KEY_KP_DEL", 83, 158),
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
