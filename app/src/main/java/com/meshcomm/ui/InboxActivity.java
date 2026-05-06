package com.meshcomm.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;

import com.meshcomm.R;
import com.meshcomm.db.MeshDatabase;
import com.meshcomm.db.ReceivedMessageDao;
import com.meshcomm.db.ReceivedMessageEntity;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * InboxActivity
 *
 * Shows ALL received messages (direct + broadcast) stored in Room DB.
 * Messages persist across app restarts — nothing is lost.
 *
 * Features:
 *  - Live updates via LiveData (new message → list updates instantly)
 *  - Unread messages highlighted
 *  - Tap a message to mark it as read
 *  - Long-press to delete
 *  - Mark All Read button
 *  - Clear All button
 */
public class InboxActivity extends AppCompatActivity {

    private ListView      listView;
    private InboxAdapter  adapter;
    private TextView      tvEmpty, tvUnreadBadge;
    private ReceivedMessageDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private List<ReceivedMessageEntity> currentMessages = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inbox);

        listView      = findViewById(R.id.lvInbox);
        tvEmpty       = findViewById(R.id.tvInboxEmpty);
        tvUnreadBadge = findViewById(R.id.tvUnreadBadge);

        dao = MeshDatabase.getInstance(this).receivedMessageDao();

        adapter = new InboxAdapter();
        listView.setAdapter(adapter);

        // Tap = mark as read, expand message
        listView.setOnItemClickListener((parent, view, pos, id) -> {
            ReceivedMessageEntity msg = currentMessages.get(pos);
            executor.execute(() -> {
                dao.markRead(msg.messageId);
                // No need to reload — LiveData auto-updates
            });
        });

        // Long-press = delete
        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            ReceivedMessageEntity msg = currentMessages.get(pos);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Delete message?")
                    .setMessage("From: " + msg.senderName + "\n\n" + msg.payload)
                    .setPositiveButton("Delete", (d, w) ->
                            executor.execute(() -> dao.deleteById(msg.messageId)))
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        // Mark all read
        findViewById(R.id.btnMarkAllRead).setOnClickListener(v ->
                executor.execute(dao::markAllRead));

        // Clear all
        findViewById(R.id.btnClearAll).setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Clear all messages?")
                        .setMessage("This will permanently delete all received messages.")
                        .setPositiveButton("Clear", (d, w) ->
                                executor.execute(dao::deleteAll))
                        .setNegativeButton("Cancel", null)
                        .show());

        // Observe LiveData — auto-refreshes list whenever DB changes
        dao.getAllLive().observe(this, messages -> {
            currentMessages = messages != null ? messages : new ArrayList<>();
            adapter.notifyDataSetChanged();
            tvEmpty.setVisibility(currentMessages.isEmpty() ? View.VISIBLE : View.GONE);
            listView.setVisibility(currentMessages.isEmpty() ? View.GONE : View.VISIBLE);
        });

        // Observe unread count for badge
        dao.getUnreadCount().observe(this, count -> {
            if (count != null && count > 0) {
                tvUnreadBadge.setVisibility(View.VISIBLE);
                tvUnreadBadge.setText(count + " unread");
            } else {
                tvUnreadBadge.setVisibility(View.GONE);
            }
        });
    }

    // -----------------------------------------------------------------------
    // Adapter
    // -----------------------------------------------------------------------

    class InboxAdapter extends BaseAdapter {

        private final SimpleDateFormat SDF =
                new SimpleDateFormat("dd MMM  HH:mm:ss", Locale.getDefault());

        @Override public int getCount()          { return currentMessages.size(); }
        @Override public Object getItem(int pos) { return currentMessages.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(InboxActivity.this)
                        .inflate(R.layout.item_inbox_message, parent, false);
            }

            ReceivedMessageEntity msg = currentMessages.get(pos);

            TextView tvType     = convertView.findViewById(R.id.tvInboxType);
            TextView tvSender   = convertView.findViewById(R.id.tvInboxSender);
            TextView tvMessage  = convertView.findViewById(R.id.tvInboxMessage);
            TextView tvTime     = convertView.findViewById(R.id.tvInboxTime);
            View     stripe     = convertView.findViewById(R.id.viewInboxStripe);
            View     unreadDot  = convertView.findViewById(R.id.viewUnreadDot);

            tvSender.setText(msg.senderName != null ? msg.senderName : msg.fromNodeId);
            tvMessage.setText(msg.payload);
            tvTime.setText(SDF.format(new Date(msg.receivedAt)));

            // Unread dot
            unreadDot.setVisibility(msg.isRead ? View.INVISIBLE : View.VISIBLE);

            // Unread = slightly brighter background
            convertView.setBackgroundColor(msg.isRead
                    ? Color.TRANSPARENT
                    : Color.parseColor("#F3F8FF"));

            // Type badge + stripe color
            if ("BROADCAST".equals(msg.type)) {
                String cat = msg.broadcastCategory != null ? msg.broadcastCategory : "BC";
                tvType.setText(cat);
                int color = categoryColor(cat);
                tvType.setTextColor(color);
                stripe.setBackgroundColor(color);
            } else {
                tvType.setText("DIRECT");
                tvType.setTextColor(Color.parseColor("#185FA5"));
                stripe.setBackgroundColor(Color.parseColor("#185FA5"));
            }

            return convertView;
        }

        private int categoryColor(String cat) {
            switch (cat) {
                case "SOS":    return Color.parseColor("#D32F2F");
                case "ALERT":  return Color.parseColor("#F57C00");
                case "RESCUE": return Color.parseColor("#1976D2");
                default:       return Color.parseColor("#388E3C");
            }
        }
    }
}
