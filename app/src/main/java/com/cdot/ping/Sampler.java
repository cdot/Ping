package com.cdot.ping;

import android.content.Context;
import android.location.Location;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sample collector. tries to make sense of samples and only record when it's really necessary.
 */
class Sampler extends SampleData {
    private static final String TAG = "Sampler";

    private List<SampleData> mAveraging;
    private float locationAccuracy;
    private SampleData lastRecordedSample = null;

    Sampler() {
        mAveraging = new ArrayList<>();
    }

    void addLocation(Location loc, Context cxt) {
        // Accuracy is the radius of 68% confidence. If you draw a circle centered at this
        // location's latitude and longitude, and with a radius equal to the locationAccuracy (metres), then
        // there is a 68% probability that the true location is inside the circle.

        // TODO: could do this better? Average out location samples, weighted by locationAccuracy?
        // Discard outliers and average again.

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

        if (lastRecordedSample == null)
            sample(cxt);
        else {
            Location.distanceBetween(lastRecordedSample.latitude, lastRecordedSample.longitude, loc.getLatitude(), loc.getLongitude(), dist);
            if (dist[0] >= Ping.P.getFloat("minimumPositionChange"))
                sample(cxt);
        }

        // Could also: Decide whether we trust this provider more.
        // String provider = loc.getProvider();
    }

    void addSonar(SampleData data, Context cxt) {
        battery = data.battery;
        // Other fields are averaged over the history
        mAveraging.add(data);
        isLand = data.isLand;

        depth = 0;
        strength = 0;
        fishDepth = 0;
        fishType = SampleData.NO_FISH;
        int fishy = 0;
        for (SampleData hr : mAveraging) {
            depth += hr.depth;
            strength += hr.strength;
            if (hr.fishDepth > 0) {
                fishy++;
                fishDepth += hr.fishDepth;
            }
            if (hr.fishType > fishType)
                fishType = hr.fishType;
        }
        depth /= mAveraging.size();
        strength /= mAveraging.size();
        if (fishy > 0)
            fishDepth /= fishy;
        if (lastRecordedSample == null)
            sample(cxt);
        else {
            float deltaDepth = Math.abs(depth - lastRecordedSample.depth);
            if (deltaDepth >= Ping.P.getFloat("minimumDepthChange"))
                sample(cxt);
        }
    }

    /**
     * Record a sample
     */
    void sample(Context context) {
        if (Ping.P.recordingOn && Ping.P.getSampleFile() != null) {
            // Going to have to ask for permission
            try {
                ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(Ping.P.getSampleFile(), "wa");
                FileWriter fw = new FileWriter(pfd.getFileDescriptor());
                fw.write(Double.toString(latitude));
                fw.write(",");
                fw.write(Double.toString(longitude));
                fw.write(",");
                fw.write(Float.toString(depth));
                fw.write(",");
                fw.write(Float.toString(strength));
                fw.write("\n");
                fw.close();
                lastRecordedSample = new SampleData(this);
                Log.d(TAG, "Recorded sample");
            } catch (IOException ioe) {
                throw new Error("IO exception writing " + Ping.P.getSampleFile() + ": " + ioe);
            }
        }
        // Prepare for the next sample
        mAveraging.clear();
    }
}
