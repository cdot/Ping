package com.cdot.ping;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Global Application State
 */
public class Ping {
    private static final String TAG = "Ping";

    private static Ping mInstance;

    // Raw values written to the device
    static final int DEFAULT_NOISE = 0; // off
    int Noise = DEFAULT_NOISE;

    static final int DEFAULT_RANGE = 6; // auto
    int Range = DEFAULT_RANGE;

    // Divide by 10 before sending to device; 0..10
    static final int DEFAULT_SENSITIVITY = 5;
    int Sensitivity = DEFAULT_SENSITIVITY; // %

    static final boolean DEFAULT_DEMO = false;
    boolean Demo = DEFAULT_DEMO;

    private List<Activity> mActivityList;
    ArrayList<DeviceRecord> mDevices;

    private Ping() {
        mActivityList = new LinkedList<>();
        //mDatabaseAdapter = new DeviceDataBaseAdapter(context);
        mDevices = new ArrayList<>();
        mInstance = this;
   }

    /**
     * Create the Ping singleton
     * @return the new singleton
     */
    static Ping create() {
        mInstance = new Ping();
        return mInstance;
    }

    /**
     * return the Ping singleton
     * @return
     */
    static Ping getInstance() {
        return mInstance;
    }

    /**
     * Add an activity to the list of activities
     * @param activity
     */
    void addActivity(Activity activity) {
        mActivityList.add(activity);
    }

    /**
     * Remove this
     */
    void destroy() {
        for (Activity activity : mActivityList)
            activity.finish();
        clearDevices();
    }

    /**
     * Get the selected device
     * @return the device record for the selected device
     */
    public DeviceRecord getDevice() {
        if (mDevices.size() < 1)
            return null;
        return mDevices.get(0);
    }

    /**
     * Save shared preferences
     * @param context to context for which the singleton is being created
     */
    void saveSettings(Context context) {
        SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(context).edit();
        edit.putString("Sensitivity", Integer.toString(Sensitivity));
        edit.putString("Noise", Integer.toString(Noise));
        edit.putString("Range", Integer.toString(Range));
        String devices = "";
        // Note devices in REVERSE order, so the current device is LAST
        for (DeviceRecord dr : mDevices) {
            devices += dr.serialise() + (devices.length() == 0 ? devices : ("," + devices));
        }
        edit.putString("devices", devices);
        edit.apply();
    }

    /**
     * Clear shared preferences
     * @param context to context for which the singleton is being created
     */
    void clearSettings(Context context) {
        SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(context).edit();
        edit.clear();
        edit.commit();
    }

    /**
     * Load settings from shared preferences
     */
    void loadSettings(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        Demo = sp.getBoolean("Demo", DEFAULT_DEMO);
        try {
            Sensitivity = Integer.valueOf(sp.getString("Sensitivity", Integer.toString(DEFAULT_SENSITIVITY)));
        } catch (Exception rte) {
            Log.d(TAG, "Exception1 while loading settings " + rte);
        }
        try {
            Noise = Integer.valueOf(sp.getString("Noise", Integer.toString(DEFAULT_NOISE)));
        } catch (Exception rte) {
            Log.d(TAG, "Exception2 while loading settings " + rte);
        }
        try {
            Range = Integer.valueOf(sp.getString("Range", Integer.toString(DEFAULT_RANGE)));
        } catch (Exception rte) {
            Log.d(TAG, "Exception3 while loading settings " + rte);
        }
        String devs = sp.getString("devices", "");
        if (devs != null && devs.length() > 0) {
            String[] deviceSet = devs.split(",");
            // Devices added in REVERSE order so the first ends up last, restoring the original order
            for (String s : deviceSet)
                addDevice(new DeviceRecord(s));
        }
    }

    /**
     * Clear devices NOTE does not clear preferences
     */
    void clearDevices() {
        mDevices.clear();
    }

    /**
     * Move the selected device record to the head of the devices list
     * @param dr the device record to add/move
     */
    void selectDevice(DeviceRecord dr) {
        mDevices.remove(dr);
        mDevices.add(0, dr);
    }

    /**
     * Add a device to the device records. If the device is added, or the pairing status of
     * a device is changed, or the position of a device is changed in the ordering, return true.
     * Otherwise return false.
     *
     * @param mac address
     * @param name name
     * @param type classic, le, dual
     * @param isPaired is it paired
     * @return the device record for the created device
     */
    DeviceRecord addDevice(String mac, String name, int type, boolean isPaired) {
        for (DeviceRecord dr : mDevices) {
            if (mac.equalsIgnoreCase(dr.address)) {
                dr.isPaired = isPaired;
                // Promote it to the head of the list
                selectDevice(dr);
                return dr;
            }
        }
        return addDevice(new DeviceRecord(mac, name, type, isPaired));
    }

    DeviceRecord addDevice(BluetoothDevice device) {
        return addDevice(device.getAddress(), device.getName(), device.getType(),
                device.getBondState() == BluetoothDevice.BOND_BONDED);
    }

    DeviceRecord addDevice(DeviceRecord dr) {
        mDevices.add(dr);
        return dr;
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