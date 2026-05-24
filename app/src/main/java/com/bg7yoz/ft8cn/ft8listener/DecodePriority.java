package com.bg7yoz.ft8cn.ft8listener;

enum DecodePriority {
    LIVE_FULL(400),
    Q65_FULL(350),
    EARLY(200),
    DEEP_SUPPLEMENT(100),
    DIAGNOSTIC_SAMPLE(50);

    final int sortOrder;

    DecodePriority(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
