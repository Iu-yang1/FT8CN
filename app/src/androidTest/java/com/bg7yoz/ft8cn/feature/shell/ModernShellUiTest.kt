package com.bg7yoz.ft8cn.feature.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModernShellUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ModernShellActivity>()

    @Before
    fun launchModernShell() {
        composeRule.waitForIdle()
        // 先提交真实 Activity 的首帧，再冻结时钟以避免 EME 周期任务无限前推。
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun callPageAndFeatureNavigationRemainAccessible() {
        // EME 页面有生产用周期刷新；测试只手动推进有限的抽屉动画。
        composeRule.onNodeWithText("FT8 · 15 秒").assertIsDisplayed()
        composeRule.onNodeWithText("FT4 · 7.5 秒").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom-nav-eme").assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("eme-screen-title").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom-nav-satellite").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom-nav-logbook").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom-nav-radio").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom-nav-settings").assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("打开完整设置").assertIsDisplayed()
    }
}
