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

import android.bluetooth.BluetoothAdapter;
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

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.cdot.ping.databinding.ConnectedFragmentBinding;
import com.cdot.ping.samplers.LoggingService;
import com.cdot.ping.samplers.Sample;
import com.cdot.ping.samplers.SonarSampler;

import java.text.SimpleDateFormat;

/**
 * Fragment that handles user interaction when a device is connected.
 */
public class ConnectedFragment extends Fragment implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = ConnectedFragment.class.getSimpleName();

    private ConnectedFragmentBinding mBinding;
    private Settings mPrefs;
    private IntentFilter mIntentFilter;
    private static final SimpleDateFormat SAMPLE_TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

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
                    getMainActivity().settingsChanged();

            } else if (LoggingService.ACTION_SAMPLE.equals(action)) {
                onSonarSample((Sample) intent.getParcelableExtra(LoggingService.EXTRA_SAMPLE_DATA));
            }
        }
    };

    private Sample mLastSample = null;
    // Used to calculate the incoming sample rate
    private Boolean mHaveNewSamples = false; // display update
    private Thread mDisplayThread;

    private class DisplayThread extends Thread {
        DisplayThread() {
            super(new Runnable() {
                public void run() {
                    while (!interrupted()) {
                        if (mHaveNewSamples) {

                            Resources r = getResources();
                            mBinding.batteryTV.setText(r.getString(R.string.val_battery, mLastSample.battery));
                            mBinding.depthTV.setText(r.getString(R.string.val_depth, mLastSample.depth));
                            mBinding.tempTV.setText(r.getString(R.string.val_temperature, mLastSample.temperature));
                            mBinding.fishDepthTV.setText(r.getString(R.string.val_fish_depth, mLastSample.fishDepth));
                            mBinding.fishStrengthTV.setText(r.getString(R.string.val_fish_strength, mLastSample.fishStrength));
                            mBinding.strengthTV.setText(r.getString(R.string.val_strength, mLastSample.strength));

                            if (mLastSample.location != null) {
                                mBinding.latitudeTV.setText(r.getString(R.string.val_latitude, mLastSample.location.getLatitude()));
                                mBinding.longitudeTV.setText(r.getString(R.string.val_longitude, mLastSample.location.getLongitude()));
                            }

                            LoggingService svc = getLoggingService();
                            if (svc != null) {
                                mBinding.logTimeTV.setText(r.getString(R.string.val_logging_time, formatDeltaTime(svc.getLoggingTime())));
                                mBinding.logRateTV.setText(r.getString(R.string.val_logging_rate, svc.getAverageSamplingRate()));
                                mBinding.logCountTV.setText(r.getString(R.string.val_logging_count, svc.getSampleCount()));
                            } else {
                                mBinding.logTimeTV.setText("?");
                                mBinding.logRateTV.setText("?");
                                mBinding.logCountTV.setText("?");
                            }

                            mHaveNewSamples = false;
                        }
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignore) {
                    }
                }
            });
        }
    }

    public ConnectedFragment() {
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(SonarSampler.ACTION_BT_STATE);
        mIntentFilter.addAction(LoggingService.ACTION_SAMPLE);
    }

    private static String formatDeltaTime(long t) {
        long ms = (t % 1000); // milliseconds
        t = (t - ms) / 1000; // make seconds
        long s = t % 60; // seconds
        t = (t - s) / 60; // make minutes
        long m = t % 60; // minutes
        t = (t - m) / 60; // make hours
        return String.format("%02d:%02d:%02d.%d", t, m, s, ms / 100);
    }

    private MainActivity getMainActivity() {
        return ((MainActivity)getActivity());
    }

    private LoggingService getLoggingService() {
        MainActivity a = getMainActivity();
        return a == null ? null : a.getLoggingService();
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

    private void updatePreferencesDisplay() {
        Resources r = getResources();
        /*int i = mPrefs.getInt(Settings.PREF_SENSITIVITY);
        mBinding.sensitivityTV.setText(r.getString(R.string.val_sensitivity, i));
        i = mPrefs.getInt(Settings.PREF_NOISE);
        mBinding.noiseTV.setText(r.getString(R.string.val_noise, r.getStringArray(R.array.noise_options)[i]));
        i = mPrefs.getInt(Settings.PREF_RANGE);
        mBinding.rangeTV.setText(r.getString(R.string.val_range, r.getStringArray(R.array.range_options)[i]));*/
        LoggingService svc = getLoggingService();
        int bmr;
        if (svc == null)
            bmr = android.R.drawable.ic_menu_search;
        else if (svc.isLogging())
            bmr = android.R.drawable.ic_media_pause;
        else
            bmr = android.R.drawable.ic_media_play;
        mBinding.recordFAB.setImageDrawable(r.getDrawable(bmr, getActivity().getTheme()));
    }

    private void updateSonarStateDisplay(int state, int reason, String daddr) {
        Resources r = getResources();
        String text = String.format(r.getStringArray(R.array.bt_status)[state], daddr, r.getString(reason));
        Log.d(TAG, text);
        mBinding.connectionStatusTV.setText(text);
    }

    // Handle incoming sample from the sonar service
    private void onSonarSample(Sample data) {
        if (data == null)
            return;

        /*
         * when we get two samples, the sample frequency is given by the time between those two samples.
         * In a given time, then number of samples received in that time.
         */
        //Log.d(TAG, "Sonar sample received");
        mLastSample = data;
        mHaveNewSamples = true;

        // Don't do this from the display timer, we want a different feedback schedule
        if (mBinding != null && mBinding.sonarV != null)
            mBinding.sonarV.sample(mLastSample);
    }


    @Override // Activity
    public void onSaveInstanceState(@NonNull Bundle bits) {
        super.onSaveInstanceState(bits);
        if (getLoggingService() != null && getLoggingService().getConnectedDevice() != null)
            bits.putString("device", getLoggingService().getConnectedDevice().getAddress());
        if (mBinding != null && mBinding.sonarV != null)
            mBinding.sonarV.onSaveInstanceState(bits);
    }

    @Override // SharedPreferences.OnSharedPreferenceChangeListener
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        /*if (mReceiverRegistered)
            updatePreferencesDisplay();*/
    }

    // Fragment lifecycle
    // see https://developer.android.com/guide/fragments/lifecycle

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            String da = savedInstanceState.getString("device");
            if (da != null) {
                BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
                BluetoothDevice bd = bta.getRemoteDevice(da);
                if (bd != null && getLoggingService() != null)
                    getLoggingService().connectToDevice(bd);
            }
        }
        if (getLoggingService() != null)
            getLoggingService().setKeepAlive(false);
        mPrefs = new Settings(getActivity());
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        registerBroadcastReceiver();
    }

    @Override // Fragment
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView");
        setHasOptionsMenu(true);
        mBinding = ConnectedFragmentBinding.inflate(inflater, container, false);
        updatePreferencesDisplay();

        LoggingService svc = getLoggingService();
        if (svc == null || svc.getConnectedDevice() == null) {
            //mBinding.deviceAddressTV.setText("-");
            mBinding.deviceNameTV.setText("-");
        } else {
            //String daddr = getLoggingService().getConnectedDevice().getAddress();
            //mBinding.deviceAddressTV.setText(daddr);
            mBinding.deviceNameTV.setText(svc.getConnectedDevice().getName());
        }

        mBinding.recordFAB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                getMainActivity().toggleRecording();
                updatePreferencesDisplay();
            }
        });

        mBinding.sonarV.onRestoreInstanceState(savedInstanceState);

        return mBinding.connectedFragmentL;
    }

    @Override // Fragment
    public void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();
        registerBroadcastReceiver();
        // Start the sample data thread. Wake it every 2 seconds for an update.
        mDisplayThread = new DisplayThread();
        mDisplayThread.start();
    }

    @Override // Fragment
    public void onStop() {
        Log.d(TAG, "onStop");
        if (mDisplayThread.isAlive()) {
            mDisplayThread.interrupt();
            mDisplayThread = null;
        }
        mBinding.sonarV.stop(); // shut down background rendering thread
        unregisterBroadcastReceiver();
        super.onStop();
    }

    @Override // Fragment
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override // Fragment
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        // Inflate the menu; this adds items to the action bar if it is present.
        inflater.inflate(R.menu.menu_main, menu);
    }

    @Override // Fragment
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            if (getLoggingService() != null)
                getLoggingService().setKeepAlive(true);
            FragmentTransaction tx = getParentFragmentManager().beginTransaction();
            tx.replace(R.id.fragmentContainerL, new SettingsFragment(), SettingsFragment.TAG);
            tx.addToBackStack(null);
            tx.commit();
        }
        return super.onOptionsItemSelected(item);
    }
}
