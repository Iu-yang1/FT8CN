package com.bg7yoz.ft8cn.eme;

import android.util.Log;

import com.bg7yoz.ft8cn.rigs.BaseRig;

public final class EmeRigControlAdapter {
    private static final String TAG = "EmeRigControl";

    private final BaseRig rig;

    public EmeRigControlAdapter(BaseRig rig) {
        this.rig = rig;
    }

    public boolean isAvailable() {
        return rig != null && rig.isConnected();
    }

    public boolean canReadMainFrequency() {
        return rig != null;
    }

    public boolean canSetMainFrequency() {
        return isAvailable();
    }

    public boolean supportsSplitFrequency() {
        return false;
    }

    public String getRigName() {
        if (rig == null) {
            return "-";
        }
        try {
            return rig.getName();
        } catch (Exception e) {
            return rig.getClass().getSimpleName();
        }
    }

    public boolean isTransmitting() {
        return rig != null && rig.isPttOn();
    }

    public long getCachedMainFrequencyHz() {
        return rig == null ? 0L : rig.getFreq();
    }

    public void requestReadMainFrequency() {
        if (rig == null) {
            return;
        }
        try {
            rig.readFreqFromRig();
        } catch (Exception e) {
            Log.w(TAG, "EME CAT read frequency request failed: rig="
                    + getRigName()
                    + " reason="
                    + e.getMessage());
        }
    }

    public EmeRigControlResult setMainFrequencyHz(long targetFrequencyHz,
                                                  double correctionHz,
                                                  boolean allowWhileTransmitting) {
        long beforeFrequencyHz = getCachedMainFrequencyHz();
        boolean transmitting = isTransmitting();
        if (rig == null) {
            return EmeRigControlResult.failure(
                    "set-main-frequency",
                    getRigName(),
                    beforeFrequencyHz,
                    targetFrequencyHz,
                    correctionHz,
                    transmitting,
                    "rig-unavailable");
        }
        if (!rig.isConnected()) {
            return EmeRigControlResult.failure(
                    "set-main-frequency",
                    getRigName(),
                    beforeFrequencyHz,
                    targetFrequencyHz,
                    correctionHz,
                    transmitting,
                    "rig-disconnected");
        }
        if (transmitting && !allowWhileTransmitting) {
            return EmeRigControlResult.failure(
                    "set-main-frequency",
                    getRigName(),
                    beforeFrequencyHz,
                    targetFrequencyHz,
                    correctionHz,
                    true,
                    "blocked-while-transmitting");
        }
        if (targetFrequencyHz <= 0L) {
            return EmeRigControlResult.failure(
                    "set-main-frequency",
                    getRigName(),
                    beforeFrequencyHz,
                    targetFrequencyHz,
                    correctionHz,
                    transmitting,
                    "invalid-target-frequency");
        }

        try {
            rig.setFreq(targetFrequencyHz);
            rig.setFreqToRig();
            EmeRigControlResult result = EmeRigControlResult.success(
                    "set-main-frequency",
                    getRigName(),
                    beforeFrequencyHz,
                    targetFrequencyHz,
                    correctionHz,
                    transmitting);
            Log.i(TAG, "EME CAT command sent: " + result.toSummary());
            return result;
        } catch (Exception e) {
            boolean restored = false;
            try {
                if (beforeFrequencyHz > 0L) {
                    rig.setFreq(beforeFrequencyHz);
                    restored = true;
                }
            } catch (Exception restoreException) {
                Log.w(TAG, "EME CAT local cache restore failed: rig="
                        + getRigName()
                        + " before="
                        + beforeFrequencyHz
                        + " reason="
                        + restoreException.getMessage());
            }
            EmeRigControlResult result = EmeRigControlResult.failure(
                    "set-main-frequency",
                    getRigName(),
                    beforeFrequencyHz,
                    targetFrequencyHz,
                    correctionHz,
                    transmitting,
                    "command-exception:" + e.getMessage()
                            + (restored ? ":local-cache-restored" : ":local-cache-may-be-stale"));
            Log.e(TAG, "EME CAT command failed: " + result.toSummary());
            return result;
        }
    }
}
