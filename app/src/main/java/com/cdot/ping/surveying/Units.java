/* @copyright 2020 Crawford Currie - All rights reserved */
package com.cdot.ping.surveying;

import com.cdot.location.UTM;

public class Units {

    /**
     * Conversions between different unit systems.
     *
     * 4 unit systems are supported
     * 1. Lat/Long (x=lat, y=long) decimal degrees
     * 2. UTM (x=easting, y=northing, z=zone) metres
     * 3. Internal units (x, y, z), millimetres relative to an
     *    origin expressed as a UTM coordinate
     * 4. External units, y axis is flipped
     *
     * External units requires the setting up of a mapping to/from these units.
     * For transformation TO Units.IN units, the following must be set:
     *    Units.BB[Units.IN]
     */

    /**
     * @public Systems and their coordinate interpretations
     */
    public static final int IN = 0;      // (x, y, z) in inner coordinates
    public static final int UTM_POS = 1;    // { easting, northing, zone }
    public static final int LATLON = 2; // { lon, lat }
    public static final int EX = 3;     // { x, y } in external coordinates, z unused

    /**
     * @public Units per metre in different systems
     */
    public static final double[] UPM = new double[]{
            1000,       // IN internal units per metre
            1,          // UTM
            0,          // LATLON, not useful
            10          // EX, pixels per metre
    };

    static boolean flipEXy = false;

    /**
     * Origin of the inner coordinate system in the UTM system.
     * When we start up, no zone is defined, the first conversion
     * to/from a Lat/Long will initialise it.
     */
    public static UTM inOrigin = null;

    /**
     * Bounding boxes in different systems
     */
    static BoundingBox[] BB = new BoundingBox[]{
            new BoundingBox(), // used
            null, // not used
            null, // not used
            new BoundingBox()  // used
    };

    /**
     * Set up parameters for EX.
     *
     * @param bb    bounds of the EX box to be transformed to IN
     * @param exUPM units-per-metre in the EX space
     * @param flipY true to invert the EX Y axis
     */
    public static void mapFromEX(BoundingBox bb, double exUPM, boolean flipY) {
        Units.UPM[Units.EX] = exUPM;
        Units.BB[Units.EX] = bb;
        Units.BB[Units.IN] = new BoundingBox(0, 0,
                Units.BBwidth(Units.EX)
                        * Units.UPM[Units.IN] / Units.UPM[Units.EX],
                Units.BBheight(Units.EX) * Units.UPM[Units.IN] / Units.UPM[Units.EX]
        );
        flipEXy = flipY;
    }

    /**
     * Set up parameters for EX.
     *
     * @param bb    bounds of the IN box to be transformed to EX
     * @param exUPM units-per-metre in the EX space
     * @param flipY true to invert the Y axis
     */
    static void mapToEX(BoundingBox bb, double exUPM, boolean flipY) {
        Units.UPM[Units.EX] = exUPM;
        Units.BB[Units.IN] = bb;
        Units.BB[Units.EX] = new BoundingBox(0, 0,
                Units.BBwidth(Units.IN)
                        * Units.UPM[Units.EX] / Units.UPM[Units.IN],
                Units.BBheight(Units.IN)
                        * Units.UPM[Units.EX] / Units.UPM[Units.IN]);
        Units.flipEXy = flipY;
    }

    /**
     * Get the width of the BB in the given system
     */
    static double BBwidth(int system) {
        return BB[system].max.x - BB[system].min.x;
    }

    /**
     * Get the height of the BB in the given system
     */
    static double BBheight(int system) {
        return BB[system].max.y - Units.BB[system].min.y;
    }
}
