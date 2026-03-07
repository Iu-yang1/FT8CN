package com.bg7yoz.ft8cn.ft8listener;

import com.bg7yoz.ft8cn.FT8Common;
import com.bg7yoz.ft8cn.GeneralVariables;

public class ReBuildSignal {
    private static final String TAG = "ReBuildSignal";

    static {
        System.loadLibrary("ft8cn");
    }

    public static void subtractSignal(long decoder, A91List a91List) {
        subtractSignal(decoder, a91List, GeneralVariables.getSignalMode());
    }

    public static void subtractSignal(long decoder, A91List a91List, int mode) {
        if (a91List == null || a91List.list == null || a91List.list.size() == 0) {
            return;
        }

        for (A91List.A91 a91 : a91List.list) {
            if (!shouldSubtract(a91, mode)) {
                continue;
            }

            doSubtractSignal(
                    decoder,
                    a91.a91,
                    FT8Common.SAMPLE_RATE,
                    a91.freq_hz,
                    a91.time_sec,
                    mode
            );
        }
    }

    public static boolean supportSubtract(int mode) {
        return mode == FT8Common.FT8_MODE || mode == FT8Common.FT4_MODE;
    }

    private static boolean shouldSubtract(A91List.A91 a91, int mode) {
        if (a91 == null) {
            return false;
        }

        if (mode == FT8Common.FT4_MODE) {
            // FT4
            return a91.snr >= -21 && a91.score >= 12;
        } else {
            // FT8
            return a91.snr >= -28 && a91.score >= 10;
        }
    }

    private static native void doSubtractSignal(long decoder,
                                                byte[] payload,
                                                int sample_rate,
                                                float frequency,
                                                float time_sec,
                                                int mode);
}