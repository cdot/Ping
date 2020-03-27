package com.cdot.ping.devices;

import android.app.Service;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.location.Location;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Abstract base class of device services classes. There should be a different class for each of
 * the different types of device i.e. Bluetooth Classic or LE (and maybe more later). Right now we only
 * support LE, but classic might be added.
 * <p>
 * The service has to collect data from sampling sources - normally a bluetooth device, and the GPS
 * integrated into the phone - and compile these data into a SampleData packet. These packets are
 * then broadcast and/or recorded to a sample file. Callers indicate to the service if they are
 * actively listening to broadcasts, and where they want samples to be written.
 */
public abstract class DeviceService extends Service {
    private final static String TAG = "DeviceService";

    public static final String ACTION_DATA_AVAILABLE = "com.cdot.ping.devices.ACTION_DATA_AVAILABLE";
    public static final String ACTION_CONNECTED = "com.cdot.ping.devices.ACTION_CONNECTED";
    public static final String ACTION_DISCONNECTED = "com.cdot.ping.devices.ACTION_DISCONNECTED";
    public static final String DEVICE_ADDRESS = "com.cdot.ping.devices.DEVICE_ADDRESS";
    public static final String REASON = "com.cdot.ping.devices.REASON";
    public static final String SAMPLE = "com.cdot.ping.devices.SAMPLE";

    // REASONs sent with ACTION_DISCONNECTED
    public static final int CANNOT_CONNECT = 0;
    public static final int CONNECTION_LOST = 1;

    // Sample collation and serialisation
    protected Sampler mSampler = new Sampler(this);
    private Uri mSampleFile = null;
    private boolean mBroadcastingOn = true;

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
     *
     * @param device device to connect to
     * @return false if the connection attempt failed. true doesn't mean it succeeded (the
     * connection may be being made by another thread) just that it hasn't failed (yet)
     */
    public boolean connect(DeviceRecord device) {
        startLocationSampling();
        mBroadcastingOn = true;
        return true;
    }

    /**
     * Disconnects an established connection, or cancels a connection attempt currently in progress.
     */
    public void disconnect() {
        stopLocationSampling();
    }

    ;

    /**
     * Subclasses will use sensitivity, noise and range.
     *
     * @param sensitivity 1..10
     * @param noise       filtering 0..4 (off, low, med, high)
     * @param range       0..6 (3, 6, 9, 18, 24, 36, auto)
     * @param minDD       min delta depth before new sample broadcast/recorded
     * @param minDP       min delta distance moved before new sample broadcast/received
     */
    public void configure(int sensitivity, int noise, int range, float minDD, float minDP) {
        mSampler.configure(minDD, minDP);
    }

    /**
     * Set sample recording on/off
     *
     * @param sf file to sample to, or null
     */
    public void recordTo(Uri sf) {
        mSampleFile = sf;
        // Check it exists, create it with appropriate header if not
        try {
            try {
                getContentResolver().openAssetFileDescriptor(mSampleFile, "r").close();
            } catch (FileNotFoundException fnfe) {
                AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(mSampleFile, "w");
                FileWriter fw = new FileWriter(afd.getFileDescriptor());
                fw.write(SampleData.csvFields);
                fw.close();
                afd.close();
            }
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to create " + mSampleFile + ioe);
            mSampleFile = null;
        }
        Log.d(TAG, "Record to " + sf);
    }

    /**
     * Set sample broadcasting on/off
     * TODO: do we really need this? Is there any reason to stop broadcasting when the app is paused?
     *
     * @param on if broadcasting is required
     */
    public void setBroadcasting(boolean on) {
        mBroadcastingOn = on;
        Log.d(TAG, "Broadcasting " + on);
    }

    /**
     * Closes down the service
     */
    public void close() {
        stopLocationSampling();
    }

    /**
     * Location sampling uses the FusedLocationProviderClient API to request location updates
     * 2 times for each stored sample, ensuring we have a reasonably up-to-date location.
     * Note that in our application, the location is not going to change rapidly - certainly less
     * than 10m/s.
     */
    // Connect to location services
    private FusedLocationProviderClient mLocationClient = null;
    private LocationCallback mLocationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult locationResult) {
            if (locationResult == null)
                return;
            //Log.d(TAG, "Location received");
            for (Location location : locationResult.getLocations()) {
                mSampler.updateLocation(location);
            }
        }
    };
    private boolean mIsLocationSampling = false;

    private void startLocationSampling() {
        if (mIsLocationSampling)
            return;

        if (mLocationClient == null)
            mLocationClient = LocationServices.getFusedLocationProviderClient(this);

        LocationRequest lr = LocationRequest.create();
        lr.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        // Sample every second. Should be plenty.
        lr.setInterval(1000);
        //lr.setSmallestDisplacement(Ping.getMinimumPositionChange());

        try {
            mLocationClient.requestLocationUpdates(lr, mLocationCallback, null);
            mIsLocationSampling = true;
        } catch (SecurityException se) {
            throw new Error("Unexpected security exception");
        }
    }

    private void stopLocationSampling() {
        if (!mIsLocationSampling)
            return;
        mLocationClient.removeLocationUpdates(mLocationCallback);
        mIsLocationSampling = false;
    }

    /**
     * Broadcast and/or log the sample, depending on what has been asked for.
     *
     * @param data data to send/record
     */
    void sample(SampleData data) {
        if (mBroadcastingOn) {
            // Someone is listening to us
            Intent intent = new Intent(DeviceService.ACTION_DATA_AVAILABLE);
            intent.putExtra(DeviceService.SAMPLE, data.getBundle());
            sendBroadcast(intent);
            Log.d(TAG, "Broadcast sample " + data.uid);
        }

        if (mSampleFile != null) {
            // Going to have to ask for permission
            try {
                AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(mSampleFile, "wa");
                FileWriter fw = new FileWriter(afd.getFileDescriptor());
                fw.write(data.toString());
                fw.close();
                afd.close();
                Log.d(TAG, "Recorded sample " + data.uid);
            } catch (IOException ioe) {
                throw new Error("IO exception writing " + mSampleFile + ": " + ioe);
            }
        }
    }
}
