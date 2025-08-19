package com.winlator.contentdialog;

/* Decompiled from Winlator 10 Final
 * https://github.com/brunodev85/winlator/releases/tag/v10.0.0
 */

import android.content.Context;
import android.view.View;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.GPUHelper;
import com.winlator.core.KeyValueSet;
import com.winlator.widget.MultiSelectionComboBox;
import com.winlator.xenvironment.components.VortekRendererComponent;

public class VortekConfigDialog extends ContentDialog {
  public static final String DEFAULT_VK_MAX_VERSION;

  static {
    StringBuilder stringBuilder = new StringBuilder();
    int i = VortekRendererComponent.VK_MAX_VERSION;
    stringBuilder.append(GPUHelper.vkVersionMajor(i));
    stringBuilder.append(".");
    stringBuilder.append(GPUHelper.vkVersionMinor(i));
    DEFAULT_VK_MAX_VERSION = stringBuilder.toString();
  }

  public static final String DEFAULT_CONFIG = "vkMaxVersion=" + DEFAULT_VK_MAX_VERSION +
          ",maxDeviceMemory=512,imageCacheSize=256,resourceMemoryType=0,renderVersion=0";

  private final Context context;

  public VortekConfigDialog(View anchor) {
    super(anchor.getContext(), R.layout.vortek_config_dialog);
    context = anchor.getContext();
    setIcon(R.drawable.icon_settings);
    setTitle("Vortek " + context.getString(R.string.configuration));

    final Spinner SVulkanVersion = findViewById(R.id.SVulkanVersion);
    final Spinner SMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
    final Spinner SImageCacheSize = findViewById(R.id.SImageCacheSize);
    final Spinner SResourceMemoryType = findViewById(R.id.SResourceMemoryType);
    final Spinner SRenderVersion = findViewById(R.id.SRenderVersion);
    MultiSelectionComboBox multiSelectionComboBox = findViewById(R.id.CBExposedExtensions);

    String[] arrayOfString = GPUHelper.vkGetDeviceExtensions();
    ///multiSelectionComboBox.setPopupWindowWidth(360);
    ///multiSelectionComboBox.setDisplayText(context.getString(2131755268));
    multiSelectionComboBox.setItems(arrayOfString);

    KeyValueSet config = parseConfig(anchor.getTag());
    String str = config.get("exposedDeviceExtensions", "all");
    if (str.equals("all")) {
      multiSelectionComboBox.setSelectedItems(arrayOfString);
    } else if (!str.isEmpty()) {
      multiSelectionComboBox.setSelectedItems(str.split("\\|"));
    } 
    AppUtils.setSpinnerSelectionFromValue(SVulkanVersion, config.get("vkMaxVersion", DEFAULT_VK_MAX_VERSION));
    AppUtils.setSpinnerSelectionFromValue(SMaxDeviceMemory, config.get("maxDeviceMemory", String.valueOf(512)));
    AppUtils.setSpinnerSelectionFromValue(SImageCacheSize, config.get("imageCacheSize", String.valueOf(256)));
    SResourceMemoryType.setSelection(config.getInt("resourceMemoryType", 0));
    SRenderVersion.setSelection(config.getInt("renderVersion", 0));

    setOnConfirmCallback(() -> {
      config.put("vkMaxVersion", SVulkanVersion.getSelectedItem().toString());
      config.put("maxDeviceMemory", SMaxDeviceMemory.getSelectedItem().toString());
      config.put("imageCacheSize", SImageCacheSize.getSelectedItem().toString());
      config.put("resourceMemoryType", SResourceMemoryType.getSelectedItemPosition());
      config.put("exposedDeviceExtensions", String.join("|", multiSelectionComboBox.getSelectedItems()));
      config.put("renderVersion", SRenderVersion.getSelectedItemPosition());
      anchor.setTag(config.toString());
    });
  }

  public static KeyValueSet parseConfig(Object config) {
    String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
    return new KeyValueSet(data);
  }
}
