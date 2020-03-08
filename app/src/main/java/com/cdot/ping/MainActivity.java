package com.cdot.ping;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;

import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
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

import com.cdot.bluetooth.BluetoothClassicService;
import com.cdot.bluetooth.BluetoothLEService;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import static com.cdot.ping.DeviceRecord.DEVICE_ADDRESS;

/**
 * The main activity in the app, displays sonar data coming from the device
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private final static int REQUEST_ENABLE_BLUETOOTH = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private static final int REQUEST_SELECT_DEVICE = 3;
    private static final int REQUEST_AUTO_CONNECT = 4;
    private static final int REQUEST_SETTINGS_CHANGED = 5;

    private Map<Integer, Chatter> mChatters = new HashMap<>();

    private boolean mBluetoothIsStarting = false; // is bluetooth started?
    private int mBluetoothStatus = Chatter.STATE_NONE;
    private SonarView mSonarView;

    private WeakReference<MainActivity> whiff = new WeakReference<>(this);

    // Handler for messages coming from Chat interfaces
    static class ChatHandler extends Handler {
        WeakReference<MainActivity> mA;

        ChatHandler(WeakReference<MainActivity> activity) {
            mA = activity;
        }

        public void handleMessage(Message msg) {
            MainActivity act = mA.get();
            switch (msg.what) {
                case Chatter.MESSAGE_STATE_CHANGE:
                    Log.d(TAG, "Received state change " + msg.arg1);
                    switch (msg.arg1) {
                        case Chatter.STATE_NONE:
                            break;
                        case Chatter.STATE_DISCONNECTED:
                            Log.d(TAG, "Received state change: disconnected");
                            String[] reasons = act.getResources().getStringArray(R.array.disconnect_reason);
                            Toast.makeText(act, reasons[msg.arg2], Toast.LENGTH_SHORT).show();
                            break;
                        case Chatter.STATE_CONNECTED:
                            Log.d(TAG, "Received state change: connected");
                            act.stopAutoConnect();
                            if (!Ping.getInstance().Demo) {
                                // Record when we last successfully connected
                                String date = new SimpleDateFormat("MM-dd HH:mm", Locale.UK).format(new Date());
                                SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(act).edit();
                                edit.putString(Ping.getInstance().getDevice().address, date);
                                edit.apply();
                                Toast toast = Toast.makeText(act, R.string.connected_ok, Toast.LENGTH_SHORT);
                                toast.setGravity(Gravity.CENTER, 0, 0);
                                toast.show();
                                Ping ping = Ping.getInstance();
                                act.mChatters.get(ping.getDevice().type).configure(ping.Sensitivity, ping.Noise, ping.Range);
                            }
                            break;
                    }
                    act.mBluetoothStatus = msg.arg1;
                    break;

                case Chatter.MESSAGE_SONAR_DATA:
                    act.sample((SonarData) msg.obj);
                    break;
            }
        }
    }

    private void sample(SonarData data) {
        //Log.d(TAG, "Received sonar data " + data.toString());
        mSonarView.sample(data);
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
        v.setText(String.format(l, "%d", data.strength));
        v = findViewById(R.id.isLand);
        v.setText(Boolean.toString(data.isLand));
    }

    // Invoked when the activity is first created.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //Toolbar toolbar = findViewById(R.id.toolbar);
        //setSupportActionBar(toolbar);
        setContentView((int) R.layout.main_activity);

        /*LinearLayout viewlayout = findViewById(R.id.sonarViewLayout);
        mSonarView = new SonarView(this);
        viewlayout.addView(mSonarView);*/
        mSonarView = findViewById(R.id.sonarView);

        BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
        if (bta == null) {
            Toast toast = Toast.makeText(getApplicationContext(), (int) R.string.no_bluetooth, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
            // Pile on, we can still play with settings and maybe some day do a demo
        }

        Ping ping = Ping.create();

        ping.addActivity(this);
        loadSettings();

        // Create chat handlers for talking to different types of bluetooth device
        final Handler earwig = new ChatHandler(whiff);

        // Map BluetoothDevice.DEVICE_TYPE_* to the relevant service
        Chatter demo = new DemoChat(earwig);
        mChatters.put(BluetoothDevice.DEVICE_TYPE_UNKNOWN, demo);

        Chatter classic = new BluetoothChat(this, BluetoothClassicService.class, earwig);
        mChatters.put(BluetoothDevice.DEVICE_TYPE_CLASSIC, classic);

        Chatter le = new BluetoothChat(this, BluetoothLEService.class, earwig);
        mChatters.put(BluetoothDevice.DEVICE_TYPE_LE, le);
        mChatters.put(BluetoothDevice.DEVICE_TYPE_DUAL, le);

        // Immediately start trying to connect
        getPermissionsAndStart();
    }

    // Check is we have all required permissions. If we do, then start, otherwise re-try the
    // permissions. We do this even if there is no bluetooth support and we are just in demo mode.
    private void getPermissionsAndStart() {
        List<String> missing = new ArrayList<>();
        for (String perm : getResources().getStringArray(R.array.required_permissions)) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED)
                missing.add(perm);
        }
        if (missing.isEmpty())
            haveRequiredPermissions();
        else
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    // Load new settings, either on startup or on return from the SettingsActivity
    private void loadSettings() {
        Ping ping = Ping.getInstance();
        ping.loadSettings(this);
        Resources r = getResources();
        TextView v = findViewById(R.id.sensitivity);
        v.setText(String.format(r.getConfiguration().locale, "%d", ping.Sensitivity * 10));
        v = findViewById(R.id.noise);
        v.setText(r.getStringArray(R.array.noise_options)[ping.Noise]);
        v = findViewById(R.id.range);
        v.setText(r.getStringArray(R.array.range_options)[ping.Range]);
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
                                        getPermissionsAndStart();
                                    }
                                })
                        .setNegativeButton(getResources().getString(R.string.cancel),
                                (DialogInterface.OnClickListener) null).create().show();
                return;
            }
        }
        if (granted)
            haveRequiredPermissions();
        else
            getPermissionsAndStart();
    }

    /**
     * Required permissions have been granted
     */
    private void haveRequiredPermissions() {
        BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
        if (bta != null) {
            if (mBluetoothIsStarting)
                return;
            mBluetoothIsStarting = true;
            // We have bluetooth support
            if (bta.isEnabled()) {
                startAutoConnect();
            } else
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
        } else if (Ping.getInstance().Demo)
            startAutoConnect();
    }

    /**
     * Handle results from startActivityForResult.
     *
     * @param requestCode
     * @param resultCode
     * @param data
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_SELECT_DEVICE:
                if (resultCode == Activity.RESULT_OK) {
                    // A new device was selected
                    Ping p = Ping.getInstance();
                    p.selectDevice(p.getDevice(data.getExtras().getString(DEVICE_ADDRESS)));
                    p.saveSettings(this);
                }
                startAutoConnect();
                break;
            case REQUEST_ENABLE_BLUETOOTH:
                // Result from haveRequiredPermissions/enable bluetooth
                if (resultCode == Activity.RESULT_OK) {
                    Log.d(TAG, "Bluetooth enabled");
                    startAutoConnect();
                } else {
                    Log.d(TAG, "Bluetooth not enabled");
                }
                break;
            case REQUEST_SETTINGS_CHANGED:
                Ping.getInstance().saveSettings(this);
                // Fall through deliberate
            case REQUEST_AUTO_CONNECT:
                loadSettings();
                startAutoConnect();
                break;
        }
    }

    static class AutoConnectMessageHandler extends Handler {
        WeakReference<MainActivity> mA;

        AutoConnectMessageHandler(WeakReference<MainActivity> act) {
            mA = act;
        }

        public void handleMessage(Message msg) {
            int toast_message;
            MainActivity self = mA.get();
            DeviceRecord dev = Ping.getInstance().getDevice();
            if (dev == null) {
                toast_message = R.string.no_device;
            } else if (self.mBluetoothStatus == Chatter.STATE_NONE
                    || self.mBluetoothStatus == Chatter.STATE_DISCONNECTED) {

                self.mChatters.get(dev.type).connect(dev);
                Resources r = self.getResources();
                Toast toast = Toast.makeText(self,
                        r.getString(R.string.connecting,
                                r.getStringArray(R.array.device_types)[dev.type], dev.name),
                        Toast.LENGTH_SHORT);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
            } else {
                super.handleMessage(msg);
                return;
            }
            super.handleMessage(msg);
        }
    }

    private Handler mAutoConnectHandler;
    private TimerTask mAutoConnectTask;
    private Timer mAutoConnectTimer;

    /**
     * Start a task to try continually try to connect to the Bluetooth device identified
     * by Ping.getDevice()
     */
    private void startAutoConnect() {
        Log.d(TAG, "Starting autoConnect");
        if (Ping.getInstance().Demo) {
            Toast toast = Toast.makeText(this,
                    getResources().getString(R.string.connecting,"Demo", ""),
                    Toast.LENGTH_SHORT);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
            mChatters.get(BluetoothDevice.DEVICE_TYPE_UNKNOWN).connect(null);
        } else {
            mAutoConnectTimer = new Timer();
            mAutoConnectHandler = new AutoConnectMessageHandler(whiff);
            mAutoConnectTask = new TimerTask() {
                public void run() {
                    Message message = new Message();
                    message.what = 1;
                    mAutoConnectHandler.sendMessage(message);
                }
            };
            mAutoConnectTimer.schedule(mAutoConnectTask, 3000, 3000);
        }
    }

    /**
     * Shut down the automatic connection task
     */
    private void stopAutoConnect() {
        Log.d(TAG, "Stopping autoConnect");
        if (mAutoConnectTimer == null)
            return;
        mAutoConnectTimer.cancel();
        mAutoConnectTimer = null;
        mAutoConnectTask.cancel();
        mAutoConnectTask = null;
    }

    /**
     * Another activity is coming into the foreground
     */
    @Override
    protected void onPause() {
        super.onPause();
        for (Map.Entry<Integer, Chatter> entry : mChatters.entrySet())
            entry.getValue().onPause();
    }

    /**
     * Called after onCreate and onStart, and when returning to the activity after a pause (another
     * activity came into the foreground)
     */
    @Override
    protected void onResume() {
        super.onResume();
        for (Map.Entry<Integer, Chatter> entry : mChatters.entrySet())
            entry.getValue().onResume();
    }

    /**
     * Activity finishing or being destroyed by the system
     */
    @Override
    protected void onDestroy() {
        stopAutoConnect();
        for (Map.Entry<Integer, Chatter> entry : mChatters.entrySet())
            entry.getValue().stopService();
        Ping ping = Ping.getInstance();
        ping.saveSettings(this);
        ping.destroy();
        super.onDestroy();

    }

    /**
     * Switch to the DeviceListActivity. Invoked by a click on the bluetooth button.
     *
     * @param view the view to switch to
     */
    public void openDeviceListActivity(View view) {
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        switch (item.getItemId()) {
            case R.id.menu_settings:
                stopAutoConnect();
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS_CHANGED);
                return true;
            case R.id.menu_select_device:
                stopAutoConnect();
                startActivityForResult(new Intent(this, DeviceListActivity.class), REQUEST_SELECT_DEVICE);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
