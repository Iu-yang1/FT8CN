package com.bg7yoz.ft8cn.feature.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

/** 渐进迁移入口；旧 MainActivity 在功能完成前仍是产品默认入口。 */
class ModernShellActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Ft8cnFeatureShell()
            }
        }
    }
}
