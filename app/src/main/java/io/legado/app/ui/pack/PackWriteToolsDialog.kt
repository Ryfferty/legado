package io.legado.app.ui.pack

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.PackDialogWriteToolsBinding
import io.legado.app.databinding.PackItemGrantCheckboxBinding
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible

/**
 * 写操作工具授权对话框(管理面)。
 *
 * manifest 的 isWrite 只是声明; 真正的授权由宿主
 * PackToolPermissionStore 掌握, 默认全部关闭,
 * 只有用户在这里(或导入确认时)显式开启后 MCP 调用才被放行。
 * 授权与修订/epoch/权限摘要绑定, 升级或重装后自动失效。
 */
class PackWriteToolsDialog : BaseDialogFragment(R.layout.pack_dialog_write_tools) {

    private val binding by viewBinding(PackDialogWriteToolsBinding::bind)
    private val viewModel by activityViewModels<PackManageViewModel>()

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.6f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        isCancelable = false
        binding.tvOk.setOnClickListener {
            viewModel.dismissWriteTools()
        }
        viewModel.writeToolsLiveData.observe(viewLifecycleOwner) { state ->
            if (state == null) {
                dismissAllowingStateLoss()
            } else {
                upContent(state)
            }
        }
        viewModel.writeToolsLiveData.value?.let { upContent(it) }
    }

    private fun upContent(state: PackManageViewModel.WriteToolsUiState) {
        binding.tvPackName.text = state.packName
        binding.llTools.removeAllViews()
        if (state.tools.isEmpty()) {
            binding.tvEmpty.visible()
            return
        }
        binding.tvEmpty.gone()
        state.tools.forEach { status ->
            val rowBinding =
                PackItemGrantCheckboxBinding.inflate(layoutInflater, binding.llTools, false)
            rowBinding.cbGrant.text = status.tool.name
            rowBinding.cbGrant.isChecked = status.writeEnabled
            rowBinding.cbGrant.setOnUserCheckedChangeListener { checked ->
                viewModel.setWriteToolEnabled(state.packId, status.tool.name, checked)
            }
            binding.llTools.addView(rowBinding.root)
        }
    }

    companion object {

        const val TAG: String = "packWriteTools"
    }
}
