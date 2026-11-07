package com.catmoecircle.areyouok.gateway.transport

/**
 * 事件去重：`seq` 单调递增 + `id` 唯一（交接文档 §7.1「幂等」）。
 *
 * 规则：
 * - 同一 `id` 只接受一次（重连补发、多路重复都会命中）；
 * - `seq` 不大于已应用序号的事件丢弃；
 * - 允许 `seq` 缺省（部分事件只带 id），此时只按 id 去重。
 *
 * 该类无 Android 依赖，JVM 单测直接覆盖。
 */
class SeqTracker(
    initialSeq: Long = 0L,
    private val maxSeenIds: Int = 1024,
) {
    private var lastSeq: Long = initialSeq
    private val seenIds = LinkedHashSet<String>()

    @Synchronized
    fun lastAppliedSeq(): Long = lastSeq

    @Synchronized
    fun shouldApply(seq: Long?, id: String?): Boolean {
        if (id != null && !seenIds.add(id)) return false
        trimSeenIds()
        if (seq != null) {
            if (seq <= lastSeq) return false
            lastSeq = seq
        }
        return true
    }

    /** `ready` 帧里的服务端当前序号，仅用于展示/缺口判断，不推进去重水位。 */
    @Synchronized
    fun serverSeq(): Long = lastSeq

    @Synchronized
    fun reset(seq: Long = 0L) {
        lastSeq = seq
        seenIds.clear()
    }

    private fun trimSeenIds() {
        if (seenIds.size <= maxSeenIds) return
        val iterator = seenIds.iterator()
        while (seenIds.size > maxSeenIds && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }
}

/**
 * 重连退避：0.5→1→2→4→8→15→30s，带 ±30% 抖动（交接文档 §7.1）。
 */
object ReconnectPolicy {

    private val STEPS_MS = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)

    /** 连接稳定超过该时长后重置退避（交接文档：稳定 30s 后重置）。 */
    const val STABLE_RESET_MS = 30_000L

    /** 心跳：客户端每 15s 发一次 app 级 ping。 */
    const val PING_INTERVAL_MS = 15_000L

    /** 45s 没有任何帧判定连接已死。 */
    const val IDLE_TIMEOUT_MS = 45_000L

    fun baseDelayMs(attempt: Int): Long =
        STEPS_MS[(attempt - 1).coerceIn(0, STEPS_MS.lastIndex)]

    /** [jitter] 返回 [-1, 1] 之间的随机数，便于单测注入固定值。 */
    fun delayMs(attempt: Int, jitter: Double = (Math.random() * 2.0 - 1.0)): Long {
        val base = baseDelayMs(attempt)
        val factor = 1.0 + 0.3 * jitter.coerceIn(-1.0, 1.0)
        return (base * factor).toLong().coerceAtLeast(250L)
    }
}
