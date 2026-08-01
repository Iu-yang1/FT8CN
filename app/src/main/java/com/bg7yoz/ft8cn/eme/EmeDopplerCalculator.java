package com.bg7yoz.ft8cn.eme;

public final class EmeDopplerCalculator {
    private static final double SPEED_OF_LIGHT_MPS = 299_792_458.0;

    /** 与 WSJT-X 3.0.2 Astronomical Data 中的三个基础模式一一对应。 */
    public enum PathMode {
        FULL_DOPPLER_TO_DX,
        OWN_ECHO,
        CONSTANT_FREQUENCY_ON_MOON
    }

    public static final class CorrectionPlan {
        public final PathMode mode;
        public final double localOneWayHz;
        public final double dxOneWayHz;
        public final double receiveCorrectionHz;
        public final double transmitCorrectionHz;

        private CorrectionPlan(PathMode mode,
                               double localOneWayHz,
                               double dxOneWayHz,
                               double receiveCorrectionHz,
                               double transmitCorrectionHz) {
            this.mode = mode;
            this.localOneWayHz = localOneWayHz;
            this.dxOneWayHz = dxOneWayHz;
            this.receiveCorrectionHz = receiveCorrectionHz;
            this.transmitCorrectionHz = transmitCorrectionHz;
        }
    }

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

    /**
     * 计算 RX/TX 校正量。rangeRate 为正表示站点到月面的距离增加。
     *
     * WSJT-X 的 astro.cpp 使用 m_dop00=2*local，m_dop=local+DX：
     * Full DX 为 RX=m_dop、TX=-m_dop；Own Echo 为 RX=m_dop00、TX=0；
     * CFOM 为 RX=m_dop00/2、TX=-m_dop00/2。
     */
    public static CorrectionPlan calculatePlan(double frequencyHz,
                                                double localRangeRateMps,
                                                double dxRangeRateMps,
                                                PathMode mode) {
        if (mode == null || !Double.isFinite(frequencyHz) || frequencyHz <= 0.0
                || !Double.isFinite(localRangeRateMps)) {
            throw new IllegalArgumentException("invalid EME Doppler input");
        }
        if (mode == PathMode.FULL_DOPPLER_TO_DX && !Double.isFinite(dxRangeRateMps)) {
            throw new IllegalArgumentException("DX range rate is required for full Doppler");
        }
        final double local = calculateDopplerHz(frequencyHz, localRangeRateMps);
        final double dx = Double.isFinite(dxRangeRateMps)
                ? calculateDopplerHz(frequencyHz, dxRangeRateMps)
                : 0.0;
        if (mode == PathMode.OWN_ECHO) {
            return new CorrectionPlan(mode, local, dx, 2.0 * local, 0.0);
        }
        if (mode == PathMode.CONSTANT_FREQUENCY_ON_MOON) {
            return new CorrectionPlan(mode, local, dx, local, -local);
        }
        final double mutual = local + dx;
        return new CorrectionPlan(mode, local, dx, mutual, -mutual);
    }
}
