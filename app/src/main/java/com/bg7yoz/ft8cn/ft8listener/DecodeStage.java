package com.bg7yoz.ft8cn.ft8listener;

enum DecodeStage {
    LIVE_FULL(false),
    EARLY(true),
    DEEP_SUPPLEMENT(true),
    DIAGNOSTIC_SAMPLE(true);

    final boolean droppable;

    DecodeStage(boolean droppable) {
        this.droppable = droppable;
    }
}
