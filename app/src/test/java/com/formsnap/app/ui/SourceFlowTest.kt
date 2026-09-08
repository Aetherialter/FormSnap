package com.formsnap.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.formsnap.app.MainActivity
import com.formsnap.app.data.TestImageProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the real Activity Result callback and Room. Picker results are simulated, not device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN")
class SourceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun `multi-pick cancel repeat recreate remove and unavailable source`() {
        val provider = TestImageProvider.register(compose.activity.applicationContext)
        compose.onNodeWithText("+ 新建录入任务").performClick()
        compose.onNodeWithTag("taskName").performTextInput("来源流程测试")
        compose.onNodeWithTag("createTask").performClick()
        waitFor("已添加 0 页")
        val result = Intent().apply {
            clipData = ClipData.newUri(compose.activity.contentResolver, "fixture", Uri.parse("content://${TestImageProvider.AUTHORITY}/a")).apply {
                addItem(ClipData.Item(Uri.parse("content://${TestImageProvider.AUTHORITY}/b")))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        choose(Activity.RESULT_OK, result, recreateDuringPicker = true)
        waitFor("已添加 2 页")
        choose(Activity.RESULT_OK, result)
        waitFor("已添加 0 页，跳过重复 2 页，未添加 0 页。")
        choose(Activity.RESULT_CANCELED, null)
        compose.onNodeWithText("已添加 2 页").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        waitFor("已添加 2 页")
        waitUntilIdle()
        compose.onNodeWithContentDescription("移除第 1 页").performScrollTo().performClick()
        compose.onNodeWithTag("confirmRemoveSource").performClick()
        waitFor("已添加 1 页")
        assertTrue(provider.original.exists())
        provider.unavailable = true
        compose.onNodeWithText("重新检查来源").performScrollTo().performClick()
        waitFor("原文件暂时无法读取")
        compose.onNodeWithText("已添加 1 页").performScrollTo().assertIsDisplayed()
    }

    private fun choose(code: Int, result: Intent?, recreateDuringPicker: Boolean = false) {
        waitUntilIdle()
        compose.onNodeWithText("添加表单").performScrollTo().performClick()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        assertTrue(request.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertTrue(request.intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", request.intent.type)
        assertArrayEquals(arrayOf("image/*"), request.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        if (recreateDuringPicker) {
            compose.activityRule.scenario.recreate()
            waitUntilIdle()
        }
        compose.activityRule.scenario.onActivity { it.activityResultRegistry.dispatchResult(request.requestCode, code, result) }
    }
    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitUntilIdle() {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("添加表单") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
}
