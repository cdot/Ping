package com.cdot.ping;

import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;

/**
 * Preferences - a simple, single page that took f**king ages to work out how to do.
 */
public class SettingsActivity extends PreferenceActivity {

    public static class SettingsFrag extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // Load the preference page from resources. Note that only String preferences
            // can be done this way.
            addPreferencesFromResource(R.xml.settings_frag);
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getFragmentManager().beginTransaction().replace(android.R.id.content,
                new SettingsFrag()).commit();
    }

    @Override
    protected boolean isValidFragment(String fragmentName) {
        return SettingsFrag.class.getName().equals(fragmentName);
    }
}