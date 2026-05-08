package com.bg7yoz.ft8cn.ft8transmit;

import com.bg7yoz.ft8cn.Ft8Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MultiSlotTransmitPlan {
    private final int signalMode;
    private final ArrayList<MultiSlotTransmitItem> items = new ArrayList<>();

    public MultiSlotTransmitPlan(int signalMode) {
        this.signalMode = signalMode;
    }

    public static MultiSlotTransmitPlan single(Ft8Message message,
                                               int functionOrder,
                                               float frequencyHz,
                                               int signalMode) {
        MultiSlotTransmitPlan plan = new MultiSlotTransmitPlan(signalMode);
        plan.add(new MultiSlotTransmitItem(0, message, functionOrder, frequencyHz));
        return plan;
    }

    public void add(MultiSlotTransmitItem item) {
        if (item != null && item.message != null) {
            items.add(item);
        }
    }

    public int getSignalMode() {
        return signalMode;
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public boolean isMultiSlot() {
        return items.size() > 1;
    }

    public List<MultiSlotTransmitItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public MultiSlotTransmitItem getPrimaryItem() {
        if (items.isEmpty()) {
            return null;
        }
        return items.get(0);
    }

    public Ft8Message getPrimaryMessage() {
        MultiSlotTransmitItem item = getPrimaryItem();
        return item == null ? null : item.message;
    }

    public int getPrimaryFunctionOrder(int fallback) {
        MultiSlotTransmitItem item = getPrimaryItem();
        return item == null ? fallback : item.functionOrder;
    }

    public String getDisplayText(String modeLabel) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (items.size() == 1) {
            MultiSlotTransmitItem item = items.get(0);
            builder.append("[")
                    .append(modeLabel)
                    .append("] (")
                    .append(Math.round(item.frequencyHz))
                    .append(" Hz) ")
                    .append(item.message.getMessageText());
            return builder.toString();
        }

        builder.append("[")
                .append(modeLabel)
                .append("] TX Slots ")
                .append(items.size());
        for (int i = 0; i < items.size(); i++) {
            MultiSlotTransmitItem item = items.get(i);
            builder.append("\nTX")
                    .append(item.slotIndex + 1)
                    .append(": ")
                    .append(Math.round(item.frequencyHz))
                    .append(" Hz ")
                    .append(item.message.getMessageText());
        }
        return builder.toString();
    }
}

