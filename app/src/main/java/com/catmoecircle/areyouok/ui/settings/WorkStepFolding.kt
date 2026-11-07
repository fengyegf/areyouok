package com.catmoecircle.areyouok.ui.settings

import androidx.annotation.StringRes
import com.catmoecircle.areyouok.R

/** 「工作步骤收起时机」：完成一轮后什么时候把工作步骤折叠起来。 */
enum class WorkStepCollapseTiming(
    @StringRes val labelRes: Int,
) {
    /** 回答结束后立即收起（与桌面端默认一致）。 */
    COMPLETION(R.string.work_step_collapse_completion),

    /** 等到下一次有新消息时再收起；最新一轮保持逐条展开。 */
    NEXT_INPUT(R.string.work_step_collapse_next_input),
    ;

    companion object {
        val DEFAULT: WorkStepCollapseTiming = COMPLETION

        /** 从 SharedPreferences 里存的枚举名还原；未知值回退到 [DEFAULT]。 */
        fun fromStorage(raw: String?): WorkStepCollapseTiming =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/** 「收起后显示的回复」：工作步骤收起后，哪些助手回复仍留在分组外可见。 */
enum class WorkStepFoldedReplies(
    @StringRes val labelRes: Int,
) {
    /** 所有助手回复都保持可见，只有思考 / 工具步骤进入折叠分组。 */
    ALL(R.string.work_step_folded_replies_all),

    /** 只保留本轮最后一条助手回复，其余中间回复收进折叠分组。 */
    FINAL(R.string.work_step_folded_replies_final),
    ;

    companion object {
        val DEFAULT: WorkStepFoldedReplies = ALL

        /** 从 SharedPreferences 里存的枚举名还原；未知值回退到 [DEFAULT]。 */
        fun fromStorage(raw: String?): WorkStepFoldedReplies =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
