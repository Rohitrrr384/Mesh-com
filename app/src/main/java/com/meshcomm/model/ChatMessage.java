package com.meshcomm.model;

/**
 * Represents a single chat message shown in the UI.
 */
public class ChatMessage {

    public enum Direction { SENT, RECEIVED }

    public String    messageId;
    public String    fromNodeId;
    public String    toNodeId;
    public String    text;
    public long      timestamp;
    public Direction direction;
    public boolean   acknowledged;

    public ChatMessage(String messageId, String from, String to,
                       String text, Direction direction) {
        this.messageId    = messageId;
        this.fromNodeId   = from;
        this.toNodeId     = to;
        this.text         = text;
        this.timestamp    = System.currentTimeMillis();
        this.direction    = direction;
        this.acknowledged = false;
    }
}
