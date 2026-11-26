package io.legado.app.ui.pack

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.PackDialogImportConfirmBinding
import io.legado.app.databinding.PackItemGrantCheckboxBinding
import io.legado.app.lib.theme.view.ThemeCheckBox
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.pack.api.ComponentKind
import io.legado.pack.api.PackManifest

/**
 * 扩展包导入确认对话框。
 *
 * * 展示 prepareImport 返回的 manifest/权限摘要(未执行任何包代码);
 * * network=open 与 DEX/native 可信代码风险显式提示;
 * * 写操作工具默认不勾选, 须用户逐项开启;
 * * 勾选状态保存在 ViewModel, 旋转后恢复;
 * * 点击安装才调用 commitImport 提交 PackGrant。
 */
class PackImportConfirmDialog : BaseDialogFragment(R.layout.pack_dialog_import_confirm) {

    private val binding by viewBinding(PackDialogImportConfirmBinding::bind)
    private val viewModel by activityViewModels<PackManageViewModel>()

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.85f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        isCancelable = false
        binding.tvCancel.setOnClickListener {
            viewModel.cancelImport()
        }
        binding.tvOk.setOnClickListener {
            commit()
        }
        viewModel.importUiStateLiveData.observe(viewLifecycleOwner) { state ->
            if (state == null) {
                dismissAllowingStateLoss()
            } else {
                upContent()
            }
        }
        viewModel.commitErrorLiveData.observe(viewLifecycleOwner) { error ->
            if (error == null) {
                binding.tvError.gone()
            } else {
                binding.tvError.text = error
                binding.tvError.visible()
            }
        }
        upContent()
    }

    private fun upContent() {
        val state = viewModel.importUiStateLiveData.value ?: return
        val preview = state.preview
        val manifest = preview.manifest
        binding.tvSummary.text = PackTextFormat.importSummary(requireContext(), preview)
        if (state.existing == null) {
            binding.tvUpgradeLine.setText(R.string.pack_new_install)
        } else {
            binding.tvUpgradeLine.text =
                PackTextFormat.upgradeLine(requireContext(), state.existing, manifest)
        }
        val diff = PackGrantPolicy.diff(
            state.existing?.manifest?.permissions,
            preview.permissions,
        )
        val diffText = PackTextFormat.permissionDiffText(requireContext(), diff)
        binding.tvPermissionDiff.text = diffText
        if (diffText.isEmpty()) {
            binding.tvPermissionDiff.gone()
        } else {
            binding.tvPermissionDiff.visible()
        }

        val networkOpen = preview.requiresOpenNetworkApproval
        if (networkOpen) {
            binding.tvNetworkOpenWarning.visible()
            binding.cbNetworkOpen.visible()
        } else {
            binding.tvNetworkOpenWarning.gone()
            binding.cbNetworkOpen.gone()
        }
        binding.cbNetworkOpen.isChecked = viewModel.importSelection?.networkOpenApproved == true
        binding.cbNetworkOpen.setOnUserCheckedChangeListener { checked ->
            updateSelection { it.copy(networkOpenApproved = checked) }
        }

        if (hasTrustedComponent(manifest)) {
            binding.tvTrustedCode.visible()
        } else {
            binding.tvTrustedCode.gone()
        }

        bindGrantLists(manifest)
    }

    private fun bindGrantLists(manifest: PackManifest) {
        binding.llTools.removeAllViews()
        binding.llRoutes.removeAllViews()
        val selection = viewModel.importSelection ?: return
        if (manifest.mcpTools.isEmpty()) {
            binding.tvToolsLabel.gone()
            binding.tvWriteToolHint.gone()
        } else {
            binding.tvToolsLabel.visible()
            if (manifest.mcpTools.any { it.isWrite }) {
                binding.tvWriteToolHint.visible()
            } else {
                binding.tvWriteToolHint.gone()
            }
            manifest.mcpTools.forEach { tool ->
                val text = if (tool.isWrite) {
                    "${tool.name} ${getString(R.string.pack_tool_write_suffix)}"
                } else {
                    tool.name
                }
                addGrantCheckBox(
                    parent = binding.llTools,
                    text = text,
                    checked = selection.toolSelection[tool.name] == true,
                ) { checked ->
                    updateSelection {
                        it.copy(toolSelection = it.toolSelection + (tool.name to checked))
                    }
                }
            }
        }
        if (manifest.apiRoutes.isEmpty()) {
            binding.tvRoutesLabel.gone()
        } else {
            binding.tvRoutesLabel.visible()
            manifest.apiRoutes.forEach { route ->
                addGrantCheckBox(
                    parent = binding.llRoutes,
                    text = route.name,
                    checked = selection.routeSelection[route.name] == true,
                ) { checked ->
                    updateSelection {
                        it.copy(routeSelection = it.routeSelection + (route.name to checked))
                    }
                }
            }
        }
    }

    private fun addGrantCheckBox(
        parent: ViewGroup,
        text: String,
        checked: Boolean,
        onChecked: (Boolean) -> Unit,
    ): ThemeCheckBox {
        val rowBinding = PackItemGrantCheckboxBinding.inflate(layoutInflater, parent, false)
        rowBinding.cbGrant.text = text
        rowBinding.cbGrant.isChecked = checked
        rowBinding.cbGrant.setOnUserCheckedChangeListener { isChecked ->
            onChecked(isChecked)
        }
        parent.addView(rowBinding.root)
        return rowBinding.cbGrant
    }

    private fun updateSelection(transform: (PackGrantSelection) -> PackGrantSelection) {
        viewModel.importSelection?.let { current ->
            viewModel.upImportSelection(transform(current))
        }
    }

    private fun commit() {
        val state = viewModel.importUiStateLiveData.value ?: return
        val selection = viewModel.importSelection ?: return
        if (!PackGrantPolicy.canCommit(state.preview.permissions, selection)) {
            binding.tvError.setText(R.string.pack_network_open_required)
            binding.tvError.visible()
            return
        }
        binding.tvError.gone()
        val grant = PackGrantPolicy.buildGrant(state.preview.permissions, selection)
        viewModel.commitImport(grant)
    }

    private fun hasTrustedComponent(manifest: PackManifest): Boolean {
        return manifest.components.any { component ->
            component.kind == ComponentKind.DEX || component.kind == ComponentKind.NATIVE
        }
    }
}
