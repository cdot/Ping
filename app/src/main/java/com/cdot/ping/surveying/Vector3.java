package com.cdot.ping.surveying;

public class Vector3 {
    public double x, y, z;

    Vector3() {
    }

    Vector3(double x, double y) {
        this.x = x;
        this.y = y;
        this.z = 0;
    }

    Vector3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    private static String cRound(double x) {
        return String.format("%d.5", x);
    }

    public String toString() {
        return "(" + cRound(x) + "," + cRound(y) + ")";
    }

}
