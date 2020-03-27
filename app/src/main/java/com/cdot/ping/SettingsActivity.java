package com.cdot.ping;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.cdot.ping.devices.DeviceRecord;

import static com.cdot.ping.devices.DeviceRecord.DEVICE_ADDRESS;

/**
 * Preferences - a simple, single page that took f**king ages to work out how to do.
 */
public class SettingsActivity extends AppCompatActivity {
    static final String TAG = "SettingsActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment(Ping.P.mContext))
                .commit();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        setResult(Activity.RESULT_OK);// TODO: be smarter
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private static final int REQUEST_CHOOSE_FILE = 1;
        private static final int REQUEST_SELECT_DEVICE = 2;

        private static String VALUE_SEPARATOR = ": ";

        private Context mCxt;

        SettingsFragment(Context a) {
            mCxt = a;
        }

        class ValueChangeListener implements Preference.OnPreferenceChangeListener {
            public boolean onPreferenceChange(Preference pref, Object newVal) {
                String s = pref.getSummary().toString();

                int end = s.indexOf(VALUE_SEPARATOR);
                if (end >= 0) s = s.substring(0, end);
                pref.setSummary(s + VALUE_SEPARATOR + Ping.P.getText(pref.getKey(), newVal));
                return true;
            }
        }

        private class RangedChangeListener extends ValueChangeListener {
            float mMin, mMax;

            RangedChangeListener(float min, float max) {
                mMin = min;
                mMax = max;
            }

            public boolean onPreferenceChange(Preference pref, Object newVal) {
                float f = Float.valueOf(newVal.toString());
                if (f > mMax)
                    return pref.callChangeListener(mMax);
                if (f < mMin)
                    return pref.callChangeListener(mMin);

                return super.onPreferenceChange(pref, newVal);
            }
        }

        // Handle incorporating the value into the summary
        private void setChangeListener(SharedPreferences sharedPreferences, String key, String def) {
            Preference pref = findPreference(key);
            ValueChangeListener vcl = new ValueChangeListener();
            vcl.onPreferenceChange(pref, sharedPreferences.getString(key, def));
            pref.setOnPreferenceChangeListener(vcl);
        }

        private void setChangeListener(SharedPreferences sharedPreferences, String key, float min, float max) {
            Preference pref = findPreference(key);
            RangedChangeListener vcl = new RangedChangeListener(min, max);
            vcl.onPreferenceChange(pref, sharedPreferences.getString(key, key));
            pref.setOnPreferenceChangeListener(vcl);
        }

        // Set the input type of the setting
        private void setInputType(String prefName, final int type) {
            EditTextPreference etPref = findPreference(prefName);
            etPref.setOnBindEditTextListener(new EditTextPreference.OnBindEditTextListener() {
                @Override
                public void onBindEditText(@NonNull EditText editText) {
                    editText.setInputType(type);
                }
            });
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            Preference pref = findPreference("sampleFile");

            // 0-10 in steps of 1
            setInputType("sensitivity", InputType.TYPE_CLASS_NUMBER);
            setInputType("minimumPositionChange", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            setInputType("minimumDepthChange", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

            final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mCxt);
            setChangeListener(sp, "sensitivity", Ping.SENSITIVITY_MIN, Ping.SENSITIVITY_MAX);
            setChangeListener(sp, "minimumPositionChange", Ping.MINIMUM_POSITION_CHANGE_MIN, Ping.MINIMUM_POSITION_CHANGE_MAX);
            setChangeListener(sp, "minimumDepthChange", Ping.MINIMUM_DEPTH_CHANGE_MIN, Ping.MINIMUM_DEPTH_CHANGE_MAX);
            setChangeListener(sp, "sampleFile", "");
            findPreference("selectedDevice").setOnPreferenceChangeListener(new ValueChangeListener() {
                public boolean onPreferenceChange(Preference pref, Object newVal) {
                    DeviceRecord dr = Ping.P.getDevice(newVal.toString());
                    return super.onPreferenceChange(pref, dr.name);
                }
            });
            setChangeListener(sp, "range", Integer.toString(Ping.RANGE_AUTO));
            setChangeListener(sp, "noise", Integer.toString(Ping.NOISE_OFF));

            // Special handling for the sampleFile preference, to bring up a dialog that will
            // test-create the file.
            Preference poof = findPreference("sampleFile");
            poof.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference p) {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/csv");
                    intent.putExtra(Intent.EXTRA_TITLE, getResources().getString(R.string.sampleFile));
                    String curVal = sp.getString("sampleFile", "NONE");
                    if (!"NONE".equals(curVal))
                        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, curVal);
                    startActivityForResult(intent, REQUEST_CHOOSE_FILE);
                    return true;
                }
            });

            poof = findPreference("selectedDevice");
            poof.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference p) {
                    Intent intent = new Intent(mCxt, DeviceListActivity.class);
                    startActivityForResult(intent, REQUEST_SELECT_DEVICE);
                    return true;
                }
            });
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
            ValueChangeListener vcl = new ValueChangeListener();
            if (requestCode == REQUEST_CHOOSE_FILE && resultCode == Activity.RESULT_OK && resultData != null) {
                Uri uri = resultData.getData();
                vcl.onPreferenceChange(findPreference("sampleFile"), uri);
                // Can't see any obvious way of getting this value back into the cache
                // used in the preferences screen, so brute-force it back into shared
                // preferences
                Ping.P.set("sampleFile", uri.toString());
            } else if (requestCode == REQUEST_SELECT_DEVICE && resultCode == Activity.RESULT_OK && resultData != null) {
                // A new device was selected
                DeviceRecord dr = Ping.P.getDevice(resultData.getExtras().getString(DEVICE_ADDRESS));
                vcl.onPreferenceChange(findPreference("selectedDevice"), dr.name);
                Ping.P.set("selectedDevice", dr);
            }
        }
    }
}
