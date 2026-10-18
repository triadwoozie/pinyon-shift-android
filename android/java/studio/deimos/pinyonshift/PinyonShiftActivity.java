package studio.deimos.pinyonshift;

import android.app.*;
import android.content.*;
import android.content.res.AssetManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.hardware.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.system.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import org.libsdl.app.SDLActivity;

public class PinyonShiftActivity extends SDLActivity implements View.OnClickListener {
    private static final String TAG = "PinyonShift";
    public static final long MS_2505_RETAIL_XEX_SIZE = 20856832L;

    public static final int BTN_DPAD_UP = 0x0001, BTN_DPAD_DOWN = 0x0002, BTN_DPAD_LEFT = 0x0004, BTN_DPAD_RIGHT = 0x0008;
    public static final int BTN_START = 0x0010, BTN_BACK = 0x0020, BTN_LEFT_SHOULDER = 0x0100, BTN_RIGHT_SHOULDER = 0x0200;
    public static final int BTN_A = 0x1000, BTN_B = 0x2000, BTN_X = 0x4000, BTN_Y = 0x8000;

    public static final String PREF_SMART_CLUTCH = "pref_smart_clutch", PREF_GESTURE_SHIFT = "pref_gesture_shift", PREF_HUD_HITBOXES = "pref_hud_hitboxes", PREF_MENU_MODE_AUTO = "pref_menu_mode_auto", PREF_LOOK_AROUND = "pref_look_around", PREF_ASPECT_MODE = "pref_aspect_mode";

    public static native void nativeSetPadState(float steer, float throttle, float brake, float lookX, float lookY, int buttons);
    public static native void nativeAction(int actionId, float value);
    public static native void nativeSetMobileOverrides(float steer_x, float throttle, float brake, int button_mask, float look_x, float look_y, float steer_y);
    public static native void nativeSetCVar(String name, String value);
    public static native float nativeGetFrameTimeMs();

    private File stateDir, driversDir;
    private SharedPreferences prefs;
    private RR3OverlayView rr3Overlay;
    private View quickHudDrawer;

    private SensorManager sensorManager;
    private Sensor gameRotationSensor;
    private float baselineRoll = 0.0f, currentTiltDegrees = 0.0f;
    private boolean gyroCalibrated = false, autoThrottle = false, inMenuMode = false, showFpsOverlay = true, thermalPacerEnabled = true;
    private String controlScheme = "arrows";
    private TextView hudTempText;

    private volatile float currentSteerX = 0.0f, currentSteerY = 0.0f, currentThrottle = 0.0f, currentBrake = 0.0f, currentLookX = 0.0f, currentLookY = 0.0f;
    private volatile int currentButtons = 0;

    private BroadcastReceiver thermalReceiver;
    private float currentBatteryTempC = 25.0f;
    private int currentFpsTarget = 30;
    private FileObserver photoObserver;
    private static Vibrator sVibrator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File base = getExternalFilesDir(null) != null ? getExternalFilesDir(null) : getFilesDir();
        stateDir = new File(base, "state");
        setDefaultEnvironment("HOME", getFilesDir().getAbsolutePath());
        setDefaultEnvironment("PINYON_SHIFT_STATE_ROOT", stateDir.getAbsolutePath());
        setDefaultEnvironment("PINYON_SHIFT_GAME_ROOT", new File(base, "game/base").getAbsolutePath());
        driversDir = new File(base, "state/drivers");
        setDefaultEnvironment("REX_ANDROID_DRIVERS_DIR", driversDir.getAbsolutePath());
        installBundledDrivers();
        String adreno = adrenoModel();
        if (adreno != null) {
            setDefaultEnvironment("PINYON_SHIFT_ADRENO_MODEL", adreno);
            String rec = recommendedDriver(adreno);
            if (rec != null) setDefaultEnvironment("REX_ANDROID_RECOMMENDED_DRIVER", rec);
        }
        if (new File(base, "state/cache/rally_adapter/rally-stage.toml").isFile()) setDefaultEnvironment("PINYON_SHIFT_RALLY_PREPARED", "1");
        File manifest = new File(getFilesDir(), "pinyon_shift_build.json");
        try (InputStream in = getAssets().open("pinyon_shift_build.json"); OutputStream out = new FileOutputStream(manifest)) {
            copy(in, out); setEnvironment("PINYON_SHIFT_BUILD_MANIFEST", manifest.getAbsolutePath());
        } catch (IOException ignored) {}
        Bundle extras = getIntent() != null ? getIntent().getExtras() : null;
        if (extras != null) for (String k : extras.keySet()) if (k.startsWith("ENV_")) setEnvironment(k.substring(4), extras.getString(k, ""));
        prefs = getSharedPreferences("pinyon_shift_settings", MODE_PRIVATE);
        controlScheme = prefs.getString("control_scheme", "arrows");
        autoThrottle = prefs.getBoolean("auto_throttle", false);
        showFpsOverlay = prefs.getBoolean("fps_overlay", true);
        thermalPacerEnabled = prefs.getBoolean("thermal_pacer", true);
        if (autoThrottle) currentThrottle = 1.0f;
        super.onCreate(savedInstanceState);
        enforceCutoutMode();
        int res = prefs.getInt("res_scale", 720);
        if (res <= 2) { res = (res == 2) ? 1440 : 720; prefs.edit().putInt("res_scale", res).apply(); }
        applySurfaceResolution(res);
        initHaptics(); initSensors(); initThermalPacer();
        startPhotoWatcher(new File(stateDir, "photos"));
        attachMobileOverlays();
    }

    private void enforceCutoutMode() {
        if (getWindow() == null) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30 ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
            if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        }
        if (mLayout != null) mLayout.setFitsSystemWindows(false);
        if (mSurface != null) mSurface.setFitsSystemWindows(false);
    }

    @Override
    protected org.libsdl.app.SDLSurface createSDLSurface(Context context) {
        return new org.libsdl.app.SDLSurface(context) {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    SDLActivity.onNativeInsetsChanged(0, 0, 0, 0);
                }
                return insets;
            }
        };
    }

    @Override
    protected String[] getArguments() {
        File f = new File(stateDir != null ? stateDir : new File(getFilesDir(), "state"), "config/android-arguments.txt");
        List<String> list = new ArrayList<>();
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                for (String l; (l = r.readLine()) != null; ) { l = l.trim(); if (!l.isEmpty() && !l.startsWith("#")) list.add(l); }
            } catch (IOException ignored) {}
        }
        addArg(list, "--texture_cache_memory_limit_soft", "192");
        addArg(list, "--texture_cache_memory_limit_hard", "384");
        addArg(list, "--texture_cache_memory_limit_soft_lifetime", "5");
        addArg(list, "--vulkan_texture_max_size", "1024");
        return list.toArray(new String[0]);
    }

    private static void addArg(List<String> l, String k, String v) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).startsWith(k + "=")) return;
        l.add(k + "=" + v);
    }

    private void applySurfaceResolution(int s) {
        if (mSurface == null) return;
        ViewGroup.LayoutParams lp = mSurface.getLayoutParams();
        if (lp != null) { lp.width = lp.height = ViewGroup.LayoutParams.MATCH_PARENT; mSurface.setLayoutParams(lp); }
        if (s == 540 || s == 360) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float asp = (float) Math.max(dm.widthPixels, dm.heightPixels) / (float) Math.max(1, Math.min(dm.widthPixels, dm.heightPixels));
            mSurface.getHolder().setFixedSize(Math.round(s * asp), s);
        } else mSurface.getHolder().setSizeFromLayout();
    }

    Dialog createCardDialog(String title, LinearLayout c) {
        Dialog d = new Dialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(18), dp(18), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFF181D1A); bg.setCornerRadius(dp(18)); bg.setStroke(dp(1), 0xFF26302A); c.setBackground(bg);
        if (title != null) { TextView tv = new TextView(this); tv.setText(title); tv.setTextColor(Color.WHITE); tv.setTypeface(Typeface.DEFAULT_BOLD); tv.setPadding(0, 0, 0, dp(8)); c.addView(tv); }
        d.setContentView(c); return d;
    }
    Button createDialogBtn(String text, boolean primary) {
        Button b = new Button(this); b.setText(text); b.setTextColor(primary ? 0xFF0B1F11 : Color.WHITE);
        GradientDrawable gd = new GradientDrawable(); gd.setColor(primary ? 0xFF5CE68B : 0xFF212923); gd.setCornerRadius(dp(10));
        b.setBackground(gd); b.setPadding(dp(12), dp(6), dp(12), dp(6)); return b;
    }
    private void confirmQuit() {
        LinearLayout c = new LinearLayout(this); final Dialog d = createCardDialog("Quit Game", c);
        TextView msg = new TextView(this); msg.setText("Exit to launcher?"); msg.setTextColor(0xFF93A597); msg.setPadding(0, dp(2), 0, dp(12)); c.addView(msg);
        LinearLayout row = new LinearLayout(this);
        Button cancel = createDialogBtn("Cancel", false); cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } });
        Button quit = createDialogBtn("Quit", true);
        quit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); finish(); }
        });
        row.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1.0f)); row.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1)); row.addView(quit, new LinearLayout.LayoutParams(0, -2, 1.0f));
        c.addView(row); d.show();
    }

    @Override
    public void onBackPressed() {
        if (quickHudDrawer != null && quickHudDrawer.getVisibility() == View.VISIBLE) {
            toggleQuickHud();
            return;
        }
        if (inMenuMode) {
            confirmQuit();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enforceCutoutMode();
        if (Build.VERSION.SDK_INT >= 30) {
            try { SDLActivity.onNativeInsetsChanged(0, 0, 0, 0); } catch (Throwable ignored) {}
        }
        applySurfaceResolution(prefs.getInt("res_scale", 720));
        applyDisplayRefreshRate(currentFpsTarget);
        boolean gyroOn = isGyroActive();
        if (sensorManager != null && gameRotationSensor != null && gyroOn) {
            sensorManager.registerListener(sensorListener, gameRotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        String asp = prefs.getString(PREF_ASPECT_MODE, "hor_plus");
        try { nativeAction(4, "hor_plus".equals(asp) ? 1.0f : 0.0f); } catch (UnsatisfiedLinkError ignored) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enforceCutoutMode();
            if (Build.VERSION.SDK_INT >= 30) {
                try { SDLActivity.onNativeInsetsChanged(0, 0, 0, 0); } catch (Throwable ignored) {}
            }
        }
    }

    private void applyDisplayRefreshRate(int targetFps) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (getWindow() != null) {
                WindowManager.LayoutParams p = getWindow().getAttributes();
                p.preferredRefreshRate = (targetFps == 120) ? 120.0f : 60.0f; getWindow().setAttributes(p);
            }
            if (mSurface != null && mSurface.getHolder() != null && mSurface.getHolder().getSurface() != null) {
                try { mSurface.getHolder().getSurface().setFrameRate((float) targetFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE); } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(sensorListener);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (thermalReceiver != null) {
            try { unregisterReceiver(thermalReceiver); } catch (Exception ignored) {}
        }
        if (photoObserver != null) {
            photoObserver.stopWatching();
        }
        super.onDestroy();
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {"c++_shared", "rexruntime", "main"};
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int c = event.getKeyCode();
            if (c == KeyEvent.KEYCODE_BACK) { inMenuMode = !inMenuMode; pulseButton(inMenuMode ? BTN_START : BTN_B, 120); if (rr3Overlay != null) rr3Overlay.postInvalidate(); return true; }
            int b = c == 19 ? BTN_DPAD_UP : c == 20 ? BTN_DPAD_DOWN : c == 21 ? BTN_DPAD_LEFT : c == 22 ? BTN_DPAD_RIGHT : (c == 96 || c == 66 || c == 62) ? BTN_A : c == 97 ? BTN_B : c == 99 ? BTN_X : c == 100 ? BTN_Y : c == 108 ? BTN_START : 0;
            if (b != 0) { pulseButton(b, 120); return true; }
        }
        return super.dispatchKeyEvent(event);
    }

    private void flushOverrides() {
        try { nativeSetPadState(currentSteerX, currentThrottle, currentBrake, currentLookX, currentLookY, currentButtons); } catch (UnsatisfiedLinkError ignored) {}
    }

    private void pulseButton(final int mask, int durationMs) {
        currentButtons |= mask; flushOverrides(); vibrate(this, 15, 120);
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() { currentButtons &= ~mask; flushOverrides(); if (rr3Overlay != null) rr3Overlay.postInvalidate(); }
        }, Math.max(50, durationMs));
    }

    private void triggerShift(final boolean shiftUp) {
        final int shiftBtn = shiftUp ? BTN_B : BTN_X;
        if (prefs.getBoolean(PREF_SMART_CLUTCH, true)) {
            currentButtons |= BTN_LEFT_SHOULDER; flushOverrides(); vibrate(this, 10, 100);
            mainHandler.postDelayed(new Runnable() { @Override public void run() { currentButtons |= shiftBtn; flushOverrides(); } }, 40);
            mainHandler.postDelayed(new Runnable() { @Override public void run() { currentButtons &= ~(BTN_LEFT_SHOULDER | shiftBtn); flushOverrides(); } }, 80);
        } else pulseButton(shiftBtn, 90);
    }

    static String adrenoModel() {
        try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpu_model"))) {
            String l = r.readLine();
            Matcher m = l != null ? Pattern.compile("Adreno.*?(\\d{3})").matcher(l) : null;
            if (m != null && m.find()) return m.group(1);
        } catch (IOException ignored) {}
        String hw = ((Build.HARDWARE != null ? Build.HARDWARE : "") + " " + (Build.BOARD != null ? Build.BOARD : "")
                + " " + (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null ? Build.SOC_MODEL : "")).toUpperCase(Locale.ROOT);
        for (String p : new String[]{"SM8750:830","SM7635:810","VOLCANO:810","SM8650:750","SM8550:740","QCS8550:740","SM8635:735","SM7675:732","SM8450:730","SM8475:730","SM7475:725","SM7550:720","SM8350:660","SM8250:650"}) {
            String[] x = p.split(":"); if (hw.contains(x[0])) return x[1];
        }
        return null;
    }

    static String recommendedDriver(String adreno) {
        return (adreno != null && (adreno.startsWith("6") || adreno.startsWith("7") || adreno.startsWith("8"))) ? "turnip-gen8-v37" : null;
    }

    private void installBundledDrivers() { installBundledDrivers(getAssets(), driversDir); }
    private static void setDefaultEnvironment(String name, String value) { if (Os.getenv(name) == null) setEnvironment(name, value); }
    private static void setEnvironment(String name, String value) { try { Os.setenv(name, value, true); } catch (ErrnoException ignored) {} }

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (gameRotationSensor == null) gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
    }

    private final SensorEventListener sensorListener = new SensorEventListener() {
        private final float[] rotationMatrix = new float[9];
        private final float[] orientationAngles = new float[3];

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!isGyroActive() || inMenuMode) return;
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            float[] remapped = new float[9];
            int rot = 0; try { rot = getWindowManager().getDefaultDisplay().getRotation(); } catch (Throwable ignored) {}
            if (rot == Surface.ROTATION_90) SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped);
            else if (rot == Surface.ROTATION_270) SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped);
            else System.arraycopy(rotationMatrix, 0, remapped, 0, 9);
            SensorManager.getOrientation(remapped, orientationAngles);
            float rollDeg = (float) Math.toDegrees(orientationAngles[2]);

            if (!gyroCalibrated) {
                baselineRoll = rollDeg;
                gyroCalibrated = true;
            }

            float delta = rollDeg - baselineRoll;
            if (delta > 180.0f) delta -= 360.0f;
            if (delta < -180.0f) delta += 360.0f;
            currentTiltDegrees = delta;

            if (rr3Overlay != null) rr3Overlay.setTiltAngle(currentTiltDegrees);
            if (rr3Overlay != null && rr3Overlay.touchSteerActive) return;

            float deadband = 2.0f, maxAngle = 30.0f, steer = 0.0f;
            if (Math.abs(delta) > deadband) {
                float eff = Math.min(1.0f, (Math.abs(delta) - deadband) / (maxAngle - deadband));
                steer = Math.copySign((float) Math.pow(eff, 1.3), delta);
            }
            currentSteerX = steer;
            flushOverrides();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private boolean isGyroActive() {
        return "tilt_a".equals(controlScheme) && prefs.getBoolean("experimental_gyro", false);
    }

    public void recalibrateGyro() {
        gyroCalibrated = false;
        toast("Gyro baseline zeroed to current position");
    }

    private void initHaptics() { vibrate(this, 1, 1); }

    public static void vibrate(Context context, int ms, int amplitude) {
        if (sVibrator == null && context != null) {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    android.os.VibratorManager vm = (android.os.VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                    if (vm != null) sVibrator = vm.getDefaultVibrator();
                }
                if (sVibrator == null) sVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            } catch (Throwable ignored) {}
        }
        if (sVibrator == null || !sVibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) sVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            else if (Build.VERSION.SDK_INT >= 26) sVibrator.vibrate(VibrationEffect.createOneShot(Math.max(20, ms), Math.min(255, Math.max(1, amplitude))));
            else sVibrator.vibrate(Math.max(20, ms));
        } catch (Throwable ignored) {}
    }

    private void attachMobileOverlays() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                ViewGroup content = findViewById(android.R.id.content); if (content == null) return;
                rr3Overlay = new RR3OverlayView(PinyonShiftActivity.this);
                content.addView(rr3Overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                quickHudDrawer = createQuickHudDrawer(); quickHudDrawer.setVisibility(View.GONE); content.addView(quickHudDrawer);
            }
        });
    }

    private View bindClick(View v, Object tag) { v.setTag(tag); v.setOnClickListener(this); return v; }
    private void addToggle(LinearLayout p, String text, boolean active, String tag) { p.addView(bindClick(createChipButton(text, active), tag)); }
    private void addSwitch(LinearLayout p, String name, boolean val, String tag) { addToggle(p, name + ": " + (val ? "ON" : "OFF"), val, tag); }

    private void addHudRow(LinearLayout items, String title, String[] labels, String[] tags, int activeIdx) {
        addHudSectionTitle(items, title); LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            row.addView(bindClick(createChipButton(labels[i], i == activeIdx), tags[i]), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        }
        items.addView(row);
    }

    private void toggleQuickHud() {
        if (quickHudDrawer == null) return;
        boolean show = quickHudDrawer.getVisibility() != View.VISIBLE;
        if (show) {
            readBatteryTemp(registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
            if (hudTempText != null) hudTempText.setText(String.format(Locale.ROOT, "Battery Temp: %.1f°C", currentBatteryTempC));
            quickHudDrawer.bringToFront();
        }
        quickHudDrawer.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private View createQuickHudDrawer() {
        int accent = getThemeAccent(this);
        FrameLayout drawer = new FrameLayout(this); drawer.setBackgroundColor(0xF5101813);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(320), -1); lp.gravity = Gravity.START; drawer.setLayoutParams(lp);
        ScrollView scroll = new ScrollView(this); LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL); items.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this); title.setText("FH1 Live Tuning"); title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16); title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextColor(accent);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1.0f));
        header.addView(bindClick(createChipButton("Close", false), "hud_close"), new LinearLayout.LayoutParams(-2, -2));
        items.addView(header);
        hudTempText = new TextView(this); hudTempText.setText(String.format(Locale.ROOT, "Battery Temp: %.1f°C", currentBatteryTempC));
        hudTempText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); hudTempText.setTextColor(0xFF93A597); hudTempText.setPadding(0, dp(4), 0, dp(10));
        items.addView(hudTempText);
        addHudSectionTitle(items, "Quick Toggles");
        addSwitch(items, "Auto Accelerate", autoThrottle, "toggle_auto_throttle");
        addSwitch(items, "Performance Overlay", showFpsOverlay, "toggle_fps_overlay");
        addSwitch(items, "Menu Mode D-Pad", inMenuMode, "toggle_menu_mode");
        addSwitch(items, "Experimental Gyro", prefs.getBoolean("experimental_gyro", false), "toggle_gyro");
        addHudRow(items, "Control Scheme", new String[]{"Arrows", "Tilt Gyro", "Classic"}, new String[]{"ctrl_arrows", "ctrl_tilt_a", "ctrl_classic"}, "tilt_a".equals(controlScheme) ? 1 : "classic".equals(controlScheme) ? 2 : 0);
        addSwitch(items, "Treasure Map", prefs.getBoolean("treasure_map", true), "toggle_treasure_map");
        addHudRow(items, "Target FPS", new String[]{"30 FPS", "45 FPS", "60 FPS", "120 FPS"}, new String[]{"fps_30", "fps_45", "fps_60", "fps_120"}, currentFpsTarget == 120 ? 3 : currentFpsTarget == 60 ? 2 : currentFpsTarget == 45 ? 1 : 0);
        addHudRow(items, "Render Preset", new String[]{"Potato", "Balanced", "Ultra"}, new String[]{"preset_potato", "preset_balanced", "preset_ultra"}, -1);
        addHudRow(items, "Output Upscaler", new String[]{"FSR 1.0", "CAS", "Bilinear"}, new String[]{"up_fsr", "up_cas", "up_bilinear"}, -1);
        addHudSectionTitle(items, "Display & Resolution");
        int curRes = prefs.getInt("res_scale", 720);
        addHudRow(items, "Resolution", new String[]{"720p", "540p", "360p", "Native"}, new String[]{"res_720", "res_540", "res_360", "res_1080"}, curRes == 360 ? 2 : curRes == 540 ? 1 : curRes >= 1080 ? 3 : 0);
        String curAsp = prefs.getString(PREF_ASPECT_MODE, "hor_plus");
        addHudRow(items, "Aspect Ratio", new String[]{"Hor+ Ultrawide", "16:9 Fit"}, new String[]{"asp_hor_plus", "asp_16_9"}, "hor_plus".equals(curAsp) ? 0 : 1);
        addSwitch(items, "VSync", prefs.getBoolean("vsync", true), "toggle_vsync");
        int curOp = prefs.getInt("touch_opacity", 100);
        addHudRow(items, "Touch Opacity", new String[]{"100%", "75%", "50%", "Hidden"}, new String[]{"op_100", "op_75", "op_50", "op_0"}, curOp == 0 ? 3 : curOp == 50 ? 2 : curOp == 75 ? 1 : 0);
        addSwitch(items, "Steering Horizon", prefs.getBoolean("touch_horizon", true), "toggle_horizon");
        addHudSectionTitle(items, "Controls & Help");
        addToggle(items, "Recalibrate Gyro 0°", true, "gyro_cal");
        addToggle(items, "Touch Controls Guide", false, "touch_guide");
        Button quitBtn = createChipButton("Quit to Launcher", false);
        GradientDrawable qgd = (GradientDrawable) quitBtn.getBackground(); if (qgd != null) qgd.setColor(0xDD3A1618);
        quitBtn.setTextColor(0xFFFF6B6B); items.addView(bindClick(quitBtn, "quit_game"));
        scroll.addView(items); drawer.addView(scroll); return drawer;
    }

    @Override
    public void onClick(View v) {
        Object tag = v.getTag(); if (tag == null) return;
        String t = tag.toString(); toggleQuickHud();
        if ("quit_game".equals(t)) confirmQuit();
        else if ("toggle_auto_throttle".equals(t)) {
            autoThrottle = !autoThrottle; prefs.edit().putBoolean("auto_throttle", autoThrottle).apply();
            if (!inMenuMode) currentThrottle = autoThrottle ? 1.0f : 0.0f; flushOverrides();
            toast("Auto Throttle: " + (autoThrottle ? "ON" : "OFF"));
        } else if ("toggle_fps_overlay".equals(t)) {
            showFpsOverlay = !showFpsOverlay; prefs.edit().putBoolean("fps_overlay", showFpsOverlay).apply();
            if (rr3Overlay != null) rr3Overlay.setShowFpsOverlay(showFpsOverlay);
            toast("FPS Overlay: " + (showFpsOverlay ? "ON" : "OFF"));
        } else if ("toggle_menu_mode".equals(t)) {
            inMenuMode = !inMenuMode; if (inMenuMode) currentThrottle = 0.0f; else if (autoThrottle) currentThrottle = 1.0f;
            flushOverrides(); if (rr3Overlay != null) rr3Overlay.postInvalidate();
            toast("Mode: " + (inMenuMode ? "MENU" : "DRIVE"));
        } else if ("toggle_gyro".equals(t)) {
            boolean next = !prefs.getBoolean("experimental_gyro", false); prefs.edit().putBoolean("experimental_gyro", next).apply();
            if (sensorManager != null && gameRotationSensor != null) {
                if (next && "tilt_a".equals(controlScheme)) sensorManager.registerListener(sensorListener, gameRotationSensor, SensorManager.SENSOR_DELAY_GAME);
                else sensorManager.unregisterListener(sensorListener);
            }
            toast("Gyro: " + (next ? "ON" : "OFF"));
        } else if (t.startsWith("ctrl_")) {
            String c = t.substring(5); controlScheme = c; prefs.edit().putString("control_scheme", controlScheme).apply();
            if (sensorManager != null && gameRotationSensor != null) {
                if (isGyroActive()) sensorManager.registerListener(sensorListener, gameRotationSensor, SensorManager.SENSOR_DELAY_GAME);
                else sensorManager.unregisterListener(sensorListener);
            }
            if (rr3Overlay != null) rr3Overlay.postInvalidate();
            toast("Controls: " + ("tilt_a".equals(controlScheme) ? "Tilt Gyro" : "arrows".equals(controlScheme) ? "Arrows" : "Classic"));
        } else if ("toggle_treasure_map".equals(t)) {
            boolean next = !prefs.getBoolean("treasure_map", true); prefs.edit().putBoolean("treasure_map", next).apply();
            try { nativeSetCVar("pinyon_shift_dlc_treasure_map", next ? "true" : "false"); } catch (UnsatisfiedLinkError ignored) {}
            toast("Treasure Map: " + (next ? "ON" : "OFF"));
        } else if (t.startsWith("fps_")) {
            int f = Integer.parseInt(t.substring(4)); setPacedFpsTarget(f, f + " FPS");
            try { nativeAction(3, (float) f); } catch (UnsatisfiedLinkError ignored) {}
        } else if (t.startsWith("preset_")) {
            String p = t.substring(7); int pIdx = "potato".equals(p) ? 0 : "ultra".equals(p) ? 2 : 1;
            try { nativeAction(6, (float) pIdx); } catch (UnsatisfiedLinkError ignored) {}
            toast("Preset: " + p);
        } else if (t.startsWith("up_")) {
            String u = t.substring(3); int uIdx = "fsr".equals(u) ? 1 : "cas".equals(u) ? 2 : 0;
            try { nativeAction(5, (float) uIdx); } catch (UnsatisfiedLinkError ignored) {}
            toast("Upscaler: " + u.toUpperCase(Locale.ROOT));
        } else if (t.startsWith("asp_")) {
            boolean hp = "asp_hor_plus".equals(t); prefs.edit().putString(PREF_ASPECT_MODE, hp ? "hor_plus" : "letterbox").apply();
            try { nativeAction(4, hp ? 1.0f : 0.0f); } catch (UnsatisfiedLinkError ignored) {}
            toast("Aspect: " + (hp ? "Hor+" : "16:9"));
        } else if (t.startsWith("res_")) {
            int r = Integer.parseInt(t.substring(4)); prefs.edit().putInt("res_scale", r).apply();
            applySurfaceResolution(r); toast("Resolution: " + (r >= 1080 ? "Native" : r + "p"));
        } else if ("toggle_vsync".equals(t)) {
            boolean next = !prefs.getBoolean("vsync", true); prefs.edit().putBoolean("vsync", next).apply();
            try { nativeAction(7, next ? 1.0f : 0.0f); } catch (UnsatisfiedLinkError ignored) {}
            toast("VSync: " + (next ? "ON" : "OFF"));
        } else if (t.startsWith("op_")) {
            int op = Integer.parseInt(t.substring(3)); prefs.edit().putInt("touch_opacity", op).apply();
            if (rr3Overlay != null) rr3Overlay.updateTouchOpacity(op / 100.0f);
            toast("Opacity: " + (op == 0 ? "Hidden" : op + "%"));
        } else if ("toggle_horizon".equals(t)) {
            boolean next = !prefs.getBoolean("touch_horizon", true); prefs.edit().putBoolean("touch_horizon", next).apply();
            if (rr3Overlay != null) rr3Overlay.updateShowHorizon(next);
            toast("Horizon: " + (next ? "ON" : "OFF"));
        } else if ("gyro_cal".equals(t)) recalibrateGyro();
        else if ("touch_guide".equals(t)) showTouchGuide(this);
        else if ("shutter".equals(t)) {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_F8); SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_F8);
            vibrate(this, 20, 180); toast("Photo captured");
        }
    }

    private void addHudSectionTitle(LinearLayout container, String text) {
        TextView tv = new TextView(this); tv.setText(text); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); tv.setTextColor(Color.rgb(147, 165, 151)); tv.setPadding(0, dp(10), 0, dp(4)); container.addView(tv);
    }

    private Button createChipButton(String text, boolean active) {
        Button b = new Button(this); b.setText(text); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTextColor(active ? Color.rgb(10, 15, 12) : Color.rgb(238, 243, 236));
        GradientDrawable gd = new GradientDrawable(); gd.setColor(active ? getThemeAccent(this) : Color.rgb(22, 33, 26)); gd.setCornerRadius(dp(8));
        b.setBackground(gd); b.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, 0); b.setLayoutParams(lp); return b;
    }


    private void initThermalPacer() {
        thermalReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { readBatteryTemp(i); checkThermalPacing(); } };
        readBatteryTemp(registerReceiver(thermalReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
    }
    private void readBatteryTemp(Intent intent) {
        if (intent != null) { int raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0); if (raw > 0) currentBatteryTempC = raw / 10.0f; }
        if (currentBatteryTempC <= 0.0f || currentBatteryTempC == 25.0f) {
            try (BufferedReader br = new BufferedReader(new FileReader("/sys/class/power_supply/battery/temp"))) {
                String l = br.readLine(); if (l != null) { float v = Float.parseFloat(l.trim()); currentBatteryTempC = (v > 100.0f) ? (v / 10.0f) : v; }
            } catch (Exception ignored) {}
        }
    }
    private void checkThermalPacing() {
        if (!thermalPacerEnabled) return;
        if (currentBatteryTempC >= 44.0f && currentFpsTarget > 30) setPacedFpsTarget(30, "44°C: 30 FPS");
        else if (currentBatteryTempC >= 42.0f && currentFpsTarget > 45) setPacedFpsTarget(45, "42°C: 45 FPS");
        else if (currentBatteryTempC < 39.5f && currentFpsTarget < 60) setPacedFpsTarget(60, "<40°C: 60 FPS");
    }
    private void setPacedFpsTarget(int fps, String msg) {
        currentFpsTarget = fps; toast(msg);
        try { nativeAction(3, (float) fps); nativeSetCVar("pinyon_shift_fh1_render_fps_limit", String.valueOf(fps)); } catch (UnsatisfiedLinkError ignored) {}
    }
    private void startPhotoWatcher(final File photosDir) {
        if (!photosDir.exists()) photosDir.mkdirs();
        photoObserver = new FileObserver(photosDir.getAbsolutePath(), FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override public void onEvent(int event, String path) { if (path != null && path.endsWith(".png")) exportToGallery(new File(photosDir, path)); }
        };
        photoObserver.startWatching();
    }
    private void exportToGallery(final File photo) {
        try {
            ContentValues v = new ContentValues(); v.put(MediaStore.Images.Media.DISPLAY_NAME, photo.getName()); v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ForzaHorizon");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                try (InputStream in = new FileInputStream(photo); OutputStream out = getContentResolver().openOutputStream(uri)) { copy(in, out); }
                vibrate(this, 30, 200); toast("Photo saved");
            }
        } catch (Exception ignored) {}
    }

    private class RR3OverlayView extends View {
        private final Paint horizonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float tiltDeg = 0.0f;
        private float brakeAlpha = 0.0f;

        private static final int MAX_P = 10;
        private final float[] downX = new float[MAX_P];
        private final float[] downY = new float[MAX_P];
        private final long[] downT = new long[MAX_P];
        private final int[] zone = new int[MAX_P];

        private static final int Z_NONE = 0, Z_BRAKE = 1, Z_THROTTLE = 2, Z_CENTER = 3, Z_EBRAKE = 4, Z_STEER_LEFT = 5, Z_STEER_RIGHT = 6;
        private boolean steerLeftHeld = false, steerRightHeld = false;
        public boolean touchSteerActive = false;

        private void updateArrowSteer() {
            boolean l = false, r = false;
            for (int i = 0; i < MAX_P; i++) {
                if (zone[i] == Z_STEER_LEFT) l = true;
                else if (zone[i] == Z_STEER_RIGHT) r = true;
            }
            if (l != steerLeftHeld || r != steerRightHeld) {
                if ((l && !steerLeftHeld) || (r && !steerRightHeld)) {
                    vibrate(PinyonShiftActivity.this, 12, 100);
                    try { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignored) {}
                }
                steerLeftHeld = l; steerRightHeld = r;
                touchSteerActive = (l || r);
                currentSteerX = l ? -1.0f : (r ? 1.0f : 0.0f);
                flushOverrides();
                postInvalidate();
            }
        }

        private void updatePedals() {
            boolean brk = false, thr = false;
            for (int i = 0; i < MAX_P; i++) {
                if (zone[i] == Z_BRAKE) brk = true;
                else if (zone[i] == Z_THROTTLE) thr = true;
            }
            currentBrake = brk ? 1.0f : 0.0f;
            brakeAlpha = brk ? 0.25f : 0.0f;
            if (brk) {
                currentThrottle = (autoThrottle && !thr) ? 0.0f : (thr ? 1.0f : 0.0f);
            } else {
                currentThrottle = (thr || autoThrottle) ? 1.0f : 0.0f;
            }
            flushOverrides();
            postInvalidate();
        }

        private long lastCenterTapTime = 0;
        private boolean showHorizon = true;
        private float touchOpacity = 1.0f, measuredFps = 30.0f, measuredFrameMs = 33.3f;
        private long lastFpsCalcMs = 0, statusFlashUntil = 0;
        private String statusFlashText = null;

        public RR3OverlayView(Context context) {
            super(context);
            horizonPaint.setStrokeWidth(dp(2)); horizonPaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStyle(Paint.Style.STROKE); strokePaint.setStrokeWidth(dp(1.2f));
            textPaint.setTextSize(dp(13)); textPaint.setTypeface(Typeface.DEFAULT_BOLD); textPaint.setTextAlign(Paint.Align.CENTER);
            pillPaint.setStyle(Paint.Style.FILL);
            touchOpacity = prefs.getInt("touch_opacity", 100) / 100.0f;
            showHorizon = prefs.getBoolean("touch_horizon", true);
            applyOpacityPaints();
        }

        public void updateTouchOpacity(float op) { touchOpacity = op; applyOpacityPaints(); postInvalidate(); }
        public void updateShowHorizon(boolean show) { showHorizon = show; postInvalidate(); }
        private void applyOpacityPaints() {
            horizonPaint.setColor(Color.argb((int) (130 * touchOpacity), 255, 255, 255));
            textPaint.setColor(Color.argb((int) (120 * touchOpacity), 255, 255, 255));
            pillPaint.setColor(Color.argb((int) (60 * touchOpacity), 255, 255, 255));
        }
        public void setShowFpsOverlay(boolean show) { showFpsOverlay = show; postInvalidate(); }
        public void setTiltAngle(float degrees) { tiltDeg = degrees; postInvalidate(); }
        public void flashStatus(String msg) { statusFlashText = msg; statusFlashUntil = SystemClock.uptimeMillis() + 1500; postInvalidate(); }

        private void drawCircleBtn(Canvas c, float cx, float cy, float r, String txt, int fill, int stroke, int txtCol, float sz) {
            pillPaint.setColor(fill); c.drawCircle(cx, cy, r, pillPaint);
            strokePaint.setColor(stroke); c.drawCircle(cx, cy, r, strokePaint);
            textPaint.setColor(txtCol); textPaint.setTextSize(dp(sz)); c.drawText(txt, cx, cy + dp(sz * 0.35f), textPaint);
        }
        private void drawDpadBtn(Canvas c, float cx, float cy, float r, String sym, boolean p) {
            pillPaint.setColor(p ? 0xFF5CE68B : 0xDD18221B); c.drawRoundRect(cx - r, cy - r, cx + r, cy + r, dp(8), dp(8), pillPaint);
            strokePaint.setColor(p ? 0xFF0B1F11 : 0xFF2A3A2F); c.drawRoundRect(cx - r, cy - r, cx + r, cy + r, dp(8), dp(8), strokePaint);
            textPaint.setColor(p ? 0xFF0B1F11 : Color.WHITE); textPaint.setTextSize(dp(14)); c.drawText(sym, cx, cy + dp(4.5f), textPaint);
        }
        private void drawPillBtn(Canvas c, float x, float y, float w, float h, String label, int fill, int stroke, int txtCol) {
            pillPaint.setColor(fill); c.drawRoundRect(x, y, x + w, y + h, h * 0.5f, h * 0.5f, pillPaint);
            strokePaint.setColor(stroke); c.drawRoundRect(x, y, x + w, y + h, h * 0.5f, h * 0.5f, strokePaint);
            textPaint.setColor(txtCol); textPaint.setTextSize(dp(11)); c.drawText(label, x + w * 0.5f, y + h * 0.65f, textPaint);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            long nowMs = SystemClock.uptimeMillis();
            if (nowMs - lastFpsCalcMs >= 250) {
                try {
                    float ft = nativeGetFrameTimeMs();
                    if (ft > 0.1f) { measuredFrameMs = ft; measuredFps = 1000.0f / ft; }
                } catch (UnsatisfiedLinkError ignored) {}
                lastFpsCalcMs = nowMs;
            }

            boolean flashing = (nowMs < statusFlashUntil && statusFlashText != null);
            if (showFpsOverlay || flashing) {
                float bw = flashing ? dp(154) : dp(148), bh = dp(26);
                pillPaint.setColor(0xCC0F1411); strokePaint.setColor(0x80253328); strokePaint.setStrokeWidth(dp(1));
                canvas.drawRoundRect(dp(12), dp(12), dp(12) + bw, dp(12) + bh, dp(13), dp(13), pillPaint);
                canvas.drawRoundRect(dp(12), dp(12), dp(12) + bw, dp(12) + bh, dp(13), dp(13), strokePaint);
                pillPaint.setColor(flashing ? 0xFF5CE68B : (measuredFps >= 28.0f ? 0xFF5CE68B : (measuredFps >= 20.0f ? 0xFFFFD740 : 0xFFFF5252)));
                canvas.drawCircle(dp(22), dp(25), dp(3.5f), pillPaint);
                textPaint.setColor(Color.WHITE); textPaint.setTextSize(dp(10.5f)); textPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(flashing ? statusFlashText : String.format(Locale.ROOT, "%.1f FPS · %.1fms · %.1f°C", measuredFps, measuredFrameMs, currentBatteryTempC), dp(30), dp(28.5f), textPaint);
                textPaint.setTextAlign(Paint.Align.CENTER);
                postInvalidateOnAnimation();
            }

            float modeW = dp(84), modeH = dp(24), modeX = (w - modeW) * 0.5f, modeY = dp(12);
            drawPillBtn(canvas, modeX, modeY, modeW, modeH, inMenuMode ? "DRIVE" : "MENU", inMenuMode ? 0xEE1A2B20 : 0xCC1A1F1C, inMenuMode ? 0xFF5CE68B : 0x80354439, inMenuMode ? 0xFF5CE68B : Color.WHITE);
            drawCircleBtn(canvas, w - dp(66), dp(24), dp(14), "CAM", 0x99141816, 0x33FFFFFF, Color.WHITE, 8);
            drawCircleBtn(canvas, w - dp(24), dp(24), dp(14), "☰", 0x99141816, 0x33FFFFFF, Color.WHITE, 12);

            if (inMenuMode) {
                boolean lb = (currentButtons & BTN_LEFT_SHOULDER) != 0, rb = (currentButtons & BTN_RIGHT_SHOULDER) != 0;
                drawPillBtn(canvas, dp(16), dp(48), dp(68), dp(28), "LB", lb ? 0xFF5CE68B : 0xCC18221B, lb ? 0xFF0B1F11 : 0xFF2A3A2F, lb ? 0xFF0B1F11 : Color.WHITE);
                drawPillBtn(canvas, w - dp(84), dp(48), dp(68), dp(28), "RB", rb ? 0xFF5CE68B : 0xCC18221B, rb ? 0xFF0B1F11 : 0xFF2A3A2F, rb ? 0xFF0B1F11 : Color.WHITE);

                boolean start = (currentButtons & BTN_START) != 0, back = (currentButtons & BTN_BACK) != 0;
                drawPillBtn(canvas, modeX - dp(66), dp(12), dp(58), dp(26), "MAP", back ? 0xFF5CE68B : 0xCC18221B, back ? 0xFF0B1F11 : 0xFF2A3A2F, back ? 0xFF0B1F11 : Color.WHITE);
                drawPillBtn(canvas, modeX + modeW + dp(8), dp(12), dp(58), dp(26), "START", start ? 0xFF5CE68B : 0xCC18221B, start ? 0xFF0B1F11 : 0xFF2A3A2F, start ? 0xFF0B1F11 : Color.WHITE);

                float cx = dp(96), cy = h - dp(96);
                pillPaint.setColor(0x80121A15); canvas.drawCircle(cx, cy, dp(48), pillPaint);
                drawDpadBtn(canvas, cx, cy - dp(46), dp(22), "U", (currentButtons & BTN_DPAD_UP) != 0);
                drawDpadBtn(canvas, cx, cy + dp(46), dp(22), "D", (currentButtons & BTN_DPAD_DOWN) != 0);
                drawDpadBtn(canvas, cx - dp(46), cy, dp(22), "L", (currentButtons & BTN_DPAD_LEFT) != 0);
                drawDpadBtn(canvas, cx + dp(46), cy, dp(22), "R", (currentButtons & BTN_DPAD_RIGHT) != 0);

                float ax = w - dp(96), ay = h - dp(96);
                pillPaint.setColor(0x80121A15); canvas.drawCircle(ax, ay, dp(48), pillPaint);
                boolean bA = (currentButtons & BTN_A) != 0, bB = (currentButtons & BTN_B) != 0, bX = (currentButtons & BTN_X) != 0, bY = (currentButtons & BTN_Y) != 0;
                drawCircleBtn(canvas, ax, ay + dp(46), dp(22), "A", bA ? Color.WHITE : 0xDD1B4D2E, bA ? Color.WHITE : 0xFF5CE68B, bA ? Color.BLACK : 0xFFE8F5E9, 15);
                drawCircleBtn(canvas, ax + dp(46), ay, dp(22), "B", bB ? Color.WHITE : 0xDD8B1818, bB ? Color.WHITE : 0xFFFF5252, bB ? Color.BLACK : Color.WHITE, 15);
                drawCircleBtn(canvas, ax - dp(46), ay, dp(22), "X", bX ? Color.WHITE : 0xDD154378, bX ? Color.WHITE : 0xFF448AFF, bX ? Color.BLACK : Color.WHITE, 15);
                drawCircleBtn(canvas, ax, ay - dp(46), dp(22), "Y", bY ? Color.WHITE : 0xDD7A6014, bY ? Color.WHITE : 0xFFFFD740, bY ? Color.BLACK : 0xFFFFF9C4, 15);
                return;
            }

            float rAction = dp(20);
            pillPaint.setColor(0x99141816); strokePaint.setColor(0x33FFFFFF); strokePaint.setStrokeWidth(dp(1));
            canvas.drawCircle(dp(36), dp(60), rAction, pillPaint); canvas.drawCircle(dp(36), dp(60), rAction, strokePaint);
            textPaint.setColor(Color.WHITE); textPaint.setTextSize(dp(13)); textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("«", dp(36), dp(64.5f), textPaint);

            boolean eBrakeActive = (currentButtons & BTN_A) != 0;
            pillPaint.setColor(eBrakeActive ? 0xDD5CE68B : 0x99141816);
            strokePaint.setColor(eBrakeActive ? 0xFF0B1F11 : 0x33FFFFFF);
            canvas.drawCircle(w - dp(36), dp(60), rAction, pillPaint); canvas.drawCircle(w - dp(36), dp(60), rAction, strokePaint);
            strokePaint.setColor(eBrakeActive ? 0xFF0B1F11 : Color.WHITE); strokePaint.setStrokeWidth(dp(1.5f));
            canvas.drawCircle(w - dp(36), dp(60), dp(10), strokePaint);
            textPaint.setColor(eBrakeActive ? 0xFF0B1F11 : Color.WHITE); textPaint.setTextSize(dp(11)); textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("P", w - dp(36), dp(63.5f), textPaint);

            float arrowY = h * 0.70f, arrowR = dp(30);
            float arrowLeftX = dp(50), arrowRightX = dp(126);
            boolean lActive = steerLeftHeld, rActive = steerRightHeld;
            pillPaint.setColor(lActive ? 0xDD5CE68B : 0x8818221B); strokePaint.setColor(lActive ? 0xFF0B1F11 : 0x44FFFFFF); strokePaint.setStrokeWidth(dp(1.2f));
            canvas.drawCircle(arrowLeftX, arrowY, arrowR, pillPaint); canvas.drawCircle(arrowLeftX, arrowY, arrowR, strokePaint);
            textPaint.setColor(lActive ? 0xFF0B1F11 : 0xEEFFFFFF); textPaint.setTextSize(dp(22)); textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("‹", arrowLeftX, arrowY + dp(7), textPaint);
            pillPaint.setColor(rActive ? 0xDD5CE68B : 0x8818221B); strokePaint.setColor(rActive ? 0xFF0B1F11 : 0x44FFFFFF);
            canvas.drawCircle(arrowRightX, arrowY, arrowR, pillPaint); canvas.drawCircle(arrowRightX, arrowY, arrowR, strokePaint);
            textPaint.setColor(rActive ? 0xFF0B1F11 : 0xEEFFFFFF);
            canvas.drawText("›", arrowRightX, arrowY + dp(7), textPaint);

            if (touchOpacity <= 0.01f) return;
            float pedalH = dp(34), brkY = h * 0.36f, thrY = h * 0.70f, pX1 = w - dp(94), pX2 = w - dp(14);

            boolean bActive = currentBrake > 0.0f || brakeAlpha > 0.01f;
            pillPaint.setColor(bActive ? Color.argb((int)(130 * touchOpacity), 255, 60, 60) : Color.argb((int)(35 * touchOpacity), 35, 20, 20));
            strokePaint.setColor(bActive ? Color.argb((int)(240 * touchOpacity), 255, 80, 80) : Color.argb((int)(40 * touchOpacity), 255, 255, 255));
            strokePaint.setStrokeWidth(dp(1.2f));
            canvas.drawRoundRect(pX1, brkY - pedalH * 0.5f, pX2, brkY + pedalH * 0.5f, dp(8), dp(8), pillPaint);
            canvas.drawRoundRect(pX1, brkY - pedalH * 0.5f, pX2, brkY + pedalH * 0.5f, dp(8), dp(8), strokePaint);
            textPaint.setColor(bActive ? Color.WHITE : Color.argb((int)(150 * touchOpacity), 255, 255, 255));
            textPaint.setTextSize(dp(12));
            canvas.drawText("BRAKE", (pX1 + pX2) * 0.5f, brkY + dp(4.5f), textPaint);

            boolean tActive = currentThrottle > 0.0f;
            pillPaint.setColor(tActive ? Color.argb((int)(130 * touchOpacity), 92, 230, 139) : Color.argb((int)(35 * touchOpacity), 20, 35, 22));
            strokePaint.setColor(tActive ? Color.argb((int)(240 * touchOpacity), 92, 230, 139) : Color.argb((int)(40 * touchOpacity), 255, 255, 255));
            canvas.drawRoundRect(pX1, thrY - pedalH * 0.5f, pX2, thrY + pedalH * 0.5f, dp(8), dp(8), pillPaint);
            canvas.drawRoundRect(pX1, thrY - pedalH * 0.5f, pX2, thrY + pedalH * 0.5f, dp(8), dp(8), strokePaint);
            textPaint.setColor(tActive ? Color.WHITE : Color.argb((int)(150 * touchOpacity), 255, 255, 255));
            canvas.drawText(autoThrottle ? "AUTO GAS" : "GAS", (pX1 + pX2) * 0.5f, thrY + dp(4.5f), textPaint);

            if (bActive) {
                float a = Math.max(currentBrake, brakeAlpha);
                vignettePaint.setColor(Color.argb((int) (a * 24 * touchOpacity), 255, 60, 60));
                canvas.drawRoundRect(w - dp(36), dp(6), w - dp(6), dp(18), dp(4), dp(4), vignettePaint);
            }
            if (tActive) {
                vignettePaint.setColor(Color.argb((int) (currentThrottle * 24 * touchOpacity), 92, 230, 139));
                canvas.drawRoundRect(w - dp(36), h - dp(18), w - dp(6), h - dp(6), dp(4), dp(4), vignettePaint);
            }

            if (isGyroActive() && showHorizon) {
                canvas.save(); canvas.translate(w * 0.5f, dp(54)); canvas.rotate(-tiltDeg);
                float barW = dp(36); horizonPaint.setColor(Color.argb((int) (120 * touchOpacity), 255, 255, 255));
                canvas.drawLine(-barW, 0, barW, 0, horizonPaint); canvas.drawLine(0, -dp(4), 0, dp(4), horizonPaint); canvas.restore();
            }

            float pillW = dp(76), pillH = dp(22); float px = (w - pillW) * 0.5f, py = h - dp(28);
            pillPaint.setColor(Color.argb((int) (50 * touchOpacity), 25, 35, 30));
            canvas.drawRoundRect(px, py, px + pillW, py + pillH, dp(11), dp(11), pillPaint);
            textPaint.setColor(Color.argb((int) (110 * touchOpacity), 255, 255, 255)); textPaint.setTextSize(dp(10));
            canvas.drawText("[X] INTERACT", w * 0.5f, py + dp(14.5f), textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            int actionIdx = event.getActionIndex();
            int pointerId = event.getPointerId(actionIdx);
            int w = getWidth(), h = getHeight();

            if ("classic".equals(controlScheme) || "gamepad".equals(controlScheme)) {
                return false;
            }

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                float x = event.getX(actionIdx), y = event.getY(actionIdx);
                float modeW = dp(84), modeH = dp(24), modeX = (w - modeW) * 0.5f, modeY = dp(12);
                if (x >= modeX && x <= modeX + modeW && y >= modeY && y <= modeY + modeH) {
                    inMenuMode = !inMenuMode;
                    currentThrottle = (!inMenuMode && autoThrottle) ? 1.0f : 0.0f;
                    currentBrake = 0.0f; currentButtons = 0;
                    flushOverrides(); postInvalidate();
                    return true;
                }
                if (Math.hypot(x - (w - dp(24)), y - dp(24)) <= dp(20)) {
                    toggleQuickHud(); return true;
                }
                if (Math.hypot(x - (w - dp(66)), y - dp(24)) <= dp(20)) {
                    try { nativeAction(1, 0.0f); } catch (UnsatisfiedLinkError ignored) {}
                    vibrate(PinyonShiftActivity.this, 30, 200); toast("Photo captured"); return true;
                }
                if (inMenuMode) {
                    if (x >= modeX - dp(66) && x <= modeX - dp(8) && y >= modeY && y <= modeY + modeH) {
                        pulseButton(BTN_BACK, 150); return true;
                    }
                    if (x >= modeX + modeW + dp(8) && x <= modeX + modeW + dp(66) && y >= modeY && y <= modeY + modeH) {
                        pulseButton(BTN_START, 150); return true;
                    }
                }
            }

            if (inMenuMode) {
                int buttons = 0, pCount = event.getPointerCount();
                for (int i = 0; i < pCount; i++) {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) continue;
                    if (action == MotionEvent.ACTION_POINTER_UP && i == actionIdx) continue;
                    float px = event.getX(i), py = event.getY(i);

                    if (py >= dp(40) && py <= dp(85)) {
                        if (px >= dp(10) && px <= dp(90)) buttons |= BTN_LEFT_SHOULDER;
                        else if (px >= w - dp(90) && px <= w - dp(10)) buttons |= BTN_RIGHT_SHOULDER;
                    }

                    float dcx = px - dp(96), dcy = py - (h - dp(96));
                    if (Math.abs(dcx) < dp(80) && Math.abs(dcy) < dp(80)) {
                        if (dcy < -dp(16)) buttons |= BTN_DPAD_UP;
                        else if (dcy > dp(16)) buttons |= BTN_DPAD_DOWN;
                        if (dcx < -dp(16)) buttons |= BTN_DPAD_LEFT;
                        else if (dcx > dp(16)) buttons |= BTN_DPAD_RIGHT;
                    }

                    float dax = px - (w - dp(96)), day = py - (h - dp(96));
                    if (Math.abs(dax) < dp(80) && Math.abs(day) < dp(80)) {
                        if (day > dp(16)) buttons |= BTN_A;
                        if (dax > dp(16)) buttons |= BTN_B;
                        if (dax < -dp(16)) buttons |= BTN_X;
                        if (day < -dp(16)) buttons |= BTN_Y;
                    }
                }

                if ((buttons & ~currentButtons) != 0) vibrate(PinyonShiftActivity.this, 12, 100);
                currentButtons = buttons; currentSteerX = currentSteerY = currentThrottle = currentBrake = currentLookX = currentLookY = 0.0f;
                flushOverrides(); postInvalidate();
                return true;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = event.getX(actionIdx), y = event.getY(actionIdx);
                    long now = System.currentTimeMillis();
                    if (pointerId < MAX_P) { downX[pointerId] = x; downY[pointerId] = y; downT[pointerId] = now; }

                    if (Math.hypot(x - dp(36), y - dp(60)) <= dp(22)) {
                        pulseButton(BTN_Y, 200); vibrate(PinyonShiftActivity.this, 20, 120); toast("Rewind"); return true;
                    }

                    if (Math.hypot(x - (w - dp(36)), y - dp(60)) <= dp(22)) {
                        if (pointerId < MAX_P) zone[pointerId] = Z_EBRAKE;
                        currentButtons |= BTN_A; vibrate(PinyonShiftActivity.this, 15, 100);
                        flushOverrides(); postInvalidate(); return true;
                    }

                    if (x > w * 0.38f && x < w * 0.62f && y > h - dp(36)) { pulseButton(BTN_X, 150); return true; }

                    int z = Z_NONE;
                    if (x < dp(190) && y > h * 0.38f) {
                        z = (x < dp(88)) ? Z_STEER_LEFT : Z_STEER_RIGHT;
                        vibrate(PinyonShiftActivity.this, 15, 120);
                        try { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable ignored) {}
                    } else if (x >= w * 0.50f) {
                        z = (y < h * 0.52f) ? Z_BRAKE : Z_THROTTLE;
                        vibrate(PinyonShiftActivity.this, 15, 120);
                        try { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignored) {}
                    } else z = Z_CENTER;
                    if (pointerId < MAX_P) zone[pointerId] = z;
                    if (z == Z_STEER_LEFT || z == Z_STEER_RIGHT) updateArrowSteer();
                    else if (z == Z_BRAKE || z == Z_THROTTLE) updatePedals();
                    flushOverrides(); postInvalidate(); return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        int pId = event.getPointerId(i);
                        if (pId < MAX_P) {
                            if (zone[pId] == Z_STEER_LEFT || zone[pId] == Z_STEER_RIGHT) {
                                int nZ = (event.getX(i) < dp(88)) ? Z_STEER_LEFT : Z_STEER_RIGHT;
                                if (nZ != zone[pId]) { zone[pId] = nZ; vibrate(PinyonShiftActivity.this, 12, 100); updateArrowSteer(); }
                            } else if (zone[pId] == Z_BRAKE || zone[pId] == Z_THROTTLE) {
                                float py = event.getY(i), px = event.getX(i);
                                int nZ = (px >= w * 0.45f) ? ((py < h * 0.52f) ? Z_BRAKE : Z_THROTTLE) : Z_CENTER;
                                if (nZ != zone[pId]) { zone[pId] = nZ; vibrate(PinyonShiftActivity.this, 12, 100); updatePedals(); }
                            } else if (zone[pId] == Z_CENTER && prefs.getBoolean(PREF_LOOK_AROUND, true)) {
                                float dx = event.getX(i) - downX[pId], dy = event.getY(i) - downY[pId];
                                currentLookX = Math.min(1.0f, Math.max(-1.0f, dx / dp(80)));
                                currentLookY = Math.min(1.0f, Math.max(-1.0f, -dy / dp(80)));
                            }
                        }
                    }
                    flushOverrides(); return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int pId = event.getPointerId(actionIdx);
                    int z = pId < MAX_P ? zone[pId] : Z_NONE;
                    float dx = event.getX(actionIdx) - (pId < MAX_P ? downX[pId] : 0);
                    float dy = event.getY(actionIdx) - (pId < MAX_P ? downY[pId] : 0);
                    long dur = System.currentTimeMillis() - (pId < MAX_P ? downT[pId] : 0);

                    if (z == Z_EBRAKE) {
                        currentButtons &= ~BTN_A;
                    } else if (z == Z_STEER_LEFT || z == Z_STEER_RIGHT) {
                        if (pId < MAX_P) zone[pId] = Z_NONE;
                        updateArrowSteer();
                    } else if (z == Z_BRAKE || z == Z_THROTTLE) {
                        if (pId < MAX_P) zone[pId] = Z_NONE;
                        updatePedals();
                    } else if (z == Z_CENTER) {
                        currentLookX = currentLookY = 0.0f;
                        if (dur < 350) {
                            if (dy < -dp(35) && Math.abs(dx) < dp(45)) triggerShift(true);
                            else if (dy > dp(35) && Math.abs(dx) < dp(45)) triggerShift(false);
                            else if (dx < -dp(50) && Math.abs(dy) < dp(45)) pulseButton(BTN_Y, 250);
                            else if (Math.abs(dx) < dp(20) && Math.abs(dy) < dp(20)) {
                                long now = System.currentTimeMillis();
                                if (now - lastCenterTapTime < 320) { pulseButton(BTN_RIGHT_SHOULDER, 120); lastCenterTapTime = 0; }
                                else lastCenterTapTime = now;
                            }
                        }
                    }

                    if (pId < MAX_P) zone[pId] = Z_NONE;
                    flushOverrides(); postInvalidate(); return true;
                }

                case MotionEvent.ACTION_CANCEL: {
                    currentBrake = brakeAlpha = currentLookX = currentLookY = 0.0f;
                    currentThrottle = autoThrottle ? 1.0f : 0.0f; currentButtons = 0;
                    for (int i = 0; i < MAX_P; i++) zone[i] = Z_NONE;
                    updateArrowSteer(); updatePedals(); flushOverrides(); postInvalidate(); return true;
                }
            }
            return super.onTouchEvent(event);
        }
    }

    public static void showTouchGuide(Context context) {
        if (context instanceof PinyonShiftActivity) ((PinyonShiftActivity) context).toast("Left: Steer · Right: Brake/Gas · Top: Tuning");
    }

    static int getThemeAccent(Context ctx) {
        return ctx.getSharedPreferences("pinyon_shift_settings", MODE_PRIVATE).getInt("theme_accent", Color.rgb(92, 208, 138));
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[8192]; for (int r; (r = in.read(b)) > 0; ) out.write(b, 0, r);
    }
    static void installBundledDrivers(AssetManager a, File d) {
        try {
            String[] drv = a.list("drivers"); if (drv == null) return;
            for (String dr : drv) {
                File t = new File(d, dr); if (t.exists()) continue;
                String[] fls = a.list("drivers/" + dr); if (fls == null || fls.length == 0) continue;
                t.mkdirs();
                for (String n : fls) try (InputStream in = a.open("drivers/" + dr + "/" + n); OutputStream out = new FileOutputStream(new File(t, n))) { copy(in, out); }
            }
        } catch (IOException ignored) {}
    }
    static void deleteTree(File f) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) deleteTree(c); f.delete(); }
    static void copyTree(File s, File d) throws IOException {
        if (s.isDirectory()) { d.mkdirs(); File[] ch = s.listFiles(); if (ch != null) for (File c : ch) copyTree(c, new File(d, c.getName())); return; }
        d.getParentFile().mkdirs(); try (InputStream in = new FileInputStream(s); OutputStream out = new FileOutputStream(d)) { copy(in, out); }
    }
    static List<java.net.InetAddress> broadcastAddresses() {
        List<java.net.InetAddress> r = new ArrayList<>();
        try {
            for (java.net.NetworkInterface n : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
                if (n.isUp() && !n.isLoopback()) for (java.net.InterfaceAddress a : n.getInterfaceAddresses()) if (a.getBroadcast() != null) r.add(a.getBroadcast());
            r.add(java.net.InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {}
        return r;
    }
    static String unpackDriver(android.content.ContentResolver res, File dir, Uri uri) throws IOException {
        String base = "driver";
        try (android.database.Cursor c = res.query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) base = c.getString(0).replaceAll("(?i)\\.(zip|adpkg)$", "");
        }
        File target = new File(dir, base); if (target.exists()) deleteTree(target); target.mkdirs();
        try (InputStream raw = res.openInputStream(uri); java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(raw)) {
            for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null; zip.closeEntry()) {
                if (e.getName().contains("..")) continue;
                File out = new File(target, e.getName());
                if (e.isDirectory()) out.mkdirs();
                else { out.getParentFile().mkdirs(); try (OutputStream fos = new FileOutputStream(out)) { copy(zip, fos); } }
            }
        }
        return base;
    }

    private void toast(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() { if (rr3Overlay != null) rr3Overlay.flashStatus(text); }
        });
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
