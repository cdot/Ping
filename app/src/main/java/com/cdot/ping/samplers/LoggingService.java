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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.cdot.ping.MainActivity;
import com.cdot.ping.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

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
    public static final String EXTRA_SAMPLE_SOURCE = CLASS_NAME + ".sample_source";

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

    protected PrintWriter mSampleWriter = null;

    // DEBUG: Set to true in subclasses to keep this service running even when all clients are unbound
    // and logging is disabled.
    // TODO: set this false before pre-release testing
    protected boolean mKeepRunning = true;

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

    @Override // Service
    public void onCreate() {
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // Android O requires a Notification Channel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.app_name);
            // Create the channel for the notification
            NotificationChannel channel = // low importance makes it silent
                    new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW);

            // TODO: This doesn't seem to do anything with IMPORTANCE_DEFAULT, AFAICT code is correct
            Uri soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"+ getApplicationContext().getPackageName() + "/" + R.raw.ping);
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
     * @param s sampler to add
     */
    public void addSampler(Sampler s) {
        mSamplers.put(s.getTag(), s);
        s.onAttach(this);
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
        mJustAConfigurationChange = true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Called when a client (MainActivity in case of this sample) comes to the foreground
        // and binds with this service. The service should cease to be a foreground service
        // when that happens.
        Log.d(TAG, "onBind()");
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
        Log.d(TAG, "onRebind()");
        stopForeground(true);
        mJustAConfigurationChange = false;
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "in onUnbind()");

        if (mJustAConfigurationChange)
            Log.d(TAG, "Unbinding due to a configuration change");
        else if (mKeepRunning || mSampleWriter != null) {
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

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        for (Sampler s : mSamplers.values())
            s.onDestroy();
    }

    /**
     * Returns the {@link NotificationCompat} displayed in the notification drawers.
     */
    Notification getNotification() {
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

    /**
     * Samplers call this to log a sample. Samples are written in JSON format.
     * @param sample Bundle containing the sample. The content of the bundle is set by the Sampler.
     * @param source TAG of the Sampler that generated the sample
     */
    void logSample(Bundle sample, String source) {
        // Watermark the sample with data from samplers that want to contribute. In our case,
        // the LocationSampler watermarks Sonar samples with a location.
        for (Sampler s : mSamplers.values())
            s.watermark(sample);
        if (mSampleWriter != null) {
            JSONObject json = new JSONObject();
            try {
                for (String key : sample.keySet()) {
                    json.put(key, sample.get(key));
                }
                mSampleWriter.println(json);
            } catch (JSONException ex) {
                Log.e(TAG, ex.toString());
            }
        }

        // Update notification content if running as a foreground service.
        if (isRunningInForeground())
            mNotificationManager.notify(NOTIFICATION_1D, getNotification());
        else {
            //Log.d(TAG, "Broadcasting sample from " + source);
            Intent intent = new Intent(ACTION_SAMPLE);
            intent.putExtra(EXTRA_SAMPLE_SOURCE, source);
            intent.putExtra(EXTRA_SAMPLE_DATA, sample);
            sendBroadcast(intent);
        }
    }

    /**
     * Start logging to the given URI.
     *
     * @param suri URI to log to
     */
    public void startLogging(String suri) {
        Uri mLogUri = Uri.parse(suri);
        // Check it exists, create it if not
        try {
            AssetFileDescriptor afd;
            FileWriter fw = null;
            try {
                getContentResolver().openAssetFileDescriptor(mLogUri, "r").close();
                afd = getContentResolver().openAssetFileDescriptor(mLogUri, "wa");
                fw = new FileWriter(afd.getFileDescriptor());
            } catch (FileNotFoundException fnfe) {
                afd = getContentResolver().openAssetFileDescriptor(mLogUri, "w");
                fw = new FileWriter(afd.getFileDescriptor());
            }
            mSampleWriter = new PrintWriter(fw);
            // Force the next sample to be recorded
            for (Sampler s : mSamplers.values())
                s.mMustLogNextSample = true;
            Log.d(TAG, "startLogging to '" + suri + "'");
        } catch (IOException ioe) {
            Log.e(TAG, "startLogging failed: could not open '" + mLogUri + "' " + ioe);
            mSampleWriter = null;
        }
    }

    /**
     * Stop logging.
     */
    public void stopLogging() {
        if (mSampleWriter == null)
            return;
        mSampleWriter.close();
        mSampleWriter = null;
    }
}
