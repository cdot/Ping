package com.cdot.ping;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.cdot.ping.databinding.MainActivityBinding;
import com.cdot.ping.services.LocationService;
import com.cdot.ping.services.SonarService;

import java.util.ArrayList;
import java.util.List;

import static com.cdot.ping.Settings.PREF_AUTOCONNECT;

/**
 * The main activity in the app.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    // Messages that have to be handled in onActivityResult/onRequestPermissionsResult
    private final static int REQUEST_ENABLE_BLUETOOTH = 1;
    private static final int REQUEST_PERMISSIONS = 2;

    Settings mPrefs;

    // A reference to the service used to get location updates.
    private LocationService mLocationService = null;
    // Tracks the bound state of the service.
    private boolean mLocationServiceBound = false;

    SonarService mSonarService = null;
    private boolean mSonarServiceBound = false;

    private boolean mRecordingOn = false;

    // Cache of current settings, so we can detect when they change. Initial crazy values will be
    // replaced as soon as settingsChanged is called (which it will be when the services start)
    private int sensitivity = -1;
    private int noise = -1;
    ;
    private int range = -1;
    private double minDeltaD = -1;
    private String sonarSampleFile = null;
    private String locationSampleFile = null;
    private double minDeltaP = -1;

    // Invoked when the activity is first created.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);

        MainActivityBinding binding = MainActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        getPermissions();
    }

    private static boolean sameString(String a, String b) {
        if (a == b) return true;
        if (a == null || b == null || !a.equals(b)) return false;
        return true;
    }

    /**
     * Reconfigure the device according to the current options
     * @param changes optional
     */
    void settingsChanged(Object ...changes) {
        Log.d(TAG, "settingsChanged");
        int new_sensitivity = mPrefs.getInt(Settings.PREF_SENSITIVITY);
        int new_noise = mPrefs.getInt(Settings.PREF_NOISE);
        int new_range = mPrefs.getInt(Settings.PREF_RANGE);
        int new_minDeltaD = mPrefs.getInt(Settings.PREF_MIN_DEPTH_CHANGE); // mm
        int new_minDeltaP = mPrefs.getInt(Settings.PREF_MIN_POS_CHANGE); // mm
        String new_sonarSampleFile = mPrefs.getString(Settings.PREF_SONAR_SAMPLE_FILE);
        String new_locationSampleFile = mPrefs.getString(Settings.PREF_LOCATION_SAMPLE_FILE);

        if (changes.length > 0) {
            switch ((String)changes[0]) {
                case Settings.PREF_SENSITIVITY: new_sensitivity = (int)changes[1]; break;
                case Settings.PREF_NOISE: new_noise = (int)changes[1]; break;
                case Settings.PREF_RANGE: new_range = (int)changes[1]; break;
                case Settings.PREF_MIN_DEPTH_CHANGE: new_minDeltaD = (int)changes[1]; break;
                case Settings.PREF_MIN_POS_CHANGE: new_minDeltaP = (int)changes[1]; break;
                case Settings.PREF_SONAR_SAMPLE_FILE: new_sonarSampleFile = (String)changes[1]; break;
                case Settings.PREF_LOCATION_SAMPLE_FILE: new_locationSampleFile = (String)changes[1]; break;
            }
        }

        if (mSonarServiceBound && (mRecordingOn && !sameString(new_sonarSampleFile, sonarSampleFile) ||
                new_sensitivity != sensitivity || new_noise != noise || new_range != range || new_minDeltaD != minDeltaD))
            mSonarService.configure(new_sensitivity, new_noise, new_range, new_minDeltaD / 1000,
                    mRecordingOn ? mPrefs.getString(Settings.PREF_SONAR_SAMPLE_FILE) : null);

        if (mLocationServiceBound && (mRecordingOn && !sameString(new_locationSampleFile, locationSampleFile) || new_minDeltaP != minDeltaP))
            mLocationService.configure(new_minDeltaP / 1000, mRecordingOn ? new_locationSampleFile : null);

        sensitivity = new_sensitivity;
        noise = new_noise;
        range = new_range;
        minDeltaP = new_minDeltaP;
        minDeltaD = new_minDeltaD;
        locationSampleFile = new_locationSampleFile;
        sonarSampleFile = new_sonarSampleFile;
    }

    // The final call you receive before your activity is destroyed. This can happen either
    // because the activity is finishing (someone called finish() on it, or because the system
    // is temporarily destroying this instance of the activity to save space.
    public void onDestroy() {
        Log.d(TAG, "onDestroy called");
        super.onDestroy();
        if (!mRecordingOn)
            stopService(new Intent(this, SonarService.class));
    }

    /**
     * Toggle the state of recording. Both locations and samples are added to their respective log files,
     * so long as a valid logfile location is configured.
     *
     * @return the new state of recording.
     */
    public boolean toggleRecording() {
        mRecordingOn = !mRecordingOn;
        settingsChanged();
        return mRecordingOn;
    }

    /**
     * Handle the result of requestPermissions()
     *
     * @param requestCode  The request code passed in to requestPermissions
     * @param permissions  The requested permissions
     * @param grantResults The grant results for the corresponding permissions which is either
     *                     PackageManager.PERMISSION_GRANTED or PackageManager.PERMISSION_DENIED.
     */
    @Override
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
            startDiscovery();
        else
            // Try again to get permissions. We need them! They'll crack eventually.
            getPermissions();
    }

    @Override
    protected void onStop() {
        // Unbind from the services. This signals to the service that this activity is no longer
        // in the foreground, and the service can respond by promoting itself to a foreground
        // service.
        if (mLocationServiceBound) {
            unbindService(mLocationServiceConnection);
            mLocationServiceBound = false;
        }
        if (mSonarServiceBound) {
            unbindService(mSonarServiceConnection);
            mSonarServiceBound = false;
        }
        super.onStop();
    }

    // Monitors the state of the connection to the service.
    private final ServiceConnection mLocationServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            LocationService.LocalBinder binder = (LocationService.LocalBinder) service;
            mLocationService = binder.getService();
            mLocationServiceBound = true;
            settingsChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mLocationService = null;
            mLocationServiceBound = false;
        }
    };

    private final ServiceConnection mSonarServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            SonarService.LocalBinder binder = (SonarService.LocalBinder) service;
            mSonarService = binder.getService();
            mSonarServiceBound = true;
            startDiscovery();
        }

        public void onServiceDisconnected(ComponentName componentName) {
            mSonarService = null;
            mSonarServiceBound = false;
        }
    };

    // Will be followed by onResume
    @Override
    protected void onStart() {
        Log.d(TAG, "onStart");
        mPrefs = new Settings(this);
        super.onStart();

        // Bind to the services. If a service is in foreground mode, this signals to the service
        // that since this activity is in the foreground, the service can exit foreground mode.

        //bindService(new Intent(this, LocationService.class), mLocationServiceConnection, Context.BIND_AUTO_CREATE);
        bindService(new Intent(this, SonarService.class), mSonarServiceConnection, Context.BIND_AUTO_CREATE);

        // Note that we always create the services, even if bluetooth isn't available for the
        // device service.
    }

    // Check if we have all required permissions. If we do, then proceed, otherwise ask for the
    // permissions. We do this even if there is no bluetooth support and we are just in demo mode.
    void getPermissions() {
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

    /**
     * Handle results from getPermissions() startActivityForResult.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            Log.d(TAG, "REQUEST_ENABLE_BLUETOOTH received");
            if (resultCode == Activity.RESULT_OK)
                startDiscovery();
        }
    }

    // Handle permissions having been granted
    private void permissionsGranted() {
        // must be non-null before device service can be started
        BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
        if (bta == null) {
            // TODO: change this from a toast to something more permanent
            Toast toast = Toast.makeText(getApplicationContext(), R.string.bt_no_bluetooth, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else if (bta.isEnabled())
            startDiscovery();
        else
            // Bluetooth is not enabled; prompt until it is
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
    }

    private void startDiscovery() {
        if (BluetoothAdapter.getDefaultAdapter().isEnabled() && mSonarServiceBound) {
            Fragment f;

            if (mSonarService.getState() >= SonarService.BT_STATE_CONNECTING)
                // CONNECTED or CONNECTING
                f = new ConnectedFragment(mSonarService.getConnectedDevice());
            else
                // Need to pick a device
                f = new DiscoveryFragment(mPrefs.getBoolean(Settings.PREF_AUTOCONNECT));

            FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
            tx.replace(R.id.fragment, f, TAG).commit();
        }
    }
}
