package com.cdot.ping;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;

import com.cdot.ping.devices.DeviceService;
import com.cdot.ping.devices.DeviceRecord;
import com.cdot.ping.devices.SampleData;

/**
 * Chat interface to com.cdot.ping.devices.DeviceService-derived services.
 */
class DeviceChat {
    // Constants which indicate the current connection state
    static final int STATE_NONE = 0;            // we're doing nothing, or getting connected
    static final int STATE_CONNECTING = 1;      // trying to connect
    static final String[] stateName = {"None", "Connected", "Disconnected"}; // debug trace only
    private static final String TAG = "DeviceChat";

    private Context mContext; // Context we operate within
    // Is our broadcast receiver currently registered in the context i.e. can we currently
    // handle broadcasts from the application?
    private boolean mReceiverRegistered = false;

    private DeviceService mService;
    private Class mServiceClass; // Java class of the service that talks to the device
    private Handler mListener; // Listener to messages sent by this interface

    // private short mReceivedSignalStrength = 0;

    static final int MESSAGE_SONAR_DATA = 16;   // obj = SampleData
    // Messages sent by this module to mListener
    static final int MESSAGE_STATE_CHANGE = 1;  // arg1 = one of the STATE_* constants below
    static final int STATE_DISCONNECTED = 3;    // Connection was lost
    static final int STATE_CONNECTED = 2;       // now connected to a remote device
    // Map broadcasts from the service to something the application can understand
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DeviceService.ACTION_CONNECTED.equals(action)) {
                String addr = intent.getStringExtra(DeviceService.DEVICE_ADDRESS);
                DeviceRecord dr = Ping.P.getDevice(addr);
                Log.d(TAG, "Connected to " + dr.name);
                mListener.obtainMessage(MESSAGE_STATE_CHANGE, STATE_CONNECTED, -1).sendToTarget();

            } else if (DeviceService.ACTION_DISCONNECTED.equals(action)) {
                String addr = intent.getStringExtra(DeviceService.DEVICE_ADDRESS);
                DeviceRecord dr = Ping.P.getDevice(addr);
                int reason = intent.getIntExtra(DeviceService.REASON, DeviceService.CANNOT_CONNECT);
                Log.d(TAG, "Disconnected from " + dr.name + ": " + context.getResources().getStringArray(R.array.bt_disconnect_reason)[reason]);
                mListener.obtainMessage(MESSAGE_STATE_CHANGE, STATE_DISCONNECTED, reason).sendToTarget();

            } else if (DeviceService.ACTION_DATA_AVAILABLE.equals(action)) {
                Bundle bund = intent.getBundleExtra(DeviceService.SAMPLE);
                SampleData data = new SampleData(bund);
                Log.d(TAG, "Pass on sample " + data.uid);
                mListener.obtainMessage(MESSAGE_SONAR_DATA, data).sendToTarget();
            }
        }
    };

    /**
     * @param context      Context used in service creation
     * @param serviceClass Java class of the service that talks to the device
     * @param listener     Something that listens to broadcasts coming from this object
     */
    DeviceChat(Context context, Class serviceClass, Handler listener) {
        mListener = listener;
        mServiceClass = serviceClass;
        mContext = context;
    }

    /**
     * Called when the app is resumed
     */
    void setBroadcasting(boolean on) {
        if (on && !mReceiverRegistered) {
            mReceiverRegistered = true;
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(DeviceService.ACTION_CONNECTED);
            intentFilter.addAction(DeviceService.ACTION_DISCONNECTED);
            intentFilter.addAction(DeviceService.ACTION_DATA_AVAILABLE);
            Log.d(TAG, "Registering BroadcastReceiver");
            mContext.registerReceiver(mBroadcastReceiver, intentFilter);
        } else if (!on && mReceiverRegistered) {
            Log.d(TAG, "Unregistering BroadcastReceiver");
            mContext.unregisterReceiver(mBroadcastReceiver);
            mReceiverRegistered = false;
        }
        if (mService != null)
            mService.setBroadcasting(on);
    }

    void recordTo(Uri sampleFile) {
        if (mService != null)
            mService.recordTo(sampleFile);
    }

    /**
     * Called to send configuration data to the connected device
     */
    void configure(int sensitivity, int noise, int range, float minDD, float minDP) {
        if (mService != null)
            mService.configure(sensitivity, noise, range, minDD, minDP);
    }

    /**
     * Called to connect to a device
     *
     * @param dev device to connect to
     * @return true if a connection was made
     */
    boolean connect(DeviceRecord dev) {
        if (mService != null) {
            Log.d(TAG, "Existing service, need to DeviceService.connect()");
            return mService.connect(dev);
        }
        final DeviceRecord d = dev;
        ServiceConnection scon = new ServiceConnection() {
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                mService = ((DeviceService.LocalBinder) service).getService();
                if (mService != null)
                    mService.connect(d);
                else
                    Log.e(TAG, "No service. Unable to connect.");
            }

            public void onServiceDisconnected(ComponentName componentName) {
                mService = null;
            }
        };
        if (mContext.bindService(new Intent(mContext, mServiceClass), scon, Context.BIND_AUTO_CREATE))
            return true;

        Log.e(TAG, "bindService failed");
        Toast toast = Toast.makeText(mContext.getApplicationContext(), R.string.bt_cannot_bind_service, Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.show();
        mService = null;
        return false;
    }

    /**
     * Called to stop any running background services
     */
    void stopServices() {
        if (mService != null) {
            mService.disconnect();
            mService.close();
            mService = null;
        }
    }
}
