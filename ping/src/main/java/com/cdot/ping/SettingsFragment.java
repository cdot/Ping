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

    @Override // PreferenceFragmentCompat
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        // Interface to SharedPreferences
        Settings mPrefs = new Settings(getActivity());
        Preference vanilla;
        CheckBoxPreference checkbox;
        SeekBarPreference seekBar;
        IntListPreference intList;

        vanilla = findPreference(Settings.PREF_DEVICE);
        assert vanilla != null;
        summarise(vanilla, mPrefs.getString(Settings.PREF_DEVICE));
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
        checkbox.setChecked(mPrefs.getBoolean(Settings.PREF_AUTOCONNECT));

        seekBar = findPreference(Settings.PREF_SENSITIVITY);
        assert seekBar != null;
        seekBar.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(seekBar, mPrefs.getInt(Settings.PREF_SENSITIVITY));
        seekBar.setMin(Settings.SENSITIVITY_MIN);
        seekBar.setMax(Settings.SENSITIVITY_MAX);
        seekBar.setValue(mPrefs.getInt(Settings.PREF_SENSITIVITY));

        intList = findPreference(Settings.PREF_NOISE);
        assert intList != null;
        intList.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(intList, mPrefs.getInt(Settings.PREF_NOISE));
        intList.setValue(Integer.toString(mPrefs.getInt(Settings.PREF_NOISE)));

        intList = findPreference(Settings.PREF_RANGE);
        assert intList != null;
        intList.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(intList, mPrefs.getInt(Settings.PREF_RANGE));
        intList.setValue(Integer.toString(mPrefs.getInt(Settings.PREF_RANGE)));

        seekBar = findPreference(Settings.PREF_MIN_DEPTH_CHANGE);
        assert seekBar != null;
        seekBar.setOnPreferenceChangeListener(new SummaryUpdateListener());
        seekBar.setMin(Settings.MIN_DEPTH_CHANGE_MIN);
        seekBar.setMax(Settings.MIN_DEPTH_CHANGE_MAX);
        summarise(seekBar, mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE));
        seekBar.setValue(mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE));

        seekBar = findPreference(Settings.PREF_MIN_POS_CHANGE);
        assert seekBar != null;
        seekBar.setOnPreferenceChangeListener(new SummaryUpdateListener());
        seekBar.setMin(Settings.MIN_POS_CHANGE_MIN);
        seekBar.setMax(Settings.MIN_POS_CHANGE_MAX);
        summarise(seekBar, mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE));
        seekBar.setValue(mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE));

        seekBar = findPreference(Settings.PREF_MAX_SAMPLES);
        assert seekBar != null;
        seekBar.setOnPreferenceChangeListener(new SummaryUpdateListener());
        seekBar.setMin(Settings.MAX_SAMPLES_MIN);
        seekBar.setMax(Settings.MAX_SAMPLES_MAX);
        summarise(seekBar, mPrefs.getInt(Settings.PREF_MAX_SAMPLES));
        seekBar.setValue(mPrefs.getInt(Settings.PREF_MAX_SAMPLES));

        // Special handling for the sample file preferences, to bring up a dialog that will
        // test-create the file.

        /*vanilla = findPreference(Settings.PREF_GPX_FILE);
        assert vanilla != null;
        vanilla.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(vanilla, mPrefs.getString(Settings.PREF_GPX_FILE));
        vanilla.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference p) {
                ((MainActivity) getActivity()).getFile(Settings.PREF_GPX_FILE, R.string.pref_sample_file);
                return true;
            }
        });*/
    }

    // Fragment lifecycle
    // see https://developer.android.com/guide/fragments/lifecycle

    private static final float KILOBYTE = 1024.0f;
    private static final float MEGABYTE = KILOBYTE * KILOBYTE;
    private static final float GIGABYTE = MEGABYTE * KILOBYTE;

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
                int bytes = size * Sample.BYTES;
                if (size < MEGABYTE)
                    return String.format("%d (%.02fKb)", size, bytes / KILOBYTE);
                if (size < GIGABYTE)
                    return String.format("%d (%.02fMb)", size, bytes / MEGABYTE);
                return String.format("%d (%.02fGb)", size, bytes / GIGABYTE);
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

    // All value changes should come through here. Preference value changes are all handled
    // in the MainActivity when the preference screen is navigated away from.
    private class SummaryUpdateListener implements Preference.OnPreferenceChangeListener {
        @Override // implement Preference.OnPreferenceChangeListener
        public boolean onPreferenceChange(Preference pref, Object newVal) {
            if (pref.getKey().equals(Settings.PREF_NOISE) || pref.getKey().equals(Settings.PREF_RANGE))
                newVal = Integer.valueOf((String) newVal);

            summarise(pref, newVal);

            // We've been handed a new val, but it isn't in the preferences yet.
            // Tell the MainActivity to update
            ((MainActivity) getActivity()).settingsChanged(pref.getKey(), newVal);
            return true;
        }
    }
}
