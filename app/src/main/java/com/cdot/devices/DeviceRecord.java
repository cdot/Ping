package com.cdot.devices;

/**
 * A record that holds information about a device
 */
public class DeviceRecord {
    private static final String TAG = "DeviceRecord";
    public static final String DEVICE_TYPE = "type";
    public static final String DEVICE_ADDRESS = "address";
    public static final String DEVICE_NAME = "name";

    public String address;
    public String name;
    public int type;
    public boolean isPaired;
    public boolean isConnected = false;

    public DeviceRecord(String deviceAddress, String deviceName, int deviceType, boolean deviceIsPaired) {
        address = deviceAddress;
        name = deviceName;
        type = deviceType;
        isPaired = deviceIsPaired;
    }

    /**
     * Reconstruct record from a serialised string
     * @param s string to parse
     */
    public DeviceRecord(String s) {
        String[] bits = s.split("/", 3);
        address = bits[0];
        type = Integer.parseInt(bits[1]);
        name = bits[2];
    }

    /**
     * Serialise to a string
     * @return serialised version of device record
     */
    public String serialise() {
        return address + "/" + type + "/" + name;
    }
}
