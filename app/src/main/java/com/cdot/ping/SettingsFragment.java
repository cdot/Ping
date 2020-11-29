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

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.fragment.app.FragmentTransaction;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

/**
 * Fragment to handle the display and manipulation of settings
 */
public class SettingsFragment extends PreferenceFragmentCompat {
    public static final String TAG = SettingsFragment.class.getSimpleName();

    // String used to separate the preference current value from the description in the summary
    private static String VALUE_SEPARATOR = "\n";

    // Interface to SharedPreferences
    private Settings mPrefs;

    // All value changes should come through here. Preference value changes are all handled
    // in the MainActivity when the preference screen is navigated away from.
    private class SummaryUpdateListener implements Preference.OnPreferenceChangeListener {
        @Override // implement Preference.OnPreferenceChangeListener
        public boolean onPreferenceChange(Preference pref, Object newVal) {
            if (pref.getKey().equals(Settings.PREF_NOISE) || pref.getKey().equals(Settings.PREF_RANGE))
                newVal = Integer.valueOf((String)newVal);

            summarise(pref, newVal);

            // We've been handed a new val, but it isn't in the preferences yet.
            // Tell the MainActivity to update
            ((MainActivity) getActivity()).settingsChanged(pref.getKey(), newVal);
            return true;
        }
    }

    // Fragment lifecycle
    // see https://developer.android.com/guide/fragments/lifecycle

    @Override // PreferenceFragmentCompat
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        mPrefs = new Settings(getActivity());

        CheckBoxPreference cbPref = findPreference(Settings.PREF_AUTOCONNECT);
        assert cbPref != null;
        cbPref.setChecked(mPrefs.getBoolean(Settings.PREF_AUTOCONNECT));

        SeekBarPreference seekPref = findPreference(Settings.PREF_SENSITIVITY);
        assert seekPref != null;
        seekPref.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(seekPref, mPrefs.getInt(Settings.PREF_SENSITIVITY));
        seekPref.setMin(Settings.SENSITIVITY_MIN);
        seekPref.setMax(Settings.SENSITIVITY_MAX);
        seekPref.setValue(mPrefs.getInt(Settings.PREF_SENSITIVITY));

        seekPref = findPreference(Settings.PREF_MIN_POS_CHANGE);
        assert seekPref != null;
        seekPref.setOnPreferenceChangeListener(new SummaryUpdateListener());
        seekPref.setMin(Settings.MIN_POS_CHANGE_MIN);
        seekPref.setMax(Settings.MIN_POS_CHANGE_MAX);
        summarise(seekPref, mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE));
        seekPref.setValue(mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE));

        seekPref = findPreference(Settings.PREF_MIN_DEPTH_CHANGE);
        assert seekPref != null;
        seekPref.setOnPreferenceChangeListener(new SummaryUpdateListener());
        seekPref.setMin(Settings.MIN_DEPTH_CHANGE_MIN);
        seekPref.setMax(Settings.MIN_DEPTH_CHANGE_MAX);
        summarise(seekPref, mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE));
        seekPref.setValue(mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE));

        IntListPreference ilPref = findPreference(Settings.PREF_RANGE);
        assert ilPref != null;
        ilPref.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(ilPref, mPrefs.getInt(Settings.PREF_RANGE));
        ilPref.setValue(Integer.toString(mPrefs.getInt(Settings.PREF_RANGE)));

        ilPref = findPreference(Settings.PREF_NOISE);
        assert ilPref != null;
        ilPref.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(ilPref, mPrefs.getInt(Settings.PREF_NOISE));
        ilPref.setValue(Integer.toString(mPrefs.getInt(Settings.PREF_NOISE)));

        // Special handling for the sample file preferences, to bring up a dialog that will
        // test-create the file.
        Preference poof = findPreference(Settings.PREF_SONAR_SAMPLE_FILE);
        assert poof != null;
        poof.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(poof, mPrefs.getString(Settings.PREF_SONAR_SAMPLE_FILE));

        poof.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference p) {
                ((MainActivity)getActivity()).getFile(Settings.PREF_SONAR_SAMPLE_FILE, R.string.sonarSampleFile);
                return true;
            }
        });

        poof = findPreference(Settings.PREF_LOCATION_SAMPLE_FILE);
        assert poof != null;
        poof.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(poof, mPrefs.getString(Settings.PREF_LOCATION_SAMPLE_FILE));
        poof.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference p) {
                ((MainActivity)getActivity()).getFile(Settings.PREF_LOCATION_SAMPLE_FILE, R.string.locationSampleFile);
                return true;
            }
        });

        poof = findPreference(Settings.PREF_DEVICE);
        assert poof != null;
        summarise(poof, mPrefs.getString(Settings.PREF_DEVICE));
        poof.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                FragmentTransaction tx = getParentFragmentManager().beginTransaction();
                tx.replace(R.id.fragment_container, new DiscoveryFragment(false), DiscoveryFragment.TAG);
                tx.addToBackStack(null);
                tx.commit();
                return false;
            }
        });
    }

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

    /**
     * Called from MainActivity to handle a file selection
     * @param prefName preference being set
     * @param uri value
     */
    void onFileSelected(String prefName, Uri uri) {
        SettingsFragment.SummaryUpdateListener vcl = new SettingsFragment.SummaryUpdateListener();
        Preference pref = findPreference(prefName);
        assert pref != null;
        Log.d(TAG, "setting " + prefName);
        vcl.onPreferenceChange(pref, uri.toString());
    }
}
