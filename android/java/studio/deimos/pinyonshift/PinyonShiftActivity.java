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
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
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
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.Switch;
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
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
public class PinyonShiftActivity extends SDLActivity {
    private static final String TAG = "PinyonShift";
    private static final int REQUEST_GPU_DRIVER_ZIP = 0x5053;

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

    public static native void nativeSetMobileOverrides(float steer_x, float throttle, float brake, int button_mask, float look_x, float look_y);
    public static native void nativeSetCVar(String name, String value);

    private File stateDir;
    private File driversDir;
    private SharedPreferences prefs;

    // Controls and Overlay
    private RR3OverlayView rr3Overlay;
    private View quickHudButton;
    private View quickHudDrawer;
    private View floatingShutter;

    // Sensors & Gyro Steering
    private SensorManager sensorManager;
    private Sensor gameRotationSensor;
    private float baselineRoll = 0.0f;
    private boolean gyroCalibrated = false;
    private float currentTiltDegrees = 0.0f;
    private String controlScheme = "tilt_a";
    private boolean autoThrottle = false;
    private boolean inMenuMode = false;

    // Active input state for direct C++ synthesis
    private volatile float currentSteerX = 0.0f;
    private volatile float currentThrottle = 0.0f;
    private volatile float currentBrake = 0.0f;
    private volatile float currentLookX = 0.0f;
    private volatile float currentLookY = 0.0f;
    private volatile int currentButtons = 0;

    // Thermal Frame Pacer
    private BroadcastReceiver thermalReceiver;
    private float currentBatteryTempC = 25.0f;
    private boolean thermalPacerEnabled = true;
    private int currentFpsTarget = 60;

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
        autoThrottle = prefs.getBoolean("auto_throttle", false);
        thermalPacerEnabled = prefs.getBoolean("thermal_pacer", true);

        super.onCreate(savedInstanceState);

        initHaptics();
        initSensors();
        initThermalPacer();
        startPhotoWatcher(new File(stateDir, "photos"));
        attachMobileOverlays();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && gameRotationSensor != null && "tilt_a".equals(controlScheme)) {
            sensorManager.registerListener(sensorListener, gameRotationSensor, SensorManager.SENSOR_DELAY_GAME);
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
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (inMenuMode) {
                pulseButton(BTN_B, 120);
                inMenuMode = false;
                if (rr3Overlay != null) rr3Overlay.postInvalidate();
                return true;
            } else {
                pulseButton(BTN_START, 120);
                inMenuMode = true;
                if (rr3Overlay != null) rr3Overlay.postInvalidate();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void flushOverrides() {
        try {
            nativeSetMobileOverrides(currentSteerX, currentThrottle, currentBrake, currentButtons, currentLookX, currentLookY);
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
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/sys/class/kgsl/kgsl-3d0/gpu_model"))) {
            String line = reader.readLine();
            Matcher match = line != null ? Pattern.compile("Adreno.*?(\\d{3})").matcher(line) : null;
            if (match != null && match.find()) {
                return match.group(1);
            }
        } catch (IOException ignored) {}
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null) {
            switch (Build.SOC_MODEL.toUpperCase(Locale.ROOT)) {
                case "SM8250": case "SM8250-AB": case "SM8250-AC": return "650";
                case "SM8350": return "660";
                case "SM8450": case "SM8475": return "730";
                case "SM8550": case "QCS8550": return "740";
                case "SM8635": return "735";
                case "SM8650": return "750";
                case "SM7475": return "725";
                case "SM7550": return "720";
                case "SM7635": case "SM7635-AB": return "810";
                case "SM7675": return "732";
                case "SM8750": return "830";
                default: break;
            }
        }
        String hw = (Build.HARDWARE != null ? Build.HARDWARE : "") + " " + (Build.BOARD != null ? Build.BOARD : "");
        hw = hw.toUpperCase(Locale.ROOT);
        if (hw.contains("SM7635") || hw.contains("VOLCANO")) return "810";
        if (hw.contains("SM8250")) return "650";
        if (hw.contains("SM8350")) return "660";
        if (hw.contains("SM8450") || hw.contains("SM8475")) return "730";
        if (hw.contains("SM8550") || hw.contains("QCS8550")) return "740";
        if (hw.contains("SM8635")) return "735";
        if (hw.contains("SM8650")) return "750";
        if (hw.contains("SM7475")) return "725";
        if (hw.contains("SM7550")) return "720";
        if (hw.contains("SM7675")) return "732";
        if (hw.contains("SM8750")) return "830";
        return null;
    }

    static String recommendedDriver(String adreno) {
        if (adreno == null || adreno.isEmpty()) return null;
        try {
            int model = Integer.parseInt(adreno);
            if (model >= 800) return "turnip-gen8-v37";
            if (model >= 700) return "turnip-gen8-v37";
            if (model >= 600) return "turnip-gen8-v37";
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

                quickHudButton = createQuickHudButton();
                content.addView(quickHudButton);

                quickHudDrawer = createQuickHudDrawer();
                quickHudDrawer.setVisibility(View.GONE);
                content.addView(quickHudDrawer);

                floatingShutter = createFloatingShutter();
                content.addView(floatingShutter);
            }
        });
    }

    private View createQuickHudButton() {
        int accent = getThemeAccent(this);
        Button btn = new Button(this);
        btn.setText("⚡ FH1");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.argb(160, 16, 24, 19));
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), Color.argb(140, Color.red(accent), Color.green(accent), Color.blue(accent)));
        btn.setBackground(gd);
        btn.setPadding(dp(10), dp(4), dp(10), dp(4));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(8);
        btn.setLayoutParams(lp);

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleQuickHud(); }
        });
        return btn;
    }

    private View createFloatingShutter() {
        Button btn = new Button(this);
        btn.setText("📷");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.argb(140, 25, 28, 35));
        gd.setCornerRadius(dp(22));
        gd.setStroke(dp(1), Color.argb(100, 255, 255, 255));
        btn.setBackground(gd);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(44), dp(44));
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.bottomMargin = dp(14);
        lp.rightMargin = dp(14);
        btn.setLayoutParams(lp);

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_F8);
                SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_F8);
                vibrate(PinyonShiftActivity.this, 20, 180);
                toast("Capturing high-res photo...");
            }
        });
        return btn;
    }

    private void toggleQuickHud() {
        if (quickHudDrawer == null) return;
        boolean show = quickHudDrawer.getVisibility() != View.VISIBLE;
        quickHudDrawer.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private View createQuickHudDrawer() {
        int accent = getThemeAccent(this);
        FrameLayout drawer = new FrameLayout(this);
        drawer.setBackgroundColor(Color.argb(240, 16, 24, 19));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.START;
        drawer.setLayoutParams(lp);

        ScrollView scroll = new ScrollView(this);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("FH1 Live Tuning");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(accent);
        items.addView(title);

        TextView tempTv = new TextView(this);
        tempTv.setText(String.format(Locale.ROOT, "Battery Temp: %.1f°C", currentBatteryTempC));
        tempTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tempTv.setTextColor(Color.rgb(147, 165, 151));
        tempTv.setPadding(0, dp(4), 0, dp(12));
        items.addView(tempTv);

        // Framerate Presets
        addHudSectionTitle(items, "Target FPS (Live VRR)");
        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        int[] fpsOpts = {30, 45, 60, 120};
        for (final int f : fpsOpts) {
            Button b = createChipButton(f + " FPS", f == currentFpsTarget);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setPacedFpsTarget(f, "FPS set to " + f);
                    toggleQuickHud();
                }
            });
            fpsRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        }
        items.addView(fpsRow);

        // Mobile Presets
        addHudSectionTitle(items, "Render Preset");
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] presets = {"Potato", "Balanced", "Ultra"};
        for (String p : presets) {
            final String pr = p.toLowerCase(Locale.ROOT);
            Button b = createChipButton(p, false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyPreset(pr);
                    toast("Preset applied: " + pr);
                    toggleQuickHud();
                }
            });
            presetRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        }
        items.addView(presetRow);

        // Output Upscaler
        addHudSectionTitle(items, "Output Upscaler");
        LinearLayout upRow = new LinearLayout(this);
        upRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] upscalers = {"FSR 1.0", "CAS", "Bilinear"};
        for (String u : upscalers) {
            final String up = u.startsWith("FSR") ? "fsr" : u.startsWith("CAS") ? "cas" : "bilinear";
            Button b = createChipButton(u, false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setUpscaler(up);
                    toast("Upscaler set to: " + up.toUpperCase(Locale.ROOT));
                    toggleQuickHud();
                }
            });
            upRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        }
        items.addView(upRow);

        // Controls & Guide
        addHudSectionTitle(items, "Controls & Help");
        Button calBtn = createChipButton("🎯 Recalibrate Gyro 0°", true);
        calBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { recalibrateGyro(); toggleQuickHud(); }
        });
        items.addView(calBtn);

        Button guideBtn = createChipButton("📖 Touch Controls Guide", false);
        guideBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleQuickHud();
                showTouchGuide(PinyonShiftActivity.this);
            }
        });
        items.addView(guideBtn);

        Button menuModeBtn = createChipButton("🎮 Toggle Menu Mode (" + (inMenuMode ? "ON" : "OFF") + ")", false);
        menuModeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inMenuMode = !inMenuMode;
                toast("Menu Mode: " + (inMenuMode ? "ACTIVE (Swipes = LB/RB)" : "DRIVING"));
                if (rr3Overlay != null) rr3Overlay.postInvalidate();
                toggleQuickHud();
            }
        });
        items.addView(menuModeBtn);

        scroll.addView(items);
        drawer.addView(scroll);
        return drawer;
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
                int raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                currentBatteryTempC = raw / 10.0f;
                checkThermalPacing();
            }
        };
        registerReceiver(thermalReceiver, filter);
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
        private final Paint bannerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        private long leftDownTime = 0;
        private long rightDownTime = 0;
        private boolean manualShifting = false;

        public RR3OverlayView(Context context) {
            super(context);
            horizonPaint.setColor(Color.argb(130, 255, 255, 255));
            horizonPaint.setStrokeWidth(dp(2));
            horizonPaint.setStyle(Paint.Style.STROKE);

            textPaint.setColor(Color.argb(70, 255, 255, 255));
            textPaint.setTextSize(dp(13));
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);

            pillPaint.setColor(Color.argb(40, 255, 255, 255));
            pillPaint.setStyle(Paint.Style.FILL);

            int accent = getThemeAccent(context);
            bannerPaint.setColor(Color.argb(210, Color.red(accent), Color.green(accent), Color.blue(accent)));
            bannerPaint.setStyle(Paint.Style.FILL);

            manualShifting = prefs.getBoolean(PREF_MANUAL_SHIFT, false);
        }

        public void setTiltAngle(float degrees) {
            tiltDeg = degrees;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            // Menu Mode Banner
            if (inMenuMode) {
                canvas.drawRect(0, 0, w, dp(32), bannerPaint);
                textPaint.setColor(Color.BLACK);
                textPaint.setTextSize(dp(12));
                canvas.drawText("MENU MODE · Swipes = LB/RB · Tap = A (Select) · Back = B", w * 0.5f, dp(20), textPaint);
                textPaint.setColor(Color.argb(70, 255, 255, 255));
                return;
            }

            // Layer 1: Dynamic Brake Vignette (Radial red glow when left zone is pressed)
            if (brakeAlpha > 0.01f) {
                vignettePaint.setShader(new RadialGradient(0, h, w * 0.45f,
                        Color.argb((int) (brakeAlpha * 255), 255, 30, 30),
                        Color.TRANSPARENT, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w * 0.45f, h, vignettePaint);
            }

            // Layer 1: Subtle Steering Horizon Indicator (Tilts with the device)
            if ("tilt_a".equals(controlScheme)) {
                canvas.save();
                float cx = w * 0.5f;
                float cy = h * 0.88f;
                canvas.translate(cx, cy);
                canvas.rotate(-tiltDeg);
                float barW = dp(50);
                canvas.drawLine(-barW, 0, barW, 0, horizonPaint);
                canvas.drawLine(0, -dp(5), 0, dp(5), horizonPaint);
                canvas.restore();
            }

            // Layer 4: Translucent Bottom-Center [X] Interact Pill
            float pillW = dp(80);
            float pillH = dp(24);
            float px = (w - pillW) * 0.5f;
            float py = h - dp(32);
            canvas.drawRoundRect(px, py, px + pillW, py + pillH, dp(12), dp(12), pillPaint);
            canvas.drawText("[X] INTERACT", w * 0.5f, py + dp(16), textPaint);

            // Watermark labels
            canvas.drawText("BRAKE", w * 0.18f, h - dp(18), textPaint);
            canvas.drawText(autoThrottle ? "AUTO" : "THROTTLE", w * 0.82f, h - dp(18), textPaint);

            if (manualShifting) {
                canvas.drawText("MANUAL SHIFT", w * 0.5f, dp(24), textPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            int actionIdx = event.getActionIndex();
            int pointerId = event.getPointerId(actionIdx);
            int w = getWidth();
            int h = getHeight();

            if ("classic".equals(controlScheme) || "gamepad".equals(controlScheme)) {
                return false;
            }

            boolean hudHitboxes = prefs.getBoolean(PREF_HUD_HITBOXES, true);
            boolean gestureShift = prefs.getBoolean(PREF_GESTURE_SHIFT, true);
            boolean lookAround = prefs.getBoolean(PREF_LOOK_AROUND, true);
            boolean dualHandbrake = prefs.getBoolean(PREF_DUAL_HANDBRAKE, true);

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = event.getX(actionIdx);
                    float y = event.getY(actionIdx);
                    long now = System.currentTimeMillis();

                    if (pointerId < MAX_P) {
                        downX[pointerId] = x;
                        downY[pointerId] = y;
                        downT[pointerId] = now;
                    }

                    // Check Menu Mode taps
                    if (inMenuMode) {
                        if (x < w * 0.2f && y < h * 0.2f) {
                            pulseButton(BTN_B, 120);
                            inMenuMode = false;
                            postInvalidate();
                            return true;
                        }
                        pulseButton(BTN_A, 120);
                        return true;
                    }

                    // Layer 3: Native HUD Hitboxes
                    if (hudHitboxes) {
                        // Minimap (Bottom-Left) -> Map (Back Button)
                        if (x < w * 0.18f && y > h * 0.72f) {
                            pulseButton(BTN_BACK, 150);
                            if (prefs.getBoolean(PREF_MENU_MODE_AUTO, true)) inMenuMode = true;
                            postInvalidate();
                            return true;
                        }
                        // Pause / Wristband (Top-Left) -> Start Button
                        if (x < w * 0.16f && y < h * 0.16f) {
                            pulseButton(BTN_START, 150);
                            if (prefs.getBoolean(PREF_MENU_MODE_AUTO, true)) inMenuMode = true;
                            postInvalidate();
                            return true;
                        }
                        // Tachometer (Bottom-Right) -> Toggle Auto/Manual
                        if (x > w * 0.82f && y > h * 0.72f) {
                            manualShifting = !manualShifting;
                            prefs.edit().putBoolean(PREF_MANUAL_SHIFT, manualShifting).apply();
                            toast(manualShifting ? "Manual Shifting: ON (Swipe Center Up/Down)" : "Auto Shifting: ON");
                            vibrate(PinyonShiftActivity.this, 20, 150);
                            postInvalidate();
                            return true;
                        }
                    }

                    // Layer 4: Interact [X] Pill (Bottom-Center)
                    if (x > w * 0.40f && x < w * 0.60f && y > h * 0.84f) {
                        pulseButton(BTN_X, 150);
                        return true;
                    }

                    // Driving Zones
                    int z = Z_NONE;
                    if (x < w * 0.35f) {
                        z = Z_BRAKE;
                        leftHeld = true;
                        leftDownTime = now;
                        currentBrake = 1.0f;
                        brakeAlpha = 0.20f;
                        vibrate(PinyonShiftActivity.this, 10, 80);
                    } else if (x > w * 0.65f) {
                        z = Z_THROTTLE;
                        rightHeld = true;
                        rightDownTime = now;
                        currentThrottle = 1.0f;
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
                        int pid = event.getPointerId(i);
                        if (pid >= MAX_P) continue;
                        float x = event.getX(i);
                        float y = event.getY(i);
                        float dx = x - downX[pid];
                        float dy = y - downY[pid];

                        // Layer 1: Inward Swipe Handbrake (A Button)
                        if (zone[pid] == Z_BRAKE && dx > w * 0.08f) {
                            currentButtons |= BTN_A;
                            vibrate(PinyonShiftActivity.this, 15, 120);
                        } else if (zone[pid] == Z_THROTTLE && dx < -w * 0.08f) {
                            currentButtons |= BTN_A;
                            vibrate(PinyonShiftActivity.this, 15, 120);
                        }

                        // Layer 2: Center Cockpit Look Around
                        if (lookAround && zone[pid] == Z_CENTER && System.currentTimeMillis() - downT[pid] > 140) {
                            currentLookX = Math.min(1.0f, Math.max(-1.0f, dx / (0.15f * w)));
                            currentLookY = Math.min(1.0f, Math.max(-1.0f, -dy / (0.15f * h)));
                        }
                    }

                    // Layer 1: Dual-Hold Handbrake (Hold both Brake and Throttle > 100ms)
                    if (dualHandbrake && leftHeld && rightHeld) {
                        long heldMs = System.currentTimeMillis() - Math.max(leftDownTime, rightDownTime);
                        if (heldMs > 100) {
                            currentButtons |= BTN_A;
                        }
                    }

                    flushOverrides();
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    float x = event.getX(actionIdx);
                    float y = event.getY(actionIdx);
                    long now = System.currentTimeMillis();
                    int z = pointerId < MAX_P ? zone[pointerId] : Z_NONE;
                    float dx = pointerId < MAX_P ? x - downX[pointerId] : 0;
                    float dy = pointerId < MAX_P ? y - downY[pointerId] : 0;
                    long dur = pointerId < MAX_P ? now - downT[pointerId] : 0;

                    // Layer 2: Gestures in Center Cockpit Zone
                    if (z == Z_CENTER) {
                        currentLookX = 0.0f;
                        currentLookY = 0.0f;

                        if (dur < 350) {
                            // Quick Flick Up / Down -> Shift
                            if (gestureShift && Math.abs(dy) > h * 0.07f && Math.abs(dy) > Math.abs(dx)) {
                                if (dy < 0) {
                                    triggerShift(true); // Shift Up
                                } else {
                                    triggerShift(false); // Shift Down
                                }
                            }
                            // Swipe Left -> Rewind (Y)
                            else if (dx < -w * 0.08f && Math.abs(dx) > Math.abs(dy)) {
                                pulseButton(BTN_Y, 250);
                            }
                            // Double-Tap -> Switch Camera (RB)
                            else if (Math.abs(dx) < dp(20) && Math.abs(dy) < dp(20)) {
                                if (now - lastCenterTapTime < 320) {
                                    pulseButton(BTN_RIGHT_SHOULDER, 120);
                                    lastCenterTapTime = 0;
                                } else {
                                    lastCenterTapTime = now;
                                }
                            }
                        }
                    }

                    // Menu Mode Swipe Left / Right (LB / RB)
                    if (inMenuMode && Math.abs(dx) > w * 0.12f) {
                        if (dx > 0) pulseButton(BTN_RIGHT_SHOULDER, 120);
                        else pulseButton(BTN_LEFT_SHOULDER, 120);
                    }

                    if (z == Z_BRAKE) {
                        leftHeld = false;
                        currentBrake = 0.0f;
                        brakeAlpha = 0.0f;
                        currentButtons &= ~BTN_A;
                    } else if (z == Z_THROTTLE) {
                        rightHeld = false;
                        currentThrottle = autoThrottle ? 1.0f : 0.0f;
                        currentButtons &= ~BTN_A;
                    }

                    if (pointerId < MAX_P) zone[pointerId] = Z_NONE;
                    flushOverrides();
                    postInvalidate();
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
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
            return super.onTouchEvent(event);
        }
    }

    // --- Helpers & Utilities ---

    public static void showTouchGuide(Context context) {
        new AlertDialog.Builder(context)
                .setTitle("Touch Controls & Gestures Guide")
                .setMessage("• Steering: Smooth device tilt (Sensor gyro) with artificial horizon.\n\n"
                        + "• Pedals: Left half = Progressive Brake (dynamic red glow). Right half = Throttle.\n\n"
                        + "• Handbrake (A): Swipe inward toward center from either zone, or hold both pedals together.\n\n"
                        + "• Shifting (B/X): Flick UP in center zone to Shift Up, Flick DOWN to Shift Down (with Smart Auto-Clutch LB blip).\n\n"
                        + "• Look Around: Touch & drag in center zone to orbit camera (Right Stick).\n\n"
                        + "• Rewind (Y): Swipe left in center zone.\n\n"
                        + "• Camera (RB): Double-tap center zone to switch camera view.\n\n"
                        + "• HUD Hitboxes: Tap bottom-left minimap for World Map, tap top-left for Pause, tap tachometer to toggle manual shifting.")
                .setPositiveButton("Got It", null)
                .show();
    }

    public static String computeSha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int r; (r = in.read(buf)) > 0; ) {
                digest.update(buf, 0, r);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02X", b));
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        }
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

    static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] ch = src.listFiles();
            if (ch != null) for (File c : ch) copyTree(c, new File(dst, c.getName()));
            return;
        }
        dst.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            copy(in, out);
        }
    }

    static List<InetAddress> broadcastAddresses() {
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

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        for (int read; (read = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, read);
        }
    }

    static String unpackDriver(ContentResolver resolver, File driversDir, Uri uri) throws IOException {
        String baseName = "driver";
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String displayName = cursor.getString(0);
                if (displayName != null) {
                    baseName = displayName.replaceAll("(?i)\\.(zip|adpkg)$", "");
                }
            }
        }
        File targetDir = new File(driversDir, baseName);
        if (targetDir.exists()) deleteTree(targetDir);
        targetDir.mkdirs();

        try (InputStream raw = resolver.openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.contains("..")) continue;
                File out = new File(targetDir, name);
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (OutputStream fos = new FileOutputStream(out)) {
                        copy(zip, fos);
                    }
                }
                zip.closeEntry();
            }
        }
        return baseName;
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
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
