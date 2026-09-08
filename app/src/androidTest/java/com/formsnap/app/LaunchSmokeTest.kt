package com.formsnap.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchAndOpenTaskCreation() {
        compose.onNodeWithText("纸质表单，可靠变成可用数据。").assertIsDisplayed()
        compose.onNodeWithText("+ 新建录入任务").performClick()
        compose.onNodeWithText("任务名称").assertIsDisplayed()
    }
}
