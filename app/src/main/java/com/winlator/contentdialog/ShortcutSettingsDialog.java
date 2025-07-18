package com.winlator.contentdialog;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.view.Menu;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;

import com.winlator.ContainerDetailFragment;
import com.winlator.R;
import com.winlator.ShortcutsFragment;
import com.winlator.box86_64.Box86_64PresetManager;
import com.winlator.box86_64.rc.RCManager;
import com.winlator.container.Shortcut;
import com.winlator.contents.ContentsManager;
import com.winlator.core.AppUtils;
import com.winlator.core.EnvVars;
import com.winlator.core.StringUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.midi.MidiManager;
import com.winlator.widget.EnvVarsView;
import com.winlator.winhandler.WinHandler;

import java.io.File;
import java.util.ArrayList;

public class ShortcutSettingsDialog extends ContentDialog {
    private final ShortcutsFragment fragment;
    private final Shortcut shortcut;
    private InputControlsManager inputControlsManager;

    public ShortcutSettingsDialog(ShortcutsFragment fragment, Shortcut shortcut) {
        super(fragment.getContext(), R.layout.shortcut_settings_dialog);
        this.fragment = fragment;
        this.shortcut = shortcut;
        setTitle(shortcut.name);
        setIcon(R.drawable.icon_settings);

        createContentView();
    }

    private void createContentView() {
        final Context context = fragment.getContext();
        inputControlsManager = new InputControlsManager(context);
        LinearLayout llContent = findViewById(R.id.LLContent);
        llContent.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);

        final EditText etName = findViewById(R.id.ETName);
        etName.setText(shortcut.name);

        final EditText etExecArgs = findViewById(R.id.ETExecArgs);
        etExecArgs.setText(shortcut.getExtra("execArgs"));

        ContainerDetailFragment.loadScreenSizeSpinner(getContentView(), shortcut.getExtra("screenSize", shortcut.container.getScreenSize()));

        final Spinner sGraphicsDriver = findViewById(R.id.SGraphicsDriver);
        final Spinner sDXWrapper = findViewById(R.id.SDXWrapper);

        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        ContainerDetailFragment.updateGraphicsDriverSpinner(context, contentsManager, sGraphicsDriver);

        final View vDXWrapperConfig = findViewById(R.id.BTDXWrapperConfig);
        final View vGraphicsDriverConfig = findViewById(R.id.GraphicsDriverConfig1);

        vDXWrapperConfig.setTag(shortcut.getExtra("dxwrapperConfig", shortcut.container.getDXWrapperConfig()));

        vGraphicsDriverConfig.setTag(shortcut.getExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig()));

        findViewById(R.id.BTHelpGraphicsDriver).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.graphics_driver_help_content));

        ContainerDetailFragment.setupDXWrapperSpinner(sDXWrapper, vDXWrapperConfig);
        ContainerDetailFragment.loadGraphicsDriverSpinner(sGraphicsDriver, sDXWrapper, vGraphicsDriverConfig, shortcut.getExtra("graphicsDriver", shortcut.container.getGraphicsDriver()),
            shortcut.getExtra("dxwrapper", shortcut.container.getDXWrapper()));

        findViewById(R.id.BTHelpDXWrapper).setOnClickListener((v) -> AppUtils.showHelpBox(context, v, R.string.dxwrapper_help_content));

        final Spinner sAudioDriver = findViewById(R.id.SAudioDriver);
        AppUtils.setSpinnerSelectionFromIdentifier(sAudioDriver, shortcut.getExtra("audioDriver", shortcut.container.getAudioDriver()));

        final Spinner sMIDISoundFont = findViewById(R.id.SMIDISoundFont);
        MidiManager.loadSFSpinner(sMIDISoundFont);
        AppUtils.setSpinnerSelectionFromValue(sMIDISoundFont, shortcut.getExtra("midiSoundFont", shortcut.container.getMIDISoundFont()));

        final Runnable showInputWarning = () -> ContentDialog.alert(context, R.string.enable_xinput_and_dinput_same_time, null);
        final CheckBox cbEnableXInput = findViewById(R.id.CBEnableXInput);
        final CheckBox cbEnableDInput = findViewById(R.id.CBEnableDInput);
        final View llDInputType = findViewById(R.id.LLDinputMapperType);
        final View btHelpXInput = findViewById(R.id.BTXInputHelp);
        final View btHelpDInput = findViewById(R.id.BTDInputHelp);
        Spinner SDInputType = findViewById(R.id.SDInputType);
        final int inputType = Integer.parseInt(shortcut.getExtra("inputType", String.valueOf(shortcut.container.getInputType())));
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

        final EditText etLC_ALL = findViewById(R.id.ETlcall);
        etLC_ALL.setText(shortcut.getExtra("lc_all", shortcut.container.getLC_ALL()));

        final View btShowLCALL = findViewById(R.id.BTShowLCALL);
        btShowLCALL.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            String[] lcs = context.getResources().getStringArray(R.array.some_lc_all);
            for (int i = 0; i < lcs.length; i++)
                popupMenu.getMenu().add(Menu.NONE, i, Menu.NONE, lcs[i]);
            popupMenu.setOnMenuItemClickListener(item -> {
                etLC_ALL.setText(item.toString() + ".UTF-8");
                return true;
            });
            popupMenu.show();
        });

        final CheckBox cbForceFullscreen = findViewById(R.id.CBForceFullscreen);
        cbForceFullscreen.setChecked(shortcut.getExtra("forceFullscreen", "0").equals("1"));

        final CheckBox cbWoW64Mode = findViewById(R.id.CBWoW64Mode);
        cbWoW64Mode.setChecked(shortcut.getExtra("WoW64Mode", "1").equals("1"));

        final View btHelpWoW64Mode = findViewById(R.id.WoW64ModeHelp);
        btHelpWoW64Mode.setOnClickListener(v -> AppUtils.showHelpBox(context, v, R.string.help_wow64_mode));

        final Spinner sBox86Preset = findViewById(R.id.SBox86Preset);
        Box86_64PresetManager.loadSpinner("box86", sBox86Preset, shortcut.getExtra("box86Preset", shortcut.container.getBox86Preset()));

        final Spinner sBox64Preset = findViewById(R.id.SBox64Preset);
        Box86_64PresetManager.loadSpinner("box64", sBox64Preset, shortcut.getExtra("box64Preset", shortcut.container.getBox64Preset()));

        final Spinner sRCFile = findViewById(R.id.SRCFile);
        final int[] rcfileIds = {0};
        RCManager manager = new RCManager(context);
        String rcfileId = shortcut.getExtra("rcfileId", String.valueOf(shortcut.container.getRCFileId()));
        RCManager.loadRCFileSpinner(manager, Integer.parseInt(rcfileId), sRCFile, id -> {
            rcfileIds[0] = id;
        });

        final Spinner sControlsProfile = findViewById(R.id.SControlsProfile);
        loadControlsProfileSpinner(sControlsProfile, shortcut.getExtra("controlsProfile", "0"));

        final CheckBox cbSimulateTouchScreen = findViewById(R.id.CBSimulateTouchScreen);
        cbSimulateTouchScreen.setChecked(shortcut.getExtra("simTouchScreen", "0").equals("1"));

        ContainerDetailFragment.createWinComponentsTab(getContentView(), shortcut.getExtra("wincomponents", shortcut.container.getWinComponents()));
        final EnvVarsView envVarsView = createEnvVarsTab();

        AppUtils.setupTabLayout(getContentView(), R.id.TabLayout, R.id.LLTabWinComponents, R.id.LLTabEnvVars, R.id.LLTabAdvanced);

        findViewById(R.id.BTExtraArgsMenu).setOnClickListener((v) -> {
            PopupMenu popupMenu = new PopupMenu(context, v);
            popupMenu.inflate(R.menu.extra_args_popup_menu);
            popupMenu.setOnMenuItemClickListener((menuItem) -> {
                String value = String.valueOf(menuItem.getTitle());
                String execArgs = etExecArgs.getText().toString();
                if (!execArgs.contains(value)) etExecArgs.setText(!execArgs.isEmpty() ? execArgs+" "+value : value);
                return true;
            });
            popupMenu.show();
        });

        setOnConfirmCallback(() -> {
            String name = etName.getText().toString().trim();
            if (!shortcut.name.equals(name) && !name.isEmpty()) {
                renameShortcut(name);
            }
            else {
                String graphicsDriver = StringUtils.parseIdentifier(sGraphicsDriver.getSelectedItem());
                String graphicsDriverConfig = vGraphicsDriverConfig.getTag().toString(); ///
                String dxwrapper = StringUtils.parseIdentifier(sDXWrapper.getSelectedItem());
                String dxwrapperConfig = vDXWrapperConfig.getTag().toString();
                String audioDriver = StringUtils.parseIdentifier(sAudioDriver.getSelectedItem());
                String midiSoundFont = sMIDISoundFont.getSelectedItemPosition() == 0 ? "" : sMIDISoundFont.getSelectedItem().toString();
                String lc_all = etLC_ALL.getText().toString();
                String screenSize = ContainerDetailFragment.getScreenSize(getContentView());

                int finalInputType = 0;
                finalInputType |= cbEnableXInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_XINPUT : 0;
                finalInputType |= cbEnableDInput.isChecked() ? WinHandler.FLAG_INPUT_TYPE_DINPUT : 0;
                finalInputType |= SDInputType.getSelectedItemPosition() == 0 ?  WinHandler.FLAG_DINPUT_MAPPER_STANDARD : WinHandler.FLAG_DINPUT_MAPPER_XINPUT;

                String execArgs = etExecArgs.getText().toString();
                shortcut.putExtra("execArgs", !execArgs.isEmpty() ? execArgs : null);

                updateExtra("screenSize", shortcut.container.getScreenSize(), screenSize);
                updateExtra("graphicsDriver", shortcut.container.getGraphicsDriver(), graphicsDriver);
                updateExtra("graphicsDriverConfig", shortcut.container.getGraphicsDriverConfig(), graphicsDriverConfig); ///
                updateExtra("dxwrapper", shortcut.container.getDXWrapper(), dxwrapper);
                updateExtra("dxwrapperConfig", shortcut.container.getDXWrapperConfig(), dxwrapperConfig);
                updateExtra("audioDriver", shortcut.container.getAudioDriver(), audioDriver);
                updateExtra("midiSoundFont", shortcut.container.getMIDISoundFont(), midiSoundFont);
                updateExtra("lc_all", shortcut.container.getLC_ALL(), lc_all);
                shortcut.putExtra("forceFullscreen", cbForceFullscreen.isChecked() ? "1" : null);

                shortcut.putExtra("WoW64Mode", cbWoW64Mode.isChecked() ? "1" : "0");

                String wincomponents = ContainerDetailFragment.getWinComponents(getContentView());
                updateExtra("wincomponents", shortcut.container.getWinComponents(), wincomponents);

                String envVars = envVarsView.getEnvVars();
                shortcut.putExtra("envVars", !envVars.isEmpty() ? envVars : null);

                String box86Preset = Box86_64PresetManager.getSpinnerSelectedId(sBox86Preset);
                String box64Preset = Box86_64PresetManager.getSpinnerSelectedId(sBox64Preset);
                updateExtra("box86Preset", shortcut.container.getBox86Preset(), box86Preset);
                updateExtra("box64Preset", shortcut.container.getBox64Preset(), box64Preset);

                updateExtra("rcfileId", String.valueOf(shortcut.container.getRCFileId()), String.valueOf(rcfileIds[0]));

                ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
                int controlsProfile = sControlsProfile.getSelectedItemPosition() > 0 ? profiles.get(sControlsProfile.getSelectedItemPosition()-1).id : 0;
                shortcut.putExtra("controlsProfile", controlsProfile > 0 ? String.valueOf(controlsProfile) : null);
                shortcut.putExtra("simTouchScreen", cbSimulateTouchScreen.isChecked() ? "1" : "0");
                shortcut.putExtra("inputType", String.valueOf(finalInputType));
                shortcut.saveData();
            }
        });
    }

    private void updateExtra(String extraName, String containerValue, String newValue) {
        String extraValue = shortcut.getExtra(extraName);
        if (extraValue.isEmpty() && containerValue.equals(newValue))
            return;
        shortcut.putExtra(extraName, newValue);
    }

    private void renameShortcut(String newName) {
        File parent = shortcut.file.getParentFile();
        File newDesktopFile = new File(parent, newName+".desktop");
        if (!newDesktopFile.isFile()) shortcut.file.renameTo(newDesktopFile);

        File linkFile = new File(parent, shortcut.name+".lnk");
        if (linkFile.isFile()) {
            File newLinkFile = new File(parent, newName+".lnk");
            if (!newLinkFile.isFile()) linkFile.renameTo(newLinkFile);
        }
        fragment.loadShortcutsList();
        fragment.updateShortcutOnScreen(newName, newName, shortcut.container.id, newDesktopFile.getAbsolutePath(),
                Icon.createWithBitmap(shortcut.icon), shortcut.getExtra("uuid"));
    }

    private EnvVarsView createEnvVarsTab() {
        final View view = getContentView();
        final Context context = view.getContext();
        final EnvVarsView envVarsView = view.findViewById(R.id.EnvVarsView);
        envVarsView.setEnvVars(new EnvVars(shortcut.getExtra("envVars")));
        view.findViewById(R.id.BTAddEnvVar).setOnClickListener((v) -> (new AddEnvVarDialog(context, envVarsView)).show());
        return envVarsView;
    }

    private void loadControlsProfileSpinner(Spinner spinner, String selectedValue) {
        final Context context = fragment.getContext();
        final ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
        ArrayList<String> values = new ArrayList<>();
        values.add(context.getString(R.string.none));

        int selectedPosition = 0;
        int selectedId = Integer.parseInt(selectedValue);
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile profile = profiles.get(i);
            if (profile.id == selectedId) selectedPosition = i + 1;
            values.add(profile.getName());
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selectedPosition, false);
    }
}
