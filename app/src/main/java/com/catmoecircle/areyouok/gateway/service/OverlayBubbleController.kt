package com.catmoecircle.areyouok.gateway.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.catmoecircle.areyouok.R
import com.catmoecircle.areyouok.gateway.protocol.QuestionAnswer
import com.catmoecircle.areyouok.ui.overlay.OverlayBubbleHost
import kotlin.math.roundToInt

/**
 * 后台悬浮气泡的宿主：WindowManager + ComposeView，把 [OverlayViewState] 渲染成贴边气泡。
 *
 * 职责边界（刻意不依赖 repository / Service / Activity）：
 * - 只关心「窗口」，不关心业务：审批、提问、发送都通过 [Callbacks] 交给上层；
 * - [attach] 时检查 `Settings.canDrawOverlays`，没权限就静默什么都不做（不崩溃、不弹授权框）；
 * - [render] 任意线程安全：内部切主线程；`enabled = false` 或 App 在前台时移除窗口但保留状态；
 * - 位置：收起态默认右侧、垂直 38%，可上下（左右也可以）拖动，松手吸附最近侧边；
 *   点开后窗口用位移动画滑到屏幕顶部、左右铺开成面板（高度随内容），收起再滑回胶囊；
 *   比例与侧边存进自己的 SharedPreferences（不碰 GatewaySettings）；
 * - 输入法：默认 `FLAG_NOT_FOCUSABLE` 不抢 App 焦点；点「回复 / 继续回复」时临时去掉，
 *   showSoftInput，发送 / 收起后恢复。OEM 不给 overlay 输入法时，界面里有「在 App 中回复」兜底。
 *
 * 线程模型：公开方法可任意线程调用，所有内部状态只在主线程读写。
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class OverlayBubbleController(
    context: Context,
    private val callbacks: Callbacks,
) {

    /** 气泡上的动作全部回给上层；控制器自己不知道 repository 的存在。 */
    interface Callbacks {
        fun onAllowOnce(eventId: String)

        fun onReject(eventId: String)

        fun onAnswerQuestion(eventId: String, answers: List<QuestionAnswer>)

        /** @return 是否已受理（乐观返回即可；失败由上层提示）。 */
        fun onSendMessage(sessionId: String, text: String, mode: String): Boolean

        fun onOpenSession(sessionId: String)

        fun onOpenApp()
    }

    // ------------------------------------------------------------ 基础

    private val appContext: Context = context.applicationContext ?: context

    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density: Float = appContext.resources.displayMetrics.density

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Compose 侧的数据源；每次 publishUi() 整体替换。 */
    private val uiState = mutableStateOf(OverlayUiState())

    private var attached: Boolean = false

    private var host: WindowHost? = null

    /** 展开 / 收起时的窗口过渡动画（同一时刻最多一个）。 */
    private var windowAnimator: ValueAnimator? = null

    /** 收起态测量到的胶囊尺寸：既是展开动画的起点，也是收起动画的终点。 */
    private var lastCollapsedWidthPx: Int = 0
    private var lastCollapsedHeightPx: Int = 0

    /** 上层最近一次给的状态（隐藏时也保留，用于重新显示）。 */
    private var state = OverlayViewState()

    private var expanded: Boolean = false

    /** 新提醒计数器：只用来驱动收起态气泡的「弹一下」，不参与展开决策。 */
    private var attention: Int = 0

    /** 窗口此刻是否可聚焦（输入法可能弹出）。 */
    private var focused: Boolean = false

    /** 本地已读的完成提醒 key（`sessionId#time`），跨进程保留，避免重复弹。 */
    private val readCompletions: MutableSet<String> = LinkedHashSet()

    private var side: AnchorSide = AnchorSide.END

    /** 气泡顶边相对可用高度的比例（0..1），默认约 38%。 */
    private var yRatio: Float = DEFAULT_Y_RATIO

    /** 拖动中的自由坐标（松手后清空、改由 side / yRatio 决定）。 */
    private var dragX: Float? = null

    private var dragY: Float? = null

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            applyLayout()
        }

        override fun onLowMemory() = Unit
    }

    init {
        readCompletions.addAll(prefs.getStringSet(KEY_READ_COMPLETIONS, emptySet()).orEmpty())
        side = if (prefs.getString(KEY_SIDE, null) == SIDE_START) AnchorSide.START else AnchorSide.END
        yRatio = prefs.getFloat(KEY_Y_RATIO, DEFAULT_Y_RATIO)
    }

    // ------------------------------------------------------------ 公开 API

    /**
     * 开始接管悬浮窗。
     *
     * 只置位 + 重放最近一次状态；真正 addView 发生在 [render] 认为应该显示时。
     * 没有悬浮窗权限时不创建任何窗口（不崩溃）。
     */
    fun attach() = runOnMain {
        if (attached) return@runOnMain
        attached = true
        runCatching { appContext.registerComponentCallbacks(componentCallbacks) }
        // 悬浮层是「锦上添花」：任何窗口/Compose 异常都不能把前台服务（以及整个 App）带崩。
        runCatching { renderInternal(state) }
            .onFailure { android.util.Log.e(TAG, "overlay attach failed", it) }
    }

    /** 释放窗口与 Compose 生命周期（Service 销毁时调用）。之后可以再次 [attach]。 */
    fun detach() = runOnMain {
        attached = false
        cancelWindowAnimation()
        focused = false
        expanded = false
        dragX = null
        dragY = null
        runCatching { appContext.unregisterComponentCallbacks(componentCallbacks) }
        destroyHost()
        uiState.value = OverlayUiState(state = state, expanded = false, inputFocused = false)
    }

    /**
     * 用最新快照刷新气泡。任意线程安全。
     *
     * 是否显示、显示哪些卡片、角标多少都由 [OverlayBubbleState] 的纯函数决定。
     */
    fun render(state: OverlayViewState) {
        runOnMain {
            runCatching { renderInternal(state) }
                .onFailure { android.util.Log.e(TAG, "overlay render failed", it) }
        }
    }

    // ------------------------------------------------------------ 渲染决策

    private fun renderInternal(next: OverlayViewState) {
        val previous = state
        state = next
        if (!attached) return

        if (!OverlayBubbleState.shouldShow(next)) {
            releaseInputFocus()
            expanded = false
            hideWindow()
            publishUi()
            return
        }

        val wasExpanded = expanded
        val cards = OverlayBubbleState.cards(next, readCompletions)
        val becameVisible = !OverlayBubbleState.shouldShow(previous)
        // 新提醒只让气泡弹一下；跑完一轮绝不主动展开面板，避免挡住用户正在用的界面。
        if (OverlayBubbleState.hasFreshEvent(previous, next, readCompletions)) {
            attention += 1
        }
        // 只有「需要用户处理」的审批 / 提问才自动展开：新到的，或者刚切回后台时已经挂着的。
        val previousPending = previous.pending.map { it.eventId }.toSet()
        val newPending = next.pending.any { it.eventId !in previousPending }
        if (cards.isNotEmpty() && (newPending || (becameVisible && next.pending.isNotEmpty()))) {
            expanded = true
        }

        showWindow()
        // 窗口可能已经挂着（从收起变展开），必须重新算尺寸/坐标。
        applyWindowFlags()
        publishUi()
        // 收起 → 展开是一次「滑到屏幕顶部」的过渡；其余情况（同状态下刷新内容）直接落位。
        if (expanded && !wasExpanded) {
            animateToExpanded()
        } else {
            applyLayout()
        }
    }

    private fun publishUi() {
        val visible = attached && OverlayBubbleState.shouldShow(state)
        val cards = if (visible) OverlayBubbleState.cards(state, readCompletions) else emptyList()
        val badge = if (visible) OverlayBubbleState.badge(state, readCompletions) else OverlayBadge.NONE
        val showExpanded = visible && expanded
        uiState.value = OverlayUiState(
            state = state,
            cards = cards,
            badge = badge,
            expanded = showExpanded,
            inputFocused = focused && showExpanded,
            attention = attention,
            panelWidthPx = if (showExpanded) expandedWidthPx(screenBox()) else 0,
        )
    }

    // ------------------------------------------------------------ 窗口

    private fun showWindow() {
        if (!canDrawOverlays()) {
            hideWindow()
            return
        }
        // ComposeView / 生命周期宿主创建失败（厂商限制、savedstate 状态异常…）时
        // 本轮不显示即可，不能让异常冒到主线程。
        val current = runCatching { ensureHost() }.getOrNull()
        if (current == null) {
            android.util.Log.w(TAG, "overlay host unavailable")
            return
        }
        if (current.added) return

        current.owner.resume()
        applyWindowFlags()
        applyLayout()
        val ok = runCatching { windowManager.addView(current.composeView, current.params) }.isSuccess
        current.added = ok
        if (!ok) {
            destroyHost()
            return
        }
        // 窗口刚挂上去时还没量过大小：量完后再贴一次边（收起态宽度由文字决定）。
        current.composeView.post { if (attached && host === current) applyLayout() }
        if (focused) syncIme(true)
    }

    private fun hideWindow() {
        cancelWindowAnimation()
        val current = host ?: return
        if (!current.added) return
        runCatching { windowManager.removeView(current.composeView) }
        current.added = false
        current.owner.pause()
    }

    private fun destroyHost() {
        hideWindow()
        host?.owner?.destroy()
        host = null
    }

    private fun ensureHost(): WindowHost {
        host?.let { return it }
        val owner = OverlayLifecycleOwner()
        val view = ComposeView(ContextThemeWrapper(appContext, R.style.Theme_AreYouOK))
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner))
        owner.create()
        view.setContent {
            val ui = uiState.value
            OverlayBubbleHost(
                ui = ui,
                onTogglePanel = { togglePanel() },
                onCollapsePanel = { collapsePanel() },
                onDragBy = { dx, dy -> dragBy(dx, dy) },
                onDragEnd = { endDrag() },
                onAllowOnce = { eventId -> allowOnce(eventId) },
                onReject = { eventId -> reject(eventId) },
                onAnswerQuestion = { eventId, answers -> answerQuestion(eventId, answers) },
                onSendMessage = { sessionId, text, mode -> sendMessage(sessionId, text, mode) },
                onOpenSession = { sessionId -> openSession(sessionId) },
                onOpenApp = { openApp() },
                onInputFocusChanged = { focus -> setInputFocused(focus) },
            )
        }
        val params = newParams()
        return WindowHost(owner = owner, composeView = view, params = params).also { created ->
            host = created
            // 收起态宽度由文案决定：内容换行 / 文字变化后重新贴边。
            view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, _, oldRight, _ ->
                if (expanded || !created.added) return@addOnLayoutChangeListener
                val width = right - left
                if (width <= 0) return@addOnLayoutChangeListener
                lastCollapsedWidthPx = width
                lastCollapsedHeightPx = bottom - top
                if (width != oldRight - oldLeft && windowAnimator == null) applyLayout()
            }
        }
    }

    private fun newParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.setTitle("DSH 气泡")
        return params
    }

    private fun baseFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private fun canDrawOverlays(): Boolean =
        runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)

    // ------------------------------------------------------------ 布局 / 拖动

    private fun applyWindowFlags() {
        val current = host ?: return
        val focusable = focused && expanded
        current.params.flags = if (focusable) {
            baseFlags()
        } else {
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        current.params.softInputMode = if (focusable) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        if (current.added) {
            runCatching { windowManager.updateViewLayout(current.composeView, current.params) }
        }
    }

    private fun applyLayout() {
        val current = host ?: return
        // 动画期间由动画自己逐帧写 params，不要在这里把它拽回终点。
        if (windowAnimator != null) return
        val screen = screenBox()
        val params = current.params
        params.height = WindowManager.LayoutParams.WRAP_CONTENT

        if (expanded) {
            params.width = expandedWidthPx(screen)
            params.x = expandedX(screen, params.width)
            params.y = expandedTop(screen)
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = (dragX ?: sideX(screen, collapsedWidthPx).toFloat()).roundToInt()
            params.y = collapsedY(screen)
        }

        if (current.added) {
            runCatching { windowManager.updateViewLayout(current.composeView, params) }
        }
    }

    // ------------------------------------------------------------ 过渡动画

    /** 展开：从胶囊所在位置滑到屏幕顶部，同时把窗口横向铺开。 */
    private fun animateToExpanded() {
        val current = host ?: return
        if (!current.added || current.composeView.width <= 0) {
            applyLayout()
            return
        }
        val screen = screenBox()
        val width = expandedWidthPx(screen)
        animateWindow(
            targetX = expandedX(screen, width),
            targetY = expandedTop(screen),
            targetWidth = width,
            durationMs = EXPAND_DURATION_MS,
        )
    }

    /**
     * 窗口矩形过渡：只动 x / y / 宽度，高度继续随内容（WRAP_CONTENT）。
     *
     * 面板内容在 Compose 里按最终宽度排版（见 [com.catmoecircle.areyouok.gateway.service.OverlayUiState.panelWidthPx]），
     * 所以宽度渐变时不会每帧重新折行，只是被窗口边缘裁掉一部分。
     */
    private fun animateWindow(
        targetX: Int,
        targetY: Int,
        targetWidth: Int,
        durationMs: Long,
        onEnd: (() -> Unit)? = null,
    ) {
        val current = host ?: return
        if (!current.added) {
            onEnd?.invoke()
            return
        }
        cancelWindowAnimation()
        val params = current.params
        val startX = params.x
        val startY = params.y
        val startWidth = current.composeView.width.coerceAtLeast(1)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                params.x = (startX + (targetX - startX) * fraction).roundToInt()
                params.y = (startY + (targetY - startY) * fraction).roundToInt()
                params.width = (startWidth + (targetWidth - startWidth) * fraction)
                    .roundToInt()
                    .coerceAtLeast(1)
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                runCatching { windowManager.updateViewLayout(current.composeView, params) }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (windowAnimator === animation) windowAnimator = null
                    if (!cancelled) runCatching { onEnd?.invoke() }
                }
            })
        }
        windowAnimator = animator
        animator.start()
    }

    private fun cancelWindowAnimation() {
        val animator = windowAnimator ?: return
        windowAnimator = null
        runCatching { animator.cancel() }
    }

    private fun dragBy(dx: Float, dy: Float) {
        val current = host ?: return
        if (!current.added || expanded || windowAnimator != null) return
        val screen = screenBox()
        val width = collapsedWidthPx
        val height = collapsedHeightPx
        val currentX = dragX ?: sideX(screen, width).toFloat()
        val currentY = dragY ?: (screen.top + screen.height * yRatio)
        val minX = screen.left.toFloat()
        val maxX = (screen.right - width).toFloat().coerceAtLeast(minX)
        val minY = screen.top.toFloat()
        val maxY = (screen.bottom - height).toFloat().coerceAtLeast(minY)
        dragX = (currentX + dx).coerceIn(minX, maxX)
        dragY = (currentY + dy).coerceIn(minY, maxY)
        applyLayout()
    }

    private fun endDrag() {
        val screen = screenBox()
        dragY?.let { y ->
            if (screen.height > 0) {
                yRatio = ((y - screen.top) / screen.height).coerceIn(0.02f, 0.92f)
            }
        }
        dragX?.let { x ->
            side = if (x + collapsedWidthPx / 2f < screen.left + screen.width / 2f) {
                AnchorSide.START
            } else {
                AnchorSide.END
            }
        }
        dragX = null
        dragY = null
        persistAnchor()
        applyWindowFlags()
        applyLayout()
        publishUi()
    }

    private fun sideX(screen: ScreenBox, width: Int): Int = when (side) {
        AnchorSide.START -> screen.left + marginPx
        AnchorSide.END -> screen.right - width - marginPx
    }

    /** 收起态胶囊的纵向位置（默认 38%，拖动后按比例保存）。 */
    private fun collapsedY(screen: ScreenBox): Int {
        val minY = screen.top + marginPx
        val maxY = (screen.bottom - collapsedHeightPx - marginPx).coerceAtLeast(minY)
        return (screen.top + screen.height * yRatio).roundToInt().coerceIn(minY, maxY)
    }

    /** 面板宽度：左右各留一点边距，宽屏上再收窄到最大宽度。 */
    private fun expandedWidthPx(screen: ScreenBox): Int =
        (screen.width - 2 * dp(PANEL_MARGIN_DP))
            .coerceAtMost(dp(PANEL_MAX_WIDTH_DP))
            .coerceAtLeast(dp(160f))

    /** 面板横向位置：贴着胶囊原来那一侧展开，动画时那一侧的边保持不动。 */
    private fun expandedX(screen: ScreenBox, width: Int): Int = when (side) {
        AnchorSide.START -> screen.left + dp(PANEL_MARGIN_DP)
        AnchorSide.END -> (screen.right - width - dp(PANEL_MARGIN_DP)).coerceAtLeast(screen.left)
    }

    /** 面板顶边：状态栏下方一点点，和 ChatGPT 一样贴着屏幕顶部。 */
    private fun expandedTop(screen: ScreenBox): Int = screen.top + dp(PANEL_TOP_MARGIN_DP)

    private fun persistAnchor() {
        prefs.edit()
            .putString(KEY_SIDE, if (side == AnchorSide.START) SIDE_START else SIDE_END)
            .putFloat(KEY_Y_RATIO, yRatio)
            .apply()
    }

    private fun persistReadCompletions() {
        while (readCompletions.size > MAX_READ_COMPLETIONS) {
            val iterator = readCompletions.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        prefs.edit().putStringSet(KEY_READ_COMPLETIONS, readCompletions.toSet()).apply()
    }

    // ------------------------------------------------------------ 输入法

    private fun setInputFocused(focus: Boolean) {
        if (focus == focused) return
        if (focus) expanded = true
        focused = focus
        if (focus) {
            applyWindowFlags()
            applyLayout()
            syncIme(true)
        } else {
            syncIme(false)
            applyWindowFlags()
            applyLayout()
        }
        publishUi()
    }

    private fun releaseInputFocus() {
        if (!focused) return
        focused = false
        syncIme(false)
        applyWindowFlags()
    }

    private fun syncIme(show: Boolean) {
        val current = host ?: return
        val view = current.composeView
        val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (show) {
            if (!current.added) return
            runCatching { view.requestFocus() }
            view.post {
                runCatching { imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
            }
        } else {
            runCatching { imm?.hideSoftInputFromWindow(view.windowToken, 0) }
            runCatching { view.clearFocus() }
        }
    }

    // ------------------------------------------------------------ 上层动作

    private fun togglePanel() {
        if (expanded) {
            collapsePanel()
            return
        }
        expanded = true
        applyWindowFlags()
        // 先把「展开」推给 Compose（面板宽度 / 内容就位），再让窗口滑到屏幕顶部。
        publishUi()
        animateToExpanded()
    }

    /** 收起：窗口滑回胶囊的位置，动画结束后再切回胶囊内容。 */
    private fun collapsePanel() {
        releaseInputFocus()
        if (!expanded) {
            applyLayout()
            publishUi()
            return
        }
        val current = host
        if (current == null || !current.added) {
            expanded = false
            dragX = null
            dragY = null
            applyWindowFlags()
            applyLayout()
            publishUi()
            return
        }
        cancelWindowAnimation()
        val screen = screenBox()
        val targetWidth = lastCollapsedWidthPx.takeIf { it > 0 } ?: dp(DEFAULT_COLLAPSED_WIDTH_DP)
        animateWindow(
            targetX = sideX(screen, targetWidth),
            targetY = collapsedY(screen),
            targetWidth = targetWidth,
            durationMs = COLLAPSE_DURATION_MS,
        ) {
            expanded = false
            dragX = null
            dragY = null
            applyWindowFlags()
            applyLayout()
            publishUi()
        }
    }

    /** 动作成功后：收起面板 + 放掉输入焦点（是否已读完成提醒由具体动作决定）。 */
    private fun afterAction() {
        collapsePanel()
    }

    private fun allowOnce(eventId: String) {
        runCatching { callbacks.onAllowOnce(eventId) }
        afterAction()
    }

    private fun reject(eventId: String) {
        runCatching { callbacks.onReject(eventId) }
        afterAction()
    }

    private fun answerQuestion(eventId: String, answers: List<QuestionAnswer>) {
        runCatching { callbacks.onAnswerQuestion(eventId, answers) }
        afterAction()
    }

    private fun sendMessage(sessionId: String, text: String, mode: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val accepted = runCatching { callbacks.onSendMessage(sessionId, trimmed, mode) }.getOrDefault(false)
        if (accepted) {
            markCompletionsRead(sessionId)
            afterAction()
        }
        return accepted
    }

    private fun openSession(sessionId: String) {
        markCompletionsRead(sessionId)
        runCatching { callbacks.onOpenSession(sessionId) }
        afterAction()
    }

    private fun openApp() {
        runCatching { callbacks.onOpenApp() }
        afterAction()
    }

    /** 完成提醒已读：同一会话的完成卡不再弹（本地 key = `sessionId#time`）。 */
    private fun markCompletionsRead(sessionId: String) {
        val keys = state.completions
            .filter { it.sessionId == sessionId }
            .map { OverlayBubbleState.completionKey(it) }
        if (keys.isEmpty()) return
        readCompletions.addAll(keys)
        persistReadCompletions()
    }

    // ------------------------------------------------------------ 杂项

    private fun screenBox(): ScreenBox {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return ScreenBox(
            left = bounds.left + insets.left,
            top = bounds.top + insets.top,
            width = (bounds.width() - insets.left - insets.right).coerceAtLeast(1),
            height = (bounds.height() - insets.top - insets.bottom).coerceAtLeast(1),
        )
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    /** 收起态窗口按内容自适应；这里拿最近一帧量到的胶囊宽高做拖动 / 动画的兜底。 */
    private val collapsedWidthPx: Int
        get() = host?.composeView?.width?.takeIf { it > 0 }
            ?: lastCollapsedWidthPx.takeIf { it > 0 }
            ?: dp(DEFAULT_COLLAPSED_WIDTH_DP)

    private val collapsedHeightPx: Int
        get() = lastCollapsedHeightPx.takeIf { it > 0 } ?: dp(DEFAULT_COLLAPSED_HEIGHT_DP)

    private val marginPx: Int get() = dp(MARGIN_DP)

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    // ------------------------------------------------------------ 内部类型

    private enum class AnchorSide { START, END }

    private data class ScreenBox(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    ) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
    }

    private class WindowHost(
        val owner: OverlayLifecycleOwner,
        val composeView: ComposeView,
        val params: WindowManager.LayoutParams,
    ) {
        /** 视图此刻是否在 WindowManager 里。 */
        var added: Boolean = false
    }

    /**
     * ComposeView 需要的最小生命周期宿主：没有 Activity，必须自己提供一个
     * [LifecycleRegistry] + [SavedStateRegistry]，否则 Compose 在 attach 时会抛异常。
     */
    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)

        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun create() {
            savedStateController.performAttach()
            // Recreator 在 Lifecycle 进入 CREATED 时会 consumeRestoredStateForKey；
            // 必须先 performRestore（没有历史状态就传 null），否则主线程直接抛
            // IllegalStateException 把进程带崩（真机 2026-11-14 实测）。
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun resume() {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun pause() {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val TAG = "OverlayBubble"
        const val PREFS_NAME = "overlay_bubble_position"
        const val KEY_SIDE = "anchor_side"
        const val KEY_Y_RATIO = "anchor_y_ratio"
        const val KEY_READ_COMPLETIONS = "read_completions"
        const val SIDE_START = "start"
        const val SIDE_END = "end"

        /** 胶囊还没量到尺寸时的兜底宽高（实际窗口按文字自适应）。 */
        const val DEFAULT_COLLAPSED_WIDTH_DP = 84f
        const val DEFAULT_COLLAPSED_HEIGHT_DP = 34f
        const val MARGIN_DP = 8f

        /** 面板左右边距 / 顶部留白 / 平板上的最大宽度。 */
        const val PANEL_MARGIN_DP = 12f
        const val PANEL_TOP_MARGIN_DP = 10f
        const val PANEL_MAX_WIDTH_DP = 520f

        /** 展开 / 收起动画时长。 */
        const val EXPAND_DURATION_MS = 280L
        const val COLLAPSE_DURATION_MS = 220L

        /** 默认垂直位置：屏幕可用高度的 38%（需求：约 38%）。 */
        const val DEFAULT_Y_RATIO = 0.38f

        /** 记住多少条已读完成提醒就够了。 */
        const val MAX_READ_COMPLETIONS = 64
    }
}
