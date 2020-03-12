package com.cdot.ping;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
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

import com.cdot.bluetooth.BluetoothService;

/**
 * Base class of common functionality for talking to Bluetooth devices
 */
class BluetoothChat extends Chatter {
    private static final String TAG = "BluetoothChat";

    // Commands sent to the device
    private static final byte COMMAND_CONFIGURE = 1;

    private Context mContext; // Context we operate within
    // Is our broadcast receiver currently registered in the context i.e. can we currently
    // handle broadcasts from the application?
    private boolean mReceiverRegistered = false;

    private BluetoothService mService;
    private Class mServiceClass; // Java class of the service that talks to the device
    private boolean mServiceBound = false; // Is the service bound?
    private DeviceRecord mDevice = null; // Device this chatter is connected to

    // private short mReceivedSignalStrength = 0;

    // Map broadcasts from the service to something the application can understand
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothService.ACTION_BT_CONNECTED.equals(action)) {
                Log.d(TAG, "Connected to " + Ping.P.getSelectedDevice().name);
                mListener.obtainMessage(MESSAGE_STATE_CHANGE, STATE_CONNECTED, -1).sendToTarget();

            } else if (BluetoothService.ACTION_BT_DISCONNECTED.equals(action)) {
                int reason = intent.getIntExtra(BluetoothService.EXTRA_DATA, BluetoothService.CANNOT_CONNECT);
                Log.d(TAG, "Disconnected from " + Ping.P.getSelectedDevice().name + " reason " + reason);
                mListener.obtainMessage(MESSAGE_STATE_CHANGE, STATE_DISCONNECTED, reason).sendToTarget();

            } else if (BluetoothService.ACTION_BT_DATA_AVAILABLE.equals(action)) {
                byte[] bytes = intent.getByteArrayExtra(BluetoothService.EXTRA_DATA);
                SampleData data = new SampleData(bytes);
                mListener.obtainMessage(Chatter.MESSAGE_SONAR_DATA, data).sendToTarget();

/*            } else if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())
                    && ((BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)).getBondState() != BluetoothDevice.BOND_BONDED) {
                // Device discovery has found a device
                mReceivedSignalStrength = intent.getExtras().getShort(BluetoothDevice.EXTRA_RSSI);
*/
            }
        }
    };

    // Handler for when the service connects
    private final ServiceConnection mServiceConnection;

    /**
     * @param context Context used in service creation
     * @param serviceClass Java class of the service that talks to the device
     * @param listener Something that listens to broadcasts coming from this object
     */
    BluetoothChat(Context context, Class serviceClass, Handler listener) {
        super(listener);

        mServiceClass = serviceClass;
        mContext = context;
        mServiceConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName componentName, IBinder service) {
                mService = ((BluetoothService.LocalBinder) service).getService();
                if (mService != null)
                    mServiceBound = true;
                else
                    Log.e(TAG, "No service. Unable to connect.");
            }

            public void onServiceDisconnected(ComponentName componentName) {
                mService = null;
                mServiceBound = false;
            }
        };
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
        intentFilter.addAction(BluetoothService.ACTION_BT_CONNECTED);
        intentFilter.addAction(BluetoothService.ACTION_BT_DISCONNECTED);
        intentFilter.addAction(BluetoothService.ACTION_BT_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
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
    void connect(DeviceRecord dev) {
        mDevice = dev;
        if (!mServiceBound) {
            if (!mContext.bindService(new Intent(mContext, mServiceClass), mServiceConnection, Context.BIND_AUTO_CREATE)) {
                Toast toast = Toast.makeText(mContext.getApplicationContext(), R.string.cannot_bind_service, Toast.LENGTH_SHORT);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();
            }
        } else {
            BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
            if (bta == null) {
                Log.e(TAG, "No bluetooth adapter.  Unable to connect.");
            } else {
                BluetoothDevice device = bta.getRemoteDevice(mDevice.address);
                if (device == null) {
                    Log.e(TAG, "Device not found.  Unable to connect.");
                    return;
                }
                mService.connect(device);
                mDevice.isConnected = true;
            }
        }
    }

    @Override
    void disconnect() {
        if (mDevice != null) {
            mDevice.isConnected = false;
            mDevice = null;
        }
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
