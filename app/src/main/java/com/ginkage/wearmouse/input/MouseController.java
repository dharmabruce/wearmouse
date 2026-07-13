/*
 * Copyright 2018 Google LLC All Rights Reserved.
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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.Surface;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import com.ginkage.wearmouse.bluetooth.HidDataSender;
import com.ginkage.wearmouse.input.MouseSensorListener.HandMode;
import com.ginkage.wearmouse.input.MouseSensorListener.MouseButton;
import com.ginkage.wearmouse.input.SettingsUtil.SettingKey;
import com.ginkage.wearmouse.sensors.SensorService;
import com.ginkage.wearmouse.sensors.SensorServiceConnection;

/** Controls the sensor-based Mouse input behaviour for the corresponding UI. */
public class MouseController {

    /** Callback for the UI. */
    public interface Ui {
        /** Called when the connection with the current device has been lost. */
        void onDeviceDisconnected();
    }

    private final HidDataSender.ProfileListener profileListener =
            new HidDataSender.ProfileListener() {
                @Override
                @MainThread
                public void onConnectionStateChanged(BluetoothDevice device, int state) {
                    if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        ui.onDeviceDisconnected();
                    }
                }

                @Override
                @MainThread
                public void onAppStatusChanged(boolean registered) {
                    if (!registered) {
                        ui.onDeviceDisconnected();
                    }
                }

                @Override
                @MainThread
                public void onServiceStateChanged(BluetoothProfile proxy) {}
            };

    private final Ui ui;
    private final SettingsUtil settings;
    private final HidDataSender hidDataSender;
    private final MouseSensorListener sensorListener;
    private final SensorServiceConnection connection;
    private final TapDetector tapDetector;
    private final WristFlipDetector flipDetector;
    @Nullable private final Vibrator vibrator;

    /**
     * @param context Activity this controller is bound to.
     * @param ui Callback for receiving the UI updates.
     */
    public MouseController(Context context, Ui ui) {
        this.ui = checkNotNull(ui);
        this.settings = new SettingsUtil(context);
        this.hidDataSender = HidDataSender.getInstance();
        this.sensorListener = new MouseSensorListener(hidDataSender);
        this.connection = new SensorServiceConnection(context, this::onServiceConnected);
        this.tapDetector =
                new TapDetector(
                        context,
                        new TapDetector.PinchListener() {
                            @Override
                            public void onPinchDown() {
                                handlePinchDown();
                            }

                            @Override
                            public void onPinchUp() {
                                handlePinchUp();
                            }
                        });
        this.flipDetector =
                new WristFlipDetector(
                        context,
                        new WristFlipDetector.FlipListener() {
                            @Override
                            public void onFlipStart() {
                                handleFlipStart();
                            }

                            @Override
                            public void onBack() {
                                // A flip that was in flight when the pointer got muted can still
                                // land here after flipDetector.stop(); drop it.
                                if (sensorListener.isPaused()) {
                                    return;
                                }
                                pressBack();
                                buzz(BUZZ_BACK);
                            }

                            @Override
                            public void onHome() {
                                if (sensorListener.isPaused()) {
                                    return;
                                }
                                pressHome();
                                buzz(BUZZ_HOME);
                            }

                            @Override
                            public void onFlipEnd() {
                                handleFlipEnd();
                            }
                        });
        this.vibrator = context.getSystemService(Vibrator.class);
        // A sweep that breaks the dead-zone pin is drag evidence: commit the button right away
        // (posted to the main thread; the break arrives on the sensor thread).
        this.sensorListener.setDeadZoneBreakListener(() -> pinchHandler.post(dragCommit));
    }

    /**
     * Should be called in the Activity's (or Fragment's) onCreate() method.
     *
     * @param context The context to register listener with.
     */
    public void onCreate(Context context) {
        sensorListener.onCreate();
        hidDataSender.register(context, profileListener);
    }

    /** Should be called in the Activity's (or Fragment's) onStart() method. */
    public void onStart() {
        connection.bind();
    }

    /** Should be called in the Activity's (or Fragment's) onStop() method. */
    public void onStop() {
        // Flip first: ending an in-flight flip may restart the tap detector, which the next line
        // then stops for good.
        flipDetector.stop();
        tapDetector.stop();
        pinchHandler.removeCallbacks(inertiaTick);
        // Stopping the tap detector mid-pinch enqueues the button release, but the queue only
        // drains on orientation frames, which unbind() is about to stop — flush it directly so
        // the host isn't left with a button held down until the next connect.
        sensorListener.flush();
        connection.unbind();
    }

    /**
     * Should be called in the Activity's (or Fragment's) onDestroy() method.
     *
     * @param context The context to unregister listener with.
     */
    public void onDestroy(Context context) {
        hidDataSender.unregister(context, profileListener);
    }

    /**
     * Should be called from a Mouse Button View's OnTouchListener callback.
     *
     * @param leftButton {@code true} if the event came from the Left mouse button, {@code false} if
     *     it was from the right one.
     * @param event Touch event to react to.
     */
    public void onTouch(MotionEvent event, boolean leftButton) {
        final int action = event.getActionMasked();
        final @MouseButton int button = leftButton ? MouseButton.LEFT : MouseButton.RIGHT;
        if (action == MotionEvent.ACTION_DOWN) {
            sendButtonEvent(button, true);
        } else if (action == MotionEvent.ACTION_UP) {
            sendButtonEvent(button, false);
        }
    }

    /**
     * Should be called when an RSB event is detected.
     *
     * @param delta Movement of the Mouse Wheel.
     */
    public void onRotaryInput(float delta) {
        sensorListener.sendMouseMove(0, 0, delta);
    }

    /** Sends a Left Mouse Button "down" event. */
    public void leftClickAndHold() {
        sendButtonEvent(MouseButton.LEFT, true);
    }

    /** Sends a Right Mouse Button "down" event. */
    public void rightClickAndHold() {
        sendButtonEvent(MouseButton.RIGHT, true);
    }

    /** Sends a Middle Mouse Button "down" event immediately followed by an "up" event. */
    public void middleClick() {
        sendButtonEvent(MouseButton.MIDDLE, true);
        sendButtonEvent(MouseButton.MIDDLE, false);
    }

    /** Taps the Android "Back" navigation button (Consumer Control AC Back → KEYCODE_BACK). */
    public void pressBack() {
        hidDataSender.sendConsumer(false, true);
        hidDataSender.sendConsumer(false, false);
    }

    /** Taps the Android "Home" navigation button (Consumer Control AC Home → KEYCODE_HOME). */
    public void pressHome() {
        hidDataSender.sendConsumer(true, false);
        hidDataSender.sendConsumer(false, false);
    }

    /**
     * Toggle the pointer mute — a quick "stop the mouse wandering while I type" switch. Freezes all
     * sensor-driven input and parks the pinch detector; the HID link stays up so resuming is instant.
     *
     * @return {@code true} if the pointer is now muted, {@code false} if it resumed.
     */
    public boolean togglePause() {
        setPaused(!sensorListener.isPaused());
        return sensorListener.isPaused();
    }

    /** @return whether the pointer is currently muted. */
    public boolean isPaused() {
        return sensorListener.isPaused();
    }

    private void setPaused(boolean paused) {
        sensorListener.setPaused(paused);
        if (paused) {
            flipDetector.stop();
            tapDetector.stop();
        } else {
            startDetectorsIfEnabled();
        }
    }

    /** Starts the tap and flip detectors that are enabled in settings and supported on-device. */
    private void startDetectorsIfEnabled() {
        if (settings.getBoolean(SettingKey.TAP_TO_CLICK) && tapDetector.isSupported()) {
            tapDetector.start();
        }
        if (settings.getBoolean(SettingKey.FLIP_GESTURES) && flipDetector.isSupported()) {
            flipDetector.start();
        }
    }

    /**
     * Sets the current watch location.
     *
     * @param hand Hand index: left wrist, center (in the hand) or right wrist.
     * @see MouseSensorListener
     */
    public void setMouseHand(@HandMode int hand) {
        settings.putMouseHand(hand);
        sensorListener.setHand(hand);
    }

    /**
     * Get the last used watch location from the last time.
     *
     * @return Hand index.
     * @see MouseSensorListener
     */
    public @HandMode int getMouseHand() {
        return settings.getMouseHand();
    }

    private void onServiceConnected(SensorService service) {
        sensorListener.setLefty(isLefty(service.getApplicationContext()));
        sensorListener.setHand(settings.getMouseHand());
        sensorListener.setStabilize(settings.getBoolean(SettingKey.STABILIZE));
        sensorListener.setReverseScroll(settings.getReverseScroll(getConnectedDeviceAddress()));
        service.startInput(sensorListener, settings.getBoolean(SettingKey.REDUCED_RATE));

        // The pointer mute survives an unbind/rebind (screen off/on, app switch): the paused flag
        // lives on the sensorListener, not the service. Re-arming the detectors here while muted
        // would let flips fire Back/Home from a "paused" pointer.
        if (!sensorListener.isPaused()) {
            startDetectorsIfEnabled();
        }
    }

    private @Nullable String getConnectedDeviceAddress() {
        BluetoothDevice device = hidDataSender.getConnectedDevice();
        return (device != null) ? device.getAddress() : null;
    }

    private boolean isLefty(Context context) {
        return Settings.System.getInt(
                        context.getContentResolver(),
                        Settings.System.USER_ROTATION,
                        Surface.ROTATION_0)
                == Surface.ROTATION_180;
    }

    private void sendButtonEvent(int button, boolean state) {
        sensorListener.sendButtonEvent(button, state);
    }

    // --- Tap / grab-to-scroll ------------------------------------------------

    /** Below this much accumulated scroll, a pinch is treated as a click rather than a scroll. */
    private static final double CLICK_SCROLL_THRESHOLD = 2.0;

    /**
     * A pinch landing within this long after a click is the second half of a double-tap: the left
     * button goes down immediately and the release decides what it was — a quick release reads as
     * the second click of a double-click, holding and moving is a drag (text selection, icon
     * moves). Recorded doubles re-press 150-250ms after the first release, so 300 covers them.
     */
    private static final long DRAG_WINDOW_MS = 300;

    /** Inertia tick period and decay; momentum stops when speed falls below the floor. */
    private static final long INERTIA_TICK_MS = 16;
    private static final double INERTIA_DECAY = 0.9;
    private static final double INERTIA_MIN = 0.3;

    private final Handler pinchHandler = new Handler(Looper.getMainLooper());
    private double inertiaVelocity;

    /**
     * How long a second-tap pinch may stay pinned and buttonless before it's committed as a drag.
     * A quick double-tap releases well inside this; past it (or on a pin-breaking sweep) the
     * button goes down and stays down until the pinch ends.
     */
    private static final long DRAG_COMMIT_MS = 300;

    /** When the last pinch-click resolved, for spotting the second tap of a double. */
    private long lastClickUptimeMs;

    /** The current pinch is a double-tap hold (pinned, possibly a drag). */
    private boolean dragging;

    /** The left button has been pressed for this hold (drag committed). */
    private boolean dragButtonSent;

    /** When the drag-hold press landed, to tell a real drag from a quick double-tap at release. */
    private long dragStartUptimeMs;

    /** Commits the pinned hold to a button-drag: on hold timeout or a pin-breaking sweep. */
    private final Runnable dragCommit =
            () -> {
                if (dragging && !dragButtonSent) {
                    dragButtonSent = true;
                    sendButtonEvent(MouseButton.LEFT, true);
                }
            };

    private final Runnable inertiaTick =
            new Runnable() {
                @Override
                public void run() {
                    sensorListener.sendMouseMove(0, 0, inertiaVelocity);
                    inertiaVelocity *= INERTIA_DECAY;
                    if (Math.abs(inertiaVelocity) >= INERTIA_MIN) {
                        pinchHandler.postDelayed(this, INERTIA_TICK_MS);
                    }
                }
            };

    @MainThread
    private void handlePinchDown() {
        pinchHandler.removeCallbacks(inertiaTick);
        inertiaVelocity = 0;
        if (SystemClock.uptimeMillis() - lastClickUptimeMs <= DRAG_WINDOW_MS) {
            // Second tap of a double: pin the cursor and wait — NO button yet. A quick release
            // sends an atomic second click at the pinned spot (pressing the button up front made
            // click2 hostage to release-detection latency: a missed soft release held the button
            // until wobble broke the pin, and the host canceled the double-click as a micro-drag).
            // The button only goes down on drag evidence: the hold outliving DRAG_COMMIT_MS, or a
            // sweep breaking the pin.
            dragging = true;
            dragButtonSent = false;
            dragStartUptimeMs = SystemClock.uptimeMillis();
            tapDetector.setDragHold();
            sensorListener.setDragDeadZone(true);
            pinchHandler.postDelayed(dragCommit, DRAG_COMMIT_MS);
        } else {
            // Lone pinch: grab the page — wrist motion now scrolls, cursor freezes.
            sensorListener.setScrollMode(true);
        }
        // No contact buzz: buzzing on every pinch fed the motor's ring back into the detector
        // and bred chains of phantom taps. Only gesture *outcomes* buzz (flips, drag end).
    }

    /** How long the motor's ring pollutes the IMU after a short buzz: drive + start latency +
     * mechanical decay, sized generously now that no real release timing is at stake. */
    private static final long SELF_BUZZ_MASK_MS = 250;

    // --- Wrist-flip Back/Home ---------------------------------------------------

    /** Haptic for Back: one firm buzz, felt as the wrist lands back screen-up. Full amplitude —
     * a default-strength 40ms tick proved imperceptible mid-gesture on the wrist. */
    private static final VibrationEffect BUZZ_BACK =
            VibrationEffect.createWaveform(new long[] {0, 80}, new int[] {0, 255}, -1);

    /** Haptic for Home: a double buzz, felt while still holding the wrist flipped — the cue that
     * the hold registered and it's safe to roll back. */
    private static final VibrationEffect BUZZ_HOME =
            VibrationEffect.createWaveform(
                    new long[] {0, 100, 80, 100}, new int[] {0, 255, 0, 255}, -1);

    /** Haptic for pinch contact: a sharp full-strength tick the instant the pinch lands, so the
     * feedback feels synced to the finger (buzzing at click *resolution* trailed soft releases by
     * up to ~400ms and felt disconnected). Short enough that the motor's ring-down dies inside the
     * detector's MIN_HOLD window, so the buzz can't read back as a release. Full amplitude — the
     * predefined EFFECT_CLICK and default-amplitude buzzes are imperceptible on this watch. */
    private static final VibrationEffect BUZZ_CONTACT =
            VibrationEffect.createWaveform(new long[] {0, 30}, new int[] {0, 255}, -1);

    @MainThread
    private void handleFlipStart() {
        // The flip is a violent roll: freeze the pointer for the round trip, and park the pinch
        // detector so the flip's stop transients can't fake a click.
        sensorListener.setGestureSuppressed(true);
        tapDetector.stop();
    }

    @MainThread
    private void handleFlipEnd() {
        sensorListener.setGestureSuppressed(false);
        if (!sensorListener.isPaused()
                && settings.getBoolean(SettingKey.TAP_TO_CLICK)
                && tapDetector.isSupported()) {
            tapDetector.start();
        }
    }

    private void buzz(VibrationEffect effect) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(effect);
        }
    }

    @MainThread
    private void handlePinchUp() {
        if (dragging) {
            // End of a double-tap hold. Not yet committed → it was a quick second tap: send an
            // atomic click at the pinned spot (a clean double-click). Committed → lift the button
            // to end the drag, confirmed by a buzz so the wearer knows the payload dropped.
            // Refreshing the click clock lets quick taps chain (triple-click selects a paragraph).
            dragging = false;
            pinchHandler.removeCallbacks(dragCommit);
            sensorListener.setDragDeadZone(false);
            if (dragButtonSent) {
                sendButtonEvent(MouseButton.LEFT, false);
                if (SystemClock.uptimeMillis() - dragStartUptimeMs > 400) {
                    // Blind the detector first: this buzz fires with no pinch held, and its ring
                    // could otherwise read as a fresh press.
                    tapDetector.maskSelfVibration(SELF_BUZZ_MASK_MS);
                    buzz(BUZZ_CONTACT);
                }
            } else {
                sendButtonEvent(MouseButton.LEFT, true);
                sendButtonEvent(MouseButton.LEFT, false);
            }
            lastClickUptimeMs = SystemClock.uptimeMillis();
            return;
        }
        final double scrolled = sensorListener.getScrollAccum();
        final double velocity = sensorListener.getScrollVelocity();
        sensorListener.setScrollMode(false);
        if (Math.abs(scrolled) < CLICK_SCROLL_THRESHOLD) {
            // Barely moved: it was a tap → a left click. The contact buzz already fired at pinch
            // down; a second buzz here trailed soft releases by ~400ms and felt out of sync.
            sendButtonEvent(MouseButton.LEFT, true);
            sendButtonEvent(MouseButton.LEFT, false);
            lastClickUptimeMs = SystemClock.uptimeMillis();
        } else {
            // It was a scroll: let it coast to a stop with macOS-style inertia.
            inertiaVelocity = velocity;
            pinchHandler.removeCallbacks(inertiaTick);
            if (Math.abs(inertiaVelocity) >= INERTIA_MIN) {
                pinchHandler.post(inertiaTick);
            }
        }
    }
}
