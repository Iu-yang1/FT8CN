package com.bg7yoz.ft8cn.core.radio;

/**
 * Android 进程内 Hamlib 的最小 JNI 边界。
 *
 * 所有调用由 {@link NativeHamlibRadioController} 在单一 IO lane 串行化；这里不保存
 * Android Context、USB 对象或 UI 状态，避免把设备生命周期泄漏到 native 层。
 */
public final class NativeHamlibBridge {
    static {
        System.loadLibrary("ft8cn");
    }

    private NativeHamlibBridge() {
    }

    public static native boolean nativeAvailable();
    public static native String nativeVersion();
    public static native String[] nativeListModels();

    public static native long nativeOpen(
            int model,
            String endpoint,
            int baud,
            int dataBits,
            int stopBits,
            String handshake,
            String forceDtr,
            String forceRts,
            String pttType,
            String pttEndpoint,
            int pollIntervalMs,
            int txDelayMs,
            boolean autoPowerOn,
            boolean autoPowerOff);

    public static native void nativeClose(long handle);
    public static native void nativeSetFrequency(long handle, long rxFrequencyHz, long txFrequencyHz);
    public static native long[] nativeGetFrequency(long handle);
    public static native void nativeSetMode(long handle, int mode, int passbandHz);
    public static native long[] nativeGetMode(long handle);
    public static native void nativeSetVfo(long handle, int vfo);
    public static native void nativeSetSplit(long handle, boolean enabled, int txVfo);
    public static native void nativeSetPtt(long handle, boolean enabled, boolean dataAudio);
    public static native boolean nativeGetPtt(long handle);
    public static native float nativeGetStrength(long handle);
}
