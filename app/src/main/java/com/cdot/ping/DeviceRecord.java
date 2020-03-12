package com.cdot.ping;

import android.util.Log;

/**
 * A record that holds information about a device
 */
class DeviceRecord {
    private static final String TAG = "DeviceRecord";
    static final String DEVICE_TYPE = "type";
    static final String DEVICE_ADDRESS = "address";
    static final String DEVICE_NAME = "name";

    String address;
    String name;
    int type;
    boolean isPaired;
    boolean isConnected;

    DeviceRecord(String deviceAddress, String deviceName, int deviceType, boolean deviceIsPaired) {
        address = deviceAddress;
        name = deviceName;
        type = deviceType;
        isPaired = deviceIsPaired;
        isConnected = false;
    }

    /**
     * Reconstruct record from a serialised string
     * @param s string to parse
     */
    DeviceRecord(String s) {
        String[] bits = s.split("/", 3);
        address = bits[0];
        type = Integer.parseInt(bits[1]);
        name = bits[2];
        Log.d(TAG, "construct " + name + " from " + bits);
    }

    /**
     * Serialise to a string
     * @return serialised version of device record
     */
    String serialise() {
        return address + "/" + type + "/" + name;
    }
}
