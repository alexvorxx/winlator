package com.winlator.contentdialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.contents.ContentsManager;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;
import com.winlator.core.UnitUtils;

public class FreedrenoConfigDialog extends ContentDialog {
    private static final String[] FD_MESA_DEBUG_OPTIONS = {"hiprio", "gmem", "flush", "sysmem", "lrz",
            "nofp16", "noindirect", "noblit", "nobin", "forcebin", "layout", "noubwc", "nogrow", "stomp",
            "nolrz", "bstat", "inorder", "nolrzfc", "shaderdb", "serialc", "ttile", "notile", "nohw",
            "perfcntrs", "perf", "direct", "noscis", "ddraw", "dclear", "disasm", "nosbin", "msgs", "abort"};
    private final Context context;

    public FreedrenoConfigDialog(View anchor) {
        super(anchor.getContext(), R.layout.freedreno_config_dialog);
        context = anchor.getContext();
        setIcon(R.drawable.icon_settings);
        setTitle("Freedreno "+context.getString(R.string.configuration));

        final CheckBox cbVsync = findViewById(R.id.CBVsync);

        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();

        KeyValueSet config = parseConfig(context, anchor.getTag());
        cbVsync.setChecked(config.getBoolean("vblank_mode", false));
        loadDebugOptions(config.get("fdMesaDebug", "hiprio"));

        setOnConfirmCallback(() -> {
            config.put("vblank_mode", cbVsync.isChecked());
            config.put("fdMesaDebug", getDebugOptions());
            anchor.setTag(config.toString());
        });
    }

    private void loadDebugOptions(String options) {
        LinearLayout container = findViewById(R.id.LLOptions);
        container.removeAllViews();
        Context context = container.getContext();

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, (int) UnitUtils.dpToPx(4), 0);
        for (String opt : FD_MESA_DEBUG_OPTIONS) {
            CheckBox checkBox = new CheckBox(context);
            checkBox.setText(opt);
            checkBox.setLayoutParams(params);
            checkBox.setChecked(options.contains(opt));
            container.addView(checkBox);
        }
    }

    private static String getDefaultConfig(Context context) {
        return "fdMesaDebug=hiprio,vblank_mode=0";
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

    public static KeyValueSet parseConfig(Context context, Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : getDefaultConfig(context);
        return new KeyValueSet(data);
    }

    public static void setEnvVars(KeyValueSet config, EnvVars envVars) {
        String options = config.get("fdMesaDebug", "hiprio");
        if (!options.isEmpty()) envVars.put("FD_MESA_DEBUG", options.replace("|", ","));
        envVars.put("vblank_mode", config.getBoolean("vblank_mode", false) ? "1" : "0");
    }
}
