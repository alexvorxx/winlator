package com.winlator.contentdialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;
import com.winlator.core.UnitUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TurnipConfigDialog extends ContentDialog {
    private static final String[] TU_DEBUG_OPTIONS = {"noconform", "syncdraw", "flushall", "sysmem",
            "gmem", "startup", "nir", "nobin", "forcebin", "layout", "noubwc", "nomultipos",
            "nolrz", "nolrzfc", "perf", "perfc", "push_consts_per_stage", "rast_order",
            "unaligned_store", "log_skip_gmem_ops", "dynamic", "bos", "3d_load", "fdm", "rd"};
    private final Context context;

    public TurnipConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.turnip_config_dialog);
        context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("Turnip "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        final Spinner sPresentMode = findViewById(R.id.SPresentMode);

        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        loadTurnipVersionSpinner(contentsManager, sVersion);

        KeyValueSet config = parseConfig(context, anchor.getTag());
        AppUtils.setSpinnerSelectionFromIdentifier(sVersion, config.get("version", DefaultVersion.TURNIP));
        AppUtils.setSpinnerSelectionFromNumber(sMaxDeviceMemory, config.get("maxTurnipMemory", "4096"));
        AppUtils.setSpinnerSelectionFromIdentifier(sPresentMode, config.get("presentMode", "mailbox"));
        loadDebugOptions(config.get("tuDebug", "noconform"));

        setOnConfirmCallback(() -> {
            config.put("version", sVersion.getSelectedItem().toString());
            config.put("maxTurnipMemory", StringUtils.parseNumber(sMaxDeviceMemory.getSelectedItem()));
            config.put("presentMode", sPresentMode.getSelectedItem().toString());
            config.put("tuDebug", getDebugOptions());
            anchor.setTag(config.toString());
        });
    }

    private static String getDefaultConfig(Context context) {
        return "version="+DefaultVersion.TURNIP+",tuDebug=noconform,maxTurnipMemory=4096,presentMode=mailbox";
    }

    private String getDebugOptions() {
        String options = "";
        LinearLayout container = findViewById(R.id.LLOptions);

        for (int i = 0; i < container.getChildCount(); i++) {
            CheckBox checkBox = (CheckBox)container.getChildAt(i);
            if (checkBox.isChecked()) options += (!options.isEmpty() ? "|" : "")+checkBox.getText();
        }

        return options;
    }

    private void loadDebugOptions(String options) {
        LinearLayout container = findViewById(R.id.LLOptions);
        container.removeAllViews();
        Context context = container.getContext();

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, (int)UnitUtils.dpToPx(4), 0);
        for (String opt : TU_DEBUG_OPTIONS) {
            CheckBox checkBox = new CheckBox(context);
            checkBox.setText(opt);
            checkBox.setLayoutParams(params);
            checkBox.setChecked(options.contains(opt));
            container.addView(checkBox);
        }
    }

    public static KeyValueSet parseConfig(Context context, Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : getDefaultConfig(context);
        return new KeyValueSet(data);
    }

    public static void setEnvVars(KeyValueSet config, EnvVars envVars) {
        String options = config.get("tuDebug");
        if (!options.isEmpty()) envVars.put("TU_DEBUG", options.replace("|", ","));
        envVars.put("TU_OVERRIDE_HEAP_SIZE", config.get("maxTurnipMemory"));
        envVars.put("MESA_VK_WSI_PRESENT_MODE", config.get("presentMode"));
    }

    private void loadTurnipVersionSpinner(ContentsManager manager, Spinner spinner) {
        String[] originalItems = context.getResources().getStringArray(R.array.turnip_version_entries);
        List<String> itemList = new ArrayList<>(Arrays.asList(originalItems));

        for (ContentProfile profile : manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_TURNIP)) {
            String entryName = ContentsManager.getEntryName(profile);
            int firstDashIndex = entryName.indexOf('-');
            itemList.add(entryName.substring(firstDashIndex + 1));
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, itemList));
    }
}