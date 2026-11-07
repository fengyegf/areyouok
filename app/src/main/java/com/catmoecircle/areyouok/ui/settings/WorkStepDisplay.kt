package com.catmoecircle.areyouok.ui.settings

import androidx.annotation.StringRes
import com.catmoecircle.areyouok.R

/**
 * 「工作步骤显示」详细程度：控制聊天里思考链 / 工具调用（工作步骤）的展示方式。
 *
 * 前三档共用同一套两层折叠：
 * 1. 一轮（user → 下一条 user 之间）完成后，思考链、工具调用和中间说明整体折叠成
 *    一条「工作步骤 · N 项」分组，点按分组才展开组内每一行；
 * 2. 组内每行（思考过程 / 工具调用）仍是独立折叠项，再点按才看完整内容。
 *
 * [EXPANDED] 不再把工作步骤收进分组：思考、工具与中间说明按普通对话逐条展示，
 * 但每行自身仍默认折叠，点按才看参数与输出。
 *
 * 档位决定折叠行「露出多少」：
 * - [CONCISE]  只露出「思考过程 / 工具调用」标签，不显示任何摘要；
 * - [STANDARD] 在简洁基础上露出思考开头一行与工具名；
 * - [DETAILED] 在标准基础上再露出工具输出首行（对齐上一版「标准」）；
 * - [EXPANDED] 同样露出工具名与输出首行，但不折叠成工作步骤分组。
 *
 * 持久化由 [com.catmoecircle.areyouok.gateway.GatewaySettings] 负责，
 * 存的是枚举 [name]（新增取值时旧版本会回退到 [STANDARD]）。
 */
enum class WorkStepDisplay(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    /** 折叠行是否露出摘要：思考开头一行 / 工具名。 */
    val showsRowHint: Boolean,
    /** 折叠行是否露出工具输出首行。 */
    val showsOutputPreview: Boolean,
    /** 完成后是否把工作步骤折叠成一条「工作步骤 · N 项」分组。 */
    val groupsWorkSteps: Boolean,
) {
    CONCISE(
        labelRes = R.string.work_step_display_concise,
        descriptionRes = R.string.work_step_display_concise_desc,
        showsRowHint = false,
        showsOutputPreview = false,
        groupsWorkSteps = true,
    ),
    STANDARD(
        labelRes = R.string.work_step_display_standard,
        descriptionRes = R.string.work_step_display_standard_desc,
        showsRowHint = true,
        showsOutputPreview = false,
        groupsWorkSteps = true,
    ),
    DETAILED(
        labelRes = R.string.work_step_display_detailed,
        descriptionRes = R.string.work_step_display_detailed_desc,
        showsRowHint = true,
        showsOutputPreview = true,
        groupsWorkSteps = true,
    ),
    EXPANDED(
        labelRes = R.string.work_step_display_expanded,
        descriptionRes = R.string.work_step_display_expanded_desc,
        showsRowHint = true,
        showsOutputPreview = true,
        groupsWorkSteps = false,
    ),
    ;

    companion object {
        val DEFAULT: WorkStepDisplay = STANDARD

        /** 从 SharedPreferences 里存的枚举名还原；未知值回退到 [DEFAULT]。 */
        fun fromStorage(raw: String?): WorkStepDisplay =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
