package com.meshcomm.db;

import androidx.lifecycle.LiveData;
import androidx.room.*;
import java.util.List;

@Dao
public interface ReceivedMessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(ReceivedMessageEntity msg);

    /** All messages, newest first. */
    @Query("SELECT * FROM received_messages ORDER BY receivedAt DESC")
    LiveData<List<ReceivedMessageEntity>> getAllLive();

    /** Synchronous version for non-UI use. */
    @Query("SELECT * FROM received_messages ORDER BY receivedAt DESC")
    List<ReceivedMessageEntity> getAll();

    /** Only unread messages. */
    @Query("SELECT * FROM received_messages WHERE isRead = 0 ORDER BY receivedAt DESC")
    LiveData<List<ReceivedMessageEntity>> getUnreadLive();

    /** Count of unread messages — drives the badge on the inbox button. */
    @Query("SELECT COUNT(*) FROM received_messages WHERE isRead = 0")
    LiveData<Integer> getUnreadCount();

    /** Mark a single message as read. */
    @Query("UPDATE received_messages SET isRead = 1 WHERE messageId = :messageId")
    void markRead(String messageId);

    /** Mark all as read. */
    @Query("UPDATE received_messages SET isRead = 1")
    void markAllRead();

    /** Delete a single message. */
    @Query("DELETE FROM received_messages WHERE messageId = :messageId")
    void deleteById(String messageId);

    /** Clear everything. */
    @Query("DELETE FROM received_messages")
    void deleteAll();

    @Query("SELECT * FROM received_messages WHERE messageId = :id LIMIT 1")
    ReceivedMessageEntity findById(String id);
}
