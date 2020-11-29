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
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.cdot.ping.databinding.MainActivityBinding;
import com.cdot.ping.services.LocationSampler;
import com.cdot.ping.services.LoggingService;
import com.cdot.ping.services.SonarSampler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The main activity in the app.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    // Messages that have to be handled in onActivityResult/onRequestPermissionsResult
    private final static int REQUEST_ENABLE_BLUETOOTH = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private static final int REQUEST_CHOOSE_FILE = 3;

    private Settings mPrefs;
    private String mPickingFileFor;

    private boolean mRecordingOn = false;

    // Cache of current settings, so we can detect when they change. Initial crazy values will be
    // replaced as soon as settingsChanged is called (which it will be when the services start)
    private int sensitivity = -1;
    private int noise = -1;
    private int range = -1;
    private double minDeltaD = -1;
    private String sampleFile = null;
    private double minDeltaP = -1;

    // Connections to services

    // A reference to the service used to get location updates.
    private LoggingService mLoggingService = null;
    // Monitors the state of the connection to the location service.
    private final ServiceConnection mLoggingServiceConnection = new ServiceConnection() {

        @Override // ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Logging service connected");
            LoggingService.LoggingServiceBinder binder = (LoggingService.LoggingServiceBinder) service;
            mLoggingService = binder.getService();
            mLoggingServiceBound = true;
            startService(new Intent(MainActivity.this, LoggingService.class));

            if (mLoggingService.getSampler(SonarSampler.TAG) == null)
                mLoggingService.addSampler(new SonarSampler());
            if (mLoggingService.getSampler(LocationSampler.TAG) == null)
                mLoggingService.addSampler(new LocationSampler());

            findASonarDevice();
        }

        @Override // ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Logging service disconnected");
            mLoggingService = null;
            mLoggingServiceBound = false;
        }
    };
    // Tracks the bound state of the service.
    private boolean mLoggingServiceBound = false;

    // Lifecycle management

    // See https://developer.android.com/guide/components/activities/activity-lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);

        MainActivityBinding binding = MainActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.fragmentContainer);

        getPermissions();
    }

    // See https://developer.android.com/guide/components/activities/activity-lifecycle
    @Override
    protected void onStart() {
        Log.d(TAG, "onStart, binding service");
        super.onStart();

        mPrefs = new Settings(this);

        // Bind to the service. If the service is in foreground mode, this signals to the service
        // that since this activity is in the foreground, the service can exit foreground mode.
        bindService(new Intent(this, LoggingService.class), mLoggingServiceConnection, Context.BIND_AUTO_CREATE);
    }

    // See https://developer.android.com/guide/components/activities/activity-lifecycle
    @Override
    protected void onStop() {
        Log.d(TAG, "onStop (unbinding service)");
        // Unbind from the services. This signals to the service that this activity is no longer
        // in the foreground, and the service can respond by promoting itself to a foreground
        // service.
        if (mLoggingServiceBound) {
            Log.d(TAG, "unbinding LoggingService");
            unbindService(mLoggingServiceConnection);
            mLoggingServiceBound = false;
        }

        super.onStop();
    }

    // See https://developer.android.com/guide/components/activities/activity-lifecycle
    @Override // Activity
    public void onDestroy() {
        Log.d(TAG, "onDestroy called");
        super.onDestroy();
        if (!mRecordingOn)
            // If the MainActivity is destroyed and logging isn't enabled, then shut the service down.
            // TODO: when the service realises the mainactivity has gone it should have shut down
            // anyway. This is overkill.
            stopService(new Intent(this, LoggingService.class));
    }

    // Handling permissions

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
            findASonarDevice();
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

    // Handle results from getPermissions() startActivityForResult.
    @Override// Activity
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            Log.d(TAG, "REQUEST_ENABLE_BLUETOOTH received");
            if (resultCode == Activity.RESULT_OK)
                findASonarDevice();
        } else if (requestCode == REQUEST_CHOOSE_FILE) {
            // Handle result from switching to the file selection activities used to select log file destinations
            // in the SettingsFragment
            Uri uri = data.getData();
            // Persist granted access across reboots
            int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
            SettingsFragment frag = (SettingsFragment) getSupportFragmentManager().findFragmentByTag(SettingsFragment.TAG);
            // Can't see any obvious way of getting this value back into the cache used in the
            // SettingsFragment, stuff it into shared preferences.....
            SharedPreferences.Editor edit = android.preference.PreferenceManager.getDefaultSharedPreferences(this).edit();
            edit.putString(mPickingFileFor, uri.toString());
            edit.apply();
            // ...and brute-force the fragment into updating the cache
            frag.onFileSelected(mPickingFileFor, uri);
        }
    }

    // Handle permissions having been granted
    private void permissionsGranted() {
        // must be non-null before device service can be started
        BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
        if (bta == null) {

        } else if (bta.isEnabled())
            findASonarDevice();
        else
            // Bluetooth is not enabled; prompt until it is
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
    }

    // Start looking for a sonar device. Called once permissions have been established.
    private void findASonarDevice() {
        if (!mLoggingServiceBound)
            return;

        SonarSampler sam = (SonarSampler) mLoggingService.getSampler(SonarSampler.TAG);
        Log.d(TAG, "findASonarDevice conn " + sam.getConnectedDevice());
        if (sam.getBluetoothState() >= SonarSampler.BT_STATE_CONNECTING) {
            // Service is CONNECTED or CONNECTING - skip discovery and jump straight
            // to connected
            openDevice(sam.getConnectedDevice());
            return;
        }

        boolean ac = mPrefs.getBoolean(Settings.PREF_AUTOCONNECT);
        if (ac) {
            // if autoconnect is enabled, we might be able to shortcut the discovery process
            // using the paired devices, so sniff them first
            BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
            if (bta == null) {
                openDevice(null);
                return;
            } else {
                Set<BluetoothDevice> pairedDevices = bta.getBondedDevices();
                if (pairedDevices.size() > 0) {
                    for (BluetoothDevice device : pairedDevices) {
                        Parcelable[] uuids = device.getUuids();
                        if (uuids != null) {
                            for (Parcelable p : uuids) {
                                if (SonarSampler.BTS_CUSTOM.toString().equals(p.toString())) {
                                    Log.i(TAG, "paired device " + device.getName());
                                    openDevice(device);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }
        // Need to find a device
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        tx.replace(R.id.fragment_container, new DiscoveryFragment(ac), TAG).commit();
    }

    // equals() when either string could be null
    private static boolean sameString(String a, String b) {
        if (a == b) return true;
        if (a == null || !a.equals(b)) return false;
        return true;
    }

    /**
     * Get the logging service, so it can be quizzed for state information
     *
     * @return the currently bound sonar service
     */
    LoggingService getLoggingService() {
        return mLoggingService;
    }

    /**
     * Start interacting with the given device
     *
     * @param device device to connect to
     */
    void openDevice(BluetoothDevice device) {
        if (device == null)
            Log.d(TAG, "no device");
        else {
            Log.d(TAG, "device selected " + device.getAddress() + " " + device.getName());

            // Remember the connected device so we can reconnect
            mPrefs.put(Settings.PREF_DEVICE, device.getName());
        }

        // Switch to the ConnectedFragment to monitor connection
        Fragment f = new ConnectedFragment(device);
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        tx.replace(R.id.fragment_container, f, TAG).commit();
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
        int new_minDeltaD = mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE); // mm
        int new_minDeltaP = mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE); // mm
        String new_sampleFile = mPrefs.getString(Settings.PREF_SAMPLE_FILE);

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
                    new_minDeltaP = (int) changes[1];
                    break;
                case Settings.PREF_SAMPLE_FILE:
                    new_sampleFile = (String) changes[1];
                    break;
            }
        }

        if (mLoggingService != null) {
            if (mRecordingOn && !sameString(new_sampleFile, sampleFile)) {
                mLoggingService.stopLogging();
                mLoggingService.startLogging(new_sampleFile);
            }

            if (new_sensitivity != sensitivity
                    || new_noise != noise
                    || new_range != range
                    || new_minDeltaD != minDeltaD) {
                SonarSampler sam = (SonarSampler) mLoggingService.getSampler(SonarSampler.TAG);
                sam.configureSonar(new_sensitivity, new_noise, new_range, new_minDeltaD / 1000.0);
            }

            if (new_minDeltaP != minDeltaP) {
                LocationSampler sam = (LocationSampler) mLoggingService.getSampler(LocationSampler.TAG);
                sam.configureLocation(new_minDeltaP / 1000.0);
            }
        }

        sensitivity = new_sensitivity;
        noise = new_noise;
        range = new_range;
        minDeltaP = new_minDeltaP;
        minDeltaD = new_minDeltaD;
        sampleFile = new_sampleFile;
    }

    /**
     * Toggle the state of recording. Both locations and samples are added to their respective log files,
     * so long as a valid logfile location is configured.
     *
     * @return the new state of recording.
     */
    boolean toggleRecording() {
        boolean on = !mRecordingOn;
        Log.d(TAG, "Recording " + on);
        if (on) {
            // Make sure required prefs are set
            if (mPrefs.getString(Settings.PREF_SAMPLE_FILE) == null) {
                Toast.makeText(this, R.string.sample_file_unset, Toast.LENGTH_LONG).show();
                on = false;
            } else if (mLoggingService != null)
                mLoggingService.startLogging(mPrefs.getString(Settings.PREF_SAMPLE_FILE));
        } else if (mRecordingOn && mLoggingService != null)
            mLoggingService.stopLogging();

        mRecordingOn = on;
        return on;
    }

    /**
     * Initiate a file selection activity, delegated from SettingsFragment
     *
     * @param pref   preference for which we are trying to get a value
     * @param titleR title to use for the file chooser
     */
    void getFile(String pref, int titleR) {
        Log.d(TAG, "initiate get file for " + pref);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/xml");
        intent.putExtra(Intent.EXTRA_TITLE, getResources().getString(titleR));
        // Passing data in the intent doesn't work
        //intent.putExtra(EXTRA_PREFERENCE_NAME, pref);
        mPickingFileFor = pref;
        String curVal = mPrefs.getString(pref);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, curVal);

        startActivityForResult(intent, REQUEST_CHOOSE_FILE);
    }
}
