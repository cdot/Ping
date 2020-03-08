package com.cdot.ping;

class SonarData {
    private static final String TAG = "SonarData";

    private static final float ft2m = 0.3048f;

    static final byte ID0 = 83;
    static final byte ID1 = 70;

    boolean isLand; // simple flag

    float depth; // metres
    // probably 0..100? Value in a cup is 111, suggesting that's the max.
    // Lower is fuzzier, related to softness/weediness. Fish Helper seems to suggest 30-60 is weed,
    // small bits < 40, large bits > 50
    int strength; // strength of bottom signal
    float fishDepth; // metres
    int fishType; // Fish reflection strength, 0..15 (maybe)
    int battery; // 4 bits but max seems to be 6?
    float temperature; // fahrenheit

    SonarData() {
    }

    SonarData(byte[] data) {
        if (data.length < 14 || data[0] != ID0 || data[1] != ID1)
            throw new IllegalArgumentException("Bad data block " + data);
        isLand = ((data[4] & 0x8) != 0); // wonder what the other bits are?
        depth = ft2m * (data[6] + data[7] / 100.0f);
        strength = data[8];
        fishDepth = ft2m * (data[9] + data[10] / 100.0f);
        fishType = data[11] & 0xF;
        battery = (byte) ((data[11] >> 4) & 0xF);
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
