package com.bg7yoz.ft8cn.wave;

/** Q65 专用回调；接收方取得 native PCM 缓冲区所有权并负责关闭。 */
public interface OnGetNativeVoiceDataDone {
    void onGetDone(NativeFloatBuffer data);
}
