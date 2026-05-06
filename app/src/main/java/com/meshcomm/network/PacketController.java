package com.meshcomm.network;

import com.meshcomm.model.MessagePacket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PacketController
 *
 * Implements three network-flood-prevention mechanisms:
 *   1. Duplicate Packet Filtering  – drop already-seen message IDs
 *   2. TTL (Time To Live)          – drop packets that have used all hops
 *   3. Message Expiration          – drop packets older than MAX_AGE_MS
 */
public class PacketController {

    /** Maximum message age before a packet is considered expired (ms). */
    private static final long MAX_AGE_MS = 5 * 60 * 1000L;   // 5 minutes

    /** Max entries in the seen-messages table before LRU eviction. */
    private static final int MAX_SEEN = 256;

    /**
     * LRU cache used as the "Seen Messages Table".
     * Key = messageId, Value = time first seen.
     */
    private final Map<String, Long> seenMessages =
            Collections.synchronizedMap(
                new LinkedHashMap<String, Long>(MAX_SEEN, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                        return size() > MAX_SEEN;
                    }
                }
            );

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Validates a received MessagePacket through all three checks.
     *
     * @return ACCEPT if the packet should be forwarded/processed,
     *         a reason string otherwise.
     */
    public Result validate(MessagePacket packet) {

        // 1. Duplicate check
        if (seenMessages.containsKey(packet.messageId)) {
            return Result.reject("DUPLICATE:" + packet.messageId);
        }

        // 2. TTL check
        if (packet.ttl <= 0) {
            return Result.reject("TTL_EXPIRED:" + packet.messageId);
        }

        // 3. Age / expiration check
        long age = System.currentTimeMillis() - packet.timestamp;
        if (age > MAX_AGE_MS) {
            return Result.reject("MSG_EXPIRED:" + packet.messageId
                    + " age=" + (age / 1000) + "s");
        }

        // All checks passed – mark as seen
        seenMessages.put(packet.messageId, System.currentTimeMillis());
        return Result.ACCEPT;
    }

    /**
     * Decrement the TTL of a packet before forwarding it.
     * Must be called after validate() returns ACCEPT.
     */
    public void decrementTtl(MessagePacket packet) {
        packet.ttl--;
    }

    /** How many unique messages have been seen so far. */
    public int seenCount() {
        return seenMessages.size();
    }

    // -----------------------------------------------------------------------
    // Result helper
    // -----------------------------------------------------------------------

    public static class Result {
        public static final Result ACCEPT = new Result(true, "OK");

        public final boolean accepted;
        public final String  reason;

        private Result(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason   = reason;
        }

        public static Result reject(String reason) {
            return new Result(false, reason);
        }

        public boolean isAccepted() { return accepted; }
    }
}
