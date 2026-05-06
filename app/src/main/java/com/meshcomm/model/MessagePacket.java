package com.meshcomm.model;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Message Packet (70–150 bytes)
 * Carries the actual user message across the mesh.
 */
public class MessagePacket {
    public static final String TYPE = "MSG";
    public static final int    DEFAULT_TTL = 6;

    public String packetType;
    public String messageId;
    public String sourceNodeId;
    public String destinationNodeId;
    public double destinationLatitude;
    public double destinationLongitude;
    public int    ttl;
    public long   timestamp;
    public String payload;
    public String lastHopNodeId;
    public List<String> hopPath = new ArrayList<>();

    public MessagePacket() {}

    public MessagePacket(String sourceNodeId,
                         String destinationNodeId,
                         double destLat, double destLon,
                         String payload) {
        this.packetType           = TYPE;
        this.messageId            = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.sourceNodeId         = sourceNodeId;
        this.destinationNodeId    = destinationNodeId;
        this.destinationLatitude  = destLat;
        this.destinationLongitude = destLon;
        this.ttl                  = DEFAULT_TTL;
        this.timestamp            = System.currentTimeMillis();
        this.payload              = payload;
    }

    public MessagePacket copy() {
        MessagePacket p = new MessagePacket();
        p.packetType            = packetType;
        p.messageId             = messageId;
        p.sourceNodeId          = sourceNodeId;
        p.destinationNodeId     = destinationNodeId;
        p.destinationLatitude   = destinationLatitude;
        p.destinationLongitude  = destinationLongitude;
        p.ttl                   = ttl;
        p.timestamp             = timestamp;
        p.payload               = payload;
        p.lastHopNodeId         = lastHopNodeId;
        p.hopPath               = hopPath != null ? new ArrayList<>(hopPath) : new ArrayList<>();
        return p;
    }

    public void markForwardedBy(String nodeId) {
        if (nodeId == null || nodeId.trim().isEmpty()) return;
        lastHopNodeId = nodeId;
        if (hopPath == null) hopPath = new ArrayList<>();
        if (!hopPath.contains(nodeId)) hopPath.add(nodeId);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("packetType",            packetType);
        o.put("messageId",             messageId);
        o.put("sourceNodeId",          sourceNodeId);
        o.put("destinationNodeId",     destinationNodeId);
        o.put("destinationLatitude",   destinationLatitude);
        o.put("destinationLongitude",  destinationLongitude);
        o.put("ttl",                   ttl);
        o.put("timestamp",             timestamp);
        o.put("payload",               payload);
        if (lastHopNodeId != null) o.put("lastHopNodeId", lastHopNodeId);
        JSONArray path = new JSONArray();
        if (hopPath != null) {
            for (String hop : hopPath) path.put(hop);
        }
        o.put("hopPath", path);
        return o;
    }

    public static MessagePacket fromJson(JSONObject o) throws JSONException {
        MessagePacket p = new MessagePacket();
        p.packetType           = o.getString("packetType");
        p.messageId            = o.getString("messageId");
        p.sourceNodeId         = o.getString("sourceNodeId");
        p.destinationNodeId    = o.getString("destinationNodeId");
        p.destinationLatitude  = o.getDouble("destinationLatitude");
        p.destinationLongitude = o.getDouble("destinationLongitude");
        p.ttl                  = o.getInt("ttl");
        p.timestamp            = o.getLong("timestamp");
        p.payload              = o.getString("payload");
        p.lastHopNodeId        = o.optString("lastHopNodeId", null);
        if (p.lastHopNodeId != null && p.lastHopNodeId.isEmpty()) {
            p.lastHopNodeId = null;
        }
        p.hopPath              = new ArrayList<>();
        JSONArray path = o.optJSONArray("hopPath");
        if (path != null) {
            for (int i = 0; i < path.length(); i++) {
                String hop = path.optString(i, null);
                if (hop != null && !hop.isEmpty() && !p.hopPath.contains(hop)) {
                    p.hopPath.add(hop);
                }
            }
        }
        return p;
    }
}
