package com.winlator.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.ToggleButton;

import com.winlator.R;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DXVK_VKD3DConfigDialog extends ContentDialog {
    public static final String DEFAULT_CONFIG = "dxvk_version=" + DefaultVersion.DXVK +
            ",framerate=0,maxDeviceMemory=0,async=1,asyncCache=0" +
            ",vkd3dVersion=" + DefaultVersion.VKD3D + ",vkd3dLevel=12_1" +
            ",wined3d_version=" + DefaultVersion.WINED3D + ",csmt=3" +
            ",OffScreenRenderingMode=fbo,strict_shader_math=1,VideoMemorySize=2048" +
            ",renderer=gl,deviceID=266,vendorID=32902,enableDgVoodooDDraw=1,enableDgVoodooD3D89=0";
    public static final int DXVK_TYPE_NONE = 0;
    public static final int DXVK_TYPE_ASYNC = 1;
    public static final int DXVK_TYPE_GPLASYNC = 2;
    private final ToggleButton swAsync;
    private final ToggleButton swAsyncCache;
    private final View llAsync;
    private final View llAsyncCache;
    private final Context context;
    private List<String> dxvkVersions;
    private final ToggleButton enableDgVoodooDDraw;
    private final ToggleButton enableDgVoodooD3D89;

    public static final String[] VKD3D_FEATURE_LEVEL = {"12_0", "12_1", "12_2", "11_1", "11_0", "10_1", "10_0", "9_3", "9_2", "9_1"};

    public DXVK_VKD3DConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.dxvk_vkd3d_config_dialog);
        context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("DXVK+VKD3D+dgVoodoo "+context.getString(R.string.configuration));

        final Spinner sDXVKVersion = findViewById(R.id.SDXVKVersion);
        final Spinner sFramerate = findViewById(R.id.SFramerate);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        final Spinner sVKD3DVersion = findViewById(R.id.SVKD3DVersion);
        final Spinner sFeatureLevel = findViewById(R.id.SFeatureLevel);
        swAsync = findViewById(R.id.SWAsync);
        swAsyncCache = findViewById(R.id.SWAsyncCache);
        llAsync = findViewById(R.id.LLAsync);
        llAsyncCache = findViewById(R.id.LLAsyncCache);
        enableDgVoodooDDraw = findViewById(R.id.enableDgVoodooDDraw);
        enableDgVoodooD3D89 = findViewById(R.id.enableDgVoodooD3D89);

        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        loadDxvkVersionSpinner(contentsManager,sDXVKVersion);
        loadVkd3dVersionSpinner(contentsManager, sVKD3DVersion);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, VKD3D_FEATURE_LEVEL);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sFeatureLevel.setAdapter(adapter);

        KeyValueSet config = parseConfig(anchor.getTag());
        AppUtils.setSpinnerSelectionFromIdentifier(sDXVKVersion, config.get("dxvk_version"));
        AppUtils.setSpinnerSelectionFromIdentifier(sFramerate, config.get("framerate"));
        AppUtils.setSpinnerSelectionFromNumber(sMaxDeviceMemory, config.get("maxDeviceMemory"));
        swAsync.setChecked(config.get("async").equals("1"));
        swAsyncCache.setChecked(config.get("asyncCache").equals("1"));
        AppUtils.setSpinnerSelectionFromIdentifier(sVKD3DVersion, config.get("vkd3dVersion"));
        AppUtils.setSpinnerSelectionFromIdentifier(sFeatureLevel, config.get("vkd3dLevel"));
        enableDgVoodooDDraw.setChecked(config.get("enableDgVoodooDDraw").equals("1"));
        enableDgVoodooD3D89.setChecked(config.get("enableDgVoodooD3D89").equals("1"));

        updateConfigVisibility(getDXVKType(sDXVKVersion.getSelectedItemPosition()));

        sDXVKVersion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateConfigVisibility(getDXVKType(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        setOnConfirmCallback(() -> {
            config.put("dxvk_version", sDXVKVersion.getSelectedItem().toString());
            config.put("framerate", StringUtils.parseNumber(sFramerate.getSelectedItem()));
            config.put("maxDeviceMemory", StringUtils.parseNumber(sMaxDeviceMemory.getSelectedItem()));
            config.put("async", ((swAsync.isChecked())&&(llAsync.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("asyncCache", ((swAsyncCache.isChecked())&&(llAsyncCache.getVisibility()==View.VISIBLE))?"1":"0");
            config.put("vkd3dVersion", sVKD3DVersion.getSelectedItem().toString());
            config.put("vkd3dLevel", sFeatureLevel.getSelectedItem().toString());
            config.put("enableDgVoodooDDraw", enableDgVoodooDDraw.isChecked() ? "1" : "0");
            config.put("enableDgVoodooD3D89", enableDgVoodooD3D89.isChecked() ? "1" : "0");
            anchor.setTag(config.toString());
        });
    }

    private void updateConfigVisibility(int dxvkType) {
        if (dxvkType == DXVK_TYPE_ASYNC) {
            llAsync.setVisibility(View.VISIBLE);
            llAsyncCache.setVisibility(View.GONE);
        } else if (dxvkType == DXVK_TYPE_GPLASYNC) {
            llAsync.setVisibility(View.VISIBLE);
            llAsyncCache.setVisibility(View.VISIBLE);
        } else {
            llAsync.setVisibility(View.GONE);
            llAsyncCache.setVisibility(View.GONE);
        }
    }

    private int getDXVKType(int pos) {
        final String v = dxvkVersions.get(pos);
        int dxvkType = DXVK_TYPE_NONE;
        if (v.contains("gplasync"))
            dxvkType = DXVK_TYPE_GPLASYNC;
        else if (v.contains("async"))
            dxvkType = DXVK_TYPE_ASYNC;
        return dxvkType;
    }

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        envVars.put("DXVK_STATE_CACHE_PATH", "/data/data/com.winlator/files/imagefs"+ImageFs.CACHE_PATH);
        envVars.put("DXVK_LOG_LEVEL", "none");

        File rootDir = ImageFs.find(context).getRootDir();
        File dxvkConfigFile = new File(rootDir, ImageFs.CONFIG_PATH+"/dxvk.conf");

        String content = "\"";
        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            content += "dxgi.maxDeviceMemory = "+maxDeviceMemory+';';
            content += "dxgi.maxSharedMemory = "+maxDeviceMemory+';';
        }

        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
//            content += "dxgi.maxFrameRate = "+framerate+';';
//            content += "d3d9.maxFrameRate = "+framerate+';';
            envVars.put("DXVK_FRAME_RATE", framerate);
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
//            content += "dxvk.enableAsync = True;";
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
//            content += "dxvk.gplAsyncCache = True;";
            envVars.put("DXVK_GPLASYNCCACHE", "1");
        content = content + '\"';

//        FileUtils.delete(dxvkConfigFile);
//        if (!content.isEmpty() && FileUtils.writeString(dxvkConfigFile, content)) {
//            envVars.put("DXVK_CONFIG_FILE", rootDir + ImageFs.CONFIG_PATH+"/dxvk.conf");
//        }
        envVars.put("DXVK_CONFIG_FILE", rootDir + ImageFs.CONFIG_PATH+"/dxvk.conf");
        envVars.put("DXVK_CONFIG", content);

        envVars.put("VKD3D_FEATURE_LEVEL", config.get("vkd3dLevel"));
    }

    private void loadDxvkVersionSpinner(ContentsManager manager, Spinner spinner) {
        String[] originalItems = context.getResources().getStringArray(R.array.dxvk_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));

        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            itemList.add(entryName.substring(firstDashIndex + 1));
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
        dxvkVersions = itemList;
    }

    private void loadVkd3dVersionSpinner(ContentsManager manager, Spinner spinner) {
        String[] originalItems = context.getResources().getStringArray(R.array.vkd3d_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));

        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            itemList.add(entryName.substring(firstDashIndex + 1));
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
    }
}
