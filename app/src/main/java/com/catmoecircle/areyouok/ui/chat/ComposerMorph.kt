package com.catmoecircle.areyouok.ui.chat

/**
 * 输入框「胶囊 ↔ 卡片」展开进度在方向翻转时的保护。
 *
 * 进度本身逐帧跟随软键盘高度（当前高度 ÷ 完全展开高度）：键盘上升时卡片同步长高、
 * 下降时同步收窄，与系统键盘动画共用同一条时间线。
 *
 * 但收起动画跑到一半时用户又点回输入框，键盘会掉头向上，而系统可能还有一两帧
 * 沿用上一段收起动画的高度。照抄这几帧，卡片就会「先继续下掉一截、再升回来」，
 * 看起来像上下两段动画在打架。这里只加一条约束：键盘正在向上动画期间，进度不允许回退。
 *
 * 键盘真正开始收起后（目标高度回到 0）保护自动失效，进度继续跟随键盘，
 * 因此不会把卡片卡在半空。
 */
internal fun composerMorphOnReversal(
    raw: Float,
    previous: Float,
    imeBottom: Int,
    imeTargetBottom: Int,
): Float = if (imeTargetBottom > imeBottom && raw < previous) previous else raw

/**
 * 键盘收起完成后，至少等这么久再决定是否清焦点。
 *
 * 取值覆盖一次键盘收起动画（约 250–350ms）：收起途中点回输入框时，键盘需要时间重新出现，
 * 提前清焦点会把这次弹出掐掉。
 */
internal const val ComposerImeFocusGraceMs = 600L

/**
 * 键盘收起后是否应该释放输入框焦点。
 *
 * 只处理「用户按返回键/手势收起键盘」：键盘不可见、目标高度也是 0。
 * 键盘目标高度不为 0 说明它正在重新弹出，这时清焦点会把刚发起的弹出掐掉，
 * 表现为键盘弹出一半又掉回去的抽搐。
 */
internal fun shouldReleaseComposerFocus(
    focused: Boolean,
    imeVisible: Boolean,
    imeTargetBottom: Int,
): Boolean = focused && !imeVisible && imeTargetBottom == 0
