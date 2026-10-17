package studio.deimos.pinyonshift;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.database.Cursor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import android.util.DisplayMetrics;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.libsdl.app.SDLActivity;

/**
 * Standalone Mobile Game Activity with Real Racing 3 Touch & Gyro Steering,
 * 4-Layer Touch Gestures, Native HUD Hitboxes, and Live Thermal Frame Pacing.
 */
public class PinyonShiftActivity extends SDLActivity implements View.OnClickListener {
    private static final String TAG = "PinyonShift";

    public static final String MS_2505_RETAIL_XEX_SHA256 =
            "DB40DF605ADE49A612B35A7A24C38F6004BCB17A88ED6B48288DE16DF9E3987C";
    public static final long MS_2505_RETAIL_XEX_SIZE = 20856832L;

    // Xbox 360 Controller bitmasks
    public static final int BTN_DPAD_UP = 0x0001;
    public static final int BTN_DPAD_DOWN = 0x0002;
    public static final int BTN_DPAD_LEFT = 0x0004;
    public static final int BTN_DPAD_RIGHT = 0x0008;
    public static final int BTN_START = 0x0010;
    public static final int BTN_BACK = 0x0020;
    public static final int BTN_LEFT_THUMB = 0x0040;
    public static final int BTN_RIGHT_THUMB = 0x0080;
    public static final int BTN_LEFT_SHOULDER = 0x0100;  // LB
    public static final int BTN_RIGHT_SHOULDER = 0x0200; // RB
    public static final int BTN_A = 0x1000;
    public static final int BTN_B = 0x2000;
    public static final int BTN_X = 0x4000;
    public static final int BTN_Y = 0x8000;

    public static final String PREF_SMART_CLUTCH = "pref_smart_clutch";
    public static final String PREF_GESTURE_SHIFT = "pref_gesture_shift";
    public static final String PREF_HUD_HITBOXES = "pref_hud_hitboxes";
    public static final String PREF_MENU_MODE_AUTO = "pref_menu_mode_auto";
    public static final String PREF_DUAL_HANDBRAKE = "pref_dual_handbrake";
    public static final String PREF_LOOK_AROUND = "pref_look_around";
    public static final String PREF_MANUAL_SHIFT = "pref_manual_shift";
    public static native void nativeSetPadState(float steer, float throttle, float brake, float lookX, float lookY, int buttons);
    public static native void nativeAction(int actionId, float value);
    public static native void nativeSetMobileOverrides(float steer_x, float throttle, float brake, int button_mask, float look_x, float look_y, float steer_y);
    public static native void nativeSetCVar(String name, String value);

    private File stateDir;
    private File driversDir;
    private SharedPreferences prefs;

    // Controls and Overlay
    private RR3OverlayView rr3Overlay;
    private View quickHudDrawer;

    // Sensors & Gyro Steering
    private SensorManager sensorManager;
    private Sensor gameRotationSensor;
    private float baselineRoll = 0.0f;
    private boolean gyroCalibrated = false;
    private float currentTiltDegrees = 0.0f;
    private String controlScheme = "tilt_a";
    private boolean autoThrottle = true;
    private boolean inMenuMode = true;
    private boolean showFpsOverlay = true;
    private TextView hudTempText;

    // Active input state for direct C++ synthesis
    private volatile float currentSteerX = 0.0f;
    private volatile float currentSteerY = 0.0f;
    private volatile float currentThrottle = 0.0f;
    private volatile float currentBrake = 0.0f;
    private volatile float currentLookX = 0.0f;
    private volatile float currentLookY = 0.0f;
    private volatile int currentButtons = 0;

    // Thermal Frame Pacer
    private BroadcastReceiver thermalReceiver;
    private float currentBatteryTempC = 25.0f;
    private boolean thermalPacerEnabled = true;
    private int currentFpsTarget = 30;

    // Photo Observer
    private FileObserver photoObserver;
    private static Vibrator sVibrator;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File external = getExternalFilesDir(null);
        File base = external != null ? external : getFilesDir();
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
            String recommended = recommendedDriver(adreno);
            if (recommended != null) {
                setDefaultEnvironment("REX_ANDROID_RECOMMENDED_DRIVER", recommended);
            }
        }

        if (new File(base, "state/cache/rally_adapter/rally-stage.toml").isFile()) {
            setDefaultEnvironment("PINYON_SHIFT_RALLY_PREPARED", "1");
        }

        File manifest = new File(getFilesDir(), "pinyon_shift_build.json");
        try (InputStream in = getAssets().open("pinyon_shift_build.json");
             OutputStream out = new FileOutputStream(manifest)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, read);
            }
            setEnvironment("PINYON_SHIFT_BUILD_MANIFEST", manifest.getAbsolutePath());
        } catch (IOException error) {
            Log.w(TAG, "no build manifest in the package", error);
        }

        Intent intent = getIntent();
        Bundle extras = intent != null ? intent.getExtras() : null;
        if (extras != null) {
            for (String key : extras.keySet()) {
                if (key.startsWith("ENV_")) {
                    setEnvironment(key.substring(4), extras.getString(key, ""));
                }
            }
        }

        prefs = getSharedPreferences("pinyon_shift_settings", MODE_PRIVATE);
        controlScheme = prefs.getString("control_scheme", "tilt_a");
        autoThrottle = prefs.getBoolean("auto_throttle", true);
        showFpsOverlay = prefs.getBoolean("fps_overlay", true);
        thermalPacerEnabled = prefs.getBoolean("thermal_pacer", true);
        if (autoThrottle) currentThrottle = 1.0f;

        super.onCreate(savedInstanceState);

        int res = prefs.getInt("res_scale", 720);
        if (res <= 2) {
            res = (res == 2) ? 1440 : 720;
            prefs.edit().putInt("res_scale", res).apply();
        }
        applySurfaceResolution(res);

        initHaptics();
        initSensors();
        initThermalPacer();
        startPhotoWatcher(new File(stateDir, "photos"));
        attachMobileOverlays();
    }

    private void applySurfaceResolution(int s) {
        if (mSurface == null) return;
        ViewGroup.LayoutParams lp = mSurface.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mSurface.setLayoutParams(lp);
        }
        if (s == 540 || s == 360) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float asp = (float) Math.max(dm.widthPixels, dm.heightPixels) / (float) Math.max(1, Math.min(dm.widthPixels, dm.heightPixels));
            int w = Math.round(s * asp);
            mSurface.getHolder().setFixedSize(w, s);
        } else {
            mSurface.getHolder().setSizeFromLayout();
        }
    }

    private void confirmQuit() {
        new AlertDialog.Builder(this)
                .setTitle("Quit Game")
                .setMessage("Exit Pinyon Shift and return to launcher?")
                .setPositiveButton("Quit", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        finishAffinity();
                        System.exit(0);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
        applySurfaceResolution(prefs.getInt("res_scale", 720));
        applyDisplayRefreshRate(currentFpsTarget);
        if (sensorManager != null && gameRotationSensor != null && "tilt_a".equals(controlScheme)) {
            sensorManager.registerListener(sensorListener, gameRotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    private void applyDisplayRefreshRate(int targetFps) {
        if (Build.VERSION.SDK_INT >= 30 && getWindow() != null) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            if (targetFps == 30 || targetFps == 60) params.preferredRefreshRate = 60.0f;
            else if (targetFps == 120) params.preferredRefreshRate = 120.0f;
            getWindow().setAttributes(params);
        }
        if (Build.VERSION.SDK_INT >= 30 && mSurface != null && mSurface.getHolder() != null && mSurface.getHolder().getSurface() != null) {
            try {
                mSurface.getHolder().getSurface().setFrameRate((float) targetFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            } catch (Throwable ignored) {}
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
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK) {
                inMenuMode = !inMenuMode;
                pulseButton(inMenuMode ? BTN_START : BTN_B, 120);
                if (rr3Overlay != null) rr3Overlay.postInvalidate();
                return true;
            }
            int b = code == KeyEvent.KEYCODE_DPAD_UP ? BTN_DPAD_UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? BTN_DPAD_DOWN :
                    code == KeyEvent.KEYCODE_DPAD_LEFT ? BTN_DPAD_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? BTN_DPAD_RIGHT :
                    (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE) ? BTN_A :
                    code == KeyEvent.KEYCODE_BUTTON_B ? BTN_B : code == KeyEvent.KEYCODE_BUTTON_X ? BTN_X :
                    code == KeyEvent.KEYCODE_BUTTON_Y ? BTN_Y : code == KeyEvent.KEYCODE_BUTTON_START ? BTN_START : 0;
            if (b != 0) { pulseButton(b, 120); return true; }
        }
        return super.dispatchKeyEvent(event);
    }

    private void flushOverrides() {
        try {
            nativeSetPadState(currentSteerX, currentThrottle, currentBrake, currentLookX, currentLookY, currentButtons);
        } catch (UnsatisfiedLinkError ignored) {}
    }

    private void pulseButton(final int mask, int durationMs) {
        currentButtons |= mask;
        flushOverrides();
        vibrate(this, 15, 120);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                currentButtons &= ~mask;
                flushOverrides();
                if (rr3Overlay != null) rr3Overlay.postInvalidate();
            }
        }, Math.max(50, durationMs));
    }

    private void triggerShift(final boolean shiftUp) {
        boolean smartClutch = prefs.getBoolean(PREF_SMART_CLUTCH, true);
        final int shiftBtn = shiftUp ? BTN_B : BTN_X;
        if (smartClutch) {
            currentButtons |= BTN_LEFT_SHOULDER; // Clutch LB
            flushOverrides();
            vibrate(this, 10, 100);
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    currentButtons |= shiftBtn;
                    flushOverrides();
                }
            }, 40);
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    currentButtons &= ~(BTN_LEFT_SHOULDER | shiftBtn);
                    flushOverrides();
                }
            }, 80);
        } else {
            pulseButton(shiftBtn, 90);
        }
    }

    static String adrenoModel() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpu_model"))) {
            String line = reader.readLine();
            Matcher match = line != null ? Pattern.compile("Adreno.*?(\\d{3})").matcher(line) : null;
            if (match != null && match.find()) return match.group(1);
        } catch (IOException ignored) {}
        String hw = ((Build.HARDWARE != null ? Build.HARDWARE : "") + " " + (Build.BOARD != null ? Build.BOARD : "")
                + " " + (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null ? Build.SOC_MODEL : "")).toUpperCase(Locale.ROOT);
        String[][] m = {{"SM8750", "830"}, {"SM7635", "810"}, {"VOLCANO", "810"}, {"SM8650", "750"},
                {"SM8550", "740"}, {"QCS8550", "740"}, {"SM8635", "735"}, {"SM7675", "732"},
                {"SM8450", "730"}, {"SM8475", "730"}, {"SM7475", "725"}, {"SM7550", "720"},
                {"SM8350", "660"}, {"SM8250", "650"}};
        for (String[] p : m) if (hw.contains(p[0])) return p[1];
        return null;
    }

    static String recommendedDriver(String adreno) {
        if (adreno == null || adreno.isEmpty()) return null;
        try {
            if (Integer.parseInt(adreno) >= 600) return "turnip-gen8-v37";
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private void installBundledDrivers() {
        installBundledDrivers(getAssets(), driversDir);
    }

    private static void setDefaultEnvironment(String name, String value) {
        if (Os.getenv(name) == null) setEnvironment(name, value);
    }

    private static void setEnvironment(String name, String value) {
        try { Os.setenv(name, value, true); } catch (ErrnoException ignored) {}
    }

    // --- Sensors & Tilt Steering ---

    private void initSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (gameRotationSensor == null) {
                gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            }
        }
    }

    private final SensorEventListener sensorListener = new SensorEventListener() {
        private final float[] rotationMatrix = new float[9];
        private final float[] orientationAngles = new float[3];

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!"tilt_a".equals(controlScheme) || inMenuMode) return;
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientationAngles);
            float rollRad = orientationAngles[2];
            float rollDeg = (float) Math.toDegrees(rollRad);

            if (!gyroCalibrated) {
                baselineRoll = rollDeg;
                gyroCalibrated = true;
            }

            float delta = rollDeg - baselineRoll;
            if (delta > 180.0f) delta -= 360.0f;
            if (delta < -180.0f) delta += 360.0f;
            currentTiltDegrees = delta;

            float deadband = 2.0f;
            float maxAngle = 32.0f;
            float steer = 0.0f;
            if (Math.abs(delta) > deadband) {
                float effective = (Math.abs(delta) - deadband) / (maxAngle - deadband);
                effective = Math.min(1.0f, Math.max(0.0f, effective));
                float curved = (float) Math.pow(effective, 1.4);
                steer = Math.copySign(curved, delta);
            }
            currentSteerX = steer;
            flushOverrides();

            if (rr3Overlay != null) {
                rr3Overlay.setTiltAngle(currentTiltDegrees);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    public void recalibrateGyro() {
        gyroCalibrated = false;
        toast("Gyro baseline zeroed to current position");
    }

    // --- Haptics ---

    private void initHaptics() {
        sVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }

    public static void vibrate(Context context, int ms, int amplitude) {
        if (sVibrator == null && context != null) {
            sVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (sVibrator == null || !sVibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) {
            sVibrator.vibrate(VibrationEffect.createOneShot(ms, Math.min(255, Math.max(1, amplitude))));
        } else {
            sVibrator.vibrate(ms);
        }
    }

    // --- In-Game Mobile Overlays ---

    private void attachMobileOverlays() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ViewGroup content = findViewById(android.R.id.content);
                if (content == null) return;

                rr3Overlay = new RR3OverlayView(PinyonShiftActivity.this);
                content.addView(rr3Overlay, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                quickHudDrawer = createQuickHudDrawer();
                quickHudDrawer.setVisibility(View.GONE);
                content.addView(quickHudDrawer);
            }
        });
    }

    private View bindClick(View v, Object tag) {
        v.setTag(tag);
        v.setOnClickListener(this);
        return v;
    }

    private void addHudRow(LinearLayout items, String title, String[] labels, String[] tags, int activeIdx) {
        addHudSectionTitle(items, title);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            Button b = createChipButton(labels[i], i == activeIdx);
            row.addView(bindClick(b, tags[i]), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        }
        items.addView(row);
    }

    private void toggleQuickHud() {
        if (quickHudDrawer == null) return;
        boolean show = quickHudDrawer.getVisibility() != View.VISIBLE;
        if (show) {
            readBatteryTemp(registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
            if (hudTempText != null) {
                hudTempText.setText(String.format(Locale.ROOT, "Battery Temp: %.1f°C", currentBatteryTempC));
            }
            quickHudDrawer.bringToFront();
        }
        quickHudDrawer.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private View createQuickHudDrawer() {
        int accent = getThemeAccent(this);
        FrameLayout drawer = new FrameLayout(this);
        drawer.setBackgroundColor(Color.argb(245, 16, 24, 19));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.START;
        drawer.setLayoutParams(lp);

        ScrollView scroll = new ScrollView(this);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("FH1 Live Tuning");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(accent);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Button closeBtn = createChipButton("✕ Close", false);
        bindClick(closeBtn, "hud_close");
        header.addView(closeBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        items.addView(header);

        hudTempText = new TextView(this);
        hudTempText.setText(String.format(Locale.ROOT, "Battery Temp: %.1f°C", currentBatteryTempC));
        hudTempText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hudTempText.setTextColor(Color.rgb(147, 165, 151));
        hudTempText.setPadding(0, dp(4), 0, dp(10));
        items.addView(hudTempText);

        addHudSectionTitle(items, "Quick Toggles");
        Button autoBtn = createChipButton(autoThrottle ? "Auto Accelerate: ON (Brake to Stop)" : "Auto Accelerate: OFF (Manual RT)", autoThrottle);
        items.addView(bindClick(autoBtn, "toggle_auto_throttle"));

        Button fpsOvBtn = createChipButton(showFpsOverlay ? "Live Performance Overlay: ON" : "Live Performance Overlay: OFF", showFpsOverlay);
        items.addView(bindClick(fpsOvBtn, "toggle_fps_overlay"));

        Button menuBtn = createChipButton(inMenuMode ? "Menu Mode D-Pad: ACTIVE" : "Menu Mode D-Pad: OFF", inMenuMode);
        items.addView(bindClick(menuBtn, "toggle_menu_mode"));

        addHudRow(items, "Target FPS (Live VRR)", new String[]{"30 FPS", "45 FPS", "60 FPS", "120 FPS"},
                new String[]{"fps_30", "fps_45", "fps_60", "fps_120"},
                currentFpsTarget == 120 ? 3 : currentFpsTarget == 60 ? 2 : currentFpsTarget == 45 ? 1 : 0);

        addHudRow(items, "Render Preset", new String[]{"Potato", "Balanced", "Ultra"},
                new String[]{"preset_potato", "preset_balanced", "preset_ultra"}, -1);

        addHudRow(items, "Output Upscaler", new String[]{"FSR 1.0", "CAS", "Bilinear"},
                new String[]{"up_fsr", "up_cas", "up_bilinear"}, -1);

        addHudSectionTitle(items, "Vertical Sync (VSync)");
        boolean vsyncOn = prefs.getBoolean("vsync", true);
        items.addView(bindClick(createChipButton(vsyncOn ? "VSync: ENABLED" : "VSync: DISABLED", vsyncOn), "toggle_vsync"));

        int curScale = prefs.getInt("res_scale", 720);
        addHudRow(items, "Internal Resolution", new String[]{"720p", "540p FSR", "360p FSR"},
                new String[]{"res_720", "res_540", "res_360"},
                curScale == 360 ? 2 : curScale == 540 ? 1 : 0);

        int curOp = prefs.getInt("touch_opacity", 100);
        addHudRow(items, "Touch Controls Customization", new String[]{"100%", "75%", "50%", "Hidden"},
                new String[]{"op_100", "op_75", "op_50", "op_0"},
                curOp == 0 ? 3 : curOp == 50 ? 2 : curOp == 75 ? 1 : 0);

        boolean curHor = prefs.getBoolean("touch_horizon", true);
        Button horBtn = createChipButton(curHor ? "Steering Horizon: ON" : "Steering Horizon: OFF", curHor);
        items.addView(bindClick(horBtn, "toggle_horizon"));

        // Controls & Guide
        addHudSectionTitle(items, "Controls & Help");
        Button calBtn = createChipButton("Recalibrate Gyro 0°", true);
        items.addView(bindClick(calBtn, "gyro_cal"));

        Button guideBtn = createChipButton("Touch Controls Guide", false);
        items.addView(bindClick(guideBtn, "touch_guide"));

        Button quitBtn = createChipButton("Quit to Launcher", false);
        GradientDrawable qgd = (GradientDrawable) quitBtn.getBackground();
        if (qgd != null) qgd.setColor(0xDD3A1618);
        quitBtn.setTextColor(0xFFFF6B6B);
        items.addView(bindClick(quitBtn, "quit_game"));

        scroll.addView(items);
        drawer.addView(scroll);
        return drawer;
    }

    @Override
    public void onClick(View v) {
        Object tag = v.getTag();
        if (tag == null) return;
        String t = tag.toString();
        if ("hud_toggle".equals(t) || "hud_close".equals(t)) {
            toggleQuickHud();
        } else if ("quit_game".equals(t)) {
            toggleQuickHud();
            confirmQuit();
        } else if ("toggle_auto_throttle".equals(t)) {
            autoThrottle = !autoThrottle;
            prefs.edit().putBoolean("auto_throttle", autoThrottle).apply();
            if (!inMenuMode) currentThrottle = autoThrottle ? 1.0f : 0.0f;
            flushOverrides();
            toast("Auto Accelerate: " + (autoThrottle ? "ON (Brake to Stop)" : "OFF"));
            toggleQuickHud();
        } else if ("toggle_fps_overlay".equals(t)) {
            showFpsOverlay = !showFpsOverlay;
            prefs.edit().putBoolean("fps_overlay", showFpsOverlay).apply();
            if (rr3Overlay != null) rr3Overlay.setShowFpsOverlay(showFpsOverlay);
            toast("Performance Overlay: " + (showFpsOverlay ? "ON" : "OFF"));
            toggleQuickHud();
        } else if ("toggle_menu_mode".equals(t)) {
            inMenuMode = !inMenuMode;
            if (inMenuMode) currentThrottle = 0.0f;
            else if (autoThrottle) currentThrottle = 1.0f;
            flushOverrides();
            if (rr3Overlay != null) rr3Overlay.postInvalidate();
            toast("Menu Mode: " + (inMenuMode ? "ACTIVE (On-Screen D-Pad)" : "DRIVING"));
            toggleQuickHud();
        } else if (t.startsWith("fps_")) {
            int f = Integer.parseInt(t.substring(4));
            setPacedFpsTarget(f, "FPS set to " + f);
            toggleQuickHud();
        } else if (t.startsWith("preset_")) {
            String p = t.substring(7);
            applyPreset(p);
            toast("Preset applied: " + p);
            toggleQuickHud();
        } else if (t.startsWith("up_")) {
            String u = t.substring(3);
            setUpscaler(u);
            toast("Upscaler set to: " + u.toUpperCase(Locale.ROOT));
            toggleQuickHud();
        } else if ("toggle_vsync".equals(t)) {
            boolean next = !prefs.getBoolean("vsync", true);
            prefs.edit().putBoolean("vsync", next).apply();
            try { nativeSetCVar("vsync", next ? "true" : "false"); } catch (UnsatisfiedLinkError ignored) {}
            toast("VSync: " + (next ? "ENABLED" : "DISABLED"));
            toggleQuickHud();
        } else if (t.startsWith("res_")) {
            int s = Integer.parseInt(t.substring(4));
            prefs.edit().putInt("res_scale", s).apply();
            applySurfaceResolution(s);
            toast("Resolution: " + s + "p");
            toggleQuickHud();
        } else if (t.startsWith("op_")) {
            int op = Integer.parseInt(t.substring(3));
            prefs.edit().putInt("touch_opacity", op).apply();
            if (rr3Overlay != null) rr3Overlay.updateTouchOpacity(op / 100.0f);
            toast("Touch Opacity: " + (op == 0 ? "Hidden" : op + "%"));
            toggleQuickHud();
        } else if ("toggle_horizon".equals(t)) {
            boolean next = !prefs.getBoolean("touch_horizon", true);
            prefs.edit().putBoolean("touch_horizon", next).apply();
            if (rr3Overlay != null) rr3Overlay.updateShowHorizon(next);
            toast("Steering Horizon: " + (next ? "ON" : "OFF"));
            toggleQuickHud();
        } else if ("gyro_cal".equals(t)) {
            recalibrateGyro();
            toggleQuickHud();
        } else if ("touch_guide".equals(t)) {
            toggleQuickHud();
            showTouchGuide(this);
        } else if ("shutter".equals(t)) {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_F8);
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_F8);
            vibrate(this, 20, 180);
            toast("Capturing photo...");
        }
    }

    private void addHudSectionTitle(LinearLayout container, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(Color.rgb(147, 165, 151));
        tv.setPadding(0, dp(10), 0, dp(4));
        container.addView(tv);
    }

    private Button createChipButton(String text, boolean active) {
        int accent = getThemeAccent(this);
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTextColor(active ? Color.rgb(10, 15, 12) : Color.rgb(238, 243, 236));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(active ? accent : Color.rgb(22, 33, 26));
        gd.setCornerRadius(dp(8));
        b.setBackground(gd);
        b.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void applyPreset(String preset) {
        try {
            if ("potato".equals(preset)) {
                nativeSetCVar("pinyon_shift_fh1_shadows", "false");
                nativeSetCVar("pinyon_shift_fh1_env_map_rate", "0.25");
                nativeSetCVar("present_effect", "bilinear");
            } else if ("ultra".equals(preset)) {
                nativeSetCVar("pinyon_shift_fh1_shadows", "true");
                nativeSetCVar("pinyon_shift_fh1_env_map_rate", "1.0");
                nativeSetCVar("present_effect", "cas");
            } else {
                nativeSetCVar("pinyon_shift_fh1_shadows", "true");
                nativeSetCVar("pinyon_shift_fh1_env_map_rate", "0.5");
                nativeSetCVar("present_effect", "fsr");
            }
        } catch (UnsatisfiedLinkError ignored) {}
    }

    private void setUpscaler(String effect) {
        try {
            nativeSetCVar("present_effect", effect);
        } catch (UnsatisfiedLinkError ignored) {}
    }

    // --- Thermal Frame Pacer ---

    private void initThermalPacer() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        thermalReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                readBatteryTemp(intent);
                checkThermalPacing();
            }
        };
        Intent sticky = registerReceiver(thermalReceiver, filter);
        readBatteryTemp(sticky);
    }

    private void readBatteryTemp(Intent intent) {
        if (intent != null) {
            int raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            if (raw > 0) currentBatteryTempC = raw / 10.0f;
        }
        if (currentBatteryTempC <= 0.0f || currentBatteryTempC == 25.0f) {
            try {
                File f = new File("/sys/class/power_supply/battery/temp");
                if (f.exists()) {
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String l = br.readLine();
                    br.close();
                    if (l != null) {
                        float v = Float.parseFloat(l.trim());
                        currentBatteryTempC = (v > 100.0f) ? (v / 10.0f) : v;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void checkThermalPacing() {
        if (!thermalPacerEnabled) return;
        if (currentBatteryTempC >= 44.0f && currentFpsTarget > 30) {
            setPacedFpsTarget(30, "Thermal alert (44°C+) · Paced to 30 FPS");
        } else if (currentBatteryTempC >= 42.0f && currentFpsTarget > 45) {
            setPacedFpsTarget(45, "Thermal threshold (42°C) · Paced to 45 FPS");
        } else if (currentBatteryTempC < 39.5f && currentFpsTarget < 60) {
            setPacedFpsTarget(60, "Thermals normalized (<40°C) · Restored 60 FPS");
        }
    }

    private void setPacedFpsTarget(int fps, String msg) {
        currentFpsTarget = fps;
        toast(msg);
        try {
            nativeSetCVar("pinyon_shift_fh1_render_fps_limit", String.valueOf(fps));
        } catch (UnsatisfiedLinkError ignored) {}
    }

    // --- MediaStore Native Photo Mode Integration ---

    private void startPhotoWatcher(final File photosDir) {
        if (!photosDir.exists()) photosDir.mkdirs();
        int mask = FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO;
        photoObserver = new FileObserver(photosDir.getAbsolutePath(), mask) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null || !path.endsWith(".png")) return;
                final File photo = new File(photosDir, path);
                if (!photo.isFile()) return;
                exportToGallery(photo);
            }
        };
        photoObserver.startWatching();
    }

    private void exportToGallery(final File photo) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, photo.getName());
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ForzaHorizon");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (InputStream in = new FileInputStream(photo);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    copy(in, out);
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                }
                vibrate(this, 30, 200);
                toast("Saved to Pictures/ForzaHorizon");
            }
        } catch (Exception error) {
            Log.e(TAG, "failed saving screenshot to gallery", error);
        }
    }

    // --- Real Racing 3 4-Layer Touch Overlay View ---

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

        private static final int Z_NONE = 0;
        private static final int Z_BRAKE = 1;
        private static final int Z_THROTTLE = 2;
        private static final int Z_CENTER = 3;

        private long lastCenterTapTime = 0;
        private boolean leftHeld = false;
        private boolean rightHeld = false;
        private float touchOpacity = 1.0f;
        private boolean showHorizon = true;

        private float measuredFps = 30.0f;
        private float measuredFrameMs = 33.3f;
        private int framesDrawn = 0;
        private long lastFpsCalcMs = 0;

        public RR3OverlayView(Context context) {
            super(context);
            horizonPaint.setStrokeWidth(dp(2));
            horizonPaint.setStyle(Paint.Style.STROKE);

            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(1.2f));

            textPaint.setTextSize(dp(13));
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);

            pillPaint.setStyle(Paint.Style.FILL);

            touchOpacity = prefs.getInt("touch_opacity", 100) / 100.0f;
            showHorizon = prefs.getBoolean("touch_horizon", true);
            applyOpacityPaints();
        }

        public void updateTouchOpacity(float op) {
            touchOpacity = op;
            applyOpacityPaints();
            postInvalidate();
        }

        public void updateShowHorizon(boolean show) {
            showHorizon = show;
            postInvalidate();
        }

        private void applyOpacityPaints() {
            horizonPaint.setColor(Color.argb((int) (130 * touchOpacity), 255, 255, 255));
            textPaint.setColor(Color.argb((int) (120 * touchOpacity), 255, 255, 255));
            pillPaint.setColor(Color.argb((int) (60 * touchOpacity), 255, 255, 255));
        }

        public void setShowFpsOverlay(boolean show) {
            showFpsOverlay = show;
            postInvalidate();
        }

        public void setTiltAngle(float degrees) {
            tiltDeg = degrees;
            postInvalidate();
        }

        private void drawDpadBtn(Canvas c, float cx, float cy, float r, String sym, boolean pressed) {
            pillPaint.setColor(pressed ? 0xFF5CE68B : 0xDD18221B);
            c.drawRoundRect(cx - r, cy - r, cx + r, cy + r, dp(8), dp(8), pillPaint);
            strokePaint.setColor(pressed ? 0xFF0B1F11 : 0xFF2A3A2F);
            c.drawRoundRect(cx - r, cy - r, cx + r, cy + r, dp(8), dp(8), strokePaint);
            textPaint.setColor(pressed ? 0xFF0B1F11 : Color.WHITE);
            textPaint.setTextSize(dp(16));
            c.drawText(sym, cx, cy + dp(5.5f), textPaint);
        }

        private void drawActionCircle(Canvas c, float cx, float cy, float r, String txt, int fillCol, int strokeCol, int txtCol, boolean pressed) {
            pillPaint.setColor(pressed ? Color.WHITE : fillCol);
            c.drawCircle(cx, cy, r, pillPaint);
            strokePaint.setColor(pressed ? Color.WHITE : strokeCol);
            c.drawCircle(cx, cy, r, strokePaint);
            textPaint.setColor(pressed ? Color.BLACK : txtCol);
            textPaint.setTextSize(dp(15));
            c.drawText(txt, cx, cy + dp(5.5f), textPaint);
        }

        private void drawPillBtn(Canvas c, float x, float y, float w, float h, String label, int fill, int stroke, int txtCol) {
            pillPaint.setColor(fill);
            c.drawRoundRect(x, y, x + w, y + h, h * 0.5f, h * 0.5f, pillPaint);
            strokePaint.setColor(stroke);
            c.drawRoundRect(x, y, x + w, y + h, h * 0.5f, h * 0.5f, strokePaint);
            textPaint.setColor(txtCol);
            textPaint.setTextSize(dp(11));
            c.drawText(label, x + w * 0.5f, y + h * 0.65f, textPaint);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            framesDrawn++;
            long nowMs = SystemClock.uptimeMillis();
            long dt = nowMs - lastFpsCalcMs;
            if (dt >= 500) {
                measuredFps = (framesDrawn * 1000.0f) / (float) dt;
                measuredFrameMs = (float) dt / (float) Math.max(1, framesDrawn);
                framesDrawn = 0;
                lastFpsCalcMs = nowMs;
            }

            if (showFpsOverlay) {
                float badgeW = dp(142), badgeH = dp(26);
                pillPaint.setColor(0xCC0F1411);
                canvas.drawRoundRect(dp(12), dp(12), dp(12) + badgeW, dp(12) + badgeH, dp(13), dp(13), pillPaint);
                strokePaint.setColor(0x80253328);
                canvas.drawRoundRect(dp(12), dp(12), dp(12) + badgeW, dp(12) + badgeH, dp(13), dp(13), strokePaint);
                pillPaint.setColor(measuredFps >= 28.0f ? 0xFF5CE68B : (measuredFps >= 20.0f ? 0xFFFFD740 : 0xFFFF5252));
                canvas.drawCircle(dp(22), dp(25), dp(3.5f), pillPaint);
                textPaint.setColor(Color.WHITE);
                textPaint.setTextSize(dp(10.5f));
                textPaint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(String.format(Locale.ROOT, "%.1f FPS · %.1fms · %.1f°C", measuredFps, measuredFrameMs, currentBatteryTempC),
                        dp(30), dp(28.5f), textPaint);
                textPaint.setTextAlign(Paint.Align.CENTER);
                postInvalidateOnAnimation();
            }

            float modeW = dp(104), modeH = dp(26);
            float modeX = (w - modeW) * 0.5f, modeY = dp(12);
            if (inMenuMode) {
                drawPillBtn(canvas, modeX, modeY, modeW, modeH, "🎮 DRIVE", 0xEE1A2B20, 0xFF5CE68B, 0xFF5CE68B);
            } else {
                drawPillBtn(canvas, modeX, modeY, modeW, modeH, "🧭 MENU", 0xCC1A1F1C, 0x80354439, Color.WHITE);
            }

            pillPaint.setColor(0xCC1A241E);
            strokePaint.setColor(0x802E4233);
            canvas.drawCircle(w - dp(110), dp(25), dp(13), pillPaint);
            canvas.drawCircle(w - dp(110), dp(25), dp(13), strokePaint);
            textPaint.setTextSize(dp(13));
            textPaint.setColor(0xFFFF5252);
            canvas.drawText("✕", w - dp(110), dp(29.5f), textPaint);

            canvas.drawCircle(w - dp(74), dp(25), dp(13), pillPaint);
            canvas.drawCircle(w - dp(74), dp(25), dp(13), strokePaint);
            textPaint.setTextSize(dp(12));
            textPaint.setColor(Color.WHITE);
            canvas.drawText("📷", w - dp(74), dp(29), textPaint);

            canvas.drawCircle(w - dp(38), dp(25), dp(13), pillPaint);
            canvas.drawCircle(w - dp(38), dp(25), dp(13), strokePaint);
            canvas.drawText("⚙", w - dp(38), dp(29.5f), textPaint);

            if (inMenuMode) {
                boolean lbPressed = (currentButtons & BTN_LEFT_SHOULDER) != 0;
                boolean rbPressed = (currentButtons & BTN_RIGHT_SHOULDER) != 0;
                drawPillBtn(canvas, dp(16), dp(48), dp(68), dp(28), "LB", lbPressed ? 0xFF5CE68B : 0xCC18221B, lbPressed ? 0xFF0B1F11 : 0xFF2A3A2F, lbPressed ? 0xFF0B1F11 : Color.WHITE);
                drawPillBtn(canvas, w - dp(84), dp(48), dp(68), dp(28), "RB", rbPressed ? 0xFF5CE68B : 0xCC18221B, rbPressed ? 0xFF0B1F11 : 0xFF2A3A2F, rbPressed ? 0xFF0B1F11 : Color.WHITE);

                boolean startPressed = (currentButtons & BTN_START) != 0;
                boolean backPressed = (currentButtons & BTN_BACK) != 0;
                drawPillBtn(canvas, modeX - dp(66), dp(12), dp(58), dp(26), "MAP", backPressed ? 0xFF5CE68B : 0xCC18221B, backPressed ? 0xFF0B1F11 : 0xFF2A3A2F, backPressed ? 0xFF0B1F11 : Color.WHITE);
                drawPillBtn(canvas, modeX + modeW + dp(8), dp(12), dp(58), dp(26), "START", startPressed ? 0xFF5CE68B : 0xCC18221B, startPressed ? 0xFF0B1F11 : 0xFF2A3A2F, startPressed ? 0xFF0B1F11 : Color.WHITE);

                float cx = dp(96), cy = h - dp(96);
                pillPaint.setColor(0x80121A15);
                canvas.drawCircle(cx, cy, dp(48), pillPaint);
                drawDpadBtn(canvas, cx, cy - dp(46), dp(22), "▲", (currentButtons & BTN_DPAD_UP) != 0);
                drawDpadBtn(canvas, cx, cy + dp(46), dp(22), "▼", (currentButtons & BTN_DPAD_DOWN) != 0);
                drawDpadBtn(canvas, cx - dp(46), cy, dp(22), "◀", (currentButtons & BTN_DPAD_LEFT) != 0);
                drawDpadBtn(canvas, cx + dp(46), cy, dp(22), "▶", (currentButtons & BTN_DPAD_RIGHT) != 0);

                float ax = w - dp(96), ay = h - dp(96);
                pillPaint.setColor(0x80121A15);
                canvas.drawCircle(ax, ay, dp(48), pillPaint);
                drawActionCircle(canvas, ax, ay + dp(46), dp(22), "A", 0xDD1B4D2E, 0xFF5CE68B, 0xFFE8F5E9, (currentButtons & BTN_A) != 0);
                drawActionCircle(canvas, ax + dp(46), ay, dp(22), "B", 0xDD8B1818, 0xFFFF5252, Color.WHITE, (currentButtons & BTN_B) != 0);
                drawActionCircle(canvas, ax - dp(46), ay, dp(22), "X", 0xDD154378, 0xFF448AFF, Color.WHITE, (currentButtons & BTN_X) != 0);
                drawActionCircle(canvas, ax, ay - dp(46), dp(22), "Y", 0xDD7A6014, 0xFFFFD740, 0xFFFFF9C4, (currentButtons & BTN_Y) != 0);
                return;
            }

            if (touchOpacity <= 0.01f) return;

            if (currentBrake > 0.0f || brakeAlpha > 0.01f) {
                float a = Math.max(currentBrake, brakeAlpha);
                vignettePaint.setColor(Color.argb((int) (a * 200 * touchOpacity), 255, 50, 50));
                canvas.drawRect(0, 0, dp(6), h, vignettePaint);
            }
            if (currentThrottle > 0.0f) {
                vignettePaint.setColor(Color.argb((int) (currentThrottle * 180 * touchOpacity), 92, 230, 139));
                canvas.drawRect(w - dp(6), 0, w, h, vignettePaint);
            }

            if ("tilt_a".equals(controlScheme) && showHorizon) {
                canvas.save();
                canvas.translate(w * 0.5f, dp(54));
                canvas.rotate(-tiltDeg);
                float barW = dp(36);
                horizonPaint.setColor(Color.argb((int) (120 * touchOpacity), 255, 255, 255));
                canvas.drawLine(-barW, 0, barW, 0, horizonPaint);
                canvas.drawLine(0, -dp(4), 0, dp(4), horizonPaint);
                canvas.restore();
            }

            textPaint.setColor(Color.argb((int) (80 * touchOpacity), 255, 255, 255));
            textPaint.setTextSize(dp(11));
            canvas.drawText("BRAKE", w * 0.16f, h - dp(18), textPaint);
            canvas.drawText(autoThrottle ? "AUTO RT" : "THROTTLE", w * 0.84f, h - dp(18), textPaint);

            float pillW = dp(76), pillH = dp(22);
            float px = (w - pillW) * 0.5f, py = h - dp(28);
            pillPaint.setColor(Color.argb((int) (50 * touchOpacity), 25, 35, 30));
            canvas.drawRoundRect(px, py, px + pillW, py + pillH, dp(11), dp(11), pillPaint);
            textPaint.setColor(Color.argb((int) (110 * touchOpacity), 255, 255, 255));
            textPaint.setTextSize(dp(10));
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

            if (inMenuMode) {
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                    float x = event.getX(actionIdx), y = event.getY(actionIdx);
                    float modeW = dp(104), modeH = dp(26);
                    float modeX = (w - modeW) * 0.5f, modeY = dp(12);
                    if (x >= modeX && x <= modeX + modeW && y >= modeY && y <= modeY + modeH) {
                        inMenuMode = false;
                        if (autoThrottle) currentThrottle = 1.0f;
                        currentButtons = 0;
                        flushOverrides();
                        postInvalidate();
                        return true;
                    }
                    if (Math.hypot(x - (w - dp(110)), y - dp(25)) <= dp(22)) {
                        confirmQuit();
                        return true;
                    }
                    if (Math.hypot(x - (w - dp(38)), y - dp(25)) <= dp(22)) {
                        toggleQuickHud();
                        return true;
                    }
                    if (Math.hypot(x - (w - dp(74)), y - dp(25)) <= dp(22)) {
                        try { nativeAction(1, 0.0f); } catch (UnsatisfiedLinkError ignored) {}
                        vibrate(PinyonShiftActivity.this, 30, 200);
                        toast("Photo captured");
                        return true;
                    }
                    if (x >= modeX - dp(66) && x <= modeX - dp(8) && y >= modeY && y <= modeY + modeH) {
                        pulseButton(BTN_BACK, 150);
                        return true;
                    }
                    if (x >= modeX + modeW + dp(8) && x <= modeX + modeW + dp(66) && y >= modeY && y <= modeY + modeH) {
                        pulseButton(BTN_START, 150);
                        return true;
                    }
                }

                int buttons = 0;
                int pCount = event.getPointerCount();
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

                if ((buttons & ~currentButtons) != 0) {
                    vibrate(PinyonShiftActivity.this, 12, 100);
                }
                currentButtons = buttons;
                currentSteerX = 0.0f;
                currentSteerY = 0.0f;
                currentThrottle = 0.0f;
                currentBrake = 0.0f;
                currentLookX = 0.0f;
                currentLookY = 0.0f;
                flushOverrides();
                postInvalidate();
                return true;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = event.getX(actionIdx), y = event.getY(actionIdx);
                    long now = System.currentTimeMillis();
                    if (pointerId < MAX_P) {
                        downX[pointerId] = x;
                        downY[pointerId] = y;
                        downT[pointerId] = now;
                    }

                    float modeW = dp(104), modeH = dp(26);
                    float modeX = (w - modeW) * 0.5f, modeY = dp(12);
                    if (x >= modeX && x <= modeX + modeW && y >= modeY && y <= modeY + modeH) {
                        inMenuMode = true;
                        currentThrottle = 0.0f;
                        currentBrake = 0.0f;
                        currentButtons = 0;
                        flushOverrides();
                        postInvalidate();
                        return true;
                    }

                    if (Math.hypot(x - (w - dp(38)), y - dp(25)) <= dp(22)) {
                        toggleQuickHud();
                        return true;
                    }

                    if (Math.hypot(x - (w - dp(74)), y - dp(25)) <= dp(22)) {
                        try { nativeAction(1, 0.0f); } catch (UnsatisfiedLinkError ignored) {}
                        vibrate(PinyonShiftActivity.this, 30, 200);
                        toast("Photo captured");
                        return true;
                    }

                    if (prefs.getBoolean(PREF_HUD_HITBOXES, true)) {
                        if (x < w * 0.18f && y > h * 0.72f) {
                            pulseButton(BTN_BACK, 150);
                            inMenuMode = true;
                            currentThrottle = 0.0f;
                            flushOverrides();
                            postInvalidate();
                            return true;
                        }
                        if (x < w * 0.16f && y < h * 0.16f) {
                            pulseButton(BTN_START, 150);
                            inMenuMode = true;
                            currentThrottle = 0.0f;
                            flushOverrides();
                            postInvalidate();
                            return true;
                        }
                    }

                    if (x > w * 0.38f && x < w * 0.62f && y > h - dp(36)) {
                        pulseButton(BTN_X, 150);
                        return true;
                    }

                    int z = Z_NONE;
                    if (x < w * 0.35f) {
                        z = Z_BRAKE;
                        leftHeld = true;
                        currentBrake = 1.0f;
                        if (autoThrottle) currentThrottle = 0.0f;
                        brakeAlpha = 0.25f;
                        vibrate(PinyonShiftActivity.this, 10, 80);
                    } else if (x > w * 0.65f) {
                        z = Z_THROTTLE;
                        rightHeld = true;
                        currentThrottle = 1.0f;
                        vibrate(PinyonShiftActivity.this, 10, 80);
                    } else {
                        z = Z_CENTER;
                    }
                    if (pointerId < MAX_P) zone[pointerId] = z;
                    flushOverrides();
                    postInvalidate();
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        int pId = event.getPointerId(i);
                        if (pId < MAX_P && zone[pId] == Z_CENTER && prefs.getBoolean(PREF_LOOK_AROUND, true)) {
                            float dx = event.getX(i) - downX[pId];
                            float dy = event.getY(i) - downY[pId];
                            currentLookX = Math.min(1.0f, Math.max(-1.0f, dx / dp(80)));
                            currentLookY = Math.min(1.0f, Math.max(-1.0f, -dy / dp(80)));
                        }
                    }
                    flushOverrides();
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int pId = event.getPointerId(actionIdx);
                    int z = pId < MAX_P ? zone[pId] : Z_NONE;
                    float dx = event.getX(actionIdx) - (pId < MAX_P ? downX[pId] : 0);
                    float dy = event.getY(actionIdx) - (pId < MAX_P ? downY[pId] : 0);
                    long dur = System.currentTimeMillis() - (pId < MAX_P ? downT[pId] : 0);

                    if (z == Z_CENTER) {
                        currentLookX = 0.0f;
                        currentLookY = 0.0f;
                        if (dur < 350) {
                            if (dy < -dp(35) && Math.abs(dx) < dp(45)) {
                                triggerShift(true);
                            } else if (dy > dp(35) && Math.abs(dx) < dp(45)) {
                                triggerShift(false);
                            } else if (dx < -dp(50) && Math.abs(dy) < dp(45)) {
                                pulseButton(BTN_Y, 250);
                            } else if (Math.abs(dx) < dp(20) && Math.abs(dy) < dp(20)) {
                                long now = System.currentTimeMillis();
                                if (now - lastCenterTapTime < 320) {
                                    pulseButton(BTN_RIGHT_SHOULDER, 120);
                                    lastCenterTapTime = 0;
                                } else {
                                    lastCenterTapTime = now;
                                }
                            }
                        }
                    } else if (z == Z_BRAKE) {
                        leftHeld = false;
                        currentBrake = 0.0f;
                        brakeAlpha = 0.0f;
                        if (autoThrottle) currentThrottle = 1.0f;
                    } else if (z == Z_THROTTLE) {
                        rightHeld = false;
                        currentThrottle = autoThrottle ? 1.0f : 0.0f;
                    }

                    if (pId < MAX_P) zone[pId] = Z_NONE;
                    flushOverrides();
                    postInvalidate();
                    return true;
                }

                case MotionEvent.ACTION_CANCEL: {
                    leftHeld = false;
                    rightHeld = false;
                    currentBrake = 0.0f;
                    currentThrottle = autoThrottle ? 1.0f : 0.0f;
                    currentLookX = 0.0f;
                    currentLookY = 0.0f;
                    currentButtons = 0;
                    brakeAlpha = 0.0f;
                    flushOverrides();
                    postInvalidate();
                    return true;
                }
            }
            return super.onTouchEvent(event);
        }
    }

    // --- Helpers & Utilities ---

    public static void showTouchGuide(Context context) {
        new AlertDialog.Builder(context)
                .setTitle("Touch Controls & Gestures")
                .setMessage("• Steering: Device gyro tilt\n• Left: Brake | Right: Throttle (Auto RT)\n• Menu Mode: On-screen D-Pad & A/B/X/Y\n• Top Pill: FH1 Live Tuning HUD\n• Center: Flick up/down shift, drag look, swipe rewind")
                .setPositiveButton("Got It", null)
                .show();
    }

    static void installBundledDrivers(AssetManager assets, File driversDir) {
        try {
            String[] drivers = assets.list("drivers");
            if (drivers == null) return;
            for (String driver : drivers) {
                File target = new File(driversDir, driver);
                if (target.exists()) continue;
                String[] files = assets.list("drivers/" + driver);
                if (files == null || files.length == 0) continue;
                target.mkdirs();
                for (String name : files) {
                    try (InputStream in = assets.open("drivers/" + driver + "/" + name);
                         OutputStream out = new FileOutputStream(new File(target, name))) {
                        copy(in, out);
                    }
                }
            }
        } catch (IOException error) {
            Log.w(TAG, "failed installing bundled drivers", error);
        }
    }

    static int getThemeAccent(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("pinyon_shift_settings", MODE_PRIVATE);
        return p.getInt("theme_accent", Color.rgb(92, 208, 138));
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        for (int read; (read = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, read);
        }
    }

    static void deleteTree(File f) {
        File[] ch = f.listFiles();
        if (ch != null) for (File c : ch) deleteTree(c);
        f.delete();
    }

    static void copyTree(File s, File d) throws IOException {
        if (s.isDirectory()) {
            d.mkdirs();
            File[] ch = s.listFiles();
            if (ch != null) for (File c : ch) copyTree(c, new File(d, c.getName()));
            return;
        }
        d.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(s); OutputStream out = new FileOutputStream(d)) { copy(in, out); }
    }

    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> r = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InterfaceAddress ia : n.getInterfaceAddresses()) if (ia.getBroadcast() != null) r.add(ia.getBroadcast());
            }
            r.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {}
        return r;
    }

    static String unpackDriver(ContentResolver resolver, File driversDir, Uri uri) throws IOException {
        String base = "driver";
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String d = c.getString(0);
                if (d != null) base = d.replaceAll("(?i)\\.(zip|adpkg)$", "");
            }
        }
        File target = new File(driversDir, base);
        if (target.exists()) deleteTree(target);
        target.mkdirs();
        try (InputStream raw = resolver.openInputStream(uri); ZipInputStream zip = new ZipInputStream(raw)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; zip.closeEntry()) {
                if (e.getName().contains("..")) continue;
                File out = new File(target, e.getName());
                if (e.isDirectory()) out.mkdirs();
                else {
                    out.getParentFile().mkdirs();
                    try (OutputStream fos = new FileOutputStream(out)) { copy(zip, fos); }
                }
            }
        }
        return base;
    }


    private void toast(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(PinyonShiftActivity.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
