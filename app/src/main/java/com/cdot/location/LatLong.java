package com.cdot.location;

public class LatLong {
    private static String dRound(double x, String pos, String neg) {
        double v = Math.floor(100000 * x) / 100000;
        return (v < 0) ? -v + "°" + neg : v + "°" + pos;
    }

    public double latitude, longitude;

    public String toString() {
        return dRound(latitude, "N", "S") + " " + dRound(longitude, "E", "W");

    }
}
