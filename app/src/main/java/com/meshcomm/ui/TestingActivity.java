package com.meshcomm.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.meshcomm.R;
import com.meshcomm.service.MeshService;

import java.util.*;

public class TestingActivity extends AppCompatActivity {

    // ── UI ──────────────────────────────────────────────────────────────────
    private TextView  tvMyNodeId, tvMyIp, tvGps, tvWifiStatus,
                      tvBtStatus, tvPendingCount, tvLog;
    private ListView  lvNeighbors;
    private ArrayAdapter<String> neighborAdapter;
    private final List<String>   neighborItems = new ArrayList<>();

    private final StringBuilder logBuffer = new StringBuilder();
    private final Handler       handler   = new Handler(Looper.getMainLooper());
    private static final int    REFRESH_INTERVAL = 3000;

    // ── Runtime-permission launcher (replaces deprecated requestPermissions) ─
    private ActivityResultLauncher<String[]> permissionLauncher;

    /**
     * All permissions this screen needs.
     * On API < 31 the BLUETOOTH_* entries are simply ignored by the system.
     */
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        // Bluetooth permissions (API 31+)
        "android.permission.BLUETOOTH_CONNECT",   // read BT name
        "android.permission.BLUETOOTH_SCAN",
    };

    // ── BroadcastReceiver ────────────────────────────────────────────────────
    private final BroadcastReceiver meshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (MeshService.ACTION_MSG_IN.equals(action)) {
                appendLog("MSG RECEIVED from "
                        + intent.getStringExtra("from")
                        + ": " + intent.getStringExtra(MeshService.EXTRA_PAYLOAD));
            } else if (MeshService.ACTION_ACK_IN.equals(action)) {
                appendLog("ACK received for " + intent.getStringExtra("msgId"));
            } else if (MeshService.ACTION_BROADCAST.equals(action)) {
                appendLog("BROADCAST ["
                        + intent.getStringExtra(MeshService.EXTRA_CATEGORY) + "] from "
                        + intent.getStringExtra(MeshService.EXTRA_SENDER)
                        + ": " + intent.getStringExtra(MeshService.EXTRA_PAYLOAD));
            } else if (MeshService.ACTION_PEERS.equals(action)) {
                appendLog("PEERS: " + intent.getStringExtra(MeshService.EXTRA_PAYLOAD));
                refreshAll();
            }
        }
    };

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_testing);

        tvMyNodeId     = findViewById(R.id.tvMyNodeId);
        tvMyIp         = findViewById(R.id.tvMyIp);
        tvGps          = findViewById(R.id.tvGps);
        tvWifiStatus   = findViewById(R.id.tvWifiStatus);
        tvBtStatus     = findViewById(R.id.tvBtStatus);
        tvPendingCount = findViewById(R.id.tvPendingCount);
        tvLog          = findViewById(R.id.tvLog);
        lvNeighbors    = findViewById(R.id.lvNeighbors);

        neighborAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, neighborItems);
        lvNeighbors.setAdapter(neighborAdapter);

        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshAll());
        findViewById(R.id.btnSimulateHello).setOnClickListener(v -> simulateFakeNeighbor());
        findViewById(R.id.btnSendTestMsg).setOnClickListener(v -> sendSelfTestMessage());
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            logBuffer.setLength(0);
            tvLog.setText("");
        });

        // Register the permission-result launcher BEFORE any permission request
        permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            grantMap -> {
                // Re-run refresh now that the user has responded to the dialog
                refreshAll();
                // Log which ones were denied so the developer can see clearly
                for (Map.Entry<String, Boolean> e : grantMap.entrySet()) {
                    if (!e.getValue()) {
                        appendLog("PERMISSION DENIED: " + shortName(e.getKey())
                                + " — some info may be unavailable");
                    }
                }
            }
        );

        startService(new Intent(this, MeshService.class));
        requestMissingPermissions();   // ask once on first launch
        startAutoRefresh();
        refreshAll();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(MeshService.ACTION_MSG_IN);
        f.addAction(MeshService.ACTION_ACK_IN);
        f.addAction(MeshService.ACTION_BROADCAST);
        f.addAction(MeshService.ACTION_PEERS);
        // API 34+ requires RECEIVER_NOT_EXPORTED for non-system broadcasts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(meshReceiver, f, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(meshReceiver, f);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(meshReceiver); } catch (IllegalArgumentException ignored) {}
        handler.removeCallbacksAndMessages(null);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Permission helpers
    // ────────────────────────────────────────────────────────────────────────

    /** Returns true if the given permission is currently granted. */
    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Requests every REQUIRED_PERMISSION that is not yet granted. */
    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String p : REQUIRED_PERMISSIONS) {
            if (!hasPermission(p)) missing.add(p);
        }
        if (!missing.isEmpty()) {
            permissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    /** Returns a short human-readable name for a permission constant. */
    private String shortName(String permission) {
        int dot = permission.lastIndexOf('.');
        return dot >= 0 ? permission.substring(dot + 1) : permission;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Auto-refresh
    // ────────────────────────────────────────────────────────────────────────

    private void startAutoRefresh() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                refreshAll();
                handler.postDelayed(this, REFRESH_INTERVAL);
            }
        }, REFRESH_INTERVAL);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Main refresh
    // ────────────────────────────────────────────────────────────────────────

    private void refreshAll() {
        tvMyNodeId.setText("Node ID: NODE-"
                + Build.MODEL.replaceAll("\\s+", "").toUpperCase());
        tvMyIp.setText("IP: " + getDeviceIp());
        refreshGps();
        refreshWifiStatus();
        refreshBluetoothStatus();
        refreshNeighborList();
        appendLog("Refreshed at " + new java.text.SimpleDateFormat(
                "HH:mm:ss", Locale.getDefault()).format(new Date()));
    }

    // ────────────────────────────────────────────────────────────────────────
    // GPS — explicit permission check before every call
    // ────────────────────────────────────────────────────────────────────────

    private void refreshGps() {
        // Guard: both coarse and fine may be needed; check fine first
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                && !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            tvGps.setText("GPS: Permission not granted — tap Refresh after allowing");
            return;
        }

        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (loc == null) {
                loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (loc != null) {
                tvGps.setText(String.format(Locale.getDefault(),
                        "GPS: %.5f, %.5f  (acc: %.0f m)",
                        loc.getLatitude(), loc.getLongitude(), loc.getAccuracy()));
            } else {
                tvGps.setText("GPS: Waiting for fix — open Maps once to warm up GPS");
            }
        } catch (SecurityException e) {
            // Permission was revoked between the check and the call (race condition)
            tvGps.setText("GPS: Permission revoked — please re-grant in Settings");
            appendLog("SecurityException in refreshGps: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Wi-Fi status — no dangerous permission needed, but wrapped in try/catch
    // ────────────────────────────────────────────────────────────────────────

    private void refreshWifiStatus() {
        try {
            WifiP2pManager wpm =
                    (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
            WifiManager wm = (WifiManager)
                    getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            boolean wifiOn = (wm != null && wm.isWifiEnabled());
            tvWifiStatus.setText("Wi-Fi: " + (wifiOn ? "ON" : "OFF — turn on!")
                    + "  |  Wi-Fi Direct: "
                    + (wpm != null ? "Available" : "Not available"));
        } catch (Exception e) {
            tvWifiStatus.setText("Wi-Fi: Unable to read status");
            appendLog("refreshWifiStatus error: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Bluetooth — reading name requires BLUETOOTH_CONNECT on API 31+
    // ────────────────────────────────────────────────────────────────────────

    private void refreshBluetoothStatus() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null) {
                tvBtStatus.setText("Bluetooth: Not available on this device");
                return;
            }
            if (!bt.isEnabled()) {
                tvBtStatus.setText("Bluetooth: OFF — turn on for fallback communication");
                return;
            }

            // Reading bt.getName() requires BLUETOOTH_CONNECT on API 31+
            String btName;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+: explicit permission check required
                if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    btName = bt.getName();
                } else {
                    btName = "(grant BLUETOOTH_CONNECT permission to see name)";
                }
            } else {
                // API < 31: no runtime permission needed for getName()
                btName = bt.getName();
            }

            tvBtStatus.setText("Bluetooth: ON  |  Name: " + btName);

        } catch (SecurityException e) {
            // Defensive catch — handles any permission race conditions
            tvBtStatus.setText("Bluetooth: Permission denied — " + e.getMessage());
            appendLog("SecurityException in refreshBluetoothStatus: " + e.getMessage());
        } catch (Exception e) {
            tvBtStatus.setText("Bluetooth: Error reading status");
            appendLog("refreshBluetoothStatus error: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Wi-Fi IP address
    // ────────────────────────────────────────────────────────────────────────

    @SuppressLint("HardwareIds")
    private String getDeviceIp() {
        try {
            WifiManager wm = (WifiManager)
                    getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "Wi-Fi manager unavailable";
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return "Not connected";
            int ip = info.getIpAddress();
            if (ip == 0) return "No IP assigned";
            return String.format(Locale.getDefault(), "%d.%d.%d.%d",
                    (ip & 0xff), (ip >> 8 & 0xff),
                    (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        } catch (Exception e) {
            appendLog("getDeviceIp error: " + e.getMessage());
            return "Error reading IP";
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Neighbor list
    // ────────────────────────────────────────────────────────────────────────

    private void refreshNeighborList() {
        neighborItems.clear();
        neighborItems.add("Waiting for HELLO packets from other devices...");
        neighborItems.add("Expected: NODE-PIXEL6 | RSSI:-55dBm | Bat:85%");
        neighborAdapter.notifyDataSetChanged();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test actions
    // ────────────────────────────────────────────────────────────────────────

    private void simulateFakeNeighbor() {
        Intent i = new Intent(this, MeshService.class);
        i.setAction("SIMULATE_HELLO");
        i.putExtra("nodeId",  "NODE-TESTDEVICE");
        i.putExtra("lat",     12.9716);
        i.putExtra("lon",     77.5946);
        i.putExtra("battery", 75);
        i.putExtra("rssi",    -55);
        startService(i);
        appendLog("Simulated HELLO from NODE-TESTDEVICE (RSSI=-55, Bat=75%)");
    }

    private void sendSelfTestMessage() {
        String myNodeId = "NODE-" + Build.MODEL.replaceAll("\\s+", "").toUpperCase();
        Intent i = new Intent(this, MeshService.class);
        i.setAction("SEND");
        i.putExtra("dest",    myNodeId);
        i.putExtra("payload", "SELF-TEST-" + System.currentTimeMillis());
        startService(i);
        appendLog("Self-test message sent to: " + myNodeId);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Log
    // ────────────────────────────────────────────────────────────────────────

    private void appendLog(String line) {
        String ts = new java.text.SimpleDateFormat(
                "HH:mm:ss", Locale.getDefault()).format(new Date());
        logBuffer.insert(0, "[" + ts + "] " + line + "\n");
        String[] lines = logBuffer.toString().split("\n");
        if (lines.length > 30) {
            logBuffer.setLength(0);
            for (int i = 0; i < 30; i++) logBuffer.append(lines[i]).append("\n");
        }
        tvLog.setText(logBuffer.toString());
    }
}
