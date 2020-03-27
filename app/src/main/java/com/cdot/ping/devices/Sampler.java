package com.cdot.ping.devices;

import android.location.Location;

import java.lang.ref.WeakReference;

/**
 * Sample collector. Collects sonar and location samples, and tries to only record/broadcast
 * them when a significant change has been made in either depth or location.
 */
class Sampler extends SampleData {
    private static final String TAG = "Sampler";

    private float locationAccuracy;
    private SampleData lastSample = null;
    private float mMinDeltaDepth = 0.5f, mMinDeltaPos = 0.5f;
    private WeakReference<DeviceService> mDeviceServiceRef;
    private static int sSampleUID = 0;

    Sampler(DeviceService ds) {
        // Use the uid to uniquely identify samples we have broadcast
        uid = 0;
        mDeviceServiceRef = new WeakReference<>(ds);
    }

    /**
     * Configure the minimum criteria for registering a new sample
     * @param minDeltaDepth min depth change, in metres
     * @param minDeltaPos min position move, in metres
     */
    void configure(float minDeltaDepth, float minDeltaPos) {
        mMinDeltaDepth = minDeltaDepth;
        mMinDeltaPos = minDeltaPos;
    }

    /*
     * Accuracy is the radius of 68% confidence. If you draw a circle centered at this
     * location's latitude and longitude, and with a radius equal to the locationAccuracy (metres), then
     * there is a 68% probability that the true location is inside the circle.
     */
    void updateLocation(Location loc) {
        // TODO: could do this better? Average out location samples, weighted by locationAccuracy?
        // Minimum location accuracy?

        // If the new location has greater locationAccuracy, adopt it
        float[] dist = new float[1];
        if (loc.getAccuracy() <= locationAccuracy) {
            locationAccuracy = loc.getAccuracy();
            latitude = loc.getLatitude();
            longitude = loc.getLongitude();
        } else {
            // Accuracy is less, but still accept if the old position is outside the locationAccuracy
            // circle of the new position
            Location.distanceBetween(latitude, longitude, loc.getLatitude(), loc.getLongitude(), dist);
            if (dist[0] > locationAccuracy) {
                locationAccuracy = loc.getAccuracy();
                latitude = loc.getLatitude();
                longitude = loc.getLongitude();
            }
        }

        if (lastSample == null)
            sample();
        else {
            Location.distanceBetween(lastSample.latitude, lastSample.longitude, loc.getLatitude(), loc.getLongitude(), dist);
            if (dist[0] >= mMinDeltaPos)
                sample();
        }

        // Could also: Decide whether we trust this provider more.
        // String provider = loc.getProvider();
    }

    /**
     * Update the sampler with information from a sonar device.
     * @param dry true if the device is currently out of the water. No sample will be recorded.
     * @param d depth in metres
     * @param ds depth signal strength, as a percentage of max strength
     * @param fd intermediate (fish) depth in metres
     * @param fs strength of intermediate signal
     * @param bat batter strength, 0..6
     * @param t temperature, in deg C
     */
    void updateSampleData(boolean dry, float d, float ds, float fd, float fs, int bat, float t) {
        battery = bat;
        temperature = t;
        depth = d; strength = ds;
        fishDepth = fd; fishStrength = fs;

        if (lastSample == null || battery != lastSample.battery)
            sample();
        else if (!dry) {
            float deltaDepth = Math.abs(depth - lastSample.depth);
            if (deltaDepth >= mMinDeltaDepth)
                sample();
        }
    }

    /**
     * Record a sample
     */
    private void sample() {
        lastSample = new SampleData(this);
        lastSample.uid = sSampleUID++;
        mDeviceServiceRef.get().sample(lastSample);
    }
}
