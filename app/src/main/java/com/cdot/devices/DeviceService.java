package com.cdot.devices;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * Abstract base class of bluetooth services classes. There should be a different class for each of
 * the different types of device i.e. Classic or LE (and maybe more later). Right now we only
 * support LE, but classic might be added.
 */
public abstract class DeviceService extends Service {
    public static final String ACTION_BT_DATA_AVAILABLE = "com.cdot.devices.ACTION_BT_DATA_AVAILABLE";
    public static final String ACTION_BT_CONNECTED = "com.cdot.devices.ACTION_BT_CONNECTED";
    public static final String ACTION_BT_DISCONNECTED = "com.cdot.devices.ACTION_BT_DISCONNECTED";
    public static final String DEVICE_ADDRESS = "com.cdot.devices.DEVICE_ADDRESS";
    public static final String REASON = "com.cdot.devices.REASON";
    public static final String DATA = "com.cdot.devices.DATA";

    // Explanations sent with ACTION_BT_DISCONNECTED
    public static final int CANNOT_CONNECT = 0;
    public static final int CONNECTION_LOST = 1;

    public static final String[] failReason = { "Cannot connect", "Connection lost" };

    public class LocalBinder extends Binder {
        public DeviceService getService() {
            return DeviceService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        close();
        return super.onUnbind(intent);
    }

    /**
     * Connect to a bluetooth device
     * @param device device to connect to
     * @return false if the connection attempt failed. true doesn't mean it succeeded (the
     * connection may be being made by another thread) just that it hasn't failed (yet)
     */
    public abstract boolean connect(DeviceRecord device);

    /**
     * Disconnects an established connection, or cancels a connection attempt currently in progress.
     */
    public abstract void disconnect();

    public abstract void write(byte[] data);

    /**
     * Closes down the service
     */
    public void close() {}
}
