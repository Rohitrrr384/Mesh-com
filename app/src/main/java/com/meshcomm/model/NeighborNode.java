package com.meshcomm.model;

/**
 * Represents a discovered neighbor node stored in the neighbor table.
 */
public class NeighborNode {

    public String nodeId;
    public double latitude;
    public double longitude;
    public int    batteryLevel;
    public int    rssi;           // dBm  (e.g. -55)
    public long   lastSeen;       // epoch millis

    // How long (ms) before a neighbor is considered stale
    public static final long EXPIRY_MS = 30_000;   // 30 seconds

    public NeighborNode() {}

    public NeighborNode(String nodeId, double lat, double lon, int battery, int rssi) {
        this.nodeId       = nodeId;
        this.latitude     = lat;
        this.longitude    = lon;
        this.batteryLevel = battery;
        this.rssi         = rssi;
        this.lastSeen     = System.currentTimeMillis();
    }

    public String ipAddress;

    public NeighborNode(
            String nodeId,
            String hostAddress
    ){
        this.nodeId=nodeId;
        this.ipAddress=hostAddress;

        this.latitude=0;
        this.longitude=0;
        this.batteryLevel=100;
        this.rssi=-50;

        this.lastSeen=
                System.currentTimeMillis();
    }

    /** Returns true if the entry has not expired. */
    public boolean isAlive() {
        return (System.currentTimeMillis() - lastSeen) < EXPIRY_MS;
    }

    /** Refresh the entry with the latest HELLO data. */
    public void update(HelloPacket hello, int newRssi) {
        this.latitude     = hello.latitude;
        this.longitude    = hello.longitude;
        this.batteryLevel = hello.batteryLevel;
        this.rssi         = newRssi;
        this.lastSeen     = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return nodeId + " | RSSI:" + rssi + " dBm | Bat:" + batteryLevel
                + "% | (" + String.format("%.4f", latitude)
                + "," + String.format("%.4f", longitude) + ")";
    }
}
