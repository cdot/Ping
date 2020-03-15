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

import com.cdot.devices.DeviceRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Activity that displays a list of available bluetooth devices and allows the user to select one.
 * Used from the SettingsActivity.
 */
public class DeviceListActivity extends AppCompatActivity {
    private static final String TAG = "DeviceListActivity";

    private ListView mDeviceListView;
    private List<Map<String, Object>> mDeviceList;
    private ProgressDialog mScanProgressDialog;
    private boolean mReceiverRegistered = false;
    private BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

    // Receiver for broadcasts from device discovery. Only registered if there is a bluetooth adapter
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int t = device.getType();
                // Only interested in LE devices
                if (t == BluetoothDevice.DEVICE_TYPE_LE || t == BluetoothDevice.DEVICE_TYPE_DUAL) {
                    Log.d(TAG, "Found device " + device.getName());
                    Ping.P.addDevice(device);
                    updateDisplay();
                } else
                    Log.d(TAG, "Ignoring non-LE device " + device.getName());
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
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
        for (DeviceRecord dr : Ping.P.getDevices()) {
            Map<String, Object> map = new HashMap<>();
            map.put(DeviceRecord.DEVICE_ADDRESS, dr.address);
            map.put(DeviceRecord.DEVICE_NAME, dr.name);
            map.put(DeviceRecord.DEVICE_TYPE, r.getStringArray(R.array.bt_device_types)[dr.type]);
            map.put("devicePairingStatus", r.getString(dr.isPaired ? R.string.bt_paired : R.string.bt_not_paired));
            map.put("devicePairingAction", r.getString(dr.isPaired ? R.string.bt_paired : R.string.bt_click_to_pair));
            mDeviceList.add(map);
        }
        mDeviceListView.setAdapter(new SimpleAdapter(
                this,
                mDeviceList,
                R.layout.listview_device,
                new String[]{
                        DeviceRecord.DEVICE_ADDRESS, DeviceRecord.DEVICE_NAME, DeviceRecord.DEVICE_TYPE,
                        "devicePairingStatus", "devicePairingAction"
                },
                new int[]{
                        R.id.deviceAddress, R.id.deviceName, R.id.deviceType,
                        R.id.devicePairingStatus, R.id.devicePairingAction
                }));
        mDeviceListView.setStackFromBottom(false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setResult(Activity.RESULT_CANCELED);

        setContentView(R.layout.device_list);

        createProgressDlg();
        Button scanButton = findViewById(R.id.scan_button);
        scanButton.setTextColor(getResources().getColor(R.color.white, getTheme()));
        if (mBluetoothAdapter != null) {
            scanButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    setProgressBarIndeterminateVisibility(true);
                    setTitle(R.string.bt_discovering);
                    if (mBluetoothAdapter.isDiscovering())
                        mBluetoothAdapter.cancelDiscovery();
                    mBluetoothAdapter.startDiscovery();
                    if (!mScanProgressDialog.isShowing())
                        mScanProgressDialog.show();
                }
            });
        }

        // Listener invoked when a device is selected for pairing. This is attached even
        // if there is no bluetooth, so we can test/demo it.
        AdapterView.OnItemClickListener clickListener = new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> adapterView, View v, int arg2, long arg3) {
                if (mBluetoothAdapter != null && mBluetoothAdapter.isDiscovering())
                    mBluetoothAdapter.cancelDiscovery();
                Intent intent = new Intent();
                DeviceRecord dr = Ping.P.getDevice((String) mDeviceList.get(arg2).get(DeviceRecord.DEVICE_ADDRESS));
                intent.putExtra(DeviceRecord.DEVICE_ADDRESS, dr.address);
                setResult(Activity.RESULT_OK, intent);
                finish();
            }
        };

        mDeviceListView = findViewById(R.id.paired_devices);
        mDeviceListView.setOnItemClickListener(clickListener);

        Ping.P.clearDevices(); // except demoDevice

        if (mBluetoothAdapter != null) {
            registerReceiver(mReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            registerReceiver(mReceiver, new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));
            mReceiverRegistered = true;

            // Get the bonded devices and add/update them in the view.
            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : pairedDevices)
                Ping.P.addDevice(device);
        }

        updateDisplay();
        Ping.addActivity(this);
    }

    // Dialog for device scan progress (only activated if mBluetoothAdapter is non-null
    private void createProgressDlg() {
        mScanProgressDialog = new ProgressDialog(this);
        mScanProgressDialog.setProgressStyle(0);
        mScanProgressDialog.setCanceledOnTouchOutside(true);
        mScanProgressDialog.setIndeterminate(false);
        mScanProgressDialog.setCancelable(true);
        mScanProgressDialog.setMessage(getResources().getString(R.string.bt_discovering));
        mScanProgressDialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                if (!mBluetoothAdapter.isDiscovering())
                    return false;
                mBluetoothAdapter.cancelDiscovery();
                return false;
            }
        });
        mScanProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface dialog) {
                if (mBluetoothAdapter.isDiscovering())
                    mBluetoothAdapter.cancelDiscovery();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBluetoothAdapter != null && mBluetoothAdapter.isDiscovering())
            mBluetoothAdapter.cancelDiscovery();
        mBluetoothAdapter = null;
        if (mReceiverRegistered)
            unregisterReceiver(mReceiver);
        mReceiverRegistered = false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finishAfterTransition();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
