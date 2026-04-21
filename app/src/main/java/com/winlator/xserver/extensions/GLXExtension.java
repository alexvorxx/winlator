package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;
import android.util.Log;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.XRequestError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GLXExtension implements Extension {
    public static final byte MAJOR_OPCODE = -106;
    private static final String TAG = "GLXExtension";

    // Minor opcodes
    private static final int GLX_RENDER               = 1;
    private static final int GLX_CREATE_CONTEXT       = 3;
    private static final int GLX_DESTROY_CONTEXT      = 4;
    private static final int GLX_MAKE_CURRENT         = 5;
    private static final int GLX_IS_DIRECT            = 6;
    private static final int GLX_QUERY_VERSION        = 7;
    private static final int GLX_SWAP_BUFFERS         = 11;
    private static final int GLX_GET_VISUAL_CONFIGS   = 14;
    private static final int GLX_QUERY_SERVER_STRING  = 19;
    private static final int GLX_GET_FB_CONFIGS       = 21;
    private static final int GLX_CREATE_NEW_CONTEXT   = 24;
    private static final int GLX_GET_DRAWABLE_ATTRIBUTES = 29;
    private static final int GLX_CREATE_WINDOW = 31;
    private static final int GLX_DESTROY_WINDOW = 32;
    private static final int GLX_CREATE_CONTEXT_ATTRIBS_ARB = 34;
    private static final int GLX_SET_CLIENT_INFO_2_ARB = 35;
    private static final int GLX_GEN_LISTS        = 104;
    private static final int GLX_GET_STRING       = 129;

    // Bits
    public static final int GLX_WINDOW_BIT = 0x00000001;
    public static final int GLX_RGBA_BIT = 0x00000001;
    private static final int GLX_PBUFFER_BIT = 0x00000002;
    private static final int GLX_PIXMAP_BIT   = 0x00000004;
    public static final int DEFAULT_FBCONFIG_ID = 1;
    public static final int GLX_BUFFER_SIZE = 2;
    public static final int GLX_LEVEL = 3;
    public static final int GLX_RGBA = 4;
    public static final int GLX_DOUBLEBUFFER = 5;
    public static final int GLX_STEREO = 6;
    public static final int GLX_AUX_BUFFERS = 7;
    public static final int GLX_RED_SIZE = 8;
    public static final int GLX_GREEN_SIZE = 9;
    public static final int GLX_BLUE_SIZE = 10;
    public static final int GLX_ALPHA_SIZE = 11;
    public static final int GLX_DEPTH_SIZE = 12;
    public static final int GLX_STENCIL_SIZE = 13;
    public static final int GLX_ACCUM_RED_SIZE = 14;
    public static final int GLX_ACCUM_GREEN_SIZE = 15;
    public static final int GLX_ACCUM_BLUE_SIZE = 16;
    public static final int GLX_ACCUM_ALPHA_SIZE = 17;
    public static final int GLX_CONFIG_CAVEAT = 0x20;
    public static final int GLX_X_VISUAL_TYPE = 0x22;
    public static final int GLX_TRANSPARENT_TYPE = 0x23;
    public static final int GLX_TRANSPARENT_INDEX_VALUE = 0x24;
    public static final int GLX_TRANSPARENT_RED_VALUE = 0x25;
    public static final int GLX_TRANSPARENT_GREEN_VALUE = 0x26;
    public static final int GLX_TRANSPARENT_BLUE_VALUE = 0x27;
    public static final int GLX_TRANSPARENT_ALPHA_VALUE = 0x28;
    public static final short GLX_CONTEXT_MAJOR_VERSION_ARB = 0x2091;
    public static final short GLX_CONTEXT_MINOR_VERSION_ARB = 0x2092;
    public static final short GLX_CONTEXT_FLAGS_ARB = 0x2094;
    public static final int GLX_NONE = 0x8000;
    public static final int GLX_SLOW_CONFIG = 0x8001;
    public static final int GLX_TRUE_COLOR = 0x8002;
    public static final int GLX_DIRECT_COLOR = 0x8003;
    public static final int GLX_PSEUDO_COLOR = 0x8004;
    public static final int GLX_STATIC_COLOR = 0x8005;
    public static final int GLX_GRAY_SCALE = 0x8006;
    public static final int GLX_STATIC_GRAY = 0x8007;
    public static final int GLX_TRANSPARENT_RGB = 0x8008;
    public static final int GLX_TRANSPARENT_INDEX = 0x8009;
    public static final int GLX_VISUAL_ID = 0x800B;
    public static final int GLX_SCREEN = 0x800C;
    public static final int GLX_NON_CONFORMANT_CONFIG = 0x800D;
    public static final int GLX_DRAWABLE_TYPE = 0x8010;
    public static final int GLX_RENDER_TYPE = 0x8011;
    public static final int GLX_X_RENDERABLE = 0x8012;
    public static final int GLX_FBCONFIG_ID = 0x8013;
    public static final int GLX_RGBA_TYPE = 0x8014;
    public static final int GLX_COLOR_INDEX_TYPE = 0x8015;
    public static final int GLX_MAX_PBUFFER_WIDTH = 0x8016;
    public static final int GLX_MAX_PBUFFER_HEIGHT = 0x8017;
    public static final int GLX_MAX_PBUFFER_PIXELS = 0x8018;
    public static final int GLX_PRESERVED_CONTENTS = 0x801B;
    public static final int GLX_LARGEST_PBUFFER = 0x801C;
    public static final int GLX_OPTIMAL_PBUFFER_WIDTH_SGIX  = 0x8037;
    public static final int GLX_OPTIMAL_PBUFFER_HEIGHT_SGIX = 0x8038;
    public static final int GLX_VISUAL_SELECT_GROUP_SGIX    = 0x8062;
    public static final int GLX_SWAP_METHOD_OML             = 0x8063;
    public static final int GLX_BIND_TO_TEXTURE_RGB_EXT     = 0x8064;
    public static final int GLX_BIND_TO_TEXTURE_RGBA_EXT    = 0x8065;
    public static final int GLX_BIND_TO_MIPMAP_TEXTURE_EXT  = 0x8066;
    public static final int GLX_BIND_TO_TEXTURE_TARGETS_EXT = 0x8067;
    public static final int GLX_Y_INVERTED_EXT              = 0x8068;
    public static final int GLX_FRAMEBUFFER_SRGB_CAPABLE_EXT= 0x8069;

    public static final int GLX_SAMPLE_BUFFERS = 100000;
    public static final int GLX_SAMPLES = 100001;

    private List<int[]> generatedConfigs = null;
    private final Map<Integer, Integer> fbConfigToVisual = new HashMap<>();
    private final Map<Integer, Integer> windowToFbConfig = new HashMap<>();

    private XServer xServer;

    public GLXExtension(XServer xServer) {
        this.xServer = xServer;
        Log.i(TAG, "GLX extension registered");
    }

    @Override public String getName() { return "GLX"; }

    @Override public byte getMajorOpcode() { return MAJOR_OPCODE; }

    @Override public byte getFirstErrorId() { return -100; }

    @Override public byte getFirstEventId() { return 94; }

    private synchronized List<int[]> getFBConfigsList() {
        if (generatedConfigs != null) return generatedConfigs;

        List<int[]> configs = new ArrayList<>();
        fbConfigToVisual.clear();

        int[] visualIds = {xServer.pixmapManager.glxVisual.id, xServer.pixmapManager.visual.id};
        int[] colorSizes = {24, 32};
        boolean[] doubleBufferOptions = {false, true};
        int[] depthOptions = {0, 24};
        int[] stencilOptions = {0, 8};
        int[] accumOptions = {0, 64};
        int[] drawableTypeOptions = {
                GLX_WINDOW_BIT,
                GLX_PBUFFER_BIT | GLX_PIXMAP_BIT,
                GLX_WINDOW_BIT | GLX_PBUFFER_BIT | GLX_PIXMAP_BIT
        };

        int fbconfigId = 1;
        for (int i = 0; i < visualIds.length; i++) {
            int visualId = visualIds[i];
            int colorBits = colorSizes[i];
            int red = 8, green = 8, blue = 8, alpha = (colorBits == 32) ? 8 : 0;
            for (boolean doubleBuffer : doubleBufferOptions) {
                for (int depth : depthOptions) {
                    for (int stencil : stencilOptions) {
                        for (int accum : accumOptions) {
                            for (int drawableType : drawableTypeOptions) {
                                boolean isWindow = (drawableType & GLX_WINDOW_BIT) != 0;
                                List<Integer> attrs = new ArrayList<>();
                                attrs.add(GLX_FBCONFIG_ID); attrs.add(fbconfigId);
                                attrs.add(GLX_BUFFER_SIZE); attrs.add(colorBits);
                                attrs.add(GLX_LEVEL); attrs.add(0);
                                attrs.add(GLX_DOUBLEBUFFER); attrs.add(doubleBuffer ? 1 : 0);
                                attrs.add(GLX_STEREO); attrs.add(0);
                                attrs.add(GLX_AUX_BUFFERS); attrs.add(0);
                                attrs.add(GLX_RED_SIZE); attrs.add(red);
                                attrs.add(GLX_GREEN_SIZE); attrs.add(green);
                                attrs.add(GLX_BLUE_SIZE); attrs.add(blue);
                                attrs.add(GLX_ALPHA_SIZE); attrs.add(alpha);
                                attrs.add(GLX_DEPTH_SIZE); attrs.add(depth);
                                attrs.add(GLX_STENCIL_SIZE); attrs.add(stencil);
                                attrs.add(GLX_ACCUM_RED_SIZE); attrs.add(accum);
                                attrs.add(GLX_ACCUM_GREEN_SIZE); attrs.add(accum);
                                attrs.add(GLX_ACCUM_BLUE_SIZE); attrs.add(accum);
                                attrs.add(GLX_ACCUM_ALPHA_SIZE); attrs.add(accum);
                                attrs.add(GLX_RENDER_TYPE); attrs.add(GLX_RGBA_BIT);
                                attrs.add(GLX_DRAWABLE_TYPE); attrs.add(drawableType);
                                attrs.add(GLX_X_RENDERABLE); attrs.add(isWindow ? 1 : 0);
                                attrs.add(GLX_X_VISUAL_TYPE); attrs.add(isWindow ? 0x8002 : 0);
                                attrs.add(GLX_CONFIG_CAVEAT); attrs.add(0x8000);
                                attrs.add(GLX_TRANSPARENT_TYPE); attrs.add(0x8000);
                                attrs.add(GLX_TRANSPARENT_RED_VALUE); attrs.add(0);
                                attrs.add(GLX_TRANSPARENT_GREEN_VALUE); attrs.add(0);
                                attrs.add(GLX_TRANSPARENT_BLUE_VALUE); attrs.add(0);
                                attrs.add(GLX_TRANSPARENT_ALPHA_VALUE); attrs.add(0);
                                attrs.add(GLX_TRANSPARENT_INDEX_VALUE); attrs.add(0);
                                attrs.add(GLX_VISUAL_ID); attrs.add(isWindow ? visualId : 0);
                                attrs.add(GLX_SAMPLE_BUFFERS); attrs.add(0);
                                attrs.add(GLX_SAMPLES); attrs.add(0);
                                attrs.add(GLX_MAX_PBUFFER_WIDTH); attrs.add(0);
                                attrs.add(GLX_MAX_PBUFFER_HEIGHT); attrs.add(0);
                                attrs.add(GLX_MAX_PBUFFER_PIXELS); attrs.add(0);
                                attrs.add(GLX_OPTIMAL_PBUFFER_WIDTH_SGIX); attrs.add(0);
                                attrs.add(GLX_OPTIMAL_PBUFFER_HEIGHT_SGIX); attrs.add(0);
                                attrs.add(GLX_VISUAL_SELECT_GROUP_SGIX); attrs.add(0);
                                attrs.add(GLX_SWAP_METHOD_OML); attrs.add(0x8063);
                                attrs.add(GLX_BIND_TO_TEXTURE_RGB_EXT); attrs.add(0);
                                attrs.add(GLX_BIND_TO_TEXTURE_RGBA_EXT); attrs.add(0);
                                attrs.add(GLX_BIND_TO_MIPMAP_TEXTURE_EXT); attrs.add(0);
                                attrs.add(GLX_BIND_TO_TEXTURE_TARGETS_EXT); attrs.add(0);
                                attrs.add(GLX_Y_INVERTED_EXT); attrs.add(0);
                                attrs.add(GLX_FRAMEBUFFER_SRGB_CAPABLE_EXT); attrs.add(0);

                                configs.add(attrs.stream().mapToInt(Integer::intValue).toArray());
                                fbConfigToVisual.put(fbconfigId, isWindow ? visualId : 0);
                                fbconfigId++;
                            }
                        }
                    }
                }
            }
        }
        generatedConfigs = configs;
        return configs;
    }

    private void handleQueryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextTag = inputStream.readInt();
        int name = inputStream.readInt();
        //Log.d(TAG, "QueryVersion: context_tag=" + contextTag + ", name=" + name);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(4);
            outputStream.writePad(16);
        }
        //Log.d(TAG, "QueryVersion reply sent");
    }

    private void handleQueryServerString(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextTag = inputStream.readInt();
        int name = inputStream.readInt();
        //Log.d(TAG, "QueryServerString: contextTag=" + contextTag + ", name=" + name);

        String str;
        if (name == 0x1) {
            str = "Mesa Project and SGI ";
        }
        else if (name == 0x2) {
            str = "1.4 ";
        }
        else if (name == 0x3) {
            str = "GLX_ARB_create_context GLX_ARB_create_context_no_error GLX_ARB_create_context_profile " +
                    "GLX_ARB_fbconfig_float GLX_ARB_framebuffer_sRGB GLX_ARB_multisample GLX_EXT_create_context_es_profile " +
                    "GLX_EXT_create_context_es2_profile GLX_EXT_fbconfig_packed_float GLX_EXT_framebuffer_sRGB " +
                    "GLX_EXT_get_drawable_type GLX_EXT_libglvnd GLX_EXT_no_config_context GLX_EXT_texture_from_pixmap " +
                    "GLX_EXT_visual_info GLX_EXT_visual_rating GLX_MESA_copy_sub_buffer GLX_OML_swap_method " +
                    "GLX_SGI_make_current_read GLX_SGIS_multisample GLX_SGIX_fbconfig GLX_SGIX_pbuffer GLX_SGIX_visual_select_group " +
                    "GLX_MESA_pixmap_colormap GLX_MESA_release_buffers GLX_ARB_get_proc_address " +
                    "GLX_ARB_create_context_robustness GLX_EXT_import_context GLX_INTEL_swap_event ";
        } else {
            str = "mesa ";
        }

        byte[] bytes = str.getBytes(StandardCharsets.US_ASCII);
        int len = bytes.length;
        int pad = (4 - (len % 4)) % 4;
        int dataWords = (len + pad) / 4;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(dataWords);
            outputStream.writePad(4);
            outputStream.writeInt(len);
            outputStream.writePad(16);

            outputStream.write(bytes);
            outputStream.writePad(pad);
        }
    }

    private void handleGetVisualConfigs(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int screen = inputStream.readInt();

        int[] attrs1 = {xServer.pixmapManager.glxVisual.id, 4, 1, 8, 8, 8, 0, 0, 0, 0, 0, 1, 0, 24, 24, 0, 0, 0,
                // props
                0x00000020, 0x00008000, // GLX_VISUAL_CAVEAT_EXT
                0x00000023, 0x00008000, // GLX_TRANSPARENT_TYPE
                0x00000025, 0xffffffff, // GLX_TRANSPARENT_RED_VALUE
                0x00000026, 0xffffffff, // GLX_TRANSPARENT_GREEN_VALUE
                0x00000027, 0xffffffff, // GLX_TRANSPARENT_BLUE_VALUE
                0x00000028, 0xffffffff, // GLX_TRANSPARENT_ALPHA_VALUE
                0x00000024, 0x00000000, // GLX_TRANSPARENT_INDEX_VALUE
                0x000186a1, 0x00000000, // GLX_SAMPLES_SGIS
                0x000186a0, 0x00000000, // GLX_SAMPLE_BUFFERS_SGIS
                0x00008028, 0x00000000, // unknown
                0x00000000, 0x00000000  // terminator
        };

        int[] attrs2 = {xServer.pixmapManager.visual.id, 4, 1, 8, 8, 8, 0, 0, 0, 0, 0, 1, 0, 24, 24, 0, 0, 0,
                // props
                0x00000020, 0x00008000, // GLX_VISUAL_CAVEAT_EXT
                0x00000023, 0x00008000, // GLX_TRANSPARENT_TYPE
                0x00000025, 0xffffffff, // GLX_TRANSPARENT_RED_VALUE
                0x00000026, 0xffffffff, // GLX_TRANSPARENT_GREEN_VALUE
                0x00000027, 0xffffffff, // GLX_TRANSPARENT_BLUE_VALUE
                0x00000028, 0xffffffff, // GLX_TRANSPARENT_ALPHA_VALUE
                0x00000024, 0x00000000, // GLX_TRANSPARENT_INDEX_VALUE
                0x000186a1, 0x00000000, // GLX_SAMPLES_SGIS
                0x000186a0, 0x00000000, // GLX_SAMPLE_BUFFERS_SGIS
                0x00008028, 0x00000000, // unknown
                0x00000000, 0x00000000  // terminator
        };

        int numVisuals = 2;
        int length = attrs1.length;
        int dataWords = length * numVisuals;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(dataWords);
            outputStream.writeInt(numVisuals);
            outputStream.writeInt(length);
            outputStream.writePad(16);
            for (int v : attrs1) outputStream.writeInt(v);
            for (int v : attrs2) outputStream.writeInt(v);
            //Log.d(TAG, "Sent GetVisualConfigs reply: numVisuals=" + numVisuals + ", length=" + length + ", dataWords=" + dataWords);
        }
    }

    private void handleGetFBConfigs(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int screen = inputStream.readInt();

        List<int[]> allConfigs = getFBConfigsList();
        int numConfigs = allConfigs.size();
        int numProperties = allConfigs.get(0).length / 2;
        int dataWords = 2 * numConfigs * numProperties;

        int onscreenCount = 0;
        for (int[] cfg : allConfigs) {
            for (int i = 0; i < cfg.length; i += 2) {
                if (cfg[i] == GLX_DRAWABLE_TYPE && (cfg[i+1] & GLX_WINDOW_BIT) != 0) {
                    onscreenCount++;
                    break;
                }
            }
        }
        //Log.d(TAG, "Total configs: " + numConfigs + ", onscreen: " + onscreenCount);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(dataWords);
            outputStream.writeInt(numConfigs);
            outputStream.writeInt(numProperties);
            outputStream.writePad(16);
            for (int[] cfg : allConfigs) {
                for (int value : cfg) {
                    outputStream.writeInt(value);
                }
            }
        }
        //Log.d(TAG, "Sent GetFBConfigs reply: numConfigs=" + numConfigs + ", numProperties=" + numProperties + ", dataWords=" + dataWords);
    }

    private void handleCreateContext(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextId = inputStream.readInt();
        int visualId = inputStream.readInt();
        int screen = inputStream.readInt();
        int shareList = inputStream.readInt();
        boolean isDirect = inputStream.readInt() != 0;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writePad(24);
            outputStream.writePad(4); // Additional
        }
    }

    private void handleIsDirect(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextId = inputStream.readInt();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(1);
            outputStream.writeByte((byte)1);
            outputStream.writePad(23);
            outputStream.writePad(4); // Additional
        }
    }

    private void handleMakeCurrent(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawable = inputStream.readInt();
        int readDrawable = inputStream.readInt();
        int contextId = inputStream.readInt();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(1);
            outputStream.writeInt(0); // context_tag = 0
            outputStream.writePad(20);
        }
    }

    private void handleGetString(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextTag = inputStream.readInt();
        int name = inputStream.readInt();
        //Log.d(TAG, "GetString: contextTag=" + contextTag + ", name=0x" + Integer.toHexString(name));

        String str;
        switch (name) {
            case 0x1F00: str = "Mesa Project"; break;
            case 0x1F01: str = "Software Rasterizer"; break;
            case 0x1F02: str = "1.4"; break;
            case 0x1F03: str = "GL_ARB_multitexture GL_EXT_texture_env_add"; break;
            default: str = "";
        }

        byte[] bytes = str.getBytes(StandardCharsets.US_ASCII);
        int len = bytes.length;
        int pad = (4 - (len % 4)) % 4;
        int dataWords = (len + pad) / 4;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(dataWords);
            outputStream.writePad(4);
            outputStream.writeInt(len);
            outputStream.writePad(16);
            outputStream.write(bytes);
            outputStream.writePad(pad);
        }
    }

    private void handleSwapBuffers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int drawable = inputStream.readInt();
        Window window = xServer.windowManager.getWindow(drawable);
        if (window != null) {
            window.getContent().forceUpdate();
            //xServer.windowManager.triggerOnUpdateWindowContent(window);
        }
    }

    private void handleGenLists(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextTag = inputStream.readInt();
        int range = inputStream.readInt();
        //Log.d(TAG, "glGenLists: range=" + range);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(1); // data words
            outputStream.writeInt(1); // list base
            outputStream.writePad(20);
        }
    }

    private void handleCreateNewContext(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextId = inputStream.readInt();
        int fbConfig = inputStream.readInt();
        int screen = inputStream.readInt();
        int renderType = inputStream.readInt();
        int shareList = inputStream.readInt();
        int isDirect = inputStream.readInt();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(contextId);
            outputStream.writePad(24);
        }
        //Log.d(TAG, "CreateNewContext reply: context=" + contextId);
    }

    private void handleCreateContextAttribsARB(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextId = inputStream.readInt();
        int fbConfigId = inputStream.readInt();
        inputStream.skip(4);
        int shareContext = inputStream.readInt();
        inputStream.skip(4);
        int numAttribs = inputStream.readInt();

        for (int i = 0; i < numAttribs; i++) {
            int name = inputStream.readInt();
            int value = inputStream.readInt();
        }

        //Log.d(TAG, "CreateContextAttribsARB reply: context=" + contextId + " numAttribs=" + numAttribs);
    }

    private void handleGetDrawableAttributes(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int drawable = inputStream.readInt();

        int remaining = client.getRequestLength() - 4;
        if (remaining > 0) inputStream.skip(remaining);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte) RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(1); // length = 1
            outputStream.writeInt(0); // numAttribs
            outputStream.writePad(20);
            outputStream.writePad(4); // Additional
        }
        //Log.d(TAG, "GetDrawableAttributes reply: drawable=" + drawable);
    }

    private void handleDestroyContext(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextId = inputStream.readInt();
        //Log.d(TAG, "DestroyContext: context=" + contextId);
    }

    private void handleCreateWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int screen = inputStream.readInt();
        int fbconfig = inputStream.readInt();
        int window = inputStream.readInt();
        int glxWindow = inputStream.readInt();
        int numAttribs = inputStream.readInt();

        for (int i = 0; i < numAttribs * 2; i++) {
            inputStream.readInt();
        }

        windowToFbConfig.put(glxWindow, fbconfig);
        /*Log.d(TAG, "CreateWindow: screen=" + screen + ", window=" + Integer.toHexString(window);*/
    }

    private void handleDestroyWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int glxWindow = inputStream.readInt();
        windowToFbConfig.remove(glxWindow);
        //Log.d(TAG, "DestroyWindow: glxWindow=" + Integer.toHexString(glxWindow));
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try {
            int glxCode = client.getRequestData() & 0xFF;
            //Log.d(TAG, "GLX minor opcode received: " + glxCode);
            switch (glxCode) {
                case GLX_QUERY_VERSION:
                    handleQueryVersion(client, inputStream, outputStream);
                    break;
                case GLX_QUERY_SERVER_STRING:
                    handleQueryServerString(client, inputStream, outputStream);
                    break;
                case GLX_GET_VISUAL_CONFIGS:
                    handleGetVisualConfigs(client, inputStream, outputStream);
                    break;
                case GLX_GET_FB_CONFIGS:
                    handleGetFBConfigs(client, inputStream, outputStream);
                    break;
                case GLX_CREATE_CONTEXT:
                    handleCreateContext(client, inputStream, outputStream);
                    break;
                case GLX_DESTROY_CONTEXT:
                    handleDestroyContext(client, inputStream, outputStream);
                    break;
                case GLX_IS_DIRECT:
                    handleIsDirect(client, inputStream, outputStream);
                    break;
                case GLX_MAKE_CURRENT:
                    handleMakeCurrent(client, inputStream, outputStream);
                    break;
                case GLX_GET_STRING:
                    handleGetString(client, inputStream, outputStream);
                    break;
                case GLX_RENDER:
                    inputStream.skip(client.getRemainingRequestLength());
                    break;
                case GLX_SWAP_BUFFERS:
                    handleSwapBuffers(client, inputStream, outputStream);
                    break;
                case GLX_GEN_LISTS:
                    handleGenLists(client, inputStream, outputStream);
                    break;
                case GLX_CREATE_CONTEXT_ATTRIBS_ARB:
                    handleCreateContextAttribsARB(client, inputStream, outputStream);
                    break;
                case GLX_CREATE_NEW_CONTEXT:
                    handleCreateNewContext(client, inputStream, outputStream);
                    break;
                case GLX_SET_CLIENT_INFO_2_ARB:
                    inputStream.skip(client.getRemainingRequestLength());
                    break;
                case GLX_GET_DRAWABLE_ATTRIBUTES:
                    handleGetDrawableAttributes(client, inputStream, outputStream);
                    break;
                case GLX_CREATE_WINDOW:
                    handleCreateWindow(client, inputStream, outputStream);
                    break;
                case GLX_DESTROY_WINDOW:
                    handleDestroyWindow(client, inputStream, outputStream);
                    break;
                default:
                    Log.w(TAG, "Unknown glxCode: " + glxCode);
                    throw new XRequestError(0, client.getSequenceNumber());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling GLX request", e);
            throw e;
        }
    }

}