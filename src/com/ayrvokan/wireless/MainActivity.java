package com.ayrvokan.wireless;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(12, 19, 29);
    private static final int CARD = Color.rgb(23, 35, 49);
    private static final int TEXT = Color.rgb(234, 241, 248);
    private static final int MUTED = Color.rgb(158, 179, 198);
    private static final int ACCENT = Color.rgb(110, 218, 196);
    private WorkshopStore store;
    private JSONObject catalog;
    private LinearLayout content;
    private LinearLayout navigation;
    private String screen = "Home";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new WorkshopStore(this);
        new Thread(() -> { try { WorkshopFiles.recover(this); TerminalJobs.recover(this); } catch (Exception error) { android.util.Log.e("Workshop", "Tool recovery failed", error); } }, "tool-recovery").start();
        try {
            catalog = new JSONObject(new String(read(getAssets().open("workshop/catalog.json")), StandardCharsets.UTF_8));
            store.event("startup", "Local workspace opened");
            if (state != null) screen = state.getString("screen", "Home");
            buildWindow();
            show(screen);
            snapshot();
        } catch (Exception error) {
            TextView failure = text("Unable to open the workspace\n\n" + error.getMessage(), 18, TEXT);
            failure.setPadding(dp(24), dp(64), dp(24), dp(24));
            failure.setBackgroundColor(BG);
            setContentView(failure);
        }
    }

    private void buildWindow() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout header = column();
        header.setPadding(dp(22), dp(18), dp(22), dp(14));
        TextView brand = text("WIRELESS WORKSHOP", 12, ACCENT);
        brand.setLetterSpacing(0.16f);
        header.addView(brand);
        header.addView(text("Personal workspace", 14, MUTED));
        root.addView(header);
        content = column();
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = new LinearLayout(this);
        navigation.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(navigation);
        setContentView(root);
        root.requestApplyInsets();
    }

    private void show(String page) {
        screen = page;
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = column();
        body.setPadding(dp(22), dp(10), dp(22), dp(20));
        scroll.addView(body);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        try {
            if ("Home".equals(page)) home(body);
            else if ("Modules".equals(page)) modules(body);
            else if ("Activity".equals(page)) activity(body);
            else settings(body);
        } catch (Exception error) {
            body.addView(text(error.getMessage(), 16, TEXT));
        }
        navigation.removeAllViews();
        for (String label : new String[] {"Home", "Modules", "Activity", "Settings"}) {
            Button button = button(label, () -> show(label));
            button.setTextColor(label.equals(screen) ? ACCENT : MUTED);
            button.setTextSize(12);
            button.setPadding(0, 0, 0, 0);
            button.setMinWidth(0);
            button.setBackground(shape(label.equals(screen) ? CARD : BG, 12));
            navigation.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
    }

    private void home(LinearLayout body) throws Exception {
        title(body, store.workspace(), "Your devices and local tools.");
        LinearLayout summary = card(body);
        summary.addView(text("Ready for your own setup", 19, TEXT));
        summary.addView(text("This workspace opens locally. Device connections can be added without using the original app's home screen.", 14, MUTED));
        gap(body, 10);
        body.addView(text("YOUR DEVICES", 12, ACCENT));
        JSONArray profiles = store.profiles();
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            LinearLayout device = card(body);
            device.addView(text(profile.getString("label"), 20, TEXT));
            device.addView(text(profile.getString("kind"), 14, MUTED));
            TextView status = text(profile.getString("status"), 13, ACCENT);
            status.setPadding(0, dp(12), 0, 0);
            device.addView(status);
        }
        LinearLayout tools = card(body);
        tools.addView(text("Local diagnostics", 19, TEXT));
        tools.addView(text("Check the app's storage and keep the result in Activity.", 14, MUTED));
        tools.addView(button("Check local storage", this::storageCheck));
        tools.addView(button("Open diagnostic logs", () -> startActivity(new android.content.Intent(this, DiagnosticsActivity.class))));
        tools.addView(button("Open local terminal", () -> startActivity(new android.content.Intent(this, TerminalActivity.class))));
        tools.addView(button("Open files", () -> startActivity(new android.content.Intent(this, FilesActivity.class))));
        tools.addView(button("Open JADX headless", () -> startActivity(new android.content.Intent(this, JadxActivity.class))));
        body.addView(text("Device connections are not configured yet.", 13, MUTED));
    }

    private void modules(LinearLayout body) throws Exception {
        title(body, "Device modules", "Original code is retained in this APK. Device support is verified when a module is connected.");
        JSONArray groups = catalog.getJSONArray("groups");
        LinearLayout totals = card(body);
        totals.addView(text("100 native libraries", 21, TEXT));
        totals.addView(text("1,101 bridge declarations · 6 Hermes bundles", 14, MUTED));
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.getJSONObject(i);
            LinearLayout item = card(body);
            item.addView(text(group.getString("label"), 18, TEXT));
            item.addView(text(group.getInt("classes") + " classes · " + group.getInt("methods") + " declarations", 13, MUTED));
            item.addView(button("View modules", () -> showGroup(group.optString("id"), group.optString("label"))));
        }
        body.addView(button("View native libraries", this::showLibraries));
    }

    private void showGroup(String id, String label) {
        try {
            StringBuilder message = new StringBuilder();
            JSONArray classes = catalog.getJSONArray("classes");
            for (int i = 0; i < classes.length(); i++) {
                JSONObject item = classes.getJSONObject(i);
                if (id.equals(item.getString("group"))) message.append(item.getString("name")).append("\n").append(item.getInt("methods")).append(" declarations\n\n");
            }
            new AlertDialog.Builder(this).setTitle(label).setMessage(message.toString()).setPositiveButton("Close", null).show();
        } catch (Exception error) {
            problem(error);
        }
    }

    private void showLibraries() {
        try {
            StringBuilder message = new StringBuilder();
            JSONArray libraries = catalog.getJSONArray("libraries");
            for (int i = 0; i < libraries.length(); i++) {
                JSONObject item = libraries.getJSONObject(i);
                message.append(item.getString("name")).append("\n").append(item.getString("role")).append("\n\n");
            }
            new AlertDialog.Builder(this).setTitle("Packaged native libraries").setMessage(message.toString()).setPositiveButton("Close", null).show();
        } catch (Exception error) {
            problem(error);
        }
    }

    private void activity(LinearLayout body) throws Exception {
        title(body, "Activity", "Saved on this phone.");
        body.addView(button("Open diagnostic logs", () -> startActivity(new android.content.Intent(this, DiagnosticsActivity.class))));
        JSONArray events = store.events(100);
        SimpleDateFormat format = new SimpleDateFormat("dd MMM · HH:mm:ss", Locale.getDefault());
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            LinearLayout item = card(body);
            item.addView(text(event.getString("message"), 16, TEXT));
            item.addView(text(format.format(new Date(event.getLong("time"))) + " · " + event.getString("kind"), 12, MUTED));
        }
    }

    private void settings(LinearLayout body) {
        title(body, "Settings", "Make this workspace yours.");
        LinearLayout panel = card(body);
        panel.addView(text("Workspace name", 16, TEXT));
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setTextColor(TEXT);
        name.setHintTextColor(MUTED);
        name.setText(store.workspace());
        name.setContentDescription("Workspace name");
        panel.addView(name, new LinearLayout.LayoutParams(-1, dp(56)));
        panel.addView(button("Save workspace", () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty() || value.length() > 64) {
                name.setError("Use 1 to 64 characters");
                return;
            }
            store.saveWorkspace(value);
            snapshot();
            Toast.makeText(this, "Workspace saved", Toast.LENGTH_SHORT).show();
        }));
        LinearLayout about = card(body);
        String version;
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception error) { version = ""; }
        about.addView(text("Wireless Workshop " + version, 18, TEXT));
        about.addView(text("Personal APK · ARM64\nOriginal device code: eWeLink 5.30.0", 14, MUTED));
        about.addView(button("Clear activity history", () -> {
            store.clearEvents();
            snapshot();
            Toast.makeText(this, "Activity history cleared", Toast.LENGTH_SHORT).show();
        }));
    }

    private void storageCheck() {
        new Thread(() -> {
            try { OperationLog.storageCheck(getApplicationContext()); runOnUiThread(() -> { snapshot(); Toast.makeText(this, "Storage check passed", Toast.LENGTH_SHORT).show(); }); }
            catch (Exception error) { runOnUiThread(() -> problem(error)); }
        }, "storage-diagnostics").start();
    }

    private void snapshot() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 15);
            JSONObject value = new JSONObject();
            value.put("package", getPackageName());
            value.put("version", info.versionName);
            value.put("applicationClass", getApplication().getClass().getName());
            value.put("uid", android.os.Process.myUid());
            value.put("sdk", Build.VERSION.SDK_INT);
            value.put("workspace", store.workspace());
            value.put("profiles", store.profiles());
            value.put("recentEvents", store.events(100));
            value.put("activities", info.activities == null ? 0 : info.activities.length);
            value.put("services", info.services == null ? 0 : info.services.length);
            value.put("receivers", info.receivers == null ? 0 : info.receivers.length);
            value.put("providers", info.providers == null ? 0 : info.providers.length);
            value.put("nativeLibraries", catalog.getJSONArray("libraries").length());
            value.put("bridgeDeclarations", catalog.getInt("bridgeDeclarations"));
            try (FileOutputStream output = openFileOutput("runtime-state.json", MODE_PRIVATE)) {
                output.write(value.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            store.event("error", "Snapshot failed: " + error.getMessage());
        }
    }

    private void problem(Exception error) {
        store.event("error", error.getClass().getSimpleName() + ": " + error.getMessage());
        snapshot();
        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
    }

    private byte[] read(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = stream.read(buffer)) != -1) output.write(buffer, 0, length);
            return output.toByteArray();
        }
    }

    private void title(LinearLayout body, String title, String subtitle) {
        TextView heading = text(title, 29, TEXT);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        body.addView(heading);
        TextView detail = text(subtitle, 14, MUTED);
        detail.setPadding(0, dp(5), 0, dp(18));
        body.addView(detail);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout view = column();
        view.setPadding(dp(18), dp(18), dp(18), dp(18));
        view.setBackground(shape(CARD, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(8), 0, dp(8));
        parent.addView(view, params);
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextColor(ACCENT);
        view.setOnClickListener(clicked -> action.run());
        return view;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void gap(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("screen", screen);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (store != null) store.close();
        super.onDestroy();
    }
}
