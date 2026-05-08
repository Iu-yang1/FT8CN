package com.bg7yoz.ft8cn.cq;

public enum CqRankMethod {
    CALL_ORDER(0, "按接收顺序"),
    MOST_RECENT(1, "最近优先"),
    DISTANCE_NEAR(2, "近距离优先"),
    DISTANCE_FAR(3, "远距离优先"),
    SNR_LOW(4, "弱信号优先"),
    SNR_HIGH(5, "强信号优先");

    private final int value;
    private final String displayName;

    CqRankMethod(int value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    public int getValue() {
        return value;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static CqRankMethod fromValue(int value) {
        for (CqRankMethod method : values()) {
            if (method.value == value) {
                return method;
            }
        }
        return DISTANCE_FAR;
    }

    public static int positionOf(int value) {
        CqRankMethod[] methods = values();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].value == value) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
