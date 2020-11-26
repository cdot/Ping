package com.cdot.ping.surveying;

import com.cdot.location.UTM;

public class EX extends Vector3 {
    public EX(IN data) {
        // outsys === Units.EX
        x = (data.x - Units.BB[Units.IN].min.x)
                * Units.UPM[Units.EX] / Units.UPM[Units.IN];
        y = (data.y - Units.BB[Units.IN].min.y)
                * Units.UPM[Units.EX] / Units.UPM[Units.IN];
        z = data.z * Units.UPM[Units.EX] / Units.UPM[Units.IN];

        if (Units.flipEXy)
            y = Units.BBheight(Units.EX) - y;
    }

    public IN toIN() {
        IN res = new IN(
                (x - Units.BB[Units.EX].min.x)
                        * Units.UPM[Units.IN] / Units.UPM[Units.EX],
                (y - Units.BB[Units.EX].min.y)
                        * Units.UPM[Units.IN] / Units.UPM[Units.EX],
                z * Units.UPM[Units.IN] / Units.UPM[Units.EX]
        );
        if (Units.flipEXy)
            res.y = -res.y;
        return res;
    }

    public UTM toUTM() {
        return new UTM(
                y / Units.UPM[Units.IN] + Units.inOrigin.northing,
                x / Units.UPM[Units.IN] + Units.inOrigin.easting,
                Units.inOrigin);
    }
}
