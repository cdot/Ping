package com.cdot.ping;

import android.bluetooth.BluetoothDevice;

/**
 * A record that holds information about a device
 */
class DeviceRecord {
    static final String DEVICE_TYPE = "type";
    static final String DEVICE_ADDRESS = "address";
    static final String DEVICE_NAME = "name";

    String address;
    String name;
    int type;
    boolean isPaired;

    DeviceRecord(String deviceAddress, String deviceName, int deviceType, boolean deviceIsPaired) {
        address = deviceAddress;
        name = deviceName;
        type = deviceType;
        isPaired = deviceIsPaired;
    }

    /**
     * Reconstruct record from a serialised string
     * @param s
     */
    DeviceRecord(String s) {
        String[] bits = s.split("/", 3);
        address = bits[0];
        type = Integer.parseInt(bits[1]);
        name = bits[2];
    }

    /**
     * Serialise to a string
     * @return
     */
    public String serialise() {
        return address + "/" + type + "/" + name;
    }
}
