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
package com.cdot.ping.services;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.cdot.ping.MainActivity;
import com.cdot.ping.R;

import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;

/**
 * Log incoming samples to a file.
 *
 * This is a bound and started service that is automatically promoted to a foreground service
 * when all clients unbind.
 * <p>
 * For apps running in the background on "O" devices, location is computed only once every 10
 * minutes and delivered batched every 30 minutes. This restriction applies even to apps
 * targeting "N" or lower which are run on "O" devices. So a background service will only log
 * samples every 30 minutes, which is useless for Ping.
 * <p>
 * In this implementation, when the activity is removed from the foreground the service
 * automatically promotes itself to a foreground service with a Notification, and frequent
 * location updates continue. When the activity comes back to the foreground, the
 * foreground service stops, and the Notification is removed.
 * <p>
 * Substantially based on https://github.com/android/location-samples
 */
public abstract class LoggingService extends Service {
    public static final String TAG = LoggingService.class.getSimpleName();

    protected static final String PACKAGE_NAME = LocationService.class.getPackage().getName();

    public static final String ACTION_SAMPLE = PACKAGE_NAME + ".ACTION_SAMPLE";

    public static final String EXTRA_SAMPLE_DATA = PACKAGE_NAME + ".SAMPLE_DATA";
    public static final String EXTRA_SAMPLE_SOURCE = PACKAGE_NAME + ".SAMPLE_SOURCE";

    // Extra to tell us if we arrived in onStartCommand from the Notification
    protected static final String EXTRA_STARTED_FROM_NOTIFICATION =
            LoggingService.class.getPackage().getName() + ".started_from_notification";

    /**
     * Used to check whether the bound activity has really gone away and not unbound as part of an
     * orientation change. We create a foreground service notification only if the former takes
     * place.
     */
    protected boolean mJustAConfigurationChange = false;

    protected NotificationManager mNotificationManager;

    protected PrintWriter mSampleWriter = null;
    protected boolean mMustLogNextSample = true; // set true to force logging of next sample

    protected String getTag() { return TAG; }

    // Set to true in subclasses to keep this service running even when all clients are unbound
    // and logging is disabled.
    protected boolean mKeepRunning = true;

    /**
     * The identifier for the notification displayed for the foreground service.
     * "If a notification with the same id has already been posted by your application and has
     * not yet been canceled, it will be replaced by the updated information", so each subclass has
     * to provide a unique ID.
     */
    protected abstract int getNotificationId();

    private static final String CHANNEL_ID = "Ping_Channel";

    /**
     * Method provided by subclasses to create the IBinder
     * @return
     */
    protected abstract IBinder createBinder();

    private IBinder mBinder = createBinder();

    @Override // Service
    public void onCreate() {

        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            // Android O requires a Notification Channel.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CharSequence name = getString(R.string.app_name);
                // Create the channel for the notification
                NotificationChannel channel =
                        new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT);

                // Set the Notification Channel for the Notification Manager.
                mNotificationManager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Override in subclasses to provide actions for when the service is being stopped from the
     * foreground notification/
     */
    protected void onStoppedFromNotification() {}

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");
        boolean startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION, false);

        // We got here because the user decided to kill the service from the notification.
        if (startedFromNotification) {
            Log.d(TAG, getTag() + ": stopped from notification");
            onStoppedFromNotification();
            stopSelf();
        } else
            Log.d(TAG, getTag() + ": started");
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
        Log.d(TAG, getTag() + ": in onBind()");
        stopForeground(true);
        mJustAConfigurationChange = false;
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        // Called when a client (MainActivity in case of this sample) returns to the foreground
        // and binds once again with this service. The service should cease to be a foreground
        // service when that happens.
        Log.d(TAG, getTag() + ": in onRebind()");
        stopForeground(true);
        mJustAConfigurationChange = false;
        super.onRebind(intent);
    }

    /**
     * Override in subclasses to handle what should happen when all clients are unbound from the
     * service and logging is not enabled. This should be used to release resources the service
     * is using.
     */
    protected void onAllUnbound() {};

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, getTag() + ": in onUnbind()");

        if (mJustAConfigurationChange)
            Log.d(TAG, getTag() + ": Unbinding due to a configuration change");
        else if (mKeepRunning || mSampleWriter != null) {
            Log.d(TAG, getTag() + ": Starting foreground service " + getNotificationId());
            //startForeground(getNotificationId(), getNotification());
            startForeground(getNotificationId(), getNotification());
        } else {
            Log.d(TAG, getTag() + ": All unbound");
            onAllUnbound();
        }

        return true; // Ensures onRebind() is called when a client re-binds.
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, getTag() + ": onDestroy");
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

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                // Actions are typically displayed by the system as a button adjacent to the notification content.

                // This will restore the activity
                .addAction(R.drawable.ic_launcher, getString(R.string.launch_activity), activityPendingIntent)

                // This will stop the service
                .addAction(R.drawable.ic_cancel, getString(R.string.stop_service), servicePendingIntent)

                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setWhen(System.currentTimeMillis());

        customiseNotification(builder);

        return builder.build();
    }

    /**
     * Subclasses can override this to add customisation to the generic Notification
     * @param b the notification to customise
     */
    protected void customiseNotification(NotificationCompat.Builder b) {}

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
     * Start logging to the given URI.
     * @param suri URI to log to
     * @return true if logging to that URI was enabled
     */
    public boolean startLogging(String suri) {
        Uri mLogUri = Uri.parse(suri);
        // Check it exists, create it with appropriate header if not
        try {
            AssetFileDescriptor afd;
            FileWriter fw = null;
            try {
                // TODO: fix this
                getContentResolver().openAssetFileDescriptor(mLogUri, "r").close();
                afd = getContentResolver().openAssetFileDescriptor(mLogUri, "wa");
                fw = new FileWriter(afd.getFileDescriptor());
            } catch (FileNotFoundException fnfe) {
                afd = getContentResolver().openAssetFileDescriptor(mLogUri, "w");
                fw = new FileWriter(afd.getFileDescriptor());
            }
            mSampleWriter = new PrintWriter(fw);
            // Force the next sample to be recorded
            mMustLogNextSample = true;
            Log.d(TAG, getTag() + ": startLogging to '" + suri + "'");
            return true;
        } catch (IOException ioe) {
            Log.e(TAG, getTag() + ": startLogging failed: could not open '" + mLogUri + "' " + ioe);
            mSampleWriter = null;
            return false;
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

    void logSample(Bundle sample) {
        sample.putLong("time", new Date().getTime());
        if (mSampleWriter != null) {
            StringBuilder sb = new StringBuilder("<sample ");
            for (String key : sample.keySet()) {
                sb.append(key).append("=").append('"').append(sample.get(key)).append('"');
            }
            sb.append(" />");
            mSampleWriter.println(sb);
        }

        // Update notification content if running as a foreground service.
        if (isRunningInForeground())
            mNotificationManager.notify(getNotificationId(), getNotification());
        else {
            Intent intent = new Intent(ACTION_SAMPLE);
            intent.putExtra(EXTRA_SAMPLE_SOURCE, getTag());
            intent.putExtra(EXTRA_SAMPLE_DATA, sample);
            sendBroadcast(intent);
        }
    }
}
