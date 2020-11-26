package com.cdot.ping.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.cdot.ping.MainActivity;
import com.cdot.ping.R;

import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Service for talking to Bluetooth LE FishFinder devices. This is a bound and started service
 * that is promoted to a foreground service when sensor samples have been requested and all
 * clients unbind. This is to allow logging to continue even when the foreground activity is
 * killed.
 * <p>
 * When an activity is bound to this service, the service acts as a conventional service. When
 * the activity is removed from the foreground, the service promotes itself to a foreground service,
 * sampling continues. When the activity comes back to the foreground, the foreground service stops,
 * and the notification associated with that service is removed.
 * <p>
 * The service can also be terminated from the notification.
 * <p>
 * Tested talking to a MicroChip IS1678S-152, and with an emulator for that device.
 * <p>
 * Communication with the device can be in three states: waiting, trying to connect, and connected.
 */
public class SonarService extends Service {
    public static final String TAG = SonarService.class.getSimpleName();

    private static final String PACKAGE_NAME = SonarService.class.getPackage().getName();

    // Messages sent by the service
    public static final String ACTION_SAMPLE = PACKAGE_NAME + ".ACTION_SAMPLE";
    public static final String ACTION_CONNECTED = PACKAGE_NAME + ".ACTION_CONNECTED";
    public static final String ACTION_DISCONNECTED = PACKAGE_NAME + ".ACTION_DISCONNECTED";

    // Sent message extras
    public static final String EXTRA_DEVICE_ADDRESS = PACKAGE_NAME + ".DEVICE_ADDRESS";
    public static final String EXTRA_SAMPLE_DATA = PACKAGE_NAME + ".SAMPLE_DATA";
    public static final String EXTRA_DISCONNECT_REASON = PACKAGE_NAME + ".DISCONNECT_REASON";

    // DISCONNECT_REASONs sent with ACTION_DISCONNECTED/EXTRA_DISCONNECT_REASON
    public static final int REASON_CANNOT_CONNECT = 0;
    public static final int REASON_CONNECTION_LOST = 1;

    // ID bytes sent / received in every packet received from the sonar unit
    private static final byte ID0 = 83;
    private static final byte ID1 = 70;
    // Commands sent to the device
    private static final byte COMMAND_CONFIGURE = 1;

    // feet to metres
    private static final float ft2m = 0.3048f;

    // Bluetooth services BTS_* published by the FishFinder sonar device
    public static final UUID BTS_CUSTOM = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");

    // Bluetooth characteristics BTC_*
    // Note that FishFinder packages battery state in the sample packet and there is no separate
    // characteristic
    public static final UUID BTC_CUSTOM_SAMPLE = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    // The write characteristic is used for sending packets to the device. The only command I can
    // find that FishFinder devices support is "configure".
    public static final UUID BTC_CUSTOM_CONFIGURE = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");

    // Bluetooth Descriptors BTD_*
    public static final UUID BTD_CLIENT_CHARACTERISTIC_CONFIGURATION = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    // Bit 0 - Notifications disabled/enabled
    // Bit 1 Indications disabled/enabled

    // Other IDs observed from device discovery, but not used in Ping. Descriptions found by googling; may not be correct

    public static final UUID BTS_GENERIC_ACCESS = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb");
    public static final UUID BTC_DEVICE_NAME = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"); // "Fish Helper"
    public static final UUID BTC_APPEARANCE = UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"); // [0x80, 0x00]

    public static final UUID BTC_PERIPHERAL_PREFERRED_CONNECTION_PARAMETERS = UUID.fromString("00002a04-0000-1000-8000-00805f9b34fb"); // [6, 0, -128, 0, 0, 0, -128, 12]

    public static final UUID BTS_DEVICE_INFORMATION = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb");
    public static final UUID BTC_MANUFACTURER_NAME_STRING = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb"); // "MCHP" (MicroChip)
    public static final UUID BTC_MODEL_NUMBER_STRING = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb"); // "IS1678S152"
    public static final UUID BTC_SERIAL_NUMBER_STRING = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb"); // "0000"
    public static final UUID BTC_HARDWARE_REVISION_STRING = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb"); // "5056_SPP"
    public static final UUID BTC_FIRMWARE_REVISION_STRING = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb"); // "0205012"
    public static final UUID BTC_SOFTWARE_REVISION_STRING = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb"); // "0000"
    public static final UUID BTC_SYSTEM_ID = UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb"); // [0, 0, 0, 0, 0, 0, 0, 0]
    public static final UUID BTC_IEEE_CERTIFICATION = UUID.fromString("00002a2a-0000-1000-8000-00805f9b34fb"); // [0, 1, 0, 4, 0, 0, 0, 0]

    public static final UUID BTS_MICROCHIP = UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455");
    public static final UUID BTC_MICROCHIP_CONNECTION_PARAMETER = UUID.fromString("49535343-6daa-4d02-abf6-19569aca69fe"); // [-120, 102, 0, 0, 0, 0, 0, 0, 0]
    public static final UUID BTC_MICROCHIP_AIR_PATCH = UUID.fromString("49535343-aca3-481c-91ec-d85e28a60318"); // has a Client Characteristic Configuration descriptor, value [0, 0]

    // Current bluetooth state
    public static final int BT_STATE_DISCONNECTED = 0;
    public static final int BT_STATE_CONNECTING = 1;
    public static final int BT_STATE_CONNECTED = 2;
    private static final String[] BT_STATE_NAMES = {
            "DISCONNECTED", "CONNECTING", "CONNECTED"
    };
    private int mBluetoothState = BT_STATE_DISCONNECTED;
    private String mBluetoothStateReason = "Not connected yet";

    /**
     * The identifier for the notification displayed for the foreground service.
     */
    private static final int NOTIFICATION_ID = 87654321;

    // Interface used by processes to communicate with this service.
    public class LocalBinder extends Binder {
        public SonarService getService() {
            return SonarService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Flag used to indicate whether the bound activity has really gone away, and is not just
     * unbound as part of an orientation change.
     */
    private boolean mJustAConfigurationChange = false;

    private static final String NOTIFICATION_CHANNEL_ID = TAG;
    private static final String EXTRA_STARTED_FROM_NOTIFICATION = "com.cdot.ping.services.SonarService.started_from_notification";

    // Minimum depth change between recorded samples
    public static final double MINIMUM_DELTA_DEPTH_DEFAULT = 0.5; // metres

    private double mMinDeltaDepth = MINIMUM_DELTA_DEPTH_DEFAULT;

    private int mLastLoggedBattery = -1;
    private double mLastLoggedDepth = -1;
    private BluetoothGatt mBluetoothGatt;
    private GattQueue mGattQueue;

    private String mSampleFile = null; // URI being sampled to
    private PrintWriter mSampleWriter = null; // non-null when sampling is active

    class GattBack extends GattQueue.Callback {
        // invoked when we have connected/disconnected to/from a remote GATT server
        @Override // GattQueue.Callback
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            // BluetoothProfile.STATE_CONNECTED is handled in the superclass. The status isn't
            // moved to BT_STATE_CONNECTED until services have been discovered successfully.
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (mBluetoothGatt != gatt)
                    // mBluetoothGatt should have been set from the result of connectGatt in connect()
                    Log.e(TAG, "mBluetoothGatt and gatt differ! Not expected");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setState(BT_STATE_DISCONNECTED, "Disconnected from " + gatt.getDevice().getName());
                if (mBluetoothGatt != null & gatt.getDevice().getAddress().equals(mBluetoothGatt.getDevice().getAddress()))
                    disconnect("cleaning up");

                Intent intent = new Intent(ACTION_DISCONNECTED);
                intent.putExtra(EXTRA_DEVICE_ADDRESS, gatt.getDevice().getAddress());
                intent.putExtra(EXTRA_DISCONNECT_REASON, REASON_CONNECTION_LOST);
                sendBroadcast(intent);

                // Try to reconnect
                Log.d(TAG, "Attempting to reconnect to " + gatt.getDevice().getName());
                connect(gatt.getDevice());
            }
        }

        // Once a device has been connected, superclass starts service discovery. This callback
        // is invoked when the list of remote services, characteristics and descriptors for the
        // remote device have been updated
        @Override // GattQueue.Callback
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect("onServicesDiscovered failed");
                return;
            }

            Log.d(TAG, "Service discovered " + gatt.getDevice().getName());

            // Enable push notification from the BTC_CUSTOM service
            BluetoothGattService bgs = gatt.getService(BTS_CUSTOM);
            if (bgs == null) {
                disconnect("Device does not offer service BTS_CUSTOM");
                return;
            }

            BluetoothGattCharacteristic cha = bgs.getCharacteristic(BTC_CUSTOM_SAMPLE);
            if (cha == null) {
                disconnect("Device does not offer characteristic BTC_CUSTOM_SAMPLE");
                return;
            }

            // Tell the Bluetooth stack that it should forward any received notification to the app
            gatt.setCharacteristicNotification(cha, true);
            // onCharacteristicChanged callback will be triggered if the
            // device indicates that the given characteristic has changed.

            // Tell the device to activate characteristic change notifications
            BluetoothGattDescriptor descriptor = cha.getDescriptor(BTD_CLIENT_CHARACTERISTIC_CONFIGURATION);
            if (descriptor == null) {
                disconnect("Device does not offer descriptor BTD_CLIENT_CHARACTERISTIC_CONFIGURATION");
                return;
            }

            Log.d(TAG, "Writing notification enable descriptor");
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mGattQueue.queue(new GattQueue.DescriptorWrite(descriptor));

            // Tell the world we are ready for action
            setState(BT_STATE_CONNECTED, "Connected");
            Intent intent = new Intent(ACTION_CONNECTED);
            intent.putExtra(EXTRA_DEVICE_ADDRESS, mBluetoothGatt.getDevice().getAddress());
            sendBroadcast(intent);
        }

        // triggered as a result of a remote characteristic notification
        @Override // BluetoothGattCallback
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data.length < 14 || data[0] != ID0 || data[1] != ID1)
                throw new IllegalArgumentException("Bad data block");
            // Wonder what data[5] is? Seems to be always 9
            //Log.d(TAG, "[5]=" + Integer.toHexString(data[5]));
            // There are several more bytes in the packet; wonder what they do?

            /*Log.d(TAG, "Sample received from BLE peripheral"
                    + (((data[4] & 0x8) != 0) ? " dry" : "")
                    // Convert feet to metres
                    + " D " + ((float) data[6] + (float) data[7] / 100.0f) + "ft"
                    + " S " + data[8]
                    // Convert feet to metres
                    + " fD " + ((float) data[9] + (float) data[10] / 100f)
                    + " fS " + (data[11] & 0xF)
                    + " bat " + ((data[11] >> 4) & 0xF)
                    + " T " + ((float) data[12] + (float) data[13]) + "°F");*/

            Bundle b = new Bundle();
            b.putInt("battery", (data[11] >> 4) & 0xF);
            b.putDouble("depth", (data[4] & 0x8) != 0 ? 0 : (float) (ft2m * ((double) data[6] + (double) data[7] / 100.0)));
            b.putDouble("fishDepth", ft2m * ((double) data[9] + (double) data[10] / 100.0));
            // Fish strength is in a nibble, so potentially in the range 0-15. 0 is easy, it means no mid-water
            // return. Above that, let's think. The beam is 90 degrees, so the size of the object is surely
            // proportional to the depth? Unless that is already computed in the return. The maximum size of
            // a fish is going to be a metre. At 36 meters that corresponds to 1.6 degrees of arc. Or
            // does it vary according to the range? Do we care? Just return it as a percentage.
            b.putDouble("fishStrength", 100.0 * (data[11] & 0xF) / 16.0);
            b.putDouble("strength", 100.0 * data[8] / 128.0); // Guess our max is 128
            b.putDouble("temperature", ((double) data[12] + (double) data[13] / 100.0 - 32.0) * 5.0 / 9.0);

            logSample(b);
        }
    }

    /**
     * Set the current BT_STATE for the service
     *
     * @param state  a BT_STATE
     * @param reason the reson for the change to this state
     */
    public void setState(int state, String reason) {
        Log.d(TAG, "State change to " + BT_STATE_NAMES[state] + " " + reason);
        mBluetoothState = state;
        mBluetoothStateReason = reason;
    }

    /**
     * Get the current BT_STATE for the service
     *
     * @return a BT_STATE
     */
    public int getState() {
        return mBluetoothState;
    }

    /**
     * Get the reason for the current BT_STATE for the service
     *
     * @return a reason
     */
    public String getStateReason() {
        return mBluetoothStateReason;
    }

    /**
     * Get the device the service is currently connected to
     *
     * @return the connected device
     */
    public BluetoothDevice getConnectedDevice() {
        if (mBluetoothGatt == null || mBluetoothGatt.getDevice() == null)
            return null;
        return mBluetoothGatt.getDevice();
    }

    @Override // Service
    public void onCreate() {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // Android O requires a Notification Channel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the channel for the notification
            NotificationChannel mChannel =
                    new NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT);

            // Set the Notification Channel for the Notification Manager.
            mNotificationManager.createNotificationChannel(mChannel);
        }
    }

    // The system invokes this method by calling startService() when another component
    // (such as an activity) requests that the service be started. When this method executes,
    // the service is started and can run in the background indefinitely.
    // It is your responsibility to stop the service when its work is
    // complete by calling stopSelf() or stopService().
    @Override // Service
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service started");
        boolean startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION,
                false);

        // We got here because the user decided to remove updates from the notification.
        if (startedFromNotification) {
            //removeLocationUpdates();
            stopSelf();
        }

        // Note that we don't start trying to connect to a bluetooth device. That's done
        // in startAutoConnect().

        // Tells the system to not try to recreate the service after it has been killed.
        return START_NOT_STICKY;
    }

    // Called when device configuration changes, such as the orientation. Used to flag that
    // this is a configuration change to onUnbind()
    @Override // Service
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mJustAConfigurationChange = true;
    }

    // "The system invokes this method by calling bindService() when another component wants to
    // bind with the service (such as to perform RPC). In your implementation of this method,
    // you must provide an interface that clients use to communicate with the service by returning
    // an IBinder. You must always implement this method; however, if you don't want to allow
    // binding, you should return null."
    //
    // Called when MainActivity comes to the foreground and binds with this service. The service
    // should cease to be a foreground service when that happens.
    @Override // Service
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind()");
        stopForeground(true);
        mJustAConfigurationChange = false;
        return mBinder;
    }

    // Called when MainActivity returns to the foreground and binds once again with this service.
    // The service should cease to be a foreground service when that happens.
    @Override // Service
    public void onRebind(Intent intent) {
        Log.i(TAG, "onRebind()");
        stopForeground(true);
        mJustAConfigurationChange = false;
        super.onRebind(intent);
    }

    // Called when MainActivity unbinds from this service. If this method is called due to a
    // configuration change (such as a reorientation of the device) do nothing. Otherwise,
    // if sampling is active, make this service a foreground service.
    @Override // Service
    public boolean onUnbind(Intent intent) {

        if (mJustAConfigurationChange) {
            Log.d(TAG, "Unbinding due to a configuration change");
        } else if (mSampleWriter != null) {
            Log.i(TAG, "Starting foreground service");
            startForeground(NOTIFICATION_ID, getNotification());
        } else if (mBluetoothGatt != null) {
            Log.i(TAG, "Last client unbound from service and not sampling, closing service");
            stopSelf();
        }

        return true; // Ensures onRebind() is called when a client re-binds.
    }

    // The system invokes this method when the service is no longer used and is being destroyed.
    // Your service should implement this to clean up any resources such as threads, registered
    // listeners, or receivers. This is the last call that the service receives.
    @Override // Service
    public void onDestroy() {
        // Clean up autoconnect thread, if it's running. Release Gatt.
        //NOP? mServiceHandler.removeCallbacksAndMessages(null);
        if (mBluetoothGatt != null) {
            Log.d(TAG, "Closing GATT in onDestroy");
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
    }

    /**
     * Returns the {@link NotificationCompat} used as part of the foreground service.
     */
    private Notification getNotification() {
        Intent intent = new Intent(this, LocationService.class);

        // Extra to help us figure out if we arrived in onStartCommand via the notification or not.
        intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true);

        // The PendingIntent that leads to a call to onStartCommand() in this service.
        PendingIntent servicePendingIntent = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        // The PendingIntent to launch activity.
        PendingIntent activityPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        CharSequence text = "Depth " + mLastLoggedDepth;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .addAction(R.drawable.ic_launcher, getString(R.string.launch_activity),
                        activityPendingIntent)
                .addAction(R.drawable.ic_cancel, getString(R.string.stop_sonar_service),
                        servicePendingIntent)
                .setContentText(text)
                .setContentTitle(getString(R.string.sonar_updated, DateFormat.getDateTimeInstance().format(new Date())))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setTicker(text)
                .setWhen(System.currentTimeMillis());

        // Set the Channel ID for Android O.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(NOTIFICATION_CHANNEL_ID); // Channel ID
        }

        return builder.build();
    }

    /**
     * Connect to a bluetooth device. Will disconnect() currently connected device
     * first if it is not the desired device.
     *
     * @return false if the connection attempt failed. true doesn't mean it succeeded (the
     * connection may be being made by another thread) just that it hasn't failed (yet)
     */
    public boolean connect(BluetoothDevice btd) {

        Log.d(TAG, "connect(" + btd.getAddress() + ")");

        if (mBluetoothGatt != null
                && !mBluetoothGatt.getDevice().getAddress().equals(btd.getAddress())) {
            // Connected to wrong device
            mBluetoothGatt.close(); // TODO: Wait for callback!
            mBluetoothGatt = null;
        }

        if (mBluetoothGatt == null) {
            // New connection
            setState(BT_STATE_CONNECTING, "connect()ing using new BluetoothGatt");
            GattQueue.Callback cb = new GattBack();
            mBluetoothGatt = btd.connectGatt(this,
                    false, // Don't wait for device to become available
                    cb, // Callback
                    BluetoothDevice.TRANSPORT_LE);
            mGattQueue = new GattQueue(mBluetoothGatt, cb);
            return true;
        } else if (mBluetoothState >= BT_STATE_CONNECTING)
            // BT_CONNECTING or BT_CONNECTED
            return true;

        // Re-connect to a remote device after the connection has been dropped. If the
        // device is not in range, the re-connection will be triggered once the device
        // is back in range.
        Log.d(TAG, "connect()ing using existing BluetoothGatt");
        if (mBluetoothGatt.connect()) {
            // setState(BT_STATE_CONNECTED, "using existing BluetoothGatt"); // not until onStateChanged!
            return true;
        } else {
            setState(BT_STATE_DISCONNECTED, "BluetoothGatt.connect failed");
            return false;
        }
    }

    /**
     * Disconnects an established connection, or cancels a connection attempt currently in progress.
     */
    private void disconnect(String reason) {
        Log.d(TAG, "disconnect because " + reason);
        setState(BT_STATE_DISCONNECTED, reason);
        if (mBluetoothGatt == null)
            return;
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Configuration reverse-engineered by sniffing packets sent by the official FishFinder software
     *
     * @param sensitivity   1..10
     * @param noise         filtering 0..4 (off, low, med, high)
     * @param range         0..6 (3, 6, 9, 18, 24, 36, auto)
     * @param minDeltaDepth min depth change, in metres
     * @param sampleFile    name of sample file, null to disable sampling
     */
    public void configure(int sensitivity, int noise, int range, double minDeltaDepth, String sampleFile) {
        Log.d(TAG, "configure(" + sensitivity + "," + noise + "," + range + "," + minDeltaDepth + "," + sampleFile + ")");
        mMinDeltaDepth = minDeltaDepth;
        if (sampleFile == null) {
            if (mSampleFile != null)
                stopLogging();
        } else if (!sampleFile.equals(mSampleFile)) {
            if (mSampleFile != null)
                stopLogging();
            startLogging(sampleFile);
        }

        if (mBluetoothState != BT_STATE_CONNECTED)
            return;

        BluetoothGattService service = mBluetoothGatt.getService(BTS_CUSTOM);
        if (service == null) {
            Log.e(TAG, "No service " + BTS_CUSTOM);
            return;
        }

        byte[] data = new byte[]{
                // http://ww1.microchip.com/downloads/en/DeviceDoc/50002466B.pdf
                ID0, ID1,
                0, 0, COMMAND_CONFIGURE, // SF?
                3, // size
                (byte) sensitivity, (byte) noise, (byte) range,
                0, 0, 0 // why the extra zeros?
        };
        // Compute checksum
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += data[i];
        }
        data[9] = (byte) (sum & 255);

        BluetoothGattCharacteristic charaWrite = service.getCharacteristic(BTC_CUSTOM_CONFIGURE);
        if (charaWrite == null) {
            Log.e(TAG, "Device does not support BTC_CUSTOM_CONFIGURE");
            return;
        }

        int charaProp = charaWrite.getProperties();
        if ((charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0)
            Log.e(TAG, "BTC_CUSTOM_CONFIGURE has no PROPERTY_WRITE");

        Log.d(TAG, "Writing BTC_CUSTOM_CONFIGURE");
        charaWrite.setValue(data);
        charaWrite.setWriteType(BluetoothGattCharacteristic./*WRITE_TYPE_DEFAULT*/WRITE_TYPE_NO_RESPONSE);
        mGattQueue.queue(new GattQueue.CharacteristicWrite(charaWrite));
    }

    private void startLogging(String suri) {
        Uri uri = Uri.parse(suri);
        // Check it exists, create it with appropriate header if not
        try {
            AssetFileDescriptor afd;
            FileWriter fw = null;
            try {
                // TODO: fix this
                getContentResolver().openAssetFileDescriptor(uri, "r").close();
                afd = getContentResolver().openAssetFileDescriptor(uri, "wa");
                fw = new FileWriter(afd.getFileDescriptor());
            } catch (FileNotFoundException fnfe) {
                afd = getContentResolver().openAssetFileDescriptor(uri, "w");
                fw = new FileWriter(afd.getFileDescriptor());
            }
            mSampleWriter = new PrintWriter(fw);
            Log.d(TAG, "Recording to " + uri);
            // Force the next sample to be recorded
            mLastLoggedBattery = -1;
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to create " + uri + ioe);
        }
    }

    private void stopLogging() {
        if (mSampleWriter != null) {
            mSampleWriter.close();
            mSampleWriter = null;
        }
    }

    /**
     * Update the last recorded sample
     */
    private void logSample(Bundle sample) {
        int battery = sample.getInt("battery");
        double temperature = sample.getDouble("temperature");
        double depth = sample.getDouble("depth");
        double strength = sample.getDouble("strength");
        double fishDepth = sample.getDouble("fishDepth");
        double fishStrength = sample.getDouble("fishStrength");

        boolean accepted = mLastLoggedBattery < 0
                || battery != mLastLoggedBattery
                || Math.abs(depth - mLastLoggedDepth) >= mMinDeltaDepth;

        if (!accepted)
            return;

        mLastLoggedBattery = battery;
        mLastLoggedDepth = depth;

        if (mSampleFile != null) {
            mSampleWriter.print((new Date()));
            mSampleWriter.print(',');
            mSampleWriter.print(depth);
            mSampleWriter.print(',');
            mSampleWriter.print(strength);
            mSampleWriter.print(',');
            mSampleWriter.print(temperature);
            mSampleWriter.print(',');
            mSampleWriter.print(battery);
            mSampleWriter.print(',');
            mSampleWriter.print(fishDepth);
            mSampleWriter.print(',');
            mSampleWriter.print(fishStrength);
            mSampleWriter.println();
            Log.d(TAG, "Recorded sonar sample");
        }

        Intent intent = new Intent(ACTION_SAMPLE);
        intent.putExtra(EXTRA_SAMPLE_DATA, sample);
        sendBroadcast(intent);
    }
}
