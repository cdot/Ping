package com.cdot.ping;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.Location;
import android.os.Bundle;

import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.cdot.bluetooth.BluetoothLEService;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The main activity in the app. This really should just be the UI, as all other activity should
 * happen independently in services and background threads.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    // Messages that have to be handled in onActivityResult/onRequestPermissionsResult
    private final static int REQUEST_ENABLE_BLUETOOTH = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private static final int REQUEST_SETTINGS_CHANGED = 3;

    // Chatters talk to devices
    private Map<Integer, Chatter> mChatters = new HashMap<>();

    BluetoothAdapter mBluetoothAdapter = null;
    private int mBluetoothStatus = Chatter.STATE_NONE;

    // View for seeing sample effects
    private SonarView mSonarView;

    private WeakReference<MainActivity> weakThis = new WeakReference<>(this);

    // Sample collation and serialisation
    private Sampler mSampler = new Sampler();

    private View mMainView = null;

    // Handler for messages coming from Chat interfaces
    static class ChatHandler extends Handler {
        WeakReference<MainActivity> mA;

        ChatHandler(WeakReference<MainActivity> activity) {
            mA = activity;
        }

        public void handleMessage(Message msg) {
            MainActivity act = mA.get();
            Resources r = act.getResources();
            switch (msg.what) {
                case Chatter.MESSAGE_STATE_CHANGE:
                    Log.d(TAG, "Received state change " + Chatter.stateName[msg.arg1]);
                    if (msg.arg1 == Chatter.STATE_DISCONNECTED) {
                        String[] reasons = r.getStringArray(R.array.disconnect_reason);
                        Snackbar.make(act.mMainView, reasons[msg.arg2], Snackbar.LENGTH_SHORT)
                                .show();
                        act.stopLocationSampling();
                        Ping.P.getSelectedDevice().isConnected = false;
                    } else if (msg.arg1 == Chatter.STATE_CONNECTED) {
                        // Don't need autoconnect any more, as we're connected
                        act.stopAutoConnect();
                        DeviceRecord dev = Ping.P.getSelectedDevice();
                        dev.isConnected = true;
                        Snackbar.make(act.mMainView, r.getString(R.string.connected_to,
                                r.getStringArray(R.array.device_types)[dev.type], dev.name), Snackbar.LENGTH_SHORT)
                                .show();
                        act.mChatters.get(dev.type).configure(Ping.P.getInt("sensitivity"), Ping.P.getInt("noise"), Ping.P.getInt("range"));
                        act.startLocationSampling();
                    }
                    act.mBluetoothStatus = msg.arg1;
                    break;

                case Chatter.MESSAGE_SONAR_DATA:
                    act.onSonarSample((SampleData) msg.obj);
                    break;
            }
        }
    }

    // Keep an instance variable pointing at this, or it gets garbage collected
    SharedPreferences.OnSharedPreferenceChangeListener mSPListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            TextView v;
            switch (key) {
                case "selectedDevice":
                    v = findViewById(R.id.device);
                    v.setText(Ping.P.getSelectedDevice().name);
                    break;
                case "sensitivity":
                    v = findViewById(R.id.sensitivity);
                    v.setText(Ping.P.getText("sensitivity"));
                    break;
                case "noise":
                    v = findViewById(R.id.noise);
                    v.setText(Ping.P.getText("noise"));
                    break;
                case "range":
                    v = findViewById(R.id.range);
                    v.setText(Ping.P.getText("range"));
                    break;
            }
        }
    };

    // Invoked when the activity is first created.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Ping.setContext(this);

        setContentView(R.layout.main_activity);

        mMainView = findViewById(R.id.contentLayout);
        mSonarView = findViewById(R.id.sonarView);

        Ping.addActivity(this);

        updatePreferencesDisplay();

        // Create chat handlers for talking to different types of bluetooth device
        final Handler earwig = new ChatHandler(weakThis);

        // Map BluetoothDevice.DEVICE_TYPE_* to the relevant service
        Chatter demo = new DemoChat(earwig);
        mChatters.put(BluetoothDevice.DEVICE_TYPE_UNKNOWN, demo);

        // Classic not supported - not needed as yet, LE works just fine
        // Chatter classic = new BluetoothChat(this, BluetoothClassicService.class, earwig);
        // mChatters.put(BluetoothDevice.DEVICE_TYPE_CLASSIC, classic);
        mChatters.put(BluetoothDevice.DEVICE_TYPE_CLASSIC, demo); // should never be used

        Chatter le = new BluetoothChat(this, BluetoothLEService.class, earwig);
        mChatters.put(BluetoothDevice.DEVICE_TYPE_LE, le);
        mChatters.put(BluetoothDevice.DEVICE_TYPE_DUAL, le);

        final FloatingActionButton recordButton = findViewById(R.id.record);
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Ping.P.recordingOn = !Ping.P.recordingOn;
                int dribble = (Ping.P.recordingOn) ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
                recordButton.setImageDrawable(getResources().getDrawable(dribble, getTheme()));
            }
        });

        Ping.P.mSP.registerOnSharedPreferenceChangeListener(mSPListener);

        startup1_getPermissions();
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
            String[] required = getResources().getStringArray(R.array.required_permissions);
            String[] rationale = getResources().getStringArray(R.array.rationales);
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
                                        startup1_getPermissions();
                                    }
                                })
                        .setNegativeButton(getResources().getString(R.string.cancel), null).create().show();
                return;
            }
        }
        if (granted)
            startup2_enableBluetooth();
        else
            // Try again to get permissions. We need them! They'll crack eventually.
            startup1_getPermissions();
    }

    /**
     * Handle results from startActivityForResult.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            Log.d(TAG, "REQUEST_ENABLE_BLUETOOTH received");
            // Result coming from startup2_enableBluetooth/enable bluetooth
            if (resultCode != Activity.RESULT_OK)
                // Bluetooth was not enabled; we can still continue, in demo mode
                Ping.P.set("selectedDevice", Ping.demoDevice);
            startAutoConnect();
        } else if (requestCode == REQUEST_SETTINGS_CHANGED) {
            Log.d(TAG, "REQUEST_SETTINGS_CHANGED received");
            // Result coming from SettingsActivity
            if (resultCode == Activity.RESULT_OK) {
                stopLocationSampling();
                if (Ping.P.getSelectedDevice().isConnected)
                    startLocationSampling();
                else {
                    // Selected device has changed
                    for (Map.Entry<Integer, Chatter> entry : mChatters.entrySet())
                        entry.getValue().disconnect();
                    startAutoConnect(); // will restart location sampling when it connects
                }
            }
        }
    }

    /**
     * Another activity is coming into the foreground
     */
    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
        // TODO: stop autoconnect? Or leave it to gribble away in the background?
        for (Map.Entry<Integer, Chatter> entry : mChatters.entrySet())
            entry.getValue().onPause();
    }

    /**
     * Called after onCreate and onStart, and when returning to the activity after a pause (another
     * activity came into the foreground)
     */
    @Override
    protected void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();

        for (Map.Entry<Integer, Chatter> entry : mChatters.entrySet())
            entry.getValue().onResume();

        // If we paused, did it ever get stopped?
        startLocationSampling();
    }

    /**
     * Activity finishing or being destroyed by the system
     */
    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        stopAutoConnect();
        for (Map.Entry<Integer, Chatter> entry : mChatters.entrySet())
            entry.getValue().stopServices();
        Ping.P.destroy();
        super.onDestroy();

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS_CHANGED);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // Handler for a sample coming from a Chatter
    private void onSonarSample(SampleData data) {
        if (data == null)
            return;

        //Log.d(TAG, "Sonar sample received");

        TextView v = findViewById(R.id.battery);
        Locale l = getResources().getConfiguration().locale;
        v.setText(String.format(l, "%d", data.battery));
        v = findViewById(R.id.waterDepth);
        v.setText(String.format(l, "%.2f", data.depth));
        v = findViewById(R.id.waterTemp);
        v.setText(String.format(l, "%.2f", data.temperature));
        v = findViewById(R.id.fishDepth);
        v.setText(String.format(l, "%.2f", data.fishDepth));
        v = findViewById(R.id.fishType);
        v.setText(String.format(l, "%d", data.fishType));
        v = findViewById(R.id.strength);
        v.setText(String.format(l, "%.2f", data.strength));
        v = findViewById(R.id.isLand);
        v.setText(Boolean.toString(data.isLand));

        mSonarView.sample(data);

        mSampler.addSonar(data, this);
    }


    // Check if we have all required permissions. If we do, then proceed, otherwise ask for the
    // permissions. We do this even if there is no bluetooth support and we are just in demo mode.
    private void startup1_getPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : getResources().getStringArray(R.array.required_permissions)) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED)
                missing.add(perm);
        }
        if (missing.isEmpty())
            startup2_enableBluetooth();
        else
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    // Required permissions have been granted, try to enable bluetooth
    private void startup2_enableBluetooth() {
        if (mBluetoothAdapter == null) { // can we ever get here with it non-null? OnResume?
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter == null) {
                Toast toast = Toast.makeText(getApplicationContext(), R.string.no_bluetooth, Toast.LENGTH_LONG);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
                // Pile on, we can still play with settings and maybe some day do a demo
            }
            // We have bluetooth support
            else if (!mBluetoothAdapter.isEnabled()) {
                // Bluetooth is not enabled; prompt until it is
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
                return;
            }
        }
        startAutoConnect();
    }

    // Update display of current preferences
    private void updatePreferencesDisplay() {
        TextView v;
        v = findViewById(R.id.device);
        v.setText(Ping.P.getSelectedDevice().name);
        v = findViewById(R.id.sensitivity);
        v.setText(Ping.P.getText("sensitivity"));
        v = findViewById(R.id.noise);
        v.setText(Ping.P.getText("noise"));
        v = findViewById(R.id.range);
        v.setText(Ping.P.getText("range"));
    }

    /**
     * Location sampling uses the FusedLocationProviderClient API to request location updates
     * 2 times for each stored sample, ensuring we have a reasonably up-to-date location.
     * Note that in our application, the location is not going to change rapidly - certainly less
     * than 10m/s.
     */
    // Connect to location services
    private FusedLocationProviderClient mLocationClient = null;
    private LocationCallback mLocationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult locationResult) {
            if (locationResult == null)
                return;
            //Log.d(TAG, "Location received");
            for (Location location : locationResult.getLocations()) {
                TextView v = findViewById(R.id.latitude);
                Locale l = getResources().getConfiguration().locale;
                v.setText(String.format(l, "%.5f", location.getLatitude()));
                v = findViewById(R.id.longitude);
                v.setText(String.format(l, "%.5f", location.getLongitude()));
                mSampler.addLocation(location, MainActivity.this);
            }
        }
    };
    private boolean mIsLocationSampling = false;

    private void startLocationSampling() {
        if (mIsLocationSampling)
            return;

        if (mLocationClient == null)
            mLocationClient = LocationServices.getFusedLocationProviderClient(this);

        LocationRequest lr = LocationRequest.create();
        lr.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        // Sample every second. Should be plenty.
        lr.setInterval(1000);
        //lr.setSmallestDisplacement(Ping.getMinimumPositionChange());

        try {
            mLocationClient.requestLocationUpdates(lr, mLocationCallback, null);
            mIsLocationSampling = true;
        } catch (SecurityException se) {
            throw new Error("Unexpected security exception");
        }
    }

    private void stopLocationSampling() {
        if (!mIsLocationSampling)
            return;
        mLocationClient.removeLocationUpdates(mLocationCallback);
        mIsLocationSampling = false;
    }

    /* Auto connect; a timer task that tries to connect to a bluetooth device */
    static final int AUTO_CONNECT_RETRY_DELAY = 3000;

    private Handler mAutoConnectTimerHandler;
    private TimerTask mAutoConnectTask;
    private Timer mAutoConnectTimer;

    // Message handler on the main thread
    private static class AutoConnectTimerHandler extends Handler {
        WeakReference<MainActivity> mA;

        AutoConnectTimerHandler(WeakReference<MainActivity> act) {
            mA = act;
        }

        public void handleMessage(Message msg) {
            MainActivity self = mA.get();
            DeviceRecord dev = Ping.P.getSelectedDevice();
            Resources r = self.getResources();
            if (dev == null) {
                Snackbar.make(self.mMainView, r.getString(R.string.no_device), Snackbar.LENGTH_SHORT)
                        .show();

            } else if (self.mBluetoothStatus == Chatter.STATE_NONE
                    || self.mBluetoothStatus == Chatter.STATE_DISCONNECTED) {
                self.mChatters.get(dev.type).connect(dev);
                Snackbar.make(self.mMainView, r.getString(R.string.connecting,
                        r.getStringArray(R.array.device_types)[dev.type], dev.name), Snackbar.LENGTH_SHORT)
                        .show();
            }
            super.handleMessage(msg);
        }
    }

    /**
     * Start a task to try continually try to connect to the Bluetooth device identified
     * by Ping.getDevice()
     */
    private void startAutoConnect() {
        Log.d(TAG, "Starting autoConnect " + Ping.P.getSelectedDevice().name);
        mAutoConnectTimer = new Timer();
        mAutoConnectTimerHandler = new AutoConnectTimerHandler(weakThis);
        mAutoConnectTask = new TimerTask() {
            public void run() {
                // Signal the main thread to do something. The content of the message is irrelevant.
                Message message = new Message();
                mAutoConnectTimerHandler.sendMessage(message);
            }
        };
        mAutoConnectTimer.schedule(mAutoConnectTask, AUTO_CONNECT_RETRY_DELAY, AUTO_CONNECT_RETRY_DELAY);
    }

    /**
     * Shut down the automatic connection timer/task
     */
    private void stopAutoConnect() {
        Log.d(TAG, "Stopping autoConnect");
        if (mAutoConnectTimer == null)
            return;
        mAutoConnectTimer.cancel();
        mAutoConnectTimer = null;
        // cancel() is moot, there are no loops inside the mAutoConnectTask
        mAutoConnectTask.cancel();
        mAutoConnectTask = null;
    }
}
