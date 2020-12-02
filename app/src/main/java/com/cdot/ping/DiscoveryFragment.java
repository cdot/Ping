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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
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

import com.cdot.ping.databinding.DiscoveryFragmentBinding;
import com.cdot.ping.samplers.SonarSampler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Displays a list of available bluetooth devices and allows the user to select one.
 */
public class DiscoveryFragment extends Fragment {
    public static final String TAG = DiscoveryFragment.class.getSimpleName();

    private List<BluetoothDevice> mDeviceList = new ArrayList<>();
    private List<Map<String, Object>> mDeviceViewItems;

    private DiscoveryFragmentBinding mBinding;

    private BluetoothLeScanner mBLEScanner = null;
    boolean mIgnoreScanResults = false; // used during error handling

    // Automatically connect to the first compatible device found
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
                    Log.d(TAG, mId + "Scan failed, already started");
                    return;
                case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    Log.d(TAG, mId + "Scan failed, app registration failed");
                    break;
                case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                    Log.d(TAG, mId + "Scan failed, unsupported");
                    break;
                case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                    Log.d(TAG, mId + "Scan failed, internal error");
                    break;
            }
            // Try again with a new scanner
            Log.d(TAG, mId + "Starting BLE scan again");
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
            Log.d(TAG, "onScanResult " + device.getAddress() + " " + device.getName() + " pairing state " + device.getBondState());
            // DIY filtering, because the system code doesn't work (see above)
            List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
            if (uuids != null && uuids.contains(new ParcelUuid(SonarSampler.BTS_CUSTOM))) {
                if (mAutoConnect) {
                    // First device that offers the service we want. Fingers crossed!
                    ((MainActivity) getActivity()).openDevice(device);
                    return;
                }
                if (!mDeviceList.contains(device)) {
                    Log.d(TAG, mId + " Found device " + device.getAddress() + " " + device.getName());
                    mDeviceList.add(device);
                    updateDisplay();
                }
            }
        }
    }

    // Fragment lifecycle
    // see https://developer.android.com/guide/fragments/lifecycle

    @Override // Fragment
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mBinding = DiscoveryFragmentBinding.inflate(inflater, container, false);

        mBinding.discoveryHelp.setText(mAutoConnect ? R.string.help_scan_first : R.string.help_scan_all);

        // Listener invoked when a device is selected for pairing. This is attached even
        // if there is no bluetooth, so we can test/demo it.
        AdapterView.OnItemClickListener clickListener = new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> adapterView, View v, int arg2, long arg3) {
                BluetoothDevice device = (BluetoothDevice) mDeviceViewItems.get(arg2).get("device");
                ((MainActivity) getActivity()).openDevice(device);
            }
        };

        mBinding.discoveredDevices.setOnItemClickListener(clickListener);

        // LE discovery. ScanFilter just doesn't work. A scan without filtering finds the device, a scan with
        // filtering never invokes the callback.
        BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();

        mIgnoreScanResults = false;
        // mBLEScanner is a singleton in the adapter; once it has been found once, there's no point
        // in re-finding it
        mBLEScanner = bta.getBluetoothLeScanner();

        // Note that scanning with filters just doesn't work, so we have to filter manually.
        // Scanning continues ad infinitum. Can't see any obvious way to remove a device from the
        // scan list.
        Log.d(TAG, "Starting BLE scan");
        mBLEScanner.startScan(new ScanBack("FIRST"));
        updateDisplay();

        return mBinding.discoveryFragment;
    }

    @Override // Fragment
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

    DiscoveryFragment(boolean autoconnect) {
        mAutoConnect = autoconnect;
    }

    // Update display after lists contents changed
    private void updateDisplay() {
        // Fill the UI from our list of devices
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
