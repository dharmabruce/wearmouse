/*
 * Copyright 2018 Google LLC All Rights Reserved.
 *
 * Modified 2025-2026 by the WearMouse fork contributors: added IMU
 * tap-to-click, grab-to-scroll, and wrist-flip Back/Home gestures
 * (see git history for details).
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
import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import androidx.annotation.StringDef;
import com.ginkage.wearmouse.input.MouseSensorListener.HandMode;
import com.google.common.collect.ImmutableMap;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;

/** Helper class to wrap various Settings. */
public class SettingsUtil {

    private static final String SETTINGS_PREF = "com.ginkage.wearmouse.SETTINGS";

    /** Prefix for the per-device scroll-direction override, suffixed with the BT device address. */
    private static final String REVERSE_SCROLL_DEVICE_PREFIX = "pref_settingReverseScroll_device_";

    /** These constants correspond to the various preferences in the Settings menu. */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
        SettingKey.CALIBRATION,
        SettingKey.MOUSE_HAND,
        SettingKey.CURSOR_8_WAY,
        SettingKey.REDUCED_RATE,
        SettingKey.STABILIZE,
        SettingKey.STAY_CONNECTED,
        SettingKey.TAP_TO_CLICK,
        SettingKey.REVERSE_SCROLL,
        SettingKey.FLIP_GESTURES
    })
    public @interface SettingKey {
        String CALIBRATION = "pref_settingCalibration";
        String MOUSE_HAND = "pref_settingMouseHand";
        String CURSOR_8_WAY = "pref_settingCursor8Way";
        String REDUCED_RATE = "pref_settingReducedRate";
        String STABILIZE = "pref_settingStabilize";
        String STAY_CONNECTED = "pref_settingStayConnected";
        String TAP_TO_CLICK = "pref_settingTapToClick";
        String REVERSE_SCROLL = "pref_settingReverseScroll";
        String FLIP_GESTURES = "pref_settingFlipGestures";
    }

    private static final Map<String, Boolean> defaults =
            new ImmutableMap.Builder<String, Boolean>()
                    .put(SettingKey.CALIBRATION, false)
                    .put(SettingKey.CURSOR_8_WAY, false)
                    .put(SettingKey.REDUCED_RATE, false)
                    .put(SettingKey.STABILIZE, false)
                    .put(SettingKey.STAY_CONNECTED, false)
                    .put(SettingKey.TAP_TO_CLICK, true)
                    .put(SettingKey.REVERSE_SCROLL, false)
                    .put(SettingKey.FLIP_GESTURES, true)
                    .build();

    private final SharedPreferences sharedPref;

    /** @param context The context to retrieve shared preferences with. */
    public SettingsUtil(Context context) {
        context = checkNotNull(context).getApplicationContext();
        sharedPref = context.getSharedPreferences(SETTINGS_PREF, Context.MODE_PRIVATE);
    }

    /**
     * Get the last used watch location.
     *
     * @return Watch location (left/right wrist, or held in hand).
     * @see MouseSensorListener
     */
    public @HandMode int getMouseHand() {
        return sharedPref.getInt(SettingKey.MOUSE_HAND, 0);
    }

    /**
     * Save the current watch location.
     *
     * @param hand Watch location (left/right wrist, or held in hand).
     * @see MouseSensorListener
     */
    public void putMouseHand(@HandMode int hand) {
        sharedPref.edit().putInt(SettingKey.MOUSE_HAND, hand).apply();
    }

    /**
     * Gets the boolean value that corresponds to the specified key.
     *
     * @param key Key in the values map.
     * @return Value that corresponds to the key.
     */
    public boolean getBoolean(@SettingKey String key) {
        return sharedPref.getBoolean(key, defaults.get(key));
    }

    /**
     * Saves the boolean value that corresponds to the specified key.
     *
     * @param key Key in the values map.
     * @param enabled Value that corresponds to the key.
     */
    public void setBoolean(@SettingKey String key, boolean enabled) {
        sharedPref.edit().putBoolean(key, enabled).apply();
    }

    /**
     * Get the scroll-direction setting for a specific host. Each device remembers its own choice
     * (some screens scroll the natural way, some inverted); the global {@link
     * SettingKey#REVERSE_SCROLL} value is the default applied to any device not yet configured.
     *
     * @param deviceAddress BT address of the connected host, or {@code null} when none is connected.
     * @return {@code true} if scrolling should be reversed for this device.
     */
    public boolean getReverseScroll(@Nullable String deviceAddress) {
        if (deviceAddress != null) {
            String key = REVERSE_SCROLL_DEVICE_PREFIX + deviceAddress;
            if (sharedPref.contains(key)) {
                return sharedPref.getBoolean(key, false);
            }
        }
        return getBoolean(SettingKey.REVERSE_SCROLL);
    }

    /**
     * Save the scroll-direction setting for a specific host, remembered per device. With no device
     * (address {@code null}) the value is written to the global default instead.
     *
     * @param deviceAddress BT address of the connected host, or {@code null} when none is connected.
     * @param reverse {@code true} to reverse scrolling for this device.
     */
    public void setReverseScroll(@Nullable String deviceAddress, boolean reverse) {
        if (deviceAddress != null) {
            sharedPref
                    .edit()
                    .putBoolean(REVERSE_SCROLL_DEVICE_PREFIX + deviceAddress, reverse)
                    .apply();
        } else {
            setBoolean(SettingKey.REVERSE_SCROLL, reverse);
        }
    }
}
