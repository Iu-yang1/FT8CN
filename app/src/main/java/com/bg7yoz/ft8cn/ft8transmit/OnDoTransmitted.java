package com.bg7yoz.ft8cn.ft8transmit;
/**
 * 发射的回调
 * @author BGY70Z
 * @date 2023-03-20
 */

import com.bg7yoz.ft8cn.Ft8Message;

public interface OnDoTransmitted {
    /** 只有 CAT 配置和 PTT 读回均确认后才返回 true。 */
    boolean onPrepareTransmit();

    /** PTT lead time 结束后、播放音频前进行最后一次安全复核。 */
    default boolean onAudioReady() {
        return true;
    }

    /** 每次物理发射只调用一次，不与多消息日志回调混用。 */
    default void onTransmitFinished() {
    }

    /** 在尚未完成音频播放时撤销 PTT 和电台事务。 */
    default void onTransmitAborted(String reason) {
    }

    void onBeforeTransmit(Ft8Message message,int functionOder);
    void onAfterTransmit(Ft8Message message, int functionOder);
    void onTransmitByWifi(Ft8Message message);

    //2023-08-16 由DS1UFX提交修改（基于0.9版），增加(tr)uSDX audio over cat的支持。
    boolean supportTransmitOverCAT();
    void onTransmitOverCAT(Ft8Message message);
}

