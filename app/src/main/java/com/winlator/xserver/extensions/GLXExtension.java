package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;
import android.util.Log;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.XRequestError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GLXExtension extends Extension {
    public static final byte MAJOR_VERSION = 1;
    public static final byte MINOR_VERSION = 4;
    private static final String TAG = "GLXExtension";

    private boolean isMesa = true;

    private static abstract class ClientOpcodes {
        private static final byte CREATE_GL_CONTEXT = 1;
        private static final byte DESTROY_GL_CONTEXT = 2;
        private static final byte CREATE_CONTEXT = 3;
        private static final byte DESTROY_CONTEXT = 4;
        private static final byte MAKE_CURRENT = 5;
        private static final byte IS_DIRECT = 6;
        private static final byte QUERY_VERSION = 7;
        private static final byte SWAP_BUFFERS = 11;
        private static final byte GET_VISUAL_CONFIGS = 14;
        private static final byte QUERY_EXTENSIONS_STRING = 18;
        private static final byte QUERY_SERVER_STRING = 19;
        private static final byte GET_FB_CONFIGS = 21;
        private static final byte CREATE_NEW_CONTEXT = 24;
        private static final byte GET_DRAWABLE_ATTRIBUTES = 29;
        private static final byte CREATE_WINDOW = 31;
        private static final byte DESTROY_WINDOW = 32;
        private static final byte CREATE_CONTEXT_ATTRIBS_ARB = 34;
        private static final byte SET_CLIENT_INFO_2_ARB = 35;
        private static final byte GEN_LISTS = 104;
        private static final int GET_STRING = 129;
    }

    private List<int[]> generatedConfigs = null;
    private final Map<Integer, Integer> fbConfigToVisual = new HashMap<>();
    private final Map<Integer, Integer> windowToFbConfig = new HashMap<>();

    public GLXExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
        Log.i(TAG, "GLX extension registered");
    }

    @Override public String getName() { return "GLX"; }

    @Override public byte getFirstErrorId() { return Byte.MIN_VALUE; }

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
                GLXEnums.GLX_WINDOW_BIT,
                GLXEnums.GLX_PBUFFER_BIT | GLXEnums.GLX_PIXMAP_BIT,
                GLXEnums.GLX_WINDOW_BIT | GLXEnums.GLX_PBUFFER_BIT | GLXEnums.GLX_PIXMAP_BIT
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
                                boolean isWindow = (drawableType & GLXEnums.GLX_WINDOW_BIT) != 0;
                                List<Integer> attrs = new ArrayList<>();
                                attrs.add(GLXEnums.GLX_FBCONFIG_ID); attrs.add(fbconfigId);
                                attrs.add((int) GLXEnums.GLX_BUFFER_SIZE); attrs.add(colorBits);
                                attrs.add((int) GLXEnums.GLX_LEVEL); attrs.add(0);
                                attrs.add((int) GLXEnums.GLX_DOUBLEBUFFER); attrs.add(doubleBuffer ? 1 : 0);
                                attrs.add((int) GLXEnums.GLX_STEREO); attrs.add(0);
                                attrs.add((int) GLXEnums.GLX_AUX_BUFFERS); attrs.add(0);
                                attrs.add((int) GLXEnums.GLX_RED_SIZE); attrs.add(red);
                                attrs.add((int) GLXEnums.GLX_GREEN_SIZE); attrs.add(green);
                                attrs.add((int) GLXEnums.GLX_BLUE_SIZE); attrs.add(blue);
                                attrs.add((int) GLXEnums.GLX_ALPHA_SIZE); attrs.add(alpha);
                                attrs.add((int) GLXEnums.GLX_DEPTH_SIZE); attrs.add(depth);
                                attrs.add((int) GLXEnums.GLX_STENCIL_SIZE); attrs.add(stencil);
                                attrs.add((int) GLXEnums.GLX_ACCUM_RED_SIZE); attrs.add(accum);
                                attrs.add((int) GLXEnums.GLX_ACCUM_GREEN_SIZE); attrs.add(accum);
                                attrs.add((int) GLXEnums.GLX_ACCUM_BLUE_SIZE); attrs.add(accum);
                                attrs.add((int) GLXEnums.GLX_ACCUM_ALPHA_SIZE); attrs.add(accum);
                                attrs.add((int) GLXEnums.GLX_RENDER_TYPE); attrs.add((int) GLXEnums.GLX_RGBA_BIT);
                                attrs.add((int) GLXEnums.GLX_DRAWABLE_TYPE); attrs.add(drawableType);
                                attrs.add((int) GLXEnums.GLX_X_RENDERABLE); attrs.add(isWindow ? 1 : 0);
                                attrs.add((int) GLXEnums.GLX_X_VISUAL_TYPE); attrs.add(isWindow ? 0x8002 : 0);
                                attrs.add((int) GLXEnums.GLX_CONFIG_CAVEAT); attrs.add(0x8000);
                                attrs.add((int) GLXEnums.GLX_TRANSPARENT_TYPE); attrs.add(0x8000);
                                attrs.add((int) GLXEnums.GLX_TRANSPARENT_RED_VALUE); attrs.add(0);
                                attrs.add((int) GLXEnums.GLX_TRANSPARENT_GREEN_VALUE); attrs.add(0);
                                attrs.add((int) GLXEnums.GLX_TRANSPARENT_BLUE_VALUE); attrs.add(0);
                                attrs.add((int) GLXEnums.GLX_TRANSPARENT_ALPHA_VALUE); attrs.add(0);
                                attrs.add((int) GLXEnums.GLX_TRANSPARENT_INDEX_VALUE); attrs.add(0);
                                attrs.add(GLXEnums.GLX_VISUAL_ID); attrs.add(isWindow ? visualId : 0);
                                attrs.add(GLXEnums.GLX_SAMPLE_BUFFERS); attrs.add(0);
                                attrs.add(GLXEnums.GLX_SAMPLES); attrs.add(0);
                                attrs.add(GLXEnums.GLX_MAX_PBUFFER_WIDTH); attrs.add(0);
                                attrs.add(GLXEnums.GLX_MAX_PBUFFER_HEIGHT); attrs.add(0);
                                attrs.add(GLXEnums.GLX_MAX_PBUFFER_PIXELS); attrs.add(0);
                                attrs.add(GLXEnums.GLX_OPTIMAL_PBUFFER_WIDTH_SGIX); attrs.add(0);
                                attrs.add(GLXEnums.GLX_OPTIMAL_PBUFFER_HEIGHT_SGIX); attrs.add(0);
                                attrs.add(GLXEnums.GLX_VISUAL_SELECT_GROUP_SGIX); attrs.add(0);
                                attrs.add(GLXEnums.GLX_SWAP_METHOD_OML); attrs.add(0x8063);
                                attrs.add(GLXEnums.GLX_BIND_TO_TEXTURE_RGB_EXT); attrs.add(0);
                                attrs.add(GLXEnums.GLX_BIND_TO_TEXTURE_RGBA_EXT); attrs.add(0);
                                attrs.add(GLXEnums.GLX_BIND_TO_MIPMAP_TEXTURE_EXT); attrs.add(0);
                                attrs.add(GLXEnums.GLX_BIND_TO_TEXTURE_TARGETS_EXT); attrs.add(0);
                                attrs.add(GLXEnums.GLX_Y_INVERTED_EXT); attrs.add(0);
                                attrs.add(GLXEnums.GLX_FRAMEBUFFER_SRGB_CAPABLE_EXT); attrs.add(0);

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

    private void mesaQueryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextTag = inputStream.readInt();
        int name = inputStream.readInt();
        //Log.d(TAG, "QueryVersion: context_tag=" + contextTag + ", name=" + name);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(MAJOR_VERSION);
            outputStream.writeInt(MINOR_VERSION);
            outputStream.writePad(16);
        }
        //Log.d(TAG, "QueryVersion reply sent");
    }

    private void mesaQueryServerString(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextTag = inputStream.readInt();
        int name = inputStream.readInt();
        //Log.d(TAG, "QueryServerString: contextTag=" + contextTag + ", name=" + name);

        String str;
        if (name == GLXEnums.GLX_VENDOR) {
            str = "Winlator ";
        }
        else if (name == GLXEnums.GLX_VERSION) {
            str = "1.4 ";
        }
        else if (name == GLXEnums.GLX_EXTENSIONS) {
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

    private void mesaGetVisualConfigs(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
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

    private void mesaGetFBConfigs(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int screen = inputStream.readInt();

        List<int[]> allConfigs = getFBConfigsList();
        int numConfigs = allConfigs.size();
        int numProperties = allConfigs.get(0).length / 2;
        int dataWords = 2 * numConfigs * numProperties;

        int onscreenCount = 0;
        for (int[] cfg : allConfigs) {
            for (int i = 0; i < cfg.length; i += 2) {
                if (cfg[i] == GLXEnums.GLX_DRAWABLE_TYPE && (cfg[i+1] & GLXEnums.GLX_WINDOW_BIT) != 0) {
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

    private void mesaCreateContext(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
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

    private void mesaIsDirect(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
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

    private void mesaMakeCurrent(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
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

    private void mesaGetString(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
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

    private void mesaSwapBuffers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int drawable = inputStream.readInt();
        Window window = xServer.windowManager.getWindow(drawable);
        if (window != null) {
            window.getContent().forceUpdate();
            //xServer.windowManager.triggerOnUpdateWindowContent(window);
        }
    }

    private void mesaGenLists(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
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

    private void mesaCreateNewContext(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
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

    private void mesaCreateContextAttribsARB(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
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

    private void mesaGetDrawableAttributes(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
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

    private void mesaDestroyContext(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int contextId = inputStream.readInt();
        //Log.d(TAG, "DestroyContext: context=" + contextId);
    }

    private void mesaCreateWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
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

    private void mesaDestroyWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
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
                case ClientOpcodes.QUERY_VERSION:
                    if (isMesa)
                        mesaQueryVersion(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.QUERY_SERVER_STRING:
                    if (isMesa)
                        mesaQueryServerString(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.GET_VISUAL_CONFIGS:
                    mesaGetVisualConfigs(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.GET_FB_CONFIGS:
                    if (isMesa)
                        mesaGetFBConfigs(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.CREATE_CONTEXT:
                    if (isMesa)
                        mesaCreateContext(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.DESTROY_CONTEXT:
                    if (isMesa)
                        mesaDestroyContext(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.IS_DIRECT:
                    mesaIsDirect(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.MAKE_CURRENT:
                    mesaMakeCurrent(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.GET_STRING:
                    mesaGetString(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.CREATE_GL_CONTEXT:
                    if (isMesa)
                        inputStream.skip(client.getRemainingRequestLength());
                    break;
                case ClientOpcodes.SWAP_BUFFERS:
                    mesaSwapBuffers(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.GEN_LISTS:
                    mesaGenLists(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.CREATE_CONTEXT_ATTRIBS_ARB:
                    if (isMesa)
                        mesaCreateContextAttribsARB(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.CREATE_NEW_CONTEXT:
                    mesaCreateNewContext(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.SET_CLIENT_INFO_2_ARB:
                    inputStream.skip(client.getRemainingRequestLength());
                    break;
                case ClientOpcodes.GET_DRAWABLE_ATTRIBUTES:
                    mesaGetDrawableAttributes(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.CREATE_WINDOW:
                    mesaCreateWindow(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.DESTROY_WINDOW:
                    mesaDestroyWindow(client, inputStream, outputStream);
                    break;
                default:
                    Log.w(TAG, "Unknown glxCode: " + glxCode);
                    throw new BadImplementation();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling GLX request", e);
            throw e;
        }
    }

}