package com.bg7yoz.ft8cn.ft8listener;

import java.util.Objects;

public final class DecodeWorkerConfig {
    public enum Preset {
        AUTO,
        CONSERVATIVE,
        BALANCED,
        PERFORMANCE
    }

    public final Preset preset;
    public final int workerCount;
    public final int earlyBacklogLimit;
    public final int lowPriorityBacklogLimit;

    private DecodeWorkerConfig(Preset preset,
                               int workerCount,
                               int earlyBacklogLimit,
                               int lowPriorityBacklogLimit) {
        this.preset = preset;
        this.workerCount = Math.max(1, workerCount);
        this.earlyBacklogLimit = Math.max(0, earlyBacklogLimit);
        this.lowPriorityBacklogLimit = Math.max(0, lowPriorityBacklogLimit);
    }

    public static DecodeWorkerConfig conservative() {
        return fromPreset(Preset.CONSERVATIVE);
    }

    public static DecodeWorkerConfig fromPreset(Preset preset) {
        int available = Math.max(1, Runtime.getRuntime().availableProcessors());
        switch (preset) {
            case AUTO:
                return available >= 8 ? performance() : balanced();
            case BALANCED:
                return balanced();
            case PERFORMANCE:
                return performance();
            case CONSERVATIVE:
            default:
                return new DecodeWorkerConfig(Preset.CONSERVATIVE, 1, 0, 0);
        }
    }

    private static DecodeWorkerConfig performance() {
        int available = Math.max(1, Runtime.getRuntime().availableProcessors());
        int workers = Math.max(1, Math.min(4, available - 1));
        return new DecodeWorkerConfig(Preset.PERFORMANCE, workers, Math.max(1, workers - 1), 1);
    }

    private static DecodeWorkerConfig balanced() {
        return new DecodeWorkerConfig(Preset.BALANCED, 2, 1, 1);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DecodeWorkerConfig)) {
            return false;
        }
        DecodeWorkerConfig that = (DecodeWorkerConfig) other;
        return workerCount == that.workerCount
                && earlyBacklogLimit == that.earlyBacklogLimit
                && lowPriorityBacklogLimit == that.lowPriorityBacklogLimit
                && preset == that.preset;
    }

    @Override
    public int hashCode() {
        return Objects.hash(preset, workerCount, earlyBacklogLimit, lowPriorityBacklogLimit);
    }

    @Override
    public String toString() {
        return "DecodeWorkerConfig{"
                + "preset=" + preset
                + ", workerCount=" + workerCount
                + ", earlyBacklogLimit=" + earlyBacklogLimit
                + ", lowPriorityBacklogLimit=" + lowPriorityBacklogLimit
                + '}';
    }
}
