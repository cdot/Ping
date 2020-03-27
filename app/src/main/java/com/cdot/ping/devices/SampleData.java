package com.cdot.ping.devices;

import android.os.Bundle;

/**
 * A single sample of sonar and location data.
 */
public class SampleData {
    private static final String TAG = "SampleData";

    public String device; // device identifier e.g. bluetooth address

    public float depth; // metres

    public float strength; // strength of bottom signal, 0-100%

    public float fishDepth; // metres
    // Fish strength is in a nibble, so potentially in the range 0-15. 0 is easy, it means no mid-water
    // return. Above that, let's think. The beam is 90 degrees, so the size of the object is surely
    // proportional to the depth? Unless that is already computed in the return. The maximum size of
    // a fish is going to be a metre. At 36 meters that corresponds to 1.6 degrees of arc. Or
    // does it vary according to the range? Do we care?
    public float fishStrength; // Strength of fish return signal

    public int battery; // 0..6
    public float temperature; // celcius

    // Reading device location when sample taken (or location of sampling device, if that is
    // supported
    public double latitude, longitude;

    // Unique ID for this sample
    public int uid; // for debug

    /**
     * Default constructor for subclasses
     */
    protected SampleData() {
    }

    /**
     * Create a copy of another SampleData
     *
     * @param copy SampleData to copy
     */
    public SampleData(SampleData copy) {
        depth = copy.depth;
        strength = copy.strength;
        fishDepth = copy.fishDepth;
        fishStrength = copy.fishStrength;
        battery = copy.battery;
        temperature = copy.temperature;
        latitude = copy.latitude;
        longitude = copy.longitude;
        uid = copy.uid;
    }

    /**
     * Construct from a Bundle
     *
     * @param b bundle containing parcelled fields
     */
    public SampleData(Bundle b) {
        battery = b.getInt("battery");
        depth = b.getFloat("depth");
        fishDepth = b.getFloat("fishDepth");
        fishStrength = b.getFloat("fishStrength");
        latitude = b.getDouble("latitude");
        longitude = b.getDouble("longitude");
        strength = b.getFloat("strength");
        temperature = b.getFloat("temperature");
        uid = b.getInt("uid");
    }

    /**
     * Convert to a Bundle for transmission
     *
     * @return a bundle reflecting the content
     */
    public Bundle getBundle() {
        Bundle b = new Bundle();
        b.putInt("battery", battery);
        b.putFloat("depth", depth);
        b.putFloat("fishDepth", fishDepth);
        b.putFloat("fishStrength", fishStrength);
        b.putDouble("latitude", latitude);
        b.putDouble("longitude", longitude);
        b.putFloat("strength", strength);
        b.putFloat("temperature", temperature);
        b.putInt("uid", uid);
        return b;
    }

    public static final String csvFields = "Latitude,Longitude,Depth,Strength";

    /**
     * Generate sample as CSV line. Only the fields we are really interested in are recorded.
     * @return
     */
    public String toString() {
        return Double.toString(latitude) + ","
                + Double.toString(longitude) + ","
                + Float.toString(depth) + ","
                + Float.toString(strength) + "\n";
    }
}
