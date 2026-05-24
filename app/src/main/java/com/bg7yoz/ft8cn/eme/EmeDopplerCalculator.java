package com.bg7yoz.ft8cn.eme;

public final class EmeDopplerCalculator {
    private static final double SPEED_OF_LIGHT_MPS = 299_792_458.0;

    private EmeDopplerCalculator() {
    }

    public static double calculateDopplerHz(double frequencyHz, double rangeRateMps) {
        if (!Double.isFinite(frequencyHz) || !Double.isFinite(rangeRateMps) || frequencyHz <= 0.0) {
            return 0.0;
        }
        return -(rangeRateMps / SPEED_OF_LIGHT_MPS) * frequencyHz;
    }

    public static double calculateRxCorrectionHz(double frequencyHz, double rangeRateMps) {
        return calculateDopplerHz(frequencyHz, rangeRateMps);
    }

    public static double calculateTxCorrectionHz(double frequencyHz, double rangeRateMps) {
        return -calculateDopplerHz(frequencyHz, rangeRateMps);
    }
}
