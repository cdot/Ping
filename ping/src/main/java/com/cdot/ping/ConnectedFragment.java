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

/**
 * Fragment that displays incoming samples when a device is connected. Visually it is made up of a
 * pane of textual information and a sonar view. These are updated by threads, kept separate so
 * they can have different update schedules.
 */
public class ConnectedFragment extends Fragment {
    private static final String TAG = ConnectedFragment.class.getSimpleName();

    private final IntentFilter mIntentFilter;
    private ConnectedFragmentBinding mBinding;
    // Handle broadcasts from the service
    private boolean mReceiverRegistered = false;
    private Sample mLastSample = new Sample();
    // Used to calculate the incoming sample rate
    private Boolean mHaveNewSamples = false; // display update required?

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (LoggingService.ACTION_SAMPLE.equals(action)) {
                onSonarSample(intent.getParcelableExtra(LoggingService.EXTRA_SAMPLE_DATA));
            } else if (MainActivity.ACTION_RECONFIGURE.equals(action)) {
                Log.d(TAG, "Received ACTION_RECONFIGURE");
                mBinding.sonarV.resetScale();
            }
        }
    };
    // Thread used to update the text components of the display. Done this way to keep the work off
    // the main thread.
    private Thread mDisplayThread;

    public ConnectedFragment() {
        mIntentFilter = new IntentFilter();
        //mIntentFilter.addAction(SonarSamplerTwo.ACTION_BT_STATE);
        mIntentFilter.addAction(LoggingService.ACTION_SAMPLE);
        mIntentFilter.addAction(MainActivity.ACTION_RECONFIGURE);
    }

    private MainActivity getMainActivity() {
        return ((MainActivity) getActivity());
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
        if (mBinding != null)
            mBinding.sonarV.sample(mLastSample);
    }

    // When switching to SettingsFragment, this is NOT called!
    @Override // Activity
    public void onSaveInstanceState(@NonNull Bundle bits) {
        Log.d(TAG, "onSaveInstanceState");
        super.onSaveInstanceState(bits);
        if (getLoggingService() != null && getLoggingService().getConnectedDevice() != null)
            bits.putString("device", getLoggingService().getConnectedDevice().getAddress());
    }

    // Fragment lifecycle
    // see https://developer.android.com/guide/fragments/lifecycle

    @Override // Fragment
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate savedInstanceState is " + savedInstanceState);
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            String da = savedInstanceState.getString("device");
            if (da != null) {
                BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
                BluetoothDevice bd = bta.getRemoteDevice(da);
                if (bd != null && getLoggingService() != null)
                    getLoggingService().mSonarSampler.connect(bd);
            }
        }

        registerBroadcastReceiver();
    }

    @Override // Fragment
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView savedInstanceState is " + savedInstanceState);
        setHasOptionsMenu(true);
        mBinding = ConnectedFragmentBinding.inflate(inflater, container, false);

        mBinding.zoomInFAB.setOnClickListener(view -> mBinding.sonarV.zoom(1.5f));
        mBinding.zoomOutFAB.setOnClickListener(view -> mBinding.sonarV.zoom(0.75f));

        return mBinding.connectedFragmentL;
    }

    @Override // Fragment
    public void onStart() {
        Log.d(TAG, "onStart " + this);
        super.onStart();
        registerBroadcastReceiver();
        // Start the sample data thread. Wake it every 2 seconds for an update.
        mDisplayThread = new DisplayThread();
        // Force a display update when returning from SettingsFragment
        mHaveNewSamples = true;
        mDisplayThread.start();
        getActivity().sendBroadcast(new Intent(MainActivity.ACTION_RECONFIGURE));
    }

    @Override // Fragment
    public void onStop() {
        Log.d(TAG, "onStop " + this);
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
            FragmentTransaction tx = getParentFragmentManager().beginTransaction();
            tx.replace(R.id.fragmentContainerL, new SettingsFragment(), SettingsFragment.TAG);
            tx.addToBackStack(null);
            tx.commit();
        } else if (item.getItemId() == R.id.menu_write_gpx) {
            getMainActivity().writeGPX();
        }

        return super.onOptionsItemSelected(item);
    }

    // Keep the work of redisplay off the main thread
    private class DisplayThread extends Thread {
        DisplayThread() {
            super(new Runnable() {
                @Override
                public void run() {
                    while (!interrupted()) {
                        if (mHaveNewSamples) {
                            getActivity().runOnUiThread(() -> {
                                Resources r = getResources();
                                mBinding.batteryTV.setText(r.getString(R.string.val_battery, mLastSample.battery));
                                mBinding.depthTV.setText(r.getString(R.string.val_depth, mLastSample.depth));
                                mBinding.tempTV.setText(r.getString(R.string.val_temperature, mLastSample.temperature));
                                mBinding.fishDepthTV.setText(r.getString(R.string.val_fish_depth, mLastSample.fishDepth));
                                mBinding.fishStrengthTV.setText(r.getString(R.string.val_fish_strength, mLastSample.fishStrength));
                                mBinding.strengthTV.setText(r.getString(R.string.val_strength, mLastSample.strength));

                                mBinding.latitudeTV.setText(r.getString(R.string.val_latitude, mLastSample.latitude));
                                mBinding.longitudeTV.setText(r.getString(R.string.val_longitude, mLastSample.longitude));

                                LoggingService svc = getLoggingService();
                                if (svc != null) {
                                    //mBinding.logTimeTV.setText(r.getString(R.string.val_logging_time, formatDeltaTime(svc.getLoggingTime())));
                                    mBinding.logRateTV.setText(r.getString(R.string.val_sample_rate, svc.getRawSampleRate()));
                                    mBinding.logCountTV.setText(r.getString(R.string.val_sample_count, svc.getSamplesLogged()));
                                    mBinding.cacheUsedTV.setText(r.getString(R.string.val_cache_usage, svc.getCacheUsage()));
                                } else {
                                    //mBinding.logTimeTV.setText("?");
                                    mBinding.logRateTV.setText("?");
                                    mBinding.logCountTV.setText("?");
                                }
                            });
                            mHaveNewSamples = false;
                        }

                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignore) {
                        }
                    }
                }
            });
        }
    }
}
