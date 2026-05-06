package com.meshcomm.ui;

import android.graphics.Color;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.meshcomm.R;
import com.meshcomm.model.ChatMessage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

    private final List<ChatMessage> data;
    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public ChatAdapter(List<ChatMessage> data) { this.data = data; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        ChatMessage m = data.get(pos);
        boolean sent = m.direction == ChatMessage.Direction.SENT;

        h.tvText.setText(m.text);
        h.tvMeta.setText(
                (sent ? "→ " + m.toNodeId : "← " + m.fromNodeId)
                + "  " + SDF.format(new Date(m.timestamp))
                + (m.acknowledged ? " ✓" : ""));

        // Sent = right-align blue, Received = left-align grey
        h.tvText.setBackgroundColor(sent ? Color.parseColor("#2196F3")
                                         : Color.parseColor("#E0E0E0"));
        h.tvText.setTextColor(sent ? Color.WHITE : Color.BLACK);
        h.tvText.setGravity(sent ? android.view.Gravity.END : android.view.Gravity.START);
    }

    @Override public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvText, tvMeta;
        VH(View v) {
            super(v);
            tvText = v.findViewById(R.id.tvMsgText);
            tvMeta = v.findViewById(R.id.tvMsgMeta);
        }
    }
}
