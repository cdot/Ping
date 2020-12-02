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
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.HashMap;

/**
 * Because of limitations with the Android Preference resources - specifically, ListPreference
 * assumes the value is always a String - it's simpler if all preferences are stored as Strings
 * to simplify conversion. This class is used to interface to SharePreferences and read/write
 * preferences as their expected types. It also carries the default values and range limits
 * for all preferences.
 */
public class Settings {

    private static final String TAG = Settings.class.getSimpleName();

    // Sampling preferences

    // Minimum depth change (metres) before a new depth sample is recorded
    public static final String PREF_MIN_DEPTH_CHANGE = "minimumDepthChange";
    public static final int MIN_DEPTH_CHANGE_MIN = 100; // mm
    public static final int MIN_DEPTH_CHANGE_MAX = 5000; // mm

    // String representation of file URIs
    public static final String PREF_SAMPLE_FILE = "sampleFile";

    // Configuration preferences reverse-engineered by sniffing packets sent to sonar device

    // Intensity of the sonar pulse. When the water is shallow, or there is noise in the water
    // such as engine noise, select a lower sensitivity
    // percent/10 i.e. 0..10
    public static final String PREF_SENSITIVITY = "sensitivity";
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

    public static final String PREF_DEVICE = "device";

    public static final String PREF_AUTOCONNECT = "autoconnect";

    // Default values for integer preferences
    private static final HashMap<String, Integer> intDefaults = new HashMap<String, Integer>() {
        {
            put(PREF_SENSITIVITY, 5);
            put(PREF_RANGE, RANGE_AUTO);
            put(PREF_NOISE, NOISE_OFF);
            put(PREF_MIN_DEPTH_CHANGE, 250); // mm
        }
    };

    // Default values for integer preferences
    private static final HashMap<String, Boolean> booleanDefaults = new HashMap<String, Boolean>() {
        {
            put(PREF_AUTOCONNECT, true);
        }
    };

    // Handle to shared preferences
    private SharedPreferences mPrefs;

    Settings(Context cxt) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(cxt);
    }

    public void clear() {
        Log.d(TAG, "Clearing all preference values");
        SharedPreferences.Editor ed = mPrefs.edit();
        ed.remove(PREF_SENSITIVITY);
        ed.remove(PREF_NOISE);
        ed.remove(PREF_RANGE);
        ed.remove(PREF_MIN_DEPTH_CHANGE);
        ed.remove(PREF_SAMPLE_FILE);
        ed.remove(PREF_AUTOCONNECT);
        ed.commit();
    }

    /**
     * Get the current value of an int preference.
     * @param pref key
     * @return value of preference
     */
    int getInt(String pref) {
        try {
            return mPrefs.getInt(pref, intDefaults.get(pref));
        } catch (ClassCastException cce) {
            mPrefs.edit().remove(pref).apply();
            return intDefaults.get(pref);
        }
    }

    boolean getBoolean(String pref) {
        try {
            return mPrefs.getBoolean(pref, booleanDefaults.get(pref));
        } catch (ClassCastException cce) {
            mPrefs.edit().remove(pref).apply();
            return booleanDefaults.get(pref);
        }
    }

    /**
     * Get the current value of a String preference. These all default to null
     * @param pref key to fetch
     * @return pref value or null default
     */
    String getString(String pref) {
        return mPrefs.getString(pref, null);
    }

    /**
     * Get the current value of a String preference. These all default to null
     * @param pref key to fetch
     * @param def default value
     * @return pref value or null default
     */
    String getString(String pref, String def) {
        return mPrefs.getString(pref, def);
    }

    /**
     * Set the value of a boolean preference
     * @param key pref key
     * @param value new value
     */
    void put(String key, boolean value) {
        SharedPreferences.Editor edit = mPrefs.edit();
        edit.putBoolean(key, value);
        edit.apply();
    }

    /**
     * Set the value of an int preference
     * @param key pref key
     * @param value new value
     */
    void put(String key, int value) {
        SharedPreferences.Editor edit = mPrefs.edit();
        edit.putInt(key, value);
        edit.apply();
    }

    /**
     * Set the value of a string preference
     * @param key pref key
     * @param value new value
     */
    void put(String key, String value) {
        SharedPreferences.Editor edit = mPrefs.edit();
        edit.putString(key, value);
        edit.apply();
    }

    // Stub
    void registerOnSharedPreferenceChangeListener(SharedPreferences.OnSharedPreferenceChangeListener ear) {
        mPrefs.registerOnSharedPreferenceChangeListener(ear);
    }
}
