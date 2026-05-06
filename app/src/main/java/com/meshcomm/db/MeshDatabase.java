package com.meshcomm.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/**
 * MeshDatabase – singleton Room database.
 *
 * Tables:
 *   • pending_messages   (PendingMessageEntity)  ← store-and-forward DTN queue
 *   • received_messages  (ReceivedMessageEntity) ← inbox for this device
 *
 * Version history:
 *   v1 – initial schema (pending_messages)
 *   v2 – added received_messages table
 */
@Database(
    entities = { PendingMessageEntity.class, ReceivedMessageEntity.class },
    version  = 3,
    exportSchema = false
)
public abstract class MeshDatabase extends RoomDatabase {

    public abstract PendingMessageDao pendingMessageDao();
    public abstract ReceivedMessageDao receivedMessageDao();

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE pending_messages ADD COLUMN lastHopNodeId TEXT");
            database.execSQL("ALTER TABLE pending_messages ADD COLUMN routePath TEXT");
        }
    };

    // -----------------------------------------------------------------------
    // Singleton
    // -----------------------------------------------------------------------

    private static volatile MeshDatabase INSTANCE;

    public static MeshDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (MeshDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            MeshDatabase.class,
                            "mesh_db"             // file: mesh_db on device storage
                    )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()   // safe for v1 dev
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
