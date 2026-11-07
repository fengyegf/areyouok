package com.catmoecircle.areyouok.ui.gateway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.catmoecircle.areyouok.gateway.protocol.SessionSummary
import com.catmoecircle.areyouok.gateway.protocol.Workspace
import com.catmoecircle.areyouok.gateway.repository.SessionProject
import com.catmoecircle.areyouok.ui.theme.AreYouOKTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 侧边栏关闭时整个面板会离开组合（`ModalSidebar` 的 `if (visible)`），
 * 这里验证「用户手动收起的项目」在面板重建后仍然是收起状态，而不是被重置成全部展开。
 */
@RunWith(AndroidJUnit4::class)
class GatewayProjectListStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val projects = listOf(
        SessionProject(
            workspace = Workspace(workspaceId = "w1", title = "Alpha"),
            sessions = listOf(SessionSummary(id = "s1", title = "alpha-chat")),
        ),
        SessionProject(
            workspace = Workspace(workspaceId = "w2", title = "Beta"),
            sessions = listOf(SessionSummary(id = "s2", title = "beta-chat")),
        ),
    )

    @Test
    fun collapsedProjectStaysCollapsedAfterPanelIsRecreated() {
        var showPanel by mutableStateOf(true)

        composeRule.setContent {
            AreYouOKTheme {
                val stateHolder = rememberSaveableStateHolder()
                if (showPanel) {
                    stateHolder.SaveableStateProvider("sidebar-panel") {
                        GatewayProjectList(
                            projects = projects,
                            selectedSessionId = null,
                            onSelectSession = {},
                            onNewSessionIn = {},
                        )
                    }
                }
            }
        }

        // 默认全部展开：两个项目下的会话都可见。
        composeRule.onNodeWithText("alpha-chat").assertIsDisplayed()
        composeRule.onNodeWithText("beta-chat").assertIsDisplayed()

        // 手动收起 Alpha。
        composeRule.onNodeWithText("Alpha").performClick()
        composeRule.onNodeWithText("alpha-chat").assertDoesNotExist()

        // 关闭侧边栏（面板离开组合）再重新打开。
        showPanel = false
        composeRule.waitForIdle()
        showPanel = true
        composeRule.waitForIdle()

        // Alpha 仍然收起，Beta 保持展开。
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("alpha-chat").assertDoesNotExist()
        composeRule.onNodeWithText("beta-chat").assertIsDisplayed()
    }
}
