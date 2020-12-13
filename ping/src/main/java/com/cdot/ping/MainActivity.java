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

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.cdot.ping.databinding.MainActivityBinding;
import com.cdot.ping.samplers.LoggingService;
import com.cdot.ping.samplers.SonarSampler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import no.nordicsemi.android.ble.observer.ConnectionObserver;

/**
 * The main activity in the app.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    // Messages that have to be handled in onActivityResult/onRequestPermissionsResult
    private final static int REQUEST_ENABLE_BLUETOOTH = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private static final int REQUEST_CHOOSE_FILE = 3;
    // A reference to the service used to get location updates. Used by Fragments.
    LoggingService mLoggingService = null;
    private Settings mPrefs;
    MainActivityBinding mBinding; // view binding

    // Cache of current settings, so we can detect when they change. Initial values will be
    // replaced as soon as settingsChanged is called (which it will be when the service starts)
    private int sensitivity = Settings.SENSITIVITY_MIN;
    private int noise = Settings.NOISE_OFF;
    private int range = Settings.RANGE_AUTO;
    private int minDeltaD = Settings.MIN_DEPTH_CHANGE_MAX;
    private float minDeltaPos = Settings.MIN_POS_CHANGE_MAX;
    private int maxSamples = Settings.MAX_SAMPLES_MIN;

    // Connections to services
    private String sampleFile = null;
    // Tracks the bound state of the service. Only meaningful if mLoggingService != null
    private boolean mLoggingServiceBound = false;

    // Monitors the state of the connection to the location service.
    private final ServiceConnection mLoggingServiceConnection = new ServiceConnection() {

        @Override // ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Logging service connected");
            LoggingService.LoggingServiceBinder binder = (LoggingService.LoggingServiceBinder) service;
            mLoggingService = binder.getService();
            mLoggingServiceBound = true;
            startService(new Intent(MainActivity.this, LoggingService.class));

            // Setting the sample file means a change in the activity, so when it comes back the
            // service is re-bound. If we started in SettingsFragment, we need to get back there.
            Fragment frag = getSupportFragmentManager().findFragmentByTag(SettingsFragment.TAG);
            if (frag == null || !frag.isVisible())
                connectSonarDevice();
        }

        @Override // ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Logging service disconnected");
            mLoggingService = null;
            mLoggingServiceBound = false;
        }
    };

    BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (SonarSampler.ACTION_BT_STATE.equals(action)) {
                int state = intent.getIntExtra(SonarSampler.EXTRA_STATE, SonarSampler.BT_STATE_DISCONNECTED);
                BluetoothDevice device = intent.getParcelableExtra(SonarSampler.EXTRA_DEVICE);
                int reason = intent.getIntExtra(SonarSampler.EXTRA_REASON, ConnectionObserver.REASON_UNKNOWN);
                updateSonarStateDisplay(state, reason, device);
            }
        }
    };

    private void updateSonarStateDisplay(int state, int reason, BluetoothDevice device) {
        Resources r = getResources();
        String dev = (device == null) ? r.getString(R.string.default_device_name) : device.getName();
        mBinding.deviceNameTV.setText(dev);
        String rationale = reason < 0 ? "" : r.getStringArray(R.array.bt_reason)[reason];
        String sta = String.format(r.getStringArray(R.array.bt_status)[state], rationale);
        mBinding.connectionStatusTV.setText(sta);
        Log.d(TAG, "Bluetooth state: " + sta + " " + dev);
    }

    // Lifecycle management

    @Override // Activity
    protected void onSaveInstanceState(Bundle bits) {
        Log.d(TAG, "onSaveInstanceState()");
        super.onSaveInstanceState(bits);
    }

    // equals() when either string could be null
    private static boolean sameString(String a, String b) {
        if (a == b) return true;
        return a != null && a.equals(b);
    }

    @Override // Activity
    protected void onRestoreInstanceState(@NonNull Bundle bits) {
        sensitivity = mPrefs.getInt(Settings.PREF_SENSITIVITY);
        noise = mPrefs.getInt(Settings.PREF_NOISE);
        range = mPrefs.getInt(Settings.PREF_RANGE);
        minDeltaD = mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE);
        minDeltaPos = mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE);
        maxSamples = mPrefs.getInt(Settings.PREF_MAX_SAMPLES);
        super.onRestoreInstanceState(bits);
    }

    // See https://developer.android.com/guide/components/activities/activity-lifecycle
    @Override // Activity
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);

        /* Or android:screenOrientation="locked" on the activity tag in AndroidManifest.xml to
         prevent configuration changes. This is because a configuration change inevitably ends up
         killing the logging service, I guess because it is resource hungry?
         Use this if it can't be fixed.
          @see https://android.jlelse.eu/handling-orientation-changes-in-android-7072958c442a */
        //setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        mBinding = MainActivityBinding.inflate(getLayoutInflater());
        setContentView(mBinding.mainActivityL);

        getPermissions();
    }

    // See https://developer.android.com/guide/components/activities/activity-lifecycle
    @Override // Activity
    protected void onStart() {
        super.onStart();

        // Not connected to anything yet
        updateSonarStateDisplay(SonarSampler.BT_STATE_DISCONNECTED, ConnectionObserver.REASON_UNKNOWN, null);

        Log.d(TAG, "onStart binding LoggingService");

        // Bind to the service. If the service is in foreground mode, this signals to the service
        // that since this activity is in the foreground, the service can exit foreground mode.
        // If the service is already running, it should ping us with the status.
        bindService(new Intent(getApplicationContext(), LoggingService.class), mLoggingServiceConnection, Context.BIND_AUTO_CREATE);

        mPrefs = new Settings(this);
        IntentFilter inf = new IntentFilter();
        inf.addAction(SonarSampler.ACTION_BT_STATE);
        registerReceiver(mBroadcastReceiver, inf);
    }

    // Handling permissions

    // See https://developer.android.com/guide/components/activities/activity-lifecycle
    @Override // Activity
    protected void onStop() {
        Log.d(TAG, "onStop");
        // Unbind from the services. This signals to the service that this activity is no longer
        // in the foreground, and the service can respond by promoting itself to a foreground
        // service.
        if (mLoggingServiceBound) {
            Log.d(TAG, "unbinding LoggingService");
            unbindService(mLoggingServiceConnection);
            mLoggingServiceBound = false;
        }
        unregisterReceiver(mBroadcastReceiver);

        super.onStop();
    }

    // Handle the result of requestPermissions()
    @Override // Activity
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != REQUEST_PERMISSIONS)
            return;
        boolean granted = false;
        if (permissions.length > 0) {
            // If request is cancelled, the result arrays are empty.
            granted = true;
            String[] required = getResources().getStringArray(R.array.permissions_required);
            String[] rationale = getResources().getStringArray(R.array.permission_rationales);
            StringBuffer message = new StringBuffer();
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    if (shouldShowRequestPermissionRationale(permissions[i])) {
                        for (int j = 0; j < required.length; j++)
                            if (required[j].equals(permissions[i]))
                                message.append(rationale[j]);
                    }
                }
            }
            if (!granted && message.length() > 0) {
                // Give rationale
                new AlertDialog.Builder(this).setMessage(message)
                        .setPositiveButton(getResources().getString(R.string.OK),
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        getPermissions();
                                    }
                                })
                        .setNegativeButton(getResources().getString(R.string.cancel), null).create().show();
                return;
            }
        }
        if (granted)
            connectSonarDevice();
        else
            // Try again to get permissions. We need them! They'll crack eventually.
            getPermissions();
    }

    // Check if we have all required permissions. If we do, then proceed, otherwise ask for the
    // permissions. We do this even if there is no bluetooth support and we are just in demo mode.
    private void getPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : getResources().getStringArray(R.array.permissions_required)) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED)
                missing.add(perm);
        }
        if (missing.isEmpty())
            permissionsGranted();
        else
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    // Handle results from getPermissions() and startActivityForResult.
    @Override // Activity
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            Log.d(TAG, "REQUEST_ENABLE_BLUETOOTH received");
            if (resultCode == Activity.RESULT_OK)
                connectSonarDevice();

        } else if (requestCode == REQUEST_CHOOSE_FILE) {
            // This request is made from getFile(). It would have been cleaner to handle that in
            // SettingsFragment, but I couldn't get it to work.
            Uri uri = data.getData();
            // Persist granted access across reboots
            //int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            //getContentResolver().takePersistableUriPermission(uri, takeFlags);
            try {
                mLoggingService.writeGPX(uri);
                Toast.makeText(this, R.string.write_gpx_OK, Toast.LENGTH_SHORT).show();
            } catch (IOException ioe) {
                Log.e(TAG, "writeGPX " + ioe);
                Toast.makeText(this, R.string.write_gpx_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    // Handle permissions having been granted
    private void permissionsGranted() {
        // must be non-null before device service can be started
        BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
        if (bta == null) {
            Log.e(TAG, "No bluetooth on this device");
            Toast.makeText(this, R.string.help_no_bluetooth, Toast.LENGTH_LONG).show();
        } else if (bta.isEnabled())
            connectSonarDevice();
        else
            // Bluetooth is not enabled; prompt until it is
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
    }

    // Start looking for a sonar device. Called once permissions have been established.
    private void connectSonarDevice() {
        if (!mLoggingServiceBound) {
            Log.e(TAG, "connectSonarDevice() but the logging service isn't bound yet");
            return;
        }

        // We know the logging service is bound, and it may already be sampling. If so,
        if (mLoggingService.mSonarSampler != null && mLoggingService.mSonarSampler.getBluetoothDevice() != null) {
            Log.d(TAG, "Already connected to " + mLoggingService.mSonarSampler.getBluetoothDevice().getName());
            switchToConnectedFragment();
            return;
        }

        boolean ac = mPrefs.getBoolean(Settings.PREF_AUTOCONNECT);
        if (ac) {
            // if autoconnect is enabled, we might be able to shortcut the discovery process
            // using the paired devices, so sniff them first
            BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
            if (bta == null) {
                Toast.makeText(this, R.string.help_no_bluetooth, Toast.LENGTH_LONG).show();
                return;
            }
            Set<BluetoothDevice> pairedDevices = bta.getBondedDevices();
            if (pairedDevices.size() > 0) {
                for (BluetoothDevice device : pairedDevices) {
                    Parcelable[] uuids = device.getUuids();
                    if (uuids != null) {
                        for (Parcelable p : uuids) {
                            if (SonarSampler.SERVICE_UUID.toString().equals(p.toString())) {
                                Log.i(TAG, "opening paired device " + device.getName());
                                switchToConnectedFragment(device);
                                return;
                            }
                        }
                    }
                }
            }
        }
        // Need to find a device
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        tx.replace(R.id.fragmentContainerL, new DiscoveryFragment(ac), TAG).commit();
    }

    /**
     * Get the logging service, so it can be quizzed for state information
     *
     * @return the currently bound sonar service
     */
    LoggingService getLoggingService() {
        return mLoggingService;
    }

    void switchToConnectedFragment() {
        // Switch to the ConnectedFragment to monitor connection
        Fragment f = new ConnectedFragment();
        FragmentManager fm = getSupportFragmentManager();
        if (!fm.isDestroyed()) {
            FragmentTransaction tx = fm.beginTransaction();
            tx.replace(R.id.fragmentContainerL, f, TAG).commit();
        }
    }

    /**
     * Start interacting with the given device
     *
     * @param device device to connect to
     */
    void switchToConnectedFragment(BluetoothDevice device) {
        mLoggingService.mSonarSampler.connectToDevice(device);
        switchToConnectedFragment();
    }

    /**
     * Reconfigure the device according to the current options
     *
     * @param changes optional
     */
    void settingsChanged(Object... changes) {
        Log.d(TAG, "settingsChanged");
        int new_sensitivity = mPrefs.getInt(Settings.PREF_SENSITIVITY);
        int new_noise = mPrefs.getInt(Settings.PREF_NOISE);
        int new_range = mPrefs.getInt(Settings.PREF_RANGE);
        int new_maxSamples = mPrefs.getInt(Settings.PREF_MAX_SAMPLES);
        int new_minDeltaD = mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE); // mm
        int new_minDeltaPos = mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE); // mm
        //String new_gpxFile = mPrefs.getString(Settings.PREF_GPX_FILE);

        if (changes.length > 0) {
            switch ((String) changes[0]) {
                case Settings.PREF_SENSITIVITY:
                    new_sensitivity = (int) changes[1];
                    break;
                case Settings.PREF_NOISE:
                    new_noise = (int) changes[1];
                    break;
                case Settings.PREF_RANGE:
                    new_range = (int) changes[1];
                    break;
                case Settings.PREF_MIN_DEPTH_CHANGE:
                    new_minDeltaD = (int) changes[1];
                    break;
                case Settings.PREF_MIN_POS_CHANGE:
                    new_minDeltaPos = (int) changes[1];
                    break;
                case Settings.PREF_MAX_SAMPLES:
                    new_maxSamples = (int) changes[1];
                    break;
            }
        }

        if (mLoggingService != null) {
            /*if (!sameString(new_gpxFile, sampleFile) && mLoggingService.isLogging()) {
                mLoggingService.stopLogging();
                mLoggingService.startLogging(new_gpxFile);
            }*/

            if (new_sensitivity != sensitivity
                    || new_noise != noise
                    || new_range != range
                    || new_minDeltaD != minDeltaD
                    || new_minDeltaPos != minDeltaPos) {
                mLoggingService.mSonarSampler.configure(new_sensitivity, new_noise, new_range, new_minDeltaD / 1000f, new_minDeltaPos / 1000f);
            }
            if (new_maxSamples != maxSamples)
                mLoggingService.setMaxSamples(new_maxSamples);
        }

        sensitivity = new_sensitivity;
        noise = new_noise;
        range = new_range;
        minDeltaD = new_minDeltaD;
        minDeltaPos = new_minDeltaPos;
        maxSamples = new_maxSamples;
    }

    public void writeGPX() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/gpx+xml");
        intent.putExtra(Intent.EXTRA_TITLE, getResources().getString(R.string.help_sampleFile));

        mLoggingService.setKeepAlive(true);
        startActivityForResult(intent, REQUEST_CHOOSE_FILE);
    }
}
