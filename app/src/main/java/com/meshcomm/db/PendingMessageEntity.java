package com.meshcomm.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

/**
 * PendingMessageEntity – Room database table for store-and-forward packets.
 *
 * Each row = one MessagePacket that could not be delivered yet.
 *
 * Table: pending_messages
 * ┌────────────────┬──────────────────┬──────────┬───────────────┬──────────┬───────────┬───────────┐
 * │  messageId     │ destinationNodeId│  payload │  timestamp    │   ttl    │  destLat  │  destLon  │
 * │  (PRIMARY KEY) │                  │          │               │          │           │           │
 * └────────────────┴──────────────────┴──────────┴───────────────┴──────────┴───────────┴───────────┘
 */
@Entity(tableName = "pending_messages")
public class PendingMessageEntity {

    @PrimaryKey
    @NonNull
    public String messageId;

    public String sourceNodeId;
    public String destinationNodeId;
    public double destinationLatitude;
    public double destinationLongitude;
    public String payload;
    public int    ttl;
    public long   timestamp;       // original creation time (for expiry check)
    public long   storedAt;        // when it entered the DB (for ordering)
    public int    retryCount;      // how many times we've tried to forward
    public String lastHopNodeId;    // node that handed this packet to us
    public String routePath;        // JSON array of nodeIds already visited
}
