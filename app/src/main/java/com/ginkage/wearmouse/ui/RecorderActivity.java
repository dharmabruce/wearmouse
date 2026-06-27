/*
 * Copyright 2025 The WearMouse Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ginkage.wearmouse.ui;

import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.wearable.activity.WearableActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * Debug-only tool that records the raw IMU streams (linear acceleration, raw acceleration, and
 * gyroscope) to a CSV file, so tap-vs-motion traces can be captured on-wrist and analysed offline
 * to tune {@link com.ginkage.wearmouse.input.TapDetector}.
 *
 * <p>It is intentionally not in any menu. Launch it over adb:
 *
 * <pre>adb shell am start -n com.ginkage.wearmouse/.ui.RecorderActivity</pre>
 *
 * <p>Tap the big button to start/stop. Files land in the app's external files dir; pull them with:
 *
 * <pre>adb pull /sdcard/Android/data/com.ginkage.wearmouse/files/</pre>
 *
 * <p>Suggested protocol for one file: hold still ~3s, then ~5 deliberate taps with ~2s gaps, then
 * air-mouse the cursor around ~5s, then one tap-hold-drag. The bursts segment cleanly by eye.
 *
 * <p>Writing is done synchronously on the main thread and flushed periodically, so data is durable
 * even if the recording is never explicitly stopped.
 */
public class RecorderActivity extends WearableActivity implements SensorEventListener {

    private static final String TAG = "RecorderActivity";

    /** Short sensor tags written into the CSV's "sensor" column. */
    private static final String TAG_LINEAR_ACCEL = "la";
    private static final String TAG_ACCEL = "ac";
    private static final String TAG_GYRO = "gy";

    /** Flush to disk every this many samples so a crash/kill loses at most this much. */
    private static final int FLUSH_EVERY = 64;

    private SensorManager sensorManager;
    private Sensor linearAccel;
    private Sensor accel;
    private Sensor gyro;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private TextView status;
    private Button toggle;

    private boolean recording;
    private long startUptimeMs;
    private File currentFile;
    private BufferedWriter writer;
    private int sampleCount;
    private final StringBuilder line = new StringBuilder(64);

    private final Runnable uiTicker =
            new Runnable() {
                @Override
                public void run() {
                    if (!recording) {
                        return;
                    }
                    long secs = (SystemClock.uptimeMillis() - startUptimeMs) / 1000;
                    status.setText(
                            String.format(
                                    Locale.US,
                                    "REC %02d:%02d\n%d samples\n%s",
                                    secs / 60,
                                    secs % 60,
                                    sampleCount,
                                    currentFile != null ? currentFile.getName() : ""));
                    uiHandler.postDelayed(this, 250);
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setAmbientEnabled();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Log.i(
                TAG,
                "sensors: linearAccel=" + linearAccel + " accel=" + accel + " gyro=" + gyro);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setKeepScreenOn(true);
        root.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14f);
        status.setText("Ready");
        root.addView(status);

        toggle = new Button(this);
        toggle.setText("START");
        toggle.setOnClickListener(this::onToggle);
        root.addView(toggle);

        setContentView(root);
    }

    private void onToggle(View v) {
        if (recording) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Allow starting a capture straight from adb without tapping (handy when the watch UI keeps
        // stealing focus): adb shell am start -n .../.ui.RecorderActivity --ez autostart true
        if (!recording && getIntent() != null && getIntent().getBooleanExtra("autostart", false)) {
            startRecording();
        }
    }

    private void startRecording() {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            status.setText("No external files dir");
            return;
        }
        currentFile = new File(dir, "sensorlog-" + System.currentTimeMillis() + ".csv");
        sampleCount = 0;
        try {
            // event.timestamp is nanoseconds since boot, common across all sensors.
            writer = new BufferedWriter(new FileWriter(currentFile));
            writer.write("t_ns,sensor,x,y,z\n");
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "Failed to open " + currentFile, e);
            status.setText("Open failed:\n" + e.getMessage());
            writer = null;
            return;
        }

        // 10000 us = 100 Hz, the hardware max here (sensors report minDelay=10000). Requesting
        // anything faster than 200 Hz (e.g. SENSOR_DELAY_FASTEST == 0) throws SecurityException
        // unless HIGH_SAMPLING_RATE_SENSORS is declared, so request the explicit hardware max.
        int rate = 10000;
        boolean any = false;
        try {
            if (linearAccel != null) {
                any |= sensorManager.registerListener(this, linearAccel, rate);
            }
            if (accel != null) {
                any |= sensorManager.registerListener(this, accel, rate);
            }
            if (gyro != null) {
                any |= sensorManager.registerListener(this, gyro, rate);
            }
        } catch (SecurityException e) {
            // Never let a sampling-rate permission issue crash the recorder.
            Log.e(TAG, "registerListener denied", e);
            sensorManager.unregisterListener(this);
            try {
                writer.close();
            } catch (IOException ignored) {
                // Nothing useful to do on close failure.
            }
            writer = null;
            status.setText("Sensor access denied:\n" + e.getMessage());
            return;
        }
        Log.i(TAG, "registerListener any=" + any + " file=" + currentFile);

        recording = true;
        startUptimeMs = SystemClock.uptimeMillis();
        toggle.setText("STOP");
        uiHandler.post(uiTicker);
    }

    private void stopRecording() {
        recording = false;
        uiHandler.removeCallbacks(uiTicker);
        sensorManager.unregisterListener(this);

        final File file = currentFile;
        final int count = sampleCount;
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                Log.e(TAG, "close failed", e);
            }
            writer = null;
        }
        Log.i(TAG, "stopped, samples=" + count + " file=" + file);

        toggle.setText("START");
        status.setText(
                String.format(
                        Locale.US,
                        "Saved %d samples\n%s",
                        count,
                        file != null ? file.getName() : ""));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (writer == null) {
            return;
        }
        final String tag;
        switch (event.sensor.getType()) {
            case Sensor.TYPE_LINEAR_ACCELERATION:
                tag = TAG_LINEAR_ACCEL;
                break;
            case Sensor.TYPE_ACCELEROMETER:
                tag = TAG_ACCEL;
                break;
            case Sensor.TYPE_GYROSCOPE:
                tag = TAG_GYRO;
                break;
            default:
                return;
        }
        line.setLength(0);
        line.append(event.timestamp)
                .append(',')
                .append(tag)
                .append(',')
                .append(event.values[0])
                .append(',')
                .append(event.values[1])
                .append(',')
                .append(event.values[2])
                .append('\n');
        try {
            writer.write(line.toString());
            sampleCount++;
            if (sampleCount == 1) {
                Log.i(TAG, "first sample written");
            }
            if (sampleCount % FLUSH_EVERY == 0) {
                writer.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "write failed", e);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onPause() {
        if (recording) {
            stopRecording();
        }
        super.onPause();
    }
}
