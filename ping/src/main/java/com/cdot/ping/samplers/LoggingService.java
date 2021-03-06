/*
 * Copyright © 2020 C-Dot Consultants
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.cdot.ping.samplers;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.cdot.location.GPX;
import com.cdot.location.LocationSampler;
import com.cdot.ping.MainActivity;
import com.cdot.ping.R;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Log incoming samples to a file.
 * <p>
 * This is a bound and started service that is automatically promoted to a foreground service
 * when all clients unbind. This is so sampling can continue even when the foreground app is closed.
 * <p>
 * In this implementation, when the activity is removed from the foreground the service
 * automatically promotes itself to a foreground service with a Notification, and frequent
 * location updates continue. When the activity comes back to the foreground, the
 * foreground service stops, and the Notification is removed.
 * <p>
 * Substantially based on https://github.com/android/location-samples
 */
public class LoggingService extends Service implements LocationSampler.SampleListener {
    protected static final String CLASS_NAME = LoggingService.class.getCanonicalName();
    public static final String ACTION_SAMPLE = CLASS_NAME + ".action_sample";
    public static final String EXTRA_SAMPLE_DATA = CLASS_NAME + ".sample_data";
    // Extra to tell us if we arrived in onStartCommand from the Notification
    protected static final String EXTRA_STARTED_FROM_NOTIFICATION =
            CLASS_NAME + ".started_from_notification";
    private static final String TAG = LoggingService.class.getSimpleName();
    /**
     * The identifier for the notification displayed for the foreground service.
     * "If a notification with the same id has already been posted by your application and has
     * not yet been canceled, it will be replaced by the updated information", so each subclass has
     * to provide a unique ID.
     */
    private static final int NOTIFICATION_1D = 0xC0FEFE;
    private static final String CHANNEL_ID = "Ping_Channel" + TAG;
    /**
     * The desired interval for location updates. Inexact. Updates may be more or less frequent.
     * For Ping, every 500ms should be plenty.
     */
    private static final long LOCATION_UPDATE_INTERVAL = 500;
    // Name of sample cache file. Always stored in getExternalFilesDir()
    public static String CACHEFILE_NAME = "ping.log";
    private final IBinder mBinder = new LoggingServiceBinder();
    /**
     * Public to allow access to device control methods
     */
    public SonarBluetooth mSonarSampler;
    /**
     * Used to check whether the bound activity has really gone away and not unbound as part of an
     * orientation change. We create a foreground service notification only if the former takes
     * place.
     */
    protected boolean mJustAConfigurationChange = false;
    protected NotificationManager mNotificationManager;
    private LocationSampler mLocationSampler;
    // True to keep this service running even when all clients are unbound
    // and logging is disabled. If it is false, the service will die whenever all clients unbind,
    // which includes when switching to an activity that doesn't bind the service, such as
    // Settings
    private static final boolean KEEP_ALIVE = true;
    private double mLoggedSampleRate = 0; // rolling average sampling rate
    private long mTotalSamplesLogged = 0;
    private long mLastSampleTime = System.currentTimeMillis();
    private SampleCache mCache;

    @Override // Service
    public void onCreate() {
        Log.d(TAG, "onCreate");

//        mSonarSampler = new SonarBluetooth(this, new SonarBLE(this));
        mSonarSampler = new SonarBluetooth(this, new SonarBLE(this));
        mLocationSampler = new LocationSampler(this, this, LOCATION_UPDATE_INTERVAL);

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            mCache = new SampleCache(new File(getExternalFilesDir(null), CACHEFILE_NAME), false);
        } catch (FileNotFoundException fnfe) {
            try {
                mCache = new SampleCache(new File(getExternalFilesDir(null), CACHEFILE_NAME), 1024);
            } catch (IOException ioe) {
                Log.e(TAG, "Problem creating log file " + ioe);
            }
        } catch (IOException ioe) {
            Log.e(TAG, "Problem opening log file " + ioe);
        }
        // Android O requires a Notification Channel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.app_name);
            // Create the channel for the notification
            NotificationChannel channel = // IMPORTANCE_LOW makes it silent, otherwise it screams
                    new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW);

            // TODO: This is supposed to change the sound played by the notification. Doesn't seem to do anything, AFAICT code is correct
            Uri soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + getApplicationContext().getPackageName() + "/" + R.raw.ping);
            AudioAttributes.Builder ab = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ab.setHapticChannelsMuted(true);
            channel.setSound(soundUri, ab.build());

            // Set the Notification Channel for the Notification Manager.
            mNotificationManager.createNotificationChannel(channel);
        }
    }

    @Override // Service
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        boolean startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION, false);

        // We got here because the user decided to kill the service from the notification.
        if (startedFromNotification) {
            Log.d(TAG, "stopped from notification");
            mSonarSampler.disconnect();
            mLocationSampler.stopSampling();
            stopSelf();
        } else
            Log.d(TAG, "started");
        // Tells the system to not try to recreate the service after it has been killed.
        return START_NOT_STICKY;
    }

    @Override // Service
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged()");
        mJustAConfigurationChange = true;
    }

    @Override // Service
    public IBinder onBind(Intent intent) {
        // Called when a client (MainActivity in case of this sample) comes to the foreground
        // and binds with this service. The service should cease to be a foreground service
        // when that happens.
        Log.d(TAG, "onBind() conf " + mJustAConfigurationChange + " fg " + isRunningInForeground());
        stopForeground(true);
        mSonarSampler.broadcastStatus();
        mJustAConfigurationChange = false;
        //mLocationSampler.onBind();
        return mBinder;
    }

    @Override // Service
    public void onRebind(Intent intent) {
        // Called when a client (MainActivity in case of this sample) returns to the foreground
        // and binds once again with this service. The service should cease to be a foreground
        // service when that happens.
        Log.d(TAG, "onRebind() conf " + mJustAConfigurationChange + " fg " + isRunningInForeground());
        stopForeground(true);
        mSonarSampler.broadcastStatus();
        mJustAConfigurationChange = false;
        super.onRebind(intent);
    }

    @Override // Service
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind()");

        if (mJustAConfigurationChange) {
            Log.d(TAG, "Unbinding due to a configuration change");
        } else if (KEEP_ALIVE) {
            Log.d(TAG, "Starting foreground service");
            startForeground(NOTIFICATION_1D, getNotification());
            playSound(R.raw.whoop);
        } else {
            // Service no longer required. Given KEEP_ALIVE=true, this is unreachable.
            // Keeping the code around in case it's ever wanted again e.g. to shut down the service
            // when the app quits.
            Log.d(TAG, "All unbound");
            mSonarSampler.disconnect();
            mLocationSampler.stopSampling();
            stopSelf();
        }

        return true; // Ensures onRebind() is called when a client re-binds.
    }

    void playSound(int sound) {
        Uri soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + getApplicationContext().getPackageName() + "/" + sound);
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(this, soundUri);
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play sound " + e);
        }
    }

    @Override // Service
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        mSonarSampler.close();
        mLocationSampler.stopSampling();
    }

    public BluetoothDevice getConnectedDevice() {
        if (mSonarSampler == null)
            return null;
        return mSonarSampler.getBluetoothDevice();
    }

    // Returns the {@link NotificationCompat} displayed in the notification drawers.
    private Notification getNotification() {
        Intent intent = new Intent(this, this.getClass());

        // Tell onStartCommand it was called from the Notification
        intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true);

        // The PendingIntent that leads to a call to onStartCommand() in this service.
        PendingIntent servicePendingIntent = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        // The PendingIntent to launch activity.
        PendingIntent activityPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);
        Resources r = getResources();

        String rationale = mSonarSampler.mBluetoothStateReason < 0 ? "" : r.getStringArray(R.array.bt_reason)[mSonarSampler.mBluetoothStateReason];
        String state = String.format(r.getStringArray(R.array.bt_state)[mSonarSampler.getConnectionState()], rationale);

        String title = getString(R.string.notification_title, DateFormat.getDateTimeInstance().format(new Date()), state);
        StringBuilder samText = new StringBuilder();
        Sample sam = mSonarSampler.mLastLoggedSample;
        if (sam == null)
            samText.append(r.getString(R.string.depth_unknown));
        else {
            samText.append(r.getString(R.string.val_depth, sam.depth));
            samText.append(" ")
                    .append(r.getString(R.string.val_latitude, sam.latitude))
                    .append(" ")
                    .append(r.getString(R.string.val_longitude, sam.longitude));
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                // This will restore the activity
                .addAction(R.drawable.ic_launcher, getString(R.string.notification_launch), activityPendingIntent)

                // This will stop the service
                .addAction(R.drawable.ic_cancel, getString(R.string.notification_stop), servicePendingIntent)

                .setContentTitle(title)
                .setContentText(samText)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setOnlyAlertOnce(true)
                .setWhen(System.currentTimeMillis());

        return builder.build();
    }

    // Returns true if this is currently a foreground service.
    private boolean isRunningInForeground() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (getClass().getName().equals(service.service.getClassName()) && service.foreground)
                return true;
        }
        return false;
    }

    // Called from LocationSampler when a new location has been identified - really package-private
    @Override // LocationSampler.SamplerListener
    public void onLocationSample(Location loc) {
        mSonarSampler.setLocation(loc);
    }

    // Called from SonarSampler
    void logSample(Sample sample) {
        // "real" device has a sample rate around 8Hz
        long now = System.currentTimeMillis();
        if (now > mLastSampleTime) {
            double samplingRate = 1000.0 / (now - mLastSampleTime);
            mLoggedSampleRate = ((mLoggedSampleRate * mTotalSamplesLogged) + samplingRate) / (mTotalSamplesLogged + 1);
            mTotalSamplesLogged++;
        }
        mLastSampleTime = now;

        if (mCache != null) {
            try {
                mCache.add(sample);
            } catch (IOException ioe) {
                Log.e(TAG, "logSample " + ioe);
            }
        }

        // Update notification content if running as a foreground service.
        if (isRunningInForeground()) {
            mNotificationManager.notify(NOTIFICATION_1D, getNotification());
        } else {
            //Log.d(TAG, "Broadcasting sample");
            Intent intent = new Intent(ACTION_SAMPLE);
            intent.putExtra(EXTRA_SAMPLE_DATA, sample);
            sendBroadcast(intent);
        }
    }

    /**
     * Configuration. The first three parameters are sent to the sonar device.
     *
     * @param sensitivity   1..10
     * @param noise         filtering 0..4 (off, low, med, high)
     * @param range         0..6 (3, 6, 9, 18, 24, 36, auto)
     * @param minDeltaDepth min depth change, in metres
     * @param minDeltaPos   min location change, in metres
     * @param sampleTimeout timeout waiting for a sample before we abandon the connection and try a different device. 0 means never.
     * @param maxSamples    number of samples that must be accomodated in the sample buffer
     */
    public void configure(int sensitivity, int noise, int range, float minDeltaDepth, float minDeltaPos, int sampleTimeout, int maxSamples) {
        mSonarSampler.configure(sensitivity, noise, range, minDeltaDepth, minDeltaPos, sampleTimeout);
        if (mCache == null)
            return;
        try {
            mCache.setCapacitySamples(maxSamples);
        } catch (IOException ioe) {
            Log.e(TAG, "Problem changing log size " + ioe);
        }
    }

    /**
     * Write GPX XML for all cached samples to the given Uri. All samples are written, whether they
     * has already been written or not.
     *
     * @param uri the uri to create the GPX document at
     */
    public void writeGPX(Uri uri) throws IOException {
        Document gpxDocument = GPX.openDocument(getContentResolver(), uri, getResources().getString(R.string.app_name));

        // Create new trkseg element for this trace
        Element gpxTrk = (Element) gpxDocument.getElementsByTagNameNS(GPX.NS_GPX, "trk").item(0);
        Element gpxTrkseg = gpxDocument.createElementNS(GPX.NS_GPX, "trkseg");
        gpxTrk.appendChild(gpxTrkseg);

        Sample[] snap = new Sample[mCache.getUsedSamples()];
        mCache.snapshot(snap, 0, snap.length);
        for (Sample s : snap)
            gpxTrkseg.appendChild(s.toGPX(gpxDocument));

        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            DOMSource source = new DOMSource(gpxDocument);
            AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "w");
            StreamResult result = new StreamResult(afd.createOutputStream());
            transformer.transform(source, result);
            afd.close();
            Log.d(TAG, "GPX written");
        } catch (TransformerException tce) {
            Log.e(TAG, "writeGPX failed " + tce);
        }
    }

    /**
     * Get the current average logged sampling rate, in Hz
     *
     * @return the sample rate
     */
    public double getLoggedSampleRate() {
        return mLoggedSampleRate;
    }

    /**
     * Get the current average raw sampling rate, in Hz
     *
     * @return the sample rate
     */
    public double getRawSampleRate() {
        return mSonarSampler.mRawSampleRate;
    }

    /**
     * Get the total number of samples logged in all time
     *
     * @return samples seen
     */
    public long getSamplesLogged() {
        return mTotalSamplesLogged;
    }

    /**
     * Get cache usage as a percentage of the available capacity
     *
     * @return a percentage
     */
    public float getCacheUsage() {
        return 100.0f * mCache.getUsedSamples() / mCache.getCapacitySamples();
    }

    /**
     * Service binder type
     */
    public class LoggingServiceBinder extends Binder {
        public LoggingService getService() {
            return LoggingService.this;
        }
    }
}
