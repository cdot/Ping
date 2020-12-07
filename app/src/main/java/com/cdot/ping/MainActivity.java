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
import com.cdot.ping.samplers.LocationSampler;
import com.cdot.ping.samplers.LoggingService;
import com.cdot.ping.samplers.SonarSampler;

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

    // Cache of current settings, so we can detect when they change. Initial crazy values will be
    // replaced as soon as settingsChanged is called (which it will be when the services start)
    private int sensitivity = -1;
    private int noise = -1;
    private int range = -1;
    private int minDeltaD = -1;
    private float minDeltaPos = -1;
    private String sampleFile = null;

    // Connections to services

    // A reference to the service used to get location updates. Used by Fragments.
    LoggingService mLoggingService = null;
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

            if (mLoggingService.getSampler(SonarSampler.TAG) == null)
                mLoggingService.addSampler(new SonarSampler());
            if (mLoggingService.getSampler(LocationSampler.TAG) == null)
                mLoggingService.addSampler(new LocationSampler());
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

    /*USELESS handled in ConnectedFragment
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (SonarSampler.ACTION_BT_STATE.equals(action)) {
                mSonarSamplerState = intent.getIntExtra(SonarSampler.EXTRA_STATE, R.string.reason_ok);
            }
        }
    };*/

    // Lifecycle management

//    @Override // Activity
//    protected void onSaveInstanceState(Bundle bits) {
//        super.onSaveInstanceState(bits);
//    }

    @Override // Activity
    protected void onRestoreInstanceState(Bundle bits) {
        sensitivity = mPrefs.getInt(Settings.PREF_SENSITIVITY);
        noise = mPrefs.getInt(Settings.PREF_NOISE);
        range = mPrefs.getInt(Settings.PREF_RANGE);
        minDeltaD = mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE);
        minDeltaPos = mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE);
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

        MainActivityBinding binding = MainActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.fragmentContainerL);

        getPermissions();
    }

    // See https://developer.android.com/guide/components/activities/activity-lifecycle
    @Override // Activity
    protected void onStart() {
        Log.d(TAG, "onStart");
        super.onStart();

        // Bind to the service. If the service is in foreground mode, this signals to the service
        // that since this activity is in the foreground, the service can exit foreground mode.
        // If the service is already running, it should ping us with the status.
        bindService(new Intent(getApplicationContext(), LoggingService.class), mLoggingServiceConnection, Context.BIND_AUTO_CREATE);

        mPrefs = new Settings(this);
        /*USELESS IntentFilter inf = new IntentFilter();
        inf.addAction(SonarSampler.ACTION_BT_STATE);
        registerReceiver(mBroadcastReceiver, inf);*/
    }

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
        /*USELESS unregisterReceiver(mBroadcastReceiver);*/

        super.onStop();
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

            FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
            tx.replace(R.id.fragmentContainerL, frag, SettingsFragment.TAG);
            tx.addToBackStack(null);
            tx.commit();

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
            Log.e(TAG, "findASonarDevice but the logging service isn't bound yet");
            return;
        }

        // We know the logging service is bound, and it may already be sampling. If so,
        if (mLoggingService.getConnectedDevice() != null) {
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
                            if (SonarSampler.BTS_CUSTOM.toString().equals(p.toString())) {
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

    void switchToConnectedFragment() {
        // Switch to the ConnectedFragment to monitor connection
        Fragment f = new ConnectedFragment();
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        tx.replace(R.id.fragmentContainerL, f, TAG).commit();
    }

    /**
     * Start interacting with the given device
     *
     * @param device device to connect to
     */
    void switchToConnectedFragment(BluetoothDevice device) {
        mLoggingService.connectToDevice(device);
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
        int new_minDeltaD = mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE); // mm
        float new_minDeltaPos = mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE); // mm
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
                case Settings.PREF_SAMPLE_FILE:
                    new_sampleFile = (String) changes[1];
                    break;
            }
        }

        if (mLoggingService != null) {
            if (!sameString(new_sampleFile, sampleFile) && mLoggingService.isLogging()) {
                mLoggingService.stopLogging();
                mLoggingService.startLogging(new_sampleFile);
            }

            if (new_sensitivity != sensitivity
                    || new_noise != noise
                    || new_range != range
                    || new_minDeltaD != minDeltaD) {
                SonarSampler sam = (SonarSampler) mLoggingService.getSampler(SonarSampler.TAG);
                sam.configure(new_sensitivity, new_noise, new_range, new_minDeltaD / 1000.0);
            }

            if (new_minDeltaPos != minDeltaPos) {
                LocationSampler sam = (LocationSampler) mLoggingService.getSampler(LocationSampler.TAG);
                sam.configure(new_minDeltaPos);
            }
        }

        sensitivity = new_sensitivity;
        noise = new_noise;
        range = new_range;
        minDeltaD = new_minDeltaD;
        sampleFile = new_sampleFile;
        minDeltaPos = new_minDeltaPos;
    }

    /**
     * Toggle the state of recording. Both locations and samples are added to their respective log files,
     * so long as a valid logfile location is configured.
     *
     * @return the new state of recording.
     */
    void toggleRecording() {
        if (mLoggingService == null)
            return;
        boolean on = !mLoggingService.isLogging();

        Log.d(TAG, "Recording " + on);
        if (on) {
            // Make sure required prefs are set
            if (mPrefs.getString(Settings.PREF_SAMPLE_FILE) == null) {
                Toast.makeText(this, R.string.sample_file_unset, Toast.LENGTH_LONG).show();
                on = false;
            } else if (mLoggingService != null) {
                on = mLoggingService.startLogging(mPrefs.getString(Settings.PREF_SAMPLE_FILE));
                if (!on)
                    Toast.makeText(this, R.string.could_not_start_logging, Toast.LENGTH_LONG).show();
            }
        } else
            mLoggingService.stopLogging();
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
        intent.setType("application/gpx+xml");
        intent.putExtra(Intent.EXTRA_TITLE, getResources().getString(titleR));
        // Passing data in the intent doesn't work
        //intent.putExtra(EXTRA_PREFERENCE_NAME, pref);
        mPickingFileFor = pref;
        String curVal = mPrefs.getString(pref);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, curVal);

        startActivityForResult(intent, REQUEST_CHOOSE_FILE);
    }
}
