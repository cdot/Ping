/*
 * Copyright © 2020 C-Dot Consultants
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.cdot.ping;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.cdot.ping.samplers.Sample;

import java.util.HashMap;

/**
 * This class is used to wrap to SharedPreferences and read/write
 * preferences as their expected types. It also carries the default values and range limits
 * for all preferences.
 */
public class Settings {
    public static final int KILOBYTE = 1024;
    public static final int MEGABYTE = KILOBYTE * KILOBYTE;
    public static final int GIGABYTE = MEGABYTE * KILOBYTE;
    // Minimum depth change (metres) before a new depth sample is recorded
    public static final String PREF_MIN_DEPTH_CHANGE = "minimumDepthChange";
    // Sampling preferences
    public static final int MIN_DEPTH_CHANGE_MIN = 100; // mm
    public static final int MIN_DEPTH_CHANGE_MAX = 3000; // mm
    public static final String PREF_MIN_POS_CHANGE = "minimumPositionChange";
    public static final int MIN_POS_CHANGE_MIN = 100; // mm
    public static final int MIN_POS_CHANGE_MAX = 3000; // mm
    // Intensity of the sonar pulse. When the water is shallow, or there is noise in the water
    // such as engine noise, select a lower sensitivity
    // percent/10 i.e. 0..10
    public static final String PREF_SENSITIVITY = "sensitivity";
    // Configuration preferences reverse-engineered by sniffing packets sent to sonar device
    public static final int SENSITIVITY_MIN = 1;
    public static final int SENSITIVITY_MAX = 10;
    // Sonar anticipated range, set as close to anticipated depth as possible
    public static final String PREF_RANGE = "range"; // RANGE_* value, index into RANGE_DEPTH
    public static final int RANGE_3M = 0;
    public static final int RANGE_6M = 1;
    public static final int RANGE_9M = 2;
    public static final int RANGE_18M = 3;
    public static final int RANGE_24M = 4;
    public static final int RANGE_36M = 5;
    public static final int RANGE_AUTO = 6;
    // noise filtering is for turbid water or where there are suspended solids
    public static final String PREF_NOISE = "noise"; // NOISE_*, index into
    public static final int NOISE_OFF = 0;
    public static final int NOISE_LOW = 1;
    public static final int NOISE_MEDIUM = 2;
    public static final int NOISE_HIGH = 3;
    public static final String PREF_MAX_SAMPLES = "maxSamples";
    public static final int MAX_SAMPLES_MIN = 500 * 1024 / Sample.BYTES; // 500Kb file
    public static final int MAX_SAMPLES_MAX = 1024 * 1024 * 1024 / Sample.BYTES; // 1Gb file
    public static final String PREF_SAMPLER_TIMEOUT = "samplerTimeout";
    public static final int SAMPLER_TIMEOUT_MIN = 0; // 0 means "never"
    public static final int SAMPLER_TIMEOUT_MAX = 10 * 60 * 1000; // 10 minutes in ms
    public static final String PREF_DEVICE = "device";
    public static final String PREF_AUTOCONNECT = "autoconnect";
    public static final String PREF_ZOOM_LEVEL = "zoom";
    static final int[] RANGES = new int[]{3, 6, 9, 18, 24, 36, 36};
    private static final String TAG = Settings.class.getSimpleName();
    // Default values for integer preferences
    private static final HashMap<String, Integer> intDefaults = new HashMap<String, Integer>() {
        {
            put(PREF_SENSITIVITY, 5);
            put(PREF_RANGE, RANGE_AUTO);
            put(PREF_NOISE, NOISE_OFF);
            put(PREF_MIN_DEPTH_CHANGE, 250); // mm
            put(PREF_MIN_POS_CHANGE, 500); // mm
            put(PREF_MAX_SAMPLES, 10 * MEGABYTE / Sample.BYTES); // 10Mb
            put(PREF_SAMPLER_TIMEOUT, 0); // never
        }
    };
    // Default values for integer preferences
    private static final HashMap<String, Boolean> booleanDefaults = new HashMap<String, Boolean>() {
        {
            put(PREF_AUTOCONNECT, true);
        }
    };
    // Default values for float preferences
    private static final HashMap<String, Float> floatDefaults = new HashMap<String, Float>() {
        {
            put(PREF_ZOOM_LEVEL, 1.0f);
        }
    };
    // Handle to shared preferences
    private final SharedPreferences mPrefs;

    public Settings(Context cxt) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(cxt);
        /*DEBUG
        mPrefs.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener() {

            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
                Settings prefs = new Settings(cxt);
                Log.d(TAG, "CHANGE" + s);
                }
            }
        });*/
    }

    /**
     * Get the current value of an int preference.
     *
     * @param pref key
     * @return value of preference
     */
    public int getInt(String pref) {
        try {
            return mPrefs.getInt(pref, intDefaults.get(pref));
        } catch (ClassCastException cce) {
            mPrefs.edit().remove(pref).apply();
            return intDefaults.get(pref);
        }
    }

    /**
     * Get the current value of a float preference.
     *
     * @param pref key
     * @return value of preference
     */
    public float getFloat(String pref) {
        try {
            return mPrefs.getFloat(pref, floatDefaults.get(pref));
        } catch (ClassCastException cce) {
            mPrefs.edit().remove(pref).apply();
            return floatDefaults.get(pref);
        }
    }

    /**
     * Get the current value of a preference.
     *
     * @param pref key to fetch
     * @return pref value or default
     */
    public boolean getBoolean(String pref) {
        try {
            return mPrefs.getBoolean(pref, booleanDefaults.get(pref));
        } catch (ClassCastException cce) {
            mPrefs.edit().remove(pref).apply();
            return booleanDefaults.get(pref);
        }
    }

    /**
     * Get the current value of a String preference. These all default to null
     *
     * @param pref key to fetch
     * @return pref value or null default
     */
    public String getString(String pref) {
        return mPrefs.getString(pref, null);
    }

    /**
     * Get the current value of a String preference. These all default to null
     *
     * @param pref key to fetch
     * @param def  default value
     * @return pref value or null default
     */
    public String getString(String pref, String def) {
        return mPrefs.getString(pref, def);
    }

    /**
     * Set the value of a boolean preference
     *
     * @param key   pref key
     * @param value new value
     */
    public void put(String key, boolean value) {
        SharedPreferences.Editor edit = mPrefs.edit();
        edit.putBoolean(key, value);
        edit.apply();
    }

    /**
     * Set the value of an int preference
     *
     * @param key   pref key
     * @param value new value
     */
    public void put(String key, int value) {
        SharedPreferences.Editor edit = mPrefs.edit();
        edit.putInt(key, value);
        edit.apply();
    }

    /**
     * Set the value of a float preference
     *
     * @param key   pref key
     * @param value new value
     */
    public void put(String key, float value) {
        SharedPreferences.Editor edit = mPrefs.edit();
        edit.putFloat(key, value);
        edit.apply();
    }

    /**
     * Set the value of a string preference
     *
     * @param key   pref key
     * @param value new value
     */
    public void put(String key, String value) {
        SharedPreferences.Editor edit = mPrefs.edit();
        edit.putString(key, value);
        edit.apply();
    }

    // Stub
    public void registerOnSharedPreferenceChangeListener(SharedPreferences.OnSharedPreferenceChangeListener ear) {
        mPrefs.registerOnSharedPreferenceChangeListener(ear);
    }
}
