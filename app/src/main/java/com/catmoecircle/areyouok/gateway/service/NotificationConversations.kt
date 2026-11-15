package com.catmoecircle.areyouok.gateway.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.catmoecircle.areyouok.MainActivity
import com.catmoecircle.areyouok.R

/**
 * 通知气泡的「会话」身份，按 Google 官方示例 android/socialite 实现。
 *
 * Android 11+（targetSdk 30+）要求气泡通知满足「对话」形态：
 * - 引用一条已发布的 long-lived 动态快捷方式，带 Person 与 LocusId；
 * - 通知用 MessagingStyle，并 setShortcutId / addPerson / setLocusId。
 * 只挂 BubbleMetadata 时系统只会显示成普通通知。
 *
 * 只有「当前对话的常驻气泡」和「审批 / 提问通知」会调用 [publish]；任务完成通知是普通
 * 通知，不发布会话快捷方式——long-lived 快捷方式会永久留在系统「通知类别 / 对话」区，
 * 按每个会话标题发一条会让调试会话的标题长期残留。为此这里额外提供 [keepSessionIds] +
 * [prune]：服务启动时按当前活跃会话重算，多余的快捷方式直接删掉。
 */
object NotificationConversations {

    /** 系统识别「对话」类快捷方式的 category。 */
    private const val CATEGORY_CONVERSATION = "android.shortcut.conversation"

    /** 会话快捷方式的 id 前缀：清扫时只动这一类 id，不碰别人的快捷方式。 */
    private const val SHORTCUT_PREFIX = "session:"

    /** 系统「通知类别 / 对话」区最多保留几条会话快捷方式（超出部分启动时清掉）。 */
    const val MAX_LONG_LIVED_SHORTCUTS = 8

    /** 会话对应的动态快捷方式 id。 */
    fun shortcutId(sessionId: String): String = SHORTCUT_PREFIX + sessionId

    /** 会话的内容 URI：快捷方式与气泡 Activity 都靠它识别是哪个会话。 */
    fun contentUri(sessionId: String): Uri = Uri.parse("dshgw://session/" + Uri.encode(sessionId))

    /**
     * 发布（或更新）某个会话的快捷方式，并返回写进通知用的会话信息。
     *
     * 对齐 socialite：setLocusId + setActivity + long-lived + Person(带图标)，
     * Intent 指向宿主 Activity（MainActivity），系统据此把它当对话。
     */
    fun publish(context: Context, sessionId: String?, title: String?): Conversation? {
        val safeSessionId = sessionId?.takeIf { it.isNotBlank() } ?: return null
        val label = title?.takeIf { it.isNotBlank() } ?: safeSessionId.take(8)
        val id = shortcutId(safeSessionId)
        return runCatching {
            val icon = IconCompat.createWithResource(context, R.drawable.ic_bubble_gateway)
            val person = Person.Builder()
                .setName(label)
                .setIcon(icon)
                .setKey(safeSessionId)
                .setImportant(true)
                .build()
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(contentUri(safeSessionId))
                .putExtra(GatewayService.EXTRA_SESSION_ID, safeSessionId)
            val shortcut = ShortcutInfoCompat.Builder(context, id)
                .setLocusId(LocusIdCompat(id))
                .setActivity(ComponentName(context, MainActivity::class.java))
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(icon)
                .setIntent(intent)
                .setLongLived(true)
                .setCategories(setOf(CATEGORY_CONVERSATION))
                .setPersons(arrayOf(person))
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            Conversation(shortcutId = id, person = person)
        }.getOrNull()
    }

    /**
     * 计算「值得保留」的会话 id 列表（纯函数，便于测试）。
     *
     * 优先级：当前选中会话 → 有待处理审批/提问的会话 → 会话列表顺序；去重后按
     * [maxKeep] 截断，这样无论网关有多少会话，系统里最多只留 [MAX_LONG_LIVED_SHORTCUTS] 条。
     */
    fun keepSessionIds(
        selectedSessionId: String?,
        pendingSessionIds: Collection<String?>,
        sessionIds: Collection<String>,
        maxKeep: Int = MAX_LONG_LIVED_SHORTCUTS,
    ): List<String> {
        if (maxKeep <= 0) return emptyList()
        val kept = LinkedHashSet<String>()

        fun add(id: String?) {
            val safe = id?.takeIf { it.isNotBlank() } ?: return
            if (kept.size < maxKeep) kept += safe
        }

        add(selectedSessionId)
        pendingSessionIds.forEach { add(it) }
        sessionIds.forEach { add(it) }
        return kept.toList()
    }

    /**
     * 清扫不再活跃的会话快捷方式（服务启动时调用）。
     *
     * 只保留 [keepSessionIds] 里的会话（最多 [maxKeep] 条），其余 id 以 [SHORTCUT_PREFIX]
     * 开头的动态 / long-lived 快捷方式全部通过 [ShortcutManagerCompat.removeLongLivedShortcuts]
     * 删除；不是本应用会话的快捷方式不动。
     */
    fun prune(
        context: Context,
        keepSessionIds: Collection<String>,
        maxKeep: Int = MAX_LONG_LIVED_SHORTCUTS,
    ) {
        if (maxKeep <= 0) return
        val keep = keepSessionIds
            .filter { it.isNotBlank() }
            .take(maxKeep)
            .map { shortcutId(it) }
            .toSet()
        runCatching {
            val existing = ShortcutManagerCompat.getShortcuts(
                context,
                ShortcutManagerCompat.FLAG_MATCH_DYNAMIC,
            )
            val stale = existing.filter { shortcut -> shortcut.id.startsWith(SHORTCUT_PREFIX) && shortcut.id !in keep }
            if (stale.isNotEmpty()) {
                ShortcutManagerCompat.removeLongLivedShortcuts(context, stale.map { it.id })
            }
        }
    }

    /** 一条气泡通知需要的会话信息：快捷方式 id + Person。 */
    data class Conversation(
        val shortcutId: String,
        val person: Person,
    )
}
