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
package com.cdot.ping.android;

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