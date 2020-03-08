package com.cdot.bluetooth;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * Abstract base class of bluetooth services classes. There is a different class for each of
 * the different types of device i.e. Classic or LE (and maybe more later)
 */
public abstract class BluetoothService extends Service {
    public static final String ACTION_BT_DATA_AVAILABLE = "com.cdot.bluetooth.ACTION_BT_DATA_AVAILABLE";
    public static final String ACTION_BT_CONNECTED = "com.cdot.bluetooth.ACTION_BT_CONNECTED";
    public static final String ACTION_BT_DISCONNECTED = "com.cdot.bluetooth.ACTION_BT_DISCONNECTED";
    public static final String EXTRA_DATA = "com.cdot.bluetooth.EXTRA_DATA";

    // Explanations sent with ACTION_BT_DISCONNECTED
    public static final int CANNOT_CONNECT = 0;
    public static final int CONNECTION_LOST = 1;

    public abstract void connect(BluetoothDevice device);

    /**
     * Disconnects an established connection, or cancels a connection attempt currently in progress.
     */
    public abstract void disconnect();

    /**
     * Closes down the service
     */
    public abstract void close();

    public class LocalBinder extends Binder {
        public BluetoothService getService() {
            return BluetoothService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public boolean onUnbind(Intent intent) {
        close();
        return super.onUnbind(intent);
    }

    public abstract void write(byte[] data);
}
