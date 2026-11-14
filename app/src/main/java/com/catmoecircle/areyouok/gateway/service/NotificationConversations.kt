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
 */
object NotificationConversations {

    /** 系统识别「对话」类快捷方式的 category。 */
    private const val CATEGORY_CONVERSATION = "android.shortcut.conversation"

    /** 会话对应的动态快捷方式 id。 */
    fun shortcutId(sessionId: String): String = "session:" + sessionId

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

    /** 一条气泡通知需要的会话信息：快捷方式 id + Person。 */
    data class Conversation(
        val shortcutId: String,
        val person: Person,
    )
}
