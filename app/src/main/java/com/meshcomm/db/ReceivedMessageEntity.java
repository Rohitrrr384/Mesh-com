package com.meshcomm.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

/**
 * ReceivedMessageEntity – persists every message delivered to this device.
 *
 * Table: received_messages
 * Every time MeshService calls onMessageReceived() and the destination
 * matches this node, a row is inserted here so the user can read it later
 * even if the app was in the background.
 */
@Entity(tableName = "received_messages")
public class ReceivedMessageEntity {

    @PrimaryKey
    @NonNull
    public String messageId;

    public String fromNodeId;       // who sent the original message
    public String payload;          // the actual message text
    public long   receivedAt;       // epoch millis – when this device got it
    public boolean isRead;          // false = unread (shows badge in UI)
    public String  type;            // "DIRECT" or "BROADCAST"
    public String  broadcastCategory; // SOS / ALERT / INFO / RESCUE (null for DIRECT)
    public String  senderName;      // display name (from broadcast) or nodeId for direct
}
