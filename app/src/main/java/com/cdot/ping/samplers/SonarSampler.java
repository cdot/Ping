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
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.res.Resources;
import android.location.Location;
import android.util.Log;

import com.cdot.ping.R;

import java.nio.ByteBuffer;
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
public class SonarSampler extends Sampler {
    public static final String TAG = SonarSampler.class.getSimpleName();

    private static final String CLASS_NAME = SonarSampler.class.getCanonicalName();
    private static final int DoubleBYTES = Double.SIZE / 8;

    // Messages sent by the service
    public static final String ACTION_BT_STATE = CLASS_NAME + ".action_bt_state";

    // Message extras
    public static final String EXTRA_DEVICE_ADDRESS = CLASS_NAME + ".device_address";
    public static final String EXTRA_STATE = CLASS_NAME + ".state";
    public static final String EXTRA_REASON = CLASS_NAME + ".reason";

    // Keys into sample bundles.
    public static final String P_SONAR = "sonar";

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

    // Characteristic notified by PingTest to report location information
    public static final UUID BTC_CUSTOM_LOCATION = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");

    // Bluetooth Descriptors BTD_*
    public static final UUID BTD_CLIENT_CHARACTERISTIC_CONFIGURATION = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // Bit 0 - Notifications disabled/enabled
    // Bit 1 Indications disabled/enabled

    /* Other IDs observed from device discovery, but not used in Ping. Descriptions found by googling; may not be correct
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
    */

    // Current bluetooth state
    public static final int BT_STATE_DISCONNECTED = 0;
    public static final int BT_STATE_CONNECTING = 1;
    public static final int BT_STATE_CONNECTED = 2;
    private int mBluetoothState = BT_STATE_DISCONNECTED;
    private int mBluetoothStateReason;

    // Minimum depth change between recorded samples
    public static final double MINIMUM_DELTA_DEPTH_DEFAULT = 0.5; // metres
    private double mMinDeltaDepth = MINIMUM_DELTA_DEPTH_DEFAULT;
    private Sample mLastLoggedSample = null;
    private boolean mLoggingDisabled = false;
    private static final double MIN_DELTA_TEMPERATURE = 1.0; // degrees C

    private BluetoothGatt mBluetoothGatt;
    private GattQueue mGattQueue;

    // Set true if a location packet is received from PingTest - after it is set true, no more samples
    // will be accepted from LocationService
    private boolean mLocationsFromPingTest = false;

    // The most recent location passed to the sampler, must never be null
    private Location mCurrentLocation = new Location(TAG);

    // Convert a double encoded in two bytes as realpart/fracpart to a double
    private static double b2g(byte real, byte frac) {
        int r = (int) real & 0xFF, f = (int) frac & 0xFF;
        return ((double) r + (double) f / 100.0);
    }

    // Callback that extends BluetootGattCallback
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
                if (mBluetoothGatt != null & gatt != null && gatt.getDevice().getAddress().equals(mBluetoothGatt.getDevice().getAddress())) {
                    Log.d(TAG, "Disconnected from " + gatt.getDevice().getName());
                    disconnectBluetooth(R.string.reason_cleaning_up);
                }

                setBluetoothState(BT_STATE_DISCONNECTED, R.string.reason_gatt_disconnected);

                // Try to reconnect
                Log.d(TAG, "Attempting to reconnect to " + gatt.getDevice().getName());
                connectToDevice(gatt.getDevice());
            }
        }

        // Once a device has been connected, superclass starts service discovery. This callback
        // is invoked when the list of remote services, characteristics and descriptors for the
        // remote device have been updated
        @Override // GattQueue.Callback
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnectBluetooth(R.string.reason_osd_failed);
                return;
            }

            Log.d(TAG, "Service discovered " + gatt.getDevice().getName());

            // Enable push notification from the BTC_CUSTOM service
            BluetoothGattService bgs = gatt.getService(BTS_CUSTOM);
            if (bgs == null) {
                GattQueue.describeFromCache(mBluetoothGatt); // DEBUG
                disconnectBluetooth(R.string.reason_uuid_custom);
                return;
            }

            BluetoothGattCharacteristic cha = bgs.getCharacteristic(BTC_CUSTOM_SAMPLE);
            if (cha == null) {
                disconnectBluetooth(R.string.reason_uuid_custom_sample);
                return;
            }

            // Tell the Bluetooth stack that it should forward any received notification to the app
            gatt.setCharacteristicNotification(cha, true);
            // onCharacteristicChanged callback will be triggered if the
            // device indicates that the given characteristic has changed.

            // Tell the device to activate characteristic change notifications
            BluetoothGattDescriptor descriptor = cha.getDescriptor(BTD_CLIENT_CHARACTERISTIC_CONFIGURATION);
            if (descriptor == null) {
                disconnectBluetooth(R.string.reason_uuid_ccc);
                return;
            }

            Log.d(TAG, "Writing notification enable descriptor");
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mGattQueue.queue(new GattQueue.DescriptorWrite(descriptor));

            cha = bgs.getCharacteristic(BTC_CUSTOM_LOCATION);
            if (cha != null) {
                gatt.setCharacteristicNotification(cha, true);
                descriptor = cha.getDescriptor(BTD_CLIENT_CHARACTERISTIC_CONFIGURATION);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    mGattQueue.queue(new GattQueue.DescriptorWrite(descriptor));
                }
            }

            // Tell the world we are ready for action
            setBluetoothState(BT_STATE_CONNECTED, R.string.reason_ok);
        }

        // triggered as a result of a remote characteristic notification
        @Override // BluetoothGattCallback
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();

            if (BTC_CUSTOM_SAMPLE.equals(characteristic.getUuid()))
                decodeSonarSample(data);
            else if (BTC_CUSTOM_LOCATION.equals(characteristic.getUuid()))
                decodeLocationSample(data);
        }

        // Accept a location coming from PingTest
        private synchronized void decodeLocationSample(byte[] data) {
            ByteBuffer byteBuffer = ByteBuffer.allocate(Double.BYTES);
            byteBuffer.put(data, 0, Double.BYTES);
            byteBuffer.flip();
            mCurrentLocation.setLatitude(byteBuffer.getDouble());
            byteBuffer.clear();
            byteBuffer.put(data, Double.BYTES, Double.BYTES);
            byteBuffer.flip();
            mCurrentLocation.setLongitude(byteBuffer.getDouble());
            mLocationsFromPingTest = true;
        }

        private synchronized void decodeSonarSample(byte[] data) {
            // Packets we are receiving are 18 bytes, though I've only managed to work out a subset.
            // The rest don't seem to change; possibly other devices use them?

            if (data.length != 18 || data[0] != ID0 || data[1] != ID1) {
                Log.e(TAG, "Bad signature " + data.length + " " + data[0] + " " + data[1]);
                return;
            }

            int checksum = 0;
            for (int i = 0; i < 17; i++)
                checksum = (checksum + data[i]) & 0xFF;
            int packcs = (int) data[17] & 0xFF;
            if (packcs != checksum) {
                // It's ok to throw in the callback, we will see the trace in the debug but otherwise
                // it won't stop us
                Log.e(TAG, "Bad checksum " + packcs + " != " + checksum);
                return;
            }

            Sample sample = new Sample();

            // data[2], data[3] unknown, always seem to be 0
            if (data[2] != 0) Log.d(TAG, "Mysterious 2 = " + data[2]);
            if (data[3] != 0) Log.d(TAG, "Mysterious 3 = " + data[3]);

            if ((data[4] & 0xF7) != 0) Log.d(TAG, "Mysterious 4 = " + data[4]);

            sample.time = new Date().getTime();
            sample.isDry = (data[4] & 0x8) != 0;

            // data[5] unknown, seems to be always 9 (1001)
            if (data[5] != 9) Log.d(TAG, "Mysterious 5 = " + data[5]);

            sample.depth = ft2m * b2g(data[6], data[7]);

            sample.strength = (int) data[8] & 0xFF;

            sample.fishDepth = ft2m * b2g(data[9], data[10]);

            // Fish strength is in a nibble, so in the range 0-15. Just return it as a number
            sample.fishStrength = (int) data[11] & 0xF;
            sample.battery = (data[11] >> 4) & 0xF;
            sample.temperature = (b2g(data[12], data[13]) - 32.0) * 5.0 / 9.0;

            // data[14], data[15], data[16] always 0
            if (data[14] != 0) Log.d(TAG, "Mysterious 14 = " + data[14]);
            if (data[15] != 0) Log.d(TAG, "Mysterious 15 = " + data[15]);
            if (data[16] != 0) Log.d(TAG, "Mysterious 16 = " + data[16]);
            // data[17] is a checksum of data[0]..data[16]

            /*String mess = Integer.toString(data.length);
            for (int i = 0; i < data.length; i++) mess += (i == 0 ? "[" : ",") + ((int)data[i] & 0xFF);
            Log.d(TAG, mess + "]");*/

            onSampleReceived(sample);
        }
    }

    public void setLocation(Location loc) {
        if (!mLocationsFromPingTest)
            mCurrentLocation = loc;
    }

    /**
     * Set the current BT_STATE for the service
     *
     * @param state  a BT_STATE
     * @param reason the reson for the change to this state
     */
    private void setBluetoothState(int state, int reason) {
        Log.d(TAG, "State change to " + state + " " + reason);
        mBluetoothState = state;
        mBluetoothStateReason = reason;
        onBind();
    }

    /**
     * Disconnects an established connection, or cancels a connection attempt currently in progress.
     */
    private void disconnectBluetooth(int reason) {
        setBluetoothState(BT_STATE_DISCONNECTED, reason);
        if (mBluetoothGatt == null)
            return;
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Update the last recorded sample
     */
    private void onSampleReceived(Sample sample) {
        if (mLoggingDisabled || mService == null)
            return;

        if (mMustLogNextSample
                // Log if battery level has changed
                || sample.battery != mLastLoggedSample.battery
                // Log if temperature has changed enough
                || Math.abs(sample.temperature - mLastLoggedSample.temperature) >= MIN_DELTA_TEMPERATURE
                // Log if depth has changed enough, and it's not dry
                || !sample.isDry && (Math.abs(sample.depth - mLastLoggedSample.depth) >= mMinDeltaDepth)) {
            mMustLogNextSample = false;
            mLastLoggedSample = sample;

            sample.location = mCurrentLocation;

            mService.onSonarSample(sample);
        }
    }

    // Called when something is binding to the service
    @Override // Sampler
    void onBind() {
        if (mService == null) {
            Log.d(TAG, "onBind mService is null");
            return;
        }
        Log.d(TAG, "onBind sending broadcast refreshing BT state");
        Intent intent = new Intent(ACTION_BT_STATE);
        intent.putExtra(EXTRA_DEVICE_ADDRESS, mBluetoothGatt != null ? mBluetoothGatt.getDevice().getAddress() : null);
        intent.putExtra(EXTRA_STATE, mBluetoothState);
        intent.putExtra(EXTRA_REASON, mBluetoothStateReason);
        mService.sendBroadcast(intent);
    }

    @Override // implements Sampler
    public String getTag() {
        return TAG;
    }

    @Override // implements Sampler
    public String getNotificationStateText(Resources r) {
        return mLastLoggedSample == null ? r.getString(R.string.depth_unknown) : r.getString(R.string.val_depth, mLastLoggedSample.depth);
    }

    @Override // implements Sampler
    public void stopSampling() {
        Log.d(TAG, "stopped logging");
        // Note we will continue to handle the characteristic notifications coming from the device;
        // we just won't be passing them on. Handling will only finish when the service is
        // destroyed.
        mLoggingDisabled = true;
    }

    @Override // Sampler
    public void onDestroy() {
        // Release Gatt
        if (mBluetoothGatt != null) {
            Log.d(TAG, "Closing GATT in onDestroy");
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
        super.onDestroy();
    }

    @Override // Sampler
    public BluetoothDevice getConnectedDevice() {
        if (mBluetoothGatt == null)
            return null;
        return mBluetoothGatt.getDevice();
    }

    /**
     * Connect to a bluetooth device. Will disconnect() currently connected device
     * first if it is not the desired device.
     */
    @Override
    public void connectToDevice(BluetoothDevice btd) {

        if (btd == null) {
            setBluetoothState(BT_STATE_DISCONNECTED, R.string.reason_no_device);
            return;
        }

        Log.d(TAG, "connect(" + btd.getAddress() + ")");

        if (mBluetoothGatt != null && !mBluetoothGatt.getDevice().getAddress().equals(btd.getAddress())) {
            // Connected to wrong device
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }

        if (mBluetoothGatt == null) {
            // New connection
            setBluetoothState(BT_STATE_CONNECTING, R.string.reason_new_gatt);
            GattQueue.Callback cb = new GattBack();
            mBluetoothGatt = btd.connectGatt(mService,
                    false, // Don't wait for device to become available
                    cb, // Callback
                    BluetoothDevice.TRANSPORT_LE);
            mGattQueue = new GattQueue(mBluetoothGatt, cb);
            return;
        } else if (mBluetoothState >= BT_STATE_CONNECTING)
            // BT_CONNECTING or BT_CONNECTED
            return;

        // Re-connect to a remote device after the connection has been dropped. If the
        // device is not in range, the re-connection will be triggered once the device
        // is back in range.
        Log.d(TAG, "connect()ing using existing BluetoothGatt");
        if (!mBluetoothGatt.connect())
            setBluetoothState(BT_STATE_DISCONNECTED, R.string.reason_gatt_connect_failed);
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
}
