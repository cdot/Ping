package com.cdot.ping.devices;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A record that holds information about a device
 */
public class DeviceRecord {
    private static final String TAG = "DeviceRecord";
    public static final String DEVICE_TYPE = "type";
    public static final String DEVICE_ADDRESS = "address";
    public static final String DEVICE_NAME = "name";

    // Must differ from Ping.SEPARATOR
    private static final String FIELD_SEPARATOR = ",";
    public static final String RECORD_SEPARATOR = ";";

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
     *
     * @param s string to parse
     */
    public DeviceRecord(String s) throws IllegalArgumentException {
        String[] bits = s.split(FIELD_SEPARATOR, 3);
        if (bits.length != 3)
            throw new IllegalArgumentException(s + " is not a valid device record");
        address = bits[0];
        // Verify MAC address
        Pattern p = Pattern.compile("^[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}$");
        Matcher m = p.matcher(address);
        if (!m.find())
            throw new IllegalArgumentException(s + " is not a valid device record");
        type = Integer.parseInt(bits[1]);
        name = bits[2]
                .replaceAll("%44", FIELD_SEPARATOR)
                .replaceAll("%59", RECORD_SEPARATOR)
                .replaceAll("%37", "%");
    }

    /**
     * Serialise to a string
     *
     * @return serialised version of device record
     */
    public String serialise() {
        return address + FIELD_SEPARATOR
                + type + FIELD_SEPARATOR
                + name
                .replaceAll("%", "%37")
                .replaceAll(RECORD_SEPARATOR, "%44")
                .replaceAll(FIELD_SEPARATOR, "%44");
    }

    /**
     * Principally for debug
     * @return string representation of the device
     */
    public String toString() {
        return "{address:" + address + ", type:" + type + ", name:\"" + name + "\"}";
    }
}
