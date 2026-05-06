package com.meshcomm.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * HELLO Packet (~42 bytes)
 * Broadcast periodically for neighbor discovery.
 */
public class HelloPacket {
    public static final String TYPE = "HELLO";

    public String packetType;
    public String nodeId;
    public double latitude;
    public double longitude;
    public int batteryLevel;   // 0–100
    public long timestamp;

    public HelloPacket() {}

    public HelloPacket(String nodeId, double lat, double lon, int battery) {
        this.packetType   = TYPE;
        this.nodeId       = nodeId;
        this.latitude     = lat;
        this.longitude    = lon;
        this.batteryLevel = battery;
        this.timestamp    = System.currentTimeMillis();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("packetType",   packetType);
        o.put("nodeId",       nodeId);
        o.put("latitude",     latitude);
        o.put("longitude",    longitude);
        o.put("batteryLevel", batteryLevel);
        o.put("timestamp",    timestamp);
        return o;
    }

    public static HelloPacket fromJson(JSONObject o) throws JSONException {
        HelloPacket p = new HelloPacket();
        p.packetType   = o.getString("packetType");
        p.nodeId       = o.getString("nodeId");
        p.latitude     = o.getDouble("latitude");
        p.longitude    = o.getDouble("longitude");
        p.batteryLevel = o.getInt("batteryLevel");
        p.timestamp    = o.getLong("timestamp");
        return p;
    }
}
