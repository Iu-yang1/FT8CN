package com.bg7yoz.ft8cn.eme;

public interface EmeTrackingEnvironment {
    String getObserverGrid();

    EmeRigControlAdapter getRigControlAdapter();

    long getFallbackBaseFrequencyHz();

    boolean hasAutoFrequencyConflict();

    String getAutoFrequencyConflictReason();
}
