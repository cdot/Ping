package com.cdot.ping;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import com.cdot.devices.DeviceRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Global Application State. Singleton. Maintains a list of activities, and a set of preferences
 * which includes a list of available bluetooth mDevices.
 */
class Ping {
    private static final String TAG = "Ping";

    // noise filtering is for turbid water or where there are suspended solids
    static final int NOISE_OFF = 0;
    static final int NOISE_LOW = 1;
    static final int NOISE_MEDIUM = 2;
    static final int NOISE_HIGH = 3;

    // Set as close to possible to the estimated bottom depth for best results.
    static final int RANGE_3M = 0;
    static final int RANGE_6M = 1;
    static final int RANGE_9M = 2;
    static final int RANGE_18M = 3;
    static final int RANGE_24M = 4;
    static final int RANGE_36M = 5;
    static final int RANGE_AUTO = 6;

    // range (metres) indexed by range
    static final int[] RANGE_DEPTH = {
            3, 6, 9, 18, 24, 36, 36
    };

    // Intensity of the sonar pulse. When the water is shallow, or there is noise in the water
    // such as engine noise, select a lower sensitivity
    // percent/10 i.e. 0..10
    static final int SENSITIVITY_MIN = 1; // 10%
    static final int SENSITIVITY_MAX = 10; // 100%
    static final int SENSITIVITY_DEFAULT = 5; // 50%

    static final int MAX_STRENGTH = 120;
    static final float MAX_TEMPERATURE = 60.0f; // celcius

    static final float MINIMUM_POSITION_CHANGE_MIN = 0.01f;
    static final float MINIMUM_POSITION_CHANGE_MAX = 10f;
    static final float MINIMUM_POSITION_CHANGE_DEFAULT = 0.5f;

    static final float MINIMUM_DEPTH_CHANGE_MIN = 0.01f;
    static final float MINIMUM_DEPTH_CHANGE_MAX = 5f;
    static final float MINIMUM_DEPTH_CHANGE_DEFAULT = 0.25f;

    // List of activities
    private static List<Activity> mActivityList = new LinkedList<>();

    private static Map<String, String> sKeyDefault = new HashMap<String, String>() {{
        put("sensitivity", Integer.toString(SENSITIVITY_DEFAULT));
        put("noise", Integer.toString(NOISE_OFF));
        put("range", Integer.toString(RANGE_AUTO));
        put("minimumPositionChange", Float.toString(MINIMUM_POSITION_CHANGE_DEFAULT));
        put("minimumDepthChange", Float.toString(MINIMUM_DEPTH_CHANGE_DEFAULT));
    }};

    // has the record button been pressed?
    boolean recordingOn = false;

    static final String DEMO_DEVICE = "DEMO";
    static final DeviceRecord demoDevice = new DeviceRecord(DEMO_DEVICE, DEMO_DEVICE, BluetoothDevice.DEVICE_TYPE_UNKNOWN, false);

    static Ping P = null; // Singleton for talking to prefs

    Context mContext;
    SharedPreferences mSP;
    Resources mR;
    private List<DeviceRecord> mDevices = new ArrayList<>();

    Ping(Context context) {
        mContext = context;
        mR = context.getResources();
        mSP = PreferenceManager.getDefaultSharedPreferences(context);
        String devs = mSP.getString("devices", "");
        List<DeviceRecord> res = new ArrayList<>();
        if (devs != null && devs.length() > 0) {
            String[] deviceSet = devs.split(",");
            // Devices saved in REVERSE order so the first ends up last, restoring the original order
            Log.d(TAG, "Loading devices from preferences");
            for (String s : deviceSet) {
                DeviceRecord dr = new DeviceRecord(s);
                Log.d(TAG, "remembered " + dr.serialise());
                mDevices.add(dr);
            }
        }
        // DEMO device always goes first
        mDevices.add(demoDevice);
    }

    // Factory method
    static void setContext(Context context) {
        P = new Ping(context);
    }

    /**
     * Set the string value of a preference. All preferences are saved as strings.
     *
     * @param key   key for the preference
     * @param value string value
     */
    void set(String key, String value) {
        SharedPreferences.Editor edit = mSP.edit();
        edit.putString(key, value);
        edit.apply();
    }

    void set(String key, DeviceRecord dr) {
        set(key, dr.address);
    }

    /**
     * Add an activity to the list of activities
     *
     * @param activity the activity to add
     */
    static void addActivity(Activity activity) {
        mActivityList.add(activity);
    }

    /**
     * Remove the singleton
     */
    void destroy() {
        for (Activity activity : mActivityList)
            activity.finish();
        clearDevices();
    }

    /**
     * Get the integer value of a preference. All preferences are stored as strings, and must be
     * converted on read.
     * @param key preference to read
     * @return preference value
     */
    int getInt(String key) {
        String v = mSP.getString(key, sKeyDefault.get(key));
        try {
            return Integer.valueOf(v);
        } catch (Error e) {
            return Integer.valueOf(sKeyDefault.get(key));
        }
    }

    /**
     * Get the float value of a preference. All preferences are stored as strings, and must be
     * converted on read.
     * @param key preference to read
     * @return preference value
     */
    float getFloat(String key) {
        String v = mSP.getString(key, sKeyDefault.get(key));
        try {
            return Float.valueOf(v);
        } catch (Error e) {
            return Float.valueOf(sKeyDefault.get(key));
        }
    }

    /**
     * Get the text value of the current stored value of an integer preference
     * @param key preference to read
     * @return string representation of the value of the preferene
     */
    String getText(String key) {
        return getText(key, getInt(key));
    }

    /**
     * Get the text value of the value of an integer preference
     * @param key preference to read
     * @param val value of the preference
     * @return string representation of the value of the preferene
     */
    String getText(String key, Object val) {
        float i;
        switch (key) {
            case "sensitivity":
                i = Float.valueOf(val.toString());
                return String.format(mR.getConfiguration().locale, "%d", (int)i * 10);
            case "noise":
                i = Float.valueOf(val.toString());
                return mR.getStringArray(R.array.noise_options)[(int)i];
            case "range":
                i = Float.valueOf(val.toString());
                return mR.getStringArray(R.array.range_options)[(int)i];
            default:
                return val.toString();
        }
    }

    /**
     * Get the currently selected sample file
     * @return a Uri for the sample file
     */
    Uri getSampleFile() {
        String sf = mSP.getString("sampleFile", "");

        if (sf == null || sf.length() == 0)
            return null;
        return Uri.parse(sf);
    }

    /**
     * Get the currently selected device
     * @return the DeviceRecord
     */
    DeviceRecord getSelectedDevice() {
        return getDevice(mSP.getString("selectedDevice", DEMO_DEVICE));
    }

    /**
     * Get a list of all known devices
     * @return the list
     */
    List<DeviceRecord> getDevices() {
        return mDevices;
    }

    // Save the device list cache to SharedPreferences
    private void saveDeviceList() {
        String comma = "", devs = "";
        if (mDevices != null) {
            for (DeviceRecord dr : mDevices) {
                if (!DEMO_DEVICE.equals(dr.address)) {
                    devs = dr.serialise() + comma + devs;
                    comma = ",";
                }
            }
        }
        set("devices", devs);
    }

    /**
     * Clear the list of devices. Does not remove the DEMO device. The only way to restore the
     * device list is to discover devices again.
     */
    void clearDevices() {
        mDevices.clear();
        saveDeviceList();
        mDevices.add(demoDevice);
    }

    /**
     * Add a device to the device records.
     *
     * @param mac      address
     * @param name     name
     * @param type     BluetoothDevice.DEVICE_TYPE_*
     * @param isPaired is it paired
     * @return the device record for the device
     */
    DeviceRecord addDevice(String mac, String name, int type, boolean isPaired) {
        return addDevice(new DeviceRecord(mac, name, type, isPaired));
    }

    DeviceRecord addDevice(BluetoothDevice device) {
        return addDevice(device.getAddress(), device.getName(), device.getType(),
                device.getBondState() == BluetoothDevice.BOND_BONDED);
    }

    DeviceRecord addDevice(DeviceRecord add) {
        for (DeviceRecord dr : mDevices) {
            if (dr.address.equalsIgnoreCase(add.address)) {
                dr.isPaired = add.isPaired;
                return dr;
            }
        }
        mDevices.add(add);
        saveDeviceList();
        return add;
    }

    /**
     * Find device record by address or name
     *
     * @param find address or name
     * @return the device record found
     */
    DeviceRecord getDevice(String find) {
        for (DeviceRecord dr : mDevices)
            if (dr.address.equals(find) || dr.name.equals(find))
                return dr;
        return null;
    }
}