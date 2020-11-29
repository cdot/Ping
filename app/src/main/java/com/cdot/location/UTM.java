/*
 * Copyright © 2020 C-Dot Consultants
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
/* Portions @copyright 1997-1998 by Charles L. Taylor */
package com.cdot.location;

import android.location.Location;
import android.location.LocationManager;

import com.cdot.ping.surveying.IN;
import com.cdot.ping.surveying.Units;

public class UTM {

    /**
     * The point of origin of each UTM zone is the intersection of the
     * equator and the zone's central meridian. To avoid dealing with
     * negative numbers, the central meridian of each zone is defined
     * to coincide with 500000 meters East. UTM eastings range from
     * about 167000 meters to 833000 meters at the equator.
     * <p>
     * In the northern hemisphere positions are measured northward
     * from zero at the equator. The maximum "northing" value is about
     * 9300000 meters at latitude 84 degrees North, the north end of
     * the UTM zones. In the southern hemisphere northings decrease
     * southward from the equator to about 1100000 meters at 80
     * degrees South, the south end of the UTM zones. The northing at
     * the equator is set at 10000000 meters so no point has a
     * negative northing value.
     */
    // Extremes
    private static final double MIN_EASTING = 100000;
    private static final double MAX_EASTING = 1000000;

    private static final double MIN_NORTHING = 0;
    private static final double MAX_NORTHING_S = 10000000; // Equator
    private static final double MAX_NORTHING_N = 9300000;  // 84N

    /* Ellipsoid model constants (actual values here are for WGS84) */
    private static final double SM_A = 6378137;
    private static final double SM_B = 6356752.314;

    private static final double UTM_SCALE_FACTOR = 0.9996;

    /**
     * Converts degrees to radians.
     */
    public static double deg2rad(double deg) {
        return deg / 180 * Math.PI;
    }

    /**
     * Converts radians to degrees.
     */
    public static double rad2deg(double rad) {
        return rad / Math.PI * 180;
    }

    /**
     * Work out what UTM zone a given lat/lon is in
     */
    public static int latLonToZone(double lat, double lon) {
        if (72 <= lat && lat <= 84 && lon >= 0) {
            if (lon < 9) return 31;
            if (lon < 21) return 33;
            if (lon < 33) return 35;
            if (lon < 42) return 37;
        }
        int z = (int) Math.floor((lon + 180) / 6) + 1;
        if (z > 60) z -= 60;
        return z;
    }

    /**
     * Computes the ellipsoidal distance from the equator to a point at a
     * given latitude.
     * <p>
     * Reference: Hoffmann-Wellenhof, B., Lichtenegger, H., and Collins, J.,
     * GPS: Theory and Practice, 3rd ed.  New York: Springer-Verlag Wien, 1994.
     *
     * @param phi Latitude of the point, in radians.
     *            <p>
     *            Globals:
     *            sm_a - Ellipsoid model major axis.
     *            sm_b - Ellipsoid model minor axis.
     * @return The ellipsoidal distance of the point from the equator,
     * in meters.
     */
    private static double arcLengthOfMeridian(double phi) {
        // Precalculate n
        double n = (SM_A - SM_B) / (SM_A + SM_B);

        // Precalculate alpha
        double alpha = ((SM_A + SM_B) / 2)
                * (1 + (Math.pow(n, 2) / 4) + (Math.pow(n, 4) / 64));

        // Precalculate beta
        double beta = (-3 * n / 2) + (9 * Math.pow(n, 3) / 16)
                + (-3 * Math.pow(n, 5) / 32);

        // Precalculate gamma
        double gamma = (15 * Math.pow(n, 2) / 16)
                + (-15 * Math.pow(n, 4) / 32);

        // Precalculate delta
        double delta = (-35 * Math.pow(n, 3) / 48)
                + (105 * Math.pow(n, 5) / 256);

        // Precalculate epsilon
        double epsilon = (315 * Math.pow(n, 4) / 512);

        // Now calculate the sum of the series and return
        return alpha
                * (phi + (beta * Math.sin(2 * phi))
                + (gamma * Math.sin(4 * phi))
                + (delta * Math.sin(6 * phi))
                + (epsilon * Math.sin(8 * phi)));
    }

    /**
     * Determines the central meridian for the given UTM zone.
     *
     * @param zone An integer value designating the UTM zone, range [1,60].
     * @return The central meridian for the given UTM zone, in
     * radians, or zero if the UTM zone parameter is outside the
     * range [1,60].  Range of the central meridian is the radian
     * equivalent of [-177,+177].
     */
    private static double centralUTMMeridian(int zone) {
        return deg2rad(-183 + (zone * 6));
    }

    /**
     * Computes the footpoint latitude for use in converting transverse
     * Mercator coordinates to ellipsoidal coordinates.
     * <p>
     * Reference: Hoffmann-Wellenhof, B., Lichtenegger, H., and Collins, J.,
     * GPS: Theory and Practice, 3rd ed.  New York: Springer-Verlag Wien, 1994.
     *
     * @param y The UTM northing coordinate, in meters.
     * @return The footpoint latitude, in radians.
     */
    private static double footpointLatitude(double y) {
        // Precalculate n (Eq. 10.18)
        double n = (SM_A - SM_B) / (SM_A + SM_B);

        // Precalculate alpha_ (Eq. 10.22)
        // (Same as alpha in Eq. 10.17)
        double alpha_ = ((SM_A + SM_B) / 2)
                * (1 + (Math.pow(n, 2) / 4) + (Math.pow(n, 4) / 64));

        // Precalculate y_ (Eq. 10.23)
        double y_ = y / alpha_;

        // Precalculate beta_ (Eq. 10.22)
        double beta_ = (3 * n / 2) + (-27 * Math.pow(n, 3) / 32)
                + (269 * Math.pow(n, 5) / 512);

        // Precalculate gamma_ (Eq. 10.22)
        double gamma_ = (21 * Math.pow(n, 2) / 16)
                + (-55 * Math.pow(n, 4) / 32);

        // Precalculate delta_ (Eq. 10.22)
        double delta_ = (151 * Math.pow(n, 3) / 96)
                + (-417 * Math.pow(n, 5) / 128);

        // Precalculate epsilon_ (Eq. 10.22)
        double epsilon_ = (1097 * Math.pow(n, 4) / 512);

        // Now calculate the sum of the series (Eq. 10.21)
        return y_ + (beta_ * Math.sin(2 * y_))
                + (gamma_ * Math.sin(4 * y_))
                + (delta_ * Math.sin(6 * y_))
                + (epsilon_ * Math.sin(8 * y_));
    }

    /**
     * Converts a latitude/longitude pair to x and y coordinates in the
     * Transverse Mercator projection.  Note that Transverse Mercator is not
     * the same as UTM; a scale factor is required to convert between them.
     * <p>
     * Reference: Hoffmann-Wellenhof, B., Lichtenegger, H., and Collins, J.,
     * GPS: Theory and Practice, 3rd ed.  New York: Springer-Verlag Wien, 1994.
     *
     * @param phi     Latitude of the point, in radians.
     * @param lambda  Longitude of the point, in radians.
     * @param lambda0 Longitude of the central meridian to be used, in radians.
     * @return the coordinates of the computed point as {east, north}
     */
    private static double[] latLonToTransverseMercator(double phi, double lambda, double lambda0) {
        // Precalculate ep2
        double ep2 = (Math.pow(SM_A, 2) - Math.pow(SM_B, 2))
                / Math.pow(SM_B, 2);

        // Precalculate nu2
        double nu2 = ep2 * Math.pow(Math.cos(phi), 2);

        // Precalculate N
        double N = Math.pow(SM_A, 2) / (SM_B * Math.sqrt(1 + nu2));

        // Precalculate t
        double t = Math.tan(phi);
        double t2 = t * t;

        // Precalculate l
        double l = lambda - lambda0;

        // Precalculate coefficients for l**n in the equations below
        // so a normal human being can read the expressions for easting
        // and northing
        // -- l**1 and l**2 have coefficients of 1
        double l3coef = 1 - t2 + nu2;

        double l4coef = 5 - t2 + 9 * nu2 + 4 * (nu2 * nu2);

        double l5coef = 5 - 18 * t2 + (t2 * t2) + 14 * nu2
                - 58 * t2 * nu2;

        double l6coef = 61 - 58 * t2 + (t2 * t2) + 270 * nu2
                - 330 * t2 * nu2;

        double l7coef = 61 - 479 * t2 + 179 * (t2 * t2) - (t2 * t2 * t2);

        double l8coef = 1385 - 3111 * t2 + 543 * (t2 * t2) - (t2 * t2 * t2);

        return new double[]{
                // Calculate northing
                UTM.arcLengthOfMeridian(phi)
                        + (t / 2 * N * Math.pow(Math.cos(phi), 2) * Math.pow(l, 2))
                        + (t / 24 * N * Math.pow(Math.cos(phi), 4) * l4coef * Math.pow(l, 4))
                        + (t / 720 * N * Math.pow(Math.cos(phi), 6) * l6coef * Math.pow(l, 6))
                        + (t / 40320 * N * Math.pow(Math.cos(phi), 8) * l8coef * Math.pow(l, 8)),
                // Calculate easting
                N * Math.cos(phi) * l
                        + (N / 6 * Math.pow(Math.cos(phi), 3) * l3coef * Math.pow(l, 3))
                        + (N / 120 * Math.pow(Math.cos(phi), 5) * l5coef * Math.pow(l, 5))
                        + (N / 5040 * Math.pow(Math.cos(phi), 7) * l7coef * Math.pow(l, 7))
        };
    }

    /**
     * Converts x and y coordinates in the Transverse Mercator projection to
     * a latitude/longitude pair.  Note that Transverse Mercator is not
     * the same as UTM; a scale factor is required to convert between them.
     * <p>
     * Reference: Hoffmann-Wellenhof, B., Lichtenegger, H., and Collins, J.,
     * GPS: Theory and Practice, 3rd ed.  New York: Springer-Verlag Wien, 1994.
     *
     * @param x       The easting of the point, in meters.
     * @param y       The northing of the point, in meters.
     * @param lambda0 Longitude of the central meridian to be used, in radians.
     * @return postition as {lat, lon} in radians.
     */
    private static double[] transverseMercatorToLatLong(double x, double y, double lambda0) {
        // The local variables Nf, nuf2, tf, and tf2 serve the same
        // purpose as N, nu2, t, and t2 in latLonToTransverseMercator, but they are
        // computed with respect to the footpoint latitude phif.
        // x1frac, x2frac, x2poly, x3poly, etc. are to enhance readability and
        // to optimize computations.

        // Get the value of phif, the footpoint latitude.
        double phif = footpointLatitude(y);

        // Precalculate ep2
        double ep2 = (Math.pow(SM_A, 2) - Math.pow(SM_B, 2))
                / Math.pow(SM_B, 2);

        // Precalculate cos(phif)
        double cf = Math.cos(phif);

        // Precalculate nuf2
        double nuf2 = ep2 * Math.pow(cf, 2);

        // Precalculate Nf and initialize Nfpow
        double Nf = Math.pow(SM_A, 2) / (SM_B * Math.sqrt(1 + nuf2));
        double Nfpow = Nf;

        // Precalculate tf
        double tf = Math.tan(phif);
        double tf2 = tf * tf;
        double tf4 = tf2 * tf2;

        // Precalculate fractional coefficients for x**n in the equations
        // below to simplify the expressions for latitude and longitude.
        double x1frac = 1 / (Nfpow * cf);

        Nfpow *= Nf;   // now equals Nf**2)
        double x2frac = tf / (2 * Nfpow);

        Nfpow *= Nf;   // now equals Nf**3)
        double x3frac = 1 / (6 * Nfpow * cf);

        Nfpow *= Nf;   // now equals Nf**4)
        double x4frac = tf / (24 * Nfpow);

        Nfpow *= Nf;   // now equals Nf**5)
        double x5frac = 1 / (120 * Nfpow * cf);

        Nfpow *= Nf;   // now equals Nf**6)
        double x6frac = tf / (720 * Nfpow);

        Nfpow *= Nf;   // now equals Nf**7)
        double x7frac = 1 / (5040 * Nfpow * cf);

        Nfpow *= Nf;   // now equals Nf**8)
        double x8frac = tf / (40320 * Nfpow);

        // Precalculate polynomial coefficients for x**n.
        // -- x**1 does not have a polynomial coefficient.
        double x2poly = -1 - nuf2;

        double x3poly = -1 - 2 * tf2 - nuf2;

        double x4poly = 5 + 3 * tf2 + 6 * nuf2 - 6 * tf2 * nuf2
                - 3 * (nuf2 * nuf2) - 9 * tf2 * (nuf2 * nuf2);

        double x5poly = 5 + 28 * tf2 + 24 * tf4 + 6 * nuf2 + 8 * tf2 * nuf2;

        double x6poly = -61 - 90 * tf2 - 45 * tf4 - 107 * nuf2
                + 162 * tf2 * nuf2;

        double x7poly = -61 - 662 * tf2 - 1320 * tf4 - 720 * (tf4 * tf2);

        double x8poly = 1385 + 3633 * tf2 + 4095 * tf4 + 1575 * (tf4 * tf2);

        // Calculate latitude
        return new double[]{
                phif + x2frac * x2poly * (x * x)
                        + x4frac * x4poly * Math.pow(x, 4)
                        + x6frac * x6poly * Math.pow(x, 6)
                        + x8frac * x8poly * Math.pow(x, 8),

                // Calculate longitude
                lambda0 + x1frac * x
                        + x3frac * x3poly * Math.pow(x, 3)
                        + x5frac * x5poly * Math.pow(x, 5)
                        + x7frac * x7poly * Math.pow(x, 7)
        };
    }

    public double northing, easting;
    public boolean southern;
    public int zone;

    // Adjust easting and northing for UTM system.
    private static double[] transverseMercatorToUTM(double[] tm) {
        return new double[] {
                tm[0] * UTM_SCALE_FACTOR, tm[1] * UTM_SCALE_FACTOR + 500000
        };
    }

    /**
     * Converts x and y coordinates in the Universal Transverse Mercator
     * projection to a latitude/longitude pair.
    *
     * @return lat/lon of the point, as [lat, lon] degrees
     */
    public double[] toLATLON() {
        if (easting < MIN_EASTING || easting > MAX_EASTING) {
            throw new IllegalArgumentException(
                    "UTM easting " + easting + " outside " + MIN_EASTING + ".." + MAX_EASTING);
        }

        double max = southern ? MAX_NORTHING_S : MAX_NORTHING_N;
        if (northing < MIN_NORTHING || northing > max) {
            throw new IllegalArgumentException(
                    "UTM northing " + northing + " outside " + UTM.MIN_NORTHING + ".." + max);
        }

        if (zone < 1 || zone > 60) {
            throw new IllegalArgumentException("zone " + zone + " outside 1..60");
        }

        easting = (easting - 500000) / UTM_SCALE_FACTOR;

        /* If in southern hemisphere, adjust accordingly. */
        if (southern)
            northing -= 10000000;

        northing /= UTM_SCALE_FACTOR;

        double[] ll = transverseMercatorToLatLong(easting, northing, centralUTMMeridian(zone));
        if (ll[1] < -Math.PI)
            ll[1] += 2 * Math.PI;
        else if (ll[1] > Math.PI)
            ll[1] -= 2 * Math.PI;

        return new double[]{
                rad2deg(ll[0]),
                rad2deg(ll[1])
        };
    }

    /**
     * Get an Android Location for this point.
     * @return a Location object
     */
    public Location toLocation() {
        double[] ll = toLATLON();
        Location loc = new Location(LocationManager.PASSIVE_PROVIDER);
        loc.setLatitude(ll[0]);
        loc.setLongitude(ll[1]);
        loc.setAccuracy(0.1f);
        return loc;
    }

    /**
     * Convert a lat/long into a UTM coordinate latitude,longitude
     * can be passed as {lat,lon} in the first parameter.
     *
     * @param lat       latitude decimal degrees
     * @param lon       longitude decimal degrees
     * @param forceZone Optional, override computed zone (if in range 1..60)
     */
    public UTM(double lat, double lon, int forceZone) {
        if (lat > 84 || lat < -80)
            throw new IllegalArgumentException("latitude -80<=" + lat + "<=84");

        if (lon > 180 || lon < -180)
            throw new IllegalArgumentException("longitude -180<=" + lon + "<=180");

        if (lon == 180)
            lon = -180; // special case

        zone = (forceZone >= 1 && forceZone <= 60) ? forceZone : latLonToZone(lat, lon);
        southern = (lat < 0);

        double meridian = centralUTMMeridian(zone);
        double[] tm = latLonToTransverseMercator(deg2rad(lat), deg2rad(lon), meridian);
        double[] utm = transverseMercatorToUTM(tm);
        northing = (southern && utm[0] < 0) ? -utm[0] : utm[0];
        easting = utm[1];
    }

    public UTM(double lat, double lon) {
        this(lat, lon, 0);
    }

    public UTM(double north, double east, UTM template) {
        this(template);
        northing = north;
        easting = east;
    }

    public UTM(UTM u) {
        northing = u.northing;
        easting = u.easting;
        zone = u.zone;
        southern = u.southern;
    }

    public UTM(Location loc) {
        this(loc.getLatitude(), loc.getLongitude());
    }

    public String toString() {
        return zone + " " + Math.floor(easting) + " " + Math.floor(northing) + (southern ? "S" : "N");
    }
}
