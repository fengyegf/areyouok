package com.catmoecircle.areyouok.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.catmoecircle.areyouok.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Unlock

/**
 * 输入框「模型 / 思考强度 / 权限」菜单的纯展示组件。
 *
 * 与网关 DTO 解耦：这里只接收已经映射好的 UI 模型（[ComposerModelOption] 等），
 * 便于在 ChatScreen 里把 `ModelCatalog` / `PermissionCatalog` 映射成中文文案，
 * 也便于单测与 @Preview。
 */

/** 当前模型选择：provider + model + 可选思考强度。 */
data class ComposerModelSelection(
    val provider: String,
    val model: String,
    val effort: String? = null,
)

/** 一个思考强度选项（对应 DSH `reasoning.efforts[]`）。 */
data class ComposerEffortOption(
    val id: String,
    val label: String,
    val description: String? = null,
)

/** 一个可选模型（厂商 + 型号 + 该型号支持的思考强度）。 */
data class ComposerModelOption(
    val provider: String,
    val providerLabel: String,
    val model: String,
    val label: String,
    val description: String? = null,
    val efforts: List<ComposerEffortOption> = emptyList(),
    val defaultEffort: String? = null,
)

/** 一个权限档位（对应 DSH `permissionPresets/catalog.options[]`）。 */
data class ComposerPermissionOption(
    val value: String,
    val label: String,
    val description: String? = null,
)

/** 需要二次确认的高风险权限档位：完全访问、自动审查。 */
val ComposerPermissionOption.requiresConfirmation: Boolean
    get() = value == PERMISSION_FULL_ACCESS || value == PERMISSION_AUTO || value == PERMISSION_AUTO_REVIEW

const val PERMISSION_READ_ONLY = "read-only"
const val PERMISSION_WORKSPACE_WRITE = "workspace-write"
const val PERMISSION_FULL_ACCESS = "danger-full-access"
const val PERMISSION_AUTO = "auto"
const val PERMISSION_AUTO_REVIEW = "auto-review"

/** 当前权限在输入框左侧的图标。 */
@Composable
fun permissionIcon(value: String?): ImageVector = when (value) {
    PERMISSION_READ_ONLY -> MiuixIcons.Regular.Lock
    PERMISSION_FULL_ACCESS -> MiuixIcons.Regular.Unlock
    else -> MiuixIcons.Regular.Lock
}

/**
 * 输入框左侧的权限按钮：只显示一个盾牌/锁形图标，点按弹出 3 档权限菜单。
 *
 * 参考图里该按钮不带文字；当前档位的完整名称在菜单里以勾选表示。
 */
@Composable
fun ComposerPermissionButton(
    currentValue: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.composer_permission_control)
    // 权限切换时图标与颜色一起渐变，而不是「啪」地换成另一个图标。
    val tint by animateColorAsState(
        targetValue = if (currentValue == PERMISSION_FULL_ACCESS) {
            colorScheme.error
        } else {
            colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 220),
        label = "permissionTint",
    )
    val icon = permissionIcon(currentValue)
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = icon,
            animationSpec = tween(durationMillis = 180),
            label = "permissionIcon",
        ) { current ->
            Icon(
                imageVector = current,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 输入框右侧的模型胶囊：显示「模型名 + 思考强度」，点按依次进入
 * 「智能（思考强度）→ 模型（厂商/型号）」两级菜单。
 */
@Composable
fun ComposerModelChip(
    modelLabel: String,
    effortLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.composer_model_control)
    val text = listOfNotNull(
        modelLabel.takeIf { it.isNotBlank() },
        effortLabel?.takeIf { it.isNotBlank() },
    ).joinToString(" ")
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .widthIn(max = 150.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = MiuixIcons.Regular.ExpandLess,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/** 菜单卡片的统一外观：圆角、投影响应参考图的浮层。 */
@Composable
fun ComposerMenuCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth(0.94f)
            .widthIn(max = 340.dp),
        shape = RoundedCornerShape(22.dp),
        color = colorScheme.surface,
        contentColor = colorScheme.onSurface,
        shadowElevation = 18.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            content = content,
        )
    }
}

/** 菜单里的一行：可选图标 + 标题/说明 + 当前项勾选。 */
@Composable
private fun ComposerMenuRow(
    label: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    // 选中态用渐变过渡：切换权限/强度时勾是「滑过去」的，不是瞬移。
    val selectedProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "menuRowSelected",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else colorScheme.onSurface,
        animationSpec = tween(durationMillis = 180),
        label = "menuRowLabel",
    )
    val leadingColor by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "menuRowLeading",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = leadingColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = labelColor,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer {
                    // 0 → 1：淡入并从 0.7 倍放大到原尺寸；反向则淡出缩小。
                    alpha = selectedProgress
                    val scale = 0.7f + 0.3f * selectedProgress
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Ok,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 第一级菜单：思考强度（智能）+ 「模型」入口。
 *
 * 参考图里「智能」下面直接列 低/高/Max，「模型」行右侧是箭头，点按进入厂商/型号列表。
 */
@Composable
fun ComposerModelMenu(
    currentModelLabel: String,
    effortOptions: List<ComposerEffortOption>,
    currentEffort: String?,
    onSelectEffort: (String) -> Unit,
    onOpenModelPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    ComposerMenuCard(modifier = modifier) {
        if (effortOptions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.composer_intelligence),
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 2.dp),
            )
            effortOptions.forEach { option ->
                ComposerMenuRow(
                    label = option.label,
                    // 参考图「智能」区只列档位名，不显示说明，保持干净。
                    description = null,
                    selected = option.id == currentEffort,
                    onClick = { onSelectEffort(option.id) },
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = colorScheme.outline.copy(alpha = 0.16f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpenModelPicker)
                .padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.composer_model),
                    color = colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = currentModelLabel,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Icon(
                imageVector = MiuixIcons.Regular.ChevronForward,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 第二级菜单：搜索 + 按厂商分组的模型列表。 */
@Composable
fun ComposerModelPickerMenu(
    options: List<ComposerModelOption>,
    selection: ComposerModelSelection?,
    onSelect: (ComposerModelOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(options, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            options
        } else {
            options.filter { option ->
                option.label.contains(keyword, ignoreCase = true) ||
                    option.model.contains(keyword, ignoreCase = true) ||
                    option.providerLabel.contains(keyword, ignoreCase = true) ||
                    option.provider.contains(keyword, ignoreCase = true)
            }
        }
    }
    val groups = remember(filtered) {
        filtered.groupBy { it.provider to it.providerLabel }
    }

    ComposerMenuCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Search,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colorScheme.onSurface),
                cursorBrush = SolidColor(colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.composer_model_search_hint),
                                color = colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.composer_model_empty),
                color = colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            )
            return@ComposerMenuCard
        }

        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
            groups.forEach { (groupKey, models) ->
                item(key = "header-${groupKey.first}") {
                    Text(
                        text = groupKey.second,
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 2.dp),
                    )
                }
                items(models, key = { "${it.provider}:${it.model}" }) { option ->
                    val selected = selection != null &&
                        selection.provider == option.provider &&
                        selection.model == option.model
                    ComposerMenuRow(
                        label = option.label,
                        // 模型行只显示型号名，不显示厂商给的介绍文案（保持列表干净）。
                        description = null,
                        selected = selected,
                        onClick = { onSelect(option) },
                    )
                }
            }
        }
    }
}

/** 权限菜单：DSH 只有 3 档（只读 / 工作区内修改 / 完全权限）。 */
@Composable
fun ComposerPermissionMenu(
    options: List<ComposerPermissionOption>,
    currentValue: String?,
    onSelect: (ComposerPermissionOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    ComposerMenuCard(modifier = modifier) {
        options.forEach { option ->
            ComposerMenuRow(
                label = option.label,
                description = option.description,
                selected = option.value == currentValue,
                leadingIcon = permissionIcon(option.value),
                onClick = { onSelect(option) },
            )
        }
    }
}

/**
 * 选择「完全权限」时的风险确认弹窗（参考图 6）：
 * 勾选「我已了解风险」后才允许启用。
 */
@Composable
fun FullAccessConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var acknowledged by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = colorScheme.surface,
            contentColor = colorScheme.onSurface,
            shadowElevation = 24.dp,
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.composer_full_access_title),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "×",
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 20.sp,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, colorScheme.error, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "!",
                            color = colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.composer_full_access_body),
                        color = colorScheme.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) { acknowledged = !acknowledged },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.composer_full_access_ack),
                        color = colorScheme.onSurface,
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.composer_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (acknowledged) colorScheme.onSurface
                                else colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                            .clickable(
                                enabled = acknowledged,
                                role = Role.Button,
                                onClick = {
                                    acknowledged = false
                                    onConfirm()
                                },
                            )
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.composer_full_access_confirm),
                            color = colorScheme.surface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
