package com.catmoecircle.areyouok.ui.settings

import androidx.annotation.StringRes
import com.catmoecircle.areyouok.R

/**
 * 「工作步骤展示」详细程度：对齐 DSH 桌面客户端的四档策略。
 *
 * 前三档共用同一套「工作步骤分组」：
 * 1. 一轮（user → 下一条 user 之间）里连续的思考 / 工具调用收成一个分组，
 *    助手回复把分组截断——回答、调工具、再回答、再调工具会出现多个分组；
 * 2. 分组默认收起，标题一行显示状态：运行中「正在运行命令 · git status」，
 *    结束后「已读取文件、执行了命令」；
 * 3. 点按分组才展开组内每一行，行内再点按看参数与输出。
 *
 * [EXPANDED] 不再分组：思考、工具按普通消息逐条展示，但每行自身仍默认折叠。
 *
 * 档位差异（与官方 compact / standard / detailed / verbose 一致）：
 * - [CONCISE]  运行中也收起，标题只有「正在调用工具」等活动名，不带明细；
 * - [STANDARD] 运行中也收起，标题带一行明细（工具名 / 命令 / 路径）；
 * - [DETAILED] 运行中逐条实时展示，回答结束后再折叠成组；
 * - [EXPANDED] 不分组，逐条展示。
 *
 * 持久化由 [com.catmoecircle.areyouok.gateway.GatewaySettings] 负责，
 * 存的是枚举 [name]（新增取值时旧版本会回退到 [STANDARD]）。
 */
enum class WorkStepDisplay(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    /** 是否把工作步骤收进「工作步骤」分组（「完全展开」为 false）。 */
    val groupsWorkSteps: Boolean,
    /** 运行中也分组并以实时标题收起（「详细」运行中逐条展示）。 */
    val groupsWhileRunning: Boolean,
    /** 运行中的标题是否带一行明细（工具名 / 命令 / 路径）。 */
    val showsLiveDetail: Boolean,
    /** 展开分组后，组内折叠行是否露出工具名或思考首行。 */
    val showsRowHint: Boolean,
) {
    CONCISE(
        labelRes = R.string.work_step_display_concise,
        descriptionRes = R.string.work_step_display_concise_desc,
        groupsWorkSteps = true,
        groupsWhileRunning = true,
        showsLiveDetail = false,
        showsRowHint = false,
    ),
    STANDARD(
        labelRes = R.string.work_step_display_standard,
        descriptionRes = R.string.work_step_display_standard_desc,
        groupsWorkSteps = true,
        groupsWhileRunning = true,
        showsLiveDetail = true,
        showsRowHint = true,
    ),
    DETAILED(
        labelRes = R.string.work_step_display_detailed,
        descriptionRes = R.string.work_step_display_detailed_desc,
        groupsWorkSteps = true,
        groupsWhileRunning = false,
        showsLiveDetail = true,
        showsRowHint = true,
    ),
    EXPANDED(
        labelRes = R.string.work_step_display_expanded,
        descriptionRes = R.string.work_step_display_expanded_desc,
        groupsWorkSteps = false,
        groupsWhileRunning = false,
        showsLiveDetail = false,
        showsRowHint = true,
    ),
    ;

    companion object {
        val DEFAULT: WorkStepDisplay = STANDARD

        /** 从 SharedPreferences 里存的枚举名还原；未知值回退到 [DEFAULT]。 */
        fun fromStorage(raw: String?): WorkStepDisplay =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
