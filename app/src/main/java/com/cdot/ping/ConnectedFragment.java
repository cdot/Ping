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

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.cdot.ping.databinding.ConnectedFragmentBinding;
import com.cdot.ping.samplers.LocationSampler;
import com.cdot.ping.samplers.LoggingService;
import com.cdot.ping.samplers.SonarSampler;

import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Fragment that handles user interaction when a device is connected.
 */
public class ConnectedFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = ConnectedFragment.class.getSimpleName();

    private ConnectedFragmentBinding mBinding;
    private Settings mPrefs;
    private BluetoothDevice mConnectedDevice;
    private IntentFilter mIntentFilter;

    // Handle broadcasts from the service
    private boolean mReceiverRegistered = false;
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (SonarSampler.ACTION_BT_STATE.equals(action)) {
                int state = intent.getIntExtra(SonarSampler.EXTRA_STATE, SonarSampler.BT_STATE_DISCONNECTED);
                String daddr = intent.getStringExtra(SonarSampler.EXTRA_DEVICE_ADDRESS);
                int reason = intent.getIntExtra(SonarSampler.EXTRA_REASON, R.string.reason_ok);
                updateSonarStateDisplay(state, reason, daddr);

                if (state == SonarSampler.BT_STATE_CONNECTED)
                    ((MainActivity) getActivity()).settingsChanged();

            } else if (LoggingService.ACTION_SAMPLE.equals(action)) {
                String source = intent.getStringExtra(LoggingService.EXTRA_SAMPLE_SOURCE);
                Bundle bund = intent.getBundleExtra(LoggingService.EXTRA_SAMPLE_DATA);
                if (SonarSampler.TAG.equals(source))
                    onSonarSample(bund);
                else if (LocationSampler.TAG.equals(source))
                    onLocationSample(bund);
            }
        }
    };

    ConnectedFragment(BluetoothDevice device) {
        mConnectedDevice = device;
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(SonarSampler.ACTION_BT_STATE);
        mIntentFilter.addAction(LoggingService.ACTION_SAMPLE);
    }

    private void registerBroadcastReceiver() {
        if (mReceiverRegistered)
            return;
        getActivity().registerReceiver(mBroadcastReceiver, mIntentFilter);
        mReceiverRegistered = true;
    }

    private void unregisterBroadcastReceiver() {
        if (mReceiverRegistered) {
            getActivity().unregisterReceiver(mBroadcastReceiver);
            mReceiverRegistered = false;
        }
    }

    // Fragment lifecycle
    // see https://developer.android.com/guide/fragments/lifecycle
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = new Settings(getActivity());
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        registerBroadcastReceiver();
        LoggingService svc = ((MainActivity) getActivity()).getLoggingService();
        SonarSampler sam = (SonarSampler) svc.getSampler(SonarSampler.TAG);
        sam.connectToDevice(mConnectedDevice);
    }

    // Called after onCreate and before onViewCreated, or may get here from onAttach if the
    // fragment object already exists, or from onDestroyView (under what circumstances?)
    @Override // Fragment
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView");
        setHasOptionsMenu(true);
        mBinding = ConnectedFragmentBinding.inflate(inflater, container, false);

        updatePreferencesDisplay();
        if (mConnectedDevice == null) {
            mBinding.deviceAddress.setText("-");
            mBinding.deviceName.setText("-");
            // Only way we can get here with a null device....
            Toast.makeText(getActivity(), R.string.help_no_bluetooth, Toast.LENGTH_LONG).show();
        } else {
            String daddr = mConnectedDevice.getAddress();
            mBinding.deviceAddress.setText(daddr);
            mBinding.deviceName.setText(mConnectedDevice.getName());
        }

        mBinding.record.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean on = ((MainActivity) getActivity()).toggleRecording();
                int dribble = on ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
                mBinding.record.setImageDrawable(getResources().getDrawable(dribble, getActivity().getTheme()));
            }
        });

        Resources r = getResources();
        mBinding.batteryValue.setText(r.getString(R.string.val_battery, 0));
        mBinding.depthValue.setText(r.getString(R.string.val_depth, 0.0));
        mBinding.temperatureValue.setText(r.getString(R.string.val_temperature, 0.0));
        mBinding.fishDepthValue.setText(r.getString(R.string.val_fish_depth, 0.0));
        mBinding.fishTypeValue.setText(r.getString(R.string.val_fish_strength, 0));
        mBinding.strengthValue.setText(r.getString(R.string.val_strength, 0));
        mBinding.latitude.setText(r.getString(R.string.val_latitude, 0.0));
        mBinding.longitude.setText(r.getString(R.string.val_longitude, 0.0));
        return mBinding.connectedFragment;
    }

    // Called after onCreateView->onViewCreated->onViewStateRestored. Followed by onResume
    @Override // Fragment
    public void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        registerBroadcastReceiver();
    }

    /**
     * Only way to get here is from onPause. Next will be one of onDestroyView or onStart.
     */
    @Override // Fragment
    public void onStop() {
        Log.d(TAG, "onStop");
        unregisterBroadcastReceiver();
        super.onStop();
    }

    @Override // Fragment
    public void onDestroyView() {
        mBinding.sonarView.stop(); // shut down background rendering thread
        super.onDestroyView();
    }

    @Override // SharedPreferences.OnSharedPreferenceChangeListener
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        updatePreferencesDisplay();
    }

    private void updatePreferencesDisplay() {
        Resources r = getResources();
        int i = mPrefs.getInt(Settings.PREF_SENSITIVITY);
        mBinding.sensitivity.setText(r.getString(R.string.val_sensitivity, i));
        i = mPrefs.getInt(Settings.PREF_NOISE);
        mBinding.noise.setText(r.getString(R.string.val_noise, r.getStringArray(R.array.noise_options)[i]));
        i = mPrefs.getInt(Settings.PREF_RANGE);
        mBinding.range.setText(r.getString(R.string.val_range, r.getStringArray(R.array.range_options)[i]));
    }

    private void updateSonarStateDisplay(int state, int reason, String daddr) {
        Resources r = getResources();
        String text = String.format(r.getStringArray(R.array.bt_status)[state], daddr, r.getString(reason));
        Log.d(TAG, text);
        mBinding.connectionStatus.setText(text);
    }

    @Override // Fragment
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        // Inflate the menu; this adds items to the action bar if it is present.
        inflater.inflate(R.menu.menu_main, menu);
    }

    @Override // Fragment
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            FragmentTransaction tx = getParentFragmentManager().beginTransaction();
            tx.replace(R.id.fragment_container, new SettingsFragment(), SettingsFragment.TAG);
            tx.addToBackStack(null);
            tx.commit();
        }
        return super.onOptionsItemSelected(item);
    }

    // Handle incoming sample from the sonar service
    private void onSonarSample(Bundle data) {
        if (data == null)
            return;

        //Log.d(TAG, "Sonar sample received");
        Resources r = getResources();
        mBinding.batteryValue.setText(r.getString(R.string.val_battery, data.getInt(SonarSampler.I_BATTERY)));
        mBinding.depthValue.setText(r.getString(R.string.val_depth, data.getDouble(SonarSampler.G_DEPTH)));
        mBinding.temperatureValue.setText(r.getString(R.string.val_temperature, data.getDouble(SonarSampler.G_TEMPERATURE)));
        mBinding.fishDepthValue.setText(r.getString(R.string.val_fish_depth, data.getDouble(SonarSampler.G_FISHDEPTH)));
        mBinding.fishTypeValue.setText(r.getString(R.string.val_fish_strength, data.getInt(SonarSampler.I_FISHSTRENGTH)));
        mBinding.strengthValue.setText(r.getString(R.string.val_strength, data.getInt(SonarSampler.I_STRENGTH)));
        // Sonar samples are watermarked with the location
        onLocationSample(data);
        Format f = new SimpleDateFormat("HH:mm:ss");
        mBinding.time.setText(f.format(new Date(data.getLong(SonarSampler.L_TIME))));
        mBinding.samplesReceived.setText(r.getString(R.string.val_samples, ++((MainActivity)getActivity()).sonarSampleCount));

        mBinding.sonarView.sample(data);
    }

    // Handle incoming sample from the location service
    private void onLocationSample(Bundle data) {
        Resources r = getResources();
        mBinding.latitude.setText(r.getString(R.string.val_latitude, data.getDouble(LocationSampler.G_LATITUDE)));
        mBinding.longitude.setText(r.getString(R.string.val_longitude, data.getDouble(LocationSampler.G_LONGITUDE)));
    }
}
