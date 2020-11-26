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
import android.location.Location;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.cdot.ping.MainActivity;
import com.cdot.ping.R;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.Date;

/**
 * Location sampling service.
 * <p>
 * This is a a bound and started service that is automatically promoted to a foreground service
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
 */
public class LocationService extends Service {
    private static final String TAG = LocationService.class.getSimpleName();

    private static final String PACKAGE_NAME = "com.cdot.ping.services";

    /**
     * The name of the channel for notifications.
     */
    private static final String NOTIFICATION_CHANNEL_ID = TAG;

    // Action broadcast to notify a location change to the app
    public static final String ACTION_LOCATION_CHANGED = PACKAGE_NAME + ".location_changed";

    public static final String EXTRA_LOCATION = PACKAGE_NAME + ".location";

    // Extra to tell us if we arrived in onStartCommand from the Notification
    private static final String EXTRA_STARTED_FROM_NOTIFICATION = PACKAGE_NAME + ".started_from_notification";

    private final IBinder mBinder = new LocalBinder();

    /**
     * The desired interval for location updates. Inexact. Updates may be more or less frequent.
     */
    private static final long UPDATE_INTERVAL_IN_MILLISECONDS = 10000;// in production, use 500;

    /**
     * The fastest rate for active location updates. Updates will never be more frequent
     * than this value.
     */
    private static final long FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2;

    /**
     * The identifier for the notification displayed for the foreground service.
     * "If a notification with the same id has already been posted by your application and has
     * not yet been canceled, it will be replaced by the updated information."
     */
    private static final int NOTIFICATION_ID = 12345678;

    /**
     * Used to check whether the bound activity has really gone away and not unbound as part of an
     * orientation change. We create a foreground service notification only if the former takes
     * place.
     */
    private boolean mJustAConfigurationChange = false;

    private NotificationManager mNotificationManager;

    /**
     * Contains parameters used by {@link com.google.android.gms.location.FusedLocationProviderApi}.
     */
    private LocationRequest mLocationRequest;

    /**
     * Provides access to the Fused Location Provider API.
     */
    private FusedLocationProviderClient mFusedLocationClient;

    /**
     * Callback for changes in location.
     */
    private LocationCallback mLocationCallback;

    //NOP? private Handler mServiceHandler;

    // Service ID
    private int mStartId = -1;

    private Location mCurrentLocation;
    private Location mLastLoggedLocation;

    @Override
    public void onCreate() {
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                super.onLocationResult(locationResult);
                onNewLocation(locationResult.getLastLocation());
            }
        };

        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(UPDATE_INTERVAL_IN_MILLISECONDS);
        mLocationRequest.setFastestInterval(FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        // Initialise mLocation with our current location
        try {
            mFusedLocationClient.getLastLocation()
                    .addOnCompleteListener(new OnCompleteListener<Location>() {
                        @Override
                        public void onComplete(@NonNull Task<Location> task) {
                            if (task.isSuccessful() && task.getResult() != null) {
                                logSample(task.getResult());
                            } else {
                                Log.w(TAG, "Failed to get location");
                            }
                        }
                    });
        } catch (SecurityException unlikely) {
            Log.e(TAG, "Lost location permission. " + unlikely);
        }

        //NOP? HandlerThread handlerThread = new HandlerThread(TAG);
        //NOP? handlerThread.start();
        //NOP? mServiceHandler = new Handler(handlerThread.getLooper());
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // Android O requires a Notification Channel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.app_name);
            // Create the channel for the notification
            NotificationChannel mChannel =
                    new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT);

            // Set the Notification Channel for the Notification Manager.
            mNotificationManager.createNotificationChannel(mChannel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service started");
        boolean startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION,
                false);

        // We got here because the user decided to remove location updates from the notification.
        if (startedFromNotification) {
            try {
                mFusedLocationClient.removeLocationUpdates(mLocationCallback);
            } catch (SecurityException unlikely) {
                Log.e(TAG, "Lost location permission. Could not remove updates. " + unlikely);
            }
            stopSelfResult(startId);
        } else
            mStartId = startId;
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
        Log.i(TAG, "in onBind()");
        stopForeground(true);
        mJustAConfigurationChange = false;
        return mBinder;
    }

    @Override
    public void onRebind(Intent intent) {
        // Called when a client (MainActivity in case of this sample) returns to the foreground
        // and binds once again with this service. The service should cease to be a foreground
        // service when that happens.
        Log.i(TAG, "in onRebind()");
        stopForeground(true);
        mJustAConfigurationChange = false;
        super.onRebind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "Last client unbound from service");

        // Called when the last client (MainActivity in case of this sample) unbinds from this
        // service. If this method is called due to a configuration change in MainActivity, we
        // do nothing. Otherwise, we make this service a foreground service.
        if (!mJustAConfigurationChange) {
            Log.i(TAG, "Starting foreground service");

            startForeground(NOTIFICATION_ID, getNotification());
        }
        return true; // Ensures onRebind() is called when a client re-binds.
    }

    @Override
    public void onDestroy() {
        //NOP? mServiceHandler.removeCallbacksAndMessages(null);
    }

    /**
     * Returns the {@link NotificationCompat} used as part of the foreground service.
     */
    private Notification getNotification() {
        Intent intent = new Intent(this, LocationService.class);
        // Tell onStartCommand it was called from the Notification
        intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true);
        // The PendingIntent that leads to a call to onStartCommand() in this service.
        PendingIntent servicePendingIntent = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        // The PendingIntent to launch activity.
        PendingIntent activityPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        CharSequence text = mCurrentLocation == null ? "Unknown location" :
                "(" + mCurrentLocation.getLatitude() + ", " + mCurrentLocation.getLongitude() + ")";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                // Actions are typically displayed by the system as a button adjacent to the notification content.

                // This will restore the activity
                .addAction(R.drawable.ic_launcher, getString(R.string.launch_activity),
                        activityPendingIntent)

                // This will stop the service
                .addAction(R.drawable.ic_cancel, getString(R.string.stop_location_service),
                        servicePendingIntent)

                .setContentTitle(getString(R.string.location_updated, DateFormat.getDateTimeInstance().format(new Date())))
                .setContentText(text)
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

    // Handle a result from a LocationCallback
    private void onNewLocation(Location location) {
        Log.i(TAG, "New location: " + location);

        logSample(location);

        // Update notification content if running as a foreground service.
        if (serviceIsRunningInForeground(this)) {
            mNotificationManager.notify(NOTIFICATION_ID, getNotification());
        }
    }

    /**
     * Class used for the client Binder.  Since this service runs in the same process as its
     * clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        public LocationService getService() {
            return LocationService.this;
        }
    }

    /**
     * Returns true if this is a foreground service. Which it should be.
     *
     * @param context The {@link Context}.
     */
    public boolean serviceIsRunningInForeground(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(
                Integer.MAX_VALUE)) {
            if (getClass().getName().equals(service.service.getClassName())) {
                if (service.foreground) {
                    return true;
                }
            }
        }
        return false;
    }

    private double mMinDeltaPos = 0.5;

    private String mLogFile = null;
    private PrintWriter mSampleWriter = null;

    /**
     * Configure the minimum criteria for logging a new sample, and start the service if
     * it's not already running
     *
     * @param minDeltaPos min position move, in metres
     * @param logFile  uri of file to log samples to, null to disable logging
     */
    public void configure(double minDeltaPos, String logFile) {
        Log.d(TAG, "Received configure(" + minDeltaPos + ", " + logFile + ")");
        mMinDeltaPos = minDeltaPos;
        if (logFile == null || !logFile.equals(mLogFile))
            stopLogging();
        if (logFile != null && !logFile.equals(mLogFile))
            startLogging(logFile);

        if (mStartId == -1) {
            startService(new Intent(getApplicationContext(), LocationService.class));
            // Get an initial sample
            try {
                mFusedLocationClient.requestLocationUpdates(mLocationRequest,
                        mLocationCallback, Looper.myLooper());
            } catch (SecurityException unlikely) {
                Log.e(TAG, "Lost location permission. Could not request updates. " + unlikely);
            }
        }
    }

    private void startLogging(String suri) {
        mLogFile = suri;
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
            // Force the next sample to be recorded
            mLastLoggedLocation = null;
            Log.d(TAG, "Logging to " + uri);
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

    /*
     * Accuracy is the radius of 68% confidence. If you draw a circle centered at this
     * location's latitude and longitude, and with a radius equal to the locationAccuracy (metres), then
     * there is a 68% probability that the true location is inside the circle.
     */
    void logSample(Location loc) {
        boolean acceptable = (mCurrentLocation == null)
                // if new location has better accuracy, always use it
                || (loc.getAccuracy() <= mCurrentLocation.getAccuracy())
                // if we've moved further than the current location accuracy
                || (mCurrentLocation.distanceTo(loc) > mCurrentLocation.getAccuracy());

        if (!acceptable)
            return;

        mCurrentLocation = loc;

        // Have we staggered far enough to justify logging a new sample?
        if (mLastLoggedLocation == null || mLastLoggedLocation.distanceTo(mCurrentLocation) >= mMinDeltaPos) {

            mLastLoggedLocation = mCurrentLocation;

            if (mSampleWriter != null) {
                mSampleWriter.print((new Date()));
                mSampleWriter.print(',');
                mSampleWriter.print(mCurrentLocation.getLatitude());
                mSampleWriter.print(',');
                mSampleWriter.println(mCurrentLocation.getLongitude());
                //mFileWriter.write(","); mFileWriter.write(Double.toString(fishDepth));
                //mFileWriter.write(","); mFileWriter.write(Double.toString(fishStrength));
                Log.d(TAG, "Recorded sonar sample");
            }

            // Notify anyone listening for broadcasts about the new location.
            // TODO: there is no point in broadcasting if we are running as a foreground service
            Intent intent = new Intent(ACTION_LOCATION_CHANGED);
            intent.putExtra(EXTRA_LOCATION, mCurrentLocation);
            sendBroadcast(intent);
        }
    }
}
