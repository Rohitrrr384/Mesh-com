package com.meshcomm.db;

import androidx.lifecycle.LiveData;
import androidx.room.*;

import java.util.List;

/**
 * PendingMessageDao – all database operations for the store-and-forward queue.
 */
@Dao
public interface PendingMessageDao {

    // -----------------------------------------------------------------------
    // INSERT
    // -----------------------------------------------------------------------

    /** Store a new undelivered packet. Replaces if same messageId already exists. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(PendingMessageEntity entity);

    // -----------------------------------------------------------------------
    // QUERY
    // -----------------------------------------------------------------------

    /** All pending packets, oldest first (by storedAt). */
    @Query("SELECT * FROM pending_messages ORDER BY storedAt ASC")
    List<PendingMessageEntity> getAllPending();

    /** Packets destined for a specific node (useful for targeted retry). */
    @Query("SELECT * FROM pending_messages WHERE destinationNodeId = :destId ORDER BY storedAt ASC")
    List<PendingMessageEntity> getByDestination(String destId);

    /** Count of pending packets – drives the UI badge. */
    @Query("SELECT COUNT(*) FROM pending_messages")
    int getPendingCount();

    /** LiveData version – UI observes this and updates automatically. */
    @Query("SELECT COUNT(*) FROM pending_messages")
    LiveData<Integer> observePendingCount();

    /** Packets that have exceeded the max age and should be discarded. */
    @Query("SELECT * FROM pending_messages WHERE timestamp < :expiryThreshold")
    List<PendingMessageEntity> getExpired(long expiryThreshold);

    /** Packets with TTL <= 0 (should never be forwarded). */
    @Query("SELECT * FROM pending_messages WHERE ttl <= 0")
    List<PendingMessageEntity> getExpiredTtl();

    // -----------------------------------------------------------------------
    // UPDATE
    // -----------------------------------------------------------------------

    /** Increment retry count after a failed delivery attempt. */
    @Query("UPDATE pending_messages SET retryCount = retryCount + 1 WHERE messageId = :messageId")
    void incrementRetry(String messageId);

    // -----------------------------------------------------------------------
    // DELETE
    // -----------------------------------------------------------------------

    /** Remove a successfully delivered packet. */
    @Query("DELETE FROM pending_messages WHERE messageId = :messageId")
    void deleteById(String messageId);

    /** Remove all packets older than the given threshold. */
    @Query("DELETE FROM pending_messages WHERE timestamp < :expiryThreshold")
    int deleteExpired(long expiryThreshold);

    /** Remove all packets with TTL exhausted. */
    @Query("DELETE FROM pending_messages WHERE ttl <= 0")
    int deleteExpiredTtl();

    /** Clear everything (e.g. user manually flushes queue). */
    @Query("DELETE FROM pending_messages")
    void deleteAll();
}
