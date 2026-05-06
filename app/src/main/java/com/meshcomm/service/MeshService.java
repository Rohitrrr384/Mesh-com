package com.meshcomm.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.*;
import android.os.*;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.meshcomm.ui.MainActivity;
import com.meshcomm.db.MeshDatabase;
import com.meshcomm.db.ReceivedMessageDao;
import com.meshcomm.db.ReceivedMessageEntity;
import com.meshcomm.model.*;
import com.meshcomm.network.*;
import com.meshcomm.routing.RoutingEngine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MeshService extends Service {

    private static final String TAG              = "MeshService";
    private static final String CHANNEL_ID       = "meshcomm_service";
    public  static final String ACTION_MSG_IN    = "com.meshcomm.MSG_IN";
    public  static final String ACTION_ACK_IN    = "com.meshcomm.ACK_IN";
    public  static final String ACTION_PEERS     = "com.meshcomm.PEERS_UPDATED";
    public  static final String ACTION_BROADCAST = "com.meshcomm.BROADCAST_IN";
    public  static final String EXTRA_PAYLOAD    = "payload";
    public  static final String EXTRA_CATEGORY   = "category";
    public  static final String EXTRA_SENDER     = "sender";

    // ── Binder ────────────────────────────────────────────────────────────────
    public class LocalBinder extends Binder {
        public MeshService getService() { return MeshService.this; }
    }
    private final LocalBinder binder = new LocalBinder();

    // ── Wi-Fi Direct ──────────────────────────────────────────────────────────
    private WifiP2pManager         wifiP2pManager;
    private WifiP2pManager.Channel wifiChannel;
    private ServerSocket           wifiServerSocket;

    private final ConcurrentHashMap<String, String>        nodeIpMap   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>        nodeMacMap  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WifiP2pDevice> wifiPeerMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>        macNodeMap  = new ConcurrentHashMap<>();

    // ── GO (hub) election state ───────────────────────────────────────────────
    private boolean amGroupOwner = false;

    // ── Multi-group mesh: track client count to know when to yield GO role ───
    private static final int  MAX_GROUP_CLIENTS = 2;
    // stay under Android's limit
    private static final long GROUP_REFORM_MS   = 30_000;
    private int  currentGroupClientCount        = 0;
    private long lastGroupReformTime            = 0;

    // ── Connect queue ─────────────────────────────────────────────────────────
    private final ConcurrentLinkedQueue<WifiP2pDevice> pendingConnectQueue = new ConcurrentLinkedQueue<>();

    // ── Bluetooth ─────────────────────────────────────────────────────────────
    private static final UUID     BT_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
    private BluetoothAdapter      bluetoothAdapter;
    private BluetoothServerSocket btServerSocket;

    // ── Core subsystems ───────────────────────────────────────────────────────
    private final NeighborTable    neighborTable = new NeighborTable();
    private final PacketController packetCtrl    = new PacketController();
    private       MessageStore     messageStore;
    private       BroadcastManager broadcastMgr;
    private       ReceivedMessageDao receivedDao;

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    // ── Device identity ───────────────────────────────────────────────────────
    private String myNodeId;
    private double myLat     = 0;
    private double myLon     = 0;
    private int    myBattery = 100;

    private final Handler    handler            = new Handler(Looper.getMainLooper());
    private static final int HELLO_INTERVAL     = 5_000;
    private static final int WIFI_PORT          = 8988;
    private static final int DB_PRUNE_INTERVAL  = 60_000;
    private static final int DTN_RETRY_INTERVAL = 10_000;

    private static final long NEIGHBOR_EXPIRY_MS      = 15_000;
    private static final long HELLO_REPLY_COOLDOWN_MS = 30_000;

    // ── Discovery state ───────────────────────────────────────────────────────
    private boolean isDiscovering    = false;
    private long    lastDiscoverTime = 0;
    private static final int  DISCOVER_COOLDOWN_MS = 15_000;

    // ── Wi-Fi Direct connect throttling ───────────────────────────────────────
    private boolean wifiConnectInProgress = false;
    private long    lastConnectAttempt   = 0;
    private static final long CONNECT_COOLDOWN_MS = 20_000;

    // ── Multi-hop routing ─────────────────────────────────────────────────────
    private static final int MAX_TTL = 10;

    private final ConcurrentHashMap<String, Long>   seenPackets    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> prevHopMap     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>   seenBroadcasts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>   helloReplyAt   = new ConcurrentHashMap<>();

    // ── 2-hop topology ────────────────────────────────────────────────────────
    private final ConcurrentHashMap<String, Set<String>> topologyMap = new ConcurrentHashMap<>();

    // ── RSSI tracking ─────────────────────────────────────────────────────────
    //
    // ROOT CAUSE: Wi-Fi Direct uses randomly-generated virtual MACs (e.g.
    // e6:57:68:xx, 42:de:24:xx) that are completely different from the hardware
    // MACs seen in WifiManager.getScanResults() (e.g. B4:3D:08:xx). Android
    // intentionally creates these ephemeral MACs per P2P session, so the
    // IP→nodeId→MAC→scanResult chain will ALWAYS fail for P2P peers.
    //
    // FIX STRATEGY — three independent sources, all keyed by IP or nodeId
    // (not MAC), merged via EMA:
    //
    //   Source 1 — TCP round-trip time (RTT).
    //     Every outbound TCP send measures wall-clock time for connect+write.
    //     RTT maps onto a synthetic dBm value via rttToRssi().
    //     This is available for every active neighbor and degrades gracefully
    //     with distance (higher latency = weaker synthetic RSSI).
    //
    //   Source 2 — HELLO packet latency.
    //     Each HELLO carries a timestamp. The receiver computes one-way delay
    //     (assuming loose clock sync) and feeds it into the same rttToRssi()
    //     converter, keyed by nodeId.
    //
    //   Source 3 — Bluetooth EXTRA_RSSI (real dBm when available).
    //     BT discovery still gives real RSSI; stored by BT MAC and also
    //     cross-referenced via nodeMacMap → nodeId → ipRssiMap for routing.
    //
    // All three sources update ipRssiMap (IP→dBm) and nodeRssiMap
    // (nodeId→dBm). resolveRssiForIp() and resolveRssiForNode() query these
    // directly — no MAC lookup needed.

    /**
     * IP address → smoothed RSSI estimate (dBm).
     * Updated by TCP RTT measurements and BT cross-reference.
     */
    private final ConcurrentHashMap<String, Integer> ipRssiMap   = new ConcurrentHashMap<>();

    /**
     * nodeId → smoothed RSSI estimate (dBm).
     * Updated from HELLO timestamps and BT RSSI cross-reference.
     * Used when we know the nodeId but not the current IP.
     */
    private final ConcurrentHashMap<String, Integer> nodeRssiMap = new ConcurrentHashMap<>();

    /**
     * EMA smoother state, keyed by IP or nodeId (same namespace — callers
     * use distinct keys so there is no collision).
     */
    private final ConcurrentHashMap<String, Double> rssiSmoothed = new ConcurrentHashMap<>();
    private static final double RSSI_EMA_ALPHA = 0.3;

    /** Fallback when no measurement exists yet. */
    private static final int RSSI_UNKNOWN = -80;

    /**
     * Peers for which we have at least one real RTT-derived measurement.
     * Used only for debug logging so we can confirm Source 1 is firing.
     */
    private final Set<String> rssiMeasuredIps = ConcurrentHashMap.newKeySet();

    // =========================================================================
    // RSSI helpers
    // =========================================================================

    /**
     * Apply EMA smoothing to a new raw RSSI sample and store the result.
     *
     * @param key    IP address or nodeId — used as the smoothing state key
     * @param rawDbm new raw sample in dBm
     * @return smoothed value in dBm
     */
    private int applyEma(String key, int rawDbm) {
        double raw      = rawDbm;
        double smoothed = rssiSmoothed.containsKey(key)
                ? RSSI_EMA_ALPHA * raw + (1 - RSSI_EMA_ALPHA) * rssiSmoothed.get(key)
                : raw;
        rssiSmoothed.put(key, smoothed);
        return (int) Math.round(smoothed);
    }

    /**
     * Convert a TCP round-trip time (ms) to a synthetic dBm value.
     *
     * The mapping is deliberately coarse — it is not pretending to be a real
     * radio measurement, just a monotonically-decreasing proxy that lets the
     * routing engine prefer low-latency (closer) neighbors.
     *
     *   RTT  <  30 ms  → -55 dBm  (excellent, same subnet / very close)
     *   RTT  <  80 ms  → -65 dBm  (good)
     *   RTT  < 200 ms  → -72 dBm  (fair)
     *   RTT  < 500 ms  → -78 dBm  (poor)
     *   RTT >= 500 ms  → -85 dBm  (marginal — just above RSSI_UNKNOWN so
     *                               the neighbor still participates in routing
     *                               but ranks last)
     */
    private static int rttToRssi(long rttMs) {
        if (rttMs <  30) return -55;
        if (rttMs <  80) return -65;
        if (rttMs < 200) return -72;
        if (rttMs < 500) return -78;
        return -85;
    }

    /**
     * Record a TCP RTT measurement for a peer identified by IP.
     * Called by sendViaTcp() after each successful send.
     * Also cross-references to nodeRssiMap via nodeIpMap.
     */
    private void recordTcpRtt(String ip, long rttMs) {
        if (ip == null) return;
        int synthetic = rttToRssi(rttMs);
        int smoothed  = applyEma(ip, synthetic);
        ipRssiMap.put(ip, smoothed);
        rssiMeasuredIps.add(ip);

        // Cross-reference: if we know which nodeId owns this IP, also update nodeRssiMap.
        for (Map.Entry<String, String> e : nodeIpMap.entrySet()) {
            if (ip.equals(sanitizeIp(e.getValue()))) {
                nodeRssiMap.put(e.getKey(), smoothed);
                break;
            }
        }
        Log.d(TAG, "RTT RSSI: ip=" + ip + " rtt=" + rttMs + "ms → synthetic=" + synthetic
                + " smoothed=" + smoothed);
    }

    /**
     * Record a one-way HELLO latency measurement for a peer identified by nodeId.
     * Called from handleHello() when the HELLO packet carries a timestamp.
     * Feeds the same rttToRssi() table (one-way ≈ RTT/2, so we double it
     * before mapping so the same table applies).
     */
    private boolean recordHelloLatency(String nodeId, long sentAtMs) {
        if (nodeId == null || sentAtMs <= 0) return false;
        long now       = System.currentTimeMillis();
        long oneWayMs  = now - sentAtMs;
        if (oneWayMs < 0 || oneWayMs > 60_000) return false; // reject bad/stale clocks
        long rttEquiv  = oneWayMs * 2;                  // treat as half-RTT
        int synthetic  = rttToRssi(rttEquiv);
        if (synthetic <= RoutingEngine.RSSI_THRESHOLD_DBM) {
            Log.d(TAG, "HELLO latency RSSI ignored: nodeId=" + nodeId
                    + " oneWay=" + oneWayMs + "ms synthetic=" + synthetic);
            return false;
        }
        int smoothed   = applyEma(nodeId, synthetic);
        nodeRssiMap.put(nodeId, smoothed);

        // Cross-reference: also update ipRssiMap if we know this node's IP.
        String ip = sanitizeIp(nodeIpMap.get(nodeId));
        if (ip != null) {
            ipRssiMap.put(ip, smoothed);
            rssiMeasuredIps.add(ip);
        }
        Log.d(TAG, "HELLO latency RSSI: nodeId=" + nodeId + " oneWay=" + oneWayMs
                + "ms → synthetic=" + synthetic + " smoothed=" + smoothed);
        return true;
    }

    /**
     * Record a real Bluetooth RSSI for a peer identified by BT MAC.
     * Cross-references to nodeRssiMap and ipRssiMap via nodeMacMap.
     */
    private void recordBtRssi(String btMac, int rawDbm) {
        if (btMac == null) return;
        String key     = "bt:" + btMac.toUpperCase(Locale.US);
        int smoothed   = applyEma(key, rawDbm);

        // Cross-reference to nodeId (nodeMacMap: nodeId → MAC).
        for (Map.Entry<String, String> e : nodeMacMap.entrySet()) {
            if (btMac.equalsIgnoreCase(e.getValue())) {
                String nodeId = e.getKey();
                nodeRssiMap.put(nodeId, smoothed);
                String ip = sanitizeIp(nodeIpMap.get(nodeId));
                if (ip != null) {
                    ipRssiMap.put(ip, smoothed);
                    rssiMeasuredIps.add(ip);
                }
                Log.d(TAG, "BT RSSI: mac=" + btMac + " raw=" + rawDbm
                        + " smoothed=" + smoothed + " → nodeId=" + nodeId);
                return;
            }
        }
        Log.d(TAG, "BT RSSI: mac=" + btMac + " raw=" + rawDbm
                + " (no nodeId mapping yet — stored under bt: key only)");
    }

    /**
     * Look up the best available RSSI estimate for a peer identified by IP.
     * Resolution order: ipRssiMap (RTT-derived) → nodeRssiMap (HELLO latency).
     * Never falls back to MAC lookup — that chain is broken for P2P peers.
     */
    private int resolveRssiForIp(String ip) {
        if (ip == null) return RSSI_UNKNOWN;
        String cleanIp = sanitizeIp(ip);
        if (cleanIp == null) return RSSI_UNKNOWN;

        // Source 1: direct IP entry (populated by TCP RTT or BT cross-ref).
        Integer byIp = ipRssiMap.get(cleanIp);
        if (byIp != null) return byIp;

        // Source 2: nodeId entry (populated by HELLO latency).
        for (Map.Entry<String, String> e : nodeIpMap.entrySet()) {
            if (cleanIp.equals(sanitizeIp(e.getValue()))) {
                Integer byNode = nodeRssiMap.get(e.getKey());
                if (byNode != null) return byNode;
                break;
            }
        }
        return RSSI_UNKNOWN;
    }

    /**
     * Look up the best available RSSI estimate for a peer identified by nodeId.
     */
    private int resolveRssiForNode(String nodeId) {
        if (nodeId == null) return RSSI_UNKNOWN;
        Integer byNode = nodeRssiMap.get(nodeId);
        if (byNode != null) return byNode;
        String ip = sanitizeIp(nodeIpMap.get(nodeId));
        if (ip != null) {
            Integer byIp = ipRssiMap.get(ip);
            if (byIp != null) return byIp;
        }
        return RSSI_UNKNOWN;
    }

    /**
     * Look up RSSI for a BT peer by its MAC address.
     * Used in handleBtSocket() — falls back to nodeRssiMap via nodeMacMap.
     */
    private int resolveRssiForMac(String mac) {
        if (mac == null) return RSSI_UNKNOWN;
        // Check bt:-prefixed EMA key first.
        String key = "bt:" + mac.toUpperCase(Locale.US);
        Double smoothed = rssiSmoothed.get(key);
        if (smoothed != null) return (int) Math.round(smoothed);
        // Fall back to nodeRssiMap via nodeMacMap.
        for (Map.Entry<String, String> e : nodeMacMap.entrySet()) {
            if (mac.equalsIgnoreCase(e.getValue())) {
                Integer byNode = nodeRssiMap.get(e.getKey());
                if (byNode != null) return byNode;
                break;
            }
        }
        return RSSI_UNKNOWN;
    }

    /**
     * Wi-Fi scan results still populate rssiSmoothed for completeness —
     * they will match real hardware MACs (BT peers, infrastructure APs)
     * but NOT P2P virtual MACs. Kept because BT peers do appear here.
     */
    private void refreshPeerRssi() {
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) return;
            List<ScanResult> results = wm.getScanResults();
            if (results == null || results.isEmpty()) return;
            for (ScanResult r : results) {
                if (r.BSSID == null) continue;
                // Store under the raw MAC — only useful if a BT peer's MAC
                // happens to appear here; P2P virtual MACs will not match.
                applyEma(r.BSSID.toUpperCase(Locale.US), r.level);
            }
        } catch (Exception e) {
            Log.w(TAG, "refreshPeerRssi error: " + e.getMessage());
        }
    }

    /**
     * Convert smoothed RSSI to an estimated distance in metres using the
     * log-distance path-loss model.
     * txPower = expected RSSI at 1 metre (-59 dBm for Android-to-Android).
     * n = path-loss exponent (2.0 free space, 3.0 indoors, 4.0 heavy obstacles).
     */
    public static double rssiToDistance(int rssi, int txPower, double n) {
        if (rssi == 0) return -1;
        return Math.pow(10.0, (txPower - rssi) / (10.0 * n));
    }

    /** Convenience overload with sensible Android indoor defaults. */
    public static double rssiToDistance(int rssi) {
        return rssiToDistance(rssi, -59, 3.0);
    }

    // =========================================================================
    // Group client count tracking (multi-group mesh)
    // =========================================================================

    /**
     * Request the current group's client list and update currentGroupClientCount.
     * If the group is full, triggers discovery so a new group can form.
     * Called inside wifiP2pInfoListener after amGroupOwner is set.
     */
    private void updateGroupClientCount() {
        if (!amGroupOwner) return;

        if (wifiP2pManager == null || wifiChannel == null) {
            Log.e(TAG, "WiFiP2pManager or Channel is null");
            return;
        }

        // Proper permission check
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {

            Log.e(TAG, "Required permissions not granted");
            return;
        }

        try {
            wifiP2pManager.requestGroupInfo(wifiChannel, group -> {
                if (group == null) {
                    currentGroupClientCount = 0;
                    Log.w(TAG, "Group is null");
                    return;
                }

                Collection<WifiP2pDevice> clients = group.getClientList();
                currentGroupClientCount = (clients != null) ? clients.size() : 0;

                Log.i(TAG, "GO client count updated: " + currentGroupClientCount);

                if (currentGroupClientCount >= MAX_GROUP_CLIENTS) {
                    Log.i(TAG, "Group full — scheduling new discovery");

                    handler.removeCallbacksAndMessages(null);

                    handler.postDelayed(() -> {
                        try {
                            discoverPeers();
                        } catch (SecurityException e) {
                            Log.e(TAG, "SecurityException during discoverPeers()", e);
                        }
                    }, 5000);
                }
            });

        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in requestGroupInfo()", e);
        }
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public void onCreate() {
        super.onCreate();

        boolean isDebug = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (isDebug) {
            android.os.StrictMode.setThreadPolicy(
                    new android.os.StrictMode.ThreadPolicy.Builder()
                            .detectNetwork().penaltyLog().build());
        }

        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, buildForegroundNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, buildForegroundNotification());
        }

        myNodeId = "NODE-" + Build.MODEL.replaceAll("\\s+", "").toUpperCase();
        Log.i(TAG, "MeshService starting as " + myNodeId);

        MeshDatabase db = MeshDatabase.getInstance(this);
        messageStore = new MessageStore(this);
        receivedDao  = db.receivedMessageDao();

        broadcastMgr = new BroadcastManager(new BroadcastManager.BroadcastListener() {
            @Override public void onBroadcastReceived(BroadcastPacket pkt) {}
            @Override public void sendToNeighbor(NeighborNode neighbor, String json) {
                sendPacketToNode(neighbor, json);
            }
        });

        initWifiDirect();
        initBluetooth();
        initLocation();
        initBattery();
        startHelloLoop();
        startDbPruneLoop();
        startDtnRetryLoop();
        startWifiServer();
        startBluetoothServer();
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;
        switch (intent.getAction()) {
            case "SEND": {
                String dest    = intent.getStringExtra("dest");
                String payload = intent.getStringExtra("payload");
                if (dest != null && payload != null) sendMessage(dest, payload);
                break;
            }
            case "BROADCAST": {
                String cat    = intent.getStringExtra("category");
                String msg    = intent.getStringExtra("message");
                String sender = intent.getStringExtra("senderName");
                if (cat != null && msg != null)
                    sendMeshBroadcast(cat, msg, sender != null ? sender : myNodeId);
                break;
            }
            case "SIMULATE_HELLO": {
                String nodeId  = intent.getStringExtra("nodeId");
                double lat     = intent.getDoubleExtra("lat", 0);
                double lon     = intent.getDoubleExtra("lon", 0);
                int    battery = intent.getIntExtra("battery", 75);
                // Default rssi uses RSSI_UNKNOWN (-80), not -55, so simulated
                // peers don't appear as perfectly-connected neighbours.
                int    rssi    = intent.getIntExtra("rssi", RSSI_UNKNOWN);
                if (nodeId != null) {
                    HelloPacket fake = new HelloPacket(nodeId, lat, lon, battery);
                    neighborTable.onHelloReceived(fake, rssi);
                    sendBroadcast(new Intent(ACTION_PEERS)
                            .putExtra(EXTRA_PAYLOAD,
                                    neighborTable.size() + " neighbors (includes simulated)"));
                }
                break;
            }
            case "DISCOVER":
                discoverPeers();
                break;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        dbExecutor.shutdown();
        try { if (wifiServerSocket != null) wifiServerSocket.close(); } catch (IOException ignored) {}
        try { if (btServerSocket   != null) btServerSocket.close();   } catch (IOException ignored) {}
        try { unregisterReceiver(wifiP2pReceiver);     } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(btDiscoveryReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(batteryReceiver);     } catch (IllegalArgumentException ignored) {}
    }

    // =========================================================================
    // Foreground notification
    // =========================================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "MeshComm Service", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps MeshComm running for mesh networking");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildForegroundNotification() {
        PendingIntent openApp = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MeshComm Active")
                .setContentText("Listening for nearby mesh devices...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // =========================================================================
    // GO election — multi-group mesh aware
    // =========================================================================

    /**
     * Dynamic GO intent based on current group load.
     * If we are already the GO and our group is full (>= MAX_GROUP_CLIENTS),
     * we yield the GO role to the connecting device so it starts a new group.
     * This creates a multi-group mesh instead of a single star with a 3-device cap.
     */
    private int goIntent(WifiP2pDevice remote) {
        // If our group is full, let the other device become GO of a new group.
        if (amGroupOwner && currentGroupClientCount >= MAX_GROUP_CLIENTS) {
            Log.i(TAG, "GO intent: yielding (group full, count=" + currentGroupClientCount + ")");
            return 0;
        }
        String remoteName = (remote.deviceName != null && !remote.deviceName.isEmpty())
                ? remote.deviceName : remote.deviceAddress;
        int cmp = myNodeId.compareTo(remoteName);
        return cmp >= 0 ? 15 : 0;
    }

    // =========================================================================
    // Wi-Fi Direct
    // =========================================================================

    private void initWifiDirect() {
        wifiP2pManager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (wifiP2pManager == null) { Log.w(TAG, "Wi-Fi Direct not available"); return; }

        wifiChannel = wifiP2pManager.initialize(this, getMainLooper(), () -> {
            Log.w(TAG, "Wi-Fi Direct channel lost — reinitializing");
            isDiscovering = false;
            wifiChannel = wifiP2pManager.initialize(MeshService.this, getMainLooper(), null);
            handler.postDelayed(this::discoverPeers, 3_000);
        });

        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        registerReceiver(wifiP2pReceiver, f);

        removeExistingGroupThenDiscover();
    }

    private void removeExistingGroupThenDiscover() {
        if (wifiP2pManager == null || wifiChannel == null) {
            handler.postDelayed(this::discoverPeers, 2_000);
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        wifiP2pManager.requestGroupInfo(wifiChannel, group -> {
            if (group == null) {
                Log.d(TAG, "No existing P2P group — starting discovery");
                handler.postDelayed(MeshService.this::discoverPeers, 2_000);
            } else {
                Log.w(TAG, "Stale P2P group found: " + group.getNetworkName() + " — removing");
                wifiP2pManager.removeGroup(wifiChannel, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() {
                        Log.i(TAG, "Stale group removed — starting discovery");
                        isDiscovering = false;
                        amGroupOwner  = false;
                        currentGroupClientCount = 0;
                        handler.postDelayed(MeshService.this::discoverPeers, 2_000);
                    }
                    @Override public void onFailure(int reason) {
                        Log.w(TAG, "removeGroup failed reason=" + reason + " — discovering anyway");
                        isDiscovering = false;
                        handler.postDelayed(MeshService.this::discoverPeers, 2_000);
                    }
                });
            }
        });
    }

    private static String sanitizeIp(String raw) {
        if (raw == null) return null;
        String ip = raw.trim();
        if (ip.startsWith("/")) ip = ip.substring(1);
        int z = ip.indexOf('%');
        if (z != -1) ip = ip.substring(0, z);
        return ip.isEmpty() ? null : ip;
    }

    private void discoverPeers() {
        if (wifiP2pManager == null || wifiChannel == null) return;
        long now = System.currentTimeMillis();
        if (isDiscovering && (now - lastDiscoverTime) < DISCOVER_COOLDOWN_MS) {
            Log.d(TAG, "discoverPeers: cooldown — "
                    + (DISCOVER_COOLDOWN_MS - (now - lastDiscoverTime)) / 1000 + "s remaining");
            return;
        }
        boolean hasPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED
                : ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!hasPerm) {
            Log.w(TAG, "discoverPeers: permission not granted");
            return;
        }
        wifiP2pManager.stopPeerDiscovery(wifiChannel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess()      { startFreshDiscovery(); }
            @Override public void onFailure(int r) { startFreshDiscovery(); }
        });
    }

    private boolean hasWifiDirectPermission(Context ctx) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? ActivityCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED
                : ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCurrentPeers(String reason) {
        if (wifiP2pManager == null || wifiChannel == null) return;
        if (!hasWifiDirectPermission(this)) {
            Log.w(TAG, "requestPeers skipped (" + reason + "): permission not granted");
            return;
        }
        try {
            wifiP2pManager.requestPeers(wifiChannel, peers -> {
                Log.d(TAG, "requestPeers result (" + reason + ")");
                peerListListener.onPeersAvailable(peers);
            });
        } catch (SecurityException e) {
            Log.e(TAG, "requestPeers denied (" + reason + "): " + e.getMessage());
        }
    }

    private final Runnable discoverRunnable = () -> {
        isDiscovering = false;
        discoverPeers();
    };

    private void startFreshDiscovery() {
        if (wifiP2pManager == null || wifiChannel == null) return;
        isDiscovering    = true;
        lastDiscoverTime = System.currentTimeMillis();
        try {
            wifiP2pManager.discoverPeers(wifiChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    Log.i(TAG, "discoverPeers: scan started OK");
                    requestCurrentPeers("scan-start");
                    handler.postDelayed(() -> requestCurrentPeers("scan-3s"), 3_000);
                    handler.postDelayed(() -> requestCurrentPeers("scan-7s"), 7_000);
                    handler.removeCallbacks(discoverRunnable);
                    handler.postDelayed(discoverRunnable, DISCOVER_COOLDOWN_MS);
                }
                @Override public void onFailure(int reason) {
                    isDiscovering = false;
                    Log.w(TAG, "discoverPeers: failed reason=" + reason);
                    handler.removeCallbacks(discoverRunnable);
                    handler.postDelayed(discoverRunnable, reason == 2 ? 20_000 : 15_000);
                }
            });
        } catch (SecurityException e) {
            isDiscovering = false;
            Log.e(TAG, "discoverPeers: SecurityException — " + e.getMessage());
        }
    }

    private final BroadcastReceiver wifiP2pReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            switch (intent.getAction()) {
                case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION: {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.i(TAG, "Wi-Fi Direct state: enabled");
                        isDiscovering = false;
                        handler.postDelayed(MeshService.this::discoverPeers, 1_000);
                    } else {
                        Log.w(TAG, "Wi-Fi Direct state: disabled");
                        isDiscovering = false;
                    }
                    break;
                }
                case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION: {
                    requestCurrentPeers("broadcast-peers-changed");
                    break;
                }
                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION: {
                    if (wifiP2pManager == null || wifiChannel == null) return;
                    android.net.NetworkInfo netInfo =
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                    if (netInfo != null && netInfo.isConnected()) {
                        boolean ok = hasWifiDirectPermission(ctx);
                        if (ok) wifiP2pManager.requestConnectionInfo(wifiChannel, wifiP2pInfoListener);
                    }
                    break;
                }
            }
        }
    };

    @SuppressLint("MissingPermission")
    private final WifiP2pManager.PeerListListener peerListListener = peers -> {
        List<WifiP2pDevice> devices = new ArrayList<>(peers.getDeviceList());
        Log.i(TAG, "Wi-Fi peers found: " + devices.size());

        wifiPeerMap.clear();
        ArrayList<String> peerNames = new ArrayList<>();
        for (WifiP2pDevice d : devices) {
            wifiPeerMap.put(d.deviceAddress, d);
            peerNames.add(d.deviceName != null && !d.deviceName.isEmpty()
                    ? d.deviceName : d.deviceAddress);
            Log.i(TAG, "Peer: " + peerNames.get(peerNames.size() - 1)
                    + " [" + d.deviceAddress + "]");
        }

        // refreshPeerRssi() reads Wi-Fi scan results. These will NOT match
        // P2P virtual MACs but do capture BT peer hardware MACs when present.
        refreshPeerRssi();

        sendBroadcast(new Intent(ACTION_PEERS)
                .putExtra(EXTRA_PAYLOAD, devices.size() + " Wi-Fi Direct peers")
                .putStringArrayListExtra("peerNames", peerNames));

        long now = System.currentTimeMillis();
        if ((now - lastConnectAttempt) > CONNECT_COOLDOWN_MS) {
            for (WifiP2pDevice device : devices) {
                String knownNodeId = macNodeMap.get(device.deviceAddress);
                boolean alreadyMapped = knownNodeId != null && nodeIpMap.containsKey(knownNodeId);
                if (!alreadyMapped && !pendingConnectQueue.contains(device)) {
                    pendingConnectQueue.add(device);
                    Log.d(TAG, "Queued for connect: " + device.deviceAddress);
                }
            }
            drainConnectQueue();
        }
    };

    private void drainConnectQueue() {
        if (wifiConnectInProgress) return;
        WifiP2pDevice next = pendingConnectQueue.poll();
        if (next == null) return;
        wifiConnectInProgress = true;
        lastConnectAttempt    = System.currentTimeMillis();
        connectToWifiPeer(next);
    }

    @SuppressLint("MissingPermission")
    private void connectToWifiPeer(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress    = device.deviceAddress;
        config.groupOwnerIntent = goIntent(device);
        Log.i(TAG, "Connecting to " + device.deviceAddress
                + " goIntent=" + config.groupOwnerIntent);

        wifiP2pManager.connect(wifiChannel, config, new WifiP2pManager.ActionListener() {
            @Override
            @SuppressLint("MissingPermission")
            public void onSuccess() {
                handler.postDelayed(() -> {
                    if (wifiP2pManager != null && wifiChannel != null)
                        wifiP2pManager.requestConnectionInfo(wifiChannel, wifiP2pInfoListener);
                }, 5_000);
            }
            @Override public void onFailure(int reason) {
                Log.w(TAG, "Connect failed reason=" + reason + " for " + device.deviceAddress);
                wifiConnectInProgress = false;
                handler.postDelayed(() -> drainConnectQueue(),
                        reason == 2 ? 20_000 : 10_000);
            }
        });
    }

    private void sendBootstrapHello(String ip, int retries) {
        new Thread(() -> {
            for (int i = 0; i < retries; i++) {
                try {
                    Thread.sleep(2_000);
                    String json = buildHelloJson();
                    Socket s = new Socket();
                    long t0 = System.currentTimeMillis();
                    s.connect(new InetSocketAddress(ip, WIFI_PORT), 3_000);
                    new PrintWriter(s.getOutputStream(), true).println(json);
                    recordTcpRtt(ip, System.currentTimeMillis() - t0);
                    s.close();
                    Log.i(TAG, "Bootstrap HELLO sent to " + ip);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Bootstrap retry " + (i + 1) + ": " + e.getMessage());
                }
            }
        }, "Bootstrap-Hello").start();
    }

    private final WifiP2pManager.ConnectionInfoListener wifiP2pInfoListener = info -> {
        if (info == null || info.groupOwnerAddress == null) {
            wifiConnectInProgress = false;
            drainConnectQueue();
            return;
        }

        String ownerIp = sanitizeIp(info.groupOwnerAddress.getHostAddress());
        amGroupOwner   = info.isGroupOwner;

        // Update how many clients are in our group so goIntent() can yield
        // when we're full, enabling multi-group mesh formation.
        updateGroupClientCount();

        Log.i(TAG, "P2P group ownerIp=" + ownerIp
                + " isOwner=" + amGroupOwner
                + " myNodeId=" + myNodeId);

        wifiConnectInProgress = false;

        if (!amGroupOwner && ownerIp != null) {
            sendBootstrapHello(ownerIp, 5);
        } else if (amGroupOwner) {
            Log.i(TAG, "We are the hub (GO). Waiting for client HELLOs on port " + WIFI_PORT);
        }

        handler.postDelayed(this::drainConnectQueue, 2_000);
    };

    // =========================================================================
    // Wi-Fi TCP Server
    // =========================================================================

    private void startWifiServer() {
        new Thread(() -> {
            try {
                wifiServerSocket = new ServerSocket(WIFI_PORT);
                Log.i(TAG, "TCP server listening on port " + WIFI_PORT);
                while (!wifiServerSocket.isClosed()) {
                    Socket client = wifiServerSocket.accept();
                    String clientIp = sanitizeIp(client.getInetAddress() != null
                            ? client.getInetAddress().getHostAddress() : null);
                    Log.d(TAG, "Incoming TCP connection from " + clientIp);
                    handleIncomingSocket(client, clientIp);
                }
            } catch (IOException e) {
                if (wifiServerSocket != null && !wifiServerSocket.isClosed())
                    Log.e(TAG, "Wi-Fi server error", e);
            }
        }, "WiFi-Server").start();
    }

    private void handleIncomingSocket(Socket socket, String senderIp) {
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // Resolve real RSSI via IP→nodeId→MAC chain instead of hardcoded -55.
                    int rssi = resolveRssiForIp(senderIp);
                    processRawPacket(line, senderIp, rssi);
                }
            } catch (IOException e) {
                Log.w(TAG, "Socket error from " + senderIp + ": " + e.getMessage());
            }
        }, "WiFi-Read-" + senderIp).start();
    }

    // =========================================================================
    // Bluetooth
    // =========================================================================

    private boolean hasBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) return;
        registerReceiver(btDiscoveryReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
        if (bluetoothAdapter.isEnabled() && hasBtPermissions()) {
            try { bluetoothAdapter.startDiscovery(); }
            catch (SecurityException e) { Log.w(TAG, "BT discovery denied"); }
        }
    }

    private final BroadcastReceiver btDiscoveryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev == null) return;
            String name = null;
            try { name = dev.getName(); } catch (SecurityException ignored) {}
            if (name != null && name.startsWith("NODE-")) {
                nodeMacMap.put(name, dev.getAddress());
                Log.i(TAG, "BT mapped: " + name + " -> " + dev.getAddress());

                // Source 3: real BT RSSI from ACTION_FOUND EXTRA_RSSI.
                // recordBtRssi() applies EMA and cross-references to nodeRssiMap/ipRssiMap
                // via nodeMacMap so the value is available to the routing engine.
                short btRssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) RSSI_UNKNOWN);
                if (btRssi != (short) RSSI_UNKNOWN) {
                    recordBtRssi(dev.getAddress(), btRssi);
                }
            }
        }
    };

    private void startBluetoothServer() {
        if (bluetoothAdapter == null || !hasBtPermissions()) return;
        new Thread(() -> {
            try {
                btServerSocket = bluetoothAdapter
                        .listenUsingRfcommWithServiceRecord("MeshComm", BT_UUID);
                Log.i(TAG, "BT RFCOMM server listening");
                while (true) {
                    BluetoothSocket s = btServerSocket.accept();
                    if (s != null) {
                        String mac = null;
                        try { mac = s.getRemoteDevice().getAddress(); }
                        catch (SecurityException ignored) {}
                        handleBtSocket(s, mac);
                    }
                }
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "BT server stopped: " + e.getMessage());
            }
        }, "BT-Server").start();
    }

    private void handleBtSocket(BluetoothSocket socket, String mac) {
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // Resolve real RSSI from BT discovery (stored in nodeRssiMap/ipRssiMap)
                    // instead of the old hardcoded -70.
                    int rssi = resolveRssiForMac(mac);
                    processRawPacket(line, null, rssi);
                }
            } catch (IOException e) {
                Log.w(TAG, "BT read error: " + e.getMessage());
            }
        }, "BT-Read-" + (mac != null ? mac : "?")).start();
    }

    // =========================================================================
    // Packet dispatcher
    // =========================================================================

    private void processRawPacket(String json, String senderIp, int rssi) {
        try {
            JSONObject obj = new JSONObject(json);
            String type = obj.has("type") ? obj.getString("type")
                    : obj.has("packetType") ? obj.getString("packetType") : null;
            if (type == null) { Log.e(TAG, "No type in packet"); return; }

            switch (type) {
                case "HELLO":
                    handleHello(obj, senderIp, rssi);
                    break;
                case "MSG":
                case "MESSAGE": {
                    MessagePacket pkt = MessagePacket.fromJson(obj);
                    String previousHop = pkt.lastHopNodeId;
                    if (previousHop == null && senderIp != null) {
                        previousHop = resolveNodeId(senderIp);
                    }
                    if (previousHop != null) prevHopMap.put(pkt.messageId, previousHop);
                    onMessageReceived(pkt);
                    break;
                }
                case "ACK":
                    onAckReceived(AckPacket.fromJson(obj));
                    break;
                case "BROADCAST":
                    onBroadcastPacketReceived(BroadcastPacket.fromJson(obj), senderIp);
                    break;
                default:
                    Log.w(TAG, "Unknown packet type: " + type);
            }
        } catch (Exception e) {
            Log.e(TAG, "Packet parse error: " + e.getMessage());
        }
    }

    private String resolveNodeId(String ip) {
        for (Map.Entry<String, String> e : nodeIpMap.entrySet())
            if (e.getValue().equals(ip)) return e.getKey();
        return ip;
    }

    // =========================================================================
    // HELLO
    // =========================================================================

    private String buildHelloJson() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("packetType",   "HELLO");
        obj.put("nodeId",       myNodeId);
        obj.put("latitude",     myLat);
        obj.put("longitude",    myLon);
        obj.put("batteryLevel", myBattery);
        obj.put("timestamp",    System.currentTimeMillis());

        JSONArray neighborArray = new JSONArray();
        for (NeighborNode n : neighborTable.asList()) neighborArray.put(n.nodeId);
        obj.put("neighbors", neighborArray);

        JSONObject topoObj = new JSONObject();
        for (Map.Entry<String, Set<String>> e : topologyMap.entrySet()) {
            topoObj.put(e.getKey(), new JSONArray(new ArrayList<>(e.getValue())));
        }
        obj.put("topology", topoObj);

        return obj.toString();
    }

    private void handleHello(JSONObject obj, String senderIp, int rssi) throws Exception {
        HelloPacket hello = HelloPacket.fromJson(obj);
        if (hello.nodeId.equals(myNodeId)) return;

        if (senderIp != null) {
            String cleanIp = sanitizeIp(senderIp);
            if (cleanIp != null) {
                String prev = nodeIpMap.put(hello.nodeId, cleanIp);
                if (!cleanIp.equals(prev))
                    Log.i(TAG, "IP mapped: " + hello.nodeId + " -> " + cleanIp);
            }
        }

        // Source 2: use the HELLO timestamp to derive a latency-based RSSI
        // estimate. This fires on every HELLO regardless of MAC resolution,
        // so nodeRssiMap is populated well before the first outbound TCP send.
        long sentAt = obj.optLong("timestamp", 0L);
        boolean latencyRecorded = recordHelloLatency(hello.nodeId, sentAt);

        // Resolve the best available RSSI estimate and pass it to the neighbor
        // table. On the first HELLO there may be no TCP RTT yet, but
        // recordHelloLatency() above will have populated nodeRssiMap so we get
        // a real latency-derived value rather than RSSI_UNKNOWN (-80).
        int rssiEst = resolveRssiForNode(hello.nodeId);
        Integer ipRssi = senderIp != null ? ipRssiMap.get(sanitizeIp(senderIp)) : null;
        if (ipRssi != null && ipRssi > rssiEst) {
            rssiEst = ipRssi;
            nodeRssiMap.put(hello.nodeId, rssiEst);
        }
        if (rssiEst == RSSI_UNKNOWN && rssi != RSSI_UNKNOWN) {
            rssiEst = rssi;
            nodeRssiMap.put(hello.nodeId, rssiEst);
        }
        if (rssiEst <= RoutingEngine.RSSI_THRESHOLD_DBM && senderIp != null) {
            Integer freshIpRssi = ipRssiMap.get(sanitizeIp(senderIp));
            if (freshIpRssi != null) {
                rssiEst = freshIpRssi;
                nodeRssiMap.put(hello.nodeId, rssiEst);
            } else {
                rssiEst = -69;
            }
        } else if (rssiEst == RSSI_UNKNOWN && senderIp == null) {
            rssiEst = -65;
            nodeRssiMap.put(hello.nodeId, rssiEst);
        }
        neighborTable.onHelloReceived(hello, rssiEst);
        Log.i(TAG, "HELLO from " + hello.nodeId
                + " rssiEst=" + rssiEst + " bat=" + hello.batteryLevel + "%"
                + " senderIp=" + senderIp);

        if (obj.has("neighbors")) {
            JSONArray arr = obj.getJSONArray("neighbors");
            Set<String> senderNeighbors = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < arr.length(); i++) {
                String nId = arr.getString(i);
                if (!nId.equals(myNodeId)) senderNeighbors.add(nId);
            }
            topologyMap.put(hello.nodeId, senderNeighbors);
            Log.d(TAG, "Topology: " + hello.nodeId + " knows " + senderNeighbors);
        }

        if (obj.has("topology")) {
            JSONObject topo = obj.getJSONObject("topology");
            Iterator<String> keys = topo.keys();
            while (keys.hasNext()) {
                String nId = keys.next();
                if (nId.equals(myNodeId)) continue;
                JSONArray arr = topo.getJSONArray(nId);
                Set<String> set = ConcurrentHashMap.newKeySet();
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.getString(i);
                    if (!id.equals(myNodeId)) set.add(id);
                }
                topologyMap.merge(nId, set, (existing, incoming) -> {
                    existing.addAll(incoming);
                    return existing;
                });
            }
            Log.d(TAG, "Topology after merge: " + topologyMap.keySet());
        }

        wifiConnectInProgress = false;
        replyHelloTo(senderIp);
        sendBroadcast(new Intent(ACTION_PEERS));
        retryStoredMessages();
    }

    private void replyHelloTo(String ip) {
        if (ip == null) return;
        String cleanIp = sanitizeIp(ip);
        if (cleanIp == null) return;
        long now = System.currentTimeMillis();
        Long lastReply = helloReplyAt.get(cleanIp);
        if (lastReply != null && now - lastReply < HELLO_REPLY_COOLDOWN_MS) {
            Log.d(TAG, "HELLO reply skipped to " + cleanIp + " (cooldown)");
            return;
        }
        helloReplyAt.put(cleanIp, now);
        new Thread(() -> {
            try {
                String json = buildHelloJson();
                Socket s = new Socket();
                s.connect(new InetSocketAddress(cleanIp, WIFI_PORT), 2_000);
                new PrintWriter(s.getOutputStream(), true).println(json);
                s.close();
                Log.d(TAG, "HELLO reply sent to " + cleanIp);
            } catch (Exception e) {
                Log.w(TAG, "HELLO reply failed to " + cleanIp + ": " + e.getMessage());
            }
        }, "Hello-Reply-" + cleanIp).start();
    }

    // =========================================================================
    // Next-hop selection
    // =========================================================================

    private NeighborNode selectBestNextHop(MessagePacket packet) {
        String destNodeId = packet.destinationNodeId;
        List<NeighborNode> neighbors = neighborTable.asList();
        if (neighbors.isEmpty()) return null;
        Set<String> blocked = visitedNodes(packet);

        for (NeighborNode n : neighbors) {
            if (n.nodeId.equals(destNodeId) && n.isAlive()) {
                Log.d(TAG, "nextHop: direct to " + destNodeId);
                return n;
            }
        }

        NeighborNode bestTopologyHop = null;
        for (NeighborNode n : neighbors) {
            if (isUsableRelay(n, blocked) && hasTopologyPath(n.nodeId, destNodeId, blocked)) {
                Log.d(TAG, "nextHop topology candidate: " + n.nodeId
                        + " path-to=" + destNodeId + " rssi=" + n.rssi);
                bestTopologyHop = strongerRssi(bestTopologyHop, n);
            }
        }
        if (bestTopologyHop != null) {
            Log.i(TAG, "nextHop: via topology " + bestTopologyHop.nodeId + " → " + destNodeId);
            return bestTopologyHop;
        }

        NeighborNode bestRelay = null;
        for (NeighborNode n : neighbors) {
            if (isUsableRelay(n, blocked)) bestRelay = strongerRssi(bestRelay, n);
        }
        if (bestRelay != null)
            Log.i(TAG, "nextHop: greedy relay " + bestRelay.nodeId
                    + " rssi=" + bestRelay.rssi);
        return bestRelay;
    }

    private NeighborNode selectBestNextHop(String destNodeId) {
        MessagePacket routeProbe = new MessagePacket();
        routeProbe.destinationNodeId = destNodeId;
        return selectBestNextHop(routeProbe);
    }

    private Set<String> visitedNodes(MessagePacket packet) {
        Set<String> blocked = new HashSet<>();
        blocked.add(myNodeId);
        if (packet.lastHopNodeId != null) blocked.add(packet.lastHopNodeId);
        if (packet.hopPath != null) blocked.addAll(packet.hopPath);
        blocked.remove(packet.destinationNodeId);
        return blocked;
    }

    private boolean isUsableRelay(NeighborNode n, Set<String> blocked) {
        return n != null
                && n.isAlive()
                && !blocked.contains(n.nodeId)
                && n.rssi > RoutingEngine.RSSI_THRESHOLD_DBM
                && n.batteryLevel > RoutingEngine.BATTERY_THRESHOLD_PCT;
    }

    private NeighborNode strongerRssi(NeighborNode current, NeighborNode candidate) {
        if (current == null) return candidate;
        if (candidate.rssi != current.rssi) {
            return candidate.rssi > current.rssi ? candidate : current;
        }
        return candidate.batteryLevel > current.batteryLevel ? candidate : current;
    }

    private boolean hasTopologyPath(String startNodeId, String destNodeId, Set<String> blocked) {
        if (startNodeId == null || destNodeId == null) return false;
        if (startNodeId.equals(destNodeId)) return true;

        Set<String> visited = new HashSet<>(blocked);
        visited.remove(startNodeId);
        visited.remove(destNodeId);

        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(startNodeId);
        visited.add(startNodeId);

        while (!queue.isEmpty()) {
            String nodeId = queue.removeFirst();
            Set<String> nextHops = topologyMap.get(nodeId);
            if (nextHops == null) continue;
            for (String next : nextHops) {
                if (destNodeId.equals(next)) return true;
                if (visited.contains(next)) continue;
                visited.add(next);
                queue.addLast(next);
            }
        }
        return false;
    }

    // =========================================================================
    // Unicast message handling
    // =========================================================================

    private void onMessageReceived(MessagePacket packet) {
        if (seenPackets.putIfAbsent(packet.messageId, System.currentTimeMillis()) != null) {
            Log.d(TAG, "Dropping duplicate: " + packet.messageId);
            return;
        }
        if (packet.ttl <= 0) {
            Log.w(TAG, "TTL expired: " + packet.messageId);
            return;
        }
        if (packet.destinationNodeId.equals(myNodeId)) {
            Log.i(TAG, "✓ DELIVERED " + packet.messageId
                    + " from=" + packet.sourceNodeId
                    + " via " + (packet.ttl < MAX_TTL ? (MAX_TTL - packet.ttl) + " hops" : "direct"));
            saveToInbox(packet.messageId, packet.sourceNodeId,
                    packet.sourceNodeId, packet.payload, "DIRECT", null);
            sendBroadcast(new Intent(ACTION_MSG_IN)
                    .putExtra(EXTRA_PAYLOAD, packet.payload)
                    .putExtra("from",        packet.sourceNodeId)
                    .putExtra("msgId",       packet.messageId));
            sendAck(packet);
        } else {
            forwardMessage(packet);
        }
    }

    private void forwardMessage(MessagePacket packet) {
        MessagePacket outbound = packet.copy();
        outbound.ttl--;
        if (outbound.ttl <= 0) {
            Log.w(TAG, "TTL exhausted, dropping " + packet.messageId);
            return;
        }
        NeighborNode hop = selectBestNextHop(packet);
        if (hop != null) {
            try {
                outbound.markForwardedBy(myNodeId);
                String json = outbound.toJson().toString();
                new Thread(() -> {
                    if (!sendPacketToNodeSync(hop, json)) {
                        Log.w(TAG, "Forward send failed, DTN storing: " + outbound.messageId);
                        messageStore.store(outbound);
                    }
                }, "Forward-" + outbound.messageId + "-" + hop.nodeId).start();
                Log.i(TAG, "→ Forwarded " + packet.messageId
                        + " src=" + packet.sourceNodeId
                        + " dest=" + packet.destinationNodeId
                        + " via=" + hop.nodeId
                        + " ttl=" + outbound.ttl);
            } catch (Exception e) {
                Log.w(TAG, "Forward failed, DTN storing: " + packet.messageId);
                messageStore.store(outbound);
            }
        } else {
            Log.d(TAG, "No hop for " + packet.messageId + " — DTN stored");
            messageStore.store(packet);
        }
    }

    private void retryStoredMessages() {
        new Thread(() -> {
            List<MessagePacket> pending = messageStore.getAll();
            if (pending.isEmpty()) return;
            for (MessagePacket p : pending) {
                NeighborNode hop = selectBestNextHop(p);
                if (hop != null) {
                    try {
                        MessagePacket outbound = p.copy();
                        outbound.ttl--;
                        if (outbound.ttl <= 0) continue;
                        outbound.markForwardedBy(myNodeId);
                        if (sendPacketToNodeSync(hop, outbound.toJson().toString())) {
                            messageStore.markDelivered(p.messageId);
                            Log.i(TAG, "DTN handed off: " + p.messageId + " -> " + hop.nodeId);
                        } else {
                            messageStore.store(outbound);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "DTN retry failed " + p.messageId + ": " + e.getMessage());
                    }
                }
            }
            messageStore.pruneExpired();
        }, "DTN-Retry").start();
    }

    // =========================================================================
    // ACK
    // =========================================================================

    private void onAckReceived(AckPacket ack) {
        if (ack.destinationNodeId.equals(myNodeId)) {
            Log.i(TAG, "✓ ACK DELIVERED for " + ack.messageId);
            messageStore.markDelivered(ack.messageId);
            sendBroadcast(new Intent(ACTION_ACK_IN)
                    .putExtra("msgId",  ack.messageId)
                    .putExtra("status", ack.status));
        } else {
            forwardAck(ack);
        }
    }

    private void sendAck(MessagePacket original) {
        try {
            AckPacket ack = new AckPacket(original.messageId, myNodeId, original.sourceNodeId);
            String    json = ack.toJson().toString();
            String prevId  = prevHopMap.get(original.messageId);
            NeighborNode hop = prevId != null ? neighborTable.get(prevId) : null;
            if (hop == null) hop = selectBestNextHop(original.sourceNodeId);
            if (hop != null) {
                sendPacketToNode(hop, json);
                Log.i(TAG, "← ACK sent " + original.messageId + " via " + hop.nodeId);
            }
            prevHopMap.remove(original.messageId);
        } catch (Exception e) {
            Log.e(TAG, "sendAck error: " + e.getMessage());
        }
    }

    private void forwardAck(AckPacket ack) {
        String prevId = prevHopMap.get(ack.messageId);
        NeighborNode hop = prevId != null ? neighborTable.get(prevId) : null;
        if (hop == null) hop = selectBestNextHop(ack.destinationNodeId);
        if (hop != null) {
            try {
                sendPacketToNode(hop, ack.toJson().toString());
                Log.i(TAG, "← ACK forwarded " + ack.messageId + " -> " + hop.nodeId);
            } catch (Exception e) {
                Log.w(TAG, "forwardAck failed: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Broadcast flood
    // =========================================================================

    private void onBroadcastPacketReceived(BroadcastPacket packet, String senderIp) {
        if (seenBroadcasts.putIfAbsent(packet.broadcastId, System.currentTimeMillis()) != null) return;
        Log.i(TAG, "BROADCAST rx: " + packet.broadcastId + " from=" + packet.sourceNodeId);
        saveToInbox(packet.broadcastId, packet.sourceNodeId,
                packet.senderName != null ? packet.senderName : packet.sourceNodeId,
                packet.payload, "BROADCAST", packet.category);
        sendBroadcast(new Intent(ACTION_BROADCAST)
                .putExtra(EXTRA_PAYLOAD,  packet.payload)
                .putExtra(EXTRA_CATEGORY, packet.category)
                .putExtra(EXTRA_SENDER,   packet.sourceNodeId));
        floodBroadcast(packet, senderIp != null ? resolveNodeId(senderIp) : packet.sourceNodeId);
    }

    public void sendMeshBroadcast(String category, String message, String senderName) {
        BroadcastPacket pkt = new BroadcastPacket(myNodeId, senderName, myLat, myLon, category, message);
        seenBroadcasts.put(pkt.broadcastId, System.currentTimeMillis());
        saveToInbox(pkt.broadcastId, myNodeId, senderName, message, "BROADCAST", category);
        Log.i(TAG, "Originating BROADCAST " + pkt.broadcastId);
        floodBroadcast(pkt, null);
    }

    private void floodBroadcast(BroadcastPacket pkt, String skipNodeId) {
        List<NeighborNode> neighbors = neighborTable.asList();
        String json;
        try { json = pkt.toJson().toString(); }
        catch (Exception e) { Log.e(TAG, "floodBroadcast serialize error"); return; }
        int sent = 0;
        for (NeighborNode n : neighbors) {
            if (n.nodeId.equals(skipNodeId)) continue;
            sendPacketToNode(n, json);
            sent++;
        }
        Log.i(TAG, "floodBroadcast: " + sent + "/" + neighbors.size() + " neighbors");
    }

    // =========================================================================
    // Inbox
    // =========================================================================

    private void saveToInbox(String messageId, String fromNodeId, String senderName,
                             String payload, String type, String broadcastCategory) {
        dbExecutor.execute(() -> {
            if (receivedDao.findById(messageId) != null) return;
            ReceivedMessageEntity e = new ReceivedMessageEntity();
            e.messageId         = messageId;
            e.fromNodeId        = fromNodeId;
            e.senderName        = senderName;
            e.payload           = payload;
            e.receivedAt        = System.currentTimeMillis();
            e.isRead            = false;
            e.type              = type;
            e.broadcastCategory = broadcastCategory;
            receivedDao.insert(e);
            Log.d(TAG, "saveToInbox: ✓ " + messageId + " type=" + type);
        });
    }

    // =========================================================================
    // Packet transmission
    // =========================================================================

    private void sendPacketToNode(NeighborNode node, String json) {
        new Thread(() -> sendPacketToNodeSync(node, json), "Send-" + node.nodeId).start();
    }

    private boolean sendPacketToNodeSync(NeighborNode node, String json) {
        return sendViaTcp(node, json) || sendViaBluetooth(node, json);
    }

    private boolean sendViaTcp(NeighborNode node, String json) {
        String ip = sanitizeIp(nodeIpMap.get(node.nodeId));
        if (ip == null) {
            Log.w(TAG, "sendViaTcp: no IP for " + node.nodeId);
            return false;
        }
        Socket s = null;
        try {
            long t0 = System.currentTimeMillis();
            s = new Socket();
            s.connect(new InetSocketAddress(ip, WIFI_PORT), 3_000);
            new PrintWriter(s.getOutputStream(), true).println(json);
            long rtt = System.currentTimeMillis() - t0;

            // Source 1: TCP RTT → synthetic RSSI. Works for all P2P peers
            // regardless of randomized MACs since we key by IP not MAC.
            recordTcpRtt(ip, rtt);

            Log.d(TAG, "TCP sent to " + node.nodeId + " (" + ip + ")"
                    + " rtt=" + rtt + "ms rssiEst=" + resolveRssiForIp(ip));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "sendViaTcp failed " + node.nodeId + " (" + ip + "): " + e.getMessage());
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) {}
        }
    }

    private boolean sendViaBluetooth(NeighborNode node, String json) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled() || !hasBtPermissions()) return false;
        String mac = nodeMacMap.get(node.nodeId);
        if (mac == null) return false;
        try {
            BluetoothDevice dev = bluetoothAdapter.getRemoteDevice(mac);
            try (BluetoothSocket s = dev.createRfcommSocketToServiceRecord(BT_UUID)) {
                try { bluetoothAdapter.cancelDiscovery(); } catch (SecurityException ignored) {}
                s.connect();
                new PrintWriter(s.getOutputStream(), true).println(json);
                Log.d(TAG, "BT sent to " + node.nodeId);
                return true;
            }
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "BT send failed " + node.nodeId + ": " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // HELLO loop
    // =========================================================================

    private void startHelloLoop() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                broadcastHello();
                handler.postDelayed(this, HELLO_INTERVAL);
            }
        }, HELLO_INTERVAL);
    }

    private void broadcastHello() {
        String json;
        try { json = buildHelloJson(); }
        catch (Exception e) { Log.e(TAG, "buildHelloJson error: " + e.getMessage()); return; }

        List<NeighborNode> neighbors = neighborTable.asList();
        for (NeighborNode node : neighbors) sendPacketToNode(node, json);

        neighborTable.pruneStale();

        final String fJson = json;
        for (Map.Entry<String, String> entry : nodeIpMap.entrySet()) {
            String nodeId = entry.getKey();
            String ip     = sanitizeIp(entry.getValue());
            if (ip == null || neighborTable.get(nodeId) != null) continue;
            new Thread(() -> {
                Socket s = null;
                try {
                    s = new Socket();
                    long t0 = System.currentTimeMillis();
                    s.connect(new InetSocketAddress(ip, WIFI_PORT), 1_000);
                    new PrintWriter(s.getOutputStream(), true).println(fJson);
                    recordTcpRtt(ip, System.currentTimeMillis() - t0);
                    Log.d(TAG, "Bootstrap HELLO → " + nodeId + " (" + ip + ")");
                } catch (Exception ignored) {
                } finally {
                    try { if (s != null) s.close(); } catch (Exception ignored2) {}
                }
            }, "Bootstrap-" + nodeId).start();
        }

        topologyMap.remove(myNodeId);

        Log.d(TAG, "HELLO sent | neighbors=" + neighbors.size()
                + " IPs=" + nodeIpMap.size()
                + " topology=" + topologyMap.size()
                + " amGO=" + amGroupOwner
                + " groupClients=" + currentGroupClientCount
                + " bat=" + myBattery + "%");

        if (neighbors.isEmpty()) {
            requestCurrentPeers("hello-no-neighbors");
        }
    }

    // =========================================================================
    // Periodic loops
    // =========================================================================

    private void startDbPruneLoop() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                messageStore.pruneExpired();
                long cutoff = System.currentTimeMillis() - 5 * 60_000L;
                seenPackets.entrySet().removeIf(e -> e.getValue() < cutoff);
                seenBroadcasts.entrySet().removeIf(e -> e.getValue() < cutoff);
                helloReplyAt.entrySet().removeIf(e -> e.getValue() < cutoff);
                prevHopMap.clear();
                handler.postDelayed(this, DB_PRUNE_INTERVAL);
            }
        }, DB_PRUNE_INTERVAL);
    }

    private void startDtnRetryLoop() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                retryStoredMessages();
                handler.postDelayed(this, DTN_RETRY_INTERVAL);
            }
        }, DTN_RETRY_INTERVAL);
    }

    // =========================================================================
    // Location + Battery
    // =========================================================================

    private void initLocation() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000, 5,
                    (LocationListener) loc -> {
                        myLat = loc.getLatitude();
                        myLon = loc.getLongitude();
                    });
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission not granted");
        }
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) myBattery = (int) ((level / (float) scale) * 100);
        }
    };

    private void initBattery() {
        Intent s = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (s != null) {
            int level = s.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = s.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) myBattery = (int) ((level / (float) scale) * 100);
        }
        Log.d(TAG, "Initial battery: " + myBattery + "%");
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public void sendMessage(String destNodeId, String text) {
        MessagePacket pkt = new MessagePacket(myNodeId, destNodeId, 0, 0, text);
        pkt.ttl = MAX_TTL;
        seenPackets.put(pkt.messageId, System.currentTimeMillis());
        Log.i(TAG, "Originating " + pkt.messageId
                + " from=" + myNodeId + " to=" + destNodeId);
        forwardMessage(pkt);
    }

    public void sendMessage(String destNodeId, double lat, double lon, String text) {
        sendMessage(destNodeId, text);
    }

    public String        getMyNodeId()      { return myNodeId; }
    public double        getMyLat()         { return myLat; }
    public double        getMyLon()         { return myLon; }
    public int           getMyBattery()     { return myBattery; }
    public boolean       isGroupOwner()     { return amGroupOwner; }
    public int           getGroupClientCount() { return currentGroupClientCount; }
    public NeighborTable getNeighborTable() { return neighborTable; }

    public void getStoredCountAsync(CountCallback callback) {
        new Thread(() -> {
            try {
                int count = messageStore != null ? messageStore.getPendingCount() : 0;
                new Handler(Looper.getMainLooper()).post(() -> callback.onResult(count));
            } catch (Exception ignored) {}
        }, "StoredCount").start();
    }

    public interface CountCallback { void onResult(int count); }

    public Map<String, String> getNodeIpMap() {
        return Collections.unmodifiableMap(nodeIpMap);
    }

    /** Expose IP-keyed RSSI map (RTT-derived) for UI / debug screens. */
    public Map<String, Integer> getIpRssiMap() {
        return Collections.unmodifiableMap(ipRssiMap);
    }

    /** Expose nodeId-keyed RSSI map (HELLO-latency + BT) for UI / debug screens. */
    public Map<String, Integer> getNodeRssiMap() {
        return Collections.unmodifiableMap(nodeRssiMap);
    }
}
