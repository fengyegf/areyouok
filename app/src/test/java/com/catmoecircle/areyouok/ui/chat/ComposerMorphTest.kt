package com.catmoecircle.areyouok.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入框展开进度在「收起途中又点回输入框」时不能继续倒退，焦距释放也不能抢走刚发起的弹出。 */
class ComposerMorphTest {

    private val tolerance = 0.0001f

    @Test
    fun `rising keyboard holds progress instead of letting it fall again`() {
        // 收起动画跑到一半（0.42），键盘开始回升但系统还上报了一帧 0.30：保持 0.42。
        val morph = composerMorphOnReversal(
            raw = 0.30f,
            previous = 0.42f,
            imeBottom = 300,
            imeTargetBottom = 900,
        )

        assertEquals(0.42f, morph, tolerance)
    }

    @Test
    fun `rising keyboard follows progress once it moves forward`() {
        val morph = composerMorphOnReversal(
            raw = 0.61f,
            previous = 0.42f,
            imeBottom = 549,
            imeTargetBottom = 900,
        )

        assertEquals(0.61f, morph, tolerance)
    }

    @Test
    fun `collapsing keyboard follows progress without holding`() {
        // 真正收起时（目标高度 0）不做保护，卡片继续跟着键盘回落。
        val morph = composerMorphOnReversal(
            raw = 0.30f,
            previous = 0.42f,
            imeBottom = 300,
            imeTargetBottom = 0,
        )

        assertEquals(0.30f, morph, tolerance)
    }

    @Test
    fun `settled keyboard follows progress without holding`() {
        val morph = composerMorphOnReversal(
            raw = 0.55f,
            previous = 0.42f,
            imeBottom = 500,
            imeTargetBottom = 500,
        )

        assertEquals(0.55f, morph, tolerance)
    }

    @Test
    fun `focus is released only when the keyboard is really gone`() {
        assertTrue(shouldReleaseComposerFocus(focused = true, imeVisible = false, imeTargetBottom = 0))
        assertFalse(shouldReleaseComposerFocus(focused = true, imeVisible = false, imeTargetBottom = 900))
        assertFalse(shouldReleaseComposerFocus(focused = true, imeVisible = true, imeTargetBottom = 0))
        assertFalse(shouldReleaseComposerFocus(focused = false, imeVisible = false, imeTargetBottom = 0))
    }
}
