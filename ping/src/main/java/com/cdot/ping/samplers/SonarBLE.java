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
package com.cdot.ping.samplers;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.util.Log;

import androidx.annotation.NonNull;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.RequestQueue;
import no.nordicsemi.android.ble.callback.FailCallback;
import no.nordicsemi.android.ble.callback.SuccessCallback;
import no.nordicsemi.android.ble.callback.profile.ProfileDataCallback;

/**
 * BLE interface to FishFinder devices.
 * Isolates the actual Bluetooth implementation (LE) from the sample handling
 */
public class SonarBLE extends BleManager implements SonarBluetooth.BTImplementation {
    public static final String TAG = SonarBLE.class.getSimpleName();

    // Sample data handlers
    ProfileDataCallback mLocationHandler, mSonarHandler;

    // Client characteristics
    private BluetoothGattCharacteristic mSampleCharacteristic, mConfigureCharacteristic, mLocationCharacteristic;

    public SonarBLE(@NonNull final LoggingService service) {
        super(service);
    }

    @Override // SonarCommon.BTImplementation
    public void setCommon(SonarBluetooth observer) {
        setConnectionObserver(observer);
        mSonarHandler = observer.getSonarHandler();
        mLocationHandler = observer.getLocationHandler();
    }

    @Override // SonarCommon.BTImplementation
    public void sendConfiguration(byte[] data) {
        writeCharacteristic(mConfigureCharacteristic, data)
                .done(device -> Log.d(TAG, "Configuration written to " + device.getName()))
                .enqueue();
    }

    @Override // SonarCommon.BTImplementation
    public SonarBluetooth.Request connectToDevice(BluetoothDevice device) {
        return new RequestConnector(connect(device)
                .timeout(SonarBluetooth.BT_CONNECT_TIMEOUT)
                .useAutoConnect(true)
                .retry(SonarBluetooth.BT_CONNECT_RETRIES, SonarBluetooth.BT_CONNECT_RETRY_DELAY));
    }

    @Override // SonarCommon.BTImplementation
    public SonarBluetooth.Request disconnectFromDevice() {
        return new RequestConnector(disconnect());
    }

    // Connector between Nordic BLE Request (which are package-private) and BTRequest, which
    // exposes them to *this* package.
    class RequestConnector implements SonarBluetooth.Request {
        no.nordicsemi.android.ble.Request mR;

        RequestConnector(no.nordicsemi.android.ble.Request r) {
            mR = r;
        }

        public SonarBluetooth.Request done(@NonNull final SuccessCallback callback) {
            mR.done(callback);
            return this;
        }

        public SonarBluetooth.Request fail(@NonNull final FailCallback callback) {
            mR.fail(callback);
            return this;
        }

        public void enqueue() {
            mR.enqueue();
        }
    }

    @NonNull
    @Override // BLEManager
    protected BleManagerGattCallback getGattCallback() {
        return new SonarGattCallback();
    }

    @Override // BleManager
    protected boolean shouldClearCacheWhenDisconnected() {
        return true;
    }

    // Monitoring the connection to the sonar device
    private class SonarGattCallback extends BleManagerGattCallback {

        // This method will be called when the device is connected and services are discovered.
        // You need to obtain references to the characteristics and descriptors that you will use.
        // Return true if all required services are found, false otherwise.
        @Override // BleManagerGattCallback
        public boolean isRequiredServiceSupported(@NonNull final BluetoothGatt gatt) {
            final BluetoothGattService service = gatt.getService(SonarBluetooth.SERVICE_UUID);
            if (service == null)
                return false;

            mSampleCharacteristic = service.getCharacteristic(SonarBluetooth.SAMPLE_CHARACTERISTIC_UUID);
            if (mSampleCharacteristic == null) {
                Log.e(TAG, "No sample characteristic");
                return false;
            }

            mConfigureCharacteristic = service.getCharacteristic(SonarBluetooth.CONFIGURE_CHARACTERISTIC_UUID);
            if (mConfigureCharacteristic == null) {
                Log.e(TAG, "No configure characteristic");
                return false;
            }

            // Validate properties
            if ((mSampleCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                Log.e(TAG, "Can't get sample notifications");
                return false;
            }

            if ((mConfigureCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                Log.e(TAG, "Can't write configurations");
                return false;
            }

            mConfigureCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

            mLocationCharacteristic = service.getCharacteristic(SonarBluetooth.LOCATION_CHARACTERISTIC_UUID);
            if (mLocationCharacteristic != null
                    && (mLocationCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                Log.e(TAG, "Can't get location notifications");
                mLocationCharacteristic = null;
            }

            Log.d(TAG, "All required services have been found");
            return true;
        }

        @Override // BleManagerGattCallback
        protected void initialize() {
            RequestQueue q = beginAtomicRequestQueue()
                    //.add(enableNotifications(mSampleCharacteristic));
                    .add(enableIndications(mSampleCharacteristic));
            //setNotificationCallback(mSampleCharacteristic)
            //        .with(mSonarHandler);
            setIndicationCallback(mSampleCharacteristic)
                    .with(mSonarHandler);
            if (mLocationCharacteristic != null) {
                setNotificationCallback(mLocationCharacteristic)
                        .with(mLocationHandler);
                q.add(enableNotifications(mLocationCharacteristic));
            }
            q.done(device -> Log.d(TAG, "Notification enabled")).enqueue();
            Log.d(TAG, "Hooked up notification listeners");
        }

        @Override // BleManagerGattCallback
        protected void onDeviceDisconnected() {
            Log.d(TAG, "Device disconnected");
            mSampleCharacteristic = null;
            mConfigureCharacteristic = null;
        }
    }
}