package com.cdot.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

/**
 * Customisation of EditTextPreference for capturing floating point numbers.
 */
public class EditFloatPreference extends EditTextPreference {
    private TitleRewriter mTitleRewriter = null;

    public EditFloatPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public EditFloatPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public EditFloatPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EditFloatPreference(Context context) {
        super(context);
    }

    @Override
    public void setText(String value) {
        super.setText(value);

        if (mTitleRewriter != null)
            setTitle(mTitleRewriter.rewrite(getTitle().toString(), value));
    }

    public void setTitleRewriter(TitleRewriter lw) {
        mTitleRewriter = lw;
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        float val = getPersistedFloat(defaultValue == null ? 0 : (float) defaultValue);
        setText(String.valueOf(val));
    }

    public void initialise(float min, float max, float val) {
        final float mMin = min;
        final float mMax = max;
        setText(String.valueOf(val));
        setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                float val = Float.parseFloat(newValue.toString());
                if ((val >= mMin) && (val <= mMax))
                    return true;
                else {
                    Toast.makeText(getContext(), "Out of range " + mMin + ".." + mMax, Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });
    }
}
