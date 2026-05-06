package com.meshcomm.network;

import com.meshcomm.model.HelloPacket;
import com.meshcomm.model.NeighborNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeighborTable
 *
 * Stores discovered neighbors from received HELLO packets.
 * Provides pruning of stale entries.
 */
public class NeighborTable {

    private final Map<String, NeighborNode> table = new ConcurrentHashMap<>();

    /**
     * Process a received HELLO packet and update/add the neighbor.
     *
     * @param hello   Parsed HELLO packet
     * @param rssi    Signal strength at which the packet was received (dBm)
     */
    public void onHelloReceived(HelloPacket hello, int rssi) {
        NeighborNode existing = table.get(hello.nodeId);
        if (existing != null) {
            existing.update(hello, rssi);
        } else {
            NeighborNode node = new NeighborNode(
                    hello.nodeId,
                    hello.latitude,
                    hello.longitude,
                    hello.batteryLevel,
                    rssi);
            table.put(hello.nodeId, node);
        }
    }

    /** Remove entries that have not sent a HELLO recently. */
    public synchronized void pruneStale() {
        table.entrySet().removeIf(e -> !e.getValue().isAlive());
    }

    public Map<String, NeighborNode> getAll() {
        return table;
    }

    public NeighborNode get(String nodeId) {
        return table.get(nodeId);
    }

    public boolean contains(String nodeId) {
        return table.containsKey(nodeId);
    }

    public int size() {
        return table.size();
    }

    public List<NeighborNode> asList() {
        pruneStale();
        return new ArrayList<>(table.values());
    }
}
