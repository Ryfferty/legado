package io.legado.app.ui.pack

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.PackActivityManageBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.gone
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.pack.api.PackInfo

/**
 * 扩展包管理。
 *
 * 列表/导入/启停/升级确认/卸载残留报告/自检全部通过
 * PackHost.manager 在后台协程完成, 界面只订阅 LiveData:
 * * 文件导入: ContentResolver 输入流 -> prepareImport(仅校验暂存)
 *   -> 确认对话框 -> commitImport(发布元数据), 全程不执行包代码;
 * * 自检仅由用户显式触发, 报告与 runId 一并展示;
 * * 旋转/取消不泄漏 Activity(后台任务在 ViewModel 域)。
 */
class PackManageActivity :
    VMBaseActivity<PackActivityManageBinding, PackManageViewModel>(),
    PackManageAdapter.CallBack {

    override val binding by viewBinding(PackActivityManageBinding::inflate)
    override val viewModel by viewModels<PackManageViewModel>()
    private val adapter by lazy { PackManageAdapter(this, this) }

    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            viewModel.prepareImport(uri)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.llEmpty.gone()
        initLiveData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.pack_manage, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_pack_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                title = getString(R.string.pack_import_select_title)
                allowExtensions = arrayOf("zip", "pack", "js")
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        adapter.upResumed(true)
        viewModel.refreshPacks()
    }

    override fun onPause() {
        adapter.upResumed(false)
        super.onPause()
    }

    private fun initLiveData() {
        viewModel.packsLiveData.observe(this) { packs ->
            adapter.setItems(packs, adapter.diffItemCallBack)
            upEmptyView()
        }
        viewModel.importBusyLiveData.observe(this) { busy ->
            upProgressDialog(busy, R.string.pack_import_checking)
            if (busy) {
                binding.rotateLoading.visible()
            } else {
                binding.rotateLoading.gone()
            }
        }
        viewModel.importUiStateLiveData.observe(this) { state ->
            val current = supportFragmentManager.findFragmentByTag(IMPORT_DIALOG_TAG)
            when {
                state == null -> (current as? PackImportConfirmDialog)?.dismissAllowingStateLoss()
                current == null -> PackImportConfirmDialog()
                    .show(supportFragmentManager, IMPORT_DIALOG_TAG)
            }
        }
        viewModel.writeToolsLiveData.observe(this) { state ->
            val current = supportFragmentManager.findFragmentByTag(WRITE_TOOLS_DIALOG_TAG)
            when {
                state == null -> (current as? PackWriteToolsDialog)?.dismissAllowingStateLoss()
                current == null -> PackWriteToolsDialog()
                    .show(supportFragmentManager, WRITE_TOOLS_DIALOG_TAG)
            }
        }
        viewModel.selfTestBusyLiveData.observe(this) { busy ->
            upProgressDialog(busy, R.string.pack_self_test_running)
        }
        viewModel.uninstallBusyLiveData.observe(this) { busy ->
            upProgressDialog(busy, R.string.pack_uninstall_running)
        }
        viewModel.messageEvent.observe(this) { event ->
            event?.getIfNotHandled()?.let {
                toastOnUi(it)
            }
        }
        viewModel.selfTestReportEvent.observe(this) { event ->
            event?.getIfNotHandled()?.let { report ->
                showDialogFragment(
                    TextDialog(
                        getString(R.string.pack_self_test_title),
                        PackTextFormat.selfTestReportText(this, report),
                    )
                )
            }
        }
        viewModel.uninstallReportEvent.observe(this) { event ->
            event?.getIfNotHandled()?.let { report ->
                showDialogFragment(
                    TextDialog(
                        getString(R.string.pack_uninstall_title),
                        PackTextFormat.uninstallReportText(this, report),
                    )
                )
            }
        }
    }

    private fun upEmptyView() {
        if (adapter.isEmpty()) {
            binding.llEmpty.visible()
            binding.recyclerView.gone()
        } else {
            binding.llEmpty.gone()
            binding.recyclerView.visible()
        }
    }

    private fun upProgressDialog(busy: Boolean, messageRes: Int) {
        val current = supportFragmentManager.findFragmentByTag(PROGRESS_DIALOG_TAG)
        if (busy) {
            if (current == null) {
                PackProgressDialog().apply {
                    arguments = Bundle().apply {
                        putInt("messageRes", messageRes)
                    }
                }.show(supportFragmentManager, PROGRESS_DIALOG_TAG)
            }
        } else {
            (current as? PackProgressDialog)?.dismissAllowingStateLoss()
        }
    }

    override fun enable(enable: Boolean, item: PackInfo) {
        viewModel.setEnabled(item.ref.id, enable)
    }

    override fun showDetail(item: PackInfo) {
        showDialogFragment(
            TextDialog(
                getString(R.string.pack_detail),
                PackTextFormat.manifestDetail(this, item),
            )
        )
    }

    override fun manageWriteTools(item: PackInfo) {
        viewModel.loadWriteTools(item.ref.id)
    }

    override fun runSelfTest(item: PackInfo) {
        viewModel.runSelfTest(item.ref.id)
    }

    override fun viewSelfTest(item: PackInfo) {
        viewModel.loadSelfTestReport(item.ref.id, null)
    }

    override fun uninstall(item: PackInfo) {
        alert(titleResource = R.string.pack_uninstall) {
            setMessage(
                getString(R.string.pack_uninstall_confirm, item.manifest.name)
            )
            okButton {
                viewModel.uninstall(item.ref.id)
            }
            noButton()
        }
    }

    companion object {
        private const val IMPORT_DIALOG_TAG = "packImportConfirm"
        private const val WRITE_TOOLS_DIALOG_TAG = "packWriteTools"
        private const val PROGRESS_DIALOG_TAG = "packProgressDialog"
    }
}
