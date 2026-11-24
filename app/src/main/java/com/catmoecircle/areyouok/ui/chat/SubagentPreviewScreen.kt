package com.catmoecircle.areyouok.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.repository.ChatMessage
import com.catmoecircle.areyouok.gateway.repository.SubagentPreview
import com.catmoecircle.areyouok.gateway.repository.ThinkingTranslation
import com.catmoecircle.areyouok.ui.gateway.ImageFetcher
import com.catmoecircle.areyouok.ui.gateway.MessageTimeline
import com.catmoecircle.areyouok.ui.settings.WorkStepCollapseTiming
import com.catmoecircle.areyouok.ui.settings.WorkStepDisplay
import com.catmoecircle.areyouok.ui.settings.WorkStepFoldedReplies
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 子智能体对话的**只读**全屏页（点团队面板里的子代理打开）。
 *
 * - 顶栏：返回 + 子代理标题 + 运行中/已空闲状态点 + 模型 + 「只读」标记；
 * - 正文复用主聊天的 [MessageTimeline]：工具卡 / 思考链 / 图片 / 工作步骤渲染完全一致，
 *   但没有上翻历史（只显示最新一页）、没有输入框，不能发消息；
 * - 加载中显示进度；没有消息时显示空态；失败显示错误文案 + 重试。
 *
 * 数据由 repository 的 [SubagentPreview] 提供，ChatScreen 负责 2s 刷新与返回键。
 */
@Composable
fun SubagentPreviewScreen(
    preview: SubagentPreview,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    workStepDisplay: WorkStepDisplay = WorkStepDisplay.DEFAULT,
    workStepCollapseTiming: WorkStepCollapseTiming = WorkStepCollapseTiming.DEFAULT,
    workStepFoldedReplies: WorkStepFoldedReplies = WorkStepFoldedReplies.DEFAULT,
    translationEnabled: Boolean = false,
    translations: Map<String, ThinkingTranslation> = emptyMap(),
    onTranslate: (ChatMessage) -> Unit = {},
    imageFetcher: ImageFetcher? = null,
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorScheme.surface),
    ) {
        SubagentPreviewTopBar(preview = preview, onBack = onBack)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colorScheme.outlineVariant)
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            MessageTimeline(
                messages = preview.messages,
                workStepDisplay = workStepDisplay,
                workStepCollapseTiming = workStepCollapseTiming,
                workStepFoldedReplies = workStepFoldedReplies,
                translationEnabled = translationEnabled,
                translations = translations,
                onTranslate = onTranslate,
                running = preview.running,
                // 只读预览只显示最新一页，不做上翻分页。
                hasOlder = false,
                loadingOlder = false,
                onLoadOlder = {},
                sessionId = preview.sessionId,
                imageFetcher = imageFetcher,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(top = 6.dp),
            )

            when {
                // 首屏加载：整页居中进度（有旧消息时只在顶部留白，不挡内容）。
                preview.loading && preview.messages.isEmpty() -> {
                    PreviewHint(
                        text = stringResource(R.string.subagent_preview_loading),
                        modifier = Modifier.align(Alignment.Center),
                        loading = true,
                    )
                }
                // 没有任何消息且没有错误：空态（子代理可能刚起来还没产出）。
                preview.messages.isEmpty() && preview.error == null -> {
                    PreviewHint(
                        text = stringResource(R.string.subagent_preview_empty),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            if (preview.error != null) {
                PreviewErrorBanner(
                    text = preview.error,
                    onRetry = onRetry,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SubagentPreviewTopBar(
    preview: SubagentPreview,
    onBack: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val backLabel = stringResource(R.string.subagent_preview_back)
    val status = stringResource(
        if (preview.running) R.string.team_panel_running else R.string.team_panel_idle,
    )
    val subtitle = listOfNotNull(status, preview.model?.takeIf { it.isNotBlank() })
        .joinToString(" · ")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = backLabel, role = Role.Button, onClick = onBack)
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Back,
                contentDescription = null,
                tint = colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preview.displayTitle,
                color = colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubagentStatusDot(running = preview.running)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = subtitle,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = stringResource(R.string.subagent_preview_readonly),
            color = colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SubagentStatusDot(running: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val color = if (running) colorScheme.primary else colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Canvas(modifier = Modifier.size(7.dp)) {
        drawCircle(color = color, radius = size.minDimension / 2f)
    }
}

@Composable
private fun PreviewHint(
    text: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = colorScheme.primary,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            text = text,
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            fontSize = 13.sp,
        )
    }
}

/** 失败横幅：有旧消息时压在底部，不替换内容；「重试」重新拉一次。 */
@Composable
private fun PreviewErrorBanner(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val retryLabel = stringResource(R.string.subagent_preview_retry)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = retryLabel,
            color = colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = retryLabel, role = Role.Button, onClick = onRetry)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
