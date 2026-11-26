package io.legado.app.ui.pack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.pack.api.NetworkPolicy
import io.legado.pack.api.PackCapability
import io.legado.pack.api.PackGrant
import io.legado.pack.api.PackId
import io.legado.pack.api.PackPermissionSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 授权策略纯逻辑设备测试(不依赖 PackHost/DefaultPackManager):
 * * 写工具默认关闭, 只读工具默认允许;
 * * network=open 未同意时禁止安装;
 * * PackGrant 只包含勾选项, permissionHash 绑定权限摘要;
 * * 升级权限差异可被计算并要求整体重新确认。
 */
@RunWith(AndroidJUnit4::class)
class PackGrantPolicyTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var permissions: PackPermissionSet

    @Before
    fun setUp() {
        permissions = PackPermissionSet(
            packId = PackId.require("uitest.policy"),
            capabilities = setOf(PackCapability.MCP_TOOLS),
            network = NetworkPolicy.None,
            sourceCommands = setOf("hello"),
            mcpTools = setOf("uitest_add", "uitest_write"),
            writeToolNames = setOf("uitest_write"),
        )
    }

    @Test
    fun writeToolsDefaultOffWhileReadToolsDefaultOn() {
        val selection = PackGrantPolicy.defaultSelection(permissions)

        assertEquals(false, selection.toolSelection["uitest_write"])
        assertEquals(true, selection.toolSelection["uitest_add"])
        assertFalse(selection.networkOpenApproved)
    }

    @Test
    fun grantCarriesOnlyExplicitlySelectedEntriesAndThePermissionHash() {
        val selection = PackGrantSelection(
            networkOpenApproved = false,
            toolSelection = mapOf("uitest_add" to true, "uitest_write" to false),
            routeSelection = emptyMap(),
        )
        val grant = PackGrantPolicy.buildGrant(permissions, selection)

        assertEquals(permissions.digest(), grant.permissionHash)
        assertTrue(grant.permitsTool("uitest_add"))
        assertFalse(grant.permitsTool("uitest_write"))
        assertTrue(grant.approves(permissions))
    }

    @Test
    fun openNetworkRequiresExplicitApprovalBeforeCommit() {
        val openPermissions = permissions.copy(network = NetworkPolicy.Open("dynamic host"))
        val unapproved = PackGrantPolicy.defaultSelection(openPermissions)

        assertFalse(PackGrantPolicy.canCommit(openPermissions, unapproved))

        val approved = unapproved.copy(networkOpenApproved = true)
        assertTrue(PackGrantPolicy.canCommit(openPermissions, approved))

        val grant = PackGrantPolicy.buildGrant(openPermissions, approved)
        assertTrue(grant.approves(openPermissions))
        val withoutApproval = PackGrantPolicy.buildGrant(openPermissions, unapproved)
        assertFalse(withoutApproval.approves(openPermissions))
    }

    @Test
    fun closedNetworkCommitsWithoutExtraApproval() {
        val allowlist = permissions.copy(
            network = NetworkPolicy.Allowlist(
                listOf(io.legado.pack.api.DomainPattern.require("*.example.com"))
            )
        )
        val selection = PackGrantPolicy.defaultSelection(allowlist)

        assertTrue(PackGrantPolicy.canCommit(allowlist, selection))
    }

    @Test
    fun freshInstallHasNoPermissionDiff() {
        val diff = PackGrantPolicy.diff(null, permissions)

        assertFalse(diff.changed)
    }

    @Test
    fun upgradeWithExtraWriteToolAndNetworkChangeIsFlaggedForReConfirmation() {
        val upgraded = permissions.copy(
            network = NetworkPolicy.Open("dynamic host"),
            mcpTools = permissions.mcpTools + "uitest_new",
            writeToolNames = permissions.writeToolNames + "uitest_new",
        )
        val diff = PackGrantPolicy.diff(permissions, upgraded)

        assertTrue(diff.changed)
        assertEquals(setOf("uitest_new"), diff.addedTools)
        assertEquals(setOf("uitest_new"), diff.addedWriteTools)
        assertTrue(diff.networkCanonicalChanged)

        val diffText = PackTextFormat.permissionDiffText(context, diff)
        assertTrue(diffText.isNotBlank())
        // 每个变化项都必须能在确认文本中看到
        assertTrue(diffText.contains("uitest_new"))
    }

    @Test
    fun permissionDiffTextStaysEmptyWhenNothingChanged() {
        val diff = PackGrantPolicy.diff(permissions, permissions.copy())

        assertFalse(diff.changed)
        assertEquals("", PackTextFormat.permissionDiffText(context, diff))
    }
}
