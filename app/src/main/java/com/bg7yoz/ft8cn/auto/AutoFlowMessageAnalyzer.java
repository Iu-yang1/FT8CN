package com.bg7yoz.ft8cn.auto;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;

public final class AutoFlowMessageAnalyzer {
    private AutoFlowMessageAnalyzer() {
    }

    public static boolean callsignMatches(String left, String right) {
        String leftNormalized = normalizeCallsign(left);
        String rightNormalized = normalizeCallsign(right);

        if (leftNormalized.length() == 0 || rightNormalized.length() == 0) {
            return false;
        }

        if (leftNormalized.equals(rightNormalized)) {
            return true;
        }

        return getMainCallsign(leftNormalized).equals(getMainCallsign(rightNormalized));
    }

    public static boolean isMyCallsign(String myCallsign, String candidate) {
        String myNormalized = normalizeCallsign(myCallsign);
        String candidateNormalized = normalizeCallsign(candidate);

        if (myNormalized.length() == 0 || candidateNormalized.length() == 0) {
            return false;
        }

        return candidateNormalized.contains(GeneralVariables.getShortCallsign(myNormalized));
    }

    public static int resolveIncomingOrder(Ft8Message message, String myCallsign, String targetCallsign) {
        return resolveIncomingOrder(message, myCallsign, targetCallsign, true);
    }

    public static int resolveIncomingOrder(Ft8Message message,
                                           String myCallsign,
                                           String targetCallsign,
                                           boolean allowDxpeditionHound) {
        if (message == null || !message.isAutoFlowRelevant()) {
            return -1;
        }

        if (message.isDxpeditionCompoundMessage()) {
            if (!allowDxpeditionHound) {
                return -1;
            }
            if (message.signalFormat != FT8Common.FT8_MODE) {
                return -1;
            }

            if (!callsignMatches(message.getDxpeditionFoxCallsign(), targetCallsign)) {
                return -1;
            }

            if (isMyCallsign(myCallsign, message.getCallsignTo())) {
                return 5;
            }

            if (isMyCallsign(myCallsign, message.getDxpeditionSecondCallsign())) {
                return 2;
            }

            return -1;
        }

        if (!isMyCallsign(myCallsign, message.getAutoReplyCallsignTo())) {
            return -1;
        }

        if (!callsignMatches(message.getAutoReplyCallsignFrom(), targetCallsign)) {
            return -1;
        }

        return message.checkIsCQ()
                ? 6
                : GeneralVariables.checkFunOrderByExtraInfo(message.getAutoReplyExtraInfo());
    }

    public static AutoSessionType resolveSessionType(Ft8Message message,
                                                     String myCallsign,
                                                     String targetCallsign,
                                                     AutoSessionType fallback) {
        return resolveSessionType(message, myCallsign, targetCallsign, fallback, true);
    }

    public static AutoSessionType resolveSessionType(Ft8Message message,
                                                     String myCallsign,
                                                     String targetCallsign,
                                                     AutoSessionType fallback,
                                                     boolean allowDxpeditionHound) {
        if (message != null
                && allowDxpeditionHound
                && message.isDxpeditionCompoundMessage()
                && message.signalFormat == FT8Common.FT8_MODE
                && callsignMatches(message.getDxpeditionFoxCallsign(), targetCallsign)
                && (isMyCallsign(myCallsign, message.getCallsignTo())
                || isMyCallsign(myCallsign, message.getDxpeditionSecondCallsign()))) {
            return AutoSessionType.FT8_DXPEDITION_HOUND;
        }
        return fallback == null ? AutoSessionType.STANDARD : fallback;
    }

    public static boolean isDirectedReplyToCurrentTarget(Ft8Message message,
                                                         String myCallsign,
                                                         String targetCallsign) {
        return isDirectedReplyToCurrentTarget(message, myCallsign, targetCallsign, true);
    }

    public static boolean isDirectedReplyToCurrentTarget(Ft8Message message,
                                                         String myCallsign,
                                                         String targetCallsign,
                                                         boolean allowDxpeditionHound) {
        return resolveIncomingOrder(message, myCallsign, targetCallsign, allowDxpeditionHound) != -1;
    }

    public static boolean isCurrentSessionActivity(Ft8Message message,
                                                   String targetCallsign,
                                                   int signalFormat,
                                                   long band) {
        return isCurrentSessionActivity(message, targetCallsign, signalFormat, band, true);
    }

    public static boolean isCurrentSessionActivity(Ft8Message message,
                                                   String targetCallsign,
                                                   int signalFormat,
                                                   long band,
                                                   boolean allowDxpeditionHound) {
        if (message == null || !message.isAutoFlowRelevant()) {
            return false;
        }

        if (message.signalFormat != signalFormat) {
            return false;
        }

        if (band > 0 && message.band > 0 && message.band != band) {
            return false;
        }

        if (targetCallsign == null || targetCallsign.trim().length() == 0 || "CQ".equalsIgnoreCase(targetCallsign)) {
            return false;
        }

        if (message.isDxpeditionCompoundMessage()) {
            if (!allowDxpeditionHound) {
                return false;
            }
            return callsignMatches(message.getDxpeditionFoxCallsign(), targetCallsign);
        }

        return callsignMatches(message.getAutoReplyCallsignFrom(), targetCallsign);
    }

    private static String normalizeCallsign(String callsign) {
        if (callsign == null) {
            return "";
        }

        return callsign.trim()
                .toUpperCase()
                .replace("<", "")
                .replace(">", "");
    }

    private static String getMainCallsign(String callsign) {
        int len = callsign.length();
        if (len == 0) {
            return "";
        }

        int bestStart = 0;
        int bestLen = 0;
        int start = 0;

        while (start <= len) {
            int slash = callsign.indexOf('/', start);
            int end = (slash >= 0) ? slash : len;
            int tokenLen = end - start;
            if (tokenLen > bestLen) {
                bestLen = tokenLen;
                bestStart = start;
            }
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }

        if (bestLen == 0) {
            return callsign;
        }
        return callsign.substring(bestStart, bestStart + bestLen);
    }
}
