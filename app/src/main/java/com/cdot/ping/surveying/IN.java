package com.cdot.ping.surveying;

import com.cdot.location.UTM;

public class IN extends Vector3 {

    public IN(double x, double y) {
        super(x, y);
    }

    public IN(double x, double y, double z) {
        super(x, y, z);
    }

    public IN(UTM u) {
        this((u.easting - Units.inOrigin.easting)
                        * Units.UPM[Units.IN],
                (u.northing - Units.inOrigin.northing)
                        * Units.UPM[Units.IN]);
    }

    public UTM toUTM() {
        return new UTM(
                y / Units.UPM[Units.IN] + Units.inOrigin.northing,
                x / Units.UPM[Units.IN] + Units.inOrigin.easting,
                Units.inOrigin);

    }

}
