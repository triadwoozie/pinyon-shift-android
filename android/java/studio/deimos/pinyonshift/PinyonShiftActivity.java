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
 * SDL's activity with the game's paths, arguments, Real Racing 3 touch controls,
 * thermal frame pacing, FSR/CAS overlay, and native photo mode.
 */
public class PinyonShiftActivity extends SDLActivity {
    private static final String TAG = "PinyonShift";
    private static final int REQUEST_GPU_DRIVER_ZIP = 0x5053;

    public static final String MS_2505_RETAIL_XEX_SHA256 =
            "DB40DF605ADE49A612B35A7A24C38F6004BCB17A88ED6B48288DE16DF9E3987C";
    public static final long MS_2505_RETAIL_XEX_SIZE = 20856832L;

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
    private String controlScheme = "rr3_tilt_a";
    private boolean autoThrottle = false;

    // Thermal Frame Pacer
    private BroadcastReceiver thermalReceiver;
    private float currentBatteryTempC = 25.0f;
    private boolean thermalPacerEnabled = true;
    private int currentFpsTarget = 60;

    // Photo Observer
    private FileObserver photoObserver;

    private static Vibrator sVibrator;

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
                if (key.startsWith("env.")) {
                    Object value = extras.get(key);
                    if (value != null) {
                        setEnvironment(key.substring(4), value.toString());
                    }
                }
            }
            controlScheme = intent.getStringExtra("control_scheme");
            if (controlScheme == null) controlScheme = "rr3_tilt_a";
            autoThrottle = intent.getBooleanExtra("auto_throttle", false);
            thermalPacerEnabled = intent.getBooleanExtra("thermal_pacer", true);
        }

        prefs = getSharedPreferences("pinyon_shift_settings", MODE_PRIVATE);

        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getIntent().getBooleanExtra("show_when_locked", false)) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        initSensors();
        initThermalPacer();
        startPhotoWatcher(new File(stateDir, "photos"));

        // Setup In-Game Mobile UI on top of SDL layout
        setupInGameMobileUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && gameRotationSensor != null) {
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
        if (photoObserver != null) {
            photoObserver.stopWatching();
            photoObserver = null;
        }
        if (thermalReceiver != null) {
            try { unregisterReceiver(thermalReceiver); } catch (Exception ignored) {}
            thermalReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {"c++_shared", "rexruntime", "main"};
    }

    @Override
    protected String[] getArguments() {
        Intent intent = getIntent();
        String[] given = intent != null ? intent.getStringArrayExtra("args") : null;
        List<String> arguments = new ArrayList<>();
        if (given != null) Collections.addAll(arguments, given);
        File external = getExternalFilesDir(null);
        File file = new File(external != null ? external : getFilesDir(), SetupActivity.ARGUMENTS_FILE);
        if (file.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.trim().startsWith("--")) arguments.add(line.trim());
                }
            } catch (IOException error) {
                Log.w(TAG, "launch arguments not read", error);
            }
        }
        return arguments.toArray(new String[0]);
    }

    // --- Gyroscope & Real Racing 3 Controls ---

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
        private final float[] remappedMatrix = new float[9];
        private final float[] orientation = new float[3];

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() != Sensor.TYPE_GAME_ROTATION_VECTOR
                    && event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
                return;
            }
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.remapCoordinateSystem(rotationMatrix,
                    SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix);
            SensorManager.getOrientation(remappedMatrix, orientation);
            float rollRad = orientation[2];

            if (!gyroCalibrated) {
                baselineRoll = rollRad;
                gyroCalibrated = true;
            }
            float delta = rollRad - baselineRoll;
            currentTiltDegrees = (float) Math.toDegrees(delta);

            if ("rr3_tilt_a".equals(controlScheme)) {
                float absDelta = Math.abs(delta);
                float steer = 0.0f;
                // Deadzone 1.5 deg (~0.026 rad), max 35 deg (~0.61 rad)
                if (absDelta > 0.026f) {
                    float norm = Math.min(1.0f, (absDelta - 0.026f) / (0.61f - 0.026f));
                    steer = (float) (Math.signum(delta) * Math.pow(norm, 1.35));
                }
                if (rr3Overlay != null) {
                    rr3Overlay.setTiltAngle(currentTiltDegrees);
                }
                if (steer < -0.15f) {
                    SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_DPAD_LEFT);
                    SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_DPAD_RIGHT);
                } else if (steer > 0.15f) {
                    SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT);
                    SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_DPAD_LEFT);
                } else {
                    SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_DPAD_LEFT);
                    SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_DPAD_RIGHT);
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    public void recalibrateGyro() {
        gyroCalibrated = false;
        vibrate(this, 25, 180);
        toast("Gyro Steering Center Calibrated (0°)");
    }

    // --- In-Game UI Overlay ---

    private void setupInGameMobileUi() {
        if (mLayout == null) return;

        // 1. Full-screen RR3 touch overlay
        rr3Overlay = new RR3OverlayView(this);
        mLayout.addView(rr3Overlay, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Floating Camera Shutter (Top-Right)
        floatingShutter = createFloatingButton("📷", dp(50), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                vibrate(PinyonShiftActivity.this, 25, 200);
                SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_F8);
                SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_F8);
            }
        });
        RelativeLayout.LayoutParams shutterLp = new RelativeLayout.LayoutParams(dp(50), dp(50));
        shutterLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        shutterLp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        shutterLp.setMargins(0, dp(14), dp(16), 0);
        floatingShutter.setLayoutParams(shutterLp);
        mLayout.addView(floatingShutter);

        // 3. Quick HUD Toggle Button (Top-Left)
        quickHudButton = createFloatingButton("⚡ FH1", dp(68), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleQuickHud();
            }
        });
        RelativeLayout.LayoutParams hudBtnLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        hudBtnLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        hudBtnLp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        hudBtnLp.setMargins(dp(16), dp(14), 0, 0);
        quickHudButton.setLayoutParams(hudBtnLp);
        mLayout.addView(quickHudButton);

        // 4. Slide-Out Quick HUD Drawer
        quickHudDrawer = buildQuickHudDrawer();
        quickHudDrawer.setVisibility(View.GONE);
        mLayout.addView(quickHudDrawer);
    }

    private View createFloatingButton(String text, int widthDp, View.OnClickListener click) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.argb(175, 24, 27, 33));
        gd.setCornerRadius(dp(18));
        gd.setStroke(dp(1), Color.argb(160, 241, 174, 54));
        btn.setBackground(gd);
        btn.setPadding(dp(12), dp(4), dp(12), dp(4));
        btn.setOnClickListener(click);
        return btn;
    }

    private void toggleQuickHud() {
        if (quickHudDrawer == null) return;
        boolean open = quickHudDrawer.getVisibility() == View.VISIBLE;
        quickHudDrawer.setVisibility(open ? View.GONE : View.VISIBLE);
        vibrate(this, 15, 120);
    }

    private View buildQuickHudDrawer() {
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                dp(320), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);

        LinearLayout drawer = new LinearLayout(this);
        drawer.setLayoutParams(lp);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(Color.argb(235, 20, 23, 28));
        drawer.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("PINYON SHIFT HUD");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(241, 174, 54));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Button close = new Button(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setBackground(null);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleQuickHud(); }
        });
        header.addView(close);
        drawer.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, dp(8), 0, dp(16));

        // Thermal info
        final TextView tempText = new TextView(this);
        tempText.setText(String.format(Locale.ROOT, "Battery Temp: %.1f°C · %s",
                currentBatteryTempC, currentBatteryTempC >= 42.0f ? "THROTTLED" : "NORMAL"));
        tempText.setTextColor(currentBatteryTempC >= 42.0f ? Color.rgb(244, 67, 54) : Color.rgb(76, 175, 80));
        tempText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tempText.setPadding(0, dp(4), 0, dp(8));
        items.addView(tempText);

        // Thermal Frame Pacer toggle
        Switch pacerSwitch = new Switch(this);
        pacerSwitch.setText("Thermal Frame Pacer (Auto 42°C)");
        pacerSwitch.setTextColor(Color.WHITE);
        pacerSwitch.setChecked(thermalPacerEnabled);
        pacerSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                thermalPacerEnabled = checked;
                vibrate(PinyonShiftActivity.this, 15, 120);
                toast(checked ? "Thermal Frame Pacer ON" : "Thermal Frame Pacer OFF");
            }
        });
        items.addView(pacerSwitch);

        // Target FPS chips
        addHudSectionTitle(items, "Target Framerate");
        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        final int[] fpsOpts = {30, 40, 45, 60, 120};
        for (int opt : fpsOpts) {
            final int f = opt;
            Button b = createChipButton(f + " FPS", f == currentFpsTarget);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setPacedFpsTarget(f, "Target FPS: " + f);
                    toggleQuickHud();
                }
            });
            fpsRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        }
        items.addView(fpsRow);

        // Graphics Preset
        addHudSectionTitle(items, "Dynamic Render Presets");
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

        // Gyro calibrate button
        addHudSectionTitle(items, "Controls");
        Button calBtn = createChipButton("🎯 Recalibrate Gyro 0°", true);
        calBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { recalibrateGyro(); toggleQuickHud(); }
        });
        items.addView(calBtn);

        scroll.addView(items);
        drawer.addView(scroll);
        return drawer;
    }

    private void addHudSectionTitle(LinearLayout container, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(Color.rgb(159, 178, 165));
        tv.setPadding(0, dp(10), 0, dp(4));
        container.addView(tv);
    }

    private Button createChipButton(String text, boolean active) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTextColor(active ? Color.BLACK : Color.WHITE);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(active ? Color.rgb(241, 174, 54) : Color.rgb(32, 36, 44));
        gd.setCornerRadius(dp(8));
        b.setBackground(gd);
        b.setPadding(dp(4), dp(4), dp(4), dp(4));
        return b;
    }

    private void applyPreset(String preset) {
        File toml = new File(stateDir, "config/pinyon-shift.toml");
        if (!toml.isFile()) return;
        try {
            String c = new String(readFile(toml), StandardCharsets.UTF_8);
            if ("potato".equals(preset)) {
                c = c.replaceAll("(?m)^pinyon_shift_fh1_shadows\\s*=.*$", "pinyon_shift_fh1_shadows = false");
                c = c.replaceAll("(?m)^pinyon_shift_fh1_env_map_rate\\s*=.*$", "pinyon_shift_fh1_env_map_rate = 0.0");
                c = c.replaceAll("(?m)^present_effect\\s*=.*$", "present_effect = \"bilinear\"");
            } else if ("balanced".equals(preset)) {
                c = c.replaceAll("(?m)^pinyon_shift_fh1_shadows\\s*=.*$", "pinyon_shift_fh1_shadows = false");
                c = c.replaceAll("(?m)^pinyon_shift_fh1_env_map_rate\\s*=.*$", "pinyon_shift_fh1_env_map_rate = 0.25");
                c = c.replaceAll("(?m)^present_effect\\s*=.*$", "present_effect = \"fsr\"");
            } else if ("ultra".equals(preset)) {
                c = c.replaceAll("(?m)^pinyon_shift_fh1_shadows\\s*=.*$", "pinyon_shift_fh1_shadows = true");
                c = c.replaceAll("(?m)^pinyon_shift_fh1_env_map_rate\\s*=.*$", "pinyon_shift_fh1_env_map_rate = 1.0");
                c = c.replaceAll("(?m)^present_effect\\s*=.*$", "present_effect = \"cas\"");
            }
            try (FileOutputStream out = new FileOutputStream(toml)) {
                out.write(c.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private void setUpscaler(String effect) {
        File toml = new File(stateDir, "config/pinyon-shift.toml");
        if (!toml.isFile()) return;
        try {
            String c = new String(readFile(toml), StandardCharsets.UTF_8);
            c = c.replaceAll("(?m)^present_effect\\s*=.*$", "present_effect = \"" + effect + "\"");
            try (FileOutputStream out = new FileOutputStream(toml)) {
                out.write(c.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
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
        File toml = new File(stateDir, "config/pinyon-shift.toml");
        if (toml.isFile()) {
            try {
                String c = new String(readFile(toml), StandardCharsets.UTF_8);
                c = c.replaceAll("(?m)^pinyon_shift_fh1_render_fps_limit\\s*=.*$",
                        "pinyon_shift_fh1_render_fps_limit = " + fps);
                try (FileOutputStream out = new FileOutputStream(toml)) {
                    out.write(c.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {}
        }
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
                toast("Photo saved to Pictures/ForzaHorizon");
            }
        } catch (Exception e) {
            Log.e(TAG, "Photo gallery export failed", e);
        }
    }

    // --- Real Racing 3 Touch Overlay View ---

    private class RR3OverlayView extends View {
        private final Paint horizonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float tiltDeg = 0.0f;
        private float brakeAlpha = 0.0f;

        public RR3OverlayView(Context context) {
            super(context);
            horizonPaint.setColor(Color.argb(130, 255, 255, 255));
            horizonPaint.setStrokeWidth(dp(2));
            horizonPaint.setStyle(Paint.Style.STROKE);

            textPaint.setColor(Color.argb(50, 255, 255, 255));
            textPaint.setTextSize(dp(14));
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
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

            // 1. Dynamic Brake Vignette (Radial red glow when left half is pressed)
            if (brakeAlpha > 0.01f) {
                vignettePaint.setShader(new RadialGradient(0, h, w * 0.45f,
                        Color.argb((int) (brakeAlpha * 255), 255, 30, 30),
                        Color.TRANSPARENT, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w * 0.45f, h, vignettePaint);
            }

            // 2. Subtle Steering Horizon Indicator (Tilts with the device)
            if ("rr3_tilt_a".equals(controlScheme)) {
                canvas.save();
                float cx = w * 0.5f;
                float cy = h * 0.88f;
                canvas.translate(cx, cy);
                canvas.rotate(-tiltDeg);
                float barW = dp(60);
                canvas.drawLine(-barW, 0, barW, 0, horizonPaint);
                canvas.drawLine(0, -dp(6), 0, dp(6), horizonPaint);
                canvas.restore();
            }

            // Minimal clean watermark labels
            canvas.drawText("BRAKE", w * 0.22f, h - dp(24), textPaint);
            canvas.drawText(autoThrottle ? "AUTO" : "THROTTLE", w * 0.78f, h - dp(24), textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            int w = getWidth();
            int h = getHeight();

            if ("classic".equals(controlScheme) || "gamepad".equals(controlScheme)) {
                return false;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_MOVE: {
                    boolean braking = false;
                    boolean throttling = autoThrottle;
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);
                        if (x < w * 0.45f) {
                            braking = true;
                        } else if (x > w * 0.55f) {
                            throttling = true;
                        } else {
                            if (y > h * 0.6f) {
                                SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_BUTTON_A); // Handbrake
                                vibrate(PinyonShiftActivity.this, 15, 150);
                            } else if (y < h * 0.4f) {
                                SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_BUTTON_Y); // Rewind
                            }
                        }
                    }
                    brakeAlpha = braking ? 0.20f : 0.0f;
                    if (braking) {
                        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_BUTTON_L2);
                        vibrate(PinyonShiftActivity.this, 10, 100);
                    } else {
                        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_BUTTON_L2);
                    }
                    if (throttling) {
                        SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_BUTTON_R2);
                    } else {
                        SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_BUTTON_R2);
                    }
                    postInvalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    brakeAlpha = 0.0f;
                    SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_BUTTON_L2);
                    if (!autoThrottle) SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_BUTTON_R2);
                    SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_BUTTON_A);
                    SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_BUTTON_Y);
                    postInvalidate();
                    return true;
            }
            return super.onTouchEvent(event);
        }
    }

    // --- Helpers & Utilities ---

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
        } catch (Exception e) {
            return null;
        }
    }

    public static void vibrate(Context context, int ms, int amplitude) {
        if (sVibrator == null && context != null) {
            sVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (sVibrator != null && sVibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26) {
                sVibrator.vibrate(VibrationEffect.createOneShot(ms,
                        Math.min(255, Math.max(1, amplitude))));
            } else {
                sVibrator.vibrate(ms);
            }
        }
    }

    private byte[] readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int read = 0;
            while (read < b.length) {
                int r = in.read(b, read, b.length - read);
                if (r <= 0) break;
                read += r;
            }
            return b;
        }
    }

    private int dp(float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    // The Adreno GPU's model number ("740"), from the kernel driver, or from
    // the SoC where that is not readable; null when not an Adreno.
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
        if (adreno == null) return null;
        return adreno.startsWith("6") || adreno.startsWith("7") || adreno.startsWith("8") ? "turnip-gen8-v37" : null;
    }

    static void installBundledDrivers(AssetManager assets, File driversDir) {
        try {
            String[] names = assets.list("drivers");
            if (names == null) return;
            for (String name : names) {
                String[] files = assets.list("drivers/" + name);
                if (files == null || files.length == 0) continue;
                File folder = new File(driversDir, name);
                folder.mkdirs();
                for (String file : files) {
                    File target = new File(folder, file);
                    long size;
                    try (InputStream in = assets.open("drivers/" + name + "/" + file)) {
                        size = 0;
                        byte[] buffer = new byte[65536];
                        for (int read; (read = in.read(buffer)) > 0; ) size += read;
                    }
                    if (target.length() == size) continue;
                    try (InputStream in = assets.open("drivers/" + name + "/" + file);
                         OutputStream out = new FileOutputStream(target)) {
                        copy(in, out);
                    }
                    Log.i(TAG, "bundled GPU driver " + name + ": " + file + " installed");
                }
            }
        } catch (IOException error) {
            Log.w(TAG, "bundled GPU drivers not installed", error);
        }
    }

    private void installBundledDrivers() {
        installBundledDrivers(getAssets(), driversDir);
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[65536];
        for (int read; (read = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, read);
        }
    }

    public void importGpuDriver() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                        "application/zip", "application/x-zip-compressed", "application/octet-stream"});
                try {
                    startActivityForResult(intent, REQUEST_GPU_DRIVER_ZIP);
                } catch (Exception error) {
                    Log.e(TAG, "no document picker", error);
                    toast("No file picker available on this device");
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_GPU_DRIVER_ZIP) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    result = "GPU driver " + unpackDriver(uri)
                            + " imported: choose it in SETTINGS > GRAPHICS > GPU DRIVER";
                } catch (IOException error) {
                    Log.e(TAG, "GPU driver import failed", error);
                    result = "GPU driver not imported: " + error.getMessage();
                }
                toast(result);
            }
        }, "GpuDriverImport").start();
    }

    static String unpackDriver(ContentResolver resolver, File driversDir, Uri uri) throws IOException {
        String display = "driver";
        try (Cursor cursor = resolver.query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                display = cursor.getString(0);
            }
        }
        String name = display.replaceAll("(?i)\\.(zip|adpkg)$", "")
                .replaceAll("[^A-Za-z0-9._-]+", "-").toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.startsWith(".")) name = "driver";
        File folder = new File(driversDir, name);
        File staging = new File(driversDir, "." + name + ".importing");
        deleteTree(staging);
        staging.mkdirs();
        boolean library = false;
        try (InputStream in = resolver.openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                String file = new File(entry.getName()).getName();
                if (file.isEmpty() || file.startsWith(".")) continue;
                library |= file.endsWith(".so");
                try (OutputStream out = new FileOutputStream(new File(staging, file))) {
                    copy(zip, out);
                }
            }
        }
        if (!library) {
            deleteTree(staging);
            throw new IOException("the archive holds no driver library (.so)");
        }
        deleteTree(folder);
        if (!staging.renameTo(folder)) {
            throw new IOException("cannot create " + folder);
        }
        return name;
    }

    private String unpackDriver(Uri uri) throws IOException {
        return unpackDriver(getContentResolver(), driversDir, uri);
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

    private static void setDefaultEnvironment(String name, String value) {
        if (Os.getenv(name) == null) {
            setEnvironment(name, value);
        }
    }

    private static void setEnvironment(String name, String value) {
        try {
            Os.setenv(name, value, true);
        } catch (ErrnoException error) {
            Log.e(TAG, "setenv " + name + " failed", error);
        }
    }
}
