package com.meshcomm.util;

/**
 * Haversine formula for calculating great-circle distance between two GPS coordinates.
 *
 *   a = sin²((lat2−lat1)/2) + cos(lat1)·cos(lat2)·sin²((lon2−lon1)/2)
 *   distance = R × 2 × atan2(√a, √(1−a))
 *   R = 6371 km
 */
public class HaversineUtil {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * Returns distance in metres between two lat/lon points.
     */
    public static double distanceMetres(double lat1, double lon1,
                                        double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.pow(Math.sin(dLat / 2), 2)
                 + Math.cos(Math.toRadians(lat1))
                 * Math.cos(Math.toRadians(lat2))
                 * Math.pow(Math.sin(dLon / 2), 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c * 1000.0;   // convert km → metres
    }
}
