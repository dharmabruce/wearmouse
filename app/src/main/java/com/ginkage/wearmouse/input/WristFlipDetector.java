/*
 * Copyright 2026 The WearMouse Authors. All Rights Reserved.
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
import android.os.SystemClock;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

/**
 * Detects a deliberate wrist flip (rolling the watch toward upside-down and back) and reports it as
 * a Back or Home gesture: a quick flip-and-return is Back; staying flipped for a full second is
 * Home, announced while the wrist is still turned so the wearer feels the confirmation before
 * rolling back.
 *
 * <p><b>Detection.</b> On-wrist recordings showed the natural "flip upside down" is only ~110° of
 * roll, not 180°: gravity never lands on -z (screen normal), but it does swing almost fully onto
 * the watch's <b>-y axis</b> (all recorded flips reached gy ≤ -9.37 m/s²). Everyday motions
 * (checking the time, drinking, arm hanging) reached gy -7.03 at worst, so the inversion threshold
 * sits between those. The second discriminator is roll speed: deliberate flips peaked at 13-18.7
 * rad/s of gyro while daily motion stayed under 4.4, so a slow drift into an inverted pose never
 * triggers.
 *
 * <p>A gesture must start from a screen-up pose (gz high — true whenever the air mouse is in use)
 * and ends only when that pose is restored, so the controller can freeze the pointer for the whole
 * round trip. The flip is a violent rotation that would otherwise smear the cursor.
 *
 * <p>Thresholds were tuned from recordings on the author's left wrist (see {@code
 * RecorderActivity}, 2026-07-10 logs); a right-wrist or rotated wearer likely flips the sign of gy
 * — re-record to re-tune.
 */
public class WristFlipDetector implements SensorEventListener {

    /** Callback for flip gestures. Delivered on the main thread. */
    public interface FlipListener {
        /** A flip has plausibly begun: freeze the pointer until {@link #onFlipEnd()}. */
        @MainThread
        void onFlipStart();

        /** A quick flip-and-return completed: perform Back. Followed by {@link #onFlipEnd()}. */
        @MainThread
        void onBack();

        /**
         * The wrist stayed flipped for the full hold: perform Home. Fires while still inverted;
         * {@link #onFlipEnd()} follows once the wrist rolls back.
         */
        @MainThread
        void onHome();

        /** The gesture is over (completed, cancelled, or timed out): unfreeze the pointer. */
        @MainThread
        void onFlipEnd();
    }

    // --- Tunables (from the 2026-07-10 on-wrist recordings) --------------------

    /** Screen-up gravity-z (m/s²) required to arm, and to end a gesture. True while air-mousing. */
    private static final float GZ_NORMAL = 6f;

    /** Gravity-y below this means a flip is plausibly underway: freeze the pointer. Everyday
     * wrist rolls do cross this (worst -7.03), so this only pauses the cursor — briefly freezing
     * while the wearer checks the time is a feature, not a cost. */
    private static final float GY_SUPPRESS = -5f;

    /** Rolling back above this without having inverted cancels the gesture. */
    private static final float GY_CANCEL = -4f;

    /** Inverted: recorded flips all reached gy ≤ -9.37, daily motion never passed -7.03; this
     * splits the gap with ~1 m/s² margin each way. */
    private static final float GY_INVERTED = -8.2f;

    /** Leaving the inverted pose (hysteresis against wobble at the threshold). */
    private static final float GY_INVERTED_EXIT = -7f;

    /** Peak angular speed (rad/s) the roll-in must reach. Real flips hit 13-18.7, everything else
     * stayed under 4.4 — a slow rotation into an odd pose is posture, not a gesture. */
    private static final float GYRO_GATE_RAD_S = 7f;

    /** Stay inverted this long and it's Home; return sooner and it's Back. Recorded quick flips
     * dwelt ≤ 0.75 s and deliberate holds ≥ 1.77 s, so one full second splits them comfortably. */
    private static final long HOME_DWELL_MS = 1000;

    /** A gesture that hasn't resolved by now is abandoned, so the pointer can never stay frozen. */
    private static final long GESTURE_TIMEOUT_MS = 3000;

    /** Dead-time after a gesture before the next may arm, to skip settle wobble. */
    private static final long REFRACTORY_MS = 300;

    /** Sampling period (us). 100 Hz, same as the recordings the thresholds came from. */
    private static final int SAMPLE_PERIOD_US = 10000;

    // --- State (all on the main thread: listeners registered without a Handler) --------------

    /** Gesture progress. Linear except for the INVERTED ↔ RETURNING wobble. */
    private enum State {
        /** Waiting for a screen-up pose (post-gesture, or just started). */
        DISARMED,
        /** Screen-up seen; watching for the roll to begin. */
        READY,
        /** Rolling: pointer frozen, waiting for full inversion or a roll-back. */
        TILTING,
        /** Inverted: dwell timer decides Back (leave early) vs Home (stay the full hold). */
        INVERTED,
        /** Rolling back toward screen-up; fires Back on arrival unless Home already fired. */
        RETURNING
    }

    private final SensorManager sensorManager;
    @Nullable private final Sensor gravity;
    @Nullable private final Sensor gyroscope;
    private final FlipListener listener;

    private boolean started;
    private State state = State.DISARMED;
    private float gyroSpeed;
    private float peakGyro;
    private boolean homeFired;
    private long tiltStartMs;
    private long invertedAtMs;
    private long lastEndMs;

    /**
     * @param context Context used to obtain the {@link SensorManager}.
     * @param listener Callback to receive flip gestures.
     */
    public WristFlipDetector(Context context, FlipListener listener) {
        this.sensorManager =
                (SensorManager) checkNotNull(context).getSystemService(Context.SENSOR_SERVICE);
        this.gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        this.gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        this.listener = checkNotNull(listener);
    }

    /** @return {@code true} if this device exposes the sensors required for flip detection. */
    public boolean isSupported() {
        return gravity != null && gyroscope != null;
    }

    /** Begin listening. Safe to call repeatedly. */
    public void start() {
        if (started || !isSupported()) {
            return;
        }
        started = true;
        state = State.DISARMED;
        gyroSpeed = 0f;
        sensorManager.registerListener(this, gravity, SAMPLE_PERIOD_US);
        sensorManager.registerListener(this, gyroscope, SAMPLE_PERIOD_US);
    }

    /** Stop listening, ending any in-flight gesture (without an action). Safe to call repeatedly. */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        sensorManager.unregisterListener(this);
        if (state == State.TILTING || state == State.INVERTED || state == State.RETURNING) {
            endGesture();
        }
        state = State.DISARMED;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            final float wx = event.values[0];
            final float wy = event.values[1];
            final float wz = event.values[2];
            gyroSpeed = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (state == State.TILTING) {
                peakGyro = Math.max(peakGyro, gyroSpeed);
            }
            return;
        }

        final float gy = event.values[1];
        final float gz = event.values[2];
        final long now = SystemClock.uptimeMillis();

        switch (state) {
            case DISARMED:
                if (gz > GZ_NORMAL && now - lastEndMs >= REFRACTORY_MS) {
                    state = State.READY;
                }
                break;

            case READY:
                if (gy < GY_SUPPRESS) {
                    state = State.TILTING;
                    tiltStartMs = now;
                    peakGyro = gyroSpeed;
                    homeFired = false;
                    listener.onFlipStart();
                }
                break;

            case TILTING:
                if (gy < GY_INVERTED && peakGyro >= GYRO_GATE_RAD_S) {
                    state = State.INVERTED;
                    invertedAtMs = now;
                } else if (gy > GY_CANCEL || now - tiltStartMs > GESTURE_TIMEOUT_MS) {
                    // Rolled back without inverting (or a slow roll the gyro gate rejected).
                    endGesture();
                }
                break;

            case INVERTED:
                if (now - invertedAtMs >= HOME_DWELL_MS) {
                    homeFired = true;
                    state = State.RETURNING;
                    listener.onHome();
                } else if (gy > GY_INVERTED_EXIT) {
                    state = State.RETURNING;
                }
                break;

            case RETURNING:
                if (gy < GY_INVERTED && !homeFired) {
                    // Wobble back into the inverted pose: same hold, keep the original clock.
                    state = State.INVERTED;
                } else if (gz > GZ_NORMAL) {
                    if (!homeFired) {
                        listener.onBack();
                    }
                    endGesture();
                } else if (now - tiltStartMs > GESTURE_TIMEOUT_MS) {
                    // Wandered off mid-return: give up without an action.
                    endGesture();
                }
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @MainThread
    private void endGesture() {
        state = State.DISARMED;
        lastEndMs = SystemClock.uptimeMillis();
        listener.onFlipEnd();
    }
}
