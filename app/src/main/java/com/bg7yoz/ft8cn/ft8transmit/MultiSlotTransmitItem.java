package com.bg7yoz.ft8cn.ft8transmit;

import com.bg7yoz.ft8cn.Ft8Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MultiSlotTransmitItem {
    public final int slotIndex;
    public final Ft8Message message;
    public final int functionOrder;
    public final float frequencyHz;
    public final boolean specialMessage;

    private final ArrayList<String> reportCallsigns = new ArrayList<>();
    private final ArrayList<String> rr73Callsigns = new ArrayList<>();

    public MultiSlotTransmitItem(int slotIndex,
                                 Ft8Message message,
                                 int functionOrder,
                                 float frequencyHz) {
        this(slotIndex, message, functionOrder, frequencyHz, false);
    }

    public MultiSlotTransmitItem(int slotIndex,
                                 Ft8Message message,
                                 int functionOrder,
                                 float frequencyHz,
                                 boolean specialMessage) {
        this.slotIndex = slotIndex;
        this.message = message;
        this.functionOrder = functionOrder;
        this.frequencyHz = frequencyHz;
        this.specialMessage = specialMessage;
        if (message != null) {
            message.isTransmitMessage = true;
            message.freq_hz = frequencyHz;
        }
    }

    public MultiSlotTransmitItem addReportCallsign(String callsign) {
        addCallsign(reportCallsigns, callsign);
        return this;
    }

    public MultiSlotTransmitItem addRr73Callsign(String callsign) {
        addCallsign(rr73Callsigns, callsign);
        return this;
    }

    public List<String> getReportCallsigns() {
        return Collections.unmodifiableList(reportCallsigns);
    }

    public List<String> getRr73Callsigns() {
        return Collections.unmodifiableList(rr73Callsigns);
    }

    public String getPrimaryCallsign() {
        if (!rr73Callsigns.isEmpty()) {
            return rr73Callsigns.get(0);
        }
        if (!reportCallsigns.isEmpty()) {
            return reportCallsigns.get(0);
        }
        if (message == null) {
            return "";
        }
        return message.getAutoReplyCallsignTo();
    }

    private void addCallsign(ArrayList<String> callsigns, String callsign) {
        String normalized = normalizeCallsign(callsign);
        if (normalized.length() == 0) {
            return;
        }
        for (String existing : callsigns) {
            if (existing.equalsIgnoreCase(normalized)) {
                return;
            }
        }
        callsigns.add(normalized);
    }

    private String normalizeCallsign(String callsign) {
        if (callsign == null) {
            return "";
        }
        return callsign.trim().toUpperCase()
                .replace("<", "")
                .replace(">", "");
    }
}

