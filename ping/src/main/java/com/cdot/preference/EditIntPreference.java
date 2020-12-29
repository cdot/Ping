package com.cdot.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

public class EditIntPreference extends EditTextPreference {
    private TitleRewriter mTitleRewriter = null;

    public EditIntPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public EditIntPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public EditIntPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EditIntPreference(Context context) {
        super(context);
    }

    @Override
    public void setText(String value) {
        super.setText(value);

        if (mTitleRewriter != null)
            setTitle(mTitleRewriter.rewrite(getTitle().toString(), value));
    }

    // optional title rewriter, to show the value in the preference title
    public void setTitleRewriter(TitleRewriter lw) {
        mTitleRewriter = lw;
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        int val = getPersistedInt(defaultValue == null ? 0 : (int) defaultValue);
        setText(String.valueOf(val));
    }

    public void initialise(int min, int max, int val) {
        final int mMin = min;
        final int mMax = max;
        setText(String.valueOf(val));
        setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                int val = Integer.parseInt(newValue.toString());
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
