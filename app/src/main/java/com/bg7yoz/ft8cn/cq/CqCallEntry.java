package com.bg7yoz.ft8cn.cq;

import com.bg7yoz.ft8cn.Ft8Message;

public final class CqCallEntry {
    public static final int PRIORITY_MANUAL = 0;
    public static final int PRIORITY_DIRECTED = 1;
    public static final int PRIORITY_FOLLOWED = 2;
    public static final int PRIORITY_NEW_ANY_BAND = 3;
    public static final int PRIORITY_DEFAULT = 4;

    public Ft8Message message;
    public final String callsign;
    public String cqTarget;
    public String maidenGrid;
    public int snr;
    public float freqHz;
    public long band;
    public int sequence;
    public int sequenceIndex;
    public int priority;
    public int distanceKm;
    public boolean manual;
    public boolean directed;
    public boolean followed;
    public final long firstHeardMs;
    public long lastHeardMs;
    public int heardCount;

    CqCallEntry(Ft8Message message,
                String callsign,
                String cqTarget,
                String maidenGrid,
                int priority,
                int distanceKm,
                boolean manual,
                boolean directed,
                boolean followed,
                long nowMs) {
        this.message = message;
        this.callsign = callsign;
        this.cqTarget = cqTarget;
        this.maidenGrid = maidenGrid;
        this.snr = message == null ? 0 : message.snr;
        this.freqHz = message == null ? 0f : message.freq_hz;
        this.band = message == null ? 0L : message.band;
        this.sequence = message == null ? 0 : message.getSequence();
        this.sequenceIndex = message == null ? Integer.MIN_VALUE : message.getFullSequenceIndex();
        this.priority = priority;
        this.distanceKm = distanceKm;
        this.manual = manual;
        this.directed = directed;
        this.followed = followed;
        this.firstHeardMs = nowMs;
        this.lastHeardMs = nowMs;
        this.heardCount = 1;
    }

    void refresh(Ft8Message message,
                 String cqTarget,
                 String maidenGrid,
                 int priority,
                 int distanceKm,
                 boolean manual,
                 boolean directed,
                 boolean followed,
                 long nowMs) {
        this.message = message == null ? null : new Ft8Message(message);
        this.cqTarget = cqTarget;
        this.maidenGrid = maidenGrid;
        this.snr = message == null ? 0 : message.snr;
        this.freqHz = message == null ? 0f : message.freq_hz;
        this.band = message == null ? 0L : message.band;
        this.sequence = message == null ? 0 : message.getSequence();
        this.sequenceIndex = message == null ? Integer.MIN_VALUE : message.getFullSequenceIndex();
        this.manual = this.manual || manual;
        this.directed = directed;
        this.followed = followed;
        this.priority = this.manual ? PRIORITY_MANUAL : priority;
        this.distanceKm = distanceKm;
        lastHeardMs = nowMs;
        heardCount++;
    }
}
