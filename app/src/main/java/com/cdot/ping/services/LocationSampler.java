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

import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

/**
 * Location sampling, provides location samples to LoggingService
 */
public class LocationSampler extends Sampler {
    public static final String TAG = LocationSampler.class.getSimpleName();

    /**
     * The desired interval for location updates. Inexact. Updates may be more or less frequent.
     */
    private static final long UPDATE_INTERVAL_IN_MILLISECONDS = 10000;// in production, use 500;

    /**
     * The fastest rate for active location updates. Updates will never be more frequent
     * than this value.
     */
    private static final long FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2;

    // Provides access to the Fused Location Provider API.
    private FusedLocationProviderClient mFusedLocationClient;

    // Callback for changes in location coming from location services
    private LocationCallback mLocationCallback;

    private Location mCurrentLocation;
    private Location mLastLoggedLocation;
    private double mMinDeltaPos = 0.5;

    @Override // Sampler
    void onAttach(LoggingService svc) {
        super.onAttach(svc);

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(mService);

        mLocationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                super.onLocationResult(locationResult);
                onLocationSample(locationResult.getLastLocation());
            }
        };

        // Parameters used by {@link com.google.android.gms.location.FusedLocationProviderApi}.
        LocationRequest locationRequest = new LocationRequest();
        locationRequest.setInterval(UPDATE_INTERVAL_IN_MILLISECONDS);
        locationRequest.setFastestInterval(FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        try {
            mFusedLocationClient.requestLocationUpdates(locationRequest, mLocationCallback, Looper.myLooper());

            // Initialise mLocation with our current location
            mFusedLocationClient.getLastLocation()
                    .addOnCompleteListener(new OnCompleteListener<Location>() {
                        @Override
                        public void onComplete(@NonNull Task<Location> task) {
                            if (task.isSuccessful() && task.getResult() != null) {
                                onLocationSample(task.getResult());
                            } else {
                                Log.w(TAG, "Failed to get location");
                            }
                        }
                    });
        } catch (SecurityException unlikely) {
            Log.e(TAG, "Lost location permission. " + unlikely);
        }
    }

    @Override // implements Sampler
    public String getTag() { return TAG; }

    @Override // implements Sampler
    public void onStoppedFromNotification() {
        try {
            mFusedLocationClient.removeLocationUpdates(mLocationCallback);
        } catch (SecurityException unlikely) {
            Log.e(TAG, "Lost location permission. Could not remove updates. " + unlikely);
        }
    }

    @Override // implements Sampler
    public String getNotificationText() {
        return mCurrentLocation == null ? "Unknown location" :
                "(" + mCurrentLocation.getLatitude() + ", " + mCurrentLocation.getLongitude() + ")";
    }

    /**
     * Configure the minimum criteria for logging a new sample, and start the service if
     * it's not already running
     *
     * @param minDeltaPos min position move, in metres
     */
    public void configureLocation(double minDeltaPos) {
        Log.d(TAG, "configureLocation(" + minDeltaPos + ")");
        mMinDeltaPos = minDeltaPos;
    }

    /*
     * Accuracy is the radius of 68% confidence. If you draw a circle centered at this
     * location's latitude and longitude, and with a radius equal to the locationAccuracy (metres), then
     * there is a 68% probability that the true location is inside the circle.
     */
    private void onLocationSample(Location loc) {
        boolean acceptable = (mCurrentLocation == null)
                // if new location has better accuracy, always use it
                || (loc.getAccuracy() <= mCurrentLocation.getAccuracy())
                // if we've moved further than the current location accuracy
                || (mCurrentLocation.distanceTo(loc) > mCurrentLocation.getAccuracy());

        if (!acceptable)
            return;

        mCurrentLocation = loc;

        // Have we staggered far enough to justify logging a new sample?
        if (mMustLogNextSample || mLastLoggedLocation == null || mLastLoggedLocation.distanceTo(mCurrentLocation) >= mMinDeltaPos) {
            mLastLoggedLocation = mCurrentLocation;
            mMustLogNextSample = false;
            Bundle sample = new Bundle();
            sample.putParcelable("location", loc);
            mService.logSample(sample, TAG);
        }
    }
}