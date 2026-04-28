package com.bg7yoz.ft8cn.cq;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;

public final class CallQueueManager {
    private static final long STALE_ENTRY_MS = 8 * 60 * 1000L;

    private final ArrayList<CqCallEntry> queue = new ArrayList<>();
    private final HashMap<String, CqCallEntry> byCallsign = new HashMap<>();
    private DatabaseOpr databaseOpr;
    private int maxQueueSize = 20;
    private CqRankMethod rankMethod = CqRankMethod.DISTANCE_FAR;
    private String directedPrefixes = "";

    public synchronized void setDatabaseOpr(DatabaseOpr databaseOpr) {
        this.databaseOpr = databaseOpr;
    }

    public synchronized void configure(int maxQueueSize,
                                       int rankMethod,
                                       String directedPrefixes) {
        this.maxQueueSize = Math.max(1, Math.min(maxQueueSize, 100));
        this.rankMethod = CqRankMethod.fromValue(rankMethod);
        this.directedPrefixes = normalizePrefixList(directedPrefixes);
        trimToMaxSizeLocked();
        sortLocked();
    }

    public synchronized boolean addCandidate(Ft8Message message, boolean manual) {
        pruneLocked();
        if (!isCandidate(message, manual)) {
            return false;
        }

        String callsign = normalizeCallsign(message.getAutoReplyCallsignFrom());
        CqCallEntry existing = byCallsign.get(callsign);
        long nowMs = UtcTimer.getSystemTime();
        if (existing != null) {
            CqCallEntry refreshed = buildEntry(message, callsign, manual, nowMs);
            existing.refresh(
                    refreshed.message,
                    refreshed.cqTarget,
                    refreshed.maidenGrid,
                    refreshed.priority,
                    refreshed.distanceKm,
                    manual,
                    refreshed.directed,
                    refreshed.followed,
                    nowMs
            );
            sortLocked();
            return false;
        }

        CqCallEntry entry = buildEntry(message, callsign, manual, nowMs);
        queue.add(entry);
        byCallsign.put(callsign, entry);
        sortLocked();
        trimToMaxSizeLocked();
        return byCallsign.containsKey(callsign);
    }

    public synchronized int addCandidates(ArrayList<Ft8Message> messages) {
        if (messages == null) {
            return 0;
        }
        int added = 0;
        for (Ft8Message message : messages) {
            if (addCandidate(message, false)) {
                added++;
            }
        }
        return added;
    }

    public synchronized CqCallEntry pollNext() {
        pruneLocked();
        if (queue.isEmpty()) {
            return null;
        }
        CqCallEntry entry = queue.remove(0);
        byCallsign.remove(entry.callsign);
        return entry;
    }

    public synchronized CqCallEntry peekNext() {
        pruneLocked();
        return queue.isEmpty() ? null : queue.get(0);
    }

    public synchronized int size() {
        pruneLocked();
        return queue.size();
    }

    public synchronized void clear() {
        queue.clear();
        byCallsign.clear();
    }

    public synchronized boolean remove(String callsign) {
        String normalized = normalizeCallsign(callsign);
        CqCallEntry entry = byCallsign.remove(normalized);
        if (entry == null) {
            return false;
        }
        queue.remove(entry);
        return true;
    }

    public synchronized ArrayList<CqCallEntry> snapshot() {
        pruneLocked();
        return new ArrayList<>(queue);
    }

    private boolean isCandidate(Ft8Message message, boolean manual) {
        if (message == null || !message.isAutoFlowRelevant()) {
            return false;
        }
        if (!message.checkIsCQ()) {
            return false;
        }
        String callsign = normalizeCallsign(message.getAutoReplyCallsignFrom());
        if (callsign.length() == 0 || GeneralVariables.checkIsMyCallsign(callsign)) {
            return false;
        }
        if (GeneralVariables.checkIsExcludeCallsign(callsign)) {
            return false;
        }
        if (!manual && GeneralVariables.checkQSLCallsign(callsign)) {
            return false;
        }
        return manual
                || GeneralVariables.autoFollowCQ
                || GeneralVariables.callsignInFollow(callsign)
                || isDirectedCq(message);
    }

    private CqCallEntry buildEntry(Ft8Message message,
                                   String callsign,
                                   boolean manual,
                                   long nowMs) {
        String cqTarget = normalizeCqTarget(message.callsignTo);
        boolean directed = isDirectedCq(message);
        boolean followed = GeneralVariables.callsignInFollow(callsign);
        boolean newAnyBand = !GeneralVariables.checkQSLCallsign_OtherBand(callsign);
        int priority = CqCallEntry.PRIORITY_DEFAULT;
        if (manual) {
            priority = CqCallEntry.PRIORITY_MANUAL;
        } else if (directed) {
            priority = CqCallEntry.PRIORITY_DIRECTED;
        } else if (followed) {
            priority = CqCallEntry.PRIORITY_FOLLOWED;
        } else if (newAnyBand) {
            priority = CqCallEntry.PRIORITY_NEW_ANY_BAND;
        }
        String grid = message.getMaidenheadGrid(databaseOpr);
        int distance = calculateDistanceKm(grid);
        return new CqCallEntry(
                new Ft8Message(message),
                callsign,
                cqTarget,
                grid,
                priority,
                distance,
                manual,
                directed,
                followed,
                nowMs
        );
    }

    private void pruneLocked() {
        long nowMs = UtcTimer.getSystemTime();
        for (int i = queue.size() - 1; i >= 0; i--) {
            CqCallEntry entry = queue.get(i);
            if (nowMs - entry.lastHeardMs <= STALE_ENTRY_MS) {
                continue;
            }
            queue.remove(i);
            byCallsign.remove(entry.callsign);
        }
    }

    private void trimToMaxSizeLocked() {
        sortLocked();
        while (queue.size() > maxQueueSize) {
            CqCallEntry removed = queue.remove(queue.size() - 1);
            byCallsign.remove(removed.callsign);
        }
    }

    private void sortLocked() {
        Collections.sort(queue, new Comparator<CqCallEntry>() {
            @Override
            public int compare(CqCallEntry left, CqCallEntry right) {
                if (left.priority != right.priority) {
                    return left.priority - right.priority;
                }
                int rank = compareByRankMethod(left, right);
                if (rank != 0) {
                    return rank;
                }
                if (left.lastHeardMs != right.lastHeardMs) {
                    return left.lastHeardMs < right.lastHeardMs ? 1 : -1;
                }
                return left.callsign.compareTo(right.callsign);
            }
        });
    }

    private int compareByRankMethod(CqCallEntry left, CqCallEntry right) {
        switch (rankMethod) {
            case CALL_ORDER:
                return Long.compare(left.firstHeardMs, right.firstHeardMs);
            case MOST_RECENT:
                return Long.compare(right.lastHeardMs, left.lastHeardMs);
            case DISTANCE_NEAR:
                return compareDistance(left.distanceKm, right.distanceKm, false);
            case DISTANCE_FAR:
                return compareDistance(left.distanceKm, right.distanceKm, true);
            case SNR_LOW:
                return Integer.compare(left.snr, right.snr);
            case SNR_HIGH:
                return Integer.compare(right.snr, left.snr);
            default:
                return 0;
        }
    }

    private int compareDistance(int leftKm, int rightKm, boolean farFirst) {
        boolean leftUnknown = leftKm <= 0;
        boolean rightUnknown = rightKm <= 0;
        if (leftUnknown && rightUnknown) {
            return 0;
        }
        if (leftUnknown) {
            return 1;
        }
        if (rightUnknown) {
            return -1;
        }
        return farFirst
                ? Integer.compare(rightKm, leftKm)
                : Integer.compare(leftKm, rightKm);
    }

    private int calculateDistanceKm(String grid) {
        if (grid == null || !MaidenheadGrid.checkMaidenhead(grid)) {
            return 0;
        }
        String myGrid = GeneralVariables.getMyMaidenhead4Grid();
        if (myGrid == null || !MaidenheadGrid.checkMaidenhead(myGrid)) {
            return 0;
        }
        return (int) MaidenheadGrid.getDist(myGrid, grid);
    }

    private boolean isDirectedCq(Ft8Message message) {
        if (message == null || directedPrefixes.length() == 0) {
            return false;
        }
        String target = normalizeCqTarget(message.callsignTo);
        if (target.length() == 0) {
            return false;
        }
        String[] prefixes = directedPrefixes.split(" ");
        for (String prefix : prefixes) {
            if (prefix.length() > 0 && target.contains(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeCqTarget(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.US).replaceAll("\\s+", " ");
    }

    private String normalizePrefixList(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toUpperCase(Locale.US)
                .replace(",", " ")
                .replace("|", " ")
                .replaceAll("\\s+", " ");
    }

    private String normalizeCallsign(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toUpperCase(Locale.US)
                .replace("<", "")
                .replace(">", "");
    }
}
