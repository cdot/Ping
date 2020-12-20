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
import com.cdot.ping.samplers.SonarBluetooth;

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

    public static String ACTION_RECONFIGURE = TAG + ".reconfigure";

    MainActivityBinding mBinding; // view binding
    private Settings mPrefs; // access to shared preferences
    // Service used to log samples
    private LoggingService mLoggingService = null;
    BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (SonarBluetooth.ACTION_BT_STATE.equals(action)) {
                int state = intent.getIntExtra(SonarBluetooth.EXTRA_STATE, SonarBluetooth.BT_STATE_DISCONNECTED);
                if (state == SonarBluetooth.BT_STATE_READY)
                    sendBroadcast(new Intent(ACTION_RECONFIGURE));
                BluetoothDevice device = intent.getParcelableExtra(SonarBluetooth.EXTRA_DEVICE);
                int reason = intent.getIntExtra(SonarBluetooth.EXTRA_REASON, ConnectionObserver.REASON_UNKNOWN);
                updateSonarStateDisplay(state, reason, device);
            } else if (ACTION_RECONFIGURE.equals(action)) {
                Log.d(TAG, "Received ACTION_RECONFIGURE");
                if (mLoggingService != null) {
                    mLoggingService.configure(
                            mPrefs.getInt(Settings.PREF_SENSITIVITY),
                            mPrefs.getInt(Settings.PREF_NOISE),
                            mPrefs.getInt(Settings.PREF_RANGE),
                            mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE) / 1000f,
                            mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE) / 1000f,
                            mPrefs.getInt(Settings.PREF_SAMPLER_TIMEOUT),
                            mPrefs.getInt(Settings.PREF_MAX_SAMPLES));
                }
            }
        }
    };
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
    // true when configuration must be re-sent to the logging service
    private final boolean mMustConfigureSampler = true;

    // Lifecycle management

    private void updateSonarStateDisplay(int state, int reason, BluetoothDevice device) {
        Resources r = getResources();
        String dev = (device == null) ? r.getString(R.string.default_device_name) : device.getName();
        mBinding.deviceNameTV.setText(dev);
        String rationale = reason < 0 ? "" : r.getStringArray(R.array.bt_reason)[reason];
        String sta = String.format(r.getStringArray(R.array.bt_state)[state], rationale);
        mBinding.connectionStatusTV.setText(sta);
        Log.d(TAG, "Bluetooth state: " + sta + " " + dev);
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
        updateSonarStateDisplay(SonarBluetooth.BT_STATE_DISCONNECTED, ConnectionObserver.REASON_UNKNOWN, null);

        Log.d(TAG, "onStart binding LoggingService");

        // Bind to the service. If the service is in foreground mode, this signals to the service
        // that since this activity is in the foreground, the service can exit foreground mode.
        // If the service is already running, it should ping us with the status.
        bindService(new Intent(getApplicationContext(), LoggingService.class), mLoggingServiceConnection, Context.BIND_AUTO_CREATE);

        mPrefs = new Settings(this);
        IntentFilter inf = new IntentFilter();
        inf.addAction(SonarBluetooth.ACTION_BT_STATE);
        inf.addAction(MainActivity.ACTION_RECONFIGURE);
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
                                (dialog, which) -> getPermissions())
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
        if (mLoggingService.mSonarSampler != null && mLoggingService.getConnectedDevice() != null) {
            Log.d(TAG, "Already connected to " + mLoggingService.getConnectedDevice().getName());
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
                            if (SonarBluetooth.SERVICE_UUID.toString().equals(p.toString())) {
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
        mLoggingService.mSonarSampler.connect(device);
        switchToConnectedFragment();
    }

    @Override // Activity
    public void onBackPressed() {
        // The only place we can be coming back from is the Settings screen
        Log.d(TAG, "onBackPressed");
        sendBroadcast(new Intent(ACTION_RECONFIGURE));
        super.onBackPressed();
    }

    /**
     * Reconfigure the device according to the current options
     *
     * @param key    the key for the changed preference
     * @param newVal new value of the preference, as persisted in shared preferences
     */
    synchronized void onSettingChanged(String key, Object newVal) {
        Log.d(TAG, "onSettingChanged " + key + "=" + newVal);
        sendBroadcast(new Intent(ACTION_RECONFIGURE));
    }

    public void writeGPX() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/gpx+xml");
        intent.putExtra(Intent.EXTRA_TITLE, getResources().getString(R.string.help_sampleFile));

        // Tell the logging service to keep running even though all clients have apparently unbound
        // during the switch to the file chooser activity
        mLoggingService.setKeepAlive(true);

        startActivityForResult(intent, REQUEST_CHOOSE_FILE);
    }
}
