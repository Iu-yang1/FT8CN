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
        return false;
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
        return EmeRigControlResult.failure(
                "set-main-frequency",
                getRigName(),
                beforeFrequencyHz,
                targetFrequencyHz,
                correctionHz,
                transmitting,
                "legacy-eme-cat-disabled-use-radio-transaction");
    }
}
