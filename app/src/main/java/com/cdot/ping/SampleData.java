package com.cdot.ping;

/**
 * A single sample of sonar and location data.
 */
class SampleData {
    private static final String TAG = "SampleData";

    private static final float ft2m = 0.3048f;

    static final byte ID0 = 83;
    static final byte ID1 = 70;

    boolean contactsDry; // simple flag

    float depth; // metres
    // probably 0..100? Value in a cup is 111, suggesting that's the max.
    // Lower is fuzzier, related to softness/weediness. Fish Helper seems to suggest 30-60 is weed,
    // small bits < 40, large bits > 50
    // never seen this stronger than 111 from the device
    static final float MAX_STRENGTH = 128f;
    float strength; // strength of bottom signal
    float fishDepth; // metres

    static final int NO_FISH = 0;
    static final int SMALL_FISH = 1;
    static final int MEDIUM_FISH = 2;
    static final int BIG_FISH = 3;
    static final int SHOAL = 4;
    int fishType; // Strength of fish return signal

    int battery; // 0..6
    float temperature; // celcius

    double latitude, longitude;

    SampleData() {
    }

    /**
     * Create a copy of another SampleData
     *
     * @param copy SampleData to copy
     */
    SampleData(SampleData copy) {
        contactsDry = copy.contactsDry;
        depth = copy.depth;
        strength = copy.strength;
        fishDepth = copy.fishDepth;
        fishType = copy.fishType;
        battery = copy.battery;
        temperature = copy.temperature;
        latitude = copy.latitude;
        longitude = copy.longitude;
    }

    /**
     * Create SampleData from a byte packet read from a bluetooth device
     *
     * @param data a block of data as read from a Fish Finder sonar device
     */
    SampleData(byte[] data) {
        if (data.length < 14 || data[0] != ID0 || data[1] != ID1)
            throw new IllegalArgumentException("Bad data block");
        contactsDry = ((data[4] & 0x8) != 0); // wonder what the other bits are? Always 0 AFAICT
        // Convert feet to metres
        depth = ft2m * (data[6] + data[7] / 100.0f);
        strength = 100f * data[8] / MAX_STRENGTH;
        // Convert feet to metres
        fishDepth = ft2m * (data[9] + data[10] / 100.0f);
        fishType = data[11] & 0xF; // & 0x7?
        battery = (byte) ((data[11] >> 4) & 0xF);
        // Convert fahrenheit to celcius
        temperature = (data[12] + data[13] / 100.0f - 32.0f) * 5.0f / 9.0f;
        // Wonder what data[5] is? Seems to be always 9
        //Log.d(TAG, "[5]=" + Integer.toHexString(data[5]));
        // There are several more bytes in the packet; wonder what they do?
        //for (int i = 13; i < data.length; i++)
        //    Log.d(TAG, "[" + i + "]=" + Integer.toHexString(data[i]));
    }

    public String toString() {
        return "D " + depth + " s " + strength + " T " + temperature;
    }
}
