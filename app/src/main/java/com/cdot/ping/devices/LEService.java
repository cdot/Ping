package com.cdot.ping.devices;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.util.List;
import java.util.UUID;

/**
 * Service for talking to Bluetooth LE devices. The application talks to the service
 * through a SonarChat interface.
 * *
 * Tested talking to a MicroChip IS1678S-152
 */
public class LEService extends DeviceService {
    public static final String TAG = "LEService";
    // ID bytes sent / received in every packet
    static final byte ID0 = 83;
    static final byte ID1 = 70;
    // feet to metres
    private static final float ft2m = 0.3048f;
    // Commands sent to the device
    private static final byte COMMAND_CONFIGURE = 1;

    // Bluetooth services BTS_*
    // Bluetooth characteristics BTC_*
    // Bluetooth Descriptors BTD_*

    public static UUID BTD_CLIENT_CHARACTERISTIC_CONFIGURATION = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // Bit 0 - Notifications disabled/enabled
    // Bit 1 Indications disabled/enabled

    static UUID BTS_CUSTOM = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"); // Custom service
    static UUID BTC_CUSTOM_SAMPLE = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    static UUID BTC_CUSTOM_WRITE = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");

    /*
        Other IDs observed from device discovery, but not used in Ping

    static final String BTS_GENERIC_ACCESS = "00001800-0000-1000-8000-00805f9b34fb";
    static final String BTC_DEVICE_NAME = "00002a00-0000-1000-8000-00805f9b34fb";
    static final String BTC_APPEARANCE = "00002a01-0000-1000-8000-00805f9b34fb";
    static final String BTC_PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS = "00002a04-0000-1000-8000-00805f9b34fb";

    static final String BTS_DEVICE_INFORMATION = "0000180A-0000-1000-8000-00805f9b34fb";
    static final String BTC_MANUFACTURER_NAME_STRING = "00002a29-0000-1000-8000-00805f9b34fb";
    static final String BTC_MODEL_NUMBER_STRING = "00002a24-0000-1000-8000-00805f9b34fb";
    static final String BTC_SERIAL_NUMBER_STRING = "00002a25-0000-1000-8000-00805f9b34fb";
    static final String BTC_HARDWARE_REVISION_STRING = "00002a27-0000-1000-8000-00805f9b34fb";
    static final String BTC_FIRMWARE_REVISION_STRING = "00002a26-0000-1000-8000-00805f9b34fb";
    static final String BTC_SOFTWARE_REVISION_STRING = "00002a28-0000-1000-8000-00805f9b34fb";
    static final String BTC_SYSTEM_ID = "00002a23-0000-1000-8000-00805f9b34fb";
    static final String BTC_IEEE_CERTIFICATION = "00002a2a-0000-1000-8000-00805f9b34fb";

    static final String BTS_MICROCHIP = "49535343-fe7d-4ae5-8fa9-9fafd205e455";
    static final String BTC_MICROCHIP_CONNECTION_PARAMETER = "49535343-6daa-4d02-abf6-19569aca69fe";
    static final String BTC_MICROCHIP_AIR_PATCH = "49535343-aca3-481c-91ec-d85e28a60318";
    */

    // Placeholder for a write characteristic that has notification disabled during write
    // Not used by Ping
    private BluetoothGattCharacteristic mNotifyCharacteristic;

    private BluetoothGatt mBluetoothGatt;

    private class GattBack extends BluetoothGattCallback {

        private BluetoothDevice mConnectingDevice;

        GattBack(BluetoothDevice cd) {
            mConnectingDevice = cd;
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Intent intent = null;
            Log.d(TAG, "BluetoothGatt: connection state change " + status + " " + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server. Starting service discovery");
                if (!mBluetoothGatt.discoverServices())
                    throw new Error("BLE discoverServices failed");
                // Only when service discovery is complete will we broadcast that the connection
                // is available
                mSampler.device = mConnectingDevice.getAddress();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.");
                intent = new Intent(ACTION_DISCONNECTED);
                intent.putExtra(DEVICE_ADDRESS, mConnectingDevice.getAddress());
                intent.putExtra(REASON, CONNECTION_LOST);
                sendBroadcast(intent);
                mSampler.device = null;
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data.length < 14 || data[0] != ID0 || data[1] != ID1)
                throw new IllegalArgumentException("Bad data block");
            // Wonder what data[5] is? Seems to be always 9
            //Log.d(TAG, "[5]=" + Integer.toHexString(data[5]));
            // There are several more bytes in the packet; wonder what they do?
            //for (int i = 13; i < data.length; i++)
            //    Log.d(TAG, "[" + i + "]=" + Integer.toHexString(data[i]));
            mSampler.updateSampleData(
                    (data[4] & 0x8) != 0,
                    // Convert feet to metres
                    ft2m * ((float) data[6] + (float) data[7] / 100.0f),
                    // Guess our max is 128
                    100f * data[8] / 128.0f,
                    // Convert feet to metres
                    ft2m * ((float) data[9] + (float) data[10] / 100f),
                    // Fish Helper doesn't handle anything more than 4
                    100f * (data[11] & 0xF) / 16f,
                    // battery in range 0..6
                    (data[11] >> 4) & 0xF,
                    // Convert fahrenheit to celcius
                    ((float) data[12] + (float) data[13] / 100.0f - 32.0f) * 5.0f / 9.0f);
        }

        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered received: " + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                List<BluetoothGattService> services = mBluetoothGatt.getServices();
                for (BluetoothGattService service : services) {
                    Log.d(TAG, "BTS " + service.getUuid().toString());
                    service.describeContents();
                    List<BluetoothGattCharacteristic> chs = service.getCharacteristics();
                    for (BluetoothGattCharacteristic chara : service.getCharacteristics()) {
                        Log.d(TAG, "\tBTC " + chara.getUuid().toString());
                        for (BluetoothGattDescriptor desc : chara.getDescriptors()) {
                            int perms = desc.getPermissions();

                            Log.d(TAG, "\t\tBTD " + desc.getUuid().toString() + " " + perms + " " + desc.getValue());
                        }
                    }
                }
            }
            // Tell the world we are ready for action
            Intent intent = new Intent(ACTION_CONNECTED);
            intent.putExtra(DEVICE_ADDRESS, mConnectingDevice.getAddress());
            sendBroadcast(intent);

            // Enable push notification from the BTC_CUSTOM_SAMPLE service
            BluetoothGattService bgs = mBluetoothGatt.getService(BTS_CUSTOM);

            BluetoothGattCharacteristic cha = bgs.getCharacteristic(BTC_CUSTOM_SAMPLE);
            mBluetoothGatt.setCharacteristicNotification(cha, true);
            // onCharacteristicChanged callback will be triggered if the
            // device indicates that the given characteristic has changed.

            // Not sure why we have to enable this as well, but we do.
            BluetoothGattDescriptor descriptor = cha.getDescriptor(BTD_CLIENT_CHARACTERISTIC_CONFIGURATION);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        }
    }

    @Override
    public boolean connect(DeviceRecord device) {
        super.connect(device);
        if (mBluetoothGatt == null) {
            BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
            if (bta == null) {
                Log.e(TAG, "connect() failed, no bluetooth adapter");
                return false;
            }
            BluetoothDevice btd = bta.getRemoteDevice(device.address);
            if (btd == null) {
                Log.e(TAG, "connect() failed, " + device.address + " not found");
                return false;
            }
            Log.d(TAG, "connect() using new BluetoothGatt");
            mBluetoothGatt = btd.connectGatt(this,
                    false, // Don't wait for device to become available
                    new GattBack(btd), // Callback
                    BluetoothDevice.TRANSPORT_LE); // Use LE with dual-mode devices
            return true;
        }
        // Re-connect to a remote device after the connection has been dropped. If the
        // device is not in range, the re-connection will be triggered once the device
        // is back in range.
        Log.d(TAG, "connect() using existing BluetoothGatt");
        return mBluetoothGatt.connect();
    }

    @Override
    public void disconnect() {
        super.disconnect();
        if (mBluetoothGatt == null)
            return;
        mBluetoothGatt.disconnect();
        mBluetoothGatt = null;
    }

    @Override
    public void close() {
        if (mBluetoothGatt == null)
            return;
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    @Override
    public void configure(int sensitivity, int noise, int range, float minDD, float minDP) {
        super.configure(sensitivity, noise, range, minDD, minDP);

        byte[] data = new byte[]{
                // http://ww1.microchip.com/downloads/en/DeviceDoc/50002466B.pdf
                ID0, ID1,
                0, 0, COMMAND_CONFIGURE, // SF?
                3, // size
                (byte) sensitivity, (byte) noise, (byte) range,
                0, 0, 0 // why the extra zeros?
        };
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += data[i];
        }
        data[9] = (byte) (sum & 255);
        if (mBluetoothGatt == null)
            return;
        BluetoothGattService service = mBluetoothGatt.getService(BTS_CUSTOM);
        BluetoothGattCharacteristic charaWrite = service.getCharacteristic(BTC_CUSTOM_WRITE);
        int charaProp = charaWrite.getProperties();

        if ((charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {

            charaWrite.setValue(data);
            charaWrite.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            mBluetoothGatt.writeCharacteristic(charaWrite);
        } else
            Log.e(TAG, "Characteristic has no write");
    }
}

