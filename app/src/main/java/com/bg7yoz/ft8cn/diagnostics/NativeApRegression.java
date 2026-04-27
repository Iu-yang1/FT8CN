package com.bg7yoz.ft8cn.diagnostics;

/**
 * FT8/FT4 AP/follow-up 回归入口。
 *
 * 这个类不挂到正常 UI 流程里，只给本地调试、临时按钮或 adb/IDEA 调用。
 */
public final class NativeApRegression {
    static {
        System.loadLibrary("ft8cn");
    }

    private NativeApRegression() {
    }

    /**
     * 运行一组确定性的合成弱信号场景，并返回逐项摘要。
     */
    public static native String runSyntheticSuite();
}
