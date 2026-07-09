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

package com.ginkage.wearmouse.input;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

/**
 * Detects finger-pinch gestures from the wrist IMU and reports them as press/release events. A short
 * pinch with no motion is meant to become a click; a pinch held while the wrist moves is meant to
 * become a grab-to-scroll. This class only reports the down/up transitions — the controller decides
 * which it was (by how much scrolling happened) and applies release inertia.
 *
 * <p><b>Detection.</b> A pinch is a sharp transient: a large <b>jerk</b> (rate of change of linear
 * acceleration). The press is gated by the gyroscope — aiming the air mouse is wrist rotation (loud
 * gyro) while a pinch is a jolt with little rotation (quiet gyro) — so pointing never triggers a
 * grab. Amplitude is useless here: a normal pinch barely reaches 2–4 m/s², while its jerk spikes to
 * 170–400.
 *
 * <p><b>Release is adaptive,</b> because telling "let go" from ongoing motion is the hard part:
 *
 * <ul>
 *   <li>If the wrist has stayed still since the press (a tap-to-click), a light jerk — or simply a
 *       short settle — ends it, so clicks resolve quickly.
 *   <li>Once the wrist has been moving (a scroll), ordinary motion jerk must be ignored, so release
 *       needs either a hard deliberate lift or the wrist coming to rest; a safety timeout backstops
 *       a missed release so the grab can never stick.
 * </ul>
 *
 * <p>Tunables were set from on-wrist recordings (see {@code RecorderActivity}); re-capture to re-tune.
 */
public class TapDetector implements SensorEventListener {

    /** Callback for pinch transitions. Delivered on the main thread. */
    public interface PinchListener {
        /** The pinch began (finger contact). */
        @MainThread
        void onPinchDown();

        /** The pinch ended (finger release, settle, or safety timeout). Always follows a down. */
        @MainThread
        void onPinchUp();
    }

    // --- Tunables ------------------------------------------------------------

    /** Jerk (m/s^3) that counts as a pinch contact. Real pinches are 170–400, motion stays <~115.
     * Nudged below the old 100 to catch the softest pinches; the gyro gate still rejects motion. */
    private static final float JERK_THRESHOLD = 90f;

    /** Max angular speed (rad/s) at which a press is accepted, so aiming the cursor never grabs.
     * Relaxed from 1.2 so a tap done with a little incidental wrist motion isn't rejected; pointing
     * is smooth rotation (low jerk) so it still won't reach the jerk threshold within this gate. */
    private static final float GYRO_GATE_RAD_S = 1.4f;

    /** Above this angular speed (rad/s) we consider the wrist to be moving (i.e. scrolling). */
    private static final float MOVE_GYRO_RAD_S = 0.7f;

    /** Ignore releases for this long after a press, to skip the contact's own ring-down. */
    private static final long MIN_HOLD_MS = 90;

    /** Release jerk while the wrist has stayed still (a tap) — low, so clicks resolve fast. */
    private static final float JERK_RELEASE_QUIET = 80f;

    /** Release jerk once the wrist is moving (a scroll) — high, to ignore scroll-motion jerk. */
    private static final float JERK_RELEASE_MOVING = 220f;

    /**
     * The wrist is "at rest" below this angular speed. Recordings show scrolling sits at 0.5–2 rad/s
     * and true rest near 0.05, so this cleanly marks the end of a gesture. Jerk is deliberately not
     * part of this test — the un-pinch itself spikes jerk, and including it would delay the settle.
     */
    private static final float STILL_GYRO_RAD_S = 0.35f;

    /** After a scroll, how long the wrist must be at rest to release — short, to stop promptly. */
    private static final long STILL_RELEASE_MS = 150;

    /** For a quiet hold (a tap), the rest fallback before clicking — longer, so a scroll has time
     * to get going after the pinch before it would otherwise resolve as a click. */
    private static final long QUIET_RELEASE_MS = 350;

    /** Hard cap on a held pinch; force-release after this so it can never stick. */
    private static final long SAFETY_TIMEOUT_MS = 1200;

    /** Dead-time after a release before the next press is accepted. */
    private static final long REFRACTORY_MS = 200;

    /** Sampling period (us). 100 Hz matches the tuning rate and needs no high-rate permission. */
    private static final int SAMPLE_PERIOD_US = 10000;

    // --- State (all on the main thread: listeners registered without a Handler) --------------

    private final SensorManager sensorManager;
    @Nullable private final Sensor linearAccel;
    @Nullable private final Sensor gyroscope;
    private final PinchListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean started;
    private boolean pressed;

    private float gyroSpeed;

    private boolean havePrev;
    private float prevX;
    private float prevY;
    private float prevZ;
    private long prevTimestampNs;

    private long downUptimeMs;
    private long restSinceUptimeMs;
    private boolean movedSincePinch;
    private long lastReleaseUptimeMs;

    private final Runnable safetyRelease =
            () -> {
                if (pressed) {
                    release();
                }
            };

    /**
     * @param context Context used to obtain the {@link SensorManager}.
     * @param listener Callback to receive pinch transitions.
     */
    public TapDetector(Context context, PinchListener listener) {
        this.sensorManager =
                (SensorManager) checkNotNull(context).getSystemService(Context.SENSOR_SERVICE);
        this.linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        this.gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        this.listener = checkNotNull(listener);
    }

    /** @return {@code true} if this device exposes the sensor required for pinch detection. */
    public boolean isSupported() {
        return linearAccel != null;
    }

    /** Begin listening. Safe to call repeatedly. */
    public void start() {
        if (started || linearAccel == null) {
            return;
        }
        started = true;
        pressed = false;
        havePrev = false;
        gyroSpeed = 0f;
        sensorManager.registerListener(this, linearAccel, SAMPLE_PERIOD_US);
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SAMPLE_PERIOD_US);
        }
    }

    /** Stop listening, completing any in-flight pinch. Safe to call repeatedly. */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        sensorManager.unregisterListener(this);
        handler.removeCallbacks(safetyRelease);
        if (pressed) {
            pressed = false;
            listener.onPinchUp();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            final float gx = event.values[0];
            final float gy = event.values[1];
            final float gz = event.values[2];
            gyroSpeed = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
            return;
        }

        // Linear acceleration: jerk against the previous sample.
        final float x = event.values[0];
        final float y = event.values[1];
        final float z = event.values[2];
        if (!havePrev) {
            havePrev = true;
            prevX = x;
            prevY = y;
            prevZ = z;
            prevTimestampNs = event.timestamp;
            return;
        }
        final float dx = x - prevX;
        final float dy = y - prevY;
        final float dz = z - prevZ;
        final float dtSec = (event.timestamp - prevTimestampNs) * 1e-9f;
        prevX = x;
        prevY = y;
        prevZ = z;
        prevTimestampNs = event.timestamp;
        if (dtSec <= 0f) {
            return;
        }
        final float jerk = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / dtSec;
        final long now = SystemClock.uptimeMillis();

        if (!pressed) {
            if (jerk >= JERK_THRESHOLD
                    && gyroSpeed < GYRO_GATE_RAD_S
                    && now - lastReleaseUptimeMs >= REFRACTORY_MS) {
                pressed = true;
                movedSincePinch = false;
                downUptimeMs = now;
                restSinceUptimeMs = now;
                handler.removeCallbacks(safetyRelease);
                handler.postDelayed(safetyRelease, SAFETY_TIMEOUT_MS);
                listener.onPinchDown();
            }
            return;
        }

        // Pressed: watch for motion and for the release condition.
        if (gyroSpeed > MOVE_GYRO_RAD_S) {
            movedSincePinch = true;
        }
        final boolean atRest = gyroSpeed < STILL_GYRO_RAD_S;
        if (!atRest) {
            restSinceUptimeMs = now;
        }
        if (now - downUptimeMs < MIN_HOLD_MS) {
            return;
        }

        final boolean release;
        if (!movedSincePinch) {
            // Quiet hold (a tap): a light lift or a brief settle ends it → quick click.
            release = jerk >= JERK_RELEASE_QUIET || now - restSinceUptimeMs >= QUIET_RELEASE_MS;
        } else {
            // Scrolling: ignore motion jerk; release on a hard lift or the wrist coming to rest.
            release = jerk >= JERK_RELEASE_MOVING || now - restSinceUptimeMs >= STILL_RELEASE_MS;
        }
        if (release) {
            release();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @MainThread
    private void release() {
        pressed = false;
        lastReleaseUptimeMs = SystemClock.uptimeMillis();
        handler.removeCallbacks(safetyRelease);
        listener.onPinchUp();
    }
}
