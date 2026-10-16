/*
 * Copyright 2026 Sunil Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://apache.org
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



package camera.pixelcraft.lumina;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores the only setting the user really needs to choose: the resolution.
 * Frame rate, codec, bitrate and anti-flicker are picked automatically for each phone
 * (see {@link CameraCapabilities}).
 */
public class SettingsManager {
    public static final int DEFAULT_RESOLUTION_WIDTH = 1440; // 1440x1080, the phone picks the closest it supports

    private static final String PREF_NAME = "VideoRecorderPrefs";
    private static final String KEY_RES_WIDTH = "res_width";

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void setResolutionWidth(int width) {
        prefs.edit().putInt(KEY_RES_WIDTH, width).apply();
    }

    public int getResolutionWidth() {
        return prefs.getInt(KEY_RES_WIDTH, DEFAULT_RESOLUTION_WIDTH);
    }
}
