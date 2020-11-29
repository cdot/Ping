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
package com.cdot.ping.services;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.cdot.ping.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * Service for talking to Bluetooth LE FishFinder devices. This is a bound and started service
 * that is promoted to a foreground service when sensor samples have been requested and all
 * clients unbind. This is to allow logging to continue even when the foreground activity is
 * killed.
 * <p>
 * When an activity is bound to this service, the service acts as a conventional service. When
 * the activity is removed from the foreground, the service promotes itself to a foreground service,
 * sampling continues. When the activity comes back to the foreground, the foreground service stops,
 * and the notification associated with that service is removed.
 * <p>
 * The service can also be terminated from the notification.
 * <p>
 * Tested talking to a MicroChip IS1678S-152, and with an emulator for that device.
 * <p>
 * Communication with the device can be in three states: waiting, trying to connect, and connected.
 * <p>
 * Protocol reverse engineered from a FishFinder device and software using Wireshark. Service code
 * based on https://github.com/android/location-samples
 */
public class SonarService extends LoggingService {
    public static final String TAG = SonarService.class.getSimpleName();

    private static final String PACKAGE_NAME = SonarService.class.getPackage().getName();

    // Messages sent by the service
    public static final String ACTION_CONNECTED = PACKAGE_NAME + ".ACTION_CONNECTED";
    public static final String ACTION_DISCONNECTED = PACKAGE_NAME + ".ACTION_DISCONNECTED";

    // Sent message extras
    public static final String EXTRA_DEVICE_ADDRESS = PACKAGE_NAME + ".DEVICE_ADDRESS";
    public static final String EXTRA_DISCONNECT_REASON = PACKAGE_NAME + ".DISCONNECT_REASON";

    // DISCONNECT_REASONs sent with ACTION_DISCONNECTED/EXTRA_DISCONNECT_REASON
    public static final int REASON_CANNOT_CONNECT = 0;
    public static final int REASON_CONNECTION_LOST = 1;

    // ID bytes sent / received in every packet received from the sonar unit
    private static final byte ID0 = 83;
    private static final byte ID1 = 70;
    // Commands sent to the device
    private static final byte COMMAND_CONFIGURE = 1;

    // feet to metres
    private static final float ft2m = 0.3048f;

    // Bluetooth services BTS_* published by the FishFinder sonar device
    public static final UUID BTS_CUSTOM = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");

    // Bluetooth characteristics BTC_*
    // Note that FishFinder packages battery state in the sample packet and there is no separate
    // characteristic
    public static final UUID BTC_CUSTOM_SAMPLE = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    // The write characteristic is used for sending packets to the device. The only command I can
    // find that FishFinder devices support is "configure".
    public static final UUID BTC_CUSTOM_CONFIGURE = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");

    // Bluetooth Descriptors BTD_*
    public static final UUID BTD_CLIENT_CHARACTERISTIC_CONFIGURATION = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // Bit 0 - Notifications disabled/enabled
    // Bit 1 Indications disabled/enabled

    // Other IDs observed from device discovery, but not used in Ping. Descriptions found by googling; may not be correct

    public static final UUID BTS_GENERIC_ACCESS = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb");
    public static final UUID BTC_DEVICE_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"); // "Fish Helper"
    public static final UUID BTC_APPEARANCE = UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"); // [0x80, 0x00]

    public static final UUID BTC_PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS = UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb"); // [6, 0, -128, 0, 0, 0, -128, 12]

    public static final UUID BTS_DEVICE_INFORMATION = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb");
    public static final UUID BTC_MANUFACTURER_NAME_STRING = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb"); // "MCHP" (MicroChip)
    public static final UUID BTC_MODEL_NUMBER_STRING = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb"); // "IS1678S152"
    public static final UUID BTC_SERIAL_NUMBER_STRING = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb"); // "0000"
    public static final UUID BTC_HARDWARE_REVISION_STRING = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb"); // "5056_SPP"
    public static final UUID BTC_FIRMWARE_REVISION_STRING = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"); // "0205012"
    public static final UUID BTC_SOFTWARE_REVISION_STRING = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb"); // "0000"
    public static final UUID BTC_SYSTEM_ID = UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb"); // [0, 0, 0, 0, 0, 0, 0, 0]
    public static final UUID BTC_IEEE_CERTIFICATION = UUID.fromString("00002a2a-0000-1000-8000-00805f9b34fb"); // [0, 1, 0, 4, 0, 0, 0, 0]

    public static final UUID BTS_MICROCHIP = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455");
    public static final UUID BTC_MICROCHIP_CONNECTION_PARAMETER = UUID.fromString("49535343-6daa-4d02-abf6-19569aca69fe"); // [-120, 102, 0, 0, 0, 0, 0, 0, 0]
    public static final UUID BTC_MICROCHIP_AIR_PATCH = UUID.fromString("49535343-aca3-481c-91ec-d85e28a60318"); // has a Client Characteristic Configuration descriptor, value [0, 0]

    // Current bluetooth state
    public static final int BT_STATE_DISCONNECTED = 0;
    public static final int BT_STATE_CONNECTING = 1;
    public static final int BT_STATE_CONNECTED = 2;
    private static final String[] BT_STATE_NAMES = {
            "DISCONNECTED", "CONNECTING", "CONNECTED"
    };
    private int mBluetoothState = BT_STATE_DISCONNECTED;
    private String mBluetoothStateReason = "Not connected yet";

    // Minimum depth change between recorded samples
    public static final double MINIMUM_DELTA_DEPTH_DEFAULT = 0.5; // metres
    private double mMinDeltaDepth = MINIMUM_DELTA_DEPTH_DEFAULT;
    private Bundle mLastLoggedSample = null;
    private boolean mLoggingDisabled = false;
    private static final double MIN_DELTA_TEMPERATURE = 1.0; // degrees C

    private BluetoothGatt mBluetoothGatt;
    private GattQueue mGattQueue;

    class GattBack extends GattQueue.Callback {
        // invoked when we have connected/disconnected to/from a remote GATT server
        @Override // GattQueue.Callback
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            // BluetoothProfile.STATE_CONNECTED is handled in the superclass. The status isn't
            // moved to BT_STATE_CONNECTED until services have been discovered successfully.
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (mBluetoothGatt != gatt)
                    // mBluetoothGatt should have been set from the result of connectGatt in connect()
                    Log.e(TAG, "mBluetoothGatt and gatt differ! Not expected");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setState(BT_STATE_DISCONNECTED, "Disconnected from " + gatt.getDevice().getName());
                if (mBluetoothGatt != null & gatt.getDevice().getAddress().equals(mBluetoothGatt.getDevice().getAddress()))
                    disconnect("cleaning up");

                Intent intent = new Intent(ACTION_DISCONNECTED);
                intent.putExtra(EXTRA_DEVICE_ADDRESS, gatt.getDevice().getAddress());
                intent.putExtra(EXTRA_DISCONNECT_REASON, REASON_CONNECTION_LOST);
                sendBroadcast(intent);

                // Try to reconnect
                Log.d(TAG, "Attempting to reconnect to " + gatt.getDevice().getName());
                connect(gatt.getDevice());
            }
        }

        // Once a device has been connected, superclass starts service discovery. This callback
        // is invoked when the list of remote services, characteristics and descriptors for the
        // remote device have been updated
        @Override // GattQueue.Callback
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect("onServicesDiscovered failed");
                return;
            }

            Log.d(TAG, "Service discovered " + gatt.getDevice().getName());

            // Enable push notification from the BTC_CUSTOM service
            BluetoothGattService bgs = gatt.getService(BTS_CUSTOM);
            if (bgs == null) {
                GattQueue.describeFromCache(mBluetoothGatt); // DEBUG
                disconnect("Device does not offer service BTS_CUSTOM");
                return;
            }

            BluetoothGattCharacteristic cha = bgs.getCharacteristic(BTC_CUSTOM_SAMPLE);
            if (cha == null) {
                disconnect("Device does not offer characteristic BTC_CUSTOM_SAMPLE");
                return;
            }

            // Tell the Bluetooth stack that it should forward any received notification to the app
            gatt.setCharacteristicNotification(cha, true);
            // onCharacteristicChanged callback will be triggered if the
            // device indicates that the given characteristic has changed.

            // Tell the device to activate characteristic change notifications
            BluetoothGattDescriptor descriptor = cha.getDescriptor(BTD_CLIENT_CHARACTERISTIC_CONFIGURATION);
            if (descriptor == null) {
                disconnect("Device does not offer descriptor BTD_CLIENT_CHARACTERISTIC_CONFIGURATION");
                return;
            }

            Log.d(TAG, "Writing notification enable descriptor");
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mGattQueue.queue(new GattQueue.DescriptorWrite(descriptor));

            // Tell the world we are ready for action
            setState(BT_STATE_CONNECTED, "Connected");
            Intent intent = new Intent(ACTION_CONNECTED);
            intent.putExtra(EXTRA_DEVICE_ADDRESS, mBluetoothGatt.getDevice().getAddress());
            sendBroadcast(intent);
        }

        // triggered as a result of a remote characteristic notification
        @Override // BluetoothGattCallback
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data.length < 14 || data[0] != ID0 || data[1] != ID1)
                throw new IllegalArgumentException("Bad data block");
            // Wonder what data[5] is? Seems to be always 9
            //Log.d(TAG, "[5]=" + Integer.toHexString(data[5]));
            // There are several more bytes in the packet; wonder what they do?

            /*Log.d(TAG, "Sample received from BLE peripheral"
                    + (((data[4] & 0x8) != 0) ? " dry" : "")
                    // Convert feet to metres
                    + " D " + ((float) data[6] + (float) data[7] / 100.0f) + "ft"
                    + " S " + data[8]
                    // Convert feet to metres
                    + " fD " + ((float) data[9] + (float) data[10] / 100f)
                    + " fS " + (data[11] & 0xF)
                    + " bat " + ((data[11] >> 4) & 0xF)
                    + " T " + ((float) data[12] + (float) data[13]) + "°F");*/

            Bundle b = new Bundle();
            b.putInt("battery", (data[11] >> 4) & 0xF);
            b.putDouble("depth", (data[4] & 0x8) != 0 ? 0 : (float) (ft2m * ((double) data[6] + (double) data[7] / 100.0)));
            b.putDouble("fishDepth", ft2m * ((double) data[9] + (double) data[10] / 100.0));
            // Fish strength is in a nibble, so potentially in the range 0-15. 0 is easy, it means no mid-water
            // return. Above that, let's think. The beam is 90 degrees, so the size of the object is surely
            // proportional to the depth? Unless that is already computed in the return. The maximum size of
            // a fish is going to be a metre. At 36 meters that corresponds to 1.6 degrees of arc. Or
            // does it vary according to the range? Do we care? Just return it as a percentage.
            b.putDouble("fishStrength", 100.0 * (data[11] & 0xF) / 16.0);
            b.putDouble("strength", 100.0 * data[8] / 128.0); // Guess our max is 128
            b.putDouble("temperature", ((double) data[12] + (double) data[13] / 100.0 - 32.0) * 5.0 / 9.0);

            onNewSample(b);
        }
    }

    public class SonarServiceBinder extends Binder {
        public SonarService getService() {
            return SonarService.this;
        }
    }

    protected IBinder createBinder() {
        return new SonarServiceBinder();
    }

    @Override // LoggingService
    public String getTag() { return TAG; }

    @Override // LoggingService
    protected int getNotificationId() {
        return 87654321;
    }
    /**
     * Set the current BT_STATE for the service
     *
     * @param state  a BT_STATE
     * @param reason the reson for the change to this state
     */
    public void setState(int state, String reason) {
        Log.d(TAG, "State change to " + BT_STATE_NAMES[state] + " " + reason);
        mBluetoothState = state;
        mBluetoothStateReason = reason;
    }

    /**
     * Get the current BT_STATE for the service
     *
     * @return a BT_STATE
     */
    public int getState() {
        return mBluetoothState;
    }

    /**
     * Get the reason for the current BT_STATE for the service
     *
     * @return a reason
     */
    public String getStateReason() {
        return mBluetoothStateReason;
    }

    /**
     * Get the device the service is currently connected to
     *
     * @return the connected device
     */
    public BluetoothDevice getConnectedDevice() {
        if (mBluetoothGatt == null || mBluetoothGatt.getDevice() == null)
            return null;
        return mBluetoothGatt.getDevice();
    }

    // The system invokes this method when the service is no longer used and is being destroyed.
    // Your service should implement this to clean up any resources such as threads, registered
    // listeners, or receivers. This is the last call that the service receives.
    @Override // Service
    public void onDestroy() {
        Log.d(TAG, "onDestroy " + isRunningInForeground());
        // Release Gatt
        if (mBluetoothGatt != null) {
            Log.d(TAG, "Closing GATT in onDestroy");
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
        super.onDestroy();
    }

    /**
     * Connect to a bluetooth device. Will disconnect() currently connected device
     * first if it is not the desired device.
     *
     * @return false if the connection attempt failed. true doesn't mean it succeeded (the
     * connection may be being made by another thread) just that it hasn't failed (yet)
     */
    public boolean connect(BluetoothDevice btd) {

        Log.d(TAG, "connect(" + btd.getAddress() + ")");

        if (mBluetoothGatt != null
                && !mBluetoothGatt.getDevice().getAddress().equals(btd.getAddress())) {
            // Connected to wrong device
            mBluetoothGatt.close(); // TODO: Wait for callback!
            mBluetoothGatt = null;
        }

        if (mBluetoothGatt == null) {
            // New connection
            setState(BT_STATE_CONNECTING, "connect()ing using new BluetoothGatt");
            GattQueue.Callback cb = new GattBack();
            mBluetoothGatt = btd.connectGatt(this,
                    false, // Don't wait for device to become available
                    cb, // Callback
                    BluetoothDevice.TRANSPORT_LE);
            mGattQueue = new GattQueue(mBluetoothGatt, cb);
            return true;
        } else if (mBluetoothState >= BT_STATE_CONNECTING)
            // BT_CONNECTING or BT_CONNECTED
            return true;

        // Re-connect to a remote device after the connection has been dropped. If the
        // device is not in range, the re-connection will be triggered once the device
        // is back in range.
        Log.d(TAG, "connect()ing using existing BluetoothGatt");
        if (mBluetoothGatt.connect()) {
            // setState(BT_STATE_CONNECTED, "using existing BluetoothGatt"); // not until onStateChanged!
            return true;
        } else {
            setState(BT_STATE_DISCONNECTED, "BluetoothGatt.connect failed");
            return false;
        }
    }

    @Override // LoggingService
    protected void customiseNotification(NotificationCompat.Builder b) {
        String text = "Depth " + mLastLoggedSample.getDouble("depth");
        b.setContentTitle(getString(R.string.sonar_updated, DateFormat.getDateTimeInstance().format(new Date())))
                .setContentText(text)
                .setTicker(text);
    }

    /**
     * Disconnects an established connection, or cancels a connection attempt currently in progress.
     */
    private void disconnect(String reason) {
        Log.d(TAG, "disconnect because " + reason);
        setState(BT_STATE_DISCONNECTED, reason);
        if (mBluetoothGatt == null)
            return;
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    // Called when all clients
    @Override // LoggingService
    protected void onAllUnbound() {
        if (mBluetoothGatt != null) {
            Log.i(TAG, "Last client unbound from service and not sampling, closing service");
            // Hum, is this needed?
            stopSelf();
        }
    }

    @Override // LoggingService
    protected void onStoppedFromNotification() {
        mLoggingDisabled = true;
    }

    /**
     * Configuration reverse-engineered by sniffing packets sent by the official FishFinder software
     *
     * @param sensitivity   1..10
     * @param noise         filtering 0..4 (off, low, med, high)
     * @param range         0..6 (3, 6, 9, 18, 24, 36, auto)
     * @param minDeltaDepth min depth change, in metres
     */
    public void configure(int sensitivity, int noise, int range, double minDeltaDepth) {
        Log.d(TAG, "configure(" + sensitivity + "," + noise + "," + range + "," + minDeltaDepth + ")");
        mMinDeltaDepth = minDeltaDepth;

        if (mBluetoothState != BT_STATE_CONNECTED)
            return;

        BluetoothGattService service = mBluetoothGatt.getService(BTS_CUSTOM);
        if (service == null) {
            Log.e(TAG, "No service " + BTS_CUSTOM);
            return;
        }

        byte[] data = new byte[]{
                // http://ww1.microchip.com/downloads/en/DeviceDoc/50002466B.pdf
                ID0, ID1,
                0, 0, COMMAND_CONFIGURE, // SF?
                3, // size
                (byte) sensitivity, (byte) noise, (byte) range,
                0, 0, 0 // why the extra zeros?
        };
        // Compute checksum
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += data[i];
        }
        data[9] = (byte) (sum & 255);

        BluetoothGattCharacteristic charaWrite = service.getCharacteristic(BTC_CUSTOM_CONFIGURE);
        if (charaWrite == null) {
            Log.e(TAG, "Device does not support BTC_CUSTOM_CONFIGURE");
            return;
        }

        int charaProp = charaWrite.getProperties();
        if ((charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0)
            Log.e(TAG, "BTC_CUSTOM_CONFIGURE has no PROPERTY_WRITE");

        Log.d(TAG, "Writing BTC_CUSTOM_CONFIGURE");
        charaWrite.setValue(data);
        charaWrite.setWriteType(BluetoothGattCharacteristic./*WRITE_TYPE_DEFAULT*/WRITE_TYPE_NO_RESPONSE);
        mGattQueue.queue(new GattQueue.CharacteristicWrite(charaWrite));
    }

    /**
     * Update the last recorded sample
     */
    void onNewSample(Bundle sample) {
        if (mLoggingDisabled || !(mMustLogNextSample
                || sample.getInt("battery") != mLastLoggedSample.getInt("battery")
                || Math.abs(sample.getDouble("temperature") - mLastLoggedSample.getDouble("temperature")) >= MIN_DELTA_TEMPERATURE
                || Math.abs(sample.getDouble("depth") - mLastLoggedSample.getDouble("depth")) >= mMinDeltaDepth))
            return;

        mMustLogNextSample = false;
        mLastLoggedSample = new Bundle(sample);

        logSample(sample);
     }
}
