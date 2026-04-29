package com.bg7yoz.ft8cn.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.cq.CqCallEntry;

import java.util.ArrayList;
import java.util.Locale;

public class CqQueueAdapter extends RecyclerView.Adapter<CqQueueAdapter.CqQueueItemHolder> {
    public interface OnQueueAction {
        void onPromote(CqCallEntry entry);

        void onStartNow(CqCallEntry entry);

        void onRemove(CqCallEntry entry);
    }

    private final Context context;
    private final OnQueueAction onQueueAction;
    private final ArrayList<CqCallEntry> entries = new ArrayList<>();

    public CqQueueAdapter(Context context, OnQueueAction onQueueAction) {
        this.context = context;
        this.onQueueAction = onQueueAction;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void submitList(ArrayList<CqCallEntry> newEntries) {
        entries.clear();
        if (newEntries != null) {
            entries.addAll(newEntries);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CqQueueItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.cq_queue_item, parent, false);
        return new CqQueueItemHolder(view);
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void onBindViewHolder(@NonNull CqQueueItemHolder holder, int position) {
        CqCallEntry entry = entries.get(position);
        holder.rankTextView.setText(String.format(Locale.US, "%02d", position + 1));
        holder.callsignTextView.setText(entry.callsign);
        holder.badgeTextView.setText(buildBadgeText(entry));
        holder.metaTextView.setText(buildMetaText(entry));
        holder.messageTextView.setText(entry.message == null ? "" : entry.message.getMessageText(true));
        holder.promoteButton.setOnClickListener(view -> {
            if (onQueueAction != null) {
                onQueueAction.onPromote(entry);
            }
        });
        holder.nowButton.setOnClickListener(view -> {
            if (onQueueAction != null) {
                onQueueAction.onStartNow(entry);
            }
        });
        holder.removeButton.setOnClickListener(view -> {
            if (onQueueAction != null) {
                onQueueAction.onRemove(entry);
            }
        });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private String buildBadgeText(CqCallEntry entry) {
        StringBuilder builder = new StringBuilder();
        if (entry.manual) {
            builder.append("TOP");
        }
        if (entry.directed) {
            appendBadge(builder, "DIR");
        }
        if (entry.followed) {
            appendBadge(builder, "FOL");
        }
        if (entry.priority == CqCallEntry.PRIORITY_NEW_ANY_BAND) {
            appendBadge(builder, "NEW");
        }
        if (builder.length() == 0) {
            builder.append("CQ");
        }
        return builder.toString();
    }

    private void appendBadge(StringBuilder builder, String value) {
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value);
    }

    @SuppressLint("DefaultLocale")
    private String buildMetaText(CqCallEntry entry) {
        StringBuilder builder = new StringBuilder();
        if (entry.freqHz > 0) {
            builder.append(String.format(Locale.US, "%.0fHz", entry.freqHz));
        }
        builder.append(String.format(Locale.US, " %+ddB", entry.snr));
        if (entry.maidenGrid != null && entry.maidenGrid.length() > 0) {
            builder.append(' ').append(entry.maidenGrid);
        }
        if (entry.distanceKm > 0) {
            builder.append(String.format(Locale.US, " %dkm", entry.distanceKm));
        }
        if (entry.heardCount > 1) {
            builder.append(String.format(Locale.US, " x%d", entry.heardCount));
        }
        return builder.toString().trim();
    }

    static class CqQueueItemHolder extends RecyclerView.ViewHolder {
        final TextView rankTextView;
        final TextView callsignTextView;
        final TextView badgeTextView;
        final TextView metaTextView;
        final TextView messageTextView;
        final Button promoteButton;
        final Button nowButton;
        final Button removeButton;

        CqQueueItemHolder(@NonNull View itemView) {
            super(itemView);
            rankTextView = itemView.findViewById(R.id.cqQueueRankTextView);
            callsignTextView = itemView.findViewById(R.id.cqQueueCallsignTextView);
            badgeTextView = itemView.findViewById(R.id.cqQueueBadgeTextView);
            metaTextView = itemView.findViewById(R.id.cqQueueMetaTextView);
            messageTextView = itemView.findViewById(R.id.cqQueueMessageTextView);
            promoteButton = itemView.findViewById(R.id.cqQueuePromoteButton);
            nowButton = itemView.findViewById(R.id.cqQueueNowButton);
            removeButton = itemView.findViewById(R.id.cqQueueRemoveButton);
        }
    }
}
