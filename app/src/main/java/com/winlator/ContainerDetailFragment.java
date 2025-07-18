package com.winlator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.winlator.box86_64.Box86_64Preset;
import com.winlator.box86_64.Box86_64PresetManager;
import com.winlator.box86_64.rc.RCManager;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.contentdialog.AddEnvVarDialog;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.DXVK_VKD3DConfigDialog;
import com.winlator.contentdialog.VirGLConfigDialog;
import com.winlator.contentdialog.VortekConfigDialog;
import com.winlator.contentdialog.WineD3DConfigDialog;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.AppUtils;
import com.winlator.core.Callback;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.KeyValueSet;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.StringUtils;
import com.winlator.core.WineInfo;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.midi.MidiManager;
import com.winlator.widget.CPUListView;
import com.winlator.widget.ColorPickerView;
import com.winlator.widget.EnvVarsView;
import com.winlator.widget.ImagePickerView;
import com.winlator.win32.MSLogFont;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.XKeycode;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class ContainerDetailFragment extends Fragment {
    private ContainerManager manager;
    private ContentsManager contentsManager;
    private final int containerId;
    private Container container;
    private PreloaderDialog preloaderDialog;
    private Callback<String> openDirectoryCallback;

    public ContainerDetailFragment() {
        this(0);
    }

    public ContainerDetailFragment(int containerId) {
        this.containerId = containerId;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == MainActivity.OPEN_DIRECTORY_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                String path = FileUtils.getFilePathFromUri(data.getData());
                if (path != null && openDirectoryCallback != null) openDirectoryCallback.call(path);
            }
            openDirectoryCallback = null;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(isEditMode() ? R.string.edit_container : R.string.new_container);
    }

    public boolean isEditMode() {
        return container != null;
    }

    @SuppressLint("SetTextI18n")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup root, @Nullable Bundle savedInstanceState) {
        final Context context = getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final View view = inflater.inflate(R.layout.container_detail_fragment, root, false);
        manager = new ContainerManager(context);
        container = containerId > 0 ? manager.getContainerById(containerId) : null;
        contentsManager = new ContentsManager(context);
        contentsManager.syncContents();

        final EditText etName = view.findViewById(R.id.ETName);

        if (isEditMode()) {
            etName.setText(container.getName());
        }
        else etName.setText(getString(R.string.container)+"-"+manager.getNextContainerId());

        final ArrayList<WineInfo> wineInfos = WineUtils.getInstalledWineInfos(context);
        final Spinner sWineVersion = view.findViewById(R.id.SWineVersion);
        //if (wineInfos.size() > 1) loadWineVersionSpinner(view, sWineVersion, wineInfos);
        loadWineVersionSpinner(view, sWineVersion, wineInfos);

        loadScreenSizeSpinner(view, isEditMode() ? container.getScreenSize() : Container.DEFAULT_SCREEN_SIZE);

        final Spinner sGraphicsDriver = view.findViewById(R.id.SGraphicsDriver);

        final View vDXWrapperConfig = view.findViewById(R.id.BTDXWrapperConfig);

        final Spinner sDXWrapper = view.findViewById(R.id.SDXWrapper);

        final View vGraphicsDriverConfig = view.findViewById(R.id.GraphicsDriverConfig1);

        vDXWrapperConfig.setTag(isEditMode() ? container.getDXWrapperConfig() : "");

        vGraphicsDriverConfig.setTag(isEditMode() ? container.getGraphicsDriverConfig() : "");

        view.findViewById(R.id.BTHelpGraphicsDriver).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.graphics_driver_help_content));

        setupDXWrapperSpinner(sDXWrapper, vDXWrapperConfig);
        updateGraphicsDriverSpinner(getContext(), contentsManager, sGraphicsDriver);
        loadGraphicsDriverSpinner(sGraphicsDriver, sDXWrapper, vGraphicsDriverConfig, isEditMode() ? container.getGraphicsDriver() : Container.DEFAULT_GRAPHICS_DRIVER,
            isEditMode() ? container.getDXWrapper() : Container.DEFAULT_DXWRAPPER);

        view.findViewById(R.id.BTHelpDXWrapper).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.dxwrapper_help_content));

        Spinner sAudioDriver = view.findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, isEditMode() ? container.getAudioDriver() : Container.DEFAULT_AUDIO_DRIVER);

        Spinner sMIDISoundFont = view.findViewById(R.id.SMIDISoundFont);
        MidiManager.loadSFSpinner(sMIDISoundFont);
        AppUtils.setSpinnerSelectionFromValue(sMIDISoundFont, isEditMode() ? container.getMIDISoundFont() : "");

        final CheckBox cbShowFPS = view.findViewById(R.id.CBShowFPS);
        cbShowFPS.setChecked(isEditMode() && container.isShowFPS());

        final Runnable showInputWarning = () -> ContentDialog.alert(context, R.string.enable_xinput_and_dinput_same_time, null);
        final CheckBox cbEnableXInput = view.findViewById(R.id.CBEnableXInput);
        final CheckBox cbEnableDInput = view.findViewById(R.id.CBEnableDInput);
        final View llDInputType = view.findViewById(R.id.LLDinputMapperType);
        final View btHelpXInput = view.findViewById(R.id.BTXInputHelp);
        final View btHelpDInput = view.findViewById(R.id.BTDInputHelp);
        final Spinner SDInputType = view.findViewById(R.id.SDInputType);
        int inputType = isEditMode() ? container.getInputType() : WinHandler.DEFAULT_INPUT_TYPE;
        cbEnableXInput.setChecked((inputType & WinHandler.FLAG_INPUT_TYPE_XINPUT) == WinHandler.FLAG_INPUT_TYPE_XINPUT);
        cbEnableDInput.setChecked((inputType & WinHandler.FLAG_INPUT_TYPE_DINPUT) == WinHandler.FLAG_INPUT_TYPE_DINPUT);
        cbEnableDInput.setOnCheckedChangeListener((buttonView, isChecked) -> {
            llDInputType.setVisibility(isChecked?View.VISIBLE:View.GONE);
            if (isChecked && cbEnableXInput.isChecked())
                showInputWarning.run();
        });
        cbEnableXInput.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && cbEnableDInput.isChecked())
                showInputWarning.run();
        });
        btHelpXInput.setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_xinput));
        btHelpDInput.setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_dinput));
        SDInputType.setSelection(((inputType & WinHandler.FLAG_DINPUT_MAPPER_STANDARD) == WinHandler.FLAG_DINPUT_MAPPER_STANDARD) ? 0 : 1);
        llDInputType.setVisibility(cbEnableDInput.isChecked()?View.VISIBLE:View.GONE);

        final EditText etLC_ALL = view.findViewById(R.id.ETlcall);
        Locale systemLocal = Locale.getDefault();
        etLC_ALL.setText(isEditMode() ? container.getLC_ALL() : systemLocal.getLanguage() + '_' + systemLocal.getCountry() + ".UTF-8");

        final View btShowLCALL = view.findViewById(R.id.BTShowLCALL);
        btShowLCALL.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            String[] lcs = getResources().getStringArray(R.array.some_lc_all);
            for (int i = 0; i < lcs.length; i++)
                popupMenu.getMenu().add(Menu.NONE, i, Menu.NONE, lcs[i]);
            popupMenu.setOnMenuItemClickListener(item -> {
                etLC_ALL.setText(item.toString() + ".UTF-8");
                return true;
            });
            popupMenu.show();
        });

        final CheckBox cbWoW64Mode = view.findViewById(R.id.CBWoW64Mode);
        cbWoW64Mode.setChecked(!isEditMode() || container.isWoW64Mode());

        final View btHelpWoW64Mode = view.findViewById(R.id.WoW64ModeHelp);
        btHelpWoW64Mode.setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_wow64_mode));

        final Spinner sStartupSelection = view.findViewById(R.id.SStartupSelection);
        byte previousStartupSelection = isEditMode() ? container.getStartupSelection() : -1;
        sStartupSelection.setSelection(previousStartupSelection != -1 ? previousStartupSelection : Container.STARTUP_SELECTION_ESSENTIAL);

        final Spinner sBox86Preset = view.findViewById(R.id.SBox86Preset);
        Box86_64PresetManager.loadSpinner("box86", sBox86Preset, isEditMode() ? container.getBox86Preset() : preferences.getString("box86_preset", Box86_64Preset.COMPATIBILITY));

        final Spinner sBox64Preset = view.findViewById(R.id.SBox64Preset);
        Box86_64PresetManager.loadSpinner("box64", sBox64Preset, isEditMode() ? container.getBox64Preset() : preferences.getString("box64_preset", Box86_64Preset.COMPATIBILITY));

        final Spinner sRCFile = view.findViewById(R.id.SRCFile);
        final int[] rcfileIds = {0};
        RCManager rcManager = new RCManager(context);
        RCManager.loadRCFileSpinner(rcManager, container == null ? 0 : container.getRCFileId(), sRCFile, id -> rcfileIds[0] = id);

        final CPUListView cpuListView = view.findViewById(R.id.CPUListView);
        final CPUListView cpuListViewWoW64 = view.findViewById(R.id.CPUListViewWoW64);

        cpuListView.setCheckedCPUList(isEditMode() ? container.getCPUList(true) : Container.getFallbackCPUList());
        cpuListViewWoW64.setCheckedCPUList(isEditMode() ? container.getCPUListWoW64(true) : Container.getFallbackCPUListWoW64());

        final Spinner sPrimaryController = view.findViewById(R.id.SPrimaryController);
        sPrimaryController.setSelection(isEditMode() ? container.getPrimaryController() : 1);
        setControllerMapping(view.findViewById(R.id.SButtonA), Container.XrControllerMapping.BUTTON_A, XKeycode.KEY_A.ordinal());
        setControllerMapping(view.findViewById(R.id.SButtonB), Container.XrControllerMapping.BUTTON_B, XKeycode.KEY_B.ordinal());
        setControllerMapping(view.findViewById(R.id.SButtonX), Container.XrControllerMapping.BUTTON_X, XKeycode.KEY_X.ordinal());
        setControllerMapping(view.findViewById(R.id.SButtonY), Container.XrControllerMapping.BUTTON_Y, XKeycode.KEY_Y.ordinal());
        setControllerMapping(view.findViewById(R.id.SButtonGrip), Container.XrControllerMapping.BUTTON_GRIP, XKeycode.KEY_SPACE.ordinal());
        setControllerMapping(view.findViewById(R.id.SButtonTrigger), Container.XrControllerMapping.BUTTON_TRIGGER, XKeycode.KEY_ENTER.ordinal());
        setControllerMapping(view.findViewById(R.id.SThumbstickUp), Container.XrControllerMapping.THUMBSTICK_UP, XKeycode.KEY_UP.ordinal());
        setControllerMapping(view.findViewById(R.id.SThumbstickDown), Container.XrControllerMapping.THUMBSTICK_DOWN, XKeycode.KEY_DOWN.ordinal());
        setControllerMapping(view.findViewById(R.id.SThumbstickLeft), Container.XrControllerMapping.THUMBSTICK_LEFT, XKeycode.KEY_LEFT.ordinal());
        setControllerMapping(view.findViewById(R.id.SThumbstickRight), Container.XrControllerMapping.THUMBSTICK_RIGHT, XKeycode.KEY_RIGHT.ordinal());

        final SeekBar sbDPI = view.findViewById(R.id.SBDPI);

        createWineConfigurationTab(view);
        final EnvVarsView envVarsView = createEnvVarsTab(view);
        createWinComponentsTab(view, isEditMode() ? container.getWinComponents() : Container.DEFAULT_WINCOMPONENTS);
        createDrivesTab(view);

        AppUtils.setupTabLayout(view, R.id.TabLayout, R.id.LLTabWineConfiguration, R.id.LLTabWinComponents, R.id.LLTabEnvVars, R.id.LLTabDrives, R.id.LLTabAdvanced, R.id.LLTabXR);

        view.findViewById(R.id.BTConfirm).setOnClickListener((v) -> {
            try {
                String name = etName.getText().toString();
                String screenSize = getScreenSize(view);
                String envVars = envVarsView.getEnvVars();
                String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
                String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString(); ///
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                String dxwrapperConfig = vDXWrapperConfig.getTag().toString();
                String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
                String midiSoundFont = sMIDISoundFont.getSelectedItemPosition() == 0 ? "" : sMIDISoundFont.getSelectedItem().toString();
                String lc_all = etLC_ALL.getText().toString();
                String wincomponents = getWinComponents(view);
                String drives = getDrives(view);
                boolean showFPS = cbShowFPS.isChecked();
                String cpuList = cpuListView.getCheckedCPUListAsString();
                String cpuListWoW64 = cpuListViewWoW64.getCheckedCPUListAsString();
                boolean wow64Mode = cbWoW64Mode.isChecked() /* && cbWoW64Mode.isEnabled() */ ;
                byte startupSelection = (byte)sStartupSelection.getSelectedItemPosition();
                String box86Preset = Box86_64PresetManager.getSpinnerSelectedId(sBox86Preset);
                String box64Preset = Box86_64PresetManager.getSpinnerSelectedId(sBox64Preset);
                String desktopTheme = getDesktopTheme(view);
                int rcfileId = rcfileIds[0];
                int primaryController = sPrimaryController.getSelectedItemPosition();
                String controllerMapping = getControllerMapping(view);
                int dpi = sbDPI.getProgress();

                int finalInputType = 0;
                finalInputType |= cbEnableXInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_XINPUT : 0;
                finalInputType |= cbEnableDInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_DINPUT : 0;
                finalInputType |= SDInputType.getSelectedItemPosition() == 0 ?  WinHandler.FLAG_DINPUT_MAPPER_STANDARD : WinHandler.FLAG_DINPUT_MAPPER_XINPUT;

                if (isEditMode()) {
                    container.setName(name);
                    container.setScreenSize(screenSize);
                    container.setEnvVars(envVars);
                    container.setCPUList(cpuList);
                    container.setCPUListWoW64(cpuListWoW64);
                    container.setGraphicsDriver(graphicsDriver);
                    container.setDXWrapper(dxwrapper);
                    container.setDXWrapperConfig(dxwrapperConfig);
                    container.setGraphicsDriverConfig(graphicsDriverConfig); ///
                    container.setAudioDriver(audioDriver);
                    container.setWinComponents(wincomponents);
                    container.setDrives(drives);
                    container.setShowFPS(showFPS);
                    container.setInputType(finalInputType);
                    container.setWoW64Mode(wow64Mode);
                    container.setStartupSelection(startupSelection);
                    container.setBox86Preset(box86Preset);
                    container.setBox64Preset(box64Preset);
                    container.setDesktopTheme(desktopTheme);
                    container.setRcfileId(rcfileId);
                    container.setMidiSoundFont(midiSoundFont);
                    container.setLC_ALL(lc_all);
                    container.setPrimaryController(primaryController);
                    container.setControllerMapping(controllerMapping);
                    container.setDPI(dpi);
                    container.saveData();
                    saveWineRegistryKeys(view);
                    getActivity().onBackPressed();
                }
                else {
                    JSONObject data = new JSONObject();
                    data.put("name", name);
                    data.put("screenSize", screenSize);
                    data.put("envVars", envVars);
                    data.put("cpuList", cpuList);
                    data.put("cpuListWoW64", cpuListWoW64);
                    data.put("graphicsDriver", graphicsDriver);
                    data.put("graphicsDriverConfig", graphicsDriverConfig); ///
                    data.put("dxwrapper", dxwrapper);
                    data.put("dxwrapperConfig", dxwrapperConfig);
                    data.put("audioDriver", audioDriver);
                    data.put("wincomponents", wincomponents);
                    data.put("drives", drives);
                    data.put("showFPS", showFPS);
                    data.put("inputType", finalInputType);
                    data.put("wow64Mode", wow64Mode);
                    data.put("startupSelection", startupSelection);
                    data.put("box86Preset", box86Preset);
                    data.put("box64Preset", box64Preset);
                    data.put("desktopTheme", desktopTheme);
                    data.put("rcfileId", rcfileId);
                    data.put("midiSoundFont", midiSoundFont);
                    data.put("lc_all", lc_all);
                    data.put("primaryController", primaryController);
                    data.put("controllerMapping", controllerMapping);
                    data.put("wineVersion", sWineVersion.getSelectedItem().toString());
                    data.put("dpi", dpi);

                    preloaderDialog.show(R.string.creating_container);
                    manager.createContainerAsync(data, (container) -> {
                        if (container != null) {
                            this.container = container;
                            saveWineRegistryKeys(view);
                        }
                        preloaderDialog.close();
                        getActivity().onBackPressed();
                    });
                }
            }
            catch (JSONException e) {}
        });
        return view;
    }

    private void saveWineRegistryKeys(View view) {
        File userRegFile = new File(container.getRootDir(), ".wine/user.reg");
        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            Spinner sFont = view.findViewById(R.id.SFont);
            WineUtils.setSystemFont(registryEditor, sFont.getSelectedItem().toString());

            SeekBar sbDPI = view.findViewById(R.id.SBDPI);
            registryEditor.setDwordValue("Control Panel\\Desktop", "LogPixels", sbDPI.getProgress());

            Spinner sMouseWarpOverride = view.findViewById(R.id.SMouseWarpOverride);
            registryEditor.setStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", sMouseWarpOverride.getSelectedItem().toString().toLowerCase(Locale.ENGLISH));

            if (container.getDXWrapper().equals("wined3d")) {
                KeyValueSet config = new KeyValueSet(container.getDXWrapperConfig());
                if (!config.isEmpty()) {
                    registryEditor.setDwordValue("Software\\Wine\\Direct3D", "csmt", Integer.parseInt(config.get("csmt")) != 0 ? 3 : 0);
                    registryEditor.setDwordValue("Software\\Wine\\Direct3D", "VideoPciDeviceID", Integer.parseInt(config.get("deviceID")));
                    registryEditor.setDwordValue("Software\\Wine\\Direct3D", "VideoPciVendorID", Integer.parseInt(config.get("vendorID")));
                    registryEditor.setStringValue("Software\\Wine\\Direct3D", "OffScreenRenderingMode", config.get("OffScreenRenderingMode").toLowerCase(Locale.ENGLISH));
                    registryEditor.setDwordValue("Software\\Wine\\Direct3D", "strict_shader_math", Integer.parseInt(config.get("strict_shader_math")));
                    registryEditor.setStringValue("Software\\Wine\\Direct3D", "VideoMemorySize", config.get("VideoMemorySize"));
                    registryEditor.setStringValue("Software\\Wine\\Direct3D", "renderer", config.get("renderer").toLowerCase(Locale.ENGLISH));
                    registryEditor.setStringValue("Software\\Wine\\Direct3D", "shader_backend", "glsl");
                    registryEditor.setStringValue("Software\\Wine\\Direct3D", "UseGLSL", "enabled");
                }
            } else {
                registryEditor.setStringValue("Software\\Wine\\Direct3D", "renderer", "no3d");
            }
        }
    }

    private void createWineConfigurationTab(View view) {
        Context context = getContext();

        WineThemeManager.ThemeInfo desktopTheme = new WineThemeManager.ThemeInfo(isEditMode() ? container.getDesktopTheme() : WineThemeManager.DEFAULT_DESKTOP_THEME);
        Spinner sDesktopTheme = view.findViewById(R.id.SDesktopTheme);
        sDesktopTheme.setSelection(desktopTheme.theme.ordinal());
        final ImagePickerView ipvDesktopBackgroundImage = view.findViewById(R.id.IPVDesktopBackgroundImage);
        final ColorPickerView cpvDesktopBackgroundColor = view.findViewById(R.id.CPVDesktopBackgroundColor);
        cpvDesktopBackgroundColor.setColor(desktopTheme.backgroundColor);

        Spinner sDesktopBackgroundType = view.findViewById(R.id.SDesktopBackgroundType);
        sDesktopBackgroundType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                WineThemeManager.BackgroundType type = WineThemeManager.BackgroundType.values()[position];
                ipvDesktopBackgroundImage.setVisibility(View.GONE);
                cpvDesktopBackgroundColor.setVisibility(View.GONE);

                if (type == WineThemeManager.BackgroundType.IMAGE) {
                    ipvDesktopBackgroundImage.setVisibility(View.VISIBLE);
                }
                else if (type == WineThemeManager.BackgroundType.COLOR) {
                    cpvDesktopBackgroundColor.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        sDesktopBackgroundType.setSelection(desktopTheme.backgroundType.ordinal());

        File containerDir = isEditMode() ? container.getRootDir() : null;
        File userRegFile = new File(containerDir, ".wine/user.reg");

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            Spinner sFont = view.findViewById(R.id.SFont);
            MSLogFont mSLogFont = new MSLogFont();
            AppUtils.setSpinnerSelectionFromValue(sFont, mSLogFont.fromByteArray(registryEditor.getHexValues("Control Panel\\Desktop\\WindowMetrics", "CaptionFont")).getFaceName());

            TextView tvDPI = view.findViewById(R.id.TVDPI);
            SeekBar sbDPI = view.findViewById(R.id.SBDPI);
            sbDPI.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    tvDPI.setText(progress + " dpi");
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            if (container != null)
                sbDPI.setProgress(container.getDPI());
            else
                sbDPI.setProgress(96);

            List<String> mouseWarpOverrideList = Arrays.asList(context.getString(R.string.disable), context.getString(R.string.enable), context.getString(R.string.force));
            Spinner sMouseWarpOverride = view.findViewById(R.id.SMouseWarpOverride);
            sMouseWarpOverride.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, mouseWarpOverrideList));
            AppUtils.setSpinnerSelectionFromValue(sMouseWarpOverride, registryEditor.getStringValue("Software\\Wine\\DirectInput", "MouseWarpOverride", "disable"));
        }
    }

    public static String getScreenSize(View view) {
        Spinner sScreenSize = view.findViewById(R.id.SScreenSize);
        String value = sScreenSize.getSelectedItem().toString();
        if (value.equalsIgnoreCase("custom")) {
            value = Container.DEFAULT_SCREEN_SIZE;
            String strWidth = ((EditText)view.findViewById(R.id.ETScreenWidth)).getText().toString().trim();
            String strHeight = ((EditText)view.findViewById(R.id.ETScreenHeight)).getText().toString().trim();
            if (strWidth.matches("[0-9]+") && strHeight.matches("[0-9]+")) {
                int width = Integer.parseInt(strWidth);
                int height = Integer.parseInt(strHeight);
                if ((width % 2) == 0 && (height % 2) == 0) return width+"x"+height;
            }
        }
        return StringUtils.parseIdentifier(value);
    }

    private String getDesktopTheme(View view) {
        Spinner sDesktopBackgroundType = view.findViewById(R.id.SDesktopBackgroundType);
        WineThemeManager.BackgroundType type = WineThemeManager.BackgroundType.values()[sDesktopBackgroundType.getSelectedItemPosition()];
        Spinner sDesktopTheme = view.findViewById(R.id.SDesktopTheme);
        ColorPickerView cpvDesktopBackground = view.findViewById(R.id.CPVDesktopBackgroundColor);
        WineThemeManager.Theme theme = WineThemeManager.Theme.values()[sDesktopTheme.getSelectedItemPosition()];

       String desktopTheme = theme+","+type+","+cpvDesktopBackground.getColorAsString();
        if (type == WineThemeManager.BackgroundType.IMAGE) {
            File userWallpaperFile = WineThemeManager.getUserWallpaperFile(getContext());
            desktopTheme += ","+(userWallpaperFile.isFile() ? userWallpaperFile.lastModified() : "0");
        }
        return desktopTheme;
    }

    public static void loadScreenSizeSpinner(View view, String selectedValue) {
        final Spinner sScreenSize = view.findViewById(R.id.SScreenSize);

        final LinearLayout llCustomScreenSize = view.findViewById(R.id.LLCustomScreenSize);
        sScreenSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = sScreenSize.getItemAtPosition(position).toString();
                llCustomScreenSize.setVisibility(value.equalsIgnoreCase("custom") ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        boolean found = AppUtils.setSpinnerSelectionFromIdentifier(sScreenSize, selectedValue);
        if (!found) {
            AppUtils.setSpinnerSelectionFromValue(sScreenSize, "custom");
            String[] screenSize = selectedValue.split("x");
            ((EditText)view.findViewById(R.id.ETScreenWidth)).setText(screenSize[0]);
            ((EditText)view.findViewById(R.id.ETScreenHeight)).setText(screenSize[1]);
        }
    }

    public static void loadGraphicsDriverSpinner(final Spinner sGraphicsDriver, final Spinner sDXWrapper, final View vGraphicsDriverConfig, String selectedGraphicsDriver, String selectedDXWrapper) {
        final Context context = sGraphicsDriver.getContext();
        final String[] dxwrapperEntries = context.getResources().getStringArray(R.array.dxwrapper_entries);

        Runnable update = () -> {
            String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
            boolean addAll = graphicsDriver.startsWith("turnip") || graphicsDriver.startsWith("vortek") || graphicsDriver.startsWith("llvmpipe");

            ArrayList<String> items = new ArrayList<>();
            for (String value : dxwrapperEntries)
                if (addAll || (!value.contains("DXVK")))
                    items.add(value);

            sDXWrapper.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, items.toArray(new String[0])));
            AppUtils.setSpinnerSelectionFromIdentifier(sDXWrapper, selectedDXWrapper);
        };

        sGraphicsDriver.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ///
                String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
                if (graphicsDriver.startsWith("vortek")) {
                    vGraphicsDriverConfig.setOnClickListener((v) -> (new VortekConfigDialog(vGraphicsDriverConfig)).show());
                    vGraphicsDriverConfig.setVisibility(View.VISIBLE);
                } else if (graphicsDriver.startsWith("virgl")) {
                    vGraphicsDriverConfig.setOnClickListener((v) -> (new VirGLConfigDialog(vGraphicsDriverConfig)).show());
                    vGraphicsDriverConfig.setVisibility(View.VISIBLE);
                } else
                    vGraphicsDriverConfig.setVisibility(View.GONE);
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        AppUtils.setSpinnerSelectionFromIdentifier(sGraphicsDriver, selectedGraphicsDriver);
        update.run();
    }

    public static void setupDXWrapperSpinner(final Spinner sDXWrapper, final View vDXWrapperConfig) {
        sDXWrapper.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                if (dxwrapper.contains("dxvk")) {
                    vDXWrapperConfig.setOnClickListener((v) -> (new DXVK_VKD3DConfigDialog(vDXWrapperConfig)).show());
                    vDXWrapperConfig.setVisibility(View.VISIBLE);
                } else if (dxwrapper.contains("wined3d")) {
                    vDXWrapperConfig.setOnClickListener((v) -> (new WineD3DConfigDialog(vDXWrapperConfig)).show());
                    vDXWrapperConfig.setVisibility(View.VISIBLE);
                }
                else vDXWrapperConfig.setVisibility(View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    public static String getWinComponents(View view) {
        ViewGroup parent = view.findViewById(R.id.LLTabWinComponents);
        ArrayList<View> views = new ArrayList<>();
        AppUtils.findViewsWithClass(parent, Spinner.class, views);
        String[] wincomponents = new String[views.size()];

        for (int i = 0; i < views.size(); i++) {
            Spinner spinner = (Spinner)views.get(i);
            wincomponents[i] = spinner.getTag()+"="+spinner.getSelectedItemPosition();
        }
        return String.join(",", wincomponents);
    }

    public static void createWinComponentsTab(View view, String wincomponents) {
        Context context = view.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        ViewGroup tabView = view.findViewById(R.id.LLTabWinComponents);
        ViewGroup directxSectionView = tabView.findViewById(R.id.LLWinComponentsDirectX);
        ViewGroup generalSectionView = tabView.findViewById(R.id.LLWinComponentsGeneral);

        for (String[] wincomponent : new KeyValueSet(wincomponents)) {
            ViewGroup parent = wincomponent[0].startsWith("direct") ? directxSectionView : generalSectionView;
            View itemView = inflater.inflate(R.layout.wincomponent_list_item, parent, false);
            ((TextView)itemView.findViewById(R.id.TextView)).setText(StringUtils.getString(context, wincomponent[0]));
            Spinner spinner = itemView.findViewById(R.id.Spinner);
            spinner.setSelection(Integer.parseInt(wincomponent[1]), false);
            spinner.setTag(wincomponent[0]);
            parent.addView(itemView);
        }
    }

    private EnvVarsView createEnvVarsTab(final View view) {
        final Context context = view.getContext();
        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);
        envVarsView.setEnvVars(new EnvVars(isEditMode() ? container.getEnvVars() : Container.DEFAULT_ENV_VARS));
        view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) -> (new AddEnvVarDialog(context, envVarsView)).show());
        return envVarsView;
    }

    private String getDrives(View view) {
        LinearLayout parent = view.findViewById(R.id.LLDrives);
        String drives = "";

        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            Spinner spinner = child.findViewById(R.id.Spinner);
            EditText editText = child.findViewById(R.id.EditText);
            String path = editText.getText().toString().trim();
            if (!path.isEmpty()) drives += spinner.getSelectedItem()+path;
        }
        return drives;
    }

    private void createDrivesTab(View view) {
        final Context context = getContext();

        final LinearLayout parent = view.findViewById(R.id.LLDrives);
        final View emptyTextView = view.findViewById(R.id.TVDrivesEmptyText);
        LayoutInflater inflater = LayoutInflater.from(context);
        final String drives = isEditMode() ? container.getDrives() : Container.DEFAULT_DRIVES;
        final String[] driveLetters = new String[Container.MAX_DRIVE_LETTERS];
        for (int i = 0; i < driveLetters.length; i++) driveLetters[i] = ((char)(i + 68))+":";

        Callback<String[]> addItem = (drive) -> {
            final View itemView = inflater.inflate(R.layout.drive_list_item, parent, false);
            Spinner spinner = itemView.findViewById(R.id.Spinner);
            spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, driveLetters));
            AppUtils.setSpinnerSelectionFromValue(spinner, drive[0]+":");

            final EditText editText = itemView.findViewById(R.id.EditText);
            editText.setText(drive[1]);

            itemView.findViewById(R.id.BTSearch).setOnClickListener((v) -> {
                openDirectoryCallback = (path) -> {
                    drive[1] = path;
                    editText.setText(path);
                };
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.fromFile(Environment.getExternalStorageDirectory()));
                getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_DIRECTORY_REQUEST_CODE);
            });

            itemView.findViewById(R.id.BTRemove).setOnClickListener((v) -> {
                parent.removeView(itemView);
                if (parent.getChildCount() == 0) emptyTextView.setVisibility(View.VISIBLE);
            });
            parent.addView(itemView);
        };
        for (String[] drive : Container.drivesIterator(drives)) addItem.call(drive);

        view.findViewById(R.id.BTAddDrive).setOnClickListener((v) -> {
            if (parent.getChildCount() >= Container.MAX_DRIVE_LETTERS) return;
            final String nextDriveLetter = String.valueOf(driveLetters[parent.getChildCount()].charAt(0));
            addItem.call(new String[]{nextDriveLetter, ""});
        });

        if (drives.isEmpty()) emptyTextView.setVisibility(View.VISIBLE);
    }

    private void loadWineVersionSpinner(final View view, Spinner sWineVersion, final ArrayList<WineInfo> wineInfos) {
        final Context context = getContext();
        sWineVersion.setEnabled(!isEditMode());
//        sWineVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
//            @Override
//            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
//                WineInfo wineInfo = wineInfos.get(position);
//                boolean isMainWineVersion = WineInfo.isMainWineVersion(wineInfo.identifier());
//                CheckBox cbWoW64Mode = view.findViewById(R.id.CBWoW64Mode);
//                cbWoW64Mode.setEnabled(isMainWineVersion);
//                if (!isMainWineVersion) cbWoW64Mode.setChecked(false);
//            }
//
//            @Override
//            public void onNothingSelected(AdapterView<?> parent) {}
//        });
//        view.findViewById(R.id.LLWineVersion).setVisibility(View.VISIBLE);
//        sWineVersion.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, wineInfos));
//        if (isEditMode()) AppUtils.setSpinnerSelectionFromValue(sWineVersion, WineInfo.fromIdentifier(context, container.getWineVersion()).toString());
        view.findViewById(R.id.LLWineVersion).setVisibility(View.VISIBLE);
        ArrayList<String> wineVersions = new ArrayList<>();
        wineVersions.add(WineInfo.MAIN_WINE_VERSION.identifier());
        for (ContentProfile profile : contentsManager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE))
            wineVersions.add(ContentsManager.getEntryName(profile));
        sWineVersion.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, wineVersions));
        if (isEditMode()) AppUtils.setSpinnerSelectionFromValue(sWineVersion, container.getWineVersion());
    }

    public static void updateGraphicsDriverSpinner(Context context, ContentsManager manager, Spinner spinner) {
        String[] originalItems = context.getResources().getStringArray(R.array.graphics_driver_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));
        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_TURNIP))
            itemList.add(ContentsManager.getEntryName(profile));
        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VIRGL))
            itemList.add(ContentsManager.getEntryName(profile));
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
    }

    public String getControllerMapping(View view) {
        //The order has to be the same like Container.XrControllerMapping
        int[] ids = {
                R.id.SButtonA, R.id.SButtonB, R.id.SButtonX, R.id.SButtonY, R.id.SButtonGrip, R.id.SButtonTrigger,
                R.id.SThumbstickUp, R.id.SThumbstickDown, R.id.SThumbstickLeft, R.id.SThumbstickRight
        };
        byte[] controllerMapping = new byte[ids.length];
        for (int i = 0; i < ids.length; i++) {
            int index =  ((Spinner)view.findViewById(ids[i])).getSelectedItemPosition();
            byte value = XKeycode.values()[index].id;
            controllerMapping[i] = value;
        }
        return new String(controllerMapping);
    }

    public void setControllerMapping(Spinner spinner, Container.XrControllerMapping mapping, int defaultValue) {
        XKeycode[] values = XKeycode.values();
        ArrayList<String> array = new ArrayList<>();
        for (XKeycode value : values) {
            array.add(value.name());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(spinner.getContext(), android.R.layout.simple_spinner_dropdown_item, array);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        byte keycode = isEditMode() ? container.getControllerMapping(mapping) : (byte) defaultValue;
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].id == keycode) {
                index = i;
                break;
            }
        }
        spinner.setSelection(isEditMode() && (index != 0) ? index : defaultValue);
    }
}
