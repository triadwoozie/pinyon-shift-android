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

public class SetupActivity extends Activity implements View.OnClickListener, CompoundButton.OnCheckedChangeListener {
    private static final int HTTP_PORT = 47615, DISCOVERY_PORT = 47616, REQUEST_FOLDER = 0x5055, REQUEST_DRIVER_ZIP = 0x5056;
    private static final int COLOR_BG = 0xFF0F1210, COLOR_SURFACE = 0xFF141916, COLOR_CARD = 0xFF181D1A;
    private static final int COLOR_CARD_STROKE = 0xFF26302A, COLOR_HERO_STROKE = 0xFF2F4F3A, COLOR_ACCENT_DEFAULT = 0xFF5CE68B;
    private static final int COLOR_FOG = 0xFFEEF3EC, COLOR_MUTED = 0xFF86968B, COLOR_TEXT_DIM = 0xFF5D6B63, COLOR_SUCCESS = 0xFF5CE68B, COLOR_RED = 0xFFFF6B6B;
    private static final int MP = -1, WC = -2;

    private static final String INCOMPLETE = "game/.copy-incomplete", CONFIG_TOML = "state/config/pinyon-shift.toml", SETUP_ACTION = "studio.deimos.pinyonshift.SETUP";
    static final String ARGUMENTS_FILE = "state/config/android-arguments.txt";

    private static final String PREF_SELECTED_DRIVER = "selected_driver", PREF_ASPECT_MODE = "aspect_mode", PREF_RES_SCALE = "res_scale";
    private static final String PREF_VSYNC = "vsync", PREF_FPS_CAP = "fps_cap", PREF_GRAPHICS_PRESET = "graphics_preset", PREF_UPSCALER = "upscaler";
    private static final String PREF_CONTROL_SCHEME = "control_scheme", PREF_AUTO_THROTTLE = "auto_throttle", PREF_THERMAL_PACER = "thermal_pacer";
    private static final String PREF_HAPTICS = "haptics", PREF_TBDR_TILES = "tbdr_tiles", PREF_SKIP_MOVIES = "skip_movies", PREF_CLUB_OFFLINE = "club_offline";
    private static final String PREF_TREASURE_MAP = "treasure_map", PREF_GPU_TURBO = "gpu_turbo", PREF_NO_PREAMBLE = "no_preamble", PREF_AUTO_LAUNCH = "auto_launch";

    private File base, driversDir;
    private SharedPreferences prefs;
    private int colorAccent, currentTab, sysAccent;

    private FrameLayout tabContentContainer;
    private final List<View> tabPages = new ArrayList<>();
    private final List<LinearLayout> navItems = new ArrayList<>();

    private TextView gameTitleText, gameStatusText, activeDriverText, folderPathText, discoveryText, progressText;
    private TextView aspectModeSummary, resScaleSummary, fpsCapSummary, presetSummary, upscalerSummary, controlSummary, themeSummary, touchOpacitySummary;
    private LinearLayout statusPill, launchButton, driverListContainer, backupListContainer, activeBadgesLayout, groupsBox;
    private View statusDot;
    private ImageView launchIcon;
    private TextView launchText;

    private EditText addressField, codeField;
    private Button copyButton;
    private ProgressBar progress;
    private final Map<String, CheckBox> groupChecks = new LinkedHashMap<>();
    private JSONObject manifest;
    private String server;
    private volatile boolean busy, discovering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File external = getExternalFilesDir(null);
        base = external != null ? external : getFilesDir();
        driversDir = new File(base, "state/drivers");
        driversDir.mkdirs();
        prefs = getSharedPreferences("pinyon_shift_settings", MODE_PRIVATE);

        sysAccent = COLOR_ACCENT_DEFAULT;
        if (Build.VERSION.SDK_INT >= 31) {
            try { sysAccent = getResources().getColor(android.R.color.system_accent1_500, getTheme()); } catch (Throwable ignored) {}
        }
        colorAccent = prefs.getInt("theme_accent", sysAccent);

        boolean fromShortcut = SETUP_ACTION.equals(getIntent().getAction());
        if (!fromShortcut && prefs.getBoolean(PREF_AUTO_LAUNCH, false) && gameReady()) {
            startGame();
            return;
        }

        buildMaterial3Layout();
        int savedTab = prefs.getInt("active_tab", 0);
        if (savedTab >= 0 && savedTab < tabPages.size()) switchTab(savedTab);
        refreshAllTabs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        discovering = false;
    }

    private boolean gameReady() {
        return new File(base, "game/base/default.xex").isFile() && !new File(base, INCOMPLETE).exists();
    }

    private void startGame() {
        if (gameReady()) {
            applySettings();
            Intent intent = new Intent(this, PinyonShiftActivity.class);
            intent.putExtra("pinyon_shift_base", base.getAbsolutePath());
            startActivity(intent);
            finish();
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams lpWeight1() { return new LinearLayout.LayoutParams(0, WC, 1.0f); }

    private GradientDrawable cardBg(int color, float radiusDp) { return cardBg(color, radiusDp, 0, 0); }
    private GradientDrawable cardBg(int color, float radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private RippleDrawable ripple(int normal, int ripple, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(ripple), cardBg(normal, radiusDp), mask);
    }

    private LinearLayout createCard(LinearLayout parent, int paddingDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg(COLOR_CARD, 20, COLOR_CARD_STROKE, 1));
        int p = dp(paddingDp);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams l = lp(MP, WC);
        l.bottomMargin = dp(12);
        card.setLayoutParams(l);
        if (parent != null) parent.addView(card);
        return card;
    }

    private LinearLayout addSection(LinearLayout c, String title) {
        LinearLayout card = createCard(c, 16);
        card.addView(label(title, 14, Color.WHITE, true));
        return card;
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private EditText createField(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(COLOR_TEXT_DIM);
        et.setTextColor(Color.WHITE);
        et.setTextSize(13);
        et.setInputType(inputType);
        et.setBackground(cardBg(COLOR_SURFACE, 8, COLOR_CARD_STROKE, 1));
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        return et;
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(SetupActivity.this, msg, Toast.LENGTH_SHORT).show(); }
        });
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
        Drawable d = getSymbol(name);
        if (d != null) {
            d.setBounds(0, 0, dp(18), dp(18));
            d.setTint(tint);
        }
        if (b != null) {
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(8));
        }
    }

    private Button createM3Button(String text, boolean primary) {
        return createM3Button(text, null, primary);
    }

    private Button createM3Button(String text, String icon, boolean primary) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        int bg = primary ? colorAccent : COLOR_SURFACE;
        int fg = primary ? 0xFF0B1F11 : colorAccent;
        btn.setTextColor(fg);
        btn.setLetterSpacing(0.04f);
        btn.setBackground(ripple(bg, Color.argb(60, 255, 255, 255), 14));
        btn.setPadding(dp(16), dp(10), dp(16), dp(10));
        if (icon != null) setButtonIcon(btn, icon, fg);
        return btn;
    }

    private void buildMaterial3Layout() {
        LinearLayout root = new LinearLayout(this);
        root.setFitsSystemWindows(true);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setLayoutParams(lp(MP, MP));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.TRANSPARENT);
        header.setPadding(dp(20), dp(14), dp(20), dp(8));

        ImageView mark = new ImageView(this);
        Drawable mk = getSymbol("ic_launcher_foreground");
        if (mk != null) mark.setImageDrawable(mk);
        LinearLayout.LayoutParams mlp = lp(dp(28), dp(28));
        mlp.rightMargin = dp(10);
        header.addView(mark, mlp);

        TextView title = label("PINYON SHIFT", 20, Color.WHITE, true);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
        title.setLetterSpacing(0.08f);
        header.addView(title, lpWeight1());

        TextView verBadge = label("v0.5.1", 11, COLOR_MUTED, true);
        verBadge.setBackground(cardBg(0xFF1A241D, 10, COLOR_CARD_STROKE, 1));
        verBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        header.addView(verBadge);
        root.addView(header);

        tabContentContainer = new FrameLayout(this);
        LinearLayout.LayoutParams clp = lp(MP, 0);
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
        scroll.setLayoutParams(new FrameLayout.LayoutParams(MP, MP));
        int p = dp(16);
        content.setPadding(p, p, p, p);
        scroll.addView(content);
        return scroll;
    }

    private LinearLayout buildBottomNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(COLOR_SURFACE);
        bar.setElevation(dp(8));
        bar.setPadding(0, dp(4), 0, dp(4));

        navItems.clear();
        final String[] titles = {"Play", "Drivers", "Settings", "Content"};
        final String[] icons = {"ic_play_arrow", "ic_memory", "ic_settings", "ic_folder_open"};

        for (int i = 0; i < titles.length; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(2), 0, dp(2));

            FrameLayout pill = new FrameLayout(this);
            LinearLayout.LayoutParams pillLp = lp(dp(56), dp(28));
            pillLp.bottomMargin = dp(2);
            pill.setLayoutParams(pillLp);

            ImageView icon = new ImageView(this);
            Drawable d = getSymbol(icons[i]);
            if (d != null) icon.setImageDrawable(d);
            pill.addView(icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            item.addView(pill);

            TextView lbl = label(titles[i], 11, COLOR_MUTED, false);
            lbl.setGravity(Gravity.CENTER);
            item.addView(lbl);

            item.setTag(Integer.valueOf(i));
            item.setOnClickListener(this);

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
            boolean active = (i == currentTab);
            FrameLayout pill = (FrameLayout) item.getChildAt(0);
            pill.setBackground(active ? cardBg(0xFF1F3828, 14) : null);
            ImageView iv = (ImageView) pill.getChildAt(0);
            if (iv != null && iv.getDrawable() != null) {
                iv.getDrawable().mutate().setTint(active ? colorAccent : COLOR_MUTED);
            }
            TextView tv = (TextView) item.getChildAt(1);
            tv.setTextColor(active ? colorAccent : COLOR_MUTED);
            tv.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private LinearLayout createStatTile(String cat, String val, String tag) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(ripple(0xFF1A241D, 0x28FFFFFF, 12));
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView c = label(cat.toUpperCase(Locale.ROOT), 10, 0xFF86968B, true);
        c.setLetterSpacing(0.06f);
        t.addView(c);
        TextView v = label(val, 13, Color.WHITE, true);
        v.setPadding(0, dp(2), 0, 0);
        v.setMaxLines(1);
        t.addView(v);
        t.setTag(tag);
        t.setOnClickListener(this);
        return t;
    }

    private LinearLayout createTileRow(View v1, View v2) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams lp = lp(MP, WC);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        LinearLayout.LayoutParams p1 = lpWeight1();
        p1.rightMargin = v2 != null ? dp(8) : 0;
        row.addView(v1, p1);

        if (v2 != null) row.addView(v2, lpWeight1());
        return row;
    }

    private void addBadgeRow(String c1, String v1, String t1, String c2, String v2, String t2) {
        activeBadgesLayout.addView(createTileRow(createStatTile(c1, v1, t1), createStatTile(c2, v2, t2)));
    }

    private void addInfoCard(LinearLayout content, String title, String text, String badge, int badgeFg, int badgeBg) {
        LinearLayout c = createCard(content, 14);
        c.addView(label(title, 13, COLOR_MUTED, true));
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, 0);
        r.addView(label(text, 12, COLOR_FOG, false), lpWeight1());
        TextView b = label(badge, 11, badgeFg, true);
        b.setBackground(cardBg(badgeBg, 8, COLOR_CARD_STROKE, 1));
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        r.addView(b);
        c.addView(r);
    }

    private View buildPlayTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hero = createCard(content, 18);
        hero.setBackground(cardBg(COLOR_CARD, 20, COLOR_HERO_STROKE, 1));
        gameTitleText = label("Forza Horizon (2012)", 20, COLOR_FOG, true);
        hero.addView(gameTitleText);

        statusPill = new LinearLayout(this);
        statusPill.setGravity(Gravity.CENTER_VERTICAL);
        statusPill.setPadding(dp(8), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams spl = lp(WC, WC);
        spl.setMargins(0, dp(6), 0, dp(14));
        statusPill.setLayoutParams(spl);

        statusDot = new View(this);
        LinearLayout.LayoutParams dlp = lp(dp(8), dp(8));
        dlp.rightMargin = dp(6);
        statusPill.addView(statusDot, dlp);

        gameStatusText = label("", 11, COLOR_MUTED, true);
        statusPill.addView(gameStatusText);
        hero.addView(statusPill);

        launchButton = new LinearLayout(this);
        launchButton.setGravity(Gravity.CENTER);
        launchButton.setBackground(ripple(colorAccent, 0x3CFFFFFF, 14));
        launchButton.setPadding(dp(16), dp(11), dp(16), dp(11));
        launchButton.setTag("play");
        launchButton.setOnClickListener(this);

        launchIcon = new ImageView(this);
        LinearLayout.LayoutParams lip = lp(dp(20), dp(20));
        lip.rightMargin = dp(8);
        launchButton.addView(launchIcon, lip);

        launchText = label("Play", 15, 0xFF0B1F11, true);
        launchText.setLetterSpacing(0.04f);
        launchButton.addView(launchText);
        hero.addView(launchButton);

        LinearLayout badges = createCard(content, 14);
        TextView bt = label("Active Launch Profile", 13, COLOR_MUTED, true);
        bt.setPadding(0, 0, 0, dp(8));
        badges.addView(bt);

        activeBadgesLayout = new LinearLayout(this);
        activeBadgesLayout.setOrientation(LinearLayout.VERTICAL);
        badges.addView(activeBadgesLayout);

        String mfg = Build.MANUFACTURER;
        if (mfg != null && mfg.length() > 0) mfg = Character.toUpperCase(mfg.charAt(0)) + mfg.substring(1).toLowerCase(Locale.ROOT);
        String adreno = PinyonShiftActivity.adrenoModel();
        addInfoCard(content, "Hardware & Platform", (mfg != null ? mfg + " " : "") + Build.MODEL + " · Android " + Build.VERSION.RELEASE,
                adreno != null ? "Adreno " + adreno : "Vulkan GPU", colorAccent, 0xFF1A241D);
        addInfoCard(content, "Storage & Environment", "Free Storage: " + size(base.getUsableSpace()), "Save Protected ✓", COLOR_SUCCESS, 0xFF14281E);

        return wrapScrollable(content);
    }

    private View buildDriversTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = addSection(content, "Vulkan GPU Driver");
        activeDriverText = label("", 12, colorAccent, false);
        activeDriverText.setPadding(0, dp(4), 0, dp(12));
        card.addView(activeDriverText);

        driverListContainer = new LinearLayout(this);
        driverListContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(driverListContainer);

        Button importBtn = createM3Button("+  Import Driver (.zip / .adpkg)", false);
        importBtn.setTag("import_driver");
        importBtn.setOnClickListener(this);
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
        row.setGravity(Gravity.CENTER_VERTICAL);
        int bg = isSelected ? ((colorAccent & 0x00FFFFFF) | 0x28000000) : COLOR_SURFACE;
        int stroke = isSelected ? colorAccent : COLOR_CARD_STROKE;
        row.setBackground(cardBg(bg, 12, stroke, 1));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams l = lp(MP, WC);
        l.bottomMargin = dp(8);
        row.setLayoutParams(l);

        LinearLayout textGroup = new LinearLayout(this);
        textGroup.setOrientation(LinearLayout.VERTICAL);
        textGroup.addView(label(name + (isSelected ? "  ✓" : ""), 13, isSelected ? colorAccent : COLOR_FOG, true));
        textGroup.addView(label(desc, 11, COLOR_TEXT_DIM, false));
        row.addView(textGroup, lpWeight1());

        row.setTag("drv:" + id);
        row.setOnClickListener(this);

        if (!"auto".equals(id) && !"turnip-gen8-v37".equals(id)) {
            Button del = new Button(this);
            del.setText("✕");
            del.setTextColor(COLOR_RED);
            del.setBackgroundColor(Color.TRANSPARENT);
            del.setTag("del:" + id);
            del.setOnClickListener(this);
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
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try { startActivityForResult(intent, REQUEST_DRIVER_ZIP); }
        catch (Exception e) { toast("No file picker"); }
    }

    static final String[] ASPECT_ITEMS = {"Widescreen (Hor+)  [Recommended]", "Fit (16:9 Letterbox)", "Stretch (Fullscreen)", "Crop (Zoom)"}, ASPECT_VALS = {"hor_plus", "letterbox", "stretch", "crop"};
    static final String[] RES_ITEMS = {"1x · 720p Native", "1.5x · 1080p", "2x · 1440p"}; static final int[] RES_VALS = {1, 2, 2};
    static final String[] FPS_ITEMS = {"60 FPS", "45 FPS", "40 FPS", "30 FPS", "120 FPS", "Unlocked"}; static final int[] FPS_VALS = {60, 45, 40, 30, 120, 0};
    static final String[] PRESET_ITEMS = {"Potato (Max FPS)", "Balanced (Standard)", "Ultra (Full Visuals)"}, PRESET_VALS = {"potato", "balanced", "ultra"};
    static final String[] UPSCALER_ITEMS = {"AMD FSR 1.0 (Spatial)", "CAS (Adaptive Sharpening)", "Bilinear"}, UPSCALER_VALS = {"fsr", "cas", "bilinear"};
    static final String[] CTRL_ITEMS = {"Tilt A (RR3 Gyro)", "Tilt B (Touch Steer)", "Classic (Virtual Pad)", "Gamepad (External)"}, CTRL_VALS = {"tilt_a", "tilt_b", "classic", "gamepad"};
    static final String[] OPACITY_ITEMS = {"100% · Fully Visible", "75%", "50%", "25%", "Hidden · Clean HUD"}; static final int[] OPACITY_VALS = {100, 75, 50, 25, 0};

    private View buildSettingsTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout disp = addSection(content, "Display & Resolution");
        aspectModeSummary = addSettingSelector(disp, "Display Scaling Mode", "scaling");
        resScaleSummary = addSettingSelector(disp, "Internal Resolution", "res_scale");
        fpsCapSummary = addSettingSelector(disp, "Target Framerate", "fps");
        addSwitchRow(disp, "Vertical Sync (VSync)", "Lock to display refresh rate", PREF_VSYNC, true);
        presetSummary = addSettingSelector(disp, "Graphics Preset", "preset");
        upscalerSummary = addSettingSelector(disp, "Upscaler (FSR / CAS)", "upscaler");
        themeSummary = addSettingSelector(disp, "Theme Accent", "theme");

        LinearLayout mob = addSection(content, "Mobile Engine Optimizations");
        addSwitchRow(mob, "TBDR Tile Passes", "Tile memory preservation", PREF_TBDR_TILES, true);
        addSwitchRow(mob, "Thermal Frame Pacer", "Auto-throttle at >= 42°C", PREF_THERMAL_PACER, true);
        addSwitchRow(mob, "No Driver Preamble", "Skip preambles", PREF_NO_PREAMBLE, true);
        addSwitchRow(mob, "GPU Turbo Boost", "Aggressive governor", PREF_GPU_TURBO, false);

        LinearLayout ctrl = addSection(content, "Controls & Haptics");
        controlSummary = addSettingSelector(ctrl, "Control Scheme", "controls");
        touchOpacitySummary = addSettingSelector(ctrl, "Touch Controls Opacity", "touch_opacity");
        addSettingSelector(ctrl, "Touch Controls Guide", "touch_guide").setText("View 4-Layer Gesture Map");
        addSwitchRow(ctrl, "Steering Horizon", "Subtle tilt bar at bottom center", "touch_horizon", true);
        addSwitchRow(ctrl, "Auto-Throttle", "Auto acceleration (zones brake)", PREF_AUTO_THROTTLE, false);
        addSwitchRow(ctrl, "Haptic Feedback", "Vibration on shifts & pedals", PREF_HAPTICS, true);
        addSwitchRow(ctrl, "Smart Auto-Clutch", "Auto-blip LB on shifts", PinyonShiftActivity.PREF_SMART_CLUTCH, true);
        addSwitchRow(ctrl, "Center Gesture Shift", "Flick up/down to shift", PinyonShiftActivity.PREF_GESTURE_SHIFT, true);
        addSwitchRow(ctrl, "HUD Touch Hitboxes", "Minimap map & wristband pause", PinyonShiftActivity.PREF_HUD_HITBOXES, true);
        addSwitchRow(ctrl, "Menu Mode Auto-Switch", "Swipes = LB/RB tabs in menus", PinyonShiftActivity.PREF_MENU_MODE_AUTO, true);

        LinearLayout gm = addSection(content, "Game Features");
        addSwitchRow(gm, "Skip Opening Movies", "Skip intro splash videos", PREF_SKIP_MOVIES, true);
        addSwitchRow(gm, "1000 Club Offline", "Local challenge evaluation", PREF_CLUB_OFFLINE, true);
        addSwitchRow(gm, "Unlock Treasure Map", "Show all barn finds & signs", PREF_TREASURE_MAP, true);
        addSwitchRow(gm, "Auto-Boot Game", "Launch directly on app start", PREF_AUTO_LAUNCH, false);

        return wrapScrollable(content);
    }

    private TextView addSettingSelector(LinearLayout parent, String title, String tag) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ripple(COLOR_SURFACE, 0x28FFFFFF, 10));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams l = lp(MP, WC);
        l.topMargin = dp(6);
        row.setLayoutParams(l);

        LinearLayout tg = new LinearLayout(this);
        tg.setOrientation(LinearLayout.VERTICAL);
        tg.addView(label(title, 13, Color.WHITE, true));
        TextView vv = label("", 11, colorAccent, false);
        tg.addView(vv);
        row.addView(tg, lpWeight1());

        row.addView(label("›", 16, COLOR_TEXT_DIM, false));
        row.setTag(tag);
        row.setOnClickListener(this);
        parent.addView(row);
        return vv;
    }

    private void tintSwitch(Switch sw) {
        int[][] states = new int[][]{new int[]{android.R.attr.state_checked}, new int[]{-android.R.attr.state_checked}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{colorAccent, COLOR_TEXT_DIM}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{(colorAccent & 0x00FFFFFF) | 0x80000000, 0x3CFFFFFF}));
    }

    private void addSwitchRow(LinearLayout parent, String title, String sub, String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(6));

        LinearLayout tg = new LinearLayout(this);
        tg.setOrientation(LinearLayout.VERTICAL);
        tg.addView(label(title, 13, Color.WHITE, false));
        tg.addView(label(sub, 11, COLOR_TEXT_DIM, false));
        row.addView(tg, lpWeight1());

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setTag(key);
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(this);
        row.addView(sw);
        parent.addView(row);
    }

    @Override
    public void onCheckedChanged(CompoundButton b, boolean c) {
        String key = (String) b.getTag();
        if ("wifi_scan".equals(key)) {
            if (c) {
                if (discoveryText != null) discoveryText.setText("Searching for PC on local Wi-Fi...");
                startDiscovery();
            } else {
                discovering = false;
                if (discoveryText != null) discoveryText.setText("Wi-Fi discovery idle.");
            }
            return;
        }
        if (key != null) {
            prefs.edit().putBoolean(key, c).apply();
            applySettings();
        }
    }

    private void showDialog(String title, String[] items, int cur, DialogInterface.OnClickListener ok) {
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(items, cur, ok).setNegativeButton("Cancel", null).show();
    }

    private void showChoiceDialog(String title, final String prefKey, final String[] items, final String[] values) {
        String currentVal = prefs.getString(prefKey, values[0]);
        int cur = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(currentVal)) cur = i;
        showDialog(title, items, cur, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                prefs.edit().putString(prefKey, values[w]).apply();
                d.dismiss();
                refreshAllTabs();
            }
        });
    }

    private void showIntChoiceDialog(String title, final String prefKey, final String[] items, final int[] values) {
        int currentVal = prefs.getInt(prefKey, values[0]);
        int cur = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == currentVal) cur = i;
        showDialog(title, items, cur, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                prefs.edit().putInt(prefKey, values[w]).apply();
                d.dismiss();
                if ("theme_accent".equals(prefKey)) { recreate(); return; }
                refreshAllTabs();
            }
        });
    }

    private void handleTagClick(String tag) {
        if ("preset".equals(tag)) showChoiceDialog("Graphics Preset", PREF_GRAPHICS_PRESET, PRESET_ITEMS, PRESET_VALS);
        else if ("scaling".equals(tag)) showChoiceDialog("Display Scaling Mode", PREF_ASPECT_MODE, ASPECT_ITEMS, ASPECT_VALS);
        else if ("res_scale".equals(tag)) showIntChoiceDialog("Internal Resolution", PREF_RES_SCALE, RES_ITEMS, RES_VALS);
        else if ("fps".equals(tag)) showIntChoiceDialog("Target Framerate", PREF_FPS_CAP, FPS_ITEMS, FPS_VALS);
        else if ("upscaler".equals(tag)) showChoiceDialog("Upscaler (FSR / CAS)", PREF_UPSCALER, UPSCALER_ITEMS, UPSCALER_VALS);
        else if ("controls".equals(tag)) showChoiceDialog("Control Scheme", PREF_CONTROL_SCHEME, CTRL_ITEMS, CTRL_VALS);
        else if ("touch_opacity".equals(tag)) showIntChoiceDialog("Touch Controls Opacity", "touch_opacity", OPACITY_ITEMS, OPACITY_VALS);
        else if ("touch_guide".equals(tag)) PinyonShiftActivity.showTouchGuide(this);
        else if ("theme".equals(tag)) {
            showIntChoiceDialog("Theme Accent", "theme_accent",
                    new String[]{"Pinyon Green (Original PC)", "Horizon Amber", "Alpine White", "Cyber Cyan", "Sunset Orange", "System Dynamic (Material 3)"},
                    new int[]{COLOR_ACCENT_DEFAULT, 0xFFF1AE36, COLOR_FOG, 0xFF5CE1E6, 0xFFFF7043, sysAccent});
        }
        else if ("driver".equals(tag)) switchTab(1);
        else if ("dump".equals(tag)) switchTab(3);
        else if ("play".equals(tag)) {
            if (gameReady()) startGame();
            else switchTab(3);
        }
        else if ("import_driver".equals(tag)) importDriverZip();
        else if ("import_folder".equals(tag)) chooseFolder();
    }

    private static void setSummary(TextView v, String t) { if (v != null) v.setText(t); }

    private void refreshSettingsTab() {
        String asp = prefs.getString(PREF_ASPECT_MODE, "hor_plus");
        setSummary(aspectModeSummary, "hor_plus".equals(asp) ? "Widescreen (Hor+) · No Distortion"
                : "letterbox".equals(asp) ? "Fit (16:9 Letterbox)" : "stretch".equals(asp) ? "Stretch Fullscreen" : "Crop");
        setSummary(resScaleSummary, prefs.getInt(PREF_RES_SCALE, 1) == 2 ? "2x (1440p)" : "1x (720p Native)");
        int c = prefs.getInt(PREF_FPS_CAP, 60);
        setSummary(fpsCapSummary, c > 0 ? c + " FPS" : "Unlocked");
        String p = prefs.getString(PREF_GRAPHICS_PRESET, "balanced");
        setSummary(presetSummary, "potato".equals(p) ? "Potato (Max FPS)" : "ultra".equals(p) ? "Ultra" : "Balanced");
        String u = prefs.getString(PREF_UPSCALER, "fsr");
        setSummary(upscalerSummary, "fsr".equals(u) ? "AMD FSR 1.0" : "cas".equals(u) ? "CAS" : "Bilinear");
        setSummary(themeSummary, colorAccent == COLOR_ACCENT_DEFAULT ? "Pinyon Green (Original PC)"
                : colorAccent == 0xFFF1AE36 ? "Horizon Amber" : colorAccent == COLOR_FOG ? "Alpine White"
                : colorAccent == 0xFF5CE1E6 ? "Cyber Cyan" : colorAccent == 0xFFFF7043 ? "Sunset Orange" : "System Dynamic");
        String ctrl = prefs.getString(PREF_CONTROL_SCHEME, "tilt_a");
        setSummary(controlSummary, "tilt_a".equals(ctrl) ? "Tilt A (RR3 Gyro)" : "tilt_b".equals(ctrl) ? "Tilt B (RR3 Touch)" : "classic".equals(ctrl) ? "Classic" : "Gamepad");
        int op = prefs.getInt("touch_opacity", 100);
        setSummary(touchOpacitySummary, op == 0 ? "Hidden" : op + "%");
        applySettings();
    }

    private View buildContentTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout game = addSection(content, "Game Data Files");
        folderPathText = label("", 11, COLOR_TEXT_DIM, false);
        folderPathText.setPadding(0, dp(4), 0, dp(8));
        game.addView(folderPathText);

        Button importFolderBtn = createM3Button("Select Game Folder (default.xex)", "ic_folder_open", false);
        importFolderBtn.setTag("import_folder");
        importFolderBtn.setOnClickListener(this);
        game.addView(importFolderBtn);

        LinearLayout wifi = addSection(content, "Sync from PC (Wi-Fi)");
        Switch scanSw = new Switch(this);
        scanSw.setText("Scan for PC on Wi-Fi");
        scanSw.setTextColor(Color.WHITE);
        scanSw.setTextSize(13);
        tintSwitch(scanSw);
        scanSw.setTag("wifi_scan");
        scanSw.setOnCheckedChangeListener(this);
        wifi.addView(scanSw);

        discoveryText = label("Wi-Fi discovery idle.", 11, COLOR_MUTED, false);
        discoveryText.setPadding(0, dp(4), 0, dp(8));
        wifi.addView(discoveryText);

        addressField = createField("PC Address (e.g. 192.168.1.50:47615)", InputType.TYPE_CLASS_TEXT);
        wifi.addView(addressField);

        codeField = createField("6-digit Pair Code from PC screen", InputType.TYPE_CLASS_NUMBER);
        codeField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        LinearLayout.LayoutParams clp = lp(MP, WC);
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
        LinearLayout.LayoutParams plp = lp(MP, dp(8));
        plp.setMargins(0, dp(12), 0, dp(4));
        progress.setLayoutParams(plp);
        wifi.addView(progress);

        progressText = label("", 11, COLOR_TEXT_DIM, false);
        wifi.addView(progressText);

        LinearLayout backupCard = addSection(content, "Save Backups");
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
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            row.addView(label(s.getName(), 12, Color.WHITE, false), lpWeight1());

            Button rst = new Button(this);
            rst.setText("Restore");
            rst.setTextSize(11);
            rst.setTextColor(colorAccent);
            rst.setBackgroundColor(Color.TRANSPARENT);
            rst.setTag(s);
            rst.setOnClickListener(this);
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
        if (gameTitleText != null) gameTitleText.setText(ready ? "Forza Horizon (2012)" : "Set up your copy");
        if (statusPill != null && statusDot != null && gameStatusText != null) {
            statusPill.setBackground(cardBg(ready ? 0xFF14281E : 0xFF2A1618, 6));
            statusDot.setBackground(cardBg(ready ? 0xFF5CE68B : 0xFFFF6B6B, 4));
            gameStatusText.setText(ready ? "Game Files Verified · Ready to Drive" : "Game Files Missing (default.xex)");
            gameStatusText.setTextColor(ready ? 0xFF5CE68B : 0xFFFF6B6B);
        }
        if (launchButton != null) {
            launchText.setText(ready ? "Play" : "IMPORT GAME FILES");
            Drawable d = getSymbol(ready ? "ic_play_arrow" : "ic_folder_open");
            if (d != null) {
                d.setTint(0xFF0B1F11);
                launchIcon.setImageDrawable(d);
                launchIcon.setVisibility(View.VISIBLE);
            } else {
                launchIcon.setVisibility(View.GONE);
            }
        }
        if (activeBadgesLayout != null) {
            activeBadgesLayout.removeAllViews();
            String aspect = prefs.getString(PREF_ASPECT_MODE, "hor_plus");
            String driver = prefs.getString(PREF_SELECTED_DRIVER, "auto");
            String preset = prefs.getString(PREF_GRAPHICS_PRESET, "balanced");
            String upscaler = prefs.getString(PREF_UPSCALER, "fsr");
            String ctrl = prefs.getString(PREF_CONTROL_SCHEME, "tilt_a");
            boolean vsync = prefs.getBoolean(PREF_VSYNC, true);
            int fps = prefs.getInt(PREF_FPS_CAP, 60);

            String presetStr = ("potato".equals(preset) ? "Potato" : "ultra".equals(preset) ? "Ultra" : "Balanced")
                    + " · " + upscaler.toUpperCase(Locale.ROOT);
            String scalingStr = "hor_plus".equals(aspect) ? "Widescreen (Hor+)" : aspect;
            String fpsStr = (fps > 0 ? fps + " FPS" : "Unlocked") + " · VSync " + (vsync ? "ON" : "OFF");
            String driverStr = "auto".equals(driver) ? "Turnip (Auto)" : driver;
            String ctrlStr = "tilt_a".equals(ctrl) ? "RR3 Tilt A" : "tilt_b".equals(ctrl) ? "RR3 Tilt B" : ctrl;

            File xex = new File(base, "game/base/default.xex");
            boolean isRetail = xex.isFile() && (xex.length() == PinyonShiftActivity.MS_2505_RETAIL_XEX_SIZE);
            String dataStr = !xex.isFile() ? "Missing" : isRetail ? "MS-2505 Verified ✓" : size(xex.length());

            addBadgeRow("Preset", presetStr, "preset", "Display Scaling", scalingStr, "scaling");
            addBadgeRow("Framerate", fpsStr, "fps", "Driver", driverStr, "driver");
            addBadgeRow("Controls", ctrlStr, "controls", "Game Dump", dataStr, "dump");
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
        args.add("--vsync=" + prefs.getBoolean(PREF_VSYNC, true));
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

        File configFile = new File(base, CONFIG_TOML);
        configFile.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(configFile)) {
            String toml = "[display]\nscaling = \"" + prefs.getString(PREF_ASPECT_MODE, "hor_plus") + "\"\nvsync = "
                    + prefs.getBoolean(PREF_VSYNC, true) + "\nfps_limit = " + prefs.getInt(PREF_FPS_CAP, 60)
                    + "\n\n[touch]\nscheme = \"" + prefs.getString(PREF_CONTROL_SCHEME, "tilt_a")
                    + "\"\nopacity = " + prefs.getInt("touch_opacity", 100)
                    + "\nhorizon = " + prefs.getBoolean("touch_horizon", true)
                    + "\nhaptics = " + prefs.getBoolean(PREF_HAPTICS, true) + "\n";
            out.write(toml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_FOLDER); }
        catch (Exception e) { toast("No document tree picker"); }
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
                            public void run() { refreshDriversList(); }
                        });
                        toast("Driver imported & active!");
                    } catch (IOException e) {
                        toast("Driver import failed: " + e.getMessage());
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
        int cnt = files.size();
        for (int i = 0; i < files.size(); i++) {
            Entry e = files.get(i);
            File t = destination("game/base/" + e.path);
            if (t.length() != e.size) {
                File p = new File(t.getPath() + ".part");
                t.getParentFile().mkdirs();
                final long bf = done, sm = total;
                final int num = i + 1, cn = cnt;
                try (InputStream in = getContentResolver().openInputStream(e.uri);
                     OutputStream out = new FileOutputStream(p)) {
                    if (in == null) throw new IOException("Cannot read " + e.path);
                    copy(in, out, new Progress() {
                        @Override
                        public void written(long b) { report(bf + b, sm, num, cn); }
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
        if (!(path.startsWith("game/base/") || path.startsWith("state/")) || path.contains("\\")) throw new IOException("unexpected path " + path);
        for (String part : path.split("/")) if (part.equals("..")) throw new IOException("unexpected path " + path);
        File target = new File(base, path);
        if (!target.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) throw new IOException("unexpected path " + path);
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

    private void report(final long done, final long total, final int num, final int cnt) {
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
        Object tag = v.getTag();
        if (tag instanceof Integer) {
            switchTab(((Integer) tag).intValue());
            return;
        }
        if (tag instanceof File) {
            restoreSave((File) tag);
            return;
        }
        if (tag instanceof String) {
            String s = (String) tag;
            if (s.startsWith("drv:")) {
                prefs.edit().putString(PREF_SELECTED_DRIVER, s.substring(4)).apply();
                refreshDriversList();
                applySettings();
            } else if (s.startsWith("del:")) {
                deleteDriver(s.substring(4));
            } else {
                handleTagClick(s);
            }
            return;
        }
        if (v == copyButton) {
            if (busy) return;
            String raw = addressField.getText().toString().trim();
            if (raw.isEmpty()) { toast("Enter PC address"); return; }
            if (!raw.contains(":")) raw += ":" + HTTP_PORT;
            server = raw;
            if (manifest == null) connect(server);
            else startCopy();
        }
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
                } catch (IOException e) {
                    busy = false;
                    toast("Cannot connect: " + e.getMessage());
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
                CheckBox cb = new CheckBox(this);
                cb.setText(g.getString("name") + " (" + size(g.getLong("bytes")) + ")");
                cb.setTextColor(Color.WHITE);
                cb.setChecked(true);
                groupChecks.put(g.getString("id"), cb);
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
                fetch(code, hostPort, path, part, doneBytes, totalBytes, i + 1, totalFiles);
                if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target);
                if (!part.renameTo(target)) throw new IOException("Cannot rename to " + target);
            }
            doneBytes += size;
        }
    }

    private void fetch(String code, String hostPort, String path, File part,
                       final long doneBefore, final long totalBytes,
                       final int fileNum, final int totalFiles) throws IOException {
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
                    report(doneBefore + b, totalBytes, fileNum, totalFiles);
                }
            });
        }
    }
}
