package com.meshcomm.model;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/**
 * BroadcastPacket
 *
 * Sent to ALL nodes in the mesh simultaneously.
 * Used for: SOS alerts, disaster warnings, group announcements.
 *
 * Approximate size: ~100 bytes
 */
public class BroadcastPacket {
    public static final String TYPE = "BROADCAST";
    public static final int    DEFAULT_TTL = 6;

    // Broadcast categories
    public static final String CATEGORY_SOS      = "SOS";
    public static final String CATEGORY_ALERT    = "ALERT";
    public static final String CATEGORY_INFO     = "INFO";
    public static final String CATEGORY_RESCUE   = "RESCUE";

    public String packetType;
    public String broadcastId;      // unique ID for duplicate filtering
    public String sourceNodeId;
    public String senderName;       // human-readable sender name
    public double senderLatitude;
    public double senderLongitude;
    public String category;         // SOS / ALERT / INFO / RESCUE
    public String payload;          // the message text
    public int    ttl;
    public long   timestamp;

    public BroadcastPacket() {}

    public BroadcastPacket(String sourceNodeId, String senderName,
                           double lat, double lon,
                           String category, String payload) {
        this.packetType     = TYPE;
        this.broadcastId    = "BC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.sourceNodeId   = sourceNodeId;
        this.senderName     = senderName;
        this.senderLatitude  = lat;
        this.senderLongitude = lon;
        this.category       = category;
        this.payload        = payload;
        this.ttl            = DEFAULT_TTL;
        this.timestamp      = System.currentTimeMillis();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("packetType",      packetType);
        o.put("broadcastId",     broadcastId);
        o.put("sourceNodeId",    sourceNodeId);
        o.put("senderName",      senderName);
        o.put("senderLatitude",  senderLatitude);
        o.put("senderLongitude", senderLongitude);
        o.put("category",        category);
        o.put("payload",         payload);
        o.put("ttl",             ttl);
        o.put("timestamp",       timestamp);
        return o;
    }

    public static BroadcastPacket fromJson(JSONObject o) throws JSONException {
        BroadcastPacket p = new BroadcastPacket();
        p.packetType      = o.getString("packetType");
        p.broadcastId     = o.getString("broadcastId");
        p.sourceNodeId    = o.getString("sourceNodeId");
        p.senderName      = o.getString("senderName");
        p.senderLatitude  = o.getDouble("senderLatitude");
        p.senderLongitude = o.getDouble("senderLongitude");
        p.category        = o.getString("category");
        p.payload         = o.getString("payload");
        p.ttl             = o.getInt("ttl");
        p.timestamp       = o.getLong("timestamp");
        return p;
    }
}
