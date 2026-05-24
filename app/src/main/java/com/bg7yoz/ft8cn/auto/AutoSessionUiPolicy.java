package com.bg7yoz.ft8cn.auto;

import com.bg7yoz.ft8cn.FT8Common;

public final class AutoSessionUiPolicy {
    private static final int[] CQ_ONLY = new int[]{6};
    private static final int[] STANDARD = new int[]{1, 2, 3, 4, 5, 6};
    private static final int[] DXPEDITION_HOUND = new int[]{1, 3};
    private static final int[] DXPEDITION_FOX = new int[]{2, 4, 6};

    private AutoSessionUiPolicy() {
    }

    public static boolean supportsAutomaticQso(int signalMode) {
        return signalMode != FT8Common.Q65_MODE;
    }

    public static boolean supportsDxpedition(int signalMode) {
        return signalMode == FT8Common.FT8_MODE;
    }

    public static int[] getAvailableFunctionOrders(AutoSessionType sessionType, int currentFunctionOrder) {
        if (currentFunctionOrder == 6) {
            return CQ_ONLY;
        }
        if (sessionType == AutoSessionType.FT8_DXPEDITION_HOUND) {
            return DXPEDITION_HOUND;
        }
        if (sessionType == AutoSessionType.FT8_DXPEDITION_FOX) {
            return DXPEDITION_FOX;
        }
        return STANDARD;
    }

    public static boolean isFunctionOrderAllowed(AutoSessionType sessionType, int currentFunctionOrder, int order) {
        int[] allowed = getAvailableFunctionOrders(sessionType, currentFunctionOrder);
        for (int candidate : allowed) {
            if (candidate == order) {
                return true;
            }
        }
        return false;
    }

    public static int sanitizeFunctionOrder(AutoSessionType sessionType, int currentFunctionOrder, int requestedOrder) {
        int[] allowed = getAvailableFunctionOrders(sessionType, currentFunctionOrder);
        for (int candidate : allowed) {
            if (candidate == requestedOrder) {
                return requestedOrder;
            }
        }
        return allowed[0];
    }
}

