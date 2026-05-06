package com.meshcomm.ui;

import android.annotation.SuppressLint;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import com.meshcomm.R;
import com.meshcomm.model.NeighborNode;
import com.meshcomm.service.MeshService;
import com.meshcomm.util.HaversineUtil;

import java.util.*;

public class NeighborActivity extends AppCompatActivity {

    private ListView           listView;
    private NeighborAdapter    adapter;
    private List<NeighborNode> neighbors = new ArrayList<>();
    private TextView           tvCount, tvMyNodeId;

    private MeshService meshService = null;
    private boolean     bound       = false;

    private final Handler    handler = new Handler(Looper.getMainLooper());
    private static final int REFRESH = 2000;

    // ── Binding state machine ─────────────────────────────────────────────────
    // Problem 1 (fixed): scheduleRefresh() was only called from onServiceConnected.
    // If the user opens NeighborActivity before MeshService finishes starting,
    // onServiceConnected could fire AFTER the initial refresh window passed and
    // the loop would never start.
    //
    // Fix: we always post the first refresh with a short delay. Inside refreshNeighbors()
    // we check `bound`. If not bound yet, we show "Connecting…" and reschedule.
    // Once bound, normal data flows. The loop never stops until onStop().

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshNeighbors();
            // Keep running regardless of bind state — stops only in onStop()
            handler.postDelayed(this, REFRESH);
        }
    };

    // ── ServiceConnection ─────────────────────────────────────────────────────
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            meshService = ((MeshService.LocalBinder) service).getService();
            bound = true;
            // Refresh immediately so the UI updates without waiting for the next tick
            refreshNeighbors();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Problem 2 (fixed): old code set bound=false and meshService=null here,
            // then the refresh loop would call meshService.getNeighborTable() and NPE.
            // Fix: null-guard in refreshNeighbors() + set both fields safely.
            bound       = false;
            meshService = null;
        }
    };

    // ── BroadcastReceiver — fires on every HELLO/peer change in MeshService ───
    // Problem 3 (fixed): receiver called refreshNeighbors() directly.
    // If bound was still false at that moment, it silently showed "Connecting…"
    // and never retried. Now we just let the existing refresh loop handle it —
    // the receiver only triggers an immediate extra refresh (no duplicate loops).
    private ArrayList<String> discoveredPeerNames =
            new ArrayList<>();

    private final BroadcastReceiver peersReceiver =
            new BroadcastReceiver(){

                @Override
                public void onReceive(
                        Context ctx,
                        Intent intent){

                    ArrayList<String> peerNames=
                            intent.getStringArrayListExtra(
                                    "peerNames"
                            );

                    /* receive names from MeshService */
                    if(peerNames!=null){

                        discoveredPeerNames.clear();

                        discoveredPeerNames.addAll(
                                peerNames
                        );

                    }

                    /* force immediate refresh */
                    handler.removeCallbacks(
                            refreshRunnable
                    );

                    handler.post(
                            refreshRunnable
                    );

                }
            };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_neighbor);

        listView   = findViewById(R.id.lvNeighbors);
        tvCount    = findViewById(R.id.tvNeighborCount);
        tvMyNodeId = findViewById(R.id.tvNeighborMyId);

        adapter = new NeighborAdapter();
        listView.setAdapter(adapter);

        findViewById(R.id.btnNeighborRefresh).setOnClickListener(v -> {
            // Re-trigger Wi-Fi Direct discovery via service
            Intent i = new Intent(this, MeshService.class);
            i.setAction("DISCOVER");
            startService(i);
            // Immediate UI refresh
            handler.removeCallbacks(refreshRunnable);
            handler.post(refreshRunnable);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Start MeshService if not already running, then bind
        startService(new Intent(this, MeshService.class));
        bindService(new Intent(this, MeshService.class),
                connection, Context.BIND_AUTO_CREATE);

        // Register peer-update receiver
        IntentFilter f = new IntentFilter(MeshService.ACTION_PEERS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(peersReceiver, f, RECEIVER_NOT_EXPORTED);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(peersReceiver, f,RECEIVER_NOT_EXPORTED);
            }
        }

        // Start the refresh loop immediately — it runs even while waiting for bind
        handler.post(refreshRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Stop the refresh loop
        handler.removeCallbacks(refreshRunnable);
        try { unregisterReceiver(peersReceiver); } catch (IllegalArgumentException ignored) {}
        if (bound) {
            unbindService(connection);
            bound       = false;
            meshService = null;
        }
    }

    // ── Core refresh — called every 2 seconds and on every peer event ─────────

    @SuppressLint("SetTextI18n")
    private void refreshNeighbors(){

        if(!bound || meshService==null){

            tvMyNodeId.setText(
                    "Connecting..."
            );

            tvCount.setText(
                    "Waiting for service..."
            );

            adapter.notifyDataSetChanged();

            return;
        }

        /* routed HELLO neighbors */
        neighbors=
                meshService
                        .getNeighborTable()
                        .asList();

        adapter.notifyDataSetChanged();

        tvMyNodeId.setText(
                "My Node ID: "+
                        meshService.getMyNodeId()
        );

        MeshService service = meshService;
        if (service == null) return;
        int battery = service.getMyBattery();
        int knownIpCount = service.getNodeIpMap().size();

        service.getStoredCountAsync(
                count -> {

                    String peerInfo=
                            discoveredPeerNames.isEmpty()
                                    ? "None"
                                    : discoveredPeerNames.toString();

                    tvCount.setText(
                            "Routing Neighbors: "+
                                    neighbors.size()

                                    + "\nWiFi Peers In Range: "
                                    + peerInfo

                                    + "\nDTN Queue: "
                                    + count

                                    + "\nBattery: "
                                    + battery
                                    +"%"

                                    + "\nKnown IPs: "
                                    + knownIpCount

                    );

                });
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    class NeighborAdapter extends BaseAdapter {

        @Override public int    getCount()         { return neighbors.isEmpty() ? 1 : neighbors.size(); }
        @Override public Object getItem(int pos)   { return neighbors.isEmpty() ? null : neighbors.get(pos); }
        @Override public long   getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {

            // Empty state
            if (neighbors.isEmpty()) {
                TextView tv = new TextView(NeighborActivity.this);
                tv.setPadding(32, 48, 32, 48);
                tv.setTextSize(14);
                tv.setTextColor(Color.parseColor("#9E9E9E"));
                tv.setText(
                    "No neighbors discovered yet.\n\n" +
                    "Checklist:\n" +
                    "  1.  Other phones have the app open\n" +
                    "  2.  Wi-Fi is ON on all phones\n" +
                    "  3.  Location / GPS is ON on all phones\n" +
                    "  4.  Phones within 10–15 metres\n" +
                    "  5.  Wait 5–10 s for HELLO packets\n\n" +
                    "Single phone? Use Testing → Fake Neighbor."
                );
                return tv;
            }

            // Real neighbor row
            if (convertView == null || convertView instanceof TextView) {
                convertView = LayoutInflater.from(NeighborActivity.this)
                        .inflate(R.layout.item_neighbor, parent, false);
            }

            NeighborNode node = neighbors.get(pos);

            TextView tvNodeId  = convertView.findViewById(R.id.tvNodeId);
            TextView tvBadge   = convertView.findViewById(R.id.tvRouteBadge);
            TextView tvRssi    = convertView.findViewById(R.id.tvRssi);
            TextView tvBattery = convertView.findViewById(R.id.tvBattery);
            TextView tvDist    = convertView.findViewById(R.id.tvDistance);
            TextView tvGps     = convertView.findViewById(R.id.tvGps);

            tvNodeId.setText(node.nodeId);

            // RSSI signal quality
            String signalLabel;
            int    signalColor;
            if      (node.rssi >= -55) { signalLabel = "Excellent"; signalColor = 0xFF2E7D32; }
            else if (node.rssi >= -67) { signalLabel = "Good";      signalColor = 0xFF388E3C; }
            else if (node.rssi >= -70) { signalLabel = "OK";        signalColor = 0xFFF57C00; }
            else                       { signalLabel = "Weak";      signalColor = 0xFFD32F2F; }

            tvRssi.setText("RSSI: " + node.rssi + " dBm\n(" + signalLabel + ")");
            tvRssi.setTextColor(signalColor);

            // Battery
            tvBattery.setText("Battery:\n" + node.batteryLevel + "%");
            tvBattery.setTextColor(node.batteryLevel > 20 ? 0xFF2E7D32 : 0xFFD32F2F);

            // Distance using Haversine (needs our own GPS)
            double myLat = (bound && meshService != null) ? meshService.getMyLat() : 0;
            double myLon = (bound && meshService != null) ? meshService.getMyLon() : 0;
            if (myLat != 0 || myLon != 0) {
                double dist = HaversineUtil.distanceMetres(
                        myLat, myLon, node.latitude, node.longitude);
                tvDist.setText(String.format(Locale.getDefault(), "Distance:\n%.0f m", dist));
            } else {
                tvDist.setText("Distance:\nGPS needed");
            }

            // Neighbor GPS
            tvGps.setText(String.format(Locale.getDefault(),
                    "GPS: %.5f, %.5f", node.latitude, node.longitude));

            // Routing eligibility badge
            boolean eligible = node.rssi > -70 && node.batteryLevel > 20;
            tvBadge.setText(eligible ? "CAN ROUTE" : "EXCLUDED");
            tvBadge.setBackgroundColor(eligible
                    ? Color.parseColor("#E8F5E9") : Color.parseColor("#FFEBEE"));
            tvBadge.setTextColor(eligible
                    ? Color.parseColor("#2E7D32") : Color.parseColor("#C62828"));

            // Alternating row background
            convertView.setBackgroundColor(
                    pos % 2 == 0 ? Color.WHITE : Color.parseColor("#F8F8F8"));

            return convertView;
        }
    }
}
