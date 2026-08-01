package com.bg7yoz.ft8cn;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;

/** 为跨 Activity 的接收/解码会话提供标准进程级 ViewModelStore。 */
public final class Ft8cnApplication extends Application implements ViewModelStoreOwner {
    private final ViewModelStore viewModelStore = new ViewModelStore();

    @NonNull
    @Override
    public ViewModelStore getViewModelStore() {
        return viewModelStore;
    }

    @Override
    public void onTerminate() {
        viewModelStore.clear();
        super.onTerminate();
    }
}
