package com.cdot.ping;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.SimpleAdapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.cdot.ping.databinding.DiscoveryFragmentBinding;
import com.cdot.ping.services.SonarService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fragment that displays a list of available bluetooth devices and allows the user to select one.
 */
public class DiscoveryFragment extends Fragment {
    private static final String TAG = DiscoveryFragment.class.getSimpleName();

    private List<BluetoothDevice> mDeviceList = new ArrayList<>();
    private List<Map<String, Object>> mDeviceViewItems;

    private DiscoveryFragmentBinding mBinding;
    private Settings mPrefs;

    private BluetoothLeScanner mBLEScanner = null;
    boolean mIgnoreScanResults = false; // used during error handling

    // Autoimatically connect to the first compatible device found
    private boolean mAutoConnect;

    private class ScanBack extends ScanCallback {
        String mId;

        ScanBack(String id) {
            mId = id;
        }

        // Callback when scan could not be started.
        public void onScanFailed(int errorCode) {
            switch (errorCode) {
                case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                    Log.d(TAG, mId+"Scan failed, already started");
                    return;
                case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    Log.d(TAG, mId+"Scan failed, app registration failed");
                    break;
                case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                    Log.d(TAG, mId+"Scan failed, unsupported");
                    break;
                case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                    Log.d(TAG, mId+"Scan failed, internal error");
                    break;
            }
            // Try again with a new scanner
            BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
            Log.d(TAG, mId+"Starting BLE scan again");
            mIgnoreScanResults = false;
            mBLEScanner.startScan(new ScanBack("RESTART"));
        }

        public void onBatchScanResults(List<ScanResult> results) {
            // Callback when batch results are delivered.
            throw new Error("NEVER CALLED");
        }

        // Callback when a BLE advertisement has been found.
        public void onScanResult(int callbackType, ScanResult result) {
            if (mIgnoreScanResults)
                return;

            BluetoothDevice device = result.getDevice();
            // Bond states: BOND_NONE=10, BOND_BONDING=11, BOND_BONDED=12
            // This means _paired_ and NOT _bonded_; see https://piratecomm.wordpress.com/2014/01/19/ble-pairing-vs-bonding/
            Log.d(TAG, mId+" onScanResult " + device.getAddress() + " " + device.getName() + " pairing state " + device.getBondState());
            // DIY filtering, because the system code doesn't work (see above)
            List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
            if (uuids != null && uuids.contains(new ParcelUuid(SonarService.BTS_CUSTOM))) {
                if (mAutoConnect) {
                    // First device that offers the service we want. Fingers crossed!
                    openDevice(device);
                    return;
                }
                if (!mDeviceList.contains(device)) {
                    Log.d(TAG, mId+" Found device " + device.getAddress() + " " + device.getName());
                    mDeviceList.add(device);
                    updateDisplay();
                }
            }
        }
    }

    DiscoveryFragment(boolean autoconnect) {
        mAutoConnect = autoconnect;
    }

    private void openDevice(BluetoothDevice device) {
        Log.d(TAG, "device selected " + device.getAddress() + " " + device.getName());
        // Remember the connected device so we can reconnect if that option is selected
        (new Settings(getActivity())).put(Settings.PREF_DEVICE, device.getName());

        // Switch to the ConnectedFragment to monitor connection
        Fragment f = new ConnectedFragment(device);
        FragmentTransaction tx = getActivity().getSupportFragmentManager().beginTransaction();
        tx.replace(R.id.fragment, f, TAG).commit();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mBinding = DiscoveryFragmentBinding.inflate(inflater, container, false);
        mPrefs = new Settings(getActivity());

        mBinding.discoveryHelp.setText(mAutoConnect ? R.string.help_scan_first : R.string.help_scan_all);

        // Listener invoked when a device is selected for pairing. This is attached even
        // if there is no bluetooth, so we can test/demo it.
        AdapterView.OnItemClickListener clickListener = new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> adapterView, View v, int arg2, long arg3) {
                BluetoothDevice device = (BluetoothDevice)mDeviceViewItems.get(arg2).get("device");
                openDevice(device);
            }
        };

        mBinding.discoveredDevices.setOnItemClickListener(clickListener);

        // LE discovery. ScanFilter just doesn't work. A scan without filtering finds the device, a scan with
        // filtering never invokes the callback.
        BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
        // mBLEScanner is a singleton in the adapter; once it has been found once, there's no point
        // in re-finding it
        mBLEScanner = bta.getBluetoothLeScanner();

        // Note that scanning with filters just doesn't work, so we have to filter manually.
        // Scanning continues ad infinitum. Can't see any obvious way to remove a device from the
        // scan list.
        Log.d(TAG, "Starting BLE scan");
        mIgnoreScanResults = false;
        mBLEScanner.startScan(new ScanBack("FIRST"));
        updateDisplay();

        return mBinding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "onDestroyView");
        if (mBLEScanner != null) {
            Log.d(TAG, "stopping BLE scanner");
            mIgnoreScanResults = true;
            mBLEScanner.stopScan(new ScanBack("DEAD"));
            mBLEScanner.flushPendingScanResults(new ScanBack("FLUSH"));
            mBLEScanner = null;
        }
    }

    // Update display after lists contents changed
    private void updateDisplay() {
        // Fill the UI from our list of devices
        Resources r = getResources();
        mDeviceViewItems = new ArrayList<>();
        for (BluetoothDevice dr : mDeviceList) {
            Map<String, Object> map = new HashMap<>();
            map.put("address", dr.getAddress());
            map.put("name", dr.getName());
            map.put("device", dr);
            mDeviceViewItems.add(map);
        }
        mBinding.discoveredDevices.setAdapter(new SimpleAdapter(
                getActivity(),
                mDeviceViewItems,
                R.layout.listview_device,
                new String[]{
                        "address", "name"
                },
                new int[]{
                        R.id.deviceAddress, R.id.deviceName
                }));
        mBinding.discoveredDevices.setStackFromBottom(false);
    }
}
