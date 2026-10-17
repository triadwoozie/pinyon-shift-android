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
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
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
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Standalone Mobile Hub and Launcher for Pinyon Shift (Android).
 *
 * Full feature parity with the desktop launcher directly on device:
 * - Standalone game file import from device storage (or PC Wi-Fi sync)
 * - In-launcher GPU Driver Swapper (Mesa Turnip Gen8, System Vulkan, Custom ZIPs)
 * - Display Scaling & Native Hor+ Widescreen scaling for mobile aspect ratios
 * - Graphics and resolution scaling (720p, 1080p, 1440p)
 * - Save data backup, restore and management
 * - Material 3 mobile interface honoring the original Horizon aesthetic
 */
public class SetupActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "PinyonShiftSetup";
    private static final int HTTP_PORT = 47615;
    private static final int DISCOVERY_PORT = 47616;
    private static final int REQUEST_FOLDER = 0x5055;
    private static final int REQUEST_DRIVER_ZIP = 0x5056;

    private static final int COLOR_BG = Color.rgb(16, 18, 21);
    private static final int COLOR_SURFACE = Color.rgb(25, 28, 33);
    private static final int COLOR_CARD = Color.rgb(32, 36, 44);
    private static final int COLOR_CARD_STROKE = Color.rgb(44, 49, 59);
    private static final int COLOR_ACCENT = Color.rgb(241, 174, 54);
    private static final int COLOR_MUTED = Color.rgb(159, 178, 165);
    private static final int COLOR_TEXT_DIM = Color.rgb(160, 165, 176);
    private static final int COLOR_SUCCESS = Color.rgb(76, 175, 80);

    private static final String INCOMPLETE = "game/.copy-incomplete";
    static final String ARGUMENTS_FILE = "state/config/android-arguments.txt";
    private static final String CONFIG_TOML = "state/config/pinyon-shift.toml";
    private static final String SETUP_ACTION = "studio.deimos.pinyonshift.SETUP";
    private static final String PREF_SELECTED_DRIVER = "selected_driver";
    private static final String PREF_ASPECT_MODE = "aspect_mode";
    private static final String PREF_RES_SCALE = "res_scale";
    private static final String PREF_VSYNC = "vsync";
    private static final String PREF_FPS_CAP = "fps_cap";
    private static final String PREF_SKIP_MOVIES = "skip_movies";
    private static final String PREF_CLUB_OFFLINE = "club_offline";
    private static final String PREF_TREASURE_MAP = "treasure_map";
    private static final String PREF_GPU_TURBO = "gpu_turbo";
    private static final String PREF_NO_PREAMBLE = "no_preamble";
    private static final String PREF_AUTO_LAUNCH = "auto_launch";

    private File base;
    private File driversDir;
    private SharedPreferences prefs;

    private int currentTab = 0;
    private FrameLayout tabContentContainer;
    private final List<View> tabPages = new ArrayList<>();
    private final List<LinearLayout> navItems = new ArrayList<>();

    private TextView gameStatusText;
    private Button launchButton;
    private LinearLayout driverListContainer;
    private TextView activeDriverText;
    private TextView aspectModeSummary;
    private TextView resScaleSummary;
    private TextView fpsCapSummary;
    private TextView folderPathText;
    private LinearLayout backupListContainer;

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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(lp);
        if (parent != null) parent.addView(card);
        return card;
    }

    private Button createM3Button(String text, boolean primary) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        int bg = primary ? COLOR_ACCENT : COLOR_SURFACE;
        btn.setTextColor(primary ? Color.BLACK : COLOR_ACCENT);
        btn.setBackground(rippleDrawable(bg, Color.argb(60, 255, 255, 255), 14));
        btn.setPadding(dp(16), dp(10), dp(16), dp(10));
        return btn;
    }

    private void buildMaterial3Layout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(10));
        header.setBackgroundColor(COLOR_SURFACE);

        TextView title = new TextView(this);
        title.setText("PINYON SHIFT");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_ACCENT);

        TextView sub = new TextView(this);
        sub.setText(" · FH1");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(COLOR_MUTED);

        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.HORIZONTAL);
        titleGroup.setGravity(Gravity.CENTER_VERTICAL);
        titleGroup.addView(title);
        titleGroup.addView(sub);
        header.addView(titleGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        String adreno = PinyonShiftActivity.adrenoModel();
        String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE;
        TextView chip = new TextView(this);
        chip.setText((adreno != null ? "Adreno " + adreno : "Vulkan") + (soc != null ? " · " + soc : ""));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        chip.setTextColor(COLOR_TEXT_DIM);
        chip.setBackground(cardBackground(COLOR_CARD, 10, COLOR_CARD_STROKE, 1));
        chip.setPadding(dp(8), dp(4), dp(8), dp(4));
        header.addView(chip);
        root.addView(header);

        tabContentContainer = new FrameLayout(this);
        root.addView(tabContentContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        tabPages.add(buildPlayTab());
        tabPages.add(buildDriversTab());
        tabPages.add(buildSettingsTab());
        tabPages.add(buildContentTab());

        for (int i = 0; i < tabPages.size(); i++) {
            View page = tabPages.get(i);
            page.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            tabContentContainer.addView(page);
        }

        root.addView(buildBottomNavBar());
        setContentView(root);
    }

    private View wrapScrollable(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        int p = dp(14);
        content.setPadding(p, p, p, dp(24));
        scroll.addView(content);
        return scroll;
    }

    private View buildBottomNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(COLOR_SURFACE);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));

        String[] labels = {"Play", "Drivers", "Settings", "Content"};
        String[] icons = {"▶", "⚙", "✦", "📁"};

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setBackground(rippleDrawable(Color.TRANSPARENT, Color.argb(40, 255, 255, 255), 12));
            item.setPadding(0, dp(4), 0, dp(4));

            TextView iv = new TextView(this);
            iv.setText(icons[i]);
            iv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            iv.setGravity(Gravity.CENTER);

            TextView lv = new TextView(this);
            lv.setText(labels[i]);
            lv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            lv.setTypeface(Typeface.DEFAULT_BOLD);
            lv.setGravity(Gravity.CENTER);

            item.addView(iv);
            item.addView(lv);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { switchTab(idx); }
            });
            navItems.add(item);
            bar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        }
        updateNavSelection();
        return bar;
    }

    private void switchTab(int index) {
        if (index == currentTab || index < 0 || index >= tabPages.size()) return;
        tabPages.get(currentTab).setVisibility(View.GONE);
        currentTab = index;
        tabPages.get(currentTab).setVisibility(View.VISIBLE);
        updateNavSelection();
        refreshAllTabs();
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void updateNavSelection() {
        for (int i = 0; i < navItems.size(); i++) {
            LinearLayout item = navItems.get(i);
            int color = (i == currentTab) ? COLOR_ACCENT : COLOR_TEXT_DIM;
            ((TextView) item.getChildAt(0)).setTextColor(color);
            ((TextView) item.getChildAt(1)).setTextColor(color);
        }
    }

    private View buildPlayTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout hero = createCard(content, 18);
        hero.setBackground(cardBackground(COLOR_CARD, 18, COLOR_ACCENT, 1));

        TextView ht = new TextView(this);
        ht.setText("Forza Horizon (2012)");
        ht.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        ht.setTypeface(Typeface.DEFAULT_BOLD);
        ht.setTextColor(Color.WHITE);
        hero.addView(ht);

        gameStatusText = new TextView(this);
        gameStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        gameStatusText.setPadding(0, dp(4), 0, dp(14));
        hero.addView(gameStatusText);

        launchButton = createM3Button("▶  LAUNCH GAME", true);
        launchButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        launchButton.setPadding(dp(16), dp(14), dp(16), dp(14));
        launchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (gameReady()) startGame();
                else {
                    switchTab(3);
                    Toast.makeText(SetupActivity.this, "Import game files first", Toast.LENGTH_SHORT).show();
                }
            }
        });
        hero.addView(launchButton);

        LinearLayout badges = createCard(content, 14);
        TextView bt = new TextView(this);
        bt.setText("Active Launch Profile");
        bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bt.setTypeface(Typeface.DEFAULT_BOLD);
        bt.setTextColor(COLOR_MUTED);
        bt.setPadding(0, 0, 0, dp(8));
        badges.addView(bt);

        String aspect = prefs.getString(PREF_ASPECT_MODE, "hor_plus");
        String driver = prefs.getString(PREF_SELECTED_DRIVER, "auto");
        TextView b1 = createBadgeView("Driver: " + driver);
        TextView b2 = createBadgeView("Scaling: " + ("hor_plus".equals(aspect) ? "Widescreen Hor+" : aspect));
        badges.addView(b1);
        badges.addView(b2);

        LinearLayout specs = createCard(content, 14);
        TextView st = new TextView(this);
        st.setText("Hardware");
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        st.setTypeface(Typeface.DEFAULT_BOLD);
        st.setTextColor(COLOR_MUTED);
        specs.addView(st);

        TextView sb = new TextView(this);
        String adreno = PinyonShiftActivity.adrenoModel();
        sb.setText(Build.MANUFACTURER + " " + Build.MODEL + " · "
                + (adreno != null ? "Adreno " + adreno : "GPU") + " · Android " + Build.VERSION.RELEASE);
        sb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sb.setTextColor(COLOR_TEXT_DIM);
        specs.addView(sb);

        return wrapScrollable(content);
    }

    private TextView createBadgeView(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(Color.WHITE);
        b.setBackground(cardBackground(COLOR_SURFACE, 8, COLOR_CARD_STROKE, 1));
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private View buildDriversTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = createCard(content, 16);
        TextView al = new TextView(this);
        al.setText("Active GPU Driver");
        al.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        al.setTextColor(COLOR_MUTED);
        card.addView(al);

        activeDriverText = new TextView(this);
        activeDriverText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        activeDriverText.setTypeface(Typeface.DEFAULT_BOLD);
        activeDriverText.setTextColor(COLOR_ACCENT);
        activeDriverText.setPadding(0, dp(2), 0, dp(6));
        card.addView(activeDriverText);

        String adreno = PinyonShiftActivity.adrenoModel();
        String rec = PinyonShiftActivity.recommendedDriver(adreno);
        TextView rl = new TextView(this);
        rl.setText("Recommended: " + (rec != null ? rec : "System Default"));
        rl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        rl.setTextColor(COLOR_TEXT_DIM);
        card.addView(rl);

        Button importBtn = createM3Button("+ Import Driver (.zip)", false);
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { importDriverZip(); }
        });
        card.addView(importBtn);

        LinearLayout listCard = createCard(content, 16);
        TextView lt = new TextView(this);
        lt.setText("Available Drivers");
        lt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        lt.setTypeface(Typeface.DEFAULT_BOLD);
        lt.setTextColor(Color.WHITE);
        listCard.addView(lt);

        driverListContainer = new LinearLayout(this);
        driverListContainer.setOrientation(LinearLayout.VERTICAL);
        driverListContainer.setPadding(0, dp(8), 0, 0);
        listCard.addView(driverListContainer);

        LinearLayout tweaks = createCard(content, 16);
        TextView tt = new TextView(this);
        tt.setText("Driver Optimizations");
        tt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tt.setTypeface(Typeface.DEFAULT_BOLD);
        tt.setTextColor(Color.WHITE);
        tweaks.addView(tt);

        addSwitchRow(tweaks, "GPU Turbo Clocks", "Max Adreno clocks", PREF_GPU_TURBO, false);
        addSwitchRow(tweaks, "No-Preamble Shaders", "Lower draw latency in Turnip", PREF_NO_PREAMBLE, true);

        return wrapScrollable(content);
    }

    private void refreshDriversList() {
        if (driverListContainer == null) return;
        driverListContainer.removeAllViews();
        String sel = prefs.getString(PREF_SELECTED_DRIVER, "auto");
        String adreno = PinyonShiftActivity.adrenoModel();
        String rec = PinyonShiftActivity.recommendedDriver(adreno);

        if (activeDriverText != null) {
            activeDriverText.setText("auto".equals(sel) ? "Auto (" + (rec != null ? rec : "System") + ")"
                    : "".equals(sel) ? "System Default" : sel);
        }

        addDriverItem("Auto (Recommended)", "auto", "Best driver for GPU", "auto".equals(sel), false);
        addDriverItem("System Default", "", "Device vendor Vulkan", "".equals(sel), false);

        File[] found = driversDir.listFiles();
        if (found != null) {
            Arrays.sort(found);
            for (File d : found) {
                if (!d.isDirectory() || d.getName().startsWith(".")) continue;
                String n = d.getName();
                boolean b = n.equals("turnip-gen8-v37");
                addDriverItem(n, n, b ? "Mesa Turnip Gen8 v37" : "Custom Driver", n.equals(sel), !b);
            }
        }
    }

    private void addDriverItem(final String name, final String id, String desc, boolean sel, boolean canDel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(cardBackground(sel ? Color.rgb(38, 46, 56) : COLOR_SURFACE, 10,
                sel ? COLOR_ACCENT : COLOR_CARD_STROKE, 1));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, 0, 0, dp(8));
        row.setLayoutParams(rlp);

        LinearLayout tg = new LinearLayout(this);
        tg.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(this);
        tv.setText((sel ? "● " : "○ ") + name);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(sel ? COLOR_ACCENT : Color.WHITE);
        tg.addView(tv);

        TextView dv = new TextView(this);
        dv.setText(desc);
        dv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        dv.setTextColor(COLOR_TEXT_DIM);
        tg.addView(dv);
        row.addView(tg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        if (canDel) {
            Button del = new Button(this);
            del.setText("✕");
            del.setTextColor(Color.rgb(244, 67, 54));
            del.setBackground(rippleDrawable(Color.TRANSPARENT, Color.argb(40, 244, 67, 54), 8));
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { deleteDriver(id); }
            });
            row.addView(del);
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putString(PREF_SELECTED_DRIVER, id).apply();
                refreshDriversList();
            }
        });
        driverListContainer.addView(row);
    }

    private void deleteDriver(final String name) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Driver")
                .setMessage("Remove '" + name + "'?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        deleteTree(new File(driversDir, name));
                        if (name.equals(prefs.getString(PREF_SELECTED_DRIVER, ""))) {
                            prefs.edit().putString(PREF_SELECTED_DRIVER, "auto").apply();
                        }
                        refreshDriversList();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void importDriverZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try { startActivityForResult(intent, REQUEST_DRIVER_ZIP); }
        catch (Exception e) { Toast.makeText(this, "No file picker", Toast.LENGTH_SHORT).show(); }
    }

    private View buildSettingsTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout disp = createCard(content, 16);
        TextView dt = new TextView(this);
        dt.setText("Display & Resolution");
        dt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        dt.setTypeface(Typeface.DEFAULT_BOLD);
        dt.setTextColor(Color.WHITE);
        disp.addView(dt);

        aspectModeSummary = addSettingSelector(disp, "Display Scaling Mode", "Widescreen (Hor+)",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { showAspectDialog(); }
                });

        resScaleSummary = addSettingSelector(disp, "Internal Resolution", "1x (720p)",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { showResDialog(); }
                });

        fpsCapSummary = addSettingSelector(disp, "Framerate & Sync", "60 FPS (VSync)",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { showFpsDialog(); }
                });

        LinearLayout game = createCard(content, 16);
        TextView gt = new TextView(this);
        gt.setText("Game Features");
        gt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        gt.setTypeface(Typeface.DEFAULT_BOLD);
        gt.setTextColor(Color.WHITE);
        game.addView(gt);

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
        row.setBackground(rippleDrawable(COLOR_SURFACE, Color.argb(40, 255, 255, 255), 10));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(lp);

        LinearLayout tg = new LinearLayout(this);
        tg.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.WHITE);
        tg.addView(tv);

        TextView vv = new TextView(this);
        vv.setText(val);
        vv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        vv.setTextColor(COLOR_ACCENT);
        tg.addView(vv);
        row.addView(tg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView arr = new TextView(this);
        arr.setText("›");
        arr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        arr.setTextColor(COLOR_TEXT_DIM);
        row.addView(arr);

        row.setOnClickListener(clk);
        parent.addView(row);
        return vv;
    }

    private void addSwitchRow(LinearLayout parent, String title, String sub, final String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(6));

        LinearLayout tg = new LinearLayout(this);
        tg.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(Color.WHITE);
        tg.addView(tv);

        TextView sv = new TextView(this);
        sv.setText(sub);
        sv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sv.setTextColor(COLOR_TEXT_DIM);
        tg.addView(sv);
        row.addView(tg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
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

    private void showAspectDialog() {
        final String[] m = {"Widescreen (Hor+)  [Recommended]\nWidens FOV to match phone screen with no distortion",
                "Fit (16:9 Letterbox)\nStandard 16:9 with pillarboxes",
                "Stretch (Fullscreen)\nStretches 16:9 output",
                "Crop (Zoom)\nCrops into overscan"};
        final String[] k = {"hor_plus", "letterbox", "stretch", "crop"};
        int cur = 0;
        String ck = prefs.getString(PREF_ASPECT_MODE, "hor_plus");
        for (int i = 0; i < k.length; i++) if (k[i].equals(ck)) cur = i;

        new AlertDialog.Builder(this)
                .setTitle("Display Scaling Mode")
                .setSingleChoiceItems(m, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putString(PREF_ASPECT_MODE, k[w]).apply();
                        d.dismiss();
                        refreshSettingsTab();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showResDialog() {
        final String[] res = {"1x · 720p Native (1280x720) [Best battery]",
                "1.5x · 1080p (1920x1080) [Crisp visuals]",
                "2x · 1440p (2560x1440) [Quad HD]"};
        final int[] s = {1, 2, 2};
        int cur = prefs.getInt(PREF_RES_SCALE, 1) == 2 ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Resolution Scaling")
                .setSingleChoiceItems(res, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putInt(PREF_RES_SCALE, w == 0 ? 1 : 2).apply();
                        d.dismiss();
                        refreshSettingsTab();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFpsDialog() {
        final String[] f = {"60 FPS (VSync)", "30 FPS (Battery saver)", "Unlocked (VRR)"};
        final int[] caps = {60, 30, 0};
        int cap = prefs.getInt(PREF_FPS_CAP, 60);
        int cur = cap == 30 ? 1 : (cap == 0 ? 2 : 0);

        new AlertDialog.Builder(this)
                .setTitle("Framerate & Sync")
                .setSingleChoiceItems(f, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putInt(PREF_FPS_CAP, caps[w]).putBoolean(PREF_VSYNC, caps[w] != 0).apply();
                        d.dismiss();
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
            fpsCapSummary.setText(c > 0 ? c + " FPS (VSync)" : "Unlocked");
        }
        applySettings();
    }

    private View buildContentTab() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout game = createCard(content, 16);
        TextView gt = new TextView(this);
        gt.setText("Game Data Files");
        gt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        gt.setTypeface(Typeface.DEFAULT_BOLD);
        gt.setTextColor(Color.WHITE);
        game.addView(gt);

        folderPathText = new TextView(this);
        folderPathText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        folderPathText.setTextColor(COLOR_TEXT_DIM);
        folderPathText.setPadding(0, dp(4), 0, dp(8));
        game.addView(folderPathText);

        Button ib = createM3Button("Choose Extracted Game Folder", true);
        ib.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { chooseFolder(); }
        });
        game.addView(ib);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        game.addView(progress);

        progressText = new TextView(this);
        progressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        progressText.setTextColor(COLOR_MUTED);
        game.addView(progressText);

        LinearLayout save = createCard(content, 16);
        TextView st = new TextView(this);
        st.setText("Save Backups & Profile");
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        st.setTypeface(Typeface.DEFAULT_BOLD);
        st.setTextColor(Color.WHITE);
        save.addView(st);

        Button bb = createM3Button("Backup Current Save", false);
        bb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backUpSave();
                refreshBackupsList();
                Toast.makeText(SetupActivity.this, "Save backed up", Toast.LENGTH_SHORT).show();
            }
        });
        save.addView(bb);

        backupListContainer = new LinearLayout(this);
        backupListContainer.setOrientation(LinearLayout.VERTICAL);
        backupListContainer.setPadding(0, dp(6), 0, 0);
        save.addView(backupListContainer);

        LinearLayout pc = createCard(content, 16);
        TextView pt = new TextView(this);
        pt.setText("Wi-Fi Transfer from PC (Optional)");
        pt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pt.setTypeface(Typeface.DEFAULT_BOLD);
        pt.setTextColor(Color.WHITE);
        pc.addView(pt);

        discoveryText = new TextView(this);
        discoveryText.setText("Looking for PC on local network…");
        discoveryText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        discoveryText.setTextColor(COLOR_TEXT_DIM);
        discoveryText.setPadding(0, dp(2), 0, dp(6));
        pc.addView(discoveryText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        addressField = new EditText(this);
        addressField.setHint("PC IP:Port");
        addressField.setTextColor(Color.WHITE);
        addressField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        addressField.setBackground(cardBackground(COLOR_SURFACE, 8, COLOR_CARD_STROKE, 1));
        addressField.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.addView(addressField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        codeField = new EditText(this);
        codeField.setHint("6-digits");
        codeField.setTextColor(Color.WHITE);
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeField.setFilters(new InputFilter[] {new InputFilter.LengthFilter(6)});
        codeField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        codeField.setBackground(cardBackground(COLOR_SURFACE, 8, COLOR_CARD_STROKE, 1));
        codeField.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.addView(codeField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Button conn = createM3Button("Connect", true);
        conn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { connect(); }
        });
        row.addView(conn);
        pc.addView(row);

        groupsBox = new LinearLayout(this);
        groupsBox.setOrientation(LinearLayout.VERTICAL);
        pc.addView(groupsBox);

        copyButton = createM3Button("Sync Files", true);
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startCopy(); }
        });
        pc.addView(copyButton);

        return wrapScrollable(content);
    }

    private void refreshBackupsList() {
        if (backupListContainer == null) return;
        backupListContainer.removeAllViews();
        File bd = new File(base, "state/backups/saves");
        File[] bk = bd.listFiles();
        if (bk == null || bk.length == 0) return;
        Arrays.sort(bk, Collections.reverseOrder());
        for (int i = 0; i < Math.min(4, bk.length); i++) {
            final File b = bk[i];
            LinearLayout it = new LinearLayout(this);
            it.setOrientation(LinearLayout.HORIZONTAL);
            it.setGravity(Gravity.CENTER_VERTICAL);
            it.setBackground(cardBackground(COLOR_SURFACE, 8, COLOR_CARD_STROKE, 1));
            it.setPadding(dp(8), dp(6), dp(8), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(4));
            it.setLayoutParams(lp);

            TextView n = new TextView(this);
            n.setText(b.getName());
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            n.setTextColor(Color.WHITE);
            it.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            Button rb = new Button(this);
            rb.setText("Restore");
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            rb.setTextColor(COLOR_ACCENT);
            rb.setBackground(rippleDrawable(Color.TRANSPARENT, Color.argb(40, 241, 174, 54), 6));
            rb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { restoreSave(b); }
            });
            it.addView(rb);
            backupListContainer.addView(it);
        }
    }

    private void restoreSave(final File backup) {
        new AlertDialog.Builder(this)
                .setTitle("Restore Save")
                .setMessage("Restore '" + backup.getName() + "'?")
                .setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            backUpSave();
                            File ud = new File(base, "state/user");
                            deleteTree(ud);
                            ud.mkdirs();
                            copyTree(backup, ud);
                            Toast.makeText(SetupActivity.this, "Save restored", Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Toast.makeText(SetupActivity.this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshAllTabs() {
        boolean r = gameReady();
        if (gameStatusText != null) {
            gameStatusText.setText(r ? "Installed & Verified in files/game/base" : "Game data needed · Tap below to import");
            gameStatusText.setTextColor(r ? COLOR_SUCCESS : COLOR_ACCENT);
        }
        if (launchButton != null) {
            launchButton.setText(r ? "▶  LAUNCH GAME" : "📁  IMPORT GAME FILES");
        }
        if (folderPathText != null) {
            folderPathText.setText("Target: " + new File(base, "game/base").getAbsolutePath()
                    + "\nStatus: " + (r ? "default.xex ready" : "no default.xex detected"));
        }
        refreshDriversList();
        refreshSettingsTab();
        refreshBackupsList();
    }

    String[] buildCurrentArguments() {
        List<String> args = new ArrayList<>();
        String aspect = prefs.getString(PREF_ASPECT_MODE, "hor_plus");
        if ("hor_plus".equals(aspect)) {
            args.add("--pinyon_shift_hor_plus=true");
            args.add("--present_letterbox=false");
        } else if ("letterbox".equals(aspect)) {
            args.add("--pinyon_shift_hor_plus=false");
            args.add("--present_letterbox=true");
        } else if ("stretch".equals(aspect)) {
            args.add("--pinyon_shift_hor_plus=false");
            args.add("--present_letterbox=false");
        } else if ("crop".equals(aspect)) {
            args.add("--pinyon_shift_hor_plus=false");
            args.add("--present_letterbox=true");
            args.add("--present_crop=true");
        }

        args.add("--android_gpu_driver=" + prefs.getString(PREF_SELECTED_DRIVER, "auto"));
        int scale = prefs.getInt(PREF_RES_SCALE, 1);
        args.add("--draw_resolution_scale_x=" + scale);
        args.add("--draw_resolution_scale_y=" + scale);
        args.add("--vsync=" + (prefs.getBoolean(PREF_VSYNC, true) ? "true" : "false"));
        args.add("--framerate_limit=" + prefs.getInt(PREF_FPS_CAP, 60));

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
        StringBuilder sb = new StringBuilder();
        sb.append("[Display]\npinyon_shift_hor_plus = ").append("hor_plus".equals(prefs.getString(PREF_ASPECT_MODE, "hor_plus"))).append("\n");
        sb.append("present_letterbox = ").append("letterbox".equals(prefs.getString(PREF_ASPECT_MODE, "hor_plus"))).append("\n\n");
        sb.append("[UI/Vulkan]\nandroid_gpu_driver = \"").append(prefs.getString(PREF_SELECTED_DRIVER, "auto")).append("\"\n");
        sb.append("android_gpu_turbo = ").append(prefs.getBoolean(PREF_GPU_TURBO, false)).append("\n");
        sb.append("android_gpu_driver_no_preamble = ").append(prefs.getBoolean(PREF_NO_PREAMBLE, true)).append("\n\n");
        sb.append("[Graphics]\ndraw_resolution_scale_x = ").append(prefs.getInt(PREF_RES_SCALE, 1)).append("\n");
        sb.append("draw_resolution_scale_y = ").append(prefs.getInt(PREF_RES_SCALE, 1)).append("\n");
        sb.append("vsync = ").append(prefs.getBoolean(PREF_VSYNC, true)).append("\n");
        sb.append("framerate_limit = ").append(prefs.getInt(PREF_FPS_CAP, 60)).append("\n");
        try (OutputStream out = new FileOutputStream(tomlFile)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    private void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try { startActivityForResult(intent, REQUEST_FOLDER); }
        catch (Exception e) { Toast.makeText(this, "No folder picker", Toast.LENGTH_SHORT).show(); }
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
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(SetupActivity.this, "Driver import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("unexpected path " + path);
            }
        }
        File target = new File(base, path);
        if (!target.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) {
            throw new IOException("unexpected path " + path);
        }
        return target;
    }

    private void backUpSave() {
        File user = new File(base, "state/user");
        if (!user.isDirectory()) return;
        String stamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).format(new Date());
        File backup = new File(base, "state/backups/saves/" + stamp);
        try { copyTree(user, backup); } catch (IOException ignored) {}
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] ch = src.listFiles();
            if (ch != null) for (File c : ch) copyTree(c, new File(dst, c.getName()));
            return;
        }
        dst.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            copy(in, out, null);
        }
    }

    private static void deleteTree(File file) {
        File[] ch = file.listFiles();
        if (ch != null) for (File c : ch) deleteTree(c);
        file.delete();
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
                    long el = Math.max(1, (System.currentTimeMillis() - st) / 1000);
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
                        for (InetAddress a : broadcastAddresses()) {
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

    private List<InetAddress> broadcastAddresses() {
        List<InetAddress> res = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) res.add(ia.getBroadcast());
                }
            }
            res.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {}
        return res;
    }

    private void connect() {
        String addr = addressField.getText().toString().trim().replaceFirst("^https?://", "").replaceAll("/.*$", "");
        final String code = codeField.getText().toString().trim();
        if (addr.isEmpty() || code.length() != 6) {
            Toast.makeText(this, "Enter IP and 6-digit code", Toast.LENGTH_SHORT).show();
            return;
        }
        final String host = addr.contains(":") ? addr : addr + ":" + HTTP_PORT;
        busy = true;
        if (discoveryText != null) discoveryText.setText("Connecting…");
        new Thread(new Runnable() {
            @Override
            public void run() { fetchManifest(host, code); }
        }, "PcConnect").start();
    }

    private void fetchManifest(final String host, String code) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://" + host + "/pinyon/v1/manifest").openConnection();
            conn.setConnectTimeout(8000);
            conn.setRequestProperty("X-Pinyon-Code", code);
            if (conn.getResponseCode() != 200) throw new IOException("PC answered " + conn.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(conn.getInputStream(), out, null);
            final JSONObject res = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            discovering = false;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    server = host;
                    manifest = res;
                    showGroups();
                    if (discoveryText != null) discoveryText.setText("Connected to " + res.optString("name", host));
                }
            });
        } catch (Exception e) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { if (discoveryText != null) discoveryText.setText("Failed: " + e.getMessage()); }
            });
        } finally { busy = false; }
    }

    private void showGroups() {
        if (groupsBox == null || manifest == null) return;
        groupsBox.removeAllViews();
        groupChecks.clear();
        JSONArray groups = manifest.optJSONArray("groups");
        if (groups == null) return;
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            if (g == null) continue;
            String id = g.optString("id");
            CheckBox cb = new CheckBox(this);
            cb.setText(g.optString("label") + " (" + size(g.optLong("bytes")) + ")");
            cb.setTextColor(Color.WHITE);
            cb.setChecked(g.optBoolean("selected", true));
            groupChecks.put(id, cb);
            groupsBox.addView(cb);
        }
        if (copyButton != null) copyButton.setVisibility(View.VISIBLE);
    }

    private void startCopy() {
        if (manifest == null || server == null) return;
        final String code = codeField.getText().toString().trim();
        final List<String> chosen = new ArrayList<>();
        for (Map.Entry<String, CheckBox> e : groupChecks.entrySet()) {
            if (e.getValue().isChecked()) chosen.add(e.getKey());
        }
        if (chosen.isEmpty()) return;
        busy = true;
        if (progress != null) progress.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    copyFromPc(code, chosen);
                    finishOp("PC Sync Complete!", true);
                } catch (Exception e) { finishOp("Sync failed: " + e.getMessage(), false); }
            }
        }, "PcCopy").start();
    }

    private void copyFromPc(String code, List<String> chosen) throws IOException, JSONException {
        List<JSONObject> files = new ArrayList<>();
        long total = 0;
        JSONArray groups = manifest.getJSONArray("groups");
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.getJSONObject(i);
            if (!chosen.contains(g.getString("id"))) continue;
            JSONArray list = g.getJSONArray("files");
            for (int j = 0; j < list.length(); j++) {
                JSONObject f = list.getJSONObject(j);
                total += f.getLong("size");
                files.add(f);
            }
        }
        if (chosen.contains("save")) backUpSave();
        boolean gm = chosen.contains("game");
        File inc = new File(base, INCOMPLETE);
        if (gm) {
            inc.getParentFile().mkdirs();
            new FileOutputStream(inc).close();
        }
        long[] done = {0};
        long started = System.currentTimeMillis();
        for (int i = 0; i < files.size(); i++) {
            JSONObject f = files.get(i);
            String path = f.getString("path");
            long sz = f.getLong("size");
            File target = destination(path);
            if (target.length() != sz) {
                fetch(code, f.getInt("index"), target, sz, done, total, i + 1, files.size(), started);
            } else {
                done[0] += sz;
            }
        }
        if (gm) inc.delete();
    }

    private void fetch(String code, int index, File target, long size, final long[] done, final long total,
                       final int number, final int count, final long started) throws IOException {
        File part = new File(target.getPath() + ".part");
        target.getParentFile().mkdirs();
        long offset = part.isFile() ? part.length() : 0;
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + server + "/pinyon/v1/files/" + index).openConnection();
        conn.setConnectTimeout(8000);
        conn.setRequestProperty("X-Pinyon-Code", code);
        if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-");
        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(part, offset > 0)) {
            final long before = done[0] + offset;
            copy(in, out, new Progress() {
                @Override
                public void written(long b) { report(before + b, total, number, count, started); }
            });
        }
        done[0] += size;
        if (!part.renameTo(target)) {
            target.delete();
            part.renameTo(target);
        }
    }

    @Override
    public void onClick(View v) {}
}
