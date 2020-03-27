package com.cdot.ping;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;

import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.NonNull;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.cdot.ping.databinding.MainActivityBinding;
import com.cdot.ping.devices.DemoService;
import com.cdot.ping.devices.DeviceRecord;
import com.cdot.ping.devices.LEService;

import com.cdot.ping.devices.SampleData;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

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

    // DeviceChats talk to devices
    private Map<Integer, DeviceChat> mDeviceChats = new HashMap<>();

    BluetoothAdapter mBluetoothAdapter = null;
    private int mBluetoothStatus = DeviceChat.STATE_NONE;

    private WeakReference<MainActivity> weakThis = new WeakReference<>(this);

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
                case DeviceChat.MESSAGE_STATE_CHANGE:
                    String extra = "";
                    if (msg.arg1 == DeviceChat.STATE_DISCONNECTED) {
                        String[] reasons = r.getStringArray(R.array.bt_disconnect_reason);
                        DeviceRecord dev = Ping.P.getSelectedDevice();
                        dev.isConnected = false;
                        act.updateStatus(DeviceChat.STATE_DISCONNECTED, reasons[msg.arg2]);
                        act.startAutoConnect();
                    } else if (msg.arg1 == DeviceChat.STATE_CONNECTED) {
                        // Don't need autoconnect any more, as we're connected
                        act.stopAutoConnect();
                        act.updateStatus(DeviceChat.STATE_CONNECTED);
                        DeviceRecord dev = Ping.P.getSelectedDevice();
                        dev.isConnected = true;
                        act.mDeviceChats.get(dev.type).configure(
                                Ping.P.getInt("sensitivity"), Ping.P.getInt("noise"), Ping.P.getInt("range"),
                                Ping.P.getFloat("minimumDepthChange"), Ping.P.getFloat("minimumPositionChange"));
                    } else
                        act.updateStatus(msg.arg1);
                    break;

                case DeviceChat.MESSAGE_SONAR_DATA:
                    act.onSonarSample((SampleData) msg.obj);
                    break;
            }
        }
    }

    void updateStatus(int status, Object... extra) {
        mBluetoothStatus = status;
        String s = String.format(getResources().getStringArray(R.array.bt_status)[status], extra);
        Log.d(TAG, "Bluetooth state change " + s);
        mBinding.connectionStatus.setText(s);
    }

    // Keep an instance variable pointing at this, or it gets garbage collected
    SharedPreferences.OnSharedPreferenceChangeListener mSPListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            switch (key) {
                case "selectedDevice":
                    mBinding.device.setText(Ping.P.getSelectedDevice().name);
                    break;
                case "sensitivity":
                    mBinding.sensitivity.setText(Ping.P.getText("sensitivity"));
                    break;
                case "noise":
                    mBinding.noise.setSelection(Ping.P.getInt("noise"));
                    break;
                case "range":
                    mBinding.range.setSelection(Ping.P.getInt("range"));
                    break;
            }
        }
    };

    private MainActivityBinding mBinding;

    // Invoked when the activity is first created.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Ping.setContext(this);

        mBinding = DataBindingUtil.setContentView(this, R.layout.main_activity);

        Ping.addActivity(this);

        updatePreferencesDisplay();

        // Create chat handlers for talking to different types of bluetooth device
        final Handler earwig = new ChatHandler(weakThis);

        // Map BluetoothDevice.DEVICE_TYPE_* to the relevant service
        DeviceChat demo = new DeviceChat(this, DemoService.class, earwig);
        mDeviceChats.put(BluetoothDevice.DEVICE_TYPE_UNKNOWN, demo);

        // Classic not supported - not needed as yet, LE works just fine
        // DeviceChat classic = new DeviceChat(this, BluetoothClassicService.class, earwig);
        // mDeviceChats.put(BluetoothDevice.DEVICE_TYPE_CLASSIC, classic);
        mDeviceChats.put(BluetoothDevice.DEVICE_TYPE_CLASSIC, demo); // should never be used

        DeviceChat le = new DeviceChat(this, LEService.class, earwig);
        mDeviceChats.put(BluetoothDevice.DEVICE_TYPE_LE, le);
        mDeviceChats.put(BluetoothDevice.DEVICE_TYPE_DUAL, le);

        final FloatingActionButton recordButton = mBinding.record;
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Ping.P.recordingOn = !Ping.P.recordingOn;
                Uri uri = (Ping.P.recordingOn ? Ping.P.getSampleFile() : null);
                mDeviceChats.get(Ping.P.getSelectedDevice().type).recordTo(uri);
                int dribble = (Ping.P.recordingOn) ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
                recordButton.setImageDrawable(getResources().getDrawable(dribble, getTheme()));
            }
        });

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.noise_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mBinding.noise.setAdapter(adapter);
        mBinding.noise.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                Ping.P.set("noise", pos);
                reconfigure();
            }
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        adapter = ArrayAdapter.createFromResource(this,
                R.array.range_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mBinding.range.setAdapter(adapter);
        mBinding.range.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                Ping.P.set("range", pos);
                reconfigure();
            }
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Ping.P.mSP.registerOnSharedPreferenceChangeListener(mSPListener);

        startup1_getPermissions();
    }

    private void reconfigure() {
        mDeviceChats.get(Ping.P.getSelectedDevice().type)
                .configure(Ping.P.getInt("sensitivity"), Ping.P.getInt("noise"), Ping.P.getInt("range"),
                        Ping.P.getFloat("minimumDepthChange"), Ping.P.getFloat("minimumPositionChange"));
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
                Ping.P.set("selectedDevice", Ping.P.getDevice(Ping.DEMO_DEVICE));
            startAutoConnect();
        } else if (requestCode == REQUEST_SETTINGS_CHANGED) {
            Log.d(TAG, "REQUEST_SETTINGS_CHANGED received");
            // Result coming from SettingsActivity
            if (resultCode == Activity.RESULT_OK) {
                // Location options might have changed, stop and restart sampling
                if (Ping.P.getSelectedDevice().isConnected)
                    // Device not changed, just reconfigure it
                    reconfigure();
                else {
                    // Selected device has changed. Stop services for previous devices.
                    for (Map.Entry<Integer, DeviceChat> d : mDeviceChats.entrySet())
                        d.getValue().stopServices();
                    for (DeviceRecord dr : Ping.P.getDevices())
                        dr.isConnected = false;
                    updateStatus(DeviceChat.STATE_DISCONNECTED, getResources().getString(R.string.new_device));
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
        stopAutoConnect();
        // We don't stop the service, we just ask it to stop sending us stuff
        mDeviceChats.get(Ping.P.getSelectedDevice().type).setBroadcasting(false);
    }

    /**
     * Called after onCreate and onStart, and when returning to the activity after a pause (another
     * activity came into the foreground)
     */
    @Override
    protected void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        mDeviceChats.get(Ping.P.getSelectedDevice().type).setBroadcasting(true);
        if (mBluetoothStatus != DeviceChat.STATE_CONNECTED)
            startAutoConnect();
    }

    /**
     * Activity finishing or being destroyed by the system
     */
    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        stopAutoConnect();
        mDeviceChats.get(Ping.P.getSelectedDevice().type).stopServices();
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

    // Handler for a sample coming from a DeviceChat
    private void onSonarSample(SampleData data) {
        if (data == null)
            return;

        Log.d(TAG, "Sonar sample " + data.uid + " received");

        Locale l = getResources().getConfiguration().locale;
        mBinding.battery.setText(String.format(l, "%d", data.battery));
        mBinding.depth.setText(String.format(l, "%.2f", data.depth));
        mBinding.temperature.setText(String.format(l, "%.2f", data.temperature));
        mBinding.fishDepth.setText(String.format(l, "%.2f", data.fishDepth));
        mBinding.fishType.setText(String.format(l, "%.2f", data.fishStrength));
        mBinding.strength.setText(String.format(l, "%.2f", data.strength));
        mBinding.latitude.setText(String.format(l, "%.5f", data.latitude));
        mBinding.longitude.setText(String.format(l, "%.5f", data.longitude));

        mBinding.sonarView.sample(data);
    }

    // Check if we have all required permissions. If we do, then proceed, otherwise ask for the
    // permissions. We do this even if there is no bluetooth support and we are just in demo mode.
    private void startup1_getPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : getResources().getStringArray(R.array.permissions_required)) {
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
                Toast toast = Toast.makeText(getApplicationContext(), R.string.bt_no_bluetooth, Toast.LENGTH_LONG);
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
        mBinding.device.setText(Ping.P.getSelectedDevice().name);
        mBinding.sensitivity.setText(Ping.P.getText("sensitivity"));
        mBinding.noise.setSelection(Ping.P.getInt("noise"));
        mBinding.range.setSelection(Ping.P.getInt("range"));
    }

    /* Auto connect; a timer task that tries to connect to a bluetooth device */
    static final int AUTO_CONNECT_RETRY_DELAY = 5000;

    private Timer mAutoConnectTimer;

    // Message handler on the main thread
    private static class AutoConnectTimerHandler extends Handler {
        WeakReference<MainActivity> mA;

        AutoConnectTimerHandler(WeakReference<MainActivity> act) {
            mA = act;
        }

        public void handleMessage(Message msg) {
            MainActivity self = mA.get();
            if (self.mAutoConnectTimer != null) {
                Log.d(TAG, "AutoConnect handler is waking");
                DeviceRecord dev = Ping.P.getSelectedDevice();
                Resources r = self.getResources();
                if (dev == null) {
                    self.updateStatus(DeviceChat.STATE_NONE);

                } else if (self.mBluetoothStatus == DeviceChat.STATE_NONE
                        || self.mBluetoothStatus == DeviceChat.STATE_DISCONNECTED) {
                    Log.d(TAG, "Autoconnect " + r.getStringArray(R.array.bt_device_types)[dev.type]);
                    self.updateStatus(DeviceChat.STATE_CONNECTING, dev.name);
                    // Start a connection attempt. We won't know if it succeeded until we get a message.
                    self.mDeviceChats.get(dev.type).connect(dev);
                }
            }
            super.handleMessage(msg);
        }
    }

    /**
     * Start a task to try continually try to connect to the Bluetooth device identified
     * by Ping.getDevice()
     */
    private void startAutoConnect() {
        if (mAutoConnectTimer != null)
            return;
        Log.d(TAG, "Starting autoConnect " + Ping.P.getSelectedDevice().name);
        mAutoConnectTimer = new Timer();
        final Handler ach = new AutoConnectTimerHandler(weakThis);
        TimerTask task = new TimerTask() {
            public void run() {
                if (mAutoConnectTimer == null)
                    return;
                // Signal the main thread to do something. The content of the message is irrelevant.
                Message message = new Message();
                ach.sendMessage(message);
            }
        };
        mAutoConnectTimer.schedule(task, 0, AUTO_CONNECT_RETRY_DELAY);
    }

    /**
     * Shut down the automatic connection timer/task
     */
    private void stopAutoConnect() {
        if (mAutoConnectTimer == null)
            return;
        Log.d(TAG, "Stopping autoConnect");
        mAutoConnectTimer.cancel();
        mAutoConnectTimer = null;
        updateStatus(DeviceChat.STATE_NONE);
    }
}
