package com.meshcomm.routing;

import com.meshcomm.model.NeighborNode;
import com.meshcomm.util.HaversineUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hybrid Geographic Routing Engine
 *
 * Routing rules (all three must pass):
 *   1. RSSI > -70 dBm           (acceptable signal)
 *   2. distance < 80 metres     (node is physically close)
 *   3. battery  > 20 %          (node can relay)
 *
 * Among qualifying candidates, the node with the STRONGEST RSSI is chosen.
 */
public class RoutingEngine {

    // Thresholds
    public static final int    RSSI_THRESHOLD_DBM      = -70;
    public static final double DISTANCE_THRESHOLD_M    = 80.0;
    public static final int    BATTERY_THRESHOLD_PCT   = 20;

    /**
     * Select the best next-hop neighbor towards the destination.
     *
     * @param neighbors    Current neighbor table (nodeId → NeighborNode)
     * @param destLat      Destination latitude
     * @param destLon      Destination longitude
     * @param selfLat      This device's current latitude
     * @param selfLon      This device's current longitude
     * @return Best NeighborNode, or null if no suitable candidate found
     */
    public static NeighborNode selectNextHop(Map<String, NeighborNode> neighbors,
                                             double destLat, double destLon,
                                             double selfLat, double selfLon) {

        List<NeighborNode> candidates = new ArrayList<>();

        for (NeighborNode node : neighbors.values()) {

            // Skip stale entries
            if (!node.isAlive()) continue;

            // Distance from THIS device to the neighbor
            double distToNeighbour = HaversineUtil.distanceMetres(
                    selfLat, selfLon, node.latitude, node.longitude);

            // Apply all three routing rules
            boolean rssiOk     = node.rssi         > RSSI_THRESHOLD_DBM;
            boolean distOk     = distToNeighbour   < DISTANCE_THRESHOLD_M;
            boolean batteryOk  = node.batteryLevel  > BATTERY_THRESHOLD_PCT;

            if (rssiOk && distOk && batteryOk) {
                candidates.add(node);
            }
        }

        if (candidates.isEmpty()) return null;

        // Select candidate with strongest RSSI (closest to 0 dBm)
        NeighborNode best = candidates.get(0);
        for (NeighborNode n : candidates) {
            if (n.rssi > best.rssi) {
                best = n;
            }
        }
        return best;
    }

    /**
     * Quick helper: returns a human-readable reason why a node was rejected.
     */
    public static String diagnose(NeighborNode node,
                                  double selfLat, double selfLon) {
        double dist = HaversineUtil.distanceMetres(
                selfLat, selfLon, node.latitude, node.longitude);

        StringBuilder sb = new StringBuilder();
        if (node.rssi <= RSSI_THRESHOLD_DBM)
            sb.append("RSSI too weak (").append(node.rssi).append(" dBm). ");
        if (dist >= DISTANCE_THRESHOLD_M)
            sb.append("Too far (").append((int)dist).append(" m). ");
        if (node.batteryLevel <= BATTERY_THRESHOLD_PCT)
            sb.append("Low battery (").append(node.batteryLevel).append("%). ");
        if (sb.length() == 0)
            sb.append("Eligible.");
        return sb.toString();
    }
}
