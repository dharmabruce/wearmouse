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

import androidx.annotation.IntDef;
import com.ginkage.wearmouse.bluetooth.MouseReport.MouseDataSender;
import com.ginkage.wearmouse.sensors.SensorService;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/** Helper class that interprets sensor data and translates it to Mouse data events. */
public class MouseSensorListener implements SensorService.OrientationListener {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({HandMode.LEFT, HandMode.CENTER, HandMode.RIGHT})
    public @interface HandMode {
        int LEFT = 0;
        int CENTER = 1;
        int RIGHT = 2;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MouseButton.LEFT, MouseButton.RIGHT, MouseButton.MIDDLE})
    public @interface MouseButton {
        int LEFT = 0;
        int RIGHT = 1;
        int MIDDLE = 2;
    }

    private static final double CURSOR_SPEED = 1024.0 / (Math.PI / 4);
    private static final double STABILIZE_BIAS = 16.0;

    /**
     * Pixels of cursor motion per one mouse-wheel tick while in scroll-grab mode. The scroll is tied
     * to the exact displacement the cursor <em>would</em> have moved, so scrolling inherits the
     * cursor's feel; this only converts those pixels into the wheel's coarse unit. Lower scrolls
     * faster (fewer pixels buys a tick). Tunable by feel.
     */
    private static final double SCROLL_PIXELS_PER_TICK = 50.0;

    /**
     * Low-pass weight on the previous frame's wheel rate (0..1) while grabbing. The 8-bit wheel can
     * only emit whole ticks, so a noisy per-frame rate comes out as bursty single ticks that the
     * host's own scroll acceleration then amplifies into visible jumps. Smoothing the rate spreads
     * those ticks evenly. At the 50–89 Hz orientation rate this is only a ~20–40 ms time constant —
     * enough to kill tremor without perceptible lag. Higher = smoother but laggier.
     */
    private static final double SCROLL_SMOOTHING = 0.6;

    static final class ButtonEvent {
        final @MouseButton int button;
        final boolean state;

        ButtonEvent(@MouseButton int b, boolean s) {
            button = b;
            state = s;
        }
    }

    /**
     * A list of button events that are pending and need to be sent. The oldest event is at the
     * front.
     */
    private final List<ButtonEvent> pendingEvents = new ArrayList<>();

    private final MouseDataSender dataSender;

    private double yaw;
    private double pitch;
    private double dYaw;
    private double dPitch;
    private double dWheel;

    /**
     * Whether this is the very first event we received after starting to listen or changing the
     * wrist mode.
     */
    private boolean firstRead;

    private boolean leftButtonPressed;
    private boolean rightButtonPressed;
    private boolean middleButtonPressed;
    private @HandMode int handMode;
    private boolean stabilize;
    private boolean lefty;
    /** When true, flip the grab-to-scroll direction (natural scrolling). */
    private boolean reverseScroll;
    /** When true, drop all sensor-driven output (motion, scroll, clicks) — a pointer "mute". */
    private boolean paused;

    /** When true, wrist motion scrolls the wheel instead of moving the cursor (grab-to-scroll). */
    private boolean scrollMode;
    /** Total wheel ticks emitted during the current grab, used to tell a tap from a scroll. */
    private double scrollAccum;
    /** Smoothed wheel ticks per frame; drives both the live scroll output and the inertia seed. */
    private double scrollVelocity;

    /** @param dataSender Interface to send Mouse data with. */
    MouseSensorListener(MouseDataSender dataSender) {
        this.dataSender = checkNotNull(dataSender);
    }

    @Override
    public void onOrientation(double[] quaternion) {
        if (paused) {
            // Muted: freeze the pointer. The next frame after resume re-seeds from rest (firstRead).
            return;
        }
        double q1 = quaternion[0]; // X * sin(T/2)
        double q2 = quaternion[1]; // Y * sin(T/2)
        double q3 = quaternion[2]; // Z * sin(T/2)
        double q0 = quaternion[3]; // cos(T/2)

        if (lefty) {
            // Rotate 180 degrees
            q1 = -q1;
            q2 = -q2;
        }

        if (handMode == HandMode.LEFT) {
            // Rotate 90 degrees counter-clockwise
            double x = q1;
            double y = q2;
            q1 = -y;
            q2 = x;
        } else if (handMode == HandMode.RIGHT) {
            // Rotate 90 degrees clockwise
            double x = q1;
            double y = q2;
            q1 = y;
            q2 = -x;
        } // else it's CENTER for which we do not need to rotate.

        double yaw = Math.atan2(2 * (q0 * q3 - q1 * q2), (1 - 2 * (q1 * q1 + q3 * q3)));
        double pitch = Math.asin(2 * (q0 * q1 + q2 * q3));
        // double roll = Math.atan2(2 * (q0 * q2 - q1 * q3), (1 - 2 * (q1 * q1 + q2 * q2)));

        if (Double.isNaN(yaw) || Double.isNaN(pitch)) {
            // NaN case, skip it
            return;
        }

        if (firstRead) {
            this.yaw = yaw;
            this.pitch = pitch;
            firstRead = false;
        } else {
            final double newYaw = highpass(this.yaw, yaw);
            final double newPitch = highpass(this.pitch, pitch);

            double dYaw = clamp(this.yaw - newYaw);
            double dPitch = this.pitch - newPitch;
            this.yaw = newYaw;
            this.pitch = newPitch;

            // Accumulate the error locally.
            this.dYaw += dYaw;
            this.dPitch += dPitch;
        }

        sendCurrentState();
    }

    /** Should be called in the controller's onCreate() method. */
    void onCreate() {
        pendingEvents.clear();
        firstRead = true;
        yaw = 0;
        pitch = 0;
        dYaw = 0;
        dPitch = 0;
    }

    /**
     * Enqueue a button press event.
     *
     * @param button Button index. Can be one of LEFT, RIGHT, MIDDLE.
     * @param state {@code true} if the button is pressed, {@code false} otherwise.
     */
    void sendButtonEvent(@MouseButton int button, boolean state) {
        if (paused) {
            return;
        }
        synchronized (pendingEvents) {
            pendingEvents.add(new ButtonEvent(button, state));
        }
    }

    /**
     * Adjust the enqueued Mouse data with an offset.
     *
     * @param x Extra displacement along X axis.
     * @param y Extra displacement along Y axis.
     * @param wheel Extra Wheel rotation.
     */
    void sendMouseMove(double x, double y, double wheel) {
        if (paused) {
            return;
        }
        dYaw += x / CURSOR_SPEED;
        dPitch += y / CURSOR_SPEED;
        dWheel += wheel;
    }

    /**
     * Enter or leave scroll-grab mode. While on, vertical wrist motion is converted to wheel scroll
     * and the cursor is frozen, as if the wrist had grabbed the page. Entering resets the per-grab
     * scroll meters.
     *
     * @param on {@code true} to start grabbing, {@code false} to release.
     */
    void setScrollMode(boolean on) {
        if (on && !scrollMode) {
            scrollAccum = 0;
            scrollVelocity = 0;
            // Drop any pending rotation so the grab starts from a clean slate (no cursor jump and no
            // stale pitch dumped into the first scroll frame).
            dYaw = 0;
            dPitch = 0;
        }
        scrollMode = on;
    }

    /** @return total wheel ticks scrolled since the current grab began (signed). */
    double getScrollAccum() {
        return scrollAccum;
    }

    /** @return smoothed wheel velocity (ticks/frame) at this moment, for seeding inertia. */
    double getScrollVelocity() {
        return scrollVelocity;
    }

    /**
     * Sets the current watch location: left/right wrist, or in the hand.
     *
     * @param hand Can be one of LEFT, CENTER, RIGHT.
     */
    void setHand(@HandMode int hand) {
        handMode = hand;
        firstRead = true;
    }

    /**
     * Set the pointer stabilization setting state.
     *
     * @param stabilize {@code true} if pointer stabilization is enabled, {@code false} otherwise.
     */
    void setStabilize(boolean stabilize) {
        this.stabilize = stabilize;
    }

    /**
     * Sets the "lefty" mode for the mouse data. This inverts all movements along Y axis.
     *
     * @param isLefty {@code true} if "lefty" mode is active, {@code false} if not.
     */
    void setLefty(boolean isLefty) {
        lefty = isLefty;
    }

    /**
     * Sets the grab-to-scroll direction.
     *
     * @param reverse {@code true} to flip the scroll direction (natural scrolling), {@code false}
     *     for the default where the wheel turns the way you'd push a physical wheel.
     */
    void setReverseScroll(boolean reverse) {
        reverseScroll = reverse;
    }

    /**
     * Pause or resume all sensor-driven output. While paused, wrist motion, grab-to-scroll and pinch
     * clicks are dropped so the wearer can type on the host without the pointer wandering. The HID
     * connection stays up, so resuming is instant. Entering pause discards any queued button events
     * and releases anything held (so a mute can't leave a button stuck down); the first orientation
     * after resume re-seeds from rest so the pointer doesn't jump.
     *
     * @param paused {@code true} to mute the pointer, {@code false} to resume.
     */
    void setPaused(boolean paused) {
        if (paused && !this.paused) {
            synchronized (pendingEvents) {
                pendingEvents.clear();
            }
            if (leftButtonPressed || rightButtonPressed || middleButtonPressed) {
                leftButtonPressed = false;
                rightButtonPressed = false;
                middleButtonPressed = false;
                dataSender.sendMouse(false, false, false, 0, 0, 0);
            }
            scrollMode = false;
        } else if (!paused && this.paused) {
            firstRead = true;
        }
        this.paused = paused;
    }

    /** @return whether sensor-driven output is currently muted. */
    boolean isPaused() {
        return paused;
    }

    private static double clamp(double val) {
        while (val <= -Math.PI) {
            val += 2 * Math.PI;
        }
        while (val >= Math.PI) {
            val -= 2 * Math.PI;
        }
        return val;
    }

    /**
     * Applies an adaptive high-pass filter if mStability is {@code true}. Otherwise simple returns
     * the new value.
     */
    private double highpass(double oldVal, double newVal) {
        if (!stabilize) {
            return newVal;
        }
        double delta = clamp(oldVal - newVal);
        double alpha =
                Math.max(0, 1 - Math.pow(Math.abs(delta) * CURSOR_SPEED / STABILIZE_BIAS, 3));
        return newVal + alpha * delta;
    }

    /**
     * Returns {@code true} if we couldn't send the full displacement in one go (if it didn't fit in
     * one byte), {@code false} otherwise.
     */
    private boolean sendCurrentState() {
        boolean overflow = false;

        if (scrollMode) {
            // Grab-to-scroll: vertical wrist motion drives the wheel, cursor is frozen. Tie the
            // scroll to the exact displacement the cursor would have moved (in pixels), then convert
            // to the coarse wheel unit, so it feels like the cursor is dragging the page. Negative
            // sign so tilting the wrist the way you'd drag the page scrolls it that way.
            double cursorPixels = dPitch * CURSOR_SPEED;
            // Default sign drags the page the way you tilt; reverseScroll flips it for natural
            // scrolling, where the wheel turns the opposite way.
            double direction = reverseScroll ? 1.0 : -1.0;
            double rawWheel = direction * cursorPixels / SCROLL_PIXELS_PER_TICK;
            // Low-pass the wheel rate so tremor and single-frame spikes don't become bursty single
            // ticks. The same smoothed value drives the live scroll and seeds the release inertia,
            // so the hand-off coasts seamlessly. It also gives each grab a soft start, since the
            // filter ramps up from zero (reset in setScrollMode).
            scrollVelocity = SCROLL_SMOOTHING * scrollVelocity + (1 - SCROLL_SMOOTHING) * rawWheel;
            double wheelDelta = scrollVelocity;
            dWheel += wheelDelta;
            scrollAccum += wheelDelta;
            // Consume the rotation so it neither moves the cursor nor piles up for later.
            dYaw = 0;
            dPitch = 0;
        }

        double dX = dYaw * CURSOR_SPEED;
        double dY = dPitch * CURSOR_SPEED;
        double dZ = dWheel;

        // Scale the shift down to fit the protocol.
        if (dX > 127) {
            dY *= 127.0 / dX;
            dX = 127;
            overflow = true;
        }
        if (dX < -127) {
            dY *= -127.0 / dX;
            dX = -127;
            overflow = true;
        }
        if (dY > 127) {
            dX *= 127.0 / dY;
            dY = 127;
            overflow = true;
        }
        if (dY < -127) {
            dX *= -127.0 / dY;
            dY = -127;
            overflow = true;
        }
        if (dZ > 127) {
            dZ = 127;
            overflow = true;
        }
        if (dZ < -127) {
            dZ = -127;
            overflow = true;
        }

        final byte x = (byte) Math.round(dX);
        final byte y = (byte) Math.round(dY);
        final byte z = (byte) Math.round(dZ);
        sendData(x, y, z);

        // Only subtract the part of the error that was already sent.
        if (x != 0) {
            dYaw -= x / CURSOR_SPEED;
        }
        if (y != 0) {
            dPitch -= y / CURSOR_SPEED;
        }
        if (z != 0) {
            dWheel -= z;
        }

        return overflow;
    }

    private void sendData(byte x, byte y, byte wheel) {
        synchronized (pendingEvents) {
            if (!pendingEvents.isEmpty()) {
                ButtonEvent event = pendingEvents.remove(0);
                if (event.button == MouseButton.LEFT) {
                    leftButtonPressed = event.state;
                } else if (event.button == MouseButton.RIGHT) {
                    rightButtonPressed = event.state;
                } else if (event.button == MouseButton.MIDDLE) {
                    middleButtonPressed = event.state;
                }
            }
        }

        dataSender.sendMouse(
                leftButtonPressed, rightButtonPressed, middleButtonPressed, x, y, wheel);
    }
}
