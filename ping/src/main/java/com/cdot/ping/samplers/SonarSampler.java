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
import android.content.Intent;
import android.content.res.Resources;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;

import com.cdot.ping.R;

import java.util.Date;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.callback.profile.ProfileDataCallback;
import no.nordicsemi.android.ble.data.Data;
import no.nordicsemi.android.ble.observer.ConnectionObserver;

/**
 * Interface to Bluetooth LE FishFinder devices. Supports Erchang
 */
public class SonarSampler extends BleManager implements ConnectionObserver {
    public static final String TAG = SonarSampler.class.getSimpleName();

    // Bluetooth state, set by the ConnectionObserver callbacks
    public static final int BT_STATE_DISCONNECTED = 0;
    public static final int BT_STATE_CONNECTING = 1;
    public static final int BT_STATE_CONNECTED = 2;
    public static final int BT_STATE_READY = 3;
    public static final int BT_STATE_DISCONNECTING = 4;
    public static final int BT_STATE_CONNECT_FAILED = 5;

    public static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    // Minimum depth change between recorded samples
    public static final float MINIMUM_DELTA_DEPTH_DEFAULT = 0.5f; // metres
    static final UUID SAMPLE_CHARACTERISTIC_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    static final UUID CONFIGURE_CHARACTERISTIC_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final String CLASS_NAME = SonarSampler.class.getCanonicalName();
    // Messages sent by the service
    public static final String ACTION_BT_STATE = CLASS_NAME + ".action_bt_state";
    // Message extras
    public static final String EXTRA_DEVICE = CLASS_NAME + ".device";
    public static final String EXTRA_STATE = CLASS_NAME + ".state";
    public static final String EXTRA_REASON = CLASS_NAME + ".reason";

    // ID bytes sent / received in every packet received from the sonar unit
    private static final byte ID0 = (byte) 'S'; // guessing "S for Sonar"
    private static final byte ID1 = (byte) 'F'; // maybe "F for FishFinder"

    // Commands sent to the device
    private static final byte COMMAND_CONFIGURE = 1;

    // feet to metres. Bloody Americans, wake up and join the 20th Century!
    private static final float ft2m = 0.3048f;

    private static final float MIN_DELTA_TEMPERATURE = 1.0f; // degrees C
    // The logging service we're sampling for
    protected LoggingService mService = null;
    // Will be set true on startup and when logging is turned on
    protected boolean mMustLogNextSample = true;
    // Client characteristics
    private BluetoothGattCharacteristic mSampleCharacteristic, mConfigureCharacteristic;
    private float mMinDeltaDepth = MINIMUM_DELTA_DEPTH_DEFAULT;
    private Sample mLastLoggedSample = null;
    // Set true if a location packet is received from PingTest - after it is set true, no more samples
    // will be accepted from LocationService
    private boolean mLocationsFromPingTest = false;
    // The most recent location given to the sampler, must never be null
    private Location mCurrentLocation = new Location(TAG);
    // The location most recently logged
    private Location mLastLoggedLocation = mCurrentLocation;
    private float mMinDeltaPos = 1; //m
    private BluetoothDevice mBluetoothDevice;

    public SonarSampler(@NonNull final LoggingService service) {
        super(service);
        this.mService = service;
    }

    // Convert a double encoded in two bytes as realpart/fracpart to a double
    private static float b2f(byte real, byte frac) {
        int r = (int) real & 0xFF, f = (int) frac & 0xFF;
        return ((float) r + (float) f / 100.0f);
    }

    @Override
    public void log(final int priority, @NonNull final String message) {
        if (priority == Log.INFO)
            return;
            Log.println(priority, TAG, message);
    }

    /**
     * Location being set from LocationSampler (view LoggingService)
     *
     * @param loc location to set
     */
    void setLocation(Location loc) {
        if (!mLocationsFromPingTest)
            mCurrentLocation = loc;
    }

    /**
     * Aborts time travel. Call during 3 sec after enabling Flux Capacitor and only if you don't
     * like 2020.
     */
    /*public void abort() {
        cancelQueue();
    }*/
    @NonNull
    @Override
    protected BleManagerGattCallback getGattCallback() {
        return new SonarGattCallback();
    }

    /**
     * Get the text string this sampler contributes to the notification. This string should
     * incorporate the most recent sample, and the connected state of the sampler.
     *
     * @return a text string illustrating the current state of the sampler.
     */
    public String getNotificationStateText(Resources r) {
        return (mLastLoggedSample == null
                ? r.getString(R.string.depth_unknown) : r.getString(R.string.val_depth, mLastLoggedSample.depth)) + " "
                + r.getString(R.string.val_latitude, mLastLoggedSample.latitude) + " "
                + r.getString(R.string.val_longitude, mLastLoggedSample.longitude);
    }

    public BluetoothDevice getConnectedDevice() {
        return mBluetoothDevice;
    }

    private void broadcastStateChange(int state, @NonNull BluetoothDevice device, int reason) {
        Intent intent = new Intent(ACTION_BT_STATE);
        intent.putExtra(EXTRA_STATE, state);
        intent.putExtra(EXTRA_DEVICE, device);
        intent.putExtra(EXTRA_REASON, reason);
        mService.sendBroadcast(intent);
    }

    private void broadcastStateChange(int state, @NonNull BluetoothDevice device) {
        broadcastStateChange(state, device, ConnectionObserver.REASON_UNKNOWN);
    }

    @Override
    public void onDeviceConnecting(@NonNull BluetoothDevice device) {
        Log.d(TAG, "onDeviceConnecting");
        broadcastStateChange(BT_STATE_CONNECTING, device);
    }

    @Override
    public void onDeviceConnected(@NonNull BluetoothDevice device) {
        Log.d(TAG, "onDeviceConnected");
        broadcastStateChange(BT_STATE_CONNECTED, device);
    }

    @Override
    public void onDeviceFailedToConnect(@NonNull BluetoothDevice device, int reason) {
        Log.d(TAG, "onDeviceFailedToConnect");
        broadcastStateChange(BT_STATE_CONNECT_FAILED, device, reason);
    }

    @Override
    public void onDeviceReady(@NonNull BluetoothDevice device) {
        Log.d(TAG, "onDeviceReady");
        broadcastStateChange(BT_STATE_READY, device);
    }

    @Override
    public void onDeviceDisconnecting(@NonNull BluetoothDevice device) {
        Log.d(TAG, "onDeviceDisconnecting");
        broadcastStateChange(BT_STATE_DISCONNECTING, device);
    }

    @Override
    public void onDeviceDisconnected(@NonNull BluetoothDevice device, int reason) {
        Log.d(TAG, "onDeviceDisconnected");
        broadcastStateChange(BT_STATE_DISCONNECTED, device);
     }

    /**
     * Configuration reverse-engineered by sniffing packets sent by the official FishFinder software
     *
     * @param sensitivity   1..10
     * @param noise         filtering 0..4 (off, low, med, high)
     * @param range         0..6 (3, 6, 9, 18, 24, 36, auto)
     * @param minDeltaDepth min depth change, in metres
     */
    public void configure(int sensitivity, int noise, int range, float minDeltaDepth, float minDeltaPos) {
        Log.d(TAG, "configure(" + sensitivity + "," + noise + "," + range + "," + minDeltaDepth + ")");
        mMinDeltaDepth = minDeltaDepth;
        mMinDeltaPos = minDeltaPos;

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

        writeCharacteristic(mConfigureCharacteristic, data)
                .done(device -> Log.d(TAG, "Configuration sent to " + device.getName()))
                .enqueue();
    }

    // Connect to the given device
    // TODO: what if we're already connected to another device? How do we disconnect?
    public void connectToDevice(BluetoothDevice device) {
        setConnectionObserver(this);
        connect(device)
                .timeout(100000)
                .retry(3, 100)
                .done(dev -> {
                    Log.i(TAG, "Device connection to " + device.getName() + " done");
                })
                .enqueue();
    }

    /**
     * BluetoothGatt callbacks object.
     */
    private class SonarGattCallback extends BleManagerGattCallback {
        private static final String TAG = "SonarSamplerTwo/SonarGattCallback";

        // This method will be called when the device is connected and services are discovered.
        // You need to obtain references to the characteristics and descriptors that you will use.
        // Return true if all required services are found, false otherwise.
        @Override
        public boolean isRequiredServiceSupported(@NonNull final BluetoothGatt gatt) {
            final BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null)
                return false;

            mSampleCharacteristic = service.getCharacteristic(SAMPLE_CHARACTERISTIC_UUID);
            if (mSampleCharacteristic == null) {
                Log.e(TAG, "No sample characteristic");
                return false;
            }

            mConfigureCharacteristic = service.getCharacteristic(CONFIGURE_CHARACTERISTIC_UUID);
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

            // all required services have been found
            return true;
        }

        @Override
        protected void initialize() {
            Log.d(TAG, "Connecting SampleHandler");
            beginAtomicRequestQueue()
                    .add(enableNotifications(mSampleCharacteristic))
                    .done(device -> log(Log.INFO, "Notification enabled"))
                    .enqueue();
            setNotificationCallback(mSampleCharacteristic).with(new SampleHandler());
        }

        @Override
        protected void onDeviceDisconnected() {
            Log.d(TAG, "Device disconnected");
            mSampleCharacteristic = null;
            mConfigureCharacteristic = null;
        }
    }

    private class SampleHandler implements ProfileDataCallback {
        private static final String TAG = "SonarSamplerTwo/SampleHandler";

        @Override
        public void onDataReceived(@NonNull final BluetoothDevice device, @NonNull final Data data) {
            int sz = data.size();
            byte id0 = data.getByte(0);
            byte id1 = data.getByte(1);
            if (sz != 18 || id0 != ID0 || id1 != ID1) {
                Log.e(TAG, "Bad signature " + data.size() + " " + id0 + " " + id1);
                onInvalidDataReceived(device, data);
                return;
            }

            int checksum = 0;
            for (int i = 0; i < 17; i++)
                checksum = (checksum + data.getByte(i)) & 0xFF;
            int packcs = (int) data.getByte(17) & 0xFF;
            if (packcs != checksum) {
                // It's ok to throw in the callback, we will see the trace in the debug but otherwise
                // it won't stop us
                Log.e(TAG, "Bad checksum " + packcs + " != " + checksum);
                return;
            }

            Sample sample = new Sample();

            // data.getByte(2), data.getByte(3) unknown, always seem to be 0
            if (data.getByte(2) != 0) Log.d(TAG, "Mysterious 2 = " + data.getByte(2));
            if (data.getByte(3) != 0) Log.d(TAG, "Mysterious 3 = " + data.getByte(3));

            if ((data.getByte(4) & 0xF7) != 0) Log.d(TAG, "Mysterious 4 = " + data.getByte(4));

            sample.time = new Date().getTime();
            boolean isDry = (data.getByte(4) & 0x8) != 0;

            // data.getByte(5) unknown, seems to be always 9 (1001)
            if (data.getByte(5) != 9) Log.d(TAG, "Mysterious 5 = " + data.getByte(5));

            sample.depth = isDry ? -0.01f : ft2m * b2f(data.getByte(6), data.getByte(7));

            sample.strength = (short) ((int) data.getByte(8) & 0xFF);

            sample.fishDepth = ft2m * b2f(data.getByte(9), data.getByte(10));

            // Fish strength is in a nibble, so in the range 0-15. Just return it as a number
            sample.fishStrength = (short) ((int) data.getByte(11) & 0xF);
            sample.battery = (byte) ((data.getByte(11) >> 4) & 0xF);
            sample.temperature = (b2f(data.getByte(12), data.getByte(13)) - 32.0f) * 5.0f / 9.0f;

            // data.getByte(14), data.getByte(15), data.getByte(16) always 0
            if (data.getByte(14) != 0) Log.d(TAG, "Mysterious 14 = " + data.getByte(14));
            if (data.getByte(15) != 0) Log.d(TAG, "Mysterious 15 = " + data.getByte(15));
            if (data.getByte(16) != 0) Log.d(TAG, "Mysterious 16 = " + data.getByte(16));
            // data.getByte(17) is a checksum of data.getByte(0)..data.getByte(16)

            /*String mess = Integer.toString(sz);
            for (int i = 0; i < sz; i++)
                mess += (i == 0 ? "[" : ",") + ((int) data.getByte(i) & 0xFF);
            Log.d(TAG, mess + "]");*/

            sample.latitude = mCurrentLocation.getLatitude();
            sample.longitude = mCurrentLocation.getLongitude();

            if (mMustLogNextSample
                    // Log if battery level has changed
                    || sample.battery != mLastLoggedSample.battery
                    // Log if temperature has changed enough
                    || Math.abs(sample.temperature - mLastLoggedSample.temperature) >= MIN_DELTA_TEMPERATURE
                    // Log if depth has changed enough, and it's not dry
                    || Math.abs(sample.depth - mLastLoggedSample.depth) >= mMinDeltaDepth
                    // if we've moved further than the current location accuracy or the target min delta
                    || (mLastLoggedLocation.distanceTo(mCurrentLocation) > mMinDeltaPos)) {

                mMustLogNextSample = false;
                mLastLoggedSample = sample;
                mLastLoggedLocation = new Location(mCurrentLocation);

                if (mService != null)
                    mService.logSample(sample);
            }
        }
    }
}