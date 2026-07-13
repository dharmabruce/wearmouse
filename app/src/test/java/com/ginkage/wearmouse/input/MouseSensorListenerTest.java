/*
 * Copyright 2018 Google LLC All Rights Reserved.
 *
 * Modified 2025-2026 by the WearMouse fork contributors: added this test.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ginkage.wearmouse.bluetooth.MouseReport.MouseDataSender;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for the fork's button/pause plumbing in {@link MouseSensorListener}. The class
 * is deliberately Android-free — it operates on plain {@code double[]} quaternions and a {@link
 * MouseDataSender} interface — so these run as plain JVM unit tests with no Robolectric.
 *
 * <p>Each test targets one of the three must-fix bugs the fork fixed: button events must reach the
 * host, {@link MouseSensorListener#flush()} must release a held button when frames are about to
 * stop, and pause must both release held buttons and drop input while muted.
 */
public class MouseSensorListenerTest {

    /** Records the most recent {@code sendMouse} report and how many were sent. */
    private static final class FakeSender implements MouseDataSender {
        boolean left;
        boolean right;
        boolean middle;
        int dX;
        int dY;
        int dWheel;
        int calls;

        @Override
        public void sendMouse(boolean left, boolean right, boolean middle, int dX, int dY,
                int dWheel) {
            this.left = left;
            this.right = right;
            this.middle = middle;
            this.dX = dX;
            this.dY = dY;
            this.dWheel = dWheel;
            this.calls++;
        }
    }

    // Identity orientation (no rotation): drives a frame that reports no motion but still drains one
    // queued button event via sendCurrentState().
    private static final double[] IDENTITY = {0.0, 0.0, 0.0, 1.0};

    private FakeSender sender;
    private MouseSensorListener listener;

    @Before
    public void setUp() {
        sender = new FakeSender();
        listener = new MouseSensorListener(sender);
    }

    /** A queued button press is delivered to the host on the next orientation frame. */
    @Test
    public void buttonPress_reachesHostOnNextFrame() {
        listener.sendButtonEvent(MouseSensorListener.MouseButton.LEFT, true);
        listener.onOrientation(IDENTITY);

        assertTrue("left button should be pressed after a frame drains the queue", sender.left);
    }

    /** flush() releases a held button immediately, without waiting for another frame. */
    @Test
    public void flush_releasesHeldButton() {
        listener.sendButtonEvent(MouseSensorListener.MouseButton.LEFT, true);
        listener.onOrientation(IDENTITY);
        assertTrue(sender.left);

        listener.flush();

        assertFalse("flush must release the held left button", sender.left);
        assertFalse(sender.right);
        assertFalse(sender.middle);
    }

    /** Pausing releases held buttons and then mutes further frames (no more reports). */
    @Test
    public void pause_releasesHeldButtonsThenMutesFrames() {
        listener.sendButtonEvent(MouseSensorListener.MouseButton.LEFT, true);
        listener.onOrientation(IDENTITY);
        assertTrue(sender.left);

        listener.setPaused(true);
        assertTrue(listener.isPaused());
        assertFalse("pausing must release the held button", sender.left);

        int callsAfterPause = sender.calls;
        listener.onOrientation(IDENTITY);
        assertEquals("a paused listener must not send any report", callsAfterPause, sender.calls);
    }

    /** Button events that arrive while paused are dropped, not replayed after resume. */
    @Test
    public void buttonEvent_whilePaused_isDropped() {
        listener.setPaused(true);
        listener.sendButtonEvent(MouseSensorListener.MouseButton.LEFT, true);
        listener.setPaused(false);

        listener.onOrientation(IDENTITY);

        assertFalse("an event enqueued while paused must not click after resume", sender.left);
    }
}
