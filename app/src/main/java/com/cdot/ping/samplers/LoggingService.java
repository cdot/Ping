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
import android.location.Location;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.cdot.ping.MainActivity;
import com.cdot.ping.R;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import java.io.FileInputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
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
public class LoggingService extends Service {
    public static final String TAG = LoggingService.class.getSimpleName();

    protected static final String CLASS_NAME = LoggingService.class.getCanonicalName();

    public static final String ACTION_SAMPLE = CLASS_NAME + ".action_sample";

    public static final String EXTRA_SAMPLE_DATA = CLASS_NAME + ".sample_data";

    // Extra to tell us if we arrived in onStartCommand from the Notification
    protected static final String EXTRA_STARTED_FROM_NOTIFICATION =
            CLASS_NAME + ".started_from_notification";

    /**
     * Used to check whether the bound activity has really gone away and not unbound as part of an
     * orientation change. We create a foreground service notification only if the former takes
     * place.
     */
    protected boolean mJustAConfigurationChange = false;

    protected NotificationManager mNotificationManager;

    // Set to true to keep this service running even when all clients are unbound
    // and logging is disabled.
    private boolean mKeepAlive = false;

    /**
     * The identifier for the notification displayed for the foreground service.
     * "If a notification with the same id has already been posted by your application and has
     * not yet been canceled, it will be replaced by the updated information", so each subclass has
     * to provide a unique ID.
     */
    private static final int NOTIFICATION_1D = 0xC0FEFE;

    private static final String CHANNEL_ID = "Ping_Channel";

    public class LoggingServiceBinder extends Binder {
        public LoggingService getService() {
            return LoggingService.this;
        }
    }

    private IBinder mBinder = new LoggingServiceBinder();

    // Set of samplers that are providing sample updates to this logger
    Map<String, Sampler> mSamplers = new HashMap<>();

    public Sampler getSampler(String id) {
        return mSamplers.get(id);
    }

    private double mAverageSamplingRate = 0; // rolling average sampling rate
    private long mTotalSampleCount = 0;
    private long mLastSampleTime = System.currentTimeMillis();
    private long mRecordingStartTime = mLastSampleTime;

    private Uri mGPXFileUri;
    private Document mGPXDocument = null; // GPX document
    private Element mGPX_trkseg = null; // GPX <trk>...</trk>

    @Override // Service
    public void onCreate() {
        Log.d(TAG, "onCreate");

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // Android O requires a Notification Channel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.app_name);
            // Create the channel for the notification
            NotificationChannel channel = // low importance makes it silent
                    new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW);

            // TODO: This doesn't seem to do anything with IMPORTANCE_DEFAULT, AFAICT code is correct
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

    /**
     * This is how users of the service add samplers to it. Doesn't check if the sampler is already
     * there!
     *
     * @param s sampler to add
     */
    public void addSampler(Sampler s) {
        mSamplers.put(s.getTag(), s);
        s.onAttach(this);
    }

    public long getLoggingTime() {
        return System.currentTimeMillis() - mRecordingStartTime;
    }

    public double getAverageSamplingRate() {
        return mAverageSamplingRate;
    }

    public long getSampleCount() {
        return mTotalSampleCount;
    }

    public void setKeepAlive(boolean on) {
        mKeepAlive = on;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        boolean startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION, false);

        // We got here because the user decided to kill the service from the notification.
        if (startedFromNotification) {
            Log.d(TAG, "stopped from notification");
            for (Sampler s : mSamplers.values())
                s.stopSampling();
            stopSelf();
        } else
            Log.d(TAG, "started");
        // Tells the system to not try to recreate the service after it has been killed.
        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged()");
        mJustAConfigurationChange = true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Called when a client (MainActivity in case of this sample) comes to the foreground
        // and binds with this service. The service should cease to be a foreground service
        // when that happens.
        Log.d(TAG, "onBind() conf " + mJustAConfigurationChange + " fg " + isRunningInForeground());
        stopForeground(true);
        mJustAConfigurationChange = false;
        for (Sampler s : mSamplers.values())
            s.onBind();
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        // Called when a client (MainActivity in case of this sample) returns to the foreground
        // and binds once again with this service. The service should cease to be a foreground
        // service when that happens.
        Log.d(TAG, "onRebind() conf " + mJustAConfigurationChange + " fg " + isRunningInForeground());
        stopForeground(true);
        mJustAConfigurationChange = false;
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind()");

        if (mJustAConfigurationChange) {
            Log.d(TAG, "Unbinding due to a configuration change");
        } else if (mKeepAlive || mGPXDocument != null) {
            Log.d(TAG, "Starting foreground service");
            startForeground(NOTIFICATION_1D, getNotification());
        } else {
            // Service no longer required
            Log.d(TAG, "All unbound");
            for (Sampler s : mSamplers.values())
                s.stopSampling();
        }

        return true; // Ensures onRebind() is called when a client re-binds.
    }

    @Override // Service
    public void onDestroy() {
        // Why are we destroyed when the screen is rotated? Possibly because it is resource hungry.
        Log.d(TAG, "onDestroy");
        for (Sampler s : mSamplers.values()) {
            s.stopSampling();
            s.onDestroy();
        }
    }

    public void connectToDevice(BluetoothDevice btd) {
        for (Sampler s : mSamplers.values())
            s.connectToDevice(btd);
    }

    public BluetoothDevice getConnectedDevice() {
               for (Sampler s : mSamplers.values()) {
                   BluetoothDevice dev = s.getConnectedDevice();
                   if (dev != null) return dev;
               }
               return null;
    }
    /**
     * Returns the {@link NotificationCompat} displayed in the notification drawers.
     */
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

        StringBuilder text = new StringBuilder();
        for (Sampler s : mSamplers.values())
            text.append(s.getNotificationStateText(getResources())).append("\n");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                // Actions are typically displayed by the system as a button adjacent to the notification content.

                // This will restore the activity
                .addAction(R.drawable.ic_launcher, getString(R.string.notification_launch), activityPendingIntent)

                // This will stop the service
                .addAction(R.drawable.ic_cancel, getString(R.string.notification_stop), servicePendingIntent)

                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_title, DateFormat.getDateTimeInstance().format(new Date())))
                .setContentText(text)
                .setWhen(System.currentTimeMillis());

        return builder.build();
    }

    // Write XML to mLogUri
    private void saveSampleDocument() {
        try {
            AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(mGPXFileUri, "w");
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            DOMSource source = new DOMSource(mGPXDocument);
            StreamResult result = new StreamResult(afd.createOutputStream());
            transformer.transform(source, result);
            afd.close();
        } catch (Exception ignore) {
        }
    }

    /**
     * Returns true if this is currently a foreground service.
     */
    public boolean isRunningInForeground() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (getClass().getName().equals(service.service.getClassName()) && service.foreground)
                return true;
        }
        return false;
    }

    // Called from LocationSampler
    void onLocationSample(Location loc) {
        ((SonarSampler) getSampler(SonarSampler.TAG)).setLocation(loc);
    }

    // Called from SonarSampler
    void onSonarSample(Sample sample) {
        // "real" device has a sample rate around 12Hz
        long now = System.currentTimeMillis();
        double samplingRate = 1000.0 / (now - mLastSampleTime);
        mAverageSamplingRate = ((mAverageSamplingRate * mTotalSampleCount) + samplingRate) / (mTotalSampleCount + 1);
        mTotalSampleCount++;
        mLastSampleTime = now;

        if (mGPXDocument != null) {
            Element GPX_trkpt = sample.toGPX(mGPXDocument);
            mGPX_trkseg.appendChild(GPX_trkpt);
            saveSampleDocument();
        }

        // Update notification content if running as a foreground service.
        if (isRunningInForeground())
            mNotificationManager.notify(NOTIFICATION_1D, getNotification());
        else {
            //Log.d(TAG, "Broadcasting sample from " + source);
            Intent intent = new Intent(ACTION_SAMPLE);
            intent.putExtra(EXTRA_SAMPLE_DATA, sample);
            sendBroadcast(intent);
        }
    }

    public boolean isLogging() {
        return mGPXDocument != null;
    }

    /**
     * Start logging to the given URI.
     *
     * @param suri URI to log to
     */
    public boolean startLogging(String suri) {
        Log.d(TAG, "startLogging()");
        mGPXFileUri = Uri.parse(suri);
        // Check it exists, create it if not
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setValidating(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Element GPX_trk;
            try {
                AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(mGPXFileUri, "r");
                InputSource source = new InputSource(new FileInputStream(afd.getFileDescriptor()));
                mGPXDocument = db.parse(source);
                GPX_trk = (Element) mGPXDocument.getElementsByTagNameNS(GPX.NS_GPX, "trk").item(0);
                afd.close();
            } catch (Exception ouch) {
                Log.e(TAG, ouch.toString());
                mGPXDocument = db.newDocument();
                mGPXDocument.setDocumentURI(suri);
                mGPXDocument.setXmlVersion("1.1");
                mGPXDocument.setXmlStandalone(true);
                Element GPX_gpx = mGPXDocument.createElementNS(GPX.NS_GPX, "gpx");
                GPX_gpx.setAttribute("version", "1.1");
                GPX_gpx.setAttribute("creator", getApplicationContext().getResources().getString(R.string.app_name));
                mGPXDocument.appendChild(GPX_gpx);
                GPX_trk = mGPXDocument.createElementNS(GPX.NS_GPX, "trk");
                GPX_gpx.appendChild(GPX_trk);
            }
            // Create new trkseg element for this trace
            mGPX_trkseg = mGPXDocument.createElementNS(GPX.NS_GPX, "trkseg");
            GPX_trk.appendChild(mGPX_trkseg);

            // Force the next sample to be recorded
            for (Sampler s : mSamplers.values())
                s.mMustLogNextSample = true;
            mRecordingStartTime = System.currentTimeMillis();
            mAverageSamplingRate = 0; // rolling average sampling rate
            mTotalSampleCount = 0;
            Log.d(TAG, "startLogging to '" + suri + "'");
            return true;
        } catch (Exception ioe) {
            Log.e(TAG, "startLogging failed: could not open '" + mGPXFileUri + "' " + ioe);
            mGPXDocument = null;
            mGPX_trkseg = null;
            return false;
        }
    }

    /**
     * Stop logging.
     */
    public void stopLogging() {
        Log.d(TAG, "stopLogging()");
        mGPXDocument = null;
        mGPX_trkseg = null;
    }
}
