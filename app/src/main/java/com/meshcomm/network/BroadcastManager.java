package com.meshcomm.network;

import android.util.Log;

import com.meshcomm.model.BroadcastPacket;
import com.meshcomm.model.NeighborNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BroadcastManager
 *
 * Implements controlled flooding for mesh-wide broadcast delivery.
 *
 * How it works:
 *  1. Source sends BroadcastPacket to ALL current neighbors simultaneously
 *  2. Each intermediate node re-floods to ALL its neighbors (except back to sender)
 *  3. Duplicate filter (broadcastId) ensures each node processes it only ONCE
 *  4. TTL limits total hop depth to prevent infinite flooding
 *
 * This guarantees every reachable node in the mesh receives the broadcast.
 */
public class BroadcastManager {

    private static final String TAG        = "BroadcastManager";
    private static final int    MAX_SEEN   = 256;
    private static final long   MAX_AGE_MS = 5 * 60 * 1000L;  // 5 minutes

    /** Callback interface — implemented by MeshService */
    public interface BroadcastListener {
        /** Called when a new broadcast is received and should be shown in UI. */
        void onBroadcastReceived(BroadcastPacket packet);

        /** Called when a broadcast needs to be forwarded to a specific neighbor. */
        void sendToNeighbor(NeighborNode neighbor, String json);
    }

    // LRU cache of seen broadcast IDs
    private final Map<String, Long> seenBroadcasts =
            Collections.synchronizedMap(
                new LinkedHashMap<String, Long>(MAX_SEEN, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                        return size() > MAX_SEEN;
                    }
                }
            );

    private final BroadcastListener listener;

    public BroadcastManager(BroadcastListener listener) {
        this.listener = listener;
    }

    // -----------------------------------------------------------------------
    // Send a new broadcast (called by THIS device's user)
    // -----------------------------------------------------------------------

    /**
     * Originate a new broadcast from this node.
     * Floods to ALL neighbors immediately.
     */
    public void sendBroadcast(BroadcastPacket packet,
                              Map<String, NeighborNode> neighbors) {
        try {
            // Mark as seen so we don't re-process our own broadcast
            seenBroadcasts.put(packet.broadcastId, System.currentTimeMillis());

            String json = packet.toJson().toString();
            int count = 0;
            for (NeighborNode neighbor : neighbors.values()) {
                if (neighbor.isAlive()) {
                    listener.sendToNeighbor(neighbor, json);
                    count++;
                }
            }
            Log.i(TAG, "Broadcast SENT [" + packet.category + "] "
                    + packet.broadcastId + " → " + count + " neighbors");

        } catch (Exception e) {
            Log.e(TAG, "Failed to send broadcast", e);
        }
    }

    // -----------------------------------------------------------------------
    // Receive + re-flood an incoming broadcast
    // -----------------------------------------------------------------------

    /**
     * Process a received BroadcastPacket.
     * Delivers to UI and re-floods to all neighbors (controlled flooding).
     *
     * @param packet    The received broadcast
     * @param fromNodeId The neighbor who sent it to us (avoid sending back)
     * @param neighbors  Current neighbor table for re-flooding
     */
    public void onBroadcastReceived(BroadcastPacket packet,
                                    String fromNodeId,
                                    Map<String, NeighborNode> neighbors) {

        // 1. Duplicate check
        if (seenBroadcasts.containsKey(packet.broadcastId)) {
            Log.d(TAG, "Duplicate broadcast dropped: " + packet.broadcastId);
            return;
        }

        // 2. TTL check
        if (packet.ttl <= 0) {
            Log.d(TAG, "Broadcast TTL expired: " + packet.broadcastId);
            return;
        }

        // 3. Age check
        long age = System.currentTimeMillis() - packet.timestamp;
        if (age > MAX_AGE_MS) {
            Log.d(TAG, "Broadcast expired (age=" + age/1000 + "s): " + packet.broadcastId);
            return;
        }

        // Mark as seen
        seenBroadcasts.put(packet.broadcastId, System.currentTimeMillis());

        // 4. Deliver to this node's UI
        listener.onBroadcastReceived(packet);
        Log.i(TAG, "Broadcast RECEIVED [" + packet.category + "] from "
                + packet.sourceNodeId + ": " + packet.payload);

        // 5. Re-flood to ALL neighbors (except the one who sent it to us)
        packet.ttl--;
        try {
            String json = packet.toJson().toString();
            int relayed = 0;
            for (NeighborNode neighbor : neighbors.values()) {
                if (!neighbor.nodeId.equals(fromNodeId) && neighbor.isAlive()) {
                    listener.sendToNeighbor(neighbor, json);
                    relayed++;
                }
            }
            Log.i(TAG, "Broadcast RELAYED to " + relayed + " neighbors (TTL left=" + packet.ttl + ")");
        } catch (Exception e) {
            Log.e(TAG, "Re-flood error", e);
        }
    }

    /** Number of unique broadcasts seen so far. */
    public int seenCount() { return seenBroadcasts.size(); }
}
