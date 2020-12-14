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

import com.cdot.ping.android.IntListPreference;
import com.cdot.ping.android.SliderPreference;
import com.cdot.ping.samplers.Sample;

/**
 * Fragment to handle the display and manipulation of settings
 */
public class SettingsFragment extends PreferenceFragmentCompat {
    public static final String TAG = SettingsFragment.class.getSimpleName();

    private MainActivity getMainActivity() {
        return ((MainActivity) getActivity());
    }

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
        SliderPreference slider;
        IntListPreference intList;

        // All value changes should come through here. Preference value changes are all handled
        // in the MainActivity when the preference screen is navigated away from.
        // implement Preference.OnPreferenceChangeListener
        Preference.OnPreferenceChangeListener sul = (pref, newVal) -> {
            if (pref.getKey().equals(Settings.PREF_NOISE) || pref.getKey().equals(Settings.PREF_RANGE))
                newVal = Integer.valueOf((String) newVal);
            getMainActivity().onSettingChanged(pref.getKey(), newVal);
            return true;
        };

        vanilla = findPreference(Settings.PREF_DEVICE);
        assert vanilla != null;
        vanilla.setTitle(getString(R.string.device) + " (" + prefs.getString(Settings.PREF_DEVICE) + ")");
        vanilla.setOnPreferenceClickListener(preference -> {
            FragmentTransaction tx = getParentFragmentManager().beginTransaction();
            tx.replace(R.id.fragmentContainerL, new DiscoveryFragment(false), DiscoveryFragment.TAG);
            tx.addToBackStack(null);
            tx.commit();
            return false;
        });

        checkbox = findPreference(Settings.PREF_AUTOCONNECT);
        assert checkbox != null;
        checkbox.setChecked(prefs.getBoolean(Settings.PREF_AUTOCONNECT));

        slider = findPreference(Settings.PREF_SENSITIVITY);
        assert slider != null;
        slider.setOnPreferenceChangeListener(sul);
        slider.initialise(Settings.SENSITIVITY_MIN, Settings.SENSITIVITY_MAX, prefs.getInt(Settings.PREF_SENSITIVITY));

        intList = findPreference(Settings.PREF_NOISE);
        assert intList != null;
        intList.setOnPreferenceChangeListener(sul);
        intList.setLabelRewriter(value -> getResources().getStringArray(R.array.noise_options)[value]);
        intList.setValue(prefs.getInt(Settings.PREF_NOISE));

        intList = findPreference(Settings.PREF_RANGE);
        assert intList != null;
        intList.setOnPreferenceChangeListener(sul);
        intList.setLabelRewriter(value -> getResources().getStringArray(R.array.range_options)[value]);
        intList.setValue(prefs.getInt(Settings.PREF_RANGE));

        slider = findPreference(Settings.PREF_MIN_DEPTH_CHANGE);
        assert slider != null;
        slider.setOnPreferenceChangeListener(sul);
        slider.setLabelRewriter(value -> {
            return value / 1000.0 + "m"; // convert mm to metres
        });
        slider.initialise(Settings.MIN_DEPTH_CHANGE_MIN, Settings.MIN_DEPTH_CHANGE_MAX, prefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE));

        slider = findPreference(Settings.PREF_MIN_POS_CHANGE);
        assert slider != null;
        slider.setOnPreferenceChangeListener(sul);
        slider.setLabelRewriter(value -> {
            return value / 1000.0 + "m"; // convert mm to metres
        });
        slider.initialise(Settings.MIN_POS_CHANGE_MIN, Settings.MIN_POS_CHANGE_MAX, prefs.getInt(Settings.PREF_MIN_POS_CHANGE));

        slider = findPreference(Settings.PREF_MAX_SAMPLES);
        assert slider != null;
        slider.setOnPreferenceChangeListener(sul);
        slider.setLabelRewriter(value -> {
            float bytes = value * Sample.BYTES;
            String sams, sbytes;
            if (value < 1000)
                sams = Integer.toString(value);
            else if (value < 1000000)
                sams = String.format("%.02gk", value / 1000.0);
            else if (value < 1000000000)
                sams = String.format("%.02gM", value / 1000000.0);
            else
                sams = String.format("%.02gG", value / 1000000000.0);
            if (bytes < Settings.MEGABYTE)
                sbytes = String.format("%.02fKB", bytes / Settings.KILOBYTE);
            else if (bytes < Settings.GIGABYTE)
                sbytes = String.format("%.02fMB", bytes / Settings.MEGABYTE);
            else
                sbytes =String.format("%.02fGB", bytes / Settings.GIGABYTE);
            return sams + "=" + sbytes;
        });
        slider.initialise(Settings.MAX_SAMPLES_MIN, Settings.MAX_SAMPLES_MAX, prefs.getInt(Settings.PREF_MAX_SAMPLES));

        slider = findPreference(Settings.PREF_SAMPLER_TIMEOUT);
        assert slider != null;
        slider.setOnPreferenceChangeListener(sul);
        slider.setLabelRewriter(timeout -> {
            int h = timeout / (1000 * 60 * 60);
            timeout %= 1000 * 60 * 60;
            int m = timeout / (1000 * 60);
            timeout %= 1000 * 60;
            float s = timeout / 1000f;
            if (h > 0)
                return String.format("%dh%02dm%.02fs", h, m, s);
            else if (m > 0)
                return String.format("%dm%02.02fs", m, s);
            else
                return String.format("%.02fs", s);
        });
        slider.initialise(Settings.SAMPLER_TIMEOUT_MIN, Settings.SAMPLER_TIMEOUT_MAX, prefs.getInt(Settings.PREF_SAMPLER_TIMEOUT));
    }
}
