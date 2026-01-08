package com.winlator;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.container.ContainerManager;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.ShortcutSettingsDialog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ShortcutsFragment extends Fragment {
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        loadShortcutsList();
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.shortcuts);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout)inflater.inflate(R.layout.shortcuts_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
        return frameLayout;
    }

    public void loadShortcutsList() {
        ArrayList<Shortcut> shortcuts = manager.loadShortcuts();
        recyclerView.setAdapter(new ShortcutsAdapter(shortcuts));
        if (shortcuts.isEmpty()) emptyTextView.setVisibility(View.VISIBLE);
    }

    private class ShortcutsAdapter extends RecyclerView.Adapter<ShortcutsAdapter.ViewHolder> {
        private final List<Shortcut> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageButton menuButton;
            private final ImageView imageView;
            private final TextView title;
            private final TextView subtitle;
            private final View innerArea;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.subtitle = view.findViewById(R.id.TVSubtitle);
                this.menuButton = view.findViewById(R.id.BTMenu);
                this.innerArea = view.findViewById(R.id.LLInnerArea);
            }
        }

        public ShortcutsAdapter(List<Shortcut> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.shortcut_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.menuButton.setOnClickListener(null);
            holder.innerArea.setOnClickListener(null);
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final Shortcut item = data.get(position);
            if (item.icon != null) holder.imageView.setImageBitmap(item.icon);
            holder.title.setText(item.name);
            holder.subtitle.setText(item.container.getName());
            holder.menuButton.setOnClickListener((v) -> showListItemMenu(v, item));
            holder.innerArea.setOnClickListener((v) -> runFromShortcut(item));
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, final Shortcut shortcut) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.inflate(R.menu.shortcut_popup_menu);
            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.shortcut_settings) {
                    (new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut)).show();
                }
                else if (itemId == R.id.shortcut_remove) {
                    ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_shortcut, () -> {
                        if (shortcut.file.delete() && shortcut.iconFile != null) shortcut.iconFile.delete();
                        disableShortcutOnScreen(requireContext(), shortcut);
                        loadShortcutsList();
                    });
                }
                else if (itemId == R.id.shortcut_add_to_home_screen) {
                    if (shortcut.getExtra("uuid").equals(""))
                        shortcut.genUUID();
                    addShortcutToScreen(shortcut);
                }
                return true;
            });
            listItemMenu.show();
        }

        private void runFromShortcut(Shortcut shortcut) {
            Activity activity = getActivity();

            final Context context = getContext();
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            boolean isX11LorieEnabled = sp.getBoolean("use_lorie", false);
            if(isX11LorieEnabled) {
                launchXLorie(shortcut.container.id, shortcut.file.getPath());
            }
            else {
                if (!XrActivity.isEnabled(getContext())) {
                    Intent intent = new Intent(activity, XServerDisplayActivity.class);
                    intent.putExtra("container_id", shortcut.container.id);
                    intent.putExtra("shortcut_path", shortcut.file.getPath());
                    activity.startActivity(intent);
                } else
                    XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.getPath());
            }
        }
    }

    private ShortcutInfo buildScreenShortCut(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        Intent intent = new Intent(getActivity(), XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", containerId);
        intent.putExtra("shortcut_path", shortcutPath);

        return new ShortcutInfo.Builder(getActivity(), uuid)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(icon)
                .setIntent(intent)
                .build();
    }

    private void addShortcutToScreen(Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported())
            shortcutManager.requestPinShortcut(buildScreenShortCut(shortcut.name, shortcut.name, shortcut.container.id,
                    shortcut.file.getPath(), Icon.createWithBitmap(shortcut.icon), shortcut.getExtra("uuid")), null);
    }

    public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(context, ShortcutManager.class);
        try {
            shortcutManager.disableShortcuts(Collections.singletonList(shortcut.getExtra("uuid")),
                    context.getString(R.string.shortcut_not_available));
        } catch (Exception e) {}
    }

    public void updateShortcutOnScreen(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        try {
            for (ShortcutInfo shortcutInfo : shortcutManager.getPinnedShortcuts()) {
                if (shortcutInfo.getId().equals(uuid)) {
                    shortcutManager.updateShortcuts(Collections.singletonList(
                            buildScreenShortCut(shortLabel, longLabel, containerId, shortcutPath, icon, uuid)));
                    break;
                }
            }
        } catch (Exception e) {}
    }

    //@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void launchXLorie(int container_id, String shortcut_path) {
        PackageManager pm = getActivity().getPackageManager();
        PackageInfo info;
        try {
            ///info = pm.getPackageInfo(getActivity().getPackageName(), PackageManager.PackageInfoFlags.of(0));
            info = pm.getPackageInfo(getActivity().getPackageName(), PackageManager.GET_META_DATA);
        }
        catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        ProcessBuilder builder = new ProcessBuilder("/system/bin/app_process", "/", "com.winlator.CmdEntryPoint", ":0", "-legacy-drawing");
        builder.redirectErrorStream(true);
        builder.environment().put("CLASSPATH", info.applicationInfo.sourceDir);
        builder.environment().put("WINLATOR_X11_DEBUG", "1");
        Log.i("SourceDir: ", info.applicationInfo.sourceDir);
        builder.environment().put("TMPDIR", "/data/data/com.winlator/files/imagefs/usr/tmp");
        builder.environment().put("XKB_CONFIG_ROOT", "/data/data/com.winlator/files/imagefs/usr/share/X11/xkb");
        Thread t = new Thread(new Runnable(){
            @Override
            public void run() {
                try {
                    Process x11Process = builder.start();
                    Intent x11Lorie = new Intent(getActivity(), X11Activity.class);
                    x11Lorie.putExtra("container_id", container_id);
                    x11Lorie.putExtra("shortcut_path", shortcut_path);
                    startActivity(x11Lorie);
                    BufferedReader br = new BufferedReader(new InputStreamReader(x11Process.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.d("X11Loader", line);
                    }
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        t.start();
    }
}
