package com.bg7yoz.ft8cn.feature.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat

/** 渐进迁移入口；旧 MainActivity 在功能完成前仍是产品默认入口。 */
class ModernShellActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            Ft8cnTheme {
                Ft8cnFeatureShell()
            }
        }
    }
}
