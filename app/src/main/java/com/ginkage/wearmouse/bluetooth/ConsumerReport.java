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

package com.ginkage.wearmouse.bluetooth;

import java.util.Arrays;

/**
 * Helper class to store the Consumer Control state and retrieve the binary report. Carries the two
 * Android system-navigation usages (AC Home, AC Back) as a one-byte bitmap; the host maps them to
 * {@code KEYCODE_HOME} and {@code KEYCODE_BACK}.
 */
public class ConsumerReport {

    private final byte[] consumerData = "C".getBytes();

    ConsumerReport() {
        Arrays.fill(consumerData, (byte) 0);
    }

    byte[] setValue(boolean home, boolean back) {
        int bits = (home ? 1 : 0) | (back ? 2 : 0);
        consumerData[0] = (byte) bits;
        return consumerData;
    }

    byte[] getReport() {
        return consumerData;
    }

    /** Interface to send the Consumer Control data with. */
    public interface ConsumerDataSender {
        /**
         * Send a Consumer Control report to the connected HID Host device. Pass {@code true} to hold
         * a usage down; send an all-{@code false} report to release. A tap is a down followed by a
         * release.
         *
         * @param home {@code true} while the AC Home usage is pressed.
         * @param back {@code true} while the AC Back usage is pressed.
         */
        void sendConsumer(boolean home, boolean back);
    }
}
