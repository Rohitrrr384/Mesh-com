package com.meshcomm.model;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/**
 * ACK Packet (~59 bytes)
 * Sent by destination to confirm message delivery.
 */
public class AckPacket {
    public static final String TYPE            = "ACK";
    public static final String STATUS_OK       = "DELIVERED";
    public static final String STATUS_FAIL     = "FAILED";

    public String packetType;
    public String ackId;
    public String messageId;
    public String sourceNodeId;
    public String destinationNodeId;
    public long   timestamp;
    public String status;

    public AckPacket() {}

    public AckPacket(String messageId, String sourceNodeId, String destinationNodeId) {
        this.packetType       = TYPE;
        this.ackId            = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.messageId        = messageId;
        this.sourceNodeId     = sourceNodeId;
        this.destinationNodeId = destinationNodeId;
        this.timestamp        = System.currentTimeMillis();
        this.status           = STATUS_OK;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("packetType",        packetType);
        o.put("ackId",             ackId);
        o.put("messageId",         messageId);
        o.put("sourceNodeId",      sourceNodeId);
        o.put("destinationNodeId", destinationNodeId);
        o.put("timestamp",         timestamp);
        o.put("status",            status);
        return o;
    }

    public static AckPacket fromJson(JSONObject o) throws JSONException {
        AckPacket p = new AckPacket();
        p.packetType        = o.getString("packetType");
        p.ackId             = o.getString("ackId");
        p.messageId         = o.getString("messageId");
        p.sourceNodeId      = o.getString("sourceNodeId");
        p.destinationNodeId = o.getString("destinationNodeId");
        p.timestamp         = o.getLong("timestamp");
        p.status            = o.getString("status");
        return p;
    }
}
