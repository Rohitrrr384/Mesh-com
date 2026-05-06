package com.meshcomm.ui;

import android.content.*;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.meshcomm.R;
import com.meshcomm.model.BroadcastPacket;
import com.meshcomm.service.MeshService;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * BroadcastActivity
 *
 * Allows user to:
 *  1. Send a mesh-wide broadcast (SOS / ALERT / INFO / RESCUE)
 *  2. View all received broadcasts from other nodes in the mesh
 */
public class BroadcastActivity extends AppCompatActivity {

    private ListView          lvBroadcasts;
    private EditText          etSenderName, etBroadcastMsg;
    private Spinner           spinnerCategory;
    private TextView          tvBroadcastStatus;
    private BroadcastLogAdapter adapter;
    private final List<BroadcastLogEntry> log = new ArrayList<>();

    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // -----------------------------------------------------------------------
    // BroadcastReceiver – listens for incoming broadcasts from MeshService
    // -----------------------------------------------------------------------
    private final BroadcastReceiver meshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!MeshService.ACTION_BROADCAST.equals(intent.getAction())) return;

            String payload     = intent.getStringExtra(MeshService.EXTRA_PAYLOAD);
            String category    = intent.getStringExtra(MeshService.EXTRA_CATEGORY);
            String sender      = intent.getStringExtra(MeshService.EXTRA_SENDER);
            double lat         = intent.getDoubleExtra("lat", 0);
            double lon         = intent.getDoubleExtra("lon", 0);

            BroadcastLogEntry entry = new BroadcastLogEntry(
                    sender, category, payload, lat, lon, System.currentTimeMillis());
            log.add(0, entry);   // newest at top
            adapter.notifyDataSetChanged();
            tvBroadcastStatus.setText("📡 Received [" + category + "] from " + sender);
        }
    };

    // -----------------------------------------------------------------------
    // Activity lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_broadcast);

        lvBroadcasts     = findViewById(R.id.lvBroadcasts);
        etSenderName     = findViewById(R.id.etSenderName);
        etBroadcastMsg   = findViewById(R.id.etBroadcastMsg);
        spinnerCategory  = findViewById(R.id.spinnerCategory);
        tvBroadcastStatus = findViewById(R.id.tvBroadcastStatus);
        Button btnSendBroadcast = findViewById(R.id.btnSendBroadcast);

        // Category spinner
        String[] categories = {
                BroadcastPacket.CATEGORY_SOS,
                BroadcastPacket.CATEGORY_ALERT,
                BroadcastPacket.CATEGORY_RESCUE,
                BroadcastPacket.CATEGORY_INFO
        };
        ArrayAdapter<String> catAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, categories);
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(catAdapter);

        adapter = new BroadcastLogAdapter();
        lvBroadcasts.setAdapter(adapter);

        btnSendBroadcast.setOnClickListener(v -> sendBroadcast());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(meshReceiver, new IntentFilter(MeshService.ACTION_BROADCAST),RECEIVER_NOT_EXPORTED);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(meshReceiver);
    }

    // -----------------------------------------------------------------------
    // Send broadcast
    // -----------------------------------------------------------------------

    private void sendBroadcast() {
        String name    = etSenderName.getText().toString().trim();
        String message = etBroadcastMsg.getText().toString().trim();
        String category = spinnerCategory.getSelectedItem().toString();

        if (name.isEmpty())    { Toast.makeText(this, "Enter your name", Toast.LENGTH_SHORT).show(); return; }
        if (message.isEmpty()) { Toast.makeText(this, "Enter a message", Toast.LENGTH_SHORT).show(); return; }

        // Trigger via Intent to MeshService
        Intent i = new Intent(this, MeshService.class);
        i.setAction("BROADCAST");
        i.putExtra("category",   category);
        i.putExtra("message",    message);
        i.putExtra("senderName", name);
        startService(i);

        // Show locally in the log too
        BroadcastLogEntry own = new BroadcastLogEntry(
                "ME (" + name + ")", category, message, 0, 0, System.currentTimeMillis());
        own.isMine = true;
        log.add(0, own);
        adapter.notifyDataSetChanged();

        etBroadcastMsg.setText("");
        tvBroadcastStatus.setText("📡 Broadcast sent [" + category + "] to all nodes");
    }

    // -----------------------------------------------------------------------
    // Log entry model
    // -----------------------------------------------------------------------

    static class BroadcastLogEntry {
        String  sender, category, payload;
        double  lat, lon;
        long    timestamp;
        boolean isMine = false;

        BroadcastLogEntry(String sender, String category, String payload,
                          double lat, double lon, long timestamp) {
            this.sender    = sender;
            this.category  = category;
            this.payload   = payload;
            this.lat       = lat;
            this.lon       = lon;
            this.timestamp = timestamp;
        }
    }

    // -----------------------------------------------------------------------
    // Adapter
    // -----------------------------------------------------------------------

    class BroadcastLogAdapter extends BaseAdapter {

        @Override public int getCount()              { return log.size(); }
        @Override public Object getItem(int pos)     { return log.get(pos); }
        @Override public long getItemId(int pos)     { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(BroadcastActivity.this)
                        .inflate(R.layout.item_broadcast, parent, false);
            }
            BroadcastLogEntry e = log.get(pos);

            TextView tvCategory = convertView.findViewById(R.id.tvBcCategory);
            TextView tvSender   = convertView.findViewById(R.id.tvBcSender);
            TextView tvMessage  = convertView.findViewById(R.id.tvBcMessage);
            TextView tvTime     = convertView.findViewById(R.id.tvBcTime);
            View     stripe     = convertView.findViewById(R.id.viewCategoryStripe);

            tvCategory.setText(e.category);
            tvSender.setText(e.isMine ? "📤 You" : "📥 " + e.sender);
            tvMessage.setText(e.payload);
            tvTime.setText(SDF.format(new Date(e.timestamp)));

            // Color-code by category
            int color;
            switch (e.category) {
                case BroadcastPacket.CATEGORY_SOS:    color = Color.parseColor("#D32F2F"); break;
                case BroadcastPacket.CATEGORY_ALERT:  color = Color.parseColor("#F57C00"); break;
                case BroadcastPacket.CATEGORY_RESCUE: color = Color.parseColor("#1976D2"); break;
                default:                              color = Color.parseColor("#388E3C"); break;
            }
            stripe.setBackgroundColor(color);
            tvCategory.setTextColor(color);

            // My own broadcasts slightly dimmed
            convertView.setAlpha(e.isMine ? 0.75f : 1.0f);
            return convertView;
        }
    }
}
