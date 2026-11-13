package com.catmoecircle.areyouok.ui.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.File

/**
 * 人类可读的文件大小：`12 B` / `345 KB` / `1.2 MB`。
 *
 * 未知大小（[bytes] 为 null 或非正数）返回 null，调用方不展示大小行。
 */
fun formatFileSize(bytes: Long?): String? {
    val value = bytes?.takeIf { it > 0 } ?: return null
    return when {
        value < 1024 -> "$value B"
        value < 1024L * 1024 -> String.format(Locale.US, "%.0f KB", value / 1024.0)
        value < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.1f GB", value / (1024.0 * 1024.0 * 1024.0))
    }
}

/** 附件展示名：文件名为空时兜底成「附件」，避免出现一个空白胶囊。 */
@Composable
fun attachmentDisplayName(name: String): String =
    name.takeIf { it.isNotBlank() } ?: stringResource(R.string.composer_attachment_fallback_name)

/**
 * 文件附件小胶囊：图标 + 文件名 + 大小。
 *
 * 输入框的待发送附件和聊天里的已发送附件都用它；[onRemove] 非空时右侧多一个 ×
 * （只有输入框里的草稿附件可移除，聊天里的历史附件只展示）。
 */
@Composable
fun FileAttachmentChip(
    name: String,
    bytes: Long?,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    onRemove: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val sizeText = formatFileSize(bytes)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(
                start = 10.dp,
                end = if (onRemove == null) 12.dp else 6.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.File,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.widthIn(max = 200.dp)) {
            Text(
                text = attachmentDisplayName(name),
                color = colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sizeText != null) {
                Text(
                    text = sizeText,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
        if (onRemove != null) {
            val label = stringResource(R.string.composer_attachment_remove)
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onRemove)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Close,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
