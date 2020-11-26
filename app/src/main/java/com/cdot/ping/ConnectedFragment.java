package com.cdot.ping;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.cdot.ping.databinding.ConnectedFragmentBinding;
import com.cdot.ping.services.LocationService;
import com.cdot.ping.services.SonarService;

import java.util.Locale;

public class ConnectedFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = ConnectedFragment.class.getSimpleName();

    ConnectedFragmentBinding mBinding;
    Settings mPrefs;
    private BluetoothDevice mConnectedDevice;
    private IntentFilter mIntentFilter;

    ConnectedFragment(BluetoothDevice device) {
        mConnectedDevice = device;
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(SonarService.ACTION_CONNECTED);
        mIntentFilter.addAction(SonarService.ACTION_DISCONNECTED);
        mIntentFilter.addAction(SonarService.ACTION_SAMPLE);
        mIntentFilter.addAction(LocationService.ACTION_LOCATION_CHANGED);
    }

    private SonarService getSonarService() {
        return ((MainActivity) getActivity()).mSonarService;
    }

    // Map broadcasts from the service to something the application can understand
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (SonarService.ACTION_CONNECTED.equals(action)) {
                //mConnectedDevice = intent.getStringExtra(SonarService.EXTRA_DEVICE_ADDRESS);
                Log.d(TAG, "Connected to " + mConnectedDevice);
                updateStateDisplay();

                // Set up (or confirm) configuration
                ((MainActivity) getActivity()).settingsChanged();

            } else if (SonarService.ACTION_DISCONNECTED.equals(action)) {
                Log.d(TAG, "Disconnected from " + mConnectedDevice.getAddress());
                updateStateDisplay();

            } else if (SonarService.ACTION_SAMPLE.equals(action)) {
                Bundle bund = intent.getBundleExtra(SonarService.EXTRA_SAMPLE_DATA);
                onSonarSample(bund);

            } else if (LocationService.ACTION_LOCATION_CHANGED.equals(action)) {
                Location location = intent.getParcelableExtra(LocationService.EXTRA_LOCATION);
                onLocationSample(location);
            }
        }
    };
    private boolean mReceiverRegistered = false;

    private void registerReceiver() {
        if (mReceiverRegistered)
            return;
        getActivity().registerReceiver(mBroadcastReceiver, mIntentFilter);
        mReceiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (mReceiverRegistered) {
            getActivity().unregisterReceiver(mBroadcastReceiver);
            mReceiverRegistered = false;
        }
    }

    /**
     * Called after onCreate and onStart, and when returning to the activity after a pause (another
     * activity came into the foreground)
     */
    @Override
    public void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        registerReceiver();
    }

    @Override
    public void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        registerReceiver();
    }

    /**
     * Another activity is coming into the foreground
     */
    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        mBinding.sonarView.finish();
        unregisterReceiver();
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.d(TAG, "onStop");
        mBinding.sonarView.finish();
        unregisterReceiver();
        super.onStop();
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        updatePreferencesDisplay();
    }

    // Update display of current preferences
    private void updatePreferencesDisplay() {
        Resources r = getResources();
        int i = mPrefs.getInt(Settings.PREF_SENSITIVITY);
        mBinding.sensitivity.setText(r.getString(R.string.sensitivity_value, i));
        i = mPrefs.getInt(Settings.PREF_NOISE);
        mBinding.noise.setText(r.getString(R.string.noise_value, r.getStringArray(R.array.noise_options)[i]));
        i = mPrefs.getInt(Settings.PREF_RANGE);
        mBinding.range.setText(r.getString(R.string.range_value, r.getStringArray(R.array.range_options)[i]));
    }

    private void updateStateDisplay() {
        int state = getSonarService().getState();
        String reason = getSonarService().getStateReason();
        String s = String.format(getResources().getStringArray(R.array.bt_status)[state], reason);
        mBinding.connectionStatus.setText(s);
    }

    @Override // EntryListFragment
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView");
        setHasOptionsMenu(true);
        mBinding = ConnectedFragmentBinding.inflate(inflater, container, false);
        mPrefs = new Settings(getActivity());

        updatePreferencesDisplay();
        String daddr = mConnectedDevice.getAddress();
        mBinding.deviceAddress.setText(daddr);
        mBinding.deviceName.setText(mConnectedDevice.getName());

        mBinding.record.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean on = ((MainActivity) getActivity()).toggleRecording();
                int dribble = on ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
                mBinding.record.setImageDrawable(getResources().getDrawable(dribble, getActivity().getTheme()));
            }
        });

        mPrefs.registerOnSharedPreferenceChangeListener(this);
        registerReceiver();
        getSonarService().connect(mConnectedDevice);

        return mBinding.getRoot();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        // Inflate the menu; this adds items to the action bar if it is present.
        inflater.inflate(R.menu.menu_main, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            FragmentTransaction tx = getParentFragmentManager().beginTransaction();
            tx.replace(R.id.fragment, new SettingsFragment());
            tx.addToBackStack(null);
            tx.commit();
        }
        return super.onOptionsItemSelected(item);
    }

    // Sample coming from a sonar service
    void onSonarSample(Bundle data) {
        if (data == null)
            return;

        //Log.d(TAG, "Sonar sample received");
        Resources r = getResources();
        mBinding.batteryValue.setText(r.getString(R.string.battery_value, data.getInt("battery")));
        mBinding.depthValue.setText(r.getString(R.string.depth_value, data.getDouble("depth")));
        mBinding.temperatureValue.setText(r.getString(R.string.temperature_value, data.getDouble("temperature")));
        mBinding.fishDepthValue.setText(r.getString(R.string.fish_depth_value, data.getDouble("fishDepth")));
        mBinding.fishTypeValue.setText(r.getString(R.string.fish_type_value, data.getDouble("fishStrength")));
        mBinding.strengthValue.setText(r.getString(R.string.strength_value, data.getDouble("strength")));

        mBinding.sonarView.sample(data);
    }

    // Sample coming from a location service
    void onLocationSample(Location loc) {
        Resources r = getResources();
        mBinding.latitude.setText(r.getString(R.string.latitude_value, loc.getLatitude()));
        mBinding.longitude.setText(r.getString(R.string.longitude_value, loc.getLongitude()));
    }
}
