package com.bg7yoz.ft8cn.diagnostics;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 让内部真机基准保持在前台，避免厂商省电策略杀死 instrumentation 进程。
 *
 * <p>该 Activity 不读取用户数据、不启动录音，也不执行任何解码。</p>
 */
public final class DeviceBenchmarkActivity extends Activity {
    static Intent buildStartIntent(Context context) {
        return new Intent(context, DeviceBenchmarkActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        TextView status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(18.0f);
        status.setText("FT8CN 内部真机解码测试正在运行");
        setContentView(status);
    }
}
