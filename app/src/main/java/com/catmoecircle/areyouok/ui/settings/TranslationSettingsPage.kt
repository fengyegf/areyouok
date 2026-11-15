package com.catmoecircle.areyouok.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.GatewaySettings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok

/** 翻译服务选项：id 与网关 /v1/translate 的 provider 一致。 */
private data class TranslationProviderOption(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
)

private val translationProviderOptions = listOf(
    TranslationProviderOption(
        id = GatewaySettings.DEFAULT_TRANSLATION_PROVIDER,
        labelRes = R.string.settings_translation_provider_bing,
        descriptionRes = R.string.settings_translation_provider_bing_desc,
    ),
    TranslationProviderOption(
        id = "google",
        labelRes = R.string.settings_translation_provider_google,
        descriptionRes = R.string.settings_translation_provider_google_desc,
    ),
)

/** 目标语言选项：空串表示「跟随应用」，其余存 BCP-47（与网关契约的 targets 一致）。 */
private data class TranslationTargetOption(val value: String, @StringRes val labelRes: Int)

private val translationTargetOptions = listOf(
    TranslationTargetOption("", R.string.settings_translation_target_follow),
    TranslationTargetOption("zh-CN", R.string.settings_translation_target_zh_cn),
    TranslationTargetOption("zh-TW", R.string.settings_translation_target_zh_tw),
    TranslationTargetOption("en", R.string.settings_translation_target_en),
    TranslationTargetOption("ja", R.string.settings_translation_target_ja),
)

/**
 * 思考链翻译子页：总开关 + 翻译服务 + 目标语言 + 隐私提示。
 *
 * 这里只存偏好（由 ChatScreen 写进 [GatewaySettings]）；真正的请求由
 * `GatewayRepository.translateThinking` 在用户展开思考行时发起。
 * 关掉总开关时下面两组置灰但仍可见：能看到有哪些选项，只是暂时不生效。
 */
@Composable
internal fun SettingsTranslationPage(
    enabled: Boolean,
    provider: String,
    target: String,
    onEnabledChange: (Boolean) -> Unit,
    onProviderChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    SettingsPageScaffold(title = stringResource(R.string.settings_translation_title)) {
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_translation_enable_title),
                description = stringResource(R.string.settings_translation_enable_desc),
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
        }

        Spacer(Modifier.height(32.dp))

        SettingsGroup(title = stringResource(R.string.settings_translation_provider_section)) {
            translationProviderOptions.forEach { option ->
                TranslationOptionRow(
                    title = stringResource(option.labelRes),
                    description = stringResource(option.descriptionRes),
                    selected = provider == option.id,
                    enabled = enabled,
                    onClick = { onProviderChange(option.id) },
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        SettingsGroup(title = stringResource(R.string.settings_translation_target_section)) {
            translationTargetOptions.forEach { option ->
                TranslationOptionRow(
                    title = stringResource(option.labelRes),
                    description = null,
                    selected = target == option.value,
                    enabled = enabled,
                    onClick = { onTargetChange(option.value) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_translation_privacy_hint),
            color = colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** 单选行：标题（+ 可选说明），选中且可用时右侧打勾；与「工作步骤显示」的行样式一致。 */
@Composable
private fun TranslationOptionRow(
    title: String,
    description: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = title }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.45f),
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    color = colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f),
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        if (selected && enabled) {
            Icon(
                imageVector = MiuixIcons.Regular.Ok,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Spacer(Modifier.size(20.dp))
        }
    }
}
