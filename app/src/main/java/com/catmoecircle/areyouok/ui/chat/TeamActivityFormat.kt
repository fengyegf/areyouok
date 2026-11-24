package com.catmoecircle.areyouok.ui.chat

/**
 * 团队活动面板的纯展示计算（不依赖 Compose，可在 JVM 单测里直接验证）。
 *
 * 面板打开期间每秒 tick 一次，用 [elapsed] 把「开始时间 / 最后活动时间」算成
 * `12s` / `01:23` / `1:02:03` 这样的短文案。
 */
object TeamActivityFormat {

    /** 时间缺失时的占位。 */
    const val PLACEHOLDER = "--"

    /**
     * 时长文案。
     *
     * @param now 当前时间（面板每秒 tick 一次）
     * @param startedAt 开始时间；null 时返回 [PLACEHOLDER]
     * @param finishedAt 结算时间；非 null 时按「开始 → 结算」算固定时长（不再随时间增长）
     */
    fun elapsed(now: Long, startedAt: Long?, finishedAt: Long? = null): String {
        val start = startedAt ?: return PLACEHOLDER
        val end = finishedAt ?: now
        return formatDuration(end - start)
    }

    /**
     * 把毫秒格式化成短文案：
     * - 不足 1 分钟：`12s`
     * - 不足 1 小时：`01:23`
     * - 1 小时以上：`1:02:03`
     *
     * 负数（时钟回拨 / 服务端时间略超前）按 0 处理，不出现 `-3s` 这种文案。
     */
    fun formatDuration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "%02d:%02d".format(seconds / 60, seconds % 60)
            else -> "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        }
    }
}
