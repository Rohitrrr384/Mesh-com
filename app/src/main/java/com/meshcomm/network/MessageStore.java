package com.meshcomm.network;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;

import com.meshcomm.db.MeshDatabase;
import com.meshcomm.db.PendingMessageDao;
import com.meshcomm.db.PendingMessageEntity;
import com.meshcomm.model.MessagePacket;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MessageStore (Room-backed)
 *
 * Replaces the old in-memory CopyOnWriteArrayList with a Room SQLite database.
 *
 * WHY THIS MATTERS:
 *   Old version: app crash / restart = ALL pending packets lost forever
 *   New version: packets survive app kill, phone restart, crashes
 *
 * All DB operations run on a background executor thread.
 */
public class MessageStore {

    private static final String TAG        = "MessageStore";
    private static final int    MAX_STORED = 50;
    private static final long   MAX_AGE_MS = 10 * 60 * 1000L;   // 10 minutes

    private final PendingMessageDao dao;
    private final ExecutorService   executor = Executors.newSingleThreadExecutor();

    public MessageStore(Context context) {
        dao = MeshDatabase.getInstance(context).pendingMessageDao();
    }

    // Store a packet that couldn't be forwarded
    public void store(MessagePacket packet) {
        executor.execute(() -> {
            int current = dao.getPendingCount();
            if (current >= MAX_STORED) {
                Log.w(TAG, "Store full, dropping " + packet.messageId);
                return;
            }
            dao.insert(toEntity(packet));
            Log.i(TAG, "Stored in DB: " + packet.messageId + " (total: " + (current+1) + ")");
        });
    }

    // Get all pending packets (call from background thread)
    public List<MessagePacket> getAll() {
        List<MessagePacket> result = new ArrayList<>();
        for (PendingMessageEntity e : dao.getAllPending()) result.add(toPacket(e));
        return result;
    }

    // Get pending packets for a specific destination
    public List<MessagePacket> getByDestination(String destinationNodeId) {
        List<MessagePacket> result = new ArrayList<>();
        for (PendingMessageEntity e : dao.getByDestination(destinationNodeId))
            result.add(toPacket(e));
        return result;
    }

    // Remove after successful delivery
    public void markDelivered(String messageId) {
        executor.execute(() -> {
            dao.deleteById(messageId);
            Log.i(TAG, "Delivered + removed from DB: " + messageId);
        });
    }

    // Delete expired + TTL-exhausted packets
    public void pruneExpired() {
        executor.execute(() -> {
            long threshold = System.currentTimeMillis() - MAX_AGE_MS;
            int age = dao.deleteExpired(threshold);
            int ttl = dao.deleteExpiredTtl();
            if (age + ttl > 0)
                Log.i(TAG, "Pruned " + age + " aged + " + ttl + " TTL-dead packets");
        });
    }

    // Wipe entire queue
    public void clearAll() {
        executor.execute(() -> { dao.deleteAll(); Log.i(TAG, "Queue cleared"); });
    }

    // LiveData for UI badge (observe in Activity/ViewModel)
    public LiveData<Integer> observePendingCount() {
        return dao.observePendingCount();
    }

    public int getPendingCount() { return dao.getPendingCount(); }

    // ---- Conversion helpers ----

    private PendingMessageEntity toEntity(MessagePacket p) {
        PendingMessageEntity e  = new PendingMessageEntity();
        e.messageId             = p.messageId;
        e.sourceNodeId          = p.sourceNodeId;
        e.destinationNodeId     = p.destinationNodeId;
        e.destinationLatitude   = p.destinationLatitude;
        e.destinationLongitude  = p.destinationLongitude;
        e.payload               = p.payload;
        e.ttl                   = p.ttl;
        e.timestamp             = p.timestamp;
        e.storedAt              = System.currentTimeMillis();
        e.retryCount            = 0;
        e.lastHopNodeId         = p.lastHopNodeId;
        e.routePath             = encodeHopPath(p.hopPath);
        return e;
    }

    private MessagePacket toPacket(PendingMessageEntity e) {
        MessagePacket p             = new MessagePacket();
        p.packetType                = MessagePacket.TYPE;
        p.messageId                 = e.messageId;
        p.sourceNodeId              = e.sourceNodeId;
        p.destinationNodeId         = e.destinationNodeId;
        p.destinationLatitude       = e.destinationLatitude;
        p.destinationLongitude      = e.destinationLongitude;
        p.payload                   = e.payload;
        p.ttl                       = e.ttl;
        p.timestamp                 = e.timestamp;
        p.lastHopNodeId             = e.lastHopNodeId;
        p.hopPath                   = decodeHopPath(e.routePath);
        return p;
    }

    private String encodeHopPath(List<String> path) {
        JSONArray arr = new JSONArray();
        if (path != null) {
            for (String hop : path) arr.put(hop);
        }
        return arr.toString();
    }

    private List<String> decodeHopPath(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String hop = arr.optString(i, null);
                if (hop != null && !hop.isEmpty() && !result.contains(hop)) {
                    result.add(hop);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Invalid route path in DB: " + e.getMessage());
        }
        return result;
    }
}
