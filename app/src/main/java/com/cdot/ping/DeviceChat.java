package com.cdot.ping;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;

import com.cdot.devices.DeviceService;
import com.cdot.devices.DeviceRecord;

/**
 * Chat interface to DeviceService-derived services.
 */
class DeviceChat extends Chatter {
    private static final String TAG = "DeviceChat";

    // Commands sent to the device
    private static final byte COMMAND_CONFIGURE = 1;

    private Context mContext; // Context we operate within
    // Is our broadcast receiver currently registered in the context i.e. can we currently
    // handle broadcasts from the application?
    private boolean mReceiverRegistered = false;

    private DeviceService mService;
    private Class mServiceClass; // Java class of the service that talks to the device
    private boolean mServiceBound = false; // Is the service bound?

    // private short mReceivedSignalStrength = 0;

    // Map broadcasts from the service to something the application can understand
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DeviceService.ACTION_BT_CONNECTED.equals(action)) {
                String addr = intent.getStringExtra(DeviceService.DEVICE_ADDRESS);
                DeviceRecord dr = Ping.P.getDevice(addr);
                Log.d(TAG, "Connected to " + dr.name);
                mListener.obtainMessage(MESSAGE_STATE_CHANGE, STATE_CONNECTED, -1).sendToTarget();

            } else if (DeviceService.ACTION_BT_DISCONNECTED.equals(action)) {
                String addr = intent.getStringExtra(DeviceService.DEVICE_ADDRESS);
                DeviceRecord dr = Ping.P.getDevice(addr);
                int reason = intent.getIntExtra(DeviceService.REASON, DeviceService.CANNOT_CONNECT);
                Log.d(TAG, "Disconnected from " + dr.name + " reason " + DeviceService.failReason[reason]);
                mListener.obtainMessage(MESSAGE_STATE_CHANGE, STATE_DISCONNECTED, reason).sendToTarget();

            } else if (DeviceService.ACTION_BT_DATA_AVAILABLE.equals(action)) {
                byte[] bytes = intent.getByteArrayExtra(DeviceService.DATA);
                SampleData data = new SampleData(bytes);
                mListener.obtainMessage(Chatter.MESSAGE_SONAR_DATA, data).sendToTarget();
            }
        }
    };

    /**
     * @param context      Context used in service creation
     * @param serviceClass Java class of the service that talks to the device
     * @param listener     Something that listens to broadcasts coming from this object
     */
    DeviceChat(Context context, Class serviceClass, Handler listener) {
        super(listener);

        mServiceClass = serviceClass;
        mContext = context;
    }

    @Override
    void onPause() {
        if (mReceiverRegistered) {
            mContext.unregisterReceiver(mBroadcastReceiver);
            mReceiverRegistered = false;
        }
    }

    @Override
    void onResume() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DeviceService.ACTION_BT_CONNECTED);
        intentFilter.addAction(DeviceService.ACTION_BT_DISCONNECTED);
        intentFilter.addAction(DeviceService.ACTION_BT_DATA_AVAILABLE);
        if (!mReceiverRegistered) {
            mContext.registerReceiver(mBroadcastReceiver, intentFilter);
            mReceiverRegistered = true;
        }
    }

    // Send a configure command
    @Override
    void configure(int sensitivity, int noise, int range) {
        byte[] data = new byte[]{
                // http://ww1.microchip.com/downloads/en/DeviceDoc/50002466B.pdf
                SampleData.ID0, SampleData.ID1,
                0, 0, COMMAND_CONFIGURE, // SF?
                3, // size
                (byte) sensitivity, (byte) noise, (byte) range,
                0, 0, 0 // why the extra zeros?
        };
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += data[i];
        }
        data[9] = (byte) (sum & 255);
        mService.write(data);
    }

    @Override
    boolean connect(DeviceRecord dev) {
        if (mServiceBound) {
            Log.d(TAG, "Service bound, need to DeviceService.connect()");
            return mService.connect(dev);
        }
        final DeviceRecord d = dev;
        ServiceConnection scon = new ServiceConnection() {
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                mService = ((DeviceService.LocalBinder) service).getService();
                if (mService != null) {
                    mServiceBound = true;
                    mService.connect(d);
                } else
                    Log.e(TAG, "No service. Unable to connect.");
            }

            public void onServiceDisconnected(ComponentName componentName) {
                mService = null;
                mServiceBound = false;
            }
        };
        if (mContext.bindService(new Intent(mContext, mServiceClass), scon, Context.BIND_AUTO_CREATE))
            return true;

        Log.e(TAG, "bindService failed");
        Toast toast = Toast.makeText(mContext.getApplicationContext(), R.string.bt_cannot_bind_service, Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.show();
        return false;
    }

    @Override
    void disconnect() {
        if (mService != null)
            mService.disconnect();
    }

    @Override
    void stopServices() {
        disconnect();
        if (mService != null) {
            mService.close();
            mService = null;
        }
    }
}
