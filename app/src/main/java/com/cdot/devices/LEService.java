package com.cdot.devices;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
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
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server.");
                intent = new Intent(ACTION_BT_DISCONNECTED);
                intent.putExtra(DEVICE_ADDRESS, mConnectingDevice.getAddress());
                intent.putExtra(REASON, CONNECTION_LOST);
                sendBroadcast(intent);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Intent intent = new Intent(ACTION_BT_DATA_AVAILABLE);
            intent.putExtra(DeviceService.DEVICE_ADDRESS, mConnectingDevice.getAddress());
            intent.putExtra(DeviceService.DATA, characteristic.getValue());
            sendBroadcast(intent);
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
            Intent intent = new Intent(ACTION_BT_CONNECTED);
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

    // Disconnect the currently connected device
    @Override
    public void disconnect() {
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

    public void write(byte[] data) {
        if (mBluetoothGatt == null)
            return;
        BluetoothGattService service = mBluetoothGatt.getService(BTS_CUSTOM);
        BluetoothGattCharacteristic charaWrite = service.getCharacteristic(BTC_CUSTOM_WRITE);
        int charaProp = charaWrite.getProperties();

        if ((charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            /*// Disable notification
            if (mNotifyCharacteristic != null) {
                // This never happens for Ping, because we don't enable notify on the write characteristic
                mBluetoothGatt.setCharacteristicNotification(mNotifyCharacteristic, false);
                mNotifyCharacteristic = null;
            }*/
            charaWrite.setValue(data);
            charaWrite.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            mBluetoothGatt.writeCharacteristic(charaWrite);
        } else
            Log.e(TAG, "Characteristic has no write");

        /*if ((charaProp & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            // This never happens for Ping, because we don't enable notify on the write characteristic
            mNotifyCharacteristic = charaWrite;
            mBluetoothGatt.setCharacteristicNotification(mNotifyCharacteristic, true);
        }*/
    }
}

