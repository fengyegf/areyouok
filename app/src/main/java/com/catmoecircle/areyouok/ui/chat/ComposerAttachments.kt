package com.catmoecircle.areyouok.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.ui.gateway.FileAttachmentChip
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add

/**
 * 输入框里待发送的一个本地文件。
 *
 * 选文件时立刻把字节读进内存：SAF 返回的 Uri 只对当前进程临时授权，等点发送时再读
 * 可能已经失效。体积上限与网关的 `/v1/sessions/{id}/files` 一致（32 MiB）。
 */
class ComposerAttachment(
    /** 本地唯一标识：删除时按它匹配，允许一次选进两个同名文件。 */
    val id: String,
    val name: String,
    /** ContentResolver 给出的 MIME；未知为 null（按普通文件上传）。 */
    val mimeType: String?,
    val bytes: ByteArray,
) {
    val sizeBytes: Long get() = bytes.size.toLong()
}

/** 单个附件上限：与 dsh-gateway 的 `MAX_UPLOAD_BYTES` 保持一致。 */
const val MAX_COMPOSER_ATTACHMENT_BYTES: Long = 32L * 1024 * 1024

/**
 * 从系统文件选择器返回的 [uri] 读出一个待发送附件。
 *
 * @return 成功时返回附件；读不到字节、超过 [maxBytes]、或 content provider 抛错时返回 null。
 */
suspend fun readComposerAttachment(
    context: Context,
    uri: Uri,
    id: String,
    maxBytes: Long = MAX_COMPOSER_ATTACHMENT_BYTES,
): ComposerAttachment? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bytes = readAttachmentBytes(resolver, uri, maxBytes) ?: return@withContext null
    ComposerAttachment(
        id = id,
        name = attachmentName(resolver, uri),
        mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
        bytes = bytes,
    )
}

/** 读出整个文件字节；超过上限直接放弃（不截断，避免发出去一个坏文件）。 */
private fun readAttachmentBytes(
    resolver: ContentResolver,
    uri: Uri,
    maxBytes: Long,
): ByteArray? {
    val stream = try {
        resolver.openInputStream(uri)
    } catch (error: Exception) {
        null
    } ?: return null
    stream.use {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = it.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray().takeIf { bytes -> bytes.isNotEmpty() }
    }
}

/** 文件展示名：优先 content provider 的 DISPLAY_NAME，其次 Uri 末段。 */
private fun attachmentName(resolver: ContentResolver, uri: Uri): String {
    val queried = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (error: Exception) {
        null
    }
    return queried?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: "attachment"
}

/**
 * 输入框操作行最左侧的「+」：打开系统文件选择器挑文件（可多选）。
 */
@Composable
fun ComposerAttachButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.composer_attach_file)
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.Add,
            contentDescription = null,
            tint = if (enabled) colorScheme.onSurfaceVariant else colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 输入框上方的待发送附件条：横向滚动，每一项都能点 × 移除。
 *
 * 只在有附件时显示（调用方判断），这样没选文件时输入框高度完全不变。
 */
@Composable
fun ComposerAttachmentRail(
    attachments: List<ComposerAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (attachment in attachments) {
            FileAttachmentChip(
                name = attachment.name,
                bytes = attachment.sizeBytes,
                onRemove = { onRemove(attachment.id) },
            )
        }
    }
}
