package com.formsnap.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.formsnap.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Activity, navigation and Room, running in Robolectric rather than on a physical device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN")
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `launch create open return and recreate activity`() {
        compose.onNodeWithText("纸质表单，可靠变成可用数据。").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("暂无录入任务").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("+ 新建录入任务").performClick()
        compose.onNodeWithTag("createTask").assertIsNotEnabled()
        compose.onNodeWithTag("taskName").performTextInput("设备登记测试")
        compose.onNodeWithTag("createTask").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("任务详情").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("设备登记测试").assertIsDisplayed()
        compose.onNodeWithText("草稿").assertIsDisplayed()
        compose.onNodeWithText("尚未检查").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("最近任务").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("设备登记测试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("设备登记测试").performClick()
        compose.onNodeWithText("任务详情").assertIsDisplayed()
    }
}
