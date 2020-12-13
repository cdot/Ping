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

import android.os.Bundle;

import androidx.fragment.app.FragmentTransaction;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

import com.cdot.ping.android.IntListPreference;
import com.cdot.ping.samplers.Sample;

/**
 * Fragment to handle the display and manipulation of settings
 */
public class SettingsFragment extends PreferenceFragmentCompat {
    public static final String TAG = SettingsFragment.class.getSimpleName();

    // String used to separate the preference current value from the description in the summary
    private static final String VALUE_SEPARATOR = "\n";

    // Called from onCreate
    @Override // PreferenceFragmentCompat
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Settings prefs = new Settings(getActivity());
        // If the preference xml doesn't set max and min for a SeekBar, it defaults to 100. If we
        // have an existing value for the preference outside the range 0..100, it will be clipped
        // and the new value persisted. This will then override the setValue below. So we have
        // to make sure that SeekBar preferences have an android:max larger than the maximum we
        // set below.
        setPreferencesFromResource(R.xml.root_preferences, null);

        // Interface to SharedPreferences
        Preference vanilla;
        CheckBoxPreference checkbox;
        SeekBarPreference seekBar;
        IntListPreference intList;

        // All value changes should come through here. Preference value changes are all handled
        // in the MainActivity when the preference screen is navigated away from.
        // implement Preference.OnPreferenceChangeListener
        Preference.OnPreferenceChangeListener sul = (pref, newVal) -> {
            if (pref.getKey().equals(Settings.PREF_NOISE) || pref.getKey().equals(Settings.PREF_RANGE))
                newVal = Integer.valueOf((String) newVal);

            summarise(pref, newVal);

            // We've been handed a new val, but it isn't in the preferences yet.
            // Tell the MainActivity to update
            ((MainActivity) getActivity()).settingsChanged(pref.getKey(), newVal);

            // Update the slider
            return true;
        };

        vanilla = findPreference(Settings.PREF_DEVICE);
        assert vanilla != null;
        summarise(vanilla, prefs.getString(Settings.PREF_DEVICE));
        vanilla.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                FragmentTransaction tx = getParentFragmentManager().beginTransaction();
                tx.replace(R.id.fragmentContainerL, new DiscoveryFragment(false), DiscoveryFragment.TAG);
                tx.addToBackStack(null);
                tx.commit();
                return false;
            }
        });

        checkbox = findPreference(Settings.PREF_AUTOCONNECT);
        assert checkbox != null;
        checkbox.setChecked(prefs.getBoolean(Settings.PREF_AUTOCONNECT));

        int i = prefs.getInt(Settings.PREF_SENSITIVITY);
        seekBar = findPreference(Settings.PREF_SENSITIVITY);
        assert seekBar != null;
        seekBar.setOnPreferenceChangeListener(sul);
        seekBar.setMin(Settings.SENSITIVITY_MIN);
        seekBar.setMax(Settings.SENSITIVITY_MAX);
        seekBar.setValue(i);
        seekBar.setShowSeekBarValue(true);
        summarise(seekBar, i);

        i = prefs.getInt(Settings.PREF_NOISE);
        intList = findPreference(Settings.PREF_NOISE);
        assert intList != null;
        intList.setOnPreferenceChangeListener(sul);
        intList.setValue(Integer.toString(i));
        summarise(intList, i);

        i = prefs.getInt(Settings.PREF_RANGE);
        intList = findPreference(Settings.PREF_RANGE);
        assert intList != null;
        intList.setOnPreferenceChangeListener(sul);
        intList.setValue(Integer.toString(i));
        summarise(intList, i);

        i = prefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE);
        seekBar = findPreference(Settings.PREF_MIN_DEPTH_CHANGE);
        assert seekBar != null;
        seekBar.setOnPreferenceChangeListener(sul);
        seekBar.setMin(Settings.MIN_DEPTH_CHANGE_MIN);
        seekBar.setMax(Settings.MIN_DEPTH_CHANGE_MAX);
        seekBar.setValue(i);
        seekBar.setShowSeekBarValue(true);
        summarise(seekBar, i);

        i =  prefs.getInt(Settings.PREF_MIN_POS_CHANGE);
        seekBar = findPreference(Settings.PREF_MIN_POS_CHANGE);
        assert seekBar != null;
        seekBar.setOnPreferenceChangeListener(sul);
        seekBar.setMin(Settings.MIN_POS_CHANGE_MIN);
        seekBar.setMax(Settings.MIN_POS_CHANGE_MAX);
        seekBar.setValue(i);
        seekBar.setShowSeekBarValue(true);
        summarise(seekBar, i);

        i = prefs.getInt(Settings.PREF_MAX_SAMPLES);
        seekBar = findPreference(Settings.PREF_MAX_SAMPLES);
        assert seekBar != null;
        seekBar.setOnPreferenceChangeListener(sul);
        seekBar.setMin(Settings.MAX_SAMPLES_MIN);
        seekBar.setMax(Settings.MAX_SAMPLES_MAX);
        seekBar.setValue(i);
        seekBar.setShowSeekBarValue(true);
        summarise(seekBar, i);
    }

    // Fragment lifecycle
    // see https://developer.android.com/guide/fragments/lifecycle

    /**
     * Format the text value of a preference for presentation in the summary
     *
     * @param key preference name
     * @param val value of the preference
     * @return string representation of the value of the preference
     */
    private String formatPreference(String key, Object val) {
        if (val == null)
            return "null";
        switch (key) {
            case Settings.PREF_NOISE:
                return getResources().getStringArray(R.array.noise_options)[(int) val];
            case Settings.PREF_RANGE:
                return getResources().getStringArray(R.array.range_options)[(int) val];
            case Settings.PREF_MIN_DEPTH_CHANGE:
            case Settings.PREF_MIN_POS_CHANGE:
                return Double.toString((int) val / 1000.0); // convert mm to metres
            case Settings.PREF_MAX_SAMPLES:
                int size = (int)val;
                float bytes = size * Sample.BYTES;
                if (bytes < Settings.MEGABYTE)
                    return String.format("%d (%.02fKb)", size, bytes / Settings.KILOBYTE);
                if (bytes < Settings.GIGABYTE)
                    return String.format("%d (%.02fMb)", size, bytes / Settings.MEGABYTE);
                return String.format("%d (%.02fGb)", size, bytes / Settings.GIGABYTE);
            default:
                return val.toString();
        }
    }

    private void summarise(Preference pref, Object val) {
        String s = pref.getSummary().toString();

        int end = s.indexOf(VALUE_SEPARATOR);
        if (end >= 0) s = s.substring(end + VALUE_SEPARATOR.length());
        pref.setSummary(formatPreference(pref.getKey(), val) + VALUE_SEPARATOR + s);
    }
}
