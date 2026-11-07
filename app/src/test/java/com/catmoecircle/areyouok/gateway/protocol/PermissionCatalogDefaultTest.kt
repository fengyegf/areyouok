package com.catmoecircle.areyouok.gateway.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 新建对话的默认权限档位。
 *
 * 覆盖两段纯逻辑：`PermissionCatalog.defaultSelectionValue` 的解析优先级，
 * 以及 `SessionSummary.effectivePermissionPreset` 对「空白会话没有投影」的回退。
 */
class PermissionCatalogDefaultTest {

    private fun option(value: String) = PermissionOption(value = value)

    /** DSH 实测顺序：read-only / workspace-write / danger-full-access。 */
    private fun dshOptions() = listOf(
        option("read-only"),
        option("workspace-write"),
        option("danger-full-access"),
    )

    private fun catalog(
        options: List<PermissionOption> = dshOptions(),
        defaultPreset: String? = null,
    ) = PermissionCatalog(options = options, defaultPreset = defaultPreset)

    // ---- PermissionCatalog.defaultSelectionValue --------------------------

    @Test
    fun `defaultSelectionValue prefers defaultPreset when it is a valid option`() {
        val cat = catalog(defaultPreset = "danger-full-access")
        assertEquals("danger-full-access", cat.defaultSelectionValue)
    }

    @Test
    fun `defaultSelectionValue falls back to the second option without defaultPreset`() {
        // 没有 defaultPreset 时取第二档：DSH 默认授予 workspace-write。
        assertEquals("workspace-write", catalog().defaultSelectionValue)
    }

    @Test
    fun `defaultSelectionValue falls back to the only option`() {
        val single = catalog(options = listOf(option("read-only")))
        assertEquals("read-only", single.defaultSelectionValue)
    }

    @Test
    fun `defaultSelectionValue is null for an empty catalog`() {
        assertNull(catalog(options = emptyList()).defaultSelectionValue)
    }

    @Test
    fun `defaultSelectionValue falls back to the second option when defaultPreset is unknown`() {
        // defaultPreset 不在 options 里时菜单无法勾选它，只能退到第二档。
        val cat = catalog(defaultPreset = "not-in-options")
        assertEquals("workspace-write", cat.defaultSelectionValue)
    }

    // ---- SessionSummary.effectivePermissionPreset ------------------------

    @Test
    fun `effectivePermissionPreset prefers the session projection value`() {
        val summary = SessionSummary(
            id = "s1",
            projections = SessionProjections(
                values = SessionProjectionValues(
                    permissions = SessionPermissions(currentValue = "danger-full-access"),
                ),
            ),
        )
        // 目录默认是 read-only，但会话已经显式切过权限，必须用投影值。
        assertEquals(
            "danger-full-access",
            summary.effectivePermissionPreset(catalog(defaultPreset = "read-only")),
        )
    }

    @Test
    fun `blank session without projection uses the catalog default`() {
        val summary = SessionSummary(id = "session-new", blank = true)
        assertEquals(
            "workspace-write",
            summary.effectivePermissionPreset(catalog()),
        )
    }

    @Test
    fun `blank session without projection honours an explicit defaultPreset`() {
        val summary = SessionSummary(id = "session-new", blank = true)
        assertEquals(
            "read-only",
            summary.effectivePermissionPreset(catalog(defaultPreset = "read-only")),
        )
    }

    @Test
    fun `non blank session without projection stays null`() {
        // 老网关 / 已有内容的会话缺 permissions 投影时不猜档位，交给 UI 原有兜底。
        val summary = SessionSummary(id = "s1")
        assertNull(summary.effectivePermissionPreset(catalog()))
    }

    @Test
    fun `blank session whose projection value is blank falls back to catalog default`() {
        val summary = SessionSummary(
            id = "session-new",
            blank = true,
            projections = SessionProjections(
                values = SessionProjectionValues(
                    permissions = SessionPermissions(currentValue = ""),
                ),
            ),
        )
        assertEquals("workspace-write", summary.effectivePermissionPreset(catalog()))
    }

    @Test
    fun `blank session with sessionListMetadata blank true still uses catalog default`() {
        // DSH 新建会话同时给顶层 blank 和 sessionListMetadata.blank=true，两者一致。
        val summary = SessionSummary(
            id = "session-new",
            blank = true,
            projections = SessionProjections(
                values = SessionProjectionValues(
                    sessionListMetadata = SessionListMetadata(blank = true, lastPromptAt = null),
                ),
            ),
        )
        assertEquals("workspace-write", summary.effectivePermissionPreset(catalog()))
    }

    @Test
    fun `blank session with empty catalog stays null`() {
        val summary = SessionSummary(id = "session-new", blank = true)
        assertNull(summary.effectivePermissionPreset(catalog(options = emptyList())))
    }
}
