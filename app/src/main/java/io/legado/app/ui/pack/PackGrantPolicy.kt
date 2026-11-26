package io.legado.app.ui.pack

import io.legado.pack.api.NetworkPolicy
import io.legado.pack.api.PackCapability
import io.legado.pack.api.PackGrant
import io.legado.pack.api.PackPermissionSet

/**
 * 导入确认界面的授权选择状态。保存在 ViewModel 中,
 * 旋转屏幕后确认对话框据此重建勾选项。
 */
data class PackGrantSelection(
    val networkOpenApproved: Boolean = false,
    val toolSelection: Map<String, Boolean> = emptyMap(),
    val routeSelection: Map<String, Boolean> = emptyMap(),
)

/**
 * 授权(PackGrant)构造策略, 纯逻辑便于设备测试:
 * * 写操作工具默认关闭, 必须由用户显式勾选后才进入 grant;
 * * network=open 的包必须显式勾选同意, 否则不允许安装;
 * * 只读工具/路由默认允许, 用户可在确认对话框中取消勾选;
 * * grant 始终绑定导入预览的 permissionHash, 权限变化即视为全新授权。
 */
object PackGrantPolicy {

    fun defaultSelection(permissions: PackPermissionSet): PackGrantSelection {
        val tools = permissions.mcpTools.associateWith { it !in permissions.writeToolNames }
        val routes = permissions.apiRoutes.associateWith { true }
        return PackGrantSelection(
            networkOpenApproved = false,
            toolSelection = tools,
            routeSelection = routes,
        )
    }

    fun buildGrant(permissions: PackPermissionSet, selection: PackGrantSelection): PackGrant {
        return PackGrant(
            permissionHash = permissions.digest(),
            networkOpenApproved = selection.networkOpenApproved,
            toolPermissions = selection.toolSelection.filterValues { it },
            routePermissions = selection.routeSelection.filterValues { it },
        )
    }

    /** network=open 未显式同意前禁止安装。 */
    fun canCommit(permissions: PackPermissionSet, selection: PackGrantSelection): Boolean {
        if (permissions.requiresOpenNetworkApproval && !selection.networkOpenApproved) {
            return false
        }
        return true
    }

    /**
     * 升级时权限差异。permissionHash 不同即整体重新确认,
     * diff 只用于向用户高亮变化项, 不参与授权判定。
     */
    data class PermissionDiff(
        val changed: Boolean,
        val addedCapabilities: Set<PackCapability>,
        val removedCapabilities: Set<PackCapability>,
        val addedNetwork: Boolean,
        val removedNetwork: Boolean,
        val networkCanonicalChanged: Boolean,
        val addedTools: Set<String>,
        val removedTools: Set<String>,
        val addedWriteTools: Set<String>,
        val removedWriteTools: Set<String>,
        val addedRoutes: Set<String>,
        val removedRoutes: Set<String>,
        val addedSourceCommands: Set<String>,
        val removedSourceCommands: Set<String>,
    )

    fun diff(old: PackPermissionSet?, new: PackPermissionSet): PermissionDiff {
        if (old == null) {
            return PermissionDiff(
                changed = false,
                addedCapabilities = emptySet(),
                removedCapabilities = emptySet(),
                addedNetwork = false,
                removedNetwork = false,
                networkCanonicalChanged = false,
                addedTools = emptySet(),
                removedTools = emptySet(),
                addedWriteTools = emptySet(),
                removedWriteTools = emptySet(),
                addedRoutes = emptySet(),
                removedRoutes = emptySet(),
                addedSourceCommands = emptySet(),
                removedSourceCommands = emptySet(),
            )
        }
        return PermissionDiff(
            changed = old.digest() != new.digest(),
            addedCapabilities = new.capabilities - old.capabilities,
            removedCapabilities = old.capabilities - new.capabilities,
            addedNetwork = new.network !is NetworkPolicy.None && old.network is NetworkPolicy.None,
            removedNetwork = new.network is NetworkPolicy.None && old.network !is NetworkPolicy.None,
            networkCanonicalChanged = old.network.canonical() != new.network.canonical(),
            addedTools = new.mcpTools - old.mcpTools,
            removedTools = old.mcpTools - new.mcpTools,
            addedWriteTools = new.writeToolNames - old.writeToolNames,
            removedWriteTools = old.writeToolNames - new.writeToolNames,
            addedRoutes = new.apiRoutes - old.apiRoutes,
            removedRoutes = old.apiRoutes - new.apiRoutes,
            addedSourceCommands = new.sourceCommands - old.sourceCommands,
            removedSourceCommands = old.sourceCommands - new.sourceCommands,
        )
    }
}
