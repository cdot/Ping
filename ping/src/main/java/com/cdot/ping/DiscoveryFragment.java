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

import android.bluetooth.BluetoothDevice;
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
import com.cdot.ping.samplers.SonarBluetooth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat;
import no.nordicsemi.android.support.v18.scanner.ScanCallback;
import no.nordicsemi.android.support.v18.scanner.ScanFilter;
import no.nordicsemi.android.support.v18.scanner.ScanResult;
import no.nordicsemi.android.support.v18.scanner.ScanSettings;

/**
 * Displays a list of discovered bluetooth devices and allows the user to select one.
 */
public class DiscoveryFragment extends Fragment {
    public static final String TAG = DiscoveryFragment.class.getSimpleName();
    private final List<BluetoothDevice> mDeviceList = new ArrayList<>();
    boolean mIgnoreScanResults = false; // used during error handling
    ScanSettings mScannerSettings;
    List<ScanFilter> mFilters;
    ScanBack mScanCallback;
    private List<Map<String, Object>> mDeviceViewItems;
    private DiscoveryFragmentBinding mBinding;
    private BluetoothLeScannerCompat mBLEScanner = null;
    // Automatically connect to the first compatible device found
    private boolean mAutoConnect;
    // No-arguments constructor needed when waking app from sleep
    public DiscoveryFragment() {
        // The autoconnect we pass here may be overridden by a value in the savedInstanceState
        this(true);
    }

    /**
     * @param autoconnect
     */
    DiscoveryFragment(boolean autoconnect) {
        mAutoConnect = autoconnect;
    }

    private MainActivity getMainActivity() {
        return ((MainActivity) getActivity());
    }

    // Fragment lifecycle
    // see https://developer.android.com/guide/fragments/lifecycle

    @Override // Fragment
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (savedInstanceState != null && savedInstanceState.getBoolean("autoconnect"))
            mAutoConnect = true;

        mBinding = DiscoveryFragmentBinding.inflate(inflater, container, false);
        mBinding.discoveryHelpTV.setText(mAutoConnect ? R.string.help_scan_first : R.string.help_scan_all);

        // Listener invoked when a device is selected for pairing. This is attached even
        // if there is no bluetooth, so we can test/demo it.
        AdapterView.OnItemClickListener clickListener = (adapterView, v, arg2, arg3) -> {
            BluetoothDevice device = (BluetoothDevice) mDeviceViewItems.get(arg2).get("device");
            getMainActivity().switchToConnectedFragment(device);
        };

        mBinding.devicesLV.setOnItemClickListener(clickListener);

        mIgnoreScanResults = false;

        mScannerSettings = new ScanSettings.Builder()
                .setLegacy(false)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(1000)
                .setUseHardwareBatchingIfSupported(true)
                .build();
        mFilters = new ArrayList<>();
        mFilters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SonarBluetooth.SERVICE_UUID)).build());
        mBLEScanner = BluetoothLeScannerCompat.getScanner();

        // Note that scanning with filters just doesn't work, so we have to filter manually.
        // Scanning continues ad infinitum. Can't see any obvious way to remove a device from the
        // scan list.
        Log.d(TAG, "Starting BLE scan");
        mScanCallback = new ScanBack();
        mBLEScanner.startScan(/*mFilters, mScannerSettings, */mScanCallback);
        updateDisplay();

        return mBinding.discoveryF;
    }

    @Override // Fragment
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "onDestroyView");
        if (mBLEScanner != null) {
            Log.d(TAG, "stopping BLE scanner");
            mIgnoreScanResults = true;
            mBLEScanner.stopScan(mScanCallback);
            //mBLEScanner.flushPendingScanResults(mScanCallback);
            mBLEScanner = null;
        }
    }

    @Override // Fragment
    public void onSaveInstanceState(@NonNull Bundle bundle) {
        super.onSaveInstanceState(bundle);
        bundle.putBoolean("autoconnect", mAutoConnect);
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
        mBinding.devicesLV.setAdapter(new SimpleAdapter(
                getActivity(),
                mDeviceViewItems,
                R.layout.listview_device,
                new String[]{
                        "address", "name"
                },
                new int[]{
                        R.id.deviceAddressTV, R.id.deviceNameTV
                }));
        mBinding.devicesLV.setStackFromBottom(false);
    }

    private class ScanBack extends ScanCallback {

        // Callback when scan could not be started.
        @Override
        public void onScanFailed(int errorCode) {
            switch (errorCode) {
                case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                    Log.d(TAG, "Scan failed, already started");
                    return;
                case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                    Log.d(TAG, "Scan failed, app registration failed");
                    break;
                case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                    Log.d(TAG, "Scan failed, unsupported");
                    break;
                case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                    Log.d(TAG, "Scan failed, internal error");
                    break;
            }
            if (mBLEScanner != null) { // Try again
                Log.d(TAG, "Starting BLE scan again");
                mIgnoreScanResults = false;
                mBLEScanner.startScan(/*mFilters, mScannerSettings, */mScanCallback);
            }
        }

        // Callback when a BLE advertisement has been found.
        @Override
        public void onScanResult(final int callbackType, @NonNull final ScanResult result) {
            if (mIgnoreScanResults)
                return;

            BluetoothDevice device = result.getDevice();
            // Bond states: BOND_NONE=10, BOND_BONDING=11, BOND_BONDED=12
            // This means _paired_ and NOT _bonded_; see https://piratecomm.wordpress.com/2014/01/19/ble-pairing-vs-bonding/
            Log.d(TAG, "onScanResult " + device.getAddress() + " " + device.getName() + " pairing state " + device.getBondState());
            // DIY filtering, because the system code doesn't work (see above)
            List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
            if (uuids != null && uuids.contains(new ParcelUuid(SonarBluetooth.SERVICE_UUID))) {
                if (mAutoConnect) {
                    // First device that offers the service we want. Fingers crossed!
                    getMainActivity().switchToConnectedFragment(device);
                    return;
                }
                if (!mDeviceList.contains(device)) {
                    Log.d(TAG, " Found device " + device.getAddress() + " " + device.getName());
                    mDeviceList.add(device);
                    updateDisplay();
                }
            }
        }
    }
}
