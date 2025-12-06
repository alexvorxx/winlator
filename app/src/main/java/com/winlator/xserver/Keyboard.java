package com.winlator.xserver;

import android.view.KeyEvent;

import androidx.collection.ArraySet;
import androidx.core.view.PointerIconCompat;
import androidx.recyclerview.widget.ItemTouchHelper;

import com.winlator.inputcontrols.ExternalController;

import org.apache.commons.compress.archivers.tar.TarConstants;

import java.util.ArrayList;

public class Keyboard {
    public static final byte KEYSYMS_PER_KEYCODE = 2;
    public static final short KEYS_COUNT = 248;
    public static final short MAX_KEYCODE = 255;
    public static final short MIN_KEYCODE = 8;
    public final int[] keysyms = new int[KEYS_COUNT];
    private final Bitmask modifiersMask = new Bitmask();
    private final XKeycode[] keycodeMap = createKeycodeMap();
    private final ArraySet<Byte> pressedKeys = new ArraySet<>();
    private final ArrayList<OnKeyboardListener> onKeyboardListeners = new ArrayList<>();
    private final XServer xServer;

    public interface OnKeyboardListener {
        void onKeyPress(byte keycode, int keysym);

        void onKeyRelease(byte keycode);
    }

    public Keyboard(XServer xServer) {
        this.xServer = xServer;
    }

    public Bitmask getModifiersMask() {
        return modifiersMask;
    }

    public void setKeysyms(byte keycode, int minKeysym, int majKeysym) {
        int index = keycode - 8;
        keysyms[index*KEYSYMS_PER_KEYCODE+0] = minKeysym;
        keysyms[index*KEYSYMS_PER_KEYCODE+1] = majKeysym;
    }

    public boolean hasKeysym(byte keycode, int keysym) {
        int index = keycode - 8;
        return keysyms[index*KEYSYMS_PER_KEYCODE+0] == keysym || keysyms[index*KEYSYMS_PER_KEYCODE+1] == keysym;
    }

    public void setKeyPress(byte keycode, int keysym) {
        if (isModifierSticky(keycode)) {
            if (pressedKeys.contains(keycode)) {
                pressedKeys.remove(keycode);
                modifiersMask.unset(getModifierFlag(keycode));
                triggerOnKeyRelease(keycode);
            }
            else {
                pressedKeys.add(keycode);
                modifiersMask.set(getModifierFlag(keycode));
                triggerOnKeyPress(keycode, keysym);
            }
        }
        else if (!pressedKeys.contains(keycode)) {
            pressedKeys.add(keycode);
            if (isModifier(keycode)) modifiersMask.set(getModifierFlag(keycode));
            triggerOnKeyPress(keycode, keysym);
        }
    }

    public void setKeyRelease(byte keycode) {
        if (!isModifierSticky(keycode) && pressedKeys.contains(keycode)) {
            pressedKeys.remove(keycode);
            if (isModifier(keycode)) modifiersMask.unset(getModifierFlag(keycode));
            triggerOnKeyRelease(keycode);
        }
    }

    public void addOnKeyboardListener(OnKeyboardListener onKeyboardListener) {
        onKeyboardListeners.add(onKeyboardListener);
    }

    public void removeOnKeyboardListener(OnKeyboardListener onKeyboardListener) {
        onKeyboardListeners.remove(onKeyboardListener);
    }

    private void triggerOnKeyPress(byte keycode, int keysym) {
        for (int i = onKeyboardListeners.size()-1; i >= 0; i--) {
            onKeyboardListeners.get(i).onKeyPress(keycode, keysym);
        }
    }

    private void triggerOnKeyRelease(byte keycode) {
        for (int i = onKeyboardListeners.size()-1; i >= 0; i--) {
            onKeyboardListeners.get(i).onKeyRelease(keycode);
        }
    }

    public boolean onKeyEvent(KeyEvent event) {
        /* Taken from
        * https://forum.juce.com/t/unable-to-enter-cyrillic-symbols-with-software-keyboard/12132
         */

        int unicodeKeycode = event.getUnicodeChar();
        if (event.getCharacters() != null && unicodeKeycode == 0)
        {
            String chars = event.getCharacters();
            unicodeKeycode = chars.charAt(0);
            XKey convertedUnicodeToXKey = convertUnicodeToXKey(unicodeKeycode);
            if (convertedUnicodeToXKey != null) {
                xServer.injectKeyPress(convertedUnicodeToXKey.keycode, convertedUnicodeToXKey.keysym);
                xServer.injectKeyRelease(convertedUnicodeToXKey.keycode);
            }
        }

        if (ExternalController.isGameController(event.getDevice())) return false;

        int action = event.getAction();
        int keyCode = event.getKeyCode();

        if (keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (action == KeyEvent.ACTION_DOWN) {
                xServer.injectKeyPress(keycodeMap[keyCode]);
                return true;
            } else if (action == KeyEvent.ACTION_UP) {
                xServer.injectKeyRelease(keycodeMap[keyCode]);
                return true;
            }
        } else if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
            XKeycode xKeycode = keycodeMap[keyCode];
            if (xKeycode == null) return false;

            if (action == KeyEvent.ACTION_DOWN) {
                boolean shiftPressed = event.isShiftPressed() || keyCode == KeyEvent.KEYCODE_AT || keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_POUND || keyCode == KeyEvent.KEYCODE_PLUS;
                if (shiftPressed) xServer.injectKeyPress(XKeycode.KEY_SHIFT_L);
                xServer.injectKeyPress(xKeycode, xKeycode != XKeycode.KEY_ENTER ? event.getUnicodeChar() : 0);
            }
            else if (action == KeyEvent.ACTION_UP) {
                xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L);
                xServer.injectKeyRelease(xKeycode);
            }
        }
        return true;
    }

    private static XKeycode[] createKeycodeMap() {
        XKeycode[] keycodeMap = new XKeycode[(KeyEvent.getMaxKeyCode() + 1)];
        keycodeMap[KeyEvent.KEYCODE_ESCAPE] = XKeycode.KEY_ESC;
        keycodeMap[KeyEvent.KEYCODE_ENTER] = XKeycode.KEY_ENTER;
        keycodeMap[KeyEvent.KEYCODE_DPAD_LEFT] = XKeycode.KEY_LEFT;
        keycodeMap[KeyEvent.KEYCODE_DPAD_RIGHT] = XKeycode.KEY_RIGHT;
        keycodeMap[KeyEvent.KEYCODE_DPAD_UP] = XKeycode.KEY_UP;
        keycodeMap[KeyEvent.KEYCODE_DPAD_DOWN] = XKeycode.KEY_DOWN;
        keycodeMap[KeyEvent.KEYCODE_DEL] = XKeycode.KEY_BKSP;
        keycodeMap[KeyEvent.KEYCODE_INSERT] = XKeycode.KEY_INSERT;
        keycodeMap[KeyEvent.KEYCODE_FORWARD_DEL] = XKeycode.KEY_DEL;
        keycodeMap[KeyEvent.KEYCODE_MOVE_HOME] = XKeycode.KEY_HOME;
        keycodeMap[KeyEvent.KEYCODE_MOVE_END] = XKeycode.KEY_END;
        keycodeMap[KeyEvent.KEYCODE_PAGE_UP] = XKeycode.KEY_PRIOR;
        keycodeMap[KeyEvent.KEYCODE_PAGE_DOWN] = XKeycode.KEY_NEXT;
        keycodeMap[KeyEvent.KEYCODE_SHIFT_LEFT] = XKeycode.KEY_SHIFT_L;
        keycodeMap[KeyEvent.KEYCODE_SHIFT_RIGHT] = XKeycode.KEY_SHIFT_R;
        keycodeMap[KeyEvent.KEYCODE_CTRL_LEFT] = XKeycode.KEY_CTRL_L;
        keycodeMap[KeyEvent.KEYCODE_CTRL_RIGHT] = XKeycode.KEY_CTRL_R;
        keycodeMap[KeyEvent.KEYCODE_ALT_LEFT] = XKeycode.KEY_ALT_L;
        keycodeMap[KeyEvent.KEYCODE_ALT_RIGHT] = XKeycode.KEY_ALT_R;
        keycodeMap[KeyEvent.KEYCODE_TAB] = XKeycode.KEY_TAB;
        keycodeMap[KeyEvent.KEYCODE_SPACE] = XKeycode.KEY_SPACE;
        keycodeMap[KeyEvent.KEYCODE_A] = XKeycode.KEY_A;
        keycodeMap[KeyEvent.KEYCODE_B] = XKeycode.KEY_B;
        keycodeMap[KeyEvent.KEYCODE_C] = XKeycode.KEY_C;
        keycodeMap[KeyEvent.KEYCODE_D] = XKeycode.KEY_D;
        keycodeMap[KeyEvent.KEYCODE_E] = XKeycode.KEY_E;
        keycodeMap[KeyEvent.KEYCODE_F] = XKeycode.KEY_F;
        keycodeMap[KeyEvent.KEYCODE_G] = XKeycode.KEY_G;
        keycodeMap[KeyEvent.KEYCODE_H] = XKeycode.KEY_H;
        keycodeMap[KeyEvent.KEYCODE_I] = XKeycode.KEY_I;
        keycodeMap[KeyEvent.KEYCODE_J] = XKeycode.KEY_J;
        keycodeMap[KeyEvent.KEYCODE_K] = XKeycode.KEY_K;
        keycodeMap[KeyEvent.KEYCODE_L] = XKeycode.KEY_L;
        keycodeMap[KeyEvent.KEYCODE_M] = XKeycode.KEY_M;
        keycodeMap[KeyEvent.KEYCODE_N] = XKeycode.KEY_N;
        keycodeMap[KeyEvent.KEYCODE_O] = XKeycode.KEY_O;
        keycodeMap[KeyEvent.KEYCODE_P] = XKeycode.KEY_P;
        keycodeMap[KeyEvent.KEYCODE_Q] = XKeycode.KEY_Q;
        keycodeMap[KeyEvent.KEYCODE_R] = XKeycode.KEY_R;
        keycodeMap[KeyEvent.KEYCODE_S] = XKeycode.KEY_S;
        keycodeMap[KeyEvent.KEYCODE_T] = XKeycode.KEY_T;
        keycodeMap[KeyEvent.KEYCODE_U] = XKeycode.KEY_U;
        keycodeMap[KeyEvent.KEYCODE_V] = XKeycode.KEY_V;
        keycodeMap[KeyEvent.KEYCODE_W] = XKeycode.KEY_W;
        keycodeMap[KeyEvent.KEYCODE_X] = XKeycode.KEY_X;
        keycodeMap[KeyEvent.KEYCODE_Y] = XKeycode.KEY_Y;
        keycodeMap[KeyEvent.KEYCODE_Z] = XKeycode.KEY_Z;
        keycodeMap[KeyEvent.KEYCODE_0] = XKeycode.KEY_0;
        keycodeMap[KeyEvent.KEYCODE_1] = XKeycode.KEY_1;
        keycodeMap[KeyEvent.KEYCODE_2] = XKeycode.KEY_2;
        keycodeMap[KeyEvent.KEYCODE_3] = XKeycode.KEY_3;
        keycodeMap[KeyEvent.KEYCODE_4] = XKeycode.KEY_4;
        keycodeMap[KeyEvent.KEYCODE_5] = XKeycode.KEY_5;
        keycodeMap[KeyEvent.KEYCODE_6] = XKeycode.KEY_6;
        keycodeMap[KeyEvent.KEYCODE_7] = XKeycode.KEY_7;
        keycodeMap[KeyEvent.KEYCODE_8] = XKeycode.KEY_8;
        keycodeMap[KeyEvent.KEYCODE_9] = XKeycode.KEY_9;
        keycodeMap[KeyEvent.KEYCODE_STAR] = XKeycode.KEY_8;
        keycodeMap[KeyEvent.KEYCODE_POUND] = XKeycode.KEY_3;
        keycodeMap[KeyEvent.KEYCODE_COMMA] = XKeycode.KEY_COMMA;
        keycodeMap[KeyEvent.KEYCODE_PERIOD] = XKeycode.KEY_PERIOD;
        keycodeMap[KeyEvent.KEYCODE_SEMICOLON] = XKeycode.KEY_SEMICOLON;
        keycodeMap[KeyEvent.KEYCODE_APOSTROPHE] = XKeycode.KEY_APOSTROPHE;
        keycodeMap[KeyEvent.KEYCODE_LEFT_BRACKET] = XKeycode.KEY_BRACKET_LEFT;
        keycodeMap[KeyEvent.KEYCODE_RIGHT_BRACKET] = XKeycode.KEY_BRACKET_RIGHT;
        keycodeMap[KeyEvent.KEYCODE_GRAVE] = XKeycode.KEY_GRAVE;
        keycodeMap[KeyEvent.KEYCODE_MINUS] = XKeycode.KEY_MINUS;
        keycodeMap[KeyEvent.KEYCODE_PLUS] = XKeycode.KEY_EQUAL;
        keycodeMap[KeyEvent.KEYCODE_EQUALS] = XKeycode.KEY_EQUAL;
        keycodeMap[KeyEvent.KEYCODE_SLASH] = XKeycode.KEY_SLASH;
        keycodeMap[KeyEvent.KEYCODE_AT] = XKeycode.KEY_2;
        keycodeMap[KeyEvent.KEYCODE_BACKSLASH] = XKeycode.KEY_BACKSLASH;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_DIVIDE] = XKeycode.KEY_KP_DIVIDE;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_MULTIPLY] = XKeycode.KEY_KP_MULTIPLY;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_SUBTRACT] = XKeycode.KEY_KP_SUBTRACT;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_ADD] = XKeycode.KEY_KP_ADD;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_DOT] = XKeycode.KEY_KP_DEL;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_0] = XKeycode.KEY_KP_0;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_1] = XKeycode.KEY_KP_1;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_2] = XKeycode.KEY_KP_2;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_3] = XKeycode.KEY_KP_3;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_4] = XKeycode.KEY_KP_4;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_5] = XKeycode.KEY_KP_5;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_6] = XKeycode.KEY_KP_6;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_7] = XKeycode.KEY_KP_7;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_8] = XKeycode.KEY_KP_8;
        keycodeMap[KeyEvent.KEYCODE_NUMPAD_9] = XKeycode.KEY_KP_9;
        keycodeMap[KeyEvent.KEYCODE_F1] = XKeycode.KEY_F1;
        keycodeMap[KeyEvent.KEYCODE_F2] = XKeycode.KEY_F2;
        keycodeMap[KeyEvent.KEYCODE_F3] = XKeycode.KEY_F3;
        keycodeMap[KeyEvent.KEYCODE_F4] = XKeycode.KEY_F4;
        keycodeMap[KeyEvent.KEYCODE_F5] = XKeycode.KEY_F5;
        keycodeMap[KeyEvent.KEYCODE_F6] = XKeycode.KEY_F6;
        keycodeMap[KeyEvent.KEYCODE_F7] = XKeycode.KEY_F7;
        keycodeMap[KeyEvent.KEYCODE_F8] = XKeycode.KEY_F8;
        keycodeMap[KeyEvent.KEYCODE_F9] = XKeycode.KEY_F9;
        keycodeMap[KeyEvent.KEYCODE_F10] = XKeycode.KEY_F10;
        keycodeMap[KeyEvent.KEYCODE_F11] = XKeycode.KEY_F11;
        keycodeMap[KeyEvent.KEYCODE_F12] = XKeycode.KEY_F12;
        keycodeMap[KeyEvent.KEYCODE_NUM_LOCK] = XKeycode.KEY_NUM_LOCK;
        keycodeMap[KeyEvent.KEYCODE_CAPS_LOCK] = XKeycode.KEY_CAPS_LOCK;
        return keycodeMap;
    }

    public static Keyboard createKeyboard(XServer xServer) {
        Keyboard keyboard = new Keyboard(xServer);
        keyboard.setKeysyms(XKeycode.KEY_ESC.id, 65307, 0);
        keyboard.setKeysyms(XKeycode.KEY_ENTER.id, 65293, 0);
        keyboard.setKeysyms(XKeycode.KEY_RIGHT.id, 65363, 0);
        keyboard.setKeysyms(XKeycode.KEY_UP.id, 65362, 0);
        keyboard.setKeysyms(XKeycode.KEY_LEFT.id, 65361, 0);
        keyboard.setKeysyms(XKeycode.KEY_DOWN.id, 65364, 0);
        keyboard.setKeysyms(XKeycode.KEY_DEL.id, 65535, 0);
        keyboard.setKeysyms(XKeycode.KEY_BKSP.id, 65288, 0);
        keyboard.setKeysyms(XKeycode.KEY_INSERT.id, 65379, 0);
        keyboard.setKeysyms(XKeycode.KEY_PRIOR.id, 65365, 0);
        keyboard.setKeysyms(XKeycode.KEY_NEXT.id, 65366, 0);
        keyboard.setKeysyms(XKeycode.KEY_HOME.id, 65360, 0);
        keyboard.setKeysyms(XKeycode.KEY_END.id, 65367, 0);
        keyboard.setKeysyms(XKeycode.KEY_SHIFT_L.id, 65505, 0);
        keyboard.setKeysyms(XKeycode.KEY_SHIFT_R.id, 65506, 0);
        keyboard.setKeysyms(XKeycode.KEY_CTRL_L.id, 65507, 0);
        keyboard.setKeysyms(XKeycode.KEY_CTRL_R.id, 65508, 0);
        keyboard.setKeysyms(XKeycode.KEY_ALT_L.id, 65511, 0);
        keyboard.setKeysyms(XKeycode.KEY_ALT_R.id, 65512, 0);
        keyboard.setKeysyms(XKeycode.KEY_TAB.id, 65289, 0);
        keyboard.setKeysyms(XKeycode.KEY_SPACE.id, 32, 32);
        keyboard.setKeysyms(XKeycode.KEY_A.id, 97, 65);
        keyboard.setKeysyms(XKeycode.KEY_B.id, 98, 66);
        keyboard.setKeysyms(XKeycode.KEY_C.id, 99, 67);
        keyboard.setKeysyms(XKeycode.KEY_D.id, 100, 68);
        keyboard.setKeysyms(XKeycode.KEY_E.id, 101, 69);
        keyboard.setKeysyms(XKeycode.KEY_F.id, 102, 70);
        keyboard.setKeysyms(XKeycode.KEY_G.id, 103, 71);
        keyboard.setKeysyms(XKeycode.KEY_H.id, 104, 72);
        keyboard.setKeysyms(XKeycode.KEY_I.id, 105, 73);
        keyboard.setKeysyms(XKeycode.KEY_J.id, 106, 74);
        keyboard.setKeysyms(XKeycode.KEY_K.id, 107, 75);
        keyboard.setKeysyms(XKeycode.KEY_L.id, 108, 76);
        keyboard.setKeysyms(XKeycode.KEY_M.id, 109, 77);
        keyboard.setKeysyms(XKeycode.KEY_N.id, 110, 78);
        keyboard.setKeysyms(XKeycode.KEY_O.id, 111, 79);
        keyboard.setKeysyms(XKeycode.KEY_P.id, 112, 80);
        keyboard.setKeysyms(XKeycode.KEY_Q.id, 113, 81);
        keyboard.setKeysyms(XKeycode.KEY_R.id, 114, 82);
        keyboard.setKeysyms(XKeycode.KEY_S.id, 115, 83);
        keyboard.setKeysyms(XKeycode.KEY_T.id, 116, 84);
        keyboard.setKeysyms(XKeycode.KEY_U.id, 117, 85);
        keyboard.setKeysyms(XKeycode.KEY_V.id, 118, 86);
        keyboard.setKeysyms(XKeycode.KEY_W.id, 119, 87);
        keyboard.setKeysyms(XKeycode.KEY_X.id, 120, 88);
        keyboard.setKeysyms(XKeycode.KEY_Y.id, 121, 89);
        keyboard.setKeysyms(XKeycode.KEY_Z.id, 122, 90);
        keyboard.setKeysyms(XKeycode.KEY_1.id, 49, 33);
        keyboard.setKeysyms(XKeycode.KEY_2.id, 50, 64);
        keyboard.setKeysyms(XKeycode.KEY_3.id, 51, 35);
        keyboard.setKeysyms(XKeycode.KEY_4.id, 52, 36);
        keyboard.setKeysyms(XKeycode.KEY_5.id, 53, 37);
        keyboard.setKeysyms(XKeycode.KEY_6.id, 54, 94);
        keyboard.setKeysyms(XKeycode.KEY_7.id, 55, 38);
        keyboard.setKeysyms(XKeycode.KEY_8.id, 56, 42);
        keyboard.setKeysyms(XKeycode.KEY_9.id, 57, 40);
        keyboard.setKeysyms(XKeycode.KEY_0.id, 48, 41);
        keyboard.setKeysyms(XKeycode.KEY_COMMA.id, 44, 60);
        keyboard.setKeysyms(XKeycode.KEY_PERIOD.id, 46, 62);
        keyboard.setKeysyms(XKeycode.KEY_SEMICOLON.id, 59, 58);
        keyboard.setKeysyms(XKeycode.KEY_APOSTROPHE.id, 39, 34);
        keyboard.setKeysyms(XKeycode.KEY_BRACKET_LEFT.id, 91, 123);
        keyboard.setKeysyms(XKeycode.KEY_BRACKET_RIGHT.id, 93, 125);
        keyboard.setKeysyms(XKeycode.KEY_GRAVE.id, 96, 126);
        keyboard.setKeysyms(XKeycode.KEY_MINUS.id, 45, 95);
        keyboard.setKeysyms(XKeycode.KEY_EQUAL.id, 61, 43);
        keyboard.setKeysyms(XKeycode.KEY_SLASH.id, 47, 63);
        keyboard.setKeysyms(XKeycode.KEY_BACKSLASH.id, 92, 124);
        keyboard.setKeysyms(XKeycode.KEY_KP_DIVIDE.id, 65455, 65455);
        keyboard.setKeysyms(XKeycode.KEY_KP_MULTIPLY.id, 65450, 65450);
        keyboard.setKeysyms(XKeycode.KEY_KP_SUBTRACT.id, 65453, 65453);
        keyboard.setKeysyms(XKeycode.KEY_KP_ADD.id, 65451, 65451);
        keyboard.setKeysyms(XKeycode.KEY_KP_0.id, 65456, 65438);
        keyboard.setKeysyms(XKeycode.KEY_KP_1.id, 65457, 65436);
        keyboard.setKeysyms(XKeycode.KEY_KP_2.id, 65458, 65433);
        keyboard.setKeysyms(XKeycode.KEY_KP_3.id, 65459, 65459);
        keyboard.setKeysyms(XKeycode.KEY_KP_4.id, 65460, 65430);
        keyboard.setKeysyms(XKeycode.KEY_KP_5.id, 65461, 65461);
        keyboard.setKeysyms(XKeycode.KEY_KP_6.id, 65462, 65432);
        keyboard.setKeysyms(XKeycode.KEY_KP_7.id, 65463, 65429);
        keyboard.setKeysyms(XKeycode.KEY_KP_8.id, 65464, 65431);
        keyboard.setKeysyms(XKeycode.KEY_KP_9.id, 65465, 65465);
        keyboard.setKeysyms(XKeycode.KEY_KP_DEL.id, 65439, 0);
        keyboard.setKeysyms(XKeycode.KEY_F1.id, 65470, 0);
        keyboard.setKeysyms(XKeycode.KEY_F2.id, 65471, 0);
        keyboard.setKeysyms(XKeycode.KEY_F3.id, 65472, 0);
        keyboard.setKeysyms(XKeycode.KEY_F4.id, 65473, 0);
        keyboard.setKeysyms(XKeycode.KEY_F5.id, 65474, 0);
        keyboard.setKeysyms(XKeycode.KEY_F6.id, 65475, 0);
        keyboard.setKeysyms(XKeycode.KEY_F7.id, 65476, 0);
        keyboard.setKeysyms(XKeycode.KEY_F8.id, 65477, 0);
        keyboard.setKeysyms(XKeycode.KEY_F9.id, 65478, 0);
        keyboard.setKeysyms(XKeycode.KEY_F10.id, 65479, 0);
        keyboard.setKeysyms(XKeycode.KEY_F11.id, 65480, 0);
        keyboard.setKeysyms(XKeycode.KEY_F12.id, 65481, 0);

        constructUnicodeToXKeyMap();

        return keyboard;
    }

    public static boolean isModifier(byte keycode) {
        return
            keycode == XKeycode.KEY_SHIFT_L.id ||
            keycode == XKeycode.KEY_SHIFT_R.id ||
            keycode == XKeycode.KEY_CTRL_L.id ||
            keycode == XKeycode.KEY_CTRL_R.id ||
            keycode == XKeycode.KEY_ALT_L.id ||
            keycode == XKeycode.KEY_ALT_R.id ||
            keycode == XKeycode.KEY_CAPS_LOCK.id ||
            keycode == XKeycode.KEY_NUM_LOCK.id
        ;
    }

    public static int getModifierFlag(byte keycode) {
        if (keycode == XKeycode.KEY_SHIFT_L.id || keycode == XKeycode.KEY_SHIFT_R.id) {
            return 1;
        }
        else if (keycode == XKeycode.KEY_CAPS_LOCK.id) {
            return 2;
        }
        else if (keycode == XKeycode.KEY_CTRL_L.id || keycode == XKeycode.KEY_CTRL_R.id) {
            return 4;
        }
        else if (keycode == XKeycode.KEY_ALT_L.id || keycode == XKeycode.KEY_ALT_R.id) {
            return 8;
        }
        else if (keycode == XKeycode.KEY_NUM_LOCK.id) {
            return 16;
        }
        return 0;
    }

    public static boolean isModifierSticky(byte keycode) {
        return keycode == XKeycode.KEY_CAPS_LOCK.id || keycode == XKeycode.KEY_NUM_LOCK.id;
    }

    /* Taken from
     * https://github.com/khanhduytran0/exagear_windows_emulator/blob/master/app/src/main/java/com/eltechs/axs/Keyboard.java
    */

    private static class XKey {
        public XKeycode keycode;
        public int keysym;

        public XKey(XKeycode keyCodesX, int i) {
            this.keycode = keyCodesX;
            this.keysym = i;
        }
    }

    private final static XKey[] UnicodeToXKeyMap = new XKey[65536];

    private XKey convertUnicodeToXKey(int i) {
        return UnicodeToXKeyMap[i];
    }

    private static void constructUnicodeToXKeyMap() {
        UnicodeToXKeyMap[32] = new XKey(XKeycode.KEY_A, 32);
        UnicodeToXKeyMap[33] = new XKey(XKeycode.KEY_B, 33);
        UnicodeToXKeyMap[34] = new XKey(XKeycode.KEY_C, 34);
        UnicodeToXKeyMap[35] = new XKey(XKeycode.KEY_D, 35);
        UnicodeToXKeyMap[36] = new XKey(XKeycode.KEY_E, 36);
        UnicodeToXKeyMap[37] = new XKey(XKeycode.KEY_F, 37);
        UnicodeToXKeyMap[38] = new XKey(XKeycode.KEY_G, 38);
        UnicodeToXKeyMap[39] = new XKey(XKeycode.KEY_H, 39);
        UnicodeToXKeyMap[40] = new XKey(XKeycode.KEY_I, 40);
        UnicodeToXKeyMap[41] = new XKey(XKeycode.KEY_J, 41);
        UnicodeToXKeyMap[42] = new XKey(XKeycode.KEY_K, 42);
        UnicodeToXKeyMap[43] = new XKey(XKeycode.KEY_L, 43);
        UnicodeToXKeyMap[44] = new XKey(XKeycode.KEY_M, 44);
        UnicodeToXKeyMap[45] = new XKey(XKeycode.KEY_N, 45);
        UnicodeToXKeyMap[46] = new XKey(XKeycode.KEY_O, 46);
        UnicodeToXKeyMap[47] = new XKey(XKeycode.KEY_P, 47);
        UnicodeToXKeyMap[48] = new XKey(XKeycode.KEY_Q, 48);
        UnicodeToXKeyMap[49] = new XKey(XKeycode.KEY_R, 49);
        UnicodeToXKeyMap[50] = new XKey(XKeycode.KEY_S, 50);
        UnicodeToXKeyMap[51] = new XKey(XKeycode.KEY_T, 51);
        UnicodeToXKeyMap[52] = new XKey(XKeycode.KEY_U, 52);
        UnicodeToXKeyMap[53] = new XKey(XKeycode.KEY_V, 53);
        UnicodeToXKeyMap[54] = new XKey(XKeycode.KEY_W, 54);
        UnicodeToXKeyMap[55] = new XKey(XKeycode.KEY_X, 55);
        UnicodeToXKeyMap[56] = new XKey(XKeycode.KEY_Y, 56);
        UnicodeToXKeyMap[57] = new XKey(XKeycode.KEY_Z, 57);
        UnicodeToXKeyMap[58] = new XKey(XKeycode.KEY_A, 58);
        UnicodeToXKeyMap[59] = new XKey(XKeycode.KEY_B, 59);
        UnicodeToXKeyMap[60] = new XKey(XKeycode.KEY_C, 60);
        UnicodeToXKeyMap[61] = new XKey(XKeycode.KEY_D, 61);
        UnicodeToXKeyMap[62] = new XKey(XKeycode.KEY_E, 62);
        UnicodeToXKeyMap[63] = new XKey(XKeycode.KEY_F, 63);
        UnicodeToXKeyMap[64] = new XKey(XKeycode.KEY_G, 64);
        UnicodeToXKeyMap[65] = new XKey(XKeycode.KEY_H, 65);
        UnicodeToXKeyMap[66] = new XKey(XKeycode.KEY_I, 66);
        UnicodeToXKeyMap[67] = new XKey(XKeycode.KEY_J, 67);
        UnicodeToXKeyMap[68] = new XKey(XKeycode.KEY_K, 68);
        UnicodeToXKeyMap[69] = new XKey(XKeycode.KEY_L, 69);
        UnicodeToXKeyMap[70] = new XKey(XKeycode.KEY_M, 70);
        UnicodeToXKeyMap[71] = new XKey(XKeycode.KEY_N, 71);
        UnicodeToXKeyMap[72] = new XKey(XKeycode.KEY_O, 72);
        UnicodeToXKeyMap[73] = new XKey(XKeycode.KEY_P, 73);
        UnicodeToXKeyMap[74] = new XKey(XKeycode.KEY_Q, 74);
        UnicodeToXKeyMap[75] = new XKey(XKeycode.KEY_R, 75);
        UnicodeToXKeyMap[76] = new XKey(XKeycode.KEY_S, 76);
        UnicodeToXKeyMap[77] = new XKey(XKeycode.KEY_T, 77);
        UnicodeToXKeyMap[78] = new XKey(XKeycode.KEY_U, 78);
        UnicodeToXKeyMap[79] = new XKey(XKeycode.KEY_V, 79);
        UnicodeToXKeyMap[80] = new XKey(XKeycode.KEY_W, 80);
        UnicodeToXKeyMap[81] = new XKey(XKeycode.KEY_X, 81);
        UnicodeToXKeyMap[82] = new XKey(XKeycode.KEY_Y, 82);
        UnicodeToXKeyMap[83] = new XKey(XKeycode.KEY_Z, 83);
        UnicodeToXKeyMap[84] = new XKey(XKeycode.KEY_A, 84);
        UnicodeToXKeyMap[85] = new XKey(XKeycode.KEY_B, 85);
        UnicodeToXKeyMap[86] = new XKey(XKeycode.KEY_C, 86);
        UnicodeToXKeyMap[87] = new XKey(XKeycode.KEY_D, 87);
        UnicodeToXKeyMap[88] = new XKey(XKeycode.KEY_E, 88);
        UnicodeToXKeyMap[89] = new XKey(XKeycode.KEY_F, 89);
        UnicodeToXKeyMap[90] = new XKey(XKeycode.KEY_G, 90);
        UnicodeToXKeyMap[91] = new XKey(XKeycode.KEY_H, 91);
        UnicodeToXKeyMap[92] = new XKey(XKeycode.KEY_I, 92);
        UnicodeToXKeyMap[93] = new XKey(XKeycode.KEY_J, 93);
        UnicodeToXKeyMap[94] = new XKey(XKeycode.KEY_K, 94);
        UnicodeToXKeyMap[95] = new XKey(XKeycode.KEY_L, 95);
        UnicodeToXKeyMap[96] = new XKey(XKeycode.KEY_M, 96);
        UnicodeToXKeyMap[97] = new XKey(XKeycode.KEY_N, 97);
        UnicodeToXKeyMap[98] = new XKey(XKeycode.KEY_O, 98);
        UnicodeToXKeyMap[99] = new XKey(XKeycode.KEY_P, 99);
        UnicodeToXKeyMap[100] = new XKey(XKeycode.KEY_Q, 100);
        UnicodeToXKeyMap[101] = new XKey(XKeycode.KEY_R, 101);
        UnicodeToXKeyMap[102] = new XKey(XKeycode.KEY_S, 102);
        UnicodeToXKeyMap[103] = new XKey(XKeycode.KEY_T, 103);
        UnicodeToXKeyMap[104] = new XKey(XKeycode.KEY_U, 104);
        UnicodeToXKeyMap[105] = new XKey(XKeycode.KEY_V, 105);
        UnicodeToXKeyMap[106] = new XKey(XKeycode.KEY_W, 106);
        UnicodeToXKeyMap[107] = new XKey(XKeycode.KEY_X, 107);
        UnicodeToXKeyMap[108] = new XKey(XKeycode.KEY_Y, 108);
        UnicodeToXKeyMap[109] = new XKey(XKeycode.KEY_Z, 109);
        UnicodeToXKeyMap[110] = new XKey(XKeycode.KEY_A, 110);
        UnicodeToXKeyMap[111] = new XKey(XKeycode.KEY_B, 111);
        UnicodeToXKeyMap[112] = new XKey(XKeycode.KEY_C, 112);
        UnicodeToXKeyMap[113] = new XKey(XKeycode.KEY_D, 113);
        UnicodeToXKeyMap[114] = new XKey(XKeycode.KEY_E, 114);
        UnicodeToXKeyMap[115] = new XKey(XKeycode.KEY_F, 115);
        UnicodeToXKeyMap[116] = new XKey(XKeycode.KEY_G, 116);
        UnicodeToXKeyMap[117] = new XKey(XKeycode.KEY_H, 117);
        UnicodeToXKeyMap[118] = new XKey(XKeycode.KEY_I, 118);
        UnicodeToXKeyMap[119] = new XKey(XKeycode.KEY_J, 119);
        UnicodeToXKeyMap[120] = new XKey(XKeycode.KEY_K, 120);
        UnicodeToXKeyMap[121] = new XKey(XKeycode.KEY_L, 121);
        UnicodeToXKeyMap[122] = new XKey(XKeycode.KEY_M, 122);
        UnicodeToXKeyMap[123] = new XKey(XKeycode.KEY_N, 123);
        UnicodeToXKeyMap[124] = new XKey(XKeycode.KEY_O, 124);
        UnicodeToXKeyMap[125] = new XKey(XKeycode.KEY_P, 125);
        UnicodeToXKeyMap[126] = new XKey(XKeycode.KEY_Q, 126);
        UnicodeToXKeyMap[160] = new XKey(XKeycode.KEY_R, 160);
        UnicodeToXKeyMap[161] = new XKey(XKeycode.KEY_S, 161);
        UnicodeToXKeyMap[162] = new XKey(XKeycode.KEY_T, 162);
        UnicodeToXKeyMap[163] = new XKey(XKeycode.KEY_U, 163);
        UnicodeToXKeyMap[164] = new XKey(XKeycode.KEY_V, 164);
        UnicodeToXKeyMap[165] = new XKey(XKeycode.KEY_W, 165);
        UnicodeToXKeyMap[166] = new XKey(XKeycode.KEY_X, 166);
        UnicodeToXKeyMap[167] = new XKey(XKeycode.KEY_Y, 167);
        UnicodeToXKeyMap[168] = new XKey(XKeycode.KEY_Z, 168);
        UnicodeToXKeyMap[169] = new XKey(XKeycode.KEY_A, 169);
        UnicodeToXKeyMap[170] = new XKey(XKeycode.KEY_B, 170);
        UnicodeToXKeyMap[171] = new XKey(XKeycode.KEY_C, 171);
        UnicodeToXKeyMap[172] = new XKey(XKeycode.KEY_D, 172);
        UnicodeToXKeyMap[173] = new XKey(XKeycode.KEY_E, 173);
        UnicodeToXKeyMap[174] = new XKey(XKeycode.KEY_F, 174);
        UnicodeToXKeyMap[175] = new XKey(XKeycode.KEY_G, 175);
        UnicodeToXKeyMap[176] = new XKey(XKeycode.KEY_H, 176);
        UnicodeToXKeyMap[177] = new XKey(XKeycode.KEY_I, 177);
        UnicodeToXKeyMap[178] = new XKey(XKeycode.KEY_J, 178);
        UnicodeToXKeyMap[179] = new XKey(XKeycode.KEY_K, 179);
        UnicodeToXKeyMap[180] = new XKey(XKeycode.KEY_L, 180);
        UnicodeToXKeyMap[181] = new XKey(XKeycode.KEY_M, 181);
        UnicodeToXKeyMap[182] = new XKey(XKeycode.KEY_N, 182);
        UnicodeToXKeyMap[183] = new XKey(XKeycode.KEY_O, 183);
        UnicodeToXKeyMap[184] = new XKey(XKeycode.KEY_P, 184);
        UnicodeToXKeyMap[185] = new XKey(XKeycode.KEY_Q, 185);
        UnicodeToXKeyMap[186] = new XKey(XKeycode.KEY_R, 186);
        UnicodeToXKeyMap[187] = new XKey(XKeycode.KEY_S, 187);
        UnicodeToXKeyMap[188] = new XKey(XKeycode.KEY_T, 188);
        UnicodeToXKeyMap[189] = new XKey(XKeycode.KEY_U, 189);
        UnicodeToXKeyMap[190] = new XKey(XKeycode.KEY_V, 190);
        UnicodeToXKeyMap[191] = new XKey(XKeycode.KEY_W, 191);
        UnicodeToXKeyMap[192] = new XKey(XKeycode.KEY_X, 192);
        UnicodeToXKeyMap[193] = new XKey(XKeycode.KEY_Y, 193);
        UnicodeToXKeyMap[194] = new XKey(XKeycode.KEY_Z, 194);
        UnicodeToXKeyMap[195] = new XKey(XKeycode.KEY_A, 195);
        UnicodeToXKeyMap[196] = new XKey(XKeycode.KEY_B, 196);
        UnicodeToXKeyMap[197] = new XKey(XKeycode.KEY_C, 197);
        UnicodeToXKeyMap[198] = new XKey(XKeycode.KEY_D, 198);
        UnicodeToXKeyMap[199] = new XKey(XKeycode.KEY_E, 199);
        UnicodeToXKeyMap[200] = new XKey(XKeycode.KEY_F, 200);
        UnicodeToXKeyMap[201] = new XKey(XKeycode.KEY_G, 201);
        UnicodeToXKeyMap[202] = new XKey(XKeycode.KEY_H, 202);
        UnicodeToXKeyMap[203] = new XKey(XKeycode.KEY_I, 203);
        UnicodeToXKeyMap[204] = new XKey(XKeycode.KEY_J, 204);
        UnicodeToXKeyMap[205] = new XKey(XKeycode.KEY_K, 205);
        UnicodeToXKeyMap[206] = new XKey(XKeycode.KEY_L, 206);
        UnicodeToXKeyMap[207] = new XKey(XKeycode.KEY_M, 207);
        UnicodeToXKeyMap[208] = new XKey(XKeycode.KEY_N, 208);
        UnicodeToXKeyMap[209] = new XKey(XKeycode.KEY_O, 209);
        UnicodeToXKeyMap[210] = new XKey(XKeycode.KEY_P, 210);
        UnicodeToXKeyMap[211] = new XKey(XKeycode.KEY_Q, 211);
        UnicodeToXKeyMap[212] = new XKey(XKeycode.KEY_R, 212);
        UnicodeToXKeyMap[213] = new XKey(XKeycode.KEY_S, 213);
        UnicodeToXKeyMap[214] = new XKey(XKeycode.KEY_T, 214);
        UnicodeToXKeyMap[215] = new XKey(XKeycode.KEY_U, 215);
        UnicodeToXKeyMap[216] = new XKey(XKeycode.KEY_V, 216);
        UnicodeToXKeyMap[217] = new XKey(XKeycode.KEY_W, 217);
        UnicodeToXKeyMap[218] = new XKey(XKeycode.KEY_X, 218);
        UnicodeToXKeyMap[219] = new XKey(XKeycode.KEY_Y, 219);
        UnicodeToXKeyMap[220] = new XKey(XKeycode.KEY_Z, 220);
        UnicodeToXKeyMap[221] = new XKey(XKeycode.KEY_A, 221);
        UnicodeToXKeyMap[222] = new XKey(XKeycode.KEY_B, 222);
        UnicodeToXKeyMap[223] = new XKey(XKeycode.KEY_C, 223);
        UnicodeToXKeyMap[224] = new XKey(XKeycode.KEY_D, 224);
        UnicodeToXKeyMap[225] = new XKey(XKeycode.KEY_E, 225);
        UnicodeToXKeyMap[226] = new XKey(XKeycode.KEY_F, 226);
        UnicodeToXKeyMap[227] = new XKey(XKeycode.KEY_G, 227);
        UnicodeToXKeyMap[228] = new XKey(XKeycode.KEY_H, 228);
        UnicodeToXKeyMap[229] = new XKey(XKeycode.KEY_I, 229);
        UnicodeToXKeyMap[230] = new XKey(XKeycode.KEY_J, 230);
        UnicodeToXKeyMap[231] = new XKey(XKeycode.KEY_K, 231);
        UnicodeToXKeyMap[232] = new XKey(XKeycode.KEY_L, 232);
        UnicodeToXKeyMap[233] = new XKey(XKeycode.KEY_M, 233);
        UnicodeToXKeyMap[234] = new XKey(XKeycode.KEY_N, 234);
        UnicodeToXKeyMap[235] = new XKey(XKeycode.KEY_O, 235);
        UnicodeToXKeyMap[236] = new XKey(XKeycode.KEY_P, 236);
        UnicodeToXKeyMap[237] = new XKey(XKeycode.KEY_Q, 237);
        UnicodeToXKeyMap[238] = new XKey(XKeycode.KEY_R, 238);
        UnicodeToXKeyMap[239] = new XKey(XKeycode.KEY_S, 239);
        UnicodeToXKeyMap[240] = new XKey(XKeycode.KEY_T, 240);
        UnicodeToXKeyMap[241] = new XKey(XKeycode.KEY_U, 241);
        UnicodeToXKeyMap[242] = new XKey(XKeycode.KEY_V, 242);
        UnicodeToXKeyMap[243] = new XKey(XKeycode.KEY_W, 243);
        UnicodeToXKeyMap[244] = new XKey(XKeycode.KEY_X, 244);
        UnicodeToXKeyMap[245] = new XKey(XKeycode.KEY_Y, 245);
        UnicodeToXKeyMap[246] = new XKey(XKeycode.KEY_Z, 246);
        UnicodeToXKeyMap[247] = new XKey(XKeycode.KEY_A, 247);
        UnicodeToXKeyMap[248] = new XKey(XKeycode.KEY_B, KEYS_COUNT);
        UnicodeToXKeyMap[249] = new XKey(XKeycode.KEY_C, 249);
        UnicodeToXKeyMap[250] = new XKey(XKeycode.KEY_D, ItemTouchHelper.Callback.DEFAULT_SWIPE_ANIMATION_DURATION);
        UnicodeToXKeyMap[251] = new XKey(XKeycode.KEY_E, 251);
        UnicodeToXKeyMap[252] = new XKey(XKeycode.KEY_F, 252);
        UnicodeToXKeyMap[253] = new XKey(XKeycode.KEY_G, 253);
        UnicodeToXKeyMap[254] = new XKey(XKeycode.KEY_H, 254);
        UnicodeToXKeyMap[255] = new XKey(XKeycode.KEY_I, 255);
        UnicodeToXKeyMap[256] = new XKey(XKeycode.KEY_J, 960);
        UnicodeToXKeyMap[257] = new XKey(XKeycode.KEY_K, 992);
        UnicodeToXKeyMap[258] = new XKey(XKeycode.KEY_L, 451);
        UnicodeToXKeyMap[259] = new XKey(XKeycode.KEY_M, 483);
        UnicodeToXKeyMap[260] = new XKey(XKeycode.KEY_N, 417);
        UnicodeToXKeyMap[261] = new XKey(XKeycode.KEY_O, 433);
        UnicodeToXKeyMap[262] = new XKey(XKeycode.KEY_P, 454);
        UnicodeToXKeyMap[263] = new XKey(XKeycode.KEY_Q, 486);
        UnicodeToXKeyMap[264] = new XKey(XKeycode.KEY_R, 710);
        UnicodeToXKeyMap[265] = new XKey(XKeycode.KEY_S, 742);
        UnicodeToXKeyMap[266] = new XKey(XKeycode.KEY_T, 709);
        UnicodeToXKeyMap[267] = new XKey(XKeycode.KEY_U, 741);
        UnicodeToXKeyMap[268] = new XKey(XKeycode.KEY_V, 456);
        UnicodeToXKeyMap[269] = new XKey(XKeycode.KEY_W, 488);
        UnicodeToXKeyMap[270] = new XKey(XKeycode.KEY_X, 463);
        UnicodeToXKeyMap[271] = new XKey(XKeycode.KEY_Y, 495);
        UnicodeToXKeyMap[272] = new XKey(XKeycode.KEY_Z, 464);
        UnicodeToXKeyMap[273] = new XKey(XKeycode.KEY_A, 496);
        UnicodeToXKeyMap[274] = new XKey(XKeycode.KEY_B, 938);
        UnicodeToXKeyMap[275] = new XKey(XKeycode.KEY_C, 954);
        UnicodeToXKeyMap[278] = new XKey(XKeycode.KEY_D, 972);
        UnicodeToXKeyMap[279] = new XKey(XKeycode.KEY_E, PointerIconCompat.TYPE_WAIT);
        UnicodeToXKeyMap[280] = new XKey(XKeycode.KEY_F, 458);
        UnicodeToXKeyMap[281] = new XKey(XKeycode.KEY_G, 490);
        UnicodeToXKeyMap[282] = new XKey(XKeycode.KEY_H, 460);
        UnicodeToXKeyMap[283] = new XKey(XKeycode.KEY_I, 492);
        UnicodeToXKeyMap[284] = new XKey(XKeycode.KEY_J, 728);
        UnicodeToXKeyMap[285] = new XKey(XKeycode.KEY_K, 760);
        UnicodeToXKeyMap[286] = new XKey(XKeycode.KEY_L, 683);
        UnicodeToXKeyMap[287] = new XKey(XKeycode.KEY_M, 699);
        UnicodeToXKeyMap[288] = new XKey(XKeycode.KEY_N, 725);
        UnicodeToXKeyMap[289] = new XKey(XKeycode.KEY_O, 757);
        UnicodeToXKeyMap[290] = new XKey(XKeycode.KEY_P, 939);
        UnicodeToXKeyMap[291] = new XKey(XKeycode.KEY_Q, 955);
        UnicodeToXKeyMap[292] = new XKey(XKeycode.KEY_R, 678);
        UnicodeToXKeyMap[293] = new XKey(XKeycode.KEY_S, 694);
        UnicodeToXKeyMap[294] = new XKey(XKeycode.KEY_T, 673);
        UnicodeToXKeyMap[295] = new XKey(XKeycode.KEY_U, 689);
        UnicodeToXKeyMap[296] = new XKey(XKeycode.KEY_V, 933);
        UnicodeToXKeyMap[297] = new XKey(XKeycode.KEY_W, 949);
        UnicodeToXKeyMap[298] = new XKey(XKeycode.KEY_X, 975);
        UnicodeToXKeyMap[299] = new XKey(XKeycode.KEY_Y, PointerIconCompat.TYPE_CROSSHAIR);
        UnicodeToXKeyMap[302] = new XKey(XKeycode.KEY_Z, 967);
        UnicodeToXKeyMap[303] = new XKey(XKeycode.KEY_A, 999);
        UnicodeToXKeyMap[304] = new XKey(XKeycode.KEY_B, 681);
        UnicodeToXKeyMap[305] = new XKey(XKeycode.KEY_C, 697);
        UnicodeToXKeyMap[308] = new XKey(XKeycode.KEY_D, 684);
        UnicodeToXKeyMap[309] = new XKey(XKeycode.KEY_E, 700);
        UnicodeToXKeyMap[310] = new XKey(XKeycode.KEY_F, 979);
        UnicodeToXKeyMap[311] = new XKey(XKeycode.KEY_G, PointerIconCompat.TYPE_COPY);
        UnicodeToXKeyMap[312] = new XKey(XKeycode.KEY_H, 930);
        UnicodeToXKeyMap[313] = new XKey(XKeycode.KEY_I, 453);
        UnicodeToXKeyMap[314] = new XKey(XKeycode.KEY_J, 485);
        UnicodeToXKeyMap[315] = new XKey(XKeycode.KEY_K, 934);
        UnicodeToXKeyMap[316] = new XKey(XKeycode.KEY_L, 950);
        UnicodeToXKeyMap[317] = new XKey(XKeycode.KEY_M, 421);
        UnicodeToXKeyMap[318] = new XKey(XKeycode.KEY_N, 437);
        UnicodeToXKeyMap[321] = new XKey(XKeycode.KEY_O, 419);
        UnicodeToXKeyMap[322] = new XKey(XKeycode.KEY_P, 435);
        UnicodeToXKeyMap[323] = new XKey(XKeycode.KEY_Q, 465);
        UnicodeToXKeyMap[324] = new XKey(XKeycode.KEY_R, 497);
        UnicodeToXKeyMap[325] = new XKey(XKeycode.KEY_S, 977);
        UnicodeToXKeyMap[326] = new XKey(XKeycode.KEY_T, PointerIconCompat.TYPE_VERTICAL_TEXT);
        UnicodeToXKeyMap[327] = new XKey(XKeycode.KEY_U, 466);
        UnicodeToXKeyMap[328] = new XKey(XKeycode.KEY_V, 498);
        UnicodeToXKeyMap[330] = new XKey(XKeycode.KEY_W, 957);
        UnicodeToXKeyMap[331] = new XKey(XKeycode.KEY_X, 959);
        UnicodeToXKeyMap[332] = new XKey(XKeycode.KEY_Y, 978);
        UnicodeToXKeyMap[333] = new XKey(XKeycode.KEY_Z, PointerIconCompat.TYPE_ALIAS);
        UnicodeToXKeyMap[336] = new XKey(XKeycode.KEY_A, 469);
        UnicodeToXKeyMap[337] = new XKey(XKeycode.KEY_B, 501);
        UnicodeToXKeyMap[338] = new XKey(XKeycode.KEY_C, 5052);
        UnicodeToXKeyMap[339] = new XKey(XKeycode.KEY_D, 5053);
        UnicodeToXKeyMap[340] = new XKey(XKeycode.KEY_E, 448);
        UnicodeToXKeyMap[341] = new XKey(XKeycode.KEY_F, 480);
        UnicodeToXKeyMap[342] = new XKey(XKeycode.KEY_G, 931);
        UnicodeToXKeyMap[343] = new XKey(XKeycode.KEY_H, 947);
        UnicodeToXKeyMap[344] = new XKey(XKeycode.KEY_I, 472);
        UnicodeToXKeyMap[345] = new XKey(XKeycode.KEY_J, TarConstants.SPARSELEN_GNU_SPARSE);
        UnicodeToXKeyMap[346] = new XKey(XKeycode.KEY_K, 422);
        UnicodeToXKeyMap[347] = new XKey(XKeycode.KEY_L, 438);
        UnicodeToXKeyMap[348] = new XKey(XKeycode.KEY_M, 734);
        UnicodeToXKeyMap[349] = new XKey(XKeycode.KEY_N, 766);
        UnicodeToXKeyMap[350] = new XKey(XKeycode.KEY_O, 426);
        UnicodeToXKeyMap[351] = new XKey(XKeycode.KEY_P, 442);
        UnicodeToXKeyMap[352] = new XKey(XKeycode.KEY_Q, 425);
        UnicodeToXKeyMap[353] = new XKey(XKeycode.KEY_R, 441);
        UnicodeToXKeyMap[354] = new XKey(XKeycode.KEY_S, 478);
        UnicodeToXKeyMap[355] = new XKey(XKeycode.KEY_T, 510);
        UnicodeToXKeyMap[356] = new XKey(XKeycode.KEY_U, 427);
        UnicodeToXKeyMap[357] = new XKey(XKeycode.KEY_V, 443);
        UnicodeToXKeyMap[358] = new XKey(XKeycode.KEY_W, 940);
        UnicodeToXKeyMap[359] = new XKey(XKeycode.KEY_X, 956);
        UnicodeToXKeyMap[360] = new XKey(XKeycode.KEY_Y, 989);
        UnicodeToXKeyMap[361] = new XKey(XKeycode.KEY_Z, PointerIconCompat.TYPE_GRABBING);
        UnicodeToXKeyMap[362] = new XKey(XKeycode.KEY_A, 990);
        UnicodeToXKeyMap[363] = new XKey(XKeycode.KEY_B, 1022);
        UnicodeToXKeyMap[364] = new XKey(XKeycode.KEY_C, 733);
        UnicodeToXKeyMap[365] = new XKey(XKeycode.KEY_D, 765);
        UnicodeToXKeyMap[366] = new XKey(XKeycode.KEY_E, 473);
        UnicodeToXKeyMap[367] = new XKey(XKeycode.KEY_F, 505);
        UnicodeToXKeyMap[368] = new XKey(XKeycode.KEY_G, 475);
        UnicodeToXKeyMap[369] = new XKey(XKeycode.KEY_H, 507);
        UnicodeToXKeyMap[370] = new XKey(XKeycode.KEY_I, 985);
        UnicodeToXKeyMap[371] = new XKey(XKeycode.KEY_J, PointerIconCompat.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW);
        UnicodeToXKeyMap[376] = new XKey(XKeycode.KEY_K, 5054);
        UnicodeToXKeyMap[377] = new XKey(XKeycode.KEY_L, 428);
        UnicodeToXKeyMap[378] = new XKey(XKeycode.KEY_M, 444);
        UnicodeToXKeyMap[379] = new XKey(XKeycode.KEY_N, 431);
        UnicodeToXKeyMap[380] = new XKey(XKeycode.KEY_O, 447);
        UnicodeToXKeyMap[381] = new XKey(XKeycode.KEY_P, 430);
        UnicodeToXKeyMap[382] = new XKey(XKeycode.KEY_Q, 446);
        UnicodeToXKeyMap[402] = new XKey(XKeycode.KEY_R, 2294);
        UnicodeToXKeyMap[711] = new XKey(XKeycode.KEY_S, 439);
        UnicodeToXKeyMap[728] = new XKey(XKeycode.KEY_T, 418);
        UnicodeToXKeyMap[729] = new XKey(XKeycode.KEY_U, 511);
        UnicodeToXKeyMap[731] = new XKey(XKeycode.KEY_V, 434);
        UnicodeToXKeyMap[733] = new XKey(XKeycode.KEY_W, 445);
        UnicodeToXKeyMap[901] = new XKey(XKeycode.KEY_X, 1966);
        UnicodeToXKeyMap[902] = new XKey(XKeycode.KEY_Y, 1953);
        UnicodeToXKeyMap[904] = new XKey(XKeycode.KEY_Z, 1954);
        UnicodeToXKeyMap[905] = new XKey(XKeycode.KEY_A, 1955);
        UnicodeToXKeyMap[906] = new XKey(XKeycode.KEY_B, 1956);
        UnicodeToXKeyMap[908] = new XKey(XKeycode.KEY_C, 1959);
        UnicodeToXKeyMap[910] = new XKey(XKeycode.KEY_D, 1960);
        UnicodeToXKeyMap[911] = new XKey(XKeycode.KEY_E, 1963);
        UnicodeToXKeyMap[912] = new XKey(XKeycode.KEY_F, 1974);
        UnicodeToXKeyMap[913] = new XKey(XKeycode.KEY_G, 1985);
        UnicodeToXKeyMap[914] = new XKey(XKeycode.KEY_H, 1986);
        UnicodeToXKeyMap[915] = new XKey(XKeycode.KEY_I, 1987);
        UnicodeToXKeyMap[916] = new XKey(XKeycode.KEY_J, 1988);
        UnicodeToXKeyMap[917] = new XKey(XKeycode.KEY_K, 1989);
        UnicodeToXKeyMap[918] = new XKey(XKeycode.KEY_L, 1990);
        UnicodeToXKeyMap[919] = new XKey(XKeycode.KEY_M, 1991);
        UnicodeToXKeyMap[920] = new XKey(XKeycode.KEY_N, 1992);
        UnicodeToXKeyMap[921] = new XKey(XKeycode.KEY_O, 1993);
        UnicodeToXKeyMap[922] = new XKey(XKeycode.KEY_P, 1994);
        UnicodeToXKeyMap[923] = new XKey(XKeycode.KEY_Q, 1995);
        UnicodeToXKeyMap[924] = new XKey(XKeycode.KEY_R, 1996);
        UnicodeToXKeyMap[925] = new XKey(XKeycode.KEY_S, 1997);
        UnicodeToXKeyMap[926] = new XKey(XKeycode.KEY_T, 1998);
        UnicodeToXKeyMap[927] = new XKey(XKeycode.KEY_U, 1999);
        UnicodeToXKeyMap[928] = new XKey(XKeycode.KEY_V, 2000);
        UnicodeToXKeyMap[929] = new XKey(XKeycode.KEY_W, 2001);
        UnicodeToXKeyMap[931] = new XKey(XKeycode.KEY_X, 2002);
        UnicodeToXKeyMap[932] = new XKey(XKeycode.KEY_Y, 2004);
        UnicodeToXKeyMap[933] = new XKey(XKeycode.KEY_Z, 2005);
        UnicodeToXKeyMap[934] = new XKey(XKeycode.KEY_A, 2006);
        UnicodeToXKeyMap[935] = new XKey(XKeycode.KEY_B, 2007);
        UnicodeToXKeyMap[936] = new XKey(XKeycode.KEY_C, 2008);
        UnicodeToXKeyMap[937] = new XKey(XKeycode.KEY_D, 2009);
        UnicodeToXKeyMap[938] = new XKey(XKeycode.KEY_E, 1957);
        UnicodeToXKeyMap[939] = new XKey(XKeycode.KEY_F, 1961);
        UnicodeToXKeyMap[940] = new XKey(XKeycode.KEY_G, 1969);
        UnicodeToXKeyMap[941] = new XKey(XKeycode.KEY_H, 1970);
        UnicodeToXKeyMap[942] = new XKey(XKeycode.KEY_I, 1971);
        UnicodeToXKeyMap[943] = new XKey(XKeycode.KEY_J, 1972);
        UnicodeToXKeyMap[944] = new XKey(XKeycode.KEY_K, 1978);
        UnicodeToXKeyMap[945] = new XKey(XKeycode.KEY_L, 2017);
        UnicodeToXKeyMap[946] = new XKey(XKeycode.KEY_M, 2018);
        UnicodeToXKeyMap[947] = new XKey(XKeycode.KEY_N, 2019);
        UnicodeToXKeyMap[948] = new XKey(XKeycode.KEY_O, 2020);
        UnicodeToXKeyMap[949] = new XKey(XKeycode.KEY_P, 2021);
        UnicodeToXKeyMap[950] = new XKey(XKeycode.KEY_Q, 2022);
        UnicodeToXKeyMap[951] = new XKey(XKeycode.KEY_R, 2023);
        UnicodeToXKeyMap[952] = new XKey(XKeycode.KEY_S, 2024);
        UnicodeToXKeyMap[953] = new XKey(XKeycode.KEY_T, 2025);
        UnicodeToXKeyMap[954] = new XKey(XKeycode.KEY_U, 2026);
        UnicodeToXKeyMap[955] = new XKey(XKeycode.KEY_V, 2027);
        UnicodeToXKeyMap[956] = new XKey(XKeycode.KEY_W, 2028);
        UnicodeToXKeyMap[957] = new XKey(XKeycode.KEY_X, 2029);
        UnicodeToXKeyMap[958] = new XKey(XKeycode.KEY_Y, 2030);
        UnicodeToXKeyMap[959] = new XKey(XKeycode.KEY_Z, 2031);
        UnicodeToXKeyMap[960] = new XKey(XKeycode.KEY_A, 2032);
        UnicodeToXKeyMap[961] = new XKey(XKeycode.KEY_B, 2033);
        UnicodeToXKeyMap[962] = new XKey(XKeycode.KEY_C, 2035);
        UnicodeToXKeyMap[963] = new XKey(XKeycode.KEY_D, 2034);
        UnicodeToXKeyMap[964] = new XKey(XKeycode.KEY_E, 2036);
        UnicodeToXKeyMap[965] = new XKey(XKeycode.KEY_F, 2037);
        UnicodeToXKeyMap[966] = new XKey(XKeycode.KEY_G, 2038);
        UnicodeToXKeyMap[967] = new XKey(XKeycode.KEY_H, 2039);
        UnicodeToXKeyMap[968] = new XKey(XKeycode.KEY_I, 2040);
        UnicodeToXKeyMap[969] = new XKey(XKeycode.KEY_J, 2041);
        UnicodeToXKeyMap[970] = new XKey(XKeycode.KEY_K, 1973);
        UnicodeToXKeyMap[971] = new XKey(XKeycode.KEY_L, 1977);
        UnicodeToXKeyMap[972] = new XKey(XKeycode.KEY_M, 1975);
        UnicodeToXKeyMap[973] = new XKey(XKeycode.KEY_N, 1976);
        UnicodeToXKeyMap[974] = new XKey(XKeycode.KEY_O, 1979);
        UnicodeToXKeyMap[1025] = new XKey(XKeycode.KEY_P, 1715);
        UnicodeToXKeyMap[1026] = new XKey(XKeycode.KEY_Q, 1713);
        UnicodeToXKeyMap[1027] = new XKey(XKeycode.KEY_R, 1714);
        UnicodeToXKeyMap[1028] = new XKey(XKeycode.KEY_S, 1716);
        UnicodeToXKeyMap[1029] = new XKey(XKeycode.KEY_T, 1717);
        UnicodeToXKeyMap[1030] = new XKey(XKeycode.KEY_U, 1718);
        UnicodeToXKeyMap[1031] = new XKey(XKeycode.KEY_V, 1719);
        UnicodeToXKeyMap[1032] = new XKey(XKeycode.KEY_W, 1720);
        UnicodeToXKeyMap[1033] = new XKey(XKeycode.KEY_X, 1721);
        UnicodeToXKeyMap[1034] = new XKey(XKeycode.KEY_Y, 1722);
        UnicodeToXKeyMap[1035] = new XKey(XKeycode.KEY_Z, 1723);
        UnicodeToXKeyMap[1036] = new XKey(XKeycode.KEY_A, 1724);
        UnicodeToXKeyMap[1038] = new XKey(XKeycode.KEY_B, 1726);
        UnicodeToXKeyMap[1039] = new XKey(XKeycode.KEY_C, 1727);
        UnicodeToXKeyMap[1040] = new XKey(XKeycode.KEY_D, 1761);
        UnicodeToXKeyMap[1041] = new XKey(XKeycode.KEY_E, 1762);
        UnicodeToXKeyMap[1042] = new XKey(XKeycode.KEY_F, 1783);
        UnicodeToXKeyMap[1043] = new XKey(XKeycode.KEY_G, 1767);
        UnicodeToXKeyMap[1044] = new XKey(XKeycode.KEY_H, 1764);
        UnicodeToXKeyMap[1045] = new XKey(XKeycode.KEY_I, 1765);
        UnicodeToXKeyMap[1046] = new XKey(XKeycode.KEY_J, 1782);
        UnicodeToXKeyMap[1047] = new XKey(XKeycode.KEY_K, 1786);
        UnicodeToXKeyMap[1048] = new XKey(XKeycode.KEY_L, 1769);
        UnicodeToXKeyMap[1049] = new XKey(XKeycode.KEY_M, 1770);
        UnicodeToXKeyMap[1050] = new XKey(XKeycode.KEY_N, 1771);
        UnicodeToXKeyMap[1051] = new XKey(XKeycode.KEY_O, 1772);
        UnicodeToXKeyMap[1052] = new XKey(XKeycode.KEY_P, 1773);
        UnicodeToXKeyMap[1053] = new XKey(XKeycode.KEY_Q, 1774);
        UnicodeToXKeyMap[1054] = new XKey(XKeycode.KEY_R, 1775);
        UnicodeToXKeyMap[1055] = new XKey(XKeycode.KEY_S, 1776);
        UnicodeToXKeyMap[1056] = new XKey(XKeycode.KEY_T, 1778);
        UnicodeToXKeyMap[1057] = new XKey(XKeycode.KEY_U, 1779);
        UnicodeToXKeyMap[1058] = new XKey(XKeycode.KEY_V, 1780);
        UnicodeToXKeyMap[1059] = new XKey(XKeycode.KEY_W, 1781);
        UnicodeToXKeyMap[1060] = new XKey(XKeycode.KEY_X, 1766);
        UnicodeToXKeyMap[1061] = new XKey(XKeycode.KEY_Y, 1768);
        UnicodeToXKeyMap[1062] = new XKey(XKeycode.KEY_Z, 1763);
        UnicodeToXKeyMap[1063] = new XKey(XKeycode.KEY_A, 1790);
        UnicodeToXKeyMap[1064] = new XKey(XKeycode.KEY_B, 1787);
        UnicodeToXKeyMap[1065] = new XKey(XKeycode.KEY_C, 1789);
        UnicodeToXKeyMap[1066] = new XKey(XKeycode.KEY_D, 1791);
        UnicodeToXKeyMap[1067] = new XKey(XKeycode.KEY_E, 1785);
        UnicodeToXKeyMap[1068] = new XKey(XKeycode.KEY_F, 1784);
        UnicodeToXKeyMap[1069] = new XKey(XKeycode.KEY_G, 1788);
        UnicodeToXKeyMap[1070] = new XKey(XKeycode.KEY_H, 1760);
        UnicodeToXKeyMap[1071] = new XKey(XKeycode.KEY_I, 1777);
        UnicodeToXKeyMap[1072] = new XKey(XKeycode.KEY_J, 1729);
        UnicodeToXKeyMap[1073] = new XKey(XKeycode.KEY_K, 1730);
        UnicodeToXKeyMap[1074] = new XKey(XKeycode.KEY_L, 1751);
        UnicodeToXKeyMap[1075] = new XKey(XKeycode.KEY_M, 1735);
        UnicodeToXKeyMap[1076] = new XKey(XKeycode.KEY_N, 1732);
        UnicodeToXKeyMap[1077] = new XKey(XKeycode.KEY_O, 1733);
        UnicodeToXKeyMap[1078] = new XKey(XKeycode.KEY_P, 1750);
        UnicodeToXKeyMap[1079] = new XKey(XKeycode.KEY_Q, 1754);
        UnicodeToXKeyMap[1080] = new XKey(XKeycode.KEY_R, 1737);
        UnicodeToXKeyMap[1081] = new XKey(XKeycode.KEY_S, 1738);
        UnicodeToXKeyMap[1082] = new XKey(XKeycode.KEY_T, 1739);
        UnicodeToXKeyMap[1083] = new XKey(XKeycode.KEY_U, 1740);
        UnicodeToXKeyMap[1084] = new XKey(XKeycode.KEY_V, 1741);
        UnicodeToXKeyMap[1085] = new XKey(XKeycode.KEY_W, 1742);
        UnicodeToXKeyMap[1086] = new XKey(XKeycode.KEY_X, 1743);
        UnicodeToXKeyMap[1087] = new XKey(XKeycode.KEY_Y, 1744);
        UnicodeToXKeyMap[1088] = new XKey(XKeycode.KEY_Z, 1746);
        UnicodeToXKeyMap[1089] = new XKey(XKeycode.KEY_A, 1747);
        UnicodeToXKeyMap[1090] = new XKey(XKeycode.KEY_B, 1748);
        UnicodeToXKeyMap[1091] = new XKey(XKeycode.KEY_C, 1749);
        UnicodeToXKeyMap[1092] = new XKey(XKeycode.KEY_D, 1734);
        UnicodeToXKeyMap[1093] = new XKey(XKeycode.KEY_E, 1736);
        UnicodeToXKeyMap[1094] = new XKey(XKeycode.KEY_F, 1731);
        UnicodeToXKeyMap[1095] = new XKey(XKeycode.KEY_G, 1758);
        UnicodeToXKeyMap[1096] = new XKey(XKeycode.KEY_H, 1755);
        UnicodeToXKeyMap[1097] = new XKey(XKeycode.KEY_I, 1757);
        UnicodeToXKeyMap[1098] = new XKey(XKeycode.KEY_J, 1759);
        UnicodeToXKeyMap[1099] = new XKey(XKeycode.KEY_K, 1753);
        UnicodeToXKeyMap[1100] = new XKey(XKeycode.KEY_L, 1752);
        UnicodeToXKeyMap[1101] = new XKey(XKeycode.KEY_M, 1756);
        UnicodeToXKeyMap[1102] = new XKey(XKeycode.KEY_N, 1728);
        UnicodeToXKeyMap[1103] = new XKey(XKeycode.KEY_O, 1745);
        UnicodeToXKeyMap[1105] = new XKey(XKeycode.KEY_P, 1699);
        UnicodeToXKeyMap[1106] = new XKey(XKeycode.KEY_Q, 1697);
        UnicodeToXKeyMap[1107] = new XKey(XKeycode.KEY_R, 1698);
        UnicodeToXKeyMap[1108] = new XKey(XKeycode.KEY_S, 1700);
        UnicodeToXKeyMap[1109] = new XKey(XKeycode.KEY_T, 1701);
        UnicodeToXKeyMap[1110] = new XKey(XKeycode.KEY_U, 1702);
        UnicodeToXKeyMap[1111] = new XKey(XKeycode.KEY_V, 1703);
        UnicodeToXKeyMap[1112] = new XKey(XKeycode.KEY_W, 1704);
        UnicodeToXKeyMap[1113] = new XKey(XKeycode.KEY_X, 1705);
        UnicodeToXKeyMap[1114] = new XKey(XKeycode.KEY_Y, 1706);
        UnicodeToXKeyMap[1115] = new XKey(XKeycode.KEY_Z, 1707);
        UnicodeToXKeyMap[1116] = new XKey(XKeycode.KEY_A, 1708);
        UnicodeToXKeyMap[1118] = new XKey(XKeycode.KEY_B, 1710);
        UnicodeToXKeyMap[1119] = new XKey(XKeycode.KEY_C, 1711);
        UnicodeToXKeyMap[1168] = new XKey(XKeycode.KEY_D, 1725);
        UnicodeToXKeyMap[1169] = new XKey(XKeycode.KEY_E, 1709);
        UnicodeToXKeyMap[1488] = new XKey(XKeycode.KEY_F, 3296);
        UnicodeToXKeyMap[1489] = new XKey(XKeycode.KEY_G, 3297);
        UnicodeToXKeyMap[1490] = new XKey(XKeycode.KEY_H, 3298);
        UnicodeToXKeyMap[1491] = new XKey(XKeycode.KEY_I, 3299);
        UnicodeToXKeyMap[1492] = new XKey(XKeycode.KEY_J, 3300);
        UnicodeToXKeyMap[1493] = new XKey(XKeycode.KEY_K, 3301);
        UnicodeToXKeyMap[1494] = new XKey(XKeycode.KEY_L, 3302);
        UnicodeToXKeyMap[1495] = new XKey(XKeycode.KEY_M, 3303);
        UnicodeToXKeyMap[1496] = new XKey(XKeycode.KEY_N, 3304);
        UnicodeToXKeyMap[1497] = new XKey(XKeycode.KEY_O, 3305);
        UnicodeToXKeyMap[1498] = new XKey(XKeycode.KEY_P, 3306);
        UnicodeToXKeyMap[1499] = new XKey(XKeycode.KEY_Q, 3307);
        UnicodeToXKeyMap[1500] = new XKey(XKeycode.KEY_R, 3308);
        UnicodeToXKeyMap[1501] = new XKey(XKeycode.KEY_S, 3309);
        UnicodeToXKeyMap[1502] = new XKey(XKeycode.KEY_T, 3310);
        UnicodeToXKeyMap[1503] = new XKey(XKeycode.KEY_U, 3311);
        UnicodeToXKeyMap[1504] = new XKey(XKeycode.KEY_V, 3312);
        UnicodeToXKeyMap[1505] = new XKey(XKeycode.KEY_W, 3313);
        UnicodeToXKeyMap[1506] = new XKey(XKeycode.KEY_X, 3314);
        UnicodeToXKeyMap[1507] = new XKey(XKeycode.KEY_Y, 3315);
        UnicodeToXKeyMap[1508] = new XKey(XKeycode.KEY_Z, 3316);
        UnicodeToXKeyMap[1509] = new XKey(XKeycode.KEY_A, 3317);
        UnicodeToXKeyMap[1510] = new XKey(XKeycode.KEY_B, 3318);
        UnicodeToXKeyMap[1511] = new XKey(XKeycode.KEY_C, 3319);
        UnicodeToXKeyMap[1512] = new XKey(XKeycode.KEY_D, 3320);
        UnicodeToXKeyMap[1513] = new XKey(XKeycode.KEY_E, 3321);
        UnicodeToXKeyMap[1514] = new XKey(XKeycode.KEY_F, 3322);
        UnicodeToXKeyMap[1548] = new XKey(XKeycode.KEY_G, 1452);
        UnicodeToXKeyMap[1563] = new XKey(XKeycode.KEY_H, 1467);
        UnicodeToXKeyMap[1567] = new XKey(XKeycode.KEY_I, 1471);
        UnicodeToXKeyMap[1569] = new XKey(XKeycode.KEY_J, 1473);
        UnicodeToXKeyMap[1570] = new XKey(XKeycode.KEY_K, 1474);
        UnicodeToXKeyMap[1571] = new XKey(XKeycode.KEY_L, 1475);
        UnicodeToXKeyMap[1572] = new XKey(XKeycode.KEY_M, 1476);
        UnicodeToXKeyMap[1573] = new XKey(XKeycode.KEY_N, 1477);
        UnicodeToXKeyMap[1574] = new XKey(XKeycode.KEY_O, 1478);
        UnicodeToXKeyMap[1575] = new XKey(XKeycode.KEY_P, 1479);
        UnicodeToXKeyMap[1576] = new XKey(XKeycode.KEY_Q, 1480);
        UnicodeToXKeyMap[1577] = new XKey(XKeycode.KEY_R, 1481);
        UnicodeToXKeyMap[1578] = new XKey(XKeycode.KEY_S, 1482);
        UnicodeToXKeyMap[1579] = new XKey(XKeycode.KEY_T, 1483);
        UnicodeToXKeyMap[1580] = new XKey(XKeycode.KEY_U, 1484);
        UnicodeToXKeyMap[1581] = new XKey(XKeycode.KEY_V, 1485);
        UnicodeToXKeyMap[1582] = new XKey(XKeycode.KEY_W, 1486);
        UnicodeToXKeyMap[1583] = new XKey(XKeycode.KEY_X, 1487);
        UnicodeToXKeyMap[1584] = new XKey(XKeycode.KEY_Y, 1488);
        UnicodeToXKeyMap[1585] = new XKey(XKeycode.KEY_Z, 1489);
        UnicodeToXKeyMap[1586] = new XKey(XKeycode.KEY_A, 1490);
        UnicodeToXKeyMap[1587] = new XKey(XKeycode.KEY_B, 1491);
        UnicodeToXKeyMap[1588] = new XKey(XKeycode.KEY_C, 1492);
        UnicodeToXKeyMap[1589] = new XKey(XKeycode.KEY_D, 1493);
        UnicodeToXKeyMap[1590] = new XKey(XKeycode.KEY_E, 1494);
        UnicodeToXKeyMap[1591] = new XKey(XKeycode.KEY_F, 1495);
        UnicodeToXKeyMap[1592] = new XKey(XKeycode.KEY_G, 1496);
        UnicodeToXKeyMap[1593] = new XKey(XKeycode.KEY_H, 1497);
        UnicodeToXKeyMap[1594] = new XKey(XKeycode.KEY_I, 1498);
        UnicodeToXKeyMap[1600] = new XKey(XKeycode.KEY_J, 1504);
        UnicodeToXKeyMap[1601] = new XKey(XKeycode.KEY_K, 1505);
        UnicodeToXKeyMap[1602] = new XKey(XKeycode.KEY_L, 1506);
        UnicodeToXKeyMap[1603] = new XKey(XKeycode.KEY_M, 1507);
        UnicodeToXKeyMap[1604] = new XKey(XKeycode.KEY_N, 1508);
        UnicodeToXKeyMap[1605] = new XKey(XKeycode.KEY_O, 1509);
        UnicodeToXKeyMap[1606] = new XKey(XKeycode.KEY_P, 1510);
        UnicodeToXKeyMap[1607] = new XKey(XKeycode.KEY_Q, 1511);
        UnicodeToXKeyMap[1608] = new XKey(XKeycode.KEY_R, 1512);
        UnicodeToXKeyMap[1609] = new XKey(XKeycode.KEY_S, 1513);
        UnicodeToXKeyMap[1610] = new XKey(XKeycode.KEY_T, 1514);
        UnicodeToXKeyMap[1611] = new XKey(XKeycode.KEY_U, 1515);
        UnicodeToXKeyMap[1612] = new XKey(XKeycode.KEY_V, 1516);
        UnicodeToXKeyMap[1613] = new XKey(XKeycode.KEY_W, 1517);
        UnicodeToXKeyMap[1614] = new XKey(XKeycode.KEY_X, 1518);
        UnicodeToXKeyMap[1615] = new XKey(XKeycode.KEY_Y, 1519);
        UnicodeToXKeyMap[1616] = new XKey(XKeycode.KEY_Z, 1520);
        UnicodeToXKeyMap[1617] = new XKey(XKeycode.KEY_A, 1521);
        UnicodeToXKeyMap[1618] = new XKey(XKeycode.KEY_B, 1522);
        UnicodeToXKeyMap[3585] = new XKey(XKeycode.KEY_C, 3489);
        UnicodeToXKeyMap[3586] = new XKey(XKeycode.KEY_D, 3490);
        UnicodeToXKeyMap[3587] = new XKey(XKeycode.KEY_E, 3491);
        UnicodeToXKeyMap[3588] = new XKey(XKeycode.KEY_F, 3492);
        UnicodeToXKeyMap[3589] = new XKey(XKeycode.KEY_G, 3493);
        UnicodeToXKeyMap[3590] = new XKey(XKeycode.KEY_H, 3494);
        UnicodeToXKeyMap[3591] = new XKey(XKeycode.KEY_I, 3495);
        UnicodeToXKeyMap[3592] = new XKey(XKeycode.KEY_J, 3496);
        UnicodeToXKeyMap[3593] = new XKey(XKeycode.KEY_K, 3497);
        UnicodeToXKeyMap[3594] = new XKey(XKeycode.KEY_L, 3498);
        UnicodeToXKeyMap[3595] = new XKey(XKeycode.KEY_M, 3499);
        UnicodeToXKeyMap[3596] = new XKey(XKeycode.KEY_N, 3500);
        UnicodeToXKeyMap[3597] = new XKey(XKeycode.KEY_O, 3501);
        UnicodeToXKeyMap[3598] = new XKey(XKeycode.KEY_P, 3502);
        UnicodeToXKeyMap[3599] = new XKey(XKeycode.KEY_Q, 3503);
        UnicodeToXKeyMap[3600] = new XKey(XKeycode.KEY_R, 3504);
        UnicodeToXKeyMap[3601] = new XKey(XKeycode.KEY_S, 3505);
        UnicodeToXKeyMap[3602] = new XKey(XKeycode.KEY_T, 3506);
        UnicodeToXKeyMap[3603] = new XKey(XKeycode.KEY_U, 3507);
        UnicodeToXKeyMap[3604] = new XKey(XKeycode.KEY_V, 3508);
        UnicodeToXKeyMap[3605] = new XKey(XKeycode.KEY_W, 3509);
        UnicodeToXKeyMap[3606] = new XKey(XKeycode.KEY_X, 3510);
        UnicodeToXKeyMap[3607] = new XKey(XKeycode.KEY_Y, 3511);
        UnicodeToXKeyMap[3608] = new XKey(XKeycode.KEY_Z, 3512);
        UnicodeToXKeyMap[3609] = new XKey(XKeycode.KEY_A, 3513);
        UnicodeToXKeyMap[3610] = new XKey(XKeycode.KEY_B, 3514);
        UnicodeToXKeyMap[3611] = new XKey(XKeycode.KEY_C, 3515);
        UnicodeToXKeyMap[3612] = new XKey(XKeycode.KEY_D, 3516);
        UnicodeToXKeyMap[3613] = new XKey(XKeycode.KEY_E, 3517);
        UnicodeToXKeyMap[3614] = new XKey(XKeycode.KEY_F, 3518);
        UnicodeToXKeyMap[3615] = new XKey(XKeycode.KEY_G, 3519);
        UnicodeToXKeyMap[3616] = new XKey(XKeycode.KEY_H, 3520);
        UnicodeToXKeyMap[3617] = new XKey(XKeycode.KEY_I, 3521);
        UnicodeToXKeyMap[3618] = new XKey(XKeycode.KEY_J, 3522);
        UnicodeToXKeyMap[3619] = new XKey(XKeycode.KEY_K, 3523);
        UnicodeToXKeyMap[3620] = new XKey(XKeycode.KEY_L, 3524);
        UnicodeToXKeyMap[3621] = new XKey(XKeycode.KEY_M, 3525);
        UnicodeToXKeyMap[3622] = new XKey(XKeycode.KEY_N, 3526);
        UnicodeToXKeyMap[3623] = new XKey(XKeycode.KEY_O, 3527);
        UnicodeToXKeyMap[3624] = new XKey(XKeycode.KEY_P, 3528);
        UnicodeToXKeyMap[3625] = new XKey(XKeycode.KEY_Q, 3529);
        UnicodeToXKeyMap[3626] = new XKey(XKeycode.KEY_R, 3530);
        UnicodeToXKeyMap[3627] = new XKey(XKeycode.KEY_S, 3531);
        UnicodeToXKeyMap[3628] = new XKey(XKeycode.KEY_T, 3532);
        UnicodeToXKeyMap[3629] = new XKey(XKeycode.KEY_U, 3533);
        UnicodeToXKeyMap[3630] = new XKey(XKeycode.KEY_V, 3534);
        UnicodeToXKeyMap[3631] = new XKey(XKeycode.KEY_W, 3535);
        UnicodeToXKeyMap[3632] = new XKey(XKeycode.KEY_X, 3536);
        UnicodeToXKeyMap[3633] = new XKey(XKeycode.KEY_Y, 3537);
        UnicodeToXKeyMap[3634] = new XKey(XKeycode.KEY_Z, 3538);
        UnicodeToXKeyMap[3635] = new XKey(XKeycode.KEY_A, 3539);
        UnicodeToXKeyMap[3636] = new XKey(XKeycode.KEY_B, 3540);
        UnicodeToXKeyMap[3637] = new XKey(XKeycode.KEY_C, 3541);
        UnicodeToXKeyMap[3638] = new XKey(XKeycode.KEY_D, 3542);
        UnicodeToXKeyMap[3639] = new XKey(XKeycode.KEY_E, 3543);
        UnicodeToXKeyMap[3640] = new XKey(XKeycode.KEY_F, 3544);
        UnicodeToXKeyMap[3641] = new XKey(XKeycode.KEY_G, 3545);
        UnicodeToXKeyMap[3642] = new XKey(XKeycode.KEY_H, 3546);
        UnicodeToXKeyMap[3647] = new XKey(XKeycode.KEY_I, 3551);
        UnicodeToXKeyMap[3648] = new XKey(XKeycode.KEY_J, 3552);
        UnicodeToXKeyMap[3649] = new XKey(XKeycode.KEY_K, 3553);
        UnicodeToXKeyMap[3650] = new XKey(XKeycode.KEY_L, 3554);
        UnicodeToXKeyMap[3651] = new XKey(XKeycode.KEY_M, 3555);
        UnicodeToXKeyMap[3652] = new XKey(XKeycode.KEY_N, 3556);
        UnicodeToXKeyMap[3653] = new XKey(XKeycode.KEY_O, 3557);
        UnicodeToXKeyMap[3654] = new XKey(XKeycode.KEY_P, 3558);
        UnicodeToXKeyMap[3655] = new XKey(XKeycode.KEY_Q, 3559);
        UnicodeToXKeyMap[3656] = new XKey(XKeycode.KEY_R, 3560);
        UnicodeToXKeyMap[3657] = new XKey(XKeycode.KEY_S, 3561);
        UnicodeToXKeyMap[3658] = new XKey(XKeycode.KEY_T, 3562);
        UnicodeToXKeyMap[3659] = new XKey(XKeycode.KEY_U, 3563);
        UnicodeToXKeyMap[3660] = new XKey(XKeycode.KEY_V, 3564);
        UnicodeToXKeyMap[3661] = new XKey(XKeycode.KEY_W, 3565);
        UnicodeToXKeyMap[3664] = new XKey(XKeycode.KEY_X, 3568);
        UnicodeToXKeyMap[3665] = new XKey(XKeycode.KEY_Y, 3569);
        UnicodeToXKeyMap[3666] = new XKey(XKeycode.KEY_Z, 3570);
        UnicodeToXKeyMap[3667] = new XKey(XKeycode.KEY_A, 3571);
        UnicodeToXKeyMap[3668] = new XKey(XKeycode.KEY_B, 3572);
        UnicodeToXKeyMap[3669] = new XKey(XKeycode.KEY_C, 3573);
        UnicodeToXKeyMap[3670] = new XKey(XKeycode.KEY_D, 3574);
        UnicodeToXKeyMap[3671] = new XKey(XKeycode.KEY_E, 3575);
        UnicodeToXKeyMap[3672] = new XKey(XKeycode.KEY_F, 3576);
        UnicodeToXKeyMap[3673] = new XKey(XKeycode.KEY_G, 3577);
        UnicodeToXKeyMap[8194] = new XKey(XKeycode.KEY_H, 2722);
        UnicodeToXKeyMap[8195] = new XKey(XKeycode.KEY_I, 2721);
        UnicodeToXKeyMap[8196] = new XKey(XKeycode.KEY_J, 2723);
        UnicodeToXKeyMap[8197] = new XKey(XKeycode.KEY_K, 2724);
        UnicodeToXKeyMap[8199] = new XKey(XKeycode.KEY_L, 2725);
        UnicodeToXKeyMap[8200] = new XKey(XKeycode.KEY_M, 2726);
        UnicodeToXKeyMap[8201] = new XKey(XKeycode.KEY_N, 2727);
        UnicodeToXKeyMap[8202] = new XKey(XKeycode.KEY_O, 2728);
        UnicodeToXKeyMap[8210] = new XKey(XKeycode.KEY_P, 2747);
        UnicodeToXKeyMap[8211] = new XKey(XKeycode.KEY_Q, 2730);
        UnicodeToXKeyMap[8212] = new XKey(XKeycode.KEY_R, 2729);
        UnicodeToXKeyMap[8213] = new XKey(XKeycode.KEY_S, 1967);
        UnicodeToXKeyMap[8215] = new XKey(XKeycode.KEY_T, 3295);
        UnicodeToXKeyMap[8216] = new XKey(XKeycode.KEY_U, 2768);
        UnicodeToXKeyMap[8217] = new XKey(XKeycode.KEY_V, 2769);
        UnicodeToXKeyMap[8218] = new XKey(XKeycode.KEY_W, 2813);
        UnicodeToXKeyMap[8220] = new XKey(XKeycode.KEY_X, 2770);
        UnicodeToXKeyMap[8221] = new XKey(XKeycode.KEY_Y, 2771);
        UnicodeToXKeyMap[8222] = new XKey(XKeycode.KEY_Z, 2814);
        UnicodeToXKeyMap[8224] = new XKey(XKeycode.KEY_A, 2801);
        UnicodeToXKeyMap[8225] = new XKey(XKeycode.KEY_B, 2802);
        UnicodeToXKeyMap[8229] = new XKey(XKeycode.KEY_C, 2735);
        UnicodeToXKeyMap[8230] = new XKey(XKeycode.KEY_D, 2734);
        UnicodeToXKeyMap[8242] = new XKey(XKeycode.KEY_E, 2774);
        UnicodeToXKeyMap[8243] = new XKey(XKeycode.KEY_F, 2775);
        UnicodeToXKeyMap[8248] = new XKey(XKeycode.KEY_G, 2812);
        UnicodeToXKeyMap[8254] = new XKey(XKeycode.KEY_H, 1150);
        UnicodeToXKeyMap[8364] = new XKey(XKeycode.KEY_I, 8364);
        UnicodeToXKeyMap[8453] = new XKey(XKeycode.KEY_J, 2744);
        UnicodeToXKeyMap[8470] = new XKey(XKeycode.KEY_K, 1712);
        UnicodeToXKeyMap[8471] = new XKey(XKeycode.KEY_L, 2811);
        UnicodeToXKeyMap[8478] = new XKey(XKeycode.KEY_M, 2772);
        UnicodeToXKeyMap[8482] = new XKey(XKeycode.KEY_N, 2761);
        UnicodeToXKeyMap[8531] = new XKey(XKeycode.KEY_O, 2736);
        UnicodeToXKeyMap[8532] = new XKey(XKeycode.KEY_P, 2737);
        UnicodeToXKeyMap[8533] = new XKey(XKeycode.KEY_Q, 2738);
        UnicodeToXKeyMap[8534] = new XKey(XKeycode.KEY_R, 2739);
        UnicodeToXKeyMap[8535] = new XKey(XKeycode.KEY_S, 2740);
        UnicodeToXKeyMap[8536] = new XKey(XKeycode.KEY_T, 2741);
        UnicodeToXKeyMap[8537] = new XKey(XKeycode.KEY_U, 2742);
        UnicodeToXKeyMap[8538] = new XKey(XKeycode.KEY_V, 2743);
        UnicodeToXKeyMap[8539] = new XKey(XKeycode.KEY_W, 2755);
        UnicodeToXKeyMap[8540] = new XKey(XKeycode.KEY_X, 2756);
        UnicodeToXKeyMap[8541] = new XKey(XKeycode.KEY_Y, 2757);
        UnicodeToXKeyMap[8542] = new XKey(XKeycode.KEY_Z, 2758);
        UnicodeToXKeyMap[8592] = new XKey(XKeycode.KEY_A, 2299);
        UnicodeToXKeyMap[8593] = new XKey(XKeycode.KEY_B, 2300);
        UnicodeToXKeyMap[8594] = new XKey(XKeycode.KEY_C, 2301);
        UnicodeToXKeyMap[8595] = new XKey(XKeycode.KEY_D, 2302);
        UnicodeToXKeyMap[8658] = new XKey(XKeycode.KEY_E, 2254);
        UnicodeToXKeyMap[8660] = new XKey(XKeycode.KEY_F, 2253);
        UnicodeToXKeyMap[8706] = new XKey(XKeycode.KEY_G, 2287);
        UnicodeToXKeyMap[8711] = new XKey(XKeycode.KEY_H, 2245);
        UnicodeToXKeyMap[8728] = new XKey(XKeycode.KEY_I, 3018);
        UnicodeToXKeyMap[8730] = new XKey(XKeycode.KEY_J, 2262);
        UnicodeToXKeyMap[8733] = new XKey(XKeycode.KEY_K, 2241);
        UnicodeToXKeyMap[8734] = new XKey(XKeycode.KEY_L, 2242);
        UnicodeToXKeyMap[8743] = new XKey(XKeycode.KEY_M, 2270);
        UnicodeToXKeyMap[8744] = new XKey(XKeycode.KEY_N, 2271);
        UnicodeToXKeyMap[8745] = new XKey(XKeycode.KEY_O, 2268);
        UnicodeToXKeyMap[8746] = new XKey(XKeycode.KEY_P, 2269);
        UnicodeToXKeyMap[8747] = new XKey(XKeycode.KEY_Q, 2239);
        UnicodeToXKeyMap[8756] = new XKey(XKeycode.KEY_R, 2240);
        UnicodeToXKeyMap[8764] = new XKey(XKeycode.KEY_S, 2248);
        UnicodeToXKeyMap[8771] = new XKey(XKeycode.KEY_T, 2249);
        UnicodeToXKeyMap[8800] = new XKey(XKeycode.KEY_U, 2237);
        UnicodeToXKeyMap[8801] = new XKey(XKeycode.KEY_V, 2255);
        UnicodeToXKeyMap[8804] = new XKey(XKeycode.KEY_W, 2236);
        UnicodeToXKeyMap[8805] = new XKey(XKeycode.KEY_X, 2238);
        UnicodeToXKeyMap[8834] = new XKey(XKeycode.KEY_Y, 2266);
        UnicodeToXKeyMap[8835] = new XKey(XKeycode.KEY_Z, 2267);
        UnicodeToXKeyMap[8866] = new XKey(XKeycode.KEY_A, 3036);
        UnicodeToXKeyMap[8867] = new XKey(XKeycode.KEY_B, 3068);
        UnicodeToXKeyMap[8868] = new XKey(XKeycode.KEY_C, 3022);
        UnicodeToXKeyMap[8869] = new XKey(XKeycode.KEY_D, 3010);
        UnicodeToXKeyMap[8968] = new XKey(XKeycode.KEY_E, 3027);
        UnicodeToXKeyMap[8970] = new XKey(XKeycode.KEY_F, 3012);
        UnicodeToXKeyMap[8981] = new XKey(XKeycode.KEY_G, 2810);
        UnicodeToXKeyMap[8992] = new XKey(XKeycode.KEY_H, 2212);
        UnicodeToXKeyMap[8993] = new XKey(XKeycode.KEY_I, 2213);
        UnicodeToXKeyMap[9109] = new XKey(XKeycode.KEY_J, 3020);
        UnicodeToXKeyMap[9115] = new XKey(XKeycode.KEY_K, 2219);
        UnicodeToXKeyMap[9117] = new XKey(XKeycode.KEY_L, 2220);
        UnicodeToXKeyMap[9118] = new XKey(XKeycode.KEY_M, 2221);
        UnicodeToXKeyMap[9120] = new XKey(XKeycode.KEY_N, 2222);
        UnicodeToXKeyMap[9121] = new XKey(XKeycode.KEY_O, 2215);
        UnicodeToXKeyMap[9123] = new XKey(XKeycode.KEY_P, 2216);
        UnicodeToXKeyMap[9124] = new XKey(XKeycode.KEY_Q, 2217);
        UnicodeToXKeyMap[9126] = new XKey(XKeycode.KEY_R, 2218);
        UnicodeToXKeyMap[9128] = new XKey(XKeycode.KEY_S, 2223);
        UnicodeToXKeyMap[9132] = new XKey(XKeycode.KEY_T, 2224);
        UnicodeToXKeyMap[9143] = new XKey(XKeycode.KEY_U, 2209);
        UnicodeToXKeyMap[9146] = new XKey(XKeycode.KEY_V, 2543);
        UnicodeToXKeyMap[9147] = new XKey(XKeycode.KEY_W, 2544);
        UnicodeToXKeyMap[9148] = new XKey(XKeycode.KEY_X, 2546);
        UnicodeToXKeyMap[9149] = new XKey(XKeycode.KEY_Y, 2547);
        UnicodeToXKeyMap[9225] = new XKey(XKeycode.KEY_Z, 2530);
        UnicodeToXKeyMap[9226] = new XKey(XKeycode.KEY_A, 2533);
        UnicodeToXKeyMap[9227] = new XKey(XKeycode.KEY_B, 2537);
        UnicodeToXKeyMap[9228] = new XKey(XKeycode.KEY_C, 2531);
        UnicodeToXKeyMap[9229] = new XKey(XKeycode.KEY_D, 2532);
        UnicodeToXKeyMap[9252] = new XKey(XKeycode.KEY_E, 2536);
        UnicodeToXKeyMap[9488] = new XKey(XKeycode.KEY_F, 2539);
        UnicodeToXKeyMap[9492] = new XKey(XKeycode.KEY_G, 2541);
        UnicodeToXKeyMap[9496] = new XKey(XKeycode.KEY_H, 2538);
        UnicodeToXKeyMap[9500] = new XKey(XKeycode.KEY_I, 2548);
        UnicodeToXKeyMap[9508] = new XKey(XKeycode.KEY_J, 2549);
        UnicodeToXKeyMap[9516] = new XKey(XKeycode.KEY_K, 2551);
        UnicodeToXKeyMap[9524] = new XKey(XKeycode.KEY_L, 2550);
        UnicodeToXKeyMap[9532] = new XKey(XKeycode.KEY_M, 2542);
        UnicodeToXKeyMap[9618] = new XKey(XKeycode.KEY_N, 2529);
        UnicodeToXKeyMap[9670] = new XKey(XKeycode.KEY_O, 2528);
        UnicodeToXKeyMap[9742] = new XKey(XKeycode.KEY_P, 2809);
        UnicodeToXKeyMap[9792] = new XKey(XKeycode.KEY_Q, 2808);
        UnicodeToXKeyMap[9794] = new XKey(XKeycode.KEY_R, 2807);
        UnicodeToXKeyMap[9827] = new XKey(XKeycode.KEY_S, 2796);
        UnicodeToXKeyMap[9829] = new XKey(XKeycode.KEY_T, 2798);
        UnicodeToXKeyMap[9830] = new XKey(XKeycode.KEY_U, 2797);
        UnicodeToXKeyMap[9837] = new XKey(XKeycode.KEY_V, 2806);
        UnicodeToXKeyMap[9839] = new XKey(XKeycode.KEY_W, 2805);
        UnicodeToXKeyMap[10003] = new XKey(XKeycode.KEY_X, 2803);
        UnicodeToXKeyMap[10007] = new XKey(XKeycode.KEY_Y, 2804);
        UnicodeToXKeyMap[10013] = new XKey(XKeycode.KEY_Z, 2777);
        UnicodeToXKeyMap[10016] = new XKey(XKeycode.KEY_A, 2800);
        UnicodeToXKeyMap[12289] = new XKey(XKeycode.KEY_B, 1188);
        UnicodeToXKeyMap[12290] = new XKey(XKeycode.KEY_C, 1185);
        UnicodeToXKeyMap[12300] = new XKey(XKeycode.KEY_D, 1186);
        UnicodeToXKeyMap[12301] = new XKey(XKeycode.KEY_E, 1187);
        UnicodeToXKeyMap[12443] = new XKey(XKeycode.KEY_F, 1246);
        UnicodeToXKeyMap[12444] = new XKey(XKeycode.KEY_G, 1247);
        UnicodeToXKeyMap[12449] = new XKey(XKeycode.KEY_H, 1191);
        UnicodeToXKeyMap[12450] = new XKey(XKeycode.KEY_I, 1201);
        UnicodeToXKeyMap[12451] = new XKey(XKeycode.KEY_J, 1192);
        UnicodeToXKeyMap[12452] = new XKey(XKeycode.KEY_K, 1202);
        UnicodeToXKeyMap[12453] = new XKey(XKeycode.KEY_L, 1193);
        UnicodeToXKeyMap[12454] = new XKey(XKeycode.KEY_M, 1203);
        UnicodeToXKeyMap[12455] = new XKey(XKeycode.KEY_N, 1194);
        UnicodeToXKeyMap[12456] = new XKey(XKeycode.KEY_O, 1204);
        UnicodeToXKeyMap[12457] = new XKey(XKeycode.KEY_P, 1195);
        UnicodeToXKeyMap[12458] = new XKey(XKeycode.KEY_Q, 1205);
        UnicodeToXKeyMap[12459] = new XKey(XKeycode.KEY_R, 1206);
        UnicodeToXKeyMap[12461] = new XKey(XKeycode.KEY_S, 1207);
        UnicodeToXKeyMap[12463] = new XKey(XKeycode.KEY_T, 1208);
        UnicodeToXKeyMap[12465] = new XKey(XKeycode.KEY_U, 1209);
        UnicodeToXKeyMap[12467] = new XKey(XKeycode.KEY_V, 1210);
        UnicodeToXKeyMap[12469] = new XKey(XKeycode.KEY_W, 1211);
        UnicodeToXKeyMap[12471] = new XKey(XKeycode.KEY_X, 1212);
        UnicodeToXKeyMap[12473] = new XKey(XKeycode.KEY_Y, 1213);
        UnicodeToXKeyMap[12475] = new XKey(XKeycode.KEY_Z, 1214);
        UnicodeToXKeyMap[12477] = new XKey(XKeycode.KEY_A, 1215);
        UnicodeToXKeyMap[12479] = new XKey(XKeycode.KEY_B, 1216);
        UnicodeToXKeyMap[12481] = new XKey(XKeycode.KEY_C, 1217);
        UnicodeToXKeyMap[12483] = new XKey(XKeycode.KEY_D, 1199);
        UnicodeToXKeyMap[12484] = new XKey(XKeycode.KEY_E, 1218);
        UnicodeToXKeyMap[12486] = new XKey(XKeycode.KEY_F, 1219);
        UnicodeToXKeyMap[12488] = new XKey(XKeycode.KEY_G, 1220);
        UnicodeToXKeyMap[12490] = new XKey(XKeycode.KEY_H, 1221);
        UnicodeToXKeyMap[12491] = new XKey(XKeycode.KEY_I, 1222);
        UnicodeToXKeyMap[12492] = new XKey(XKeycode.KEY_J, 1223);
        UnicodeToXKeyMap[12493] = new XKey(XKeycode.KEY_K, 1224);
        UnicodeToXKeyMap[12494] = new XKey(XKeycode.KEY_L, 1225);
        UnicodeToXKeyMap[12495] = new XKey(XKeycode.KEY_M, 1226);
        UnicodeToXKeyMap[12498] = new XKey(XKeycode.KEY_N, 1227);
        UnicodeToXKeyMap[12501] = new XKey(XKeycode.KEY_O, 1228);
        UnicodeToXKeyMap[12504] = new XKey(XKeycode.KEY_P, 1229);
        UnicodeToXKeyMap[12507] = new XKey(XKeycode.KEY_Q, 1230);
        UnicodeToXKeyMap[12510] = new XKey(XKeycode.KEY_R, 1231);
        UnicodeToXKeyMap[12511] = new XKey(XKeycode.KEY_S, 1232);
        UnicodeToXKeyMap[12512] = new XKey(XKeycode.KEY_T, 1233);
        UnicodeToXKeyMap[12513] = new XKey(XKeycode.KEY_U, 1234);
        UnicodeToXKeyMap[12514] = new XKey(XKeycode.KEY_V, 1235);
        UnicodeToXKeyMap[12515] = new XKey(XKeycode.KEY_W, 1196);
        UnicodeToXKeyMap[12516] = new XKey(XKeycode.KEY_X, 1236);
        UnicodeToXKeyMap[12517] = new XKey(XKeycode.KEY_Y, 1197);
        UnicodeToXKeyMap[12518] = new XKey(XKeycode.KEY_Z, 1237);
        UnicodeToXKeyMap[12519] = new XKey(XKeycode.KEY_A, 1198);
        UnicodeToXKeyMap[12520] = new XKey(XKeycode.KEY_B, 1238);
        UnicodeToXKeyMap[12521] = new XKey(XKeycode.KEY_C, 1239);
        UnicodeToXKeyMap[12522] = new XKey(XKeycode.KEY_D, 1240);
        UnicodeToXKeyMap[12523] = new XKey(XKeycode.KEY_E, 1241);
        UnicodeToXKeyMap[12524] = new XKey(XKeycode.KEY_F, 1242);
        UnicodeToXKeyMap[12525] = new XKey(XKeycode.KEY_G, 1243);
        UnicodeToXKeyMap[12527] = new XKey(XKeycode.KEY_H, 1244);
        UnicodeToXKeyMap[12530] = new XKey(XKeycode.KEY_I, 1190);
        UnicodeToXKeyMap[12531] = new XKey(XKeycode.KEY_J, 1245);
        UnicodeToXKeyMap[12539] = new XKey(XKeycode.KEY_K, 1189);
        UnicodeToXKeyMap[12540] = new XKey(XKeycode.KEY_L, 1200);
    }

    public int KeycodeToKeysym (XKeycode keycode) {
        switch (keycode) {
            case KEY_ESC: return 65307;
            case KEY_ENTER: return 65293;
            case KEY_RIGHT: return 65363;
            case KEY_UP: return 65362;
            case KEY_LEFT: return 65361;
            case KEY_DOWN: return 65364;
            case KEY_DEL: return 65535;
            case KEY_BKSP: return 65288;
            case KEY_INSERT: return 65379;
            case KEY_PRIOR: return 65365;
            case KEY_NEXT: return 65366;
            case KEY_HOME: return 65360;
            case KEY_END: return 65367;
            case KEY_SHIFT_L: return 65505;
            case KEY_SHIFT_R: return 65506;
            case KEY_CTRL_L: return 65507;
            case KEY_CTRL_R: return 65508;
            case KEY_ALT_L: return 65511;
            case KEY_ALT_R: return 65512;
            case KEY_TAB: return 65289;
            case KEY_SPACE: return 32;
            case KEY_A: return 97;
            case KEY_B: return 98;
            case KEY_C: return 99;
            case KEY_D: return 100;
            case KEY_E: return 101;
            case KEY_F: return 102;
            case KEY_G: return 103;
            case KEY_H: return 104;
            case KEY_I: return 105;
            case KEY_J: return 106;
            case KEY_K: return 107;
            case KEY_L: return 108;
            case KEY_M: return 109;
            case KEY_N: return 110;
            case KEY_O: return 111;
            case KEY_P: return 112;
            case KEY_Q: return 113;
            case KEY_R: return 114;
            case KEY_S: return 115;
            case KEY_T: return 116;
            case KEY_U: return 117;
            case KEY_V: return 118;
            case KEY_W: return 119;
            case KEY_X: return 120;
            case KEY_Y: return 121;
            case KEY_Z: return 122;
            case KEY_1: return 49;
            case KEY_2: return 50;
            case KEY_3: return 51;
            case KEY_4: return 52;
            case KEY_5: return 53;
            case KEY_6: return 54;
            case KEY_7: return 55;
            case KEY_8: return 56;
            case KEY_9: return 57;
            case KEY_0: return 48;
            case KEY_COMMA: return 44;
            case KEY_PERIOD: return 46;
            case KEY_SEMICOLON: return 59;
            case KEY_APOSTROPHE: return 39;
            case KEY_BRACKET_LEFT: return 91;
            case KEY_BRACKET_RIGHT: return 93;
            case KEY_GRAVE: return 96;
            case KEY_MINUS: return 45;
            case KEY_EQUAL: return 61;
            case KEY_SLASH: return 47;
            case KEY_BACKSLASH: return 92;
            case KEY_KP_DIVIDE: return 65455;
            case KEY_KP_MULTIPLY: return 65450;
            case KEY_KP_SUBTRACT: return 65453;
            case KEY_KP_ADD: return 65451;
            case KEY_KP_0: return 65456;
            case KEY_KP_1: return 65457;
            case KEY_KP_2: return 65458;
            case KEY_KP_3: return 65459;
            case KEY_KP_4: return 65460;
            case KEY_KP_5: return 65461;
            case KEY_KP_6: return 65462;
            case KEY_KP_7: return 65463;
            case KEY_KP_8: return 65464;
            case KEY_KP_9: return 65465;
            case KEY_KP_DEL: return 65439;
            case KEY_F1: return 65470;
            case KEY_F2: return 65471;
            case KEY_F3: return 65472;
            case KEY_F4: return 65473;
            case KEY_F5: return 65474;
            case KEY_F6: return 65475;
            case KEY_F7: return 65476;
            case KEY_F8: return 65477;
            case KEY_F9: return 65478;
            case KEY_F10: return 65479;
            case KEY_F11: return 65480;
            case KEY_F12: return 65481;
            default: return 0;
        }
    }
}