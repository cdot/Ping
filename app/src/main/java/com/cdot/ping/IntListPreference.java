package com.cdot.ping;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.ListPreference;

/**
 * Customisation of ListPreference to persist integer values rather than the string values that
 * ListPreference insists on.
 */
public class IntListPreference extends ListPreference {

    public IntListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public IntListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public IntListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public IntListPreference(Context context) {
        super(context);
    }

    @Override
    protected boolean persistString(String value) {
        int intValue = Integer.parseInt(value);
        return persistInt(intValue);
    }

    @Override
    protected String getPersistedString(String defaultReturnValue) {
        int intValue;

        if (defaultReturnValue != null) {
            int intDefaultReturnValue = Integer.parseInt(defaultReturnValue);
            intValue = getPersistedInt(intDefaultReturnValue);
        } else {
            // We haven't been given a default return value, but we need to specify one when retrieving the value

            if (getPersistedInt(0) == getPersistedInt(1)) {
                // The default value is being ignored, so we're good to go
                intValue = getPersistedInt(0);
            } else {
                throw new IllegalArgumentException("Cannot get an int without a default return value");
            }
        }

        return Integer.toString(intValue);
    }
}