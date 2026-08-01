package com.bg7yoz.ft8cn.feature.shell

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.bg7yoz.ft8cn.R
import com.bg7yoz.ft8cn.core.model.FeatureDestination
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque

@RunWith(AndroidJUnit4::class)
class ModernShellUiTest {
    private lateinit var instrumentation: Instrumentation
    private lateinit var activity: Ft8cnActivity

    @Before
    fun launchFt8cnShell() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        grantRuntimePermissions()
        launchDestination(FeatureDestination.DECODE)
    }

    private fun launchDestination(destination: FeatureDestination) {
        if (::activity.isInitialized && !activity.isDestroyed) {
            val previous = activity
            instrumentation.runOnMainSync { previous.finishAndRemoveTask() }
            waitForDestroyed(previous)
        }
        val intent = Intent(instrumentation.targetContext, Ft8cnActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(Ft8cnActivity.EXTRA_INITIAL_DESTINATION, destination.route)
        }
        instrumentation.targetContext.startActivity(intent)
        activity = waitForResumedActivity()
            ?: throw AssertionError("FT8CN Activity 未进入 RESUMED：${destination.route}")
    }

    @After
    fun closeFt8cnShell() {
        if (::activity.isInitialized && !activity.isFinishing) {
            instrumentation.runOnMainSync { activity.finish() }
            SystemClock.sleep(300L)
        }
    }

    @Test
    fun primaryAndSupportingNavigationRemainAccessible() {
        FeatureDestination.values().forEach { destination ->
            val description = destination.label
            assertNotNull(findAccessibilityNode(description = description))
        }
        assertNotNull(findAccessibilityNode(description = "当前模式录音槽位进度"))

        launchDestination(FeatureDestination.EME)
        assertNotNull(accessibilitySnapshot(), findAccessibilityNode(text = "Q65 / EME"))
        launchDestination(FeatureDestination.SATELLITE)
        assertNotNull(findAccessibilityNode(text = "卫星追踪"))
        launchDestination(FeatureDestination.LOGBOOK)
        assertNotNull(findAccessibilityNode(text = "通联日志"))
        launchDestination(FeatureDestination.RADIO)
        assertNotNull(findAccessibilityNode(text = "电台"))
        assertNotNull(findAccessibilityNode(text = "工作频率"))
        launchDestination(FeatureDestination.SETTINGS)
        assertNotNull(findAccessibilityNode(text = "时间同步"))
        val toolbarButton = findAccessibilityNode(description = "展开浮动工具栏")
        assertNotNull(toolbarButton)
        assertTrue(toolbarButton!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertNotNull(findAccessibilityNode(description = "选择工作频率"))
    }

    @Test
    fun legacyLogStatisticsSurvivesComposeRecomposition() {
        launchDestination(FeatureDestination.LOGBOOK)
        val countButton = waitForView(R.id.countImageButton)
        assertNotNull(countButton)
        instrumentation.runOnMainSync { assertTrue(countButton!!.performClick()) }

        val countList = waitForView(R.id.countRecyclerView)
        assertNotNull(countList)
        assertTrue(countList!!.isShown)

        // 等待 Compose 顶层状态继续更新，确认不会把嵌套 NavHost 重置回日志首页。
        SystemClock.sleep(2_000L)
        assertTrue(waitForView(R.id.countRecyclerView)!!.isShown)
    }

    private fun findAccessibilityNode(
        text: String? = null,
        description: String? = null,
        timeoutMillis: Long = 8_000L,
    ): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val match = root?.findBreadthFirst(text, description)
            if (match != null) return match
            SystemClock.sleep(100L)
        }
        return null
    }

    private fun AccessibilityNodeInfo.findBreadthFirst(
        expectedText: String?,
        expectedDescription: String?,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val textMatches = expectedText == null || node.text?.toString() == expectedText
            val descriptionMatches = expectedDescription == null ||
                node.contentDescription?.toString() == expectedDescription
            if (textMatches && descriptionMatches) return node
            repeat(node.childCount) { index ->
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun accessibilitySnapshot(): String {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "无活动无障碍窗口"
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val entries = ArrayList<String>()
        queue.add(root)
        while (queue.isNotEmpty() && entries.size < 80) {
            val node = queue.removeFirst()
            val nodeText = node.text?.toString().orEmpty()
            val nodeDescription = node.contentDescription?.toString().orEmpty()
            if (nodeText.isNotBlank() || nodeDescription.isNotBlank()) {
                entries += "text=[$nodeText] description=[$nodeDescription] " +
                    "clickable=${node.isClickable}"
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return entries.joinToString(separator = "\n")
    }

    private fun waitForView(id: Int, timeoutMillis: Long = 8_000L): View? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            var view: View? = null
            instrumentation.runOnMainSync { view = activity.findViewById(id) }
            if (view?.isShown == true) return view
            SystemClock.sleep(100L)
        }
        return null
    }

    private fun waitForResumedActivity(timeoutMillis: Long = 10_000L): Ft8cnActivity? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            var resumed: Ft8cnActivity? = null
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<Ft8cnActivity>()
                    .filterNot { it.isFinishing }
                    .lastOrNull()
            }
            if (resumed != null) return resumed
            SystemClock.sleep(100L)
        }
        return null
    }

    private fun waitForDestroyed(target: Ft8cnActivity, timeoutMillis: Long = 8_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!target.isDestroyed && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100L)
        }
        if (!target.isDestroyed) throw AssertionError("旧 FT8CN Activity 未能及时销毁")
    }

    private fun grantRuntimePermissions() {
        val packageName = instrumentation.targetContext.packageName
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        permissions.forEach { permission ->
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
            }
        }
    }
}
