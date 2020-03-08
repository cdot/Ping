package com.cdot.ping;

import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED;
import static android.bluetooth.BluetoothDevice.ACTION_FOUND;
import static android.bluetooth.BluetoothDevice.EXTRA_DEVICE;
import static com.cdot.ping.DeviceRecord.DEVICE_ADDRESS;
import static com.cdot.ping.DeviceRecord.DEVICE_NAME;
import static com.cdot.ping.DeviceRecord.DEVICE_TYPE;

/**
 * Activity that displays a list of available bluetooth devices and allows the user to select one
 */
public class DeviceListActivity extends AppCompatActivity {
    private static final String TAG = "DeviceListActivity";

    private ListView mDeviceListView;
    List<Map<String, Object>> mDeviceList;
    ProgressDialog mScanProgressDialog;
    private boolean mReceiverRegistered = false;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ACTION_FOUND)) {
                BluetoothDevice device = intent.getParcelableExtra(EXTRA_DEVICE);
                String dn = device.getName();
                Log.d(TAG, "Found device " + dn);
                if (dn == null) {
                    Log.e(TAG, "Weirdness");
                    return;
                }
                Ping.getInstance().addDevice(device);
                updateDisplay();
            } else if (action.equals(ACTION_DISCOVERY_FINISHED)) {
                setProgressBarIndeterminateVisibility(false);
                mScanProgressDialog.cancel();
            }
        }
    };

    // Update display after lists contents changed
    private void updateDisplay() {
        // Fill the UI from our list of devices
        Resources r = getResources();
        mDeviceList = new ArrayList<>();
        for (DeviceRecord dr : Ping.getInstance().mDevices) {
            Map<String, Object> map = new HashMap<>();
            map.put(DEVICE_ADDRESS, dr.address);
            map.put(DEVICE_NAME, dr.name);
            map.put(DEVICE_TYPE, r.getStringArray(R.array.device_types)[dr.type]);
            map.put("devicePairingStatus", r.getString(dr.isPaired ? R.string.paired : R.string.not_paired));
            map.put("devicePairingAction", r.getString(dr.isPaired ? R.string.paired : R.string.click_to_pair));
            mDeviceList.add(map);
        }
        mDeviceListView.setAdapter(new SimpleAdapter(
                this,
                mDeviceList,
                R.layout.listview_device,
                new String[]{
                        DEVICE_ADDRESS, DEVICE_NAME, DEVICE_TYPE,
                        "devicePairingStatus", "devicePairingAction"
                },
                new int[]{
                        R.id.deviceAddress, R.id.deviceName, R.id.deviceType,
                        R.id.devicePairingStatus, R.id.devicePairingAction
                }));
        mDeviceListView.setStackFromBottom(false);
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();

        setResult(Activity.RESULT_CANCELED);
        if (bta == null) {
            setContentView(R.layout.no_bluetooth);
            return;
        }

        setContentView(R.layout.device_list);

        createProgressDlg();
        Button scanButton = findViewById(R.id.scan_button);
        scanButton.setTextColor(getResources().getColor(R.color.white, getTheme()));
        scanButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setProgressBarIndeterminateVisibility(true);
                setTitle(R.string.scanning);
                if (bta.isDiscovering())
                    bta.cancelDiscovery();
                bta.startDiscovery();
                if (!mScanProgressDialog.isShowing())
                    mScanProgressDialog.show();
            }
        });

        Ping.getInstance().clearDevices();
        mDeviceListView = findViewById(R.id.paired_devices);

        // Listener invoked when a device is selected for pairing
        AdapterView.OnItemClickListener clickListener = new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> adapterView, View v, int arg2, long arg3) {
                if (bta.isDiscovering())
                    bta.cancelDiscovery();
                Intent intent = new Intent();
                DeviceRecord dr = Ping.getInstance().getDevice((String) mDeviceList.get(arg2).get(DEVICE_ADDRESS));
                intent.putExtra(DEVICE_ADDRESS, dr.address);
                setResult(Activity.RESULT_OK, intent);
                finish();
            }
        };
        mDeviceListView.setOnItemClickListener(clickListener);

        registerReceiver(mReceiver, new IntentFilter(ACTION_FOUND));
        registerReceiver(mReceiver, new IntentFilter(ACTION_DISCOVERY_FINISHED));
        mReceiverRegistered = true;

        // On startup, get the bonded devices and the devices remembered in the database,
        // and add them to the view.

        Ping ping = Ping.getInstance();
        Set<BluetoothDevice> pairedDevices = bta.getBondedDevices();
        // TODO: If Bluetooth state is not STATE_ON, getBondedDevices will return an empty set.
        // After turning on Bluetooth, wait for ACTION_BT_STATE_CHANGED with STATE_ON to get the updated value.
        for (BluetoothDevice device : pairedDevices)
            ping.addDevice(device);
        updateDisplay();
        ping.addActivity(this);
    }

    /**
     * Dialog for device scan progress
     */
    private void createProgressDlg() {
        mScanProgressDialog = new ProgressDialog(this);
        mScanProgressDialog.setProgressStyle(0);
        mScanProgressDialog.setCanceledOnTouchOutside(true);
        mScanProgressDialog.setIndeterminate(false);
        mScanProgressDialog.setCancelable(true);
        mScanProgressDialog.setMessage(getResources().getString(R.string.scanning));
        mScanProgressDialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
                if (!bta.isDiscovering())
                    return false;
                bta.cancelDiscovery();
                return false;
            }
        });
        mScanProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface dialog) {
                BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
                if (bta.isDiscovering())
                    bta.cancelDiscovery();
            }
        });
    }

    protected void onDestroy() {
        super.onDestroy();
        BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
        if (bta != null && bta.isDiscovering())
            bta.cancelDiscovery();
        if (mReceiverRegistered)
            unregisterReceiver(mReceiver);
        mReceiverRegistered = false;
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finishAfterTransition();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
