package studio.deimos.pinyonshift;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SetupActivity extends Activity implements View.OnClickListener {
    private static final int HTTP_PORT = 47615;
    private static final int DISCOVERY_PORT = 47616;
    private static final int REQUEST_FOLDER = 0x5055;
    private static final int REQUEST_DRIVER_ZIP = 0x5056;

    // Palette matching the PC release (PinyonShift.Launcher/App.xaml)
    private static final int COLOR_BG = 0xFF0A0F0C;
    private static final int COLOR_SURFACE = 0xFF101813;
    private static final int COLOR_CARD = 0xFF16211A;
    private static final int COLOR_CARD_STROKE = 0xFF34493B;
    private static final int COLOR_ACCENT_DEFAULT = 0xFF5CD08A;
    private static final int COLOR_FOG = 0xFFEEF3EC;
    private static final int COLOR_MUTED = 0xFF93A597;
    private static final int COLOR_TEXT_DIM = 0xFF61736A;
    private static final int COLOR_SUCCESS = 0xFF5CD08A;
    private static final int COLOR_RED = 0xFFE16E5F;

    private static final String INCOMPLETE = "game/.copy-incomplete";
    static final String ARGUMENTS_FILE = "state/config/android-arguments.txt";
    private static final String CONFIG_TOML = "state/config/pinyon-shift.toml";
    private static final String SETUP_ACTION = "studio.deimos.pinyonshift.SETUP";

    private static final String PREF_SELECTED_DRIVER = "selected_driver";
    private static final String PREF_ASPECT_MODE = "aspect_mode";
    private static final String PREF_RES_SCALE = "res_scale";
    private static final String PREF_VSYNC = "vsync";
    private static final String PREF_FPS_CAP = "fps_cap";
    private static final String PREF_GRAPHICS_PRESET = "graphics_preset";
    private static final String PREF_UPSCALER = "upscaler";
    private static final String PREF_CONTROL_SCHEME = "control_scheme";
    private static final String PREF_AUTO_THROTTLE = "auto_throttle";
    private static final String PREF_THERMAL_PACER = "thermal_pacer";
    private static final String PREF_HAPTICS = "haptics";
    private static final String PREF_TBDR_TILES = "tbdr_tiles";
    private static final String PREF_SKIP_MOVIES = "skip_movies";
    private static final String PREF_CLUB_OFFLINE = "club_offline";
    private static final String PREF_TREASURE_MAP = "treasure_map";
    private static final String PREF_GPU_TURBO = "gpu_turbo";
    private static final String PREF_NO_PREAMBLE = "no_preamble";
    private static final String PREF_AUTO_LAUNCH = "auto_launch";

    private File base;
    private File driversDir;
    private SharedPreferences prefs;
    private int colorAccent;

    private int currentTab = 0;
    private FrameLayout tabContentContainer;
    private final List<View> tabPages = new ArrayList<>();
    private final List<LinearLayout> navItems = new ArrayList<>();

    private TextView gameTitleText;
    private TextView gameStatusText;
    private Button launchButton;
    private LinearLayout driverListContainer;
    private TextView activeDriverText;
    private TextView aspectModeSummary;
    private TextView resScaleSummary;
    private TextView fpsCapSummary;
    private TextView presetSummary;
    private TextView upscalerSummary;
    private TextView controlSummary;
    private TextView themeSummary;
    private TextView touchOpacitySummary;
    private TextView folderPathText;
    private LinearLayout backupListContainer;
    private LinearLayout activeBadgesLayout;

    private TextView discoveryText;
    private EditText addressField;
    private EditText codeField;
    private LinearLayout groupsBox;
    private Button copyButton;
    private ProgressBar progress;
    private TextView progressText;
    private final Map<String, CheckBox> groupChecks = new LinkedHashMap<>();
    private JSONObject manifest;
    private String server;
    private volatile boolean busy;
    private volatile boolean discovering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File external = getExternalFilesDir(null);
        base = external != null ? external : getFilesDir();
        driversDir = new File(base, "state/drivers");
        driversDir.mkdirs();
        prefs = getSharedPreferences("pinyon_shift_settings", MODE_PRIVATE);
        colorAccent = prefs.getInt("theme_accent", COLOR_ACCENT_DEFAULT);
        currentTab = prefs.getInt("active_tab", 0);

        PinyonShiftActivity.installBundledDrivers(getAssets(), driversDir);

        Intent intent = getIntent();
        boolean asked = intent != null && (SETUP_ACTION.equals(intent.getAction())
                || intent.getBooleanExtra("setup", false));

        if (gameReady() && !asked && prefs.getBoolean(PREF_AUTO_LAUNCH, false)) {
            startGame();
            return;
        }

        buildMaterial3Layout();
        refreshAllTabs();
        startDiscovery();
    }

    @Override
    protected void onDestroy() {
        discovering = false;
        super.onDestroy();
    }

    private boolean gameReady() {
        return new File(base, "game/base/default.xex").isFile() && !new File(base, INCOMPLETE).exists();
    }

    private void startGame() {
        applySettings();
        Intent game = new Intent(this, PinyonShiftActivity.class);
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            game.putExtras(intent.getExtras());
            game.removeExtra("setup");
        }
        game.putExtra("args", buildCurrentArguments());
        startActivity(game);
        if (prefs.getBoolean(PREF_AUTO_LAUNCH, false)) {
            finish();
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams lpWeight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
    }

    private GradientDrawable cardBackground(int color, float radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private RippleDrawable rippleDrawable(int normal, int ripple, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(ripple),
                cardBackground(normal, radiusDp, 0, 0), mask);
    }

    private LinearLayout createCard(LinearLayout parent, int paddingDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBackground(COLOR_CARD, 16, COLOR_CARD_STROKE, 1));
        int p = dp(paddingDp);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams l = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(l);
        if (parent != null) parent.addView(card);
        return card;
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private EditText createField(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(COLOR_TEXT_DIM);
        et.setTextColor(Color.WHITE);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setInputType(inputType);
        et.setBackground(cardBackground(COLOR_SURFACE, 8, COLOR_CARD_STROKE, 1));
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        return et;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void confirmDialog(String title, String msg, String pos, DialogInterface.OnClickListener ok) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton(pos, ok).setNegativeButton("Cancel", null).show();
    }

    Drawable getSymbol(String name) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        if (id == 0) return null;
        Drawable d = getDrawable(id);
        return d != null ? d.mutate() : null;
    }

    void setButtonIcon(Button b, String name, int tint) {
        if (b == null) return;
        Drawable d = getSymbol(name);
        if (d != null) {
            d.setBounds(0, 0, dp(18), dp(18));
            d.setTint(tint);
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(8));
        } else {
            b.setCompoundDrawables(null, null, null, null);
        }
    }

    private Button createM3Button(String text, boolean primary) {
        return createM3Button(text, null, primary);
    }

    private Button createM3Button(String text, String icon, boolean primary) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        int bg = primary ? colorAccent : COLOR_SURFACE;
        int fg = primary ? COLOR_BG : colorAccent;
        btn.setTextColor(fg);
        btn.setBackground(rippleDrawable(bg, Color.argb(60, 255, 255, 255), 14));
        btn.setPadding(dp(16), dp(10), dp(16), dp(10));
        if (icon != null) setButtonIcon(btn, icon, fg);
        return btn;
    }

    private void buildMaterial3Layout() {
        LinearLayout root = new LinearLayout(this);
        root.setFitsSystemWindows(true);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(COLOR_SURFACE);
        header.setPadding(dp(20), dp(12), dp(20), dp(12));

        ImageView mark = new ImageView(this);
        Drawable mk = getSymbol("ic_launcher_foreground");
        if (mk != null) mark.setImageDrawable(mk);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(26), dp(26));
        mlp.rightMargin = dp(10);
        header.addView(mark, mlp);

        header.addView(label("PINYON SHIFT", 17, Color.WHITE, true), lpWeight1());
        root.addView(header);

        tabContentContainer = new FrameLayout(this);
        LinearLayout.LayoutParams clp = lp(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        clp.weight = 1.0f;
        tabContentContainer.setLayoutParams(clp);

        tabPages.clear();
        tabPages.add(buildPlayTab());
        tabPages.add(buildDriversTab());
        tabPages.add(buildSettingsTab());
        tabPages.add(buildContentTab());

        for (int i = 0; i < tabPages.size(); i++) {
            View page = tabPages.get(i);
            page.setVisibility(i == currentTab ? View.VISIBLE : View.GONE);
            tabContentContainer.addView(page);
        }
        root.addView(tabContentContainer);

        root.addView(buildBottomNavBar());
        setContentView(root);
    }

    private ScrollView wrapScrollable(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int p = dp(16);
        content.setPadding(p, p, p, p);
        scroll.addView(content);
        return scroll;
    }

    private LinearLayout buildBottomNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(COLOR_SURFACE);
        bar.setElevation(dp(8));
        bar.setPadding(0, dp(6), 0, dp(6));

        navItems.clear();
        final String[] titles = {"Play", "Drivers", "Settings", "Content"};
        final String[] icons = {"ic_play_arrow", "ic_memory", "ic_settings", "ic_folder_open"};

        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(4), 0, dp(4));

            ImageView icon = new ImageView(this);
            Drawable d = getSymbol(icons[i]);
            if (d != null) icon.setImageDrawable(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
            lp.bottomMargin = dp(2);
            item.addView(icon, lp);

            TextView lbl = label(titles[i], 11, COLOR_TEXT_DIM, false);
            lbl.setGravity(Gravity.CENTER);
            item.addView(lbl);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { switchTab(index); }
            });

            bar.addView(item, lpWeight1());
            navItems.add(item);
        }
        updateNavSelection();
        return bar;
    }

    private void switchTab(int index) {
        if (index < 0 || index >= tabPages.size()) return;
        tabPages.get(currentTab).setVisibility(View.GONE);
        currentTab = index;
        prefs.edit().putInt("active_tab", index).apply();
        tabPages.get(currentTab).setVisibility(View.VISIBLE);
        updateNavSelection();
        refreshAllTabs();
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void updateNavSelection() {
        for (int i = 0; i < navItems.size(); i++) {
            LinearLayout item = navItems.get(i);
            int color = (i == currentTab) ? colorAccent : COLOR_TEXT_DIM;
            ImageView iv = (ImageView) item.getChildAt(0);
            if (iv != null && iv.getDrawable() != null) {
                iv.getDrawable().mutate().setTint(color);
            }
            ((TextView) item.getChildAt(1)).setTextColor(color);
        }
    }

    private View buildPlayTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hero = createCard(content, 18);
        hero.setBackground(cardBackground(COLOR_CARD, 18, colorAccent, 1));
        gameTitleText = label("Ready to drive", 22, COLOR_FOG, true);
        hero.addView(gameTitleText);

        gameStatusText = label("", 12, COLOR_TEXT_DIM, false);
        gameStatusText.setPadding(0, dp(4), 0, dp(14));
        hero.addView(gameStatusText);

        launchButton = createM3Button("Play", "ic_play_arrow", true);
        launchButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        launchButton.setPadding(dp(20), dp(12), dp(20), dp(12));
        launchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (gameReady()) startGame();
                else {
                    switchTab(3);
                    toast("Import game files first");
                }
            }
        });
        hero.addView(launchButton);

        LinearLayout badges = createCard(content, 14);
        TextView bt = label("Active Launch Profile", 13, COLOR_MUTED, true);
        bt.setPadding(0, 0, 0, dp(8));
        badges.addView(bt);

        activeBadgesLayout = new LinearLayout(this);
        activeBadgesLayout.setOrientation(LinearLayout.VERTICAL);
        badges.addView(activeBadgesLayout);

        LinearLayout specs = createCard(content, 14);
        specs.addView(label("Hardware", 13, COLOR_MUTED, true));

        String adreno = PinyonShiftActivity.adrenoModel();
        TextView sb = label(Build.MANUFACTURER + " " + Build.MODEL + " · "
                + (adreno != null ? "Adreno " + adreno : "GPU") + " · Android " + Build.VERSION.RELEASE, 12, COLOR_TEXT_DIM, false);
        specs.addView(sb);

        return wrapScrollable(content);
    }

    private TextView createBadgeView(String text) {
        TextView b = label(text, 12, Color.WHITE, false);
        b.setBackground(cardBackground(COLOR_SURFACE, 8, COLOR_CARD_STROKE, 1));
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams l = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.setMargins(0, dp(4), 0, 0);
        b.setLayoutParams(l);
        return b;
    }

    private View buildDriversTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = createCard(content, 16);
        card.addView(label("Vulkan GPU Driver", 14, Color.WHITE, true));

        activeDriverText = label("", 12, colorAccent, false);
        activeDriverText.setPadding(0, dp(4), 0, dp(12));
        card.addView(activeDriverText);

        driverListContainer = new LinearLayout(this);
        driverListContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(driverListContainer);

        Button importBtn = createM3Button("+  Import Driver (.zip / .adpkg)", false);
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { importDriverZip(); }
        });
        card.addView(importBtn);

        return wrapScrollable(content);
    }

    private void refreshDriversList() {
        if (driverListContainer == null) return;
        driverListContainer.removeAllViews();

        String selected = prefs.getString(PREF_SELECTED_DRIVER, "auto");
        String adreno = PinyonShiftActivity.adrenoModel();
        String rec = PinyonShiftActivity.recommendedDriver(adreno);
        activeDriverText.setText("Active: " + selected + ("auto".equals(selected) && rec != null ? " (" + rec + ")" : ""));

        addDriverItem("System Default Driver", "auto", "System Vulkan stack (Turnip if Adreno)", "auto".equals(selected));

        File[] dirs = driversDir.listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) {
                if (d.isDirectory() && new File(d, "meta.json").isFile()) {
                    String name = d.getName();
                    addDriverItem(name, name, "Turnip / AdrenoTools package", name.equals(selected));
                }
            }
        }
    }

    private void addDriverItem(final String name, final String id, String desc, boolean isSelected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int bg = isSelected ? ((colorAccent & 0x00FFFFFF) | 0x28000000) : COLOR_SURFACE;
        int stroke = isSelected ? colorAccent : COLOR_CARD_STROKE;
        row.setBackground(cardBackground(bg, 12, stroke, 1));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams l = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(l);

        LinearLayout textGroup = new LinearLayout(this);
        textGroup.setOrientation(LinearLayout.VERTICAL);
        textGroup.addView(label(name + (isSelected ? "  ✓" : ""), 13, isSelected ? colorAccent : COLOR_FOG, true));
        textGroup.addView(label(desc, 11, COLOR_TEXT_DIM, false));
        row.addView(textGroup, lpWeight1());

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putString(PREF_SELECTED_DRIVER, id).apply();
                refreshDriversList();
                applySettings();
            }
        });

        if (!"auto".equals(id) && !"turnip-gen8-v37".equals(id)) {
            Button del = new Button(this);
            del.setText("✕");
            del.setTextColor(COLOR_RED);
            del.setBackgroundColor(Color.TRANSPARENT);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { deleteDriver(id); }
            });
            row.addView(del);
        }

        driverListContainer.addView(row);
    }

    private void deleteDriver(final String id) {
        confirmDialog("Delete Driver", "Remove " + id + "?", "Delete", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                PinyonShiftActivity.deleteTree(new File(driversDir, id));
                if (id.equals(prefs.getString(PREF_SELECTED_DRIVER, "auto"))) {
                    prefs.edit().putString(PREF_SELECTED_DRIVER, "auto").apply();
                }
                refreshDriversList();
                applySettings();
            }
        });
    }

    private void importDriverZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimes = {"application/zip", "application/x-zip-compressed", "application/octet-stream"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        try { startActivityForResult(intent, REQUEST_DRIVER_ZIP); }
        catch (Exception e) { toast("No file picker"); }
    }

    private View buildSettingsTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout disp = createCard(content, 16);
        disp.addView(label("Display & Resolution", 14, Color.WHITE, true));

        aspectModeSummary = addChoiceSelector(disp, "Display Scaling Mode", PREF_ASPECT_MODE,
                new String[]{"Widescreen (Hor+)  [Recommended]", "Fit (16:9 Letterbox)", "Stretch (Fullscreen)", "Crop (Zoom)"},
                new String[]{"hor_plus", "letterbox", "stretch", "crop"});

        resScaleSummary = addIntChoiceSelector(disp, "Internal Resolution", PREF_RES_SCALE,
                new String[]{"1x · 720p Native", "1.5x · 1080p", "2x · 1440p"},
                new int[]{1, 2, 2});

        fpsCapSummary = addIntChoiceSelector(disp, "Target Framerate", PREF_FPS_CAP,
                new String[]{"60 FPS", "45 FPS", "40 FPS", "30 FPS", "120 FPS", "Unlocked"},
                new int[]{60, 45, 40, 30, 120, 0});
        addSwitchRow(disp, "Vertical Sync (VSync)", "Lock presentation to display refresh rate", PREF_VSYNC, true);

        presetSummary = addChoiceSelector(disp, "Graphics Preset", PREF_GRAPHICS_PRESET,
                new String[]{"Potato (Max FPS)", "Balanced (Standard)", "Ultra (Full Visuals)"},
                new String[]{"potato", "balanced", "ultra"});

        upscalerSummary = addChoiceSelector(disp, "Upscaler (FSR / CAS)", PREF_UPSCALER,
                new String[]{"AMD FSR 1.0 (Spatial)", "CAS (Adaptive Sharpening)", "Bilinear"},
                new String[]{"fsr", "cas", "bilinear"});

        themeSummary = addSettingSelector(disp, "Theme Accent", "Pinyon Green", new View.OnClickListener() {
            @Override public void onClick(View v) {
                int sysAccent = COLOR_ACCENT_DEFAULT;
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        sysAccent = getResources().getColor(android.R.color.system_accent1_500, getTheme());
                    } catch (Throwable ignored) {}
                }
                showIntChoiceDialog("Theme Accent", "theme_accent",
                        new String[]{"Pinyon Green  [Original PC]", "System Dynamic  [Material You]", "Horizon Amber  [Festival]", "Alpine White", "Cyber Cyan", "Sunset Orange"},
                        new int[]{COLOR_ACCENT_DEFAULT, sysAccent, 0xFFF1AE36, COLOR_FOG, 0xFF5CE1E6, 0xFFFF7043});
            }
        });

        LinearLayout mobile = createCard(content, 16);
        mobile.addView(label("Mobile Engine Optimizations", 14, Color.WHITE, true));
        addSwitchRow(mobile, "TBDR Tile Passes", "Preserve on-chip tile memory", PREF_TBDR_TILES, true);
        addSwitchRow(mobile, "Thermal Frame Pacer", "Auto-throttle target FPS at >= 42°C", PREF_THERMAL_PACER, true);
        addSwitchRow(mobile, "No Driver Preamble", "Skip command buffer preambles", PREF_NO_PREAMBLE, true);
        addSwitchRow(mobile, "GPU Turbo Boost", "Aggressive GPU power governor", PREF_GPU_TURBO, false);

        LinearLayout ctrl = createCard(content, 16);
        ctrl.addView(label("Controls & Haptics", 14, Color.WHITE, true));
        controlSummary = addChoiceSelector(ctrl, "Control Scheme", PREF_CONTROL_SCHEME,
                new String[]{"Tilt A (RR3 Gyro)", "Tilt B (Touch Steer)", "Classic (Virtual Pad)", "Gamepad (External)"},
                new String[]{"tilt_a", "tilt_b", "classic", "gamepad"});
        touchOpacitySummary = addIntChoiceSelector(ctrl, "Touch Controls Opacity", "touch_opacity",
                new String[]{"100% · Fully Visible", "75%", "50%", "25%", "Hidden · Clean HUD"},
                new int[]{100, 75, 50, 25, 0});
        addSettingSelector(ctrl, "Touch Controls Guide", "View 4-Layer Gesture Map",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { PinyonShiftActivity.showTouchGuide(SetupActivity.this); }
                });
        addSwitchRow(ctrl, "Steering Horizon", "Subtle tilt bar at bottom center", "touch_horizon", true);
        addSwitchRow(ctrl, "Auto-Throttle", "Auto acceleration (zones brake)", PREF_AUTO_THROTTLE, false);
        addSwitchRow(ctrl, "Haptic Feedback", "Vibration on shifts & pedals", PREF_HAPTICS, true);
        addSwitchRow(ctrl, "Smart Auto-Clutch", "Auto-blip LB on shifts", PinyonShiftActivity.PREF_SMART_CLUTCH, true);
        addSwitchRow(ctrl, "Center Gesture Shift", "Flick up/down in center to shift", PinyonShiftActivity.PREF_GESTURE_SHIFT, true);
        addSwitchRow(ctrl, "HUD Touch Hitboxes", "Minimap map & wristband pause", PinyonShiftActivity.PREF_HUD_HITBOXES, true);
        addSwitchRow(ctrl, "Menu Mode Auto-Switch", "Swipes = LB/RB tabs in menus", PinyonShiftActivity.PREF_MENU_MODE_AUTO, true);

        LinearLayout game = createCard(content, 16);
        game.addView(label("Game Features", 14, Color.WHITE, true));
        addSwitchRow(game, "Skip Opening Movies", "Skip intro splash videos", PREF_SKIP_MOVIES, true);
        addSwitchRow(game, "1000 Club Offline", "Local challenge evaluation", PREF_CLUB_OFFLINE, true);
        addSwitchRow(game, "Unlock Treasure Map", "Show all barn finds & signs", PREF_TREASURE_MAP, true);
        addSwitchRow(game, "Auto-Boot Game", "Launch directly on app start", PREF_AUTO_LAUNCH, false);

        return wrapScrollable(content);
    }

    private TextView addSettingSelector(LinearLayout parent, String title, String val, View.OnClickListener clk) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rippleDrawable(COLOR_SURFACE, 0x28FFFFFF, 10));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams l = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(l);

        LinearLayout tg = new LinearLayout(this);
        tg.setOrientation(LinearLayout.VERTICAL);
        tg.addView(label(title, 13, Color.WHITE, true));
        TextView vv = label(val, 11, colorAccent, false);
        tg.addView(vv);
        row.addView(tg, lpWeight1());

        row.addView(label("›", 16, COLOR_TEXT_DIM, false));
        row.setOnClickListener(clk);
        parent.addView(row);
        return vv;
    }

    private TextView addChoiceSelector(LinearLayout parent, final String title, final String prefKey, final String[] items, final String[] values) {
        return addSettingSelector(parent, title, "", new View.OnClickListener() {
            @Override public void onClick(View v) { showChoiceDialog(title, prefKey, items, values); }
        });
    }

    private TextView addIntChoiceSelector(LinearLayout parent, final String title, final String prefKey, final String[] items, final int[] values) {
        return addSettingSelector(parent, title, "", new View.OnClickListener() {
            @Override public void onClick(View v) { showIntChoiceDialog(title, prefKey, items, values); }
        });
    }

    private void tintSwitch(Switch sw) {
        int[][] states = new int[][]{new int[]{android.R.attr.state_checked}, new int[]{-android.R.attr.state_checked}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{colorAccent, COLOR_TEXT_DIM}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{(colorAccent & 0x00FFFFFF) | 0x80000000, 0x3CFFFFFF}));
    }

    private void addSwitchRow(LinearLayout parent, String title, String sub, final String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(6));

        LinearLayout tg = new LinearLayout(this);
        tg.setOrientation(LinearLayout.VERTICAL);
        tg.addView(label(title, 13, Color.WHITE, false));
        tg.addView(label(sub, 11, COLOR_TEXT_DIM, false));
        row.addView(tg, lpWeight1());

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean c) {
                prefs.edit().putBoolean(key, c).apply();
                applySettings();
            }
        });
        row.addView(sw);
        parent.addView(row);
    }

    private void showChoiceDialog(String title, final String prefKey, final String[] items, final String[] values) {
        String currentVal = prefs.getString(prefKey, values[0]);
        int cur = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(currentVal)) cur = i;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putString(prefKey, values[w]).apply();
                        d.dismiss();
                        refreshSettingsTab();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showIntChoiceDialog(String title, final String prefKey, final String[] items, final int[] values) {
        int currentVal = prefs.getInt(prefKey, values[0]);
        int cur = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == currentVal) cur = i;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putInt(prefKey, values[w]).apply();
                        d.dismiss();
                        if ("theme_accent".equals(prefKey)) { recreate(); return; }
                        refreshSettingsTab();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshSettingsTab() {
        String asp = prefs.getString(PREF_ASPECT_MODE, "hor_plus");
        if (aspectModeSummary != null) {
            aspectModeSummary.setText("hor_plus".equals(asp) ? "Widescreen (Hor+) · No Distortion"
                    : "letterbox".equals(asp) ? "Fit (16:9 Letterbox)"
                    : "stretch".equals(asp) ? "Stretch Fullscreen" : "Crop");
        }
        if (resScaleSummary != null) {
            resScaleSummary.setText(prefs.getInt(PREF_RES_SCALE, 1) == 2 ? "2x (1440p)" : "1x (720p Native)");
        }
        if (fpsCapSummary != null) {
            int c = prefs.getInt(PREF_FPS_CAP, 60);
            fpsCapSummary.setText(c > 0 ? c + " FPS" : "Unlocked");
        }
        if (presetSummary != null) {
            String p = prefs.getString(PREF_GRAPHICS_PRESET, "balanced");
            presetSummary.setText("potato".equals(p) ? "Potato (Max FPS)" : "ultra".equals(p) ? "Ultra" : "Balanced");
        }
        if (upscalerSummary != null) {
            String u = prefs.getString(PREF_UPSCALER, "fsr");
            upscalerSummary.setText("fsr".equals(u) ? "AMD FSR 1.0" : "cas".equals(u) ? "CAS" : "Bilinear");
        }
        if (themeSummary != null) {
            themeSummary.setText(colorAccent == COLOR_ACCENT_DEFAULT ? "Pinyon Green (Original PC)"
                    : colorAccent == 0xFFF1AE36 ? "Horizon Amber"
                    : colorAccent == COLOR_FOG ? "Alpine White"
                    : colorAccent == 0xFF5CE1E6 ? "Cyber Cyan"
                    : colorAccent == 0xFFFF7043 ? "Sunset Orange" : "System Dynamic");
        }
        if (controlSummary != null) {
            String c = prefs.getString(PREF_CONTROL_SCHEME, "tilt_a");
            controlSummary.setText("tilt_a".equals(c) ? "Tilt A (RR3 Gyro)" : "tilt_b".equals(c) ? "Tilt B (RR3 Touch)" : "classic".equals(c) ? "Classic" : "Gamepad");
        }
        if (touchOpacitySummary != null) {
            int op = prefs.getInt("touch_opacity", 100);
            touchOpacitySummary.setText(op == 0 ? "Hidden" : op + "%");
        }
        applySettings();
    }

    private View buildContentTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout game = createCard(content, 16);
        game.addView(label("Game Data Files", 14, Color.WHITE, true));

        folderPathText = label("", 11, COLOR_TEXT_DIM, false);
        folderPathText.setPadding(0, dp(4), 0, dp(8));
        game.addView(folderPathText);

        Button importFolderBtn = createM3Button("Select Game Folder (default.xex)", "ic_folder_open", false);
        importFolderBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseFolder(); }
        });
        game.addView(importFolderBtn);

        LinearLayout wifi = createCard(content, 16);
        wifi.addView(label("Sync from PC (Wi-Fi)", 14, Color.WHITE, true));

        discoveryText = label("Searching for PC on local Wi-Fi...", 11, COLOR_MUTED, false);
        discoveryText.setPadding(0, dp(4), 0, dp(8));
        wifi.addView(discoveryText);

        addressField = createField("PC Address (e.g. 192.168.1.50:47615)", InputType.TYPE_CLASS_TEXT);
        wifi.addView(addressField);

        codeField = createField("6-digit Pair Code from PC screen", InputType.TYPE_CLASS_NUMBER);
        codeField.setFilters(new InputFilter[] {new InputFilter.LengthFilter(6)});
        LinearLayout.LayoutParams clp = lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(6), 0, dp(10));
        codeField.setLayoutParams(clp);
        wifi.addView(codeField);

        groupsBox = new LinearLayout(this);
        groupsBox.setOrientation(LinearLayout.VERTICAL);
        wifi.addView(groupsBox);

        copyButton = createM3Button("Connect & Transfer", "ic_sync", true);
        copyButton.setOnClickListener(this);
        wifi.addView(copyButton);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        plp.setMargins(0, dp(12), 0, dp(4));
        progress.setLayoutParams(plp);
        wifi.addView(progress);

        progressText = label("", 11, COLOR_TEXT_DIM, false);
        wifi.addView(progressText);

        LinearLayout backupCard = createCard(content, 16);
        backupCard.addView(label("Save Backups", 14, Color.WHITE, true));

        backupListContainer = new LinearLayout(this);
        backupListContainer.setOrientation(LinearLayout.VERTICAL);
        backupCard.addView(backupListContainer);

        return wrapScrollable(content);
    }

    private void refreshBackupsList() {
        if (backupListContainer == null) return;
        backupListContainer.removeAllViews();
        File backups = new File(base, "state/backups/saves");
        File[] stamps = backups.listFiles();
        if (stamps == null || stamps.length == 0) {
            backupListContainer.addView(label("No save backups found", 11, COLOR_TEXT_DIM, false));
            return;
        }
        Arrays.sort(stamps, Collections.reverseOrder());
        for (final File s : stamps) {
            if (!s.isDirectory()) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            row.addView(label(s.getName(), 12, Color.WHITE, false), lpWeight1());

            Button rst = new Button(this);
            rst.setText("Restore");
            rst.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            rst.setTextColor(colorAccent);
            rst.setBackgroundColor(Color.TRANSPARENT);
            rst.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { restoreSave(s); }
            });
            row.addView(rst);
            backupListContainer.addView(row);
        }
    }

    private void restoreSave(final File backup) {
        confirmDialog("Restore Save Backup", "Restore save from " + backup.getName() + "? Current save will be backed up.", "Restore", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                backUpSave();
                try {
                    PinyonShiftActivity.copyTree(backup, new File(base, "state/user"));
                    toast("Save restored!");
                } catch (IOException e) {
                    toast("Restore failed: " + e.getMessage());
                }
            }
        });
    }

    private void refreshAllTabs() {
        boolean ready = gameReady();
        if (gameTitleText != null) {
            gameTitleText.setText(ready ? "Ready to drive" : "Set up your copy");
        }
        if (gameStatusText != null) {
            gameStatusText.setText(ready ? "Vulkan · 1x (720p Native) · Quick HUD opens settings in game"
                    : "Game files missing (default.xex)");
            gameStatusText.setTextColor(ready ? COLOR_MUTED : COLOR_RED);
        }
        if (launchButton != null) {
            launchButton.setEnabled(true);
            launchButton.setText(ready ? "Play" : "IMPORT GAME FILES");
            setButtonIcon(launchButton, ready ? "ic_play_arrow" : "ic_folder_open", COLOR_BG);
        }
        if (activeBadgesLayout != null) {
            activeBadgesLayout.removeAllViews();
            String aspect = prefs.getString(PREF_ASPECT_MODE, "hor_plus");
            String driver = prefs.getString(PREF_SELECTED_DRIVER, "auto");
            String preset = prefs.getString(PREF_GRAPHICS_PRESET, "balanced");
            String upscaler = prefs.getString(PREF_UPSCALER, "fsr");
            String ctrl = prefs.getString(PREF_CONTROL_SCHEME, "tilt_a");
            boolean vsync = prefs.getBoolean(PREF_VSYNC, true);

            String[] bgs = {"Driver: " + driver, "Scaling: " + ("hor_plus".equals(aspect) ? "Widescreen Hor+" : aspect),
                    "Preset: " + preset.toUpperCase(Locale.ROOT), "Upscaler: " + upscaler.toUpperCase(Locale.ROOT),
                    (vsync ? "VSync: ON" : "VSync: OFF"), "Controls: " + ("tilt_a".equals(ctrl) ? "RR3 Tilt A" : ctrl)};
            for (String b : bgs) activeBadgesLayout.addView(createBadgeView(b));

            File xex = new File(base, "game/base/default.xex");
            if (xex.isFile()) {
                boolean isRetail = (xex.length() == PinyonShiftActivity.MS_2505_RETAIL_XEX_SIZE);
                TextView xexBadge = createBadgeView(isRetail ? "MS-2505 USA Retail (Verified ✓)" : "default.xex (" + size(xex.length()) + ")");
                xexBadge.setTextColor(isRetail ? COLOR_SUCCESS : COLOR_MUTED);
                activeBadgesLayout.addView(xexBadge);
            }
        }
        if (folderPathText != null) {
            folderPathText.setText("Installed at: " + new File(base, "game/base").getAbsolutePath());
        }
        refreshDriversList();
        refreshSettingsTab();
        refreshBackupsList();
    }

    String[] buildCurrentArguments() {
        List<String> args = new ArrayList<>();
        String aspect = prefs.getString(PREF_ASPECT_MODE, "hor_plus");
        args.add("--pinyon_shift_hor_plus=" + "hor_plus".equals(aspect));
        args.add("--present_letterbox=" + ("letterbox".equals(aspect) || "crop".equals(aspect)));
        if ("crop".equals(aspect)) args.add("--present_crop=true");

        args.add("--android_gpu_driver=" + prefs.getString(PREF_SELECTED_DRIVER, "auto"));
        int scale = prefs.getInt(PREF_RES_SCALE, 1);
        args.add("--draw_resolution_scale_x=" + scale);
        args.add("--draw_resolution_scale_y=" + scale);
        args.add("--vsync=" + (prefs.getBoolean(PREF_VSYNC, true) ? "true" : "false"));
        int fps = prefs.getInt(PREF_FPS_CAP, 60);
        args.add("--framerate_limit=" + fps);
        args.add("--pinyon_shift_fh1_render_fps_limit=" + fps);

        String preset = prefs.getString(PREF_GRAPHICS_PRESET, "balanced");
        boolean potato = "potato".equals(preset);
        args.add("--pinyon_shift_fh1_env_map_rate=" + (potato ? "0.25" : "ultra".equals(preset) ? "1.0" : "0.5"));
        args.add("--pinyon_shift_fh1_shadows=" + !potato);

        String upscaler = prefs.getString(PREF_UPSCALER, "fsr");
        args.add("--present_effect=" + ("fsr".equals(upscaler) ? "fsr" : "cas".equals(upscaler) ? "cas" : "bilinear"));

        if (prefs.getBoolean(PREF_TBDR_TILES, true)) args.add("--vulkan_tbdr_tile_passes=true");
        if (prefs.getBoolean(PREF_SKIP_MOVIES, true)) args.add("--pinyon_shift_skip_opening_movies=true");
        if (prefs.getBoolean(PREF_TREASURE_MAP, true)) args.add("--dlc_treasure_map=true");
        if (prefs.getBoolean(PREF_GPU_TURBO, false)) args.add("--android_gpu_turbo=true");
        if (prefs.getBoolean(PREF_NO_PREAMBLE, true)) args.add("--android_gpu_driver_no_preamble=true");
        if (prefs.getBoolean(PREF_CLUB_OFFLINE, true)) {
            args.add("--pinyon_shift_car_challenge_gate_probe=true");
            args.add("--xam_report_live_signin=true");
        }
        return args.toArray(new String[0]);
    }

    private void applySettings() {
        String[] args = buildCurrentArguments();
        File argFile = new File(base, ARGUMENTS_FILE);
        argFile.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(argFile)) {
            for (String a : args) out.write((a + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}

        File tomlFile = new File(base, CONFIG_TOML);
        tomlFile.getParentFile().mkdirs();
        String toml = "[Display]\npinyon_shift_hor_plus = " + "hor_plus".equals(prefs.getString(PREF_ASPECT_MODE, "hor_plus"))
                + "\npresent_letterbox = " + "letterbox".equals(prefs.getString(PREF_ASPECT_MODE, "hor_plus"))
                + "\n\n[UI/Vulkan]\nandroid_gpu_driver = \"" + prefs.getString(PREF_SELECTED_DRIVER, "auto")
                + "\"\nandroid_gpu_turbo = " + prefs.getBoolean(PREF_GPU_TURBO, false)
                + "\nandroid_gpu_driver_no_preamble = " + prefs.getBoolean(PREF_NO_PREAMBLE, true)
                + "\n\n[Graphics]\ndraw_resolution_scale_x = " + prefs.getInt(PREF_RES_SCALE, 1)
                + "\ndraw_resolution_scale_y = " + prefs.getInt(PREF_RES_SCALE, 1)
                + "\nvsync = " + prefs.getBoolean(PREF_VSYNC, true)
                + "\nframerate_limit = " + prefs.getInt(PREF_FPS_CAP, 60) + "\n";
        try (OutputStream out = new FileOutputStream(tomlFile)) {
            out.write(toml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try { startActivityForResult(intent, REQUEST_FOLDER); }
        catch (Exception e) { toast("No folder picker"); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQUEST_FOLDER) {
            final Uri tree = data.getData();
            busy = true;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            progress.setVisibility(View.VISIBLE);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        importFolder(tree);
                        finishOp("Game files imported!", true);
                    } catch (IOException e) {
                        finishOp("Import failed: " + e.getMessage(), false);
                    }
                }
            }, "FolderImport").start();
        } else if (req == REQUEST_DRIVER_ZIP) {
            final Uri uri = data.getData();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String n = PinyonShiftActivity.unpackDriver(getContentResolver(), driversDir, uri);
                        prefs.edit().putString(PREF_SELECTED_DRIVER, n).apply();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                refreshDriversList();
                                Toast.makeText(SetupActivity.this, "Driver imported & active!", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (IOException e) {
                        final String em = e.getMessage();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(SetupActivity.this, "Driver import failed: " + em, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            }, "ZipImport").start();
        }
    }

    private void finishOp(final String msg, final boolean ok) {
        busy = false;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (progress != null) {
                    progress.setVisibility(View.GONE);
                    if (ok) progress.setProgress(progress.getMax());
                }
                if (progressText != null) progressText.setText(msg);
                refreshAllTabs();
            }
        });
    }

    private static final class Entry {
        final Uri uri;
        final String path;
        final long size;
        Entry(Uri uri, String path, long size) {
            this.uri = uri;
            this.path = path;
            this.size = size;
        }
    }

    private void importFolder(Uri tree) throws IOException {
        String root = DocumentsContract.getTreeDocumentId(tree);
        List<Entry> files = new ArrayList<>();
        list(tree, root, "", files);
        boolean game = false;
        long total = 0;
        for (Entry e : files) {
            game |= e.path.equals("default.xex");
            total += e.size;
        }
        if (!game) throw new IOException("Folder holds no default.xex");
        if (total > base.getUsableSpace() - (256L << 20)) {
            throw new IOException("Not enough free space: " + size(total) + " needed");
        }
        File inc = new File(base, INCOMPLETE);
        inc.getParentFile().mkdirs();
        new FileOutputStream(inc).close();
        long done = 0;
        long started = System.currentTimeMillis();
        int cnt = files.size();
        for (int i = 0; i < files.size(); i++) {
            Entry e = files.get(i);
            File t = destination("game/base/" + e.path);
            if (t.length() != e.size) {
                File p = new File(t.getPath() + ".part");
                t.getParentFile().mkdirs();
                final long bf = done;
                final int num = i + 1;
                final long sm = total;
                final long st = started;
                final int cn = cnt;
                try (InputStream in = getContentResolver().openInputStream(e.uri);
                     OutputStream out = new FileOutputStream(p)) {
                    if (in == null) throw new IOException("Cannot read " + e.path);
                    copy(in, out, new Progress() {
                        @Override
                        public void written(long b) { report(bf + b, sm, num, cn, st); }
                    });
                }
                if (p.length() != e.size) throw new IOException("Incomplete " + e.path);
                if (t.exists() && !t.delete()) throw new IOException("Cannot replace " + t);
                if (!p.renameTo(t)) throw new IOException("Cannot write " + t);
            }
            done += e.size;
        }
        inc.delete();
    }

    private void list(Uri tree, String doc, String pre, List<Entry> out) throws IOException {
        Uri ch = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc);
        try (Cursor c = getContentResolver().query(ch, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c == null) throw new IOException("Cannot list folder");
            while (c.moveToNext()) {
                String id = c.getString(0);
                String nm = c.getString(1);
                if (nm == null || nm.isEmpty() || nm.equals(".") || nm.equals("..") || nm.contains("/")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    list(tree, id, pre + nm + "/", out);
                } else {
                    out.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), pre + nm, c.getLong(3)));
                }
            }
        }
    }

    private File destination(String path) throws IOException {
        if (!(path.startsWith("game/base/") || path.startsWith("state/")) || path.contains("\\")) {
            throw new IOException("unexpected path " + path);
        }
        for (String part : path.split("/")) if (part.equals("..")) throw new IOException("unexpected path " + path);
        File target = new File(base, path);
        if (!target.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) {
            throw new IOException("unexpected path " + path);
        }
        return target;
    }

    private void backUpSave() {
        File user = new File(base, "state/user");
        if (!user.isDirectory()) return;
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).format(new java.util.Date());
        File backup = new File(base, "state/backups/saves/" + stamp);
        try { PinyonShiftActivity.copyTree(user, backup); } catch (IOException ignored) {}
    }

    private static void copy(InputStream in, OutputStream out, Progress p) throws IOException {
        byte[] buf = new byte[1 << 17];
        long w = 0;
        for (int r; (r = in.read(buf)) > 0; ) {
            out.write(buf, 0, r);
            w += r;
            if (p != null) p.written(w);
        }
    }

    interface Progress { void written(long bytes); }

    private void report(final long done, final long total, final int num, final int cnt, final long st) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (progress != null) progress.setProgress(total > 0 ? (int) (done * 1000 / total) : 0);
                if (progressText != null) {
                    progressText.setText("Copying " + num + "/" + cnt + " (" + size(done) + " / " + size(total) + ")");
                }
            }
        });
    }

    private static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return (b / 1024) + " KB";
        if (b < 1073741824) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }

    private void startDiscovery() {
        discovering = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setSoTimeout(1500);
                    socket.setBroadcast(true);
                    byte[] payload = "PINYON-SHIFT-DISCOVER 1".getBytes(StandardCharsets.UTF_8);
                    while (discovering && server == null) {
                        for (InetAddress a : PinyonShiftActivity.broadcastAddresses()) {
                            try { socket.send(new DatagramPacket(payload, payload.length, a, DISCOVERY_PORT)); }
                            catch (IOException ignored) {}
                        }
                        byte[] buf = new byte[512];
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        try {
                            socket.receive(pkt);
                            String ans = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8).trim();
                            if (ans.startsWith("PINYON-SHIFT-HERE 1")) {
                                String[] pt = ans.split(" ");
                                int port = pt.length >= 3 ? Integer.parseInt(pt[2]) : HTTP_PORT;
                                final String found = pkt.getAddress().getHostAddress() + ":" + port;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (addressField != null && addressField.getText().length() == 0) addressField.setText(found);
                                        if (discoveryText != null) discoveryText.setText("Found PC: " + found);
                                    }
                                });
                                break;
                            }
                        } catch (SocketTimeoutException ignored) {}
                    }
                } catch (IOException ignored) {}
            }
        }, "PcDiscovery").start();
    }

    @Override
    public void onClick(View v) {
        if (busy) return;
        String raw = addressField.getText().toString().trim();
        if (raw.isEmpty()) {
            toast("Enter PC address");
            return;
        }
        if (!raw.contains(":")) raw += ":" + HTTP_PORT;
        server = raw;
        if (manifest == null) connect(server);
        else startCopy();
    }

    private void connect(final String hostPort) {
        busy = true;
        progressText.setText("Connecting to " + hostPort + "...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject m = fetchManifest(hostPort);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            manifest = m;
                            showGroups(m);
                        }
                    });
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            busy = false;
                            progressText.setText("Connection failed: " + e.getMessage());
                            Toast.makeText(SetupActivity.this, "Cannot connect: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "Connect").start();
    }

    private JSONObject fetchManifest(String hostPort) throws IOException {
        URL url = new URL("http://" + hostPort + "/pinyon-shift-manifest.json");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(4000);
        con.setReadTimeout(4000);
        try (InputStream in = con.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PinyonShiftActivity.copy(in, out);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("bad manifest json", e);
        }
    }

    private void showGroups(JSONObject m) {
        groupsBox.removeAllViews();
        groupChecks.clear();
        try {
            JSONArray groups = m.getJSONArray("groups");
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.getJSONObject(i);
                String id = g.getString("id");
                String name = g.getString("name");
                long bytes = g.getLong("bytes");
                CheckBox cb = new CheckBox(this);
                cb.setText(name + " (" + size(bytes) + ")");
                cb.setTextColor(Color.WHITE);
                cb.setChecked(true);
                groupChecks.put(id, cb);
                groupsBox.addView(cb);
            }
            copyButton.setText("Start Wi-Fi Transfer");
            progressText.setText("Ready to sync selected files from PC.");
        } catch (JSONException e) {
            progressText.setText("Failed parsing groups: " + e.getMessage());
        }
    }

    private void startCopy() {
        if (codeField.getText().toString().trim().length() != 6) {
            toast("Enter 6-digit pair code from PC");
            return;
        }
        final String code = codeField.getText().toString().trim();
        final List<String> selected = new ArrayList<>();
        for (Map.Entry<String, CheckBox> e : groupChecks.entrySet()) {
            if (e.getValue().isChecked()) selected.add(e.getKey());
        }
        if (selected.isEmpty()) {
            toast("Select at least one group");
            return;
        }
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        progress.setVisibility(View.VISIBLE);
        copyButton.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    copyFromPc(server, code, selected);
                    finishOp("Sync complete!", true);
                } catch (IOException e) {
                    finishOp("Sync failed: " + e.getMessage(), false);
                }
            }
        }, "WifiCopy").start();
    }

    private void copyFromPc(String hostPort, String code, List<String> groups) throws IOException {
        List<JSONObject> filesToCopy = new ArrayList<>();
        long totalBytes = 0;
        try {
            JSONArray all = manifest.getJSONArray("files");
            for (int i = 0; i < all.length(); i++) {
                JSONObject f = all.getJSONObject(i);
                if (groups.contains(f.getString("group"))) {
                    filesToCopy.add(f);
                    totalBytes += f.getLong("bytes");
                }
            }
        } catch (JSONException e) { throw new IOException("bad manifest files", e); }

        if (totalBytes > base.getUsableSpace() - (256L << 20)) {
            throw new IOException("Not enough free space on device");
        }

        backUpSave();

        long doneBytes = 0;
        long started = System.currentTimeMillis();
        int totalFiles = filesToCopy.size();
        for (int i = 0; i < totalFiles; i++) {
            JSONObject item = filesToCopy.get(i);
            String path;
            long size;
            try {
                path = item.getString("path");
                size = item.getLong("bytes");
            } catch (JSONException e) { continue; }

            File target = destination(path);
            if (target.length() != size) {
                File part = new File(target.getPath() + ".part");
                target.getParentFile().mkdirs();
                fetch(code, hostPort, path, part, doneBytes, totalBytes, i + 1, totalFiles, started);
                if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target);
                if (!part.renameTo(target)) throw new IOException("Cannot rename to " + target);
            }
            doneBytes += size;
        }
    }

    private void fetch(String code, String hostPort, String path, File part,
                       final long doneBefore, final long totalBytes,
                       final int fileNum, final int totalFiles, final long started) throws IOException {
        URL url = new URL("http://" + hostPort + "/fetch?path=" + Uri.encode(path));
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestProperty("X-Pinyon-Shift-Pair-Code", code);
        con.setConnectTimeout(6000);
        con.setReadTimeout(10000);
        if (con.getResponseCode() != 200) {
            throw new IOException("HTTP " + con.getResponseCode() + " on " + path);
        }
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            copy(in, out, new Progress() {
                @Override
                public void written(long b) {
                    report(doneBefore + b, totalBytes, fileNum, totalFiles, started);
                }
            });
        }
    }
}
