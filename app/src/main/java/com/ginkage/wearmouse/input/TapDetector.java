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
import android.util.Log;
import com.ginkage.wearmouse.BuildConfig;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import java.util.Locale;

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

    /** Debug tracing of every press/release decision; watch with `adb logcat -s TapDetector`. */
    private static final String TAG = "TapDetector";

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
     * The 2026-07-10 tap recordings show every genuine press lands with gyro ≤ 1.0, while the old
     * speculative 1.4 admitted motion-noise jerk as false taps during pointing. 1.1 keeps a little
     * headroom over the recorded taps; raise only with data. */
    private static final float GYRO_GATE_RAD_S = 1.1f;

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

    /** Quiet-hold budget while button-dragging: the wearer needs time to line up before the sweep
     * starts, so a drag-hold waits much longer before a still pinch resolves as a click. */
    private static final long DRAG_QUIET_RELEASE_MS = 700;

    /** Quiet-hold release jerk while button-dragging — firmer than a tap's, so a small adjustment
     * twitch before the sweep doesn't end the hold as a premature click. */
    private static final float DRAG_JERK_RELEASE_QUIET = 150f;

    /** Hard cap on a held pinch; force-release after this so it can never stick. */
    private static final long SAFETY_TIMEOUT_MS = 1200;

    /** Rest-release while button-dragging: selecting text or hovering an icon over a target means
     * pausing to aim, so a drag tolerates much longer stillness than a scroll before it counts as
     * "let go". 500 dropped drags mid-aim in practice; the drop is confirmed by a buzz, so a
     * longer wait is discoverable. */
    private static final long DRAG_STILL_RELEASE_MS = 800;

    /** Release jerk while button-dragging. Dragging swings the arm harder than scrolling, and its
     * motion jerk was cutting drags at the scroll threshold (220) — demand a truly hard lift. */
    private static final float DRAG_JERK_RELEASE = 300f;

    /** Safety cap while button-dragging — sweeping out a long selection takes seconds. */
    private static final long DRAG_SAFETY_TIMEOUT_MS = 5000;

    /** Absolute dead-time after a release before any press is accepted. */
    private static final long HARD_REFRACTORY_MS = 60;

    /**
     * Between the hard dead-time and this long after a release, a press must also follow silence
     * (see below). Live double-tap logs showed second presses landing 117-149 ms after the first
     * release with jerk 146-224 — the same time range AND amplitude as a single tap's release
     * wobble, so no fixed refractory or jerk bar can split them. What splits them: wobble is a
     * continuous ring-down (spikes every 10-40 ms), a real second tap arrives after a quiet gap.
     */
    private static final long REARM_WINDOW_MS = 250;

    /** Jerk below this counts as quiet for re-arming. */
    private static final float REARM_QUIET_JERK = 70f;

    /** How much continuous quiet must precede a press inside the re-arm window. Live second taps
     * showed 39-78 ms of silence before landing; wobble trains never go quiet this long. */
    private static final long REARM_QUIET_GAP_MS = 30;

    /** A drag-hold's stricter quiet-release rules only kick in after this long — before it, the
     * press is treated as the quick second tap of a double-click, whose release must stay fast. */
    private static final long DRAG_QUIET_GRACE_MS = 300;

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

    /** The current press is a button-drag (set by the controller): relax the release rules. */
    private boolean dragHold;

    /** Ignore motion until this uptime: the watch's own vibration motor rings the IMU hard enough
     * to latch the moved-flag and to fake press/release jerk. */
    private long maskUntilUptimeMs;

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

    /** Uptime of the most recent sample whose jerk exceeded {@link #REARM_QUIET_JERK}. */
    private long lastLoudUptimeMs;

    private final Runnable safetyRelease =
            () -> {
                if (pressed) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "release safety-timeout drag=" + dragHold);
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

        // While our own motor rings, don't let its noise latch motion state or fake a press.
        // Releases are unaffected: MIN_HOLD already gates them past the ring-down, and a real
        // quick release (~90-110ms after contact) must not be swallowed by the mask.
        final boolean masked = now < maskUntilUptimeMs;

        // Silence tracking for re-arming: how long ago the jerk was last loud, EXCLUDING the
        // current sample (a candidate press is itself loud).
        final long prevLoudUptimeMs = lastLoudUptimeMs;
        if (jerk >= REARM_QUIET_JERK) {
            lastLoudUptimeMs = now;
        }

        if (!pressed) {
            if (jerk >= JERK_THRESHOLD && gyroSpeed < GYRO_GATE_RAD_S) {
                final long sinceRelease = now - lastReleaseUptimeMs;
                if (masked) {
                    if (BuildConfig.DEBUG) Log.d(TAG, String.format(Locale.US,
                            "press blocked (buzz mask) jerk=%.0f gyro=%.2f", jerk, gyroSpeed));
                } else if (sinceRelease < HARD_REFRACTORY_MS) {
                    if (BuildConfig.DEBUG) Log.d(TAG, String.format(Locale.US,
                            "press blocked (dead-time %dms) jerk=%.0f gyro=%.2f",
                            sinceRelease, jerk, gyroSpeed));
                } else if (sinceRelease < REARM_WINDOW_MS
                        && now - prevLoudUptimeMs < REARM_QUIET_GAP_MS) {
                    // Still ringing from the release — not a new tap until it goes quiet.
                    if (BuildConfig.DEBUG) Log.d(TAG, String.format(Locale.US,
                            "press blocked (ring %dms, loud %dms ago) jerk=%.0f gyro=%.2f",
                            sinceRelease, now - prevLoudUptimeMs, jerk, gyroSpeed));
                } else {
                    pressed = true;
                    movedSincePinch = false;
                    downUptimeMs = now;
                    restSinceUptimeMs = now;
                    handler.removeCallbacks(safetyRelease);
                    handler.postDelayed(safetyRelease, SAFETY_TIMEOUT_MS);
                    if (BuildConfig.DEBUG) Log.d(TAG, String.format(Locale.US,
                            "press jerk=%.0f gyro=%.2f sinceRelease=%dms quietGap=%dms",
                            jerk, gyroSpeed, sinceRelease, now - prevLoudUptimeMs));
                    listener.onPinchDown();
                }
            }
            return;
        }

        // Pressed: watch for motion and for the release condition. Motor noise counts as rest.
        if (!masked && gyroSpeed > MOVE_GYRO_RAD_S) {
            movedSincePinch = true;
        }
        final boolean atRest = masked || gyroSpeed < STILL_GYRO_RAD_S;
        if (!atRest) {
            restSinceUptimeMs = now;
        }
        if (now - downUptimeMs < MIN_HOLD_MS) {
            return;
        }

        final String releaseReason;
        if (!movedSincePinch) {
            // Quiet hold (a tap): a light lift or a brief settle ends it → quick click. A
            // drag-hold that has lasted past the double-click grace demands a firmer lift, so
            // there's room to aim before the sweep — but a quick second tap releases fast.
            final boolean aiming = dragHold && now - downUptimeMs > DRAG_QUIET_GRACE_MS;
            final float liftJerk = aiming ? DRAG_JERK_RELEASE_QUIET : JERK_RELEASE_QUIET;
            final long quietMs = dragHold ? DRAG_QUIET_RELEASE_MS : QUIET_RELEASE_MS;
            releaseReason =
                    (jerk >= liftJerk)
                            ? "quiet-lift j=" + (int) jerk
                            : (now - restSinceUptimeMs >= quietMs) ? "quiet-settle" : null;
        } else {
            // Scrolling or dragging: ignore motion jerk; release on a hard lift or the wrist
            // coming to rest — a drag tolerates a much longer aiming pause and a harder swing
            // than a scroll.
            final long stillMs = dragHold ? DRAG_STILL_RELEASE_MS : STILL_RELEASE_MS;
            final float liftJerk = dragHold ? DRAG_JERK_RELEASE : JERK_RELEASE_MOVING;
            releaseReason =
                    (jerk >= liftJerk)
                            ? "move-lift j=" + (int) jerk
                            : (now - restSinceUptimeMs >= stillMs) ? "move-rest" : null;
        }
        if (releaseReason != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, String.format(Locale.US,
                    "release %s hold=%dms moved=%b drag=%b",
                    releaseReason, now - downUptimeMs, movedSincePinch, dragHold));
            release();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * Blind the detector for a moment because the app is about to buzz its own motor. Without
     * this, the vibration latches the moved-flag (pushing releases onto the strict drag rules)
     * and its jerk can fake presses and releases.
     *
     * @param durationMs How long to ignore the IMU, from now.
     */
    @MainThread
    void maskSelfVibration(long durationMs) {
        maskUntilUptimeMs = SystemClock.uptimeMillis() + durationMs;
    }

    /**
     * Mark the in-flight press as a button-drag (the controller decides this when a pinch lands
     * right after a click). Extends the rest-release window and the safety cap so a long, careful
     * selection sweep isn't cut short. Cleared automatically when the press releases.
     */
    @MainThread
    void setDragHold() {
        if (!pressed) {
            return;
        }
        dragHold = true;
        handler.removeCallbacks(safetyRelease);
        handler.postDelayed(safetyRelease, DRAG_SAFETY_TIMEOUT_MS);
    }

    @MainThread
    private void release() {
        pressed = false;
        dragHold = false;
        lastReleaseUptimeMs = SystemClock.uptimeMillis();
        handler.removeCallbacks(safetyRelease);
        listener.onPinchUp();
    }
}
