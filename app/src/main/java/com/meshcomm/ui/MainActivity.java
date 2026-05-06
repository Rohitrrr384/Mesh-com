package com.meshcomm.ui;

import android.app.AlertDialog;
import android.content.*;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.meshcomm.R;
import com.meshcomm.model.ChatMessage;
import com.meshcomm.service.MeshService;

import android.location.LocationManager;

import java.util.ArrayList;
import java.util.List;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;

/**
 * MainActivity – Chat + neighbor overview screen.
 */
public class MainActivity extends AppCompatActivity {

    private ChatAdapter     chatAdapter;
    private final List<ChatMessage> messages = new ArrayList<>();

    private EditText  etDestination, etMessage;
    private TextView  tvStatus;
    private RecyclerView rvChat;

    // -----------------------------------------------------------------------
    // BroadcastReceiver – listens for messages & ACKs from MeshService
    // -----------------------------------------------------------------------
    private final BroadcastReceiver meshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (MeshService.ACTION_MSG_IN.equals(action)) {
                String from    = intent.getStringExtra("from");
                String payload = intent.getStringExtra(MeshService.EXTRA_PAYLOAD);
                String msgId   = intent.getStringExtra("msgId");
                ChatMessage cm = new ChatMessage(msgId, from, "me",
                        payload, ChatMessage.Direction.RECEIVED);
                messages.add(cm);
                chatAdapter.notifyItemInserted(messages.size() - 1);
                rvChat.scrollToPosition(messages.size() - 1);

            } else if (MeshService.ACTION_ACK_IN.equals(action)) {
                String msgId  = intent.getStringExtra("msgId");
                String status = intent.getStringExtra("status");
                // Mark matching message as acknowledged
                for (ChatMessage m : messages) {
                    if (m.messageId.equals(msgId)) {
                        m.acknowledged = true;
                        chatAdapter.notifyDataSetChanged();
                        break;
                    }
                }
                tvStatus.setText("✓ Delivered: " + msgId);

            } else if (MeshService.ACTION_PEERS.equals(action)) {
                tvStatus.setText(intent.getStringExtra(MeshService.EXTRA_PAYLOAD));
            }
        }
    };

    // -----------------------------------------------------------------------
    // Activity lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkLocationEnabled();
        setContentView(R.layout.activity_main);

        rvChat        = findViewById(R.id.rvChat);
        etDestination = findViewById(R.id.etDestination);
        etMessage     = findViewById(R.id.etMessage);
        tvStatus      = findViewById(R.id.tvStatus);
        Button btnSend      = findViewById(R.id.btnSend);
        Button btnPeers     = findViewById(R.id.btnPeers);
        Button btnBroadcast = findViewById(R.id.btnBroadcast);
        Button btnTest      = findViewById(R.id.btnTest);
        Button btnInbox     = findViewById(R.id.btnInbox);

        chatAdapter = new ChatAdapter(messages);
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        rvChat.setAdapter(chatAdapter);

        // Start background mesh service
        requestAllPermissions();

        btnSend.setOnClickListener(v -> sendMessage());
        btnPeers.setOnClickListener(v -> {
            startActivity(new Intent(this, NeighborActivity.class));
        });
        btnBroadcast.setOnClickListener(v -> {
            startActivity(new Intent(this, BroadcastActivity.class));
        });
        btnTest.setOnClickListener(v -> {
            startActivity(new Intent(this, TestingActivity.class));
        });
        btnInbox.setOnClickListener(v -> {
            startActivity(new Intent(this, InboxActivity.class));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(MeshService.ACTION_MSG_IN);
        filter.addAction(MeshService.ACTION_ACK_IN);
        filter.addAction(MeshService.ACTION_PEERS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(meshReceiver, filter,RECEIVER_NOT_EXPORTED);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(meshReceiver);
    }

    // -----------------------------------------------------------------------
    // Send message
    // -----------------------------------------------------------------------

    private void sendMessage() {
        String dest = etDestination.getText().toString().trim();
        String text = etMessage.getText().toString().trim();
        if (dest.isEmpty() || text.isEmpty()) {
            Toast.makeText(this, "Enter destination and message", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show in chat immediately
        String fakeId = "MSG-" + System.currentTimeMillis();
        ChatMessage cm = new ChatMessage(fakeId, "me", dest, text, ChatMessage.Direction.SENT);
        messages.add(cm);
        chatAdapter.notifyItemInserted(messages.size() - 1);
        rvChat.scrollToPosition(messages.size() - 1);
        etMessage.setText("");

        // Use Intent to trigger the service (simple approach without binding)
        Intent i = new Intent(this, MeshService.class);
        i.setAction("SEND");
        i.putExtra("dest",    dest);
        i.putExtra("payload", text);
        startService(i);
        tvStatus.setText("Sending → " + dest);
    }

    // In onCreate(), BEFORE startService(...), call this:
    private static final int PERM_REQUEST_CODE = 101;

    private void requestAllPermissions() {
        List<String> needed = new ArrayList<>();
        String[] perms;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            perms = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12
            perms = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            };
        } else {
            perms = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }

        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERM_REQUEST_CODE);
        } else {
            // All granted — start service now
            Intent i = new Intent(this, MeshService.class);
            i.setAction("DISCOVER");
            startService(i);
        }
    }

    // Handle the result
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERM_REQUEST_CODE) {
            // Start the service — MeshService.initWifiDirect() handles discovery
            // internally with a 1s delay after the channel is ready.
            // We do NOT send a separate DISCOVER intent here because that causes
            // a rapid double-call which results in Wi-Fi Direct BUSY errors.
            startService(new Intent(this, MeshService.class));
        }
    }



    private void checkLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            new AlertDialog.Builder(this)
                    .setTitle("Enable Location")
                    .setMessage("Wi-Fi Direct device discovery requires Location to be ON in system settings.")
                    .setPositiveButton("Open Settings", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }
}