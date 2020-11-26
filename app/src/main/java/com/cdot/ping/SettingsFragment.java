package com.cdot.ping;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.fragment.app.FragmentTransaction;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

public class SettingsFragment extends PreferenceFragmentCompat {
    private static final String PACKAGE = SettingsFragment.class.getPackage().getName();

    private static final String TAG = SettingsFragment.class.getSimpleName();

    // File selector requests
    private static final int REQUEST_CHOOSE_FILE = 1;
    private static final String EXTRA_PREFERENCE_NAME = PACKAGE + ".EXTRA_PREFERENCE_NAME";

    // String used to separate the preference current value from the description in the summary
    private static String VALUE_SEPARATOR = "\n";

    // Interface to SharedPreferences
    private Settings mPrefs;

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
        float i;
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

    // All value changes should come through here. Preference value changes are all handled
    // in the MainActivity when the preference screen is navigated away from.
    private class SummaryUpdateListener implements Preference.OnPreferenceChangeListener {
        @Override // implement Preference.OnPreferenceChangeListener
        public boolean onPreferenceChange(Preference pref, Object newVal) {
            if (pref.getKey().equals(Settings.PREF_NOISE) || pref.getKey().equals(Settings.PREF_RANGE))
                newVal = new Integer((String)newVal);

            summarise(pref, newVal);

            // We've been handed a new val, but it isn't in the preferences yet.
            // Tell the MainActivity to update
            ((MainActivity) getActivity()).settingsChanged(pref.getKey(), newVal);
            return true;
        }
    }

    @Override // PreferenceFragmentCompat
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey);

        mPrefs = new Settings(getActivity());

        CheckBoxPreference cbPref = findPreference(Settings.PREF_AUTOCONNECT);
        cbPref.setChecked(mPrefs.getBoolean(Settings.PREF_AUTOCONNECT));

        SeekBarPreference seekPref = findPreference(Settings.PREF_SENSITIVITY);
        seekPref.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(seekPref, mPrefs.getInt(Settings.PREF_SENSITIVITY));
        seekPref.setMin(Settings.SENSITIVITY_MIN);
        seekPref.setMax(Settings.SENSITIVITY_MAX);
        seekPref.setValue(mPrefs.getInt(Settings.PREF_SENSITIVITY));

        seekPref = findPreference(Settings.PREF_MIN_POS_CHANGE);
        seekPref.setOnPreferenceChangeListener(new SummaryUpdateListener());
        seekPref.setMin(Settings.MIN_POS_CHANGE_MIN);
        seekPref.setMax(Settings.MIN_POS_CHANGE_MAX);
        summarise(seekPref, mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE));
        seekPref.setValue(mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE));

        seekPref = findPreference(Settings.PREF_MIN_DEPTH_CHANGE);
        seekPref.setOnPreferenceChangeListener(new SummaryUpdateListener());
        seekPref.setMin(Settings.MIN_DEPTH_CHANGE_MIN);
        seekPref.setMax(Settings.MIN_DEPTH_CHANGE_MAX);
        summarise(seekPref, mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE));
        seekPref.setValue(mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE));

        IntListPreference ilPref = findPreference(Settings.PREF_RANGE);
        ilPref.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(ilPref, mPrefs.getInt(Settings.PREF_RANGE));
        ilPref.setValue(Integer.toString(mPrefs.getInt(Settings.PREF_RANGE)));

        ilPref = findPreference(Settings.PREF_NOISE);
        ilPref.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(ilPref, mPrefs.getInt(Settings.PREF_NOISE));
        ilPref.setValue(Integer.toString(mPrefs.getInt(Settings.PREF_NOISE)));

        // Special handling for the sample file preferences, to bring up a dialog that will
        // test-create the file.
        Preference poof = findPreference(Settings.PREF_SONAR_SAMPLE_FILE);
        poof.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(poof, mPrefs.getString(Settings.PREF_SONAR_SAMPLE_FILE));

        poof.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference p) {
                getFile(Settings.PREF_SONAR_SAMPLE_FILE, R.string.sonarSampleFile);
                return true;
            }
        });

        poof = findPreference(Settings.PREF_LOCATION_SAMPLE_FILE);
        poof.setOnPreferenceChangeListener(new SummaryUpdateListener());
        summarise(poof, mPrefs.getString(Settings.PREF_LOCATION_SAMPLE_FILE));
        poof.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference p) {
                getFile(Settings.PREF_LOCATION_SAMPLE_FILE, R.string.locationSampleFile);
                return true;
            }
        });

        poof = findPreference(Settings.PREF_DEVICE);
        summarise(poof, mPrefs.getString(Settings.PREF_DEVICE));
        poof.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                FragmentTransaction tx = getParentFragmentManager().beginTransaction();
                tx.replace(R.id.fragment, new DiscoveryFragment(false));
                tx.addToBackStack(null);
                tx.commit();
                return false;
            }
        });
    }

    // Initiate a file selection activity
    private void getFile(String pref, int titleR) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, getResources().getString(titleR));
        intent.putExtra(EXTRA_PREFERENCE_NAME, pref);
        String curVal = mPrefs.getString(pref);
        if (curVal != null)
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, curVal);
        startActivityForResult(intent, REQUEST_CHOOSE_FILE);
    }

    // Handle result from switching to the file selection activities used to select log file destinations
    // Deprecated, but an utter PITA to handle any other way
    @Override  // Fragment
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        SummaryUpdateListener vcl = new SummaryUpdateListener();
        if (requestCode == REQUEST_CHOOSE_FILE && resultCode == Activity.RESULT_OK && resultData != null) {
            Uri uri = resultData.getData();
            String pref = resultData.getStringExtra(EXTRA_PREFERENCE_NAME);
            vcl.onPreferenceChange(findPreference(pref), uri);
            // Can't see any obvious way of getting this value back into the cache
            // used in the preferences screen, so brute-force it back into shared
            // preferences
            SharedPreferences.Editor edit = android.preference.PreferenceManager.getDefaultSharedPreferences(getActivity()).edit();
            edit.putString(pref, uri.toString());
            edit.apply();
        }
    }
}
