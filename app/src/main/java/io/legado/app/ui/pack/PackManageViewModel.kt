package io.legado.app.ui.pack

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.help.pack.PackHost
import io.legado.pack.api.ImportPreview
import io.legado.pack.api.PackDiagnostic
import io.legado.pack.api.PackGrant
import io.legado.pack.api.PackErrorCode
import io.legado.pack.api.PackException
import io.legado.pack.api.PackId
import io.legado.pack.api.PackImportHint
import io.legado.pack.api.PackInfo
import io.legado.pack.api.PackManager
import io.legado.pack.api.SelfTestReport
import io.legado.pack.api.UninstallReport

/**
 * 扩展包管理界面状态。
 *
 * 所有对 PackManager 的调用都在后台协程执行, ViewModel 只持有
 * 应用级 Context, 不引用 Activity, 旋转/退出不会泄漏界面实例;
 * 界面通过 LiveData 订阅结果。导入/启停/卸载只发布元数据,
 * 不触发任何包代码执行(自检除外, 自检只由用户显式触发)。
 */
class PackManageViewModel(application: Application) : BaseViewModel(application) {

    data class ImportUiState(
        val preview: ImportPreview,
        val existing: PackInfo?,
    )

    /** 写操作工具授权状态(管理面对话框展示)。 */
    data class WriteToolsUiState(
        val packId: PackId,
        val packName: String,
        val tools: List<PackHost.PackToolStatus>,
    )

    private val manager: PackManager
        get() = PackHost.manager

    val packsLiveData = MutableLiveData<List<PackInfo>>()
    val importBusyLiveData = MutableLiveData(false)
    val importUiStateLiveData = MutableLiveData<ImportUiState?>(null)
    val commitErrorLiveData = MutableLiveData<String?>(null)
    val messageEvent = MutableLiveData<PackUiEvent<String>>(null)
    val selfTestBusyLiveData = MutableLiveData(false)
    val selfTestReportEvent = MutableLiveData<PackUiEvent<SelfTestReport>>(null)
    val uninstallBusyLiveData = MutableLiveData(false)
    val uninstallReportEvent = MutableLiveData<PackUiEvent<UninstallReport>>(null)
    val writeToolsLiveData = MutableLiveData<WriteToolsUiState?>(null)

    var importSelection: PackGrantSelection? = null
        private set

    fun refreshPacks() {
        execute {
            manager.list()
        }.onSuccess {
            packsLiveData.postValue(it)
        }.onError {
            AppLog.put("读取扩展包列表出错", it)
            messageEvent.postValue(
                PackUiEvent(context.getString(R.string.pack_list_load_failed, errorText(it)))
            )
        }
    }

    /**
     * 文件导入第一步: 后台读取 ContentResolver 输入流并调用
     * prepareImport。此阶段只校验和暂存, 不执行包代码;
     * 成功后由确认对话框展示 manifest 与权限, 等待用户确认。
     */
    fun prepareImport(uri: Uri) {
        importBusyLiveData.postValue(true)
        commitErrorLiveData.postValue(null)
        execute {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.IO_FAILED,
                        message = context.getString(R.string.pack_import_open_stream_failed),
                    )
                )
            input.use { stream ->
                manager.prepareImport(stream, PackImportHint(fileName = fileNameHint(uri)))
            }
        }.onSuccess { preview ->
            importBusyLiveData.postValue(false)
            val state = ImportUiState(preview, manager.info(preview.manifest.id))
            importSelection = PackGrantPolicy.defaultSelection(preview.permissions)
            importUiStateLiveData.postValue(state)
        }.onError {
            importBusyLiveData.postValue(false)
            AppLog.put("导入扩展包失败", it)
            messageEvent.postValue(
                PackUiEvent(context.getString(R.string.pack_import_failed) + "\n" + errorText(it))
            )
        }
    }

    fun commitImport(grant: PackGrant) {
        val state = importUiStateLiveData.value ?: return
        importBusyLiveData.postValue(true)
        commitErrorLiveData.postValue(null)
        execute {
            manager.commitImport(state.preview.token, grant)
        }.onSuccess { info ->
            importBusyLiveData.postValue(false)
            importSelection = null
            importUiStateLiveData.postValue(null)
            // 写工具授权由 HostPackManager.commitImport 按勾选结果播种,
            // 此处不再重复同步, 见 PackHost.kt。
            refreshPacks()
            messageEvent.postValue(
                PackUiEvent(context.getString(R.string.pack_import_success, info.manifest.name))
            )
        }.onError {
            importBusyLiveData.postValue(false)
            // 保留预览, 用户修正勾选后可重试
            AppLog.put("安装扩展包失败", it)
            commitErrorLiveData.postValue(errorText(it))
        }
    }

    /** 用户取消导入: 丢弃预览与勾选状态(暂存区清理依赖管理器的过期策略, 见集成说明)。 */
    fun cancelImport() {
        importSelection = null
        importUiStateLiveData.postValue(null)
        commitErrorLiveData.postValue(null)
    }

    fun setEnabled(id: PackId, enabled: Boolean) {
        execute {
            manager.setEnabled(id, enabled)
        }.onSuccess { result ->
            refreshPacks()
            if (result.isError) {
                result.error?.let { error ->
                    messageEvent.postValue(
                        PackUiEvent(context.getString(R.string.pack_enable_failed, errorTextOf(error)))
                    )
                }
            }
        }.onError {
            refreshPacks()
            AppLog.put("扩展包启停失败", it)
            messageEvent.postValue(
                PackUiEvent(context.getString(R.string.pack_enable_failed, errorText(it)))
            )
        }
    }

    /** 用户显式触发的自检: 运行并返回带 runId 的报告。 */
    fun runSelfTest(id: PackId) {
        selfTestBusyLiveData.postValue(true)
        execute {
            manager.selfTest(id)
        }.onSuccess { report ->
            selfTestBusyLiveData.postValue(false)
            selfTestReportEvent.postValue(PackUiEvent(report))
        }.onError {
            selfTestBusyLiveData.postValue(false)
            AppLog.put("运行扩展包自检失败", it)
            messageEvent.postValue(
                PackUiEvent(context.getString(R.string.pack_self_test_failed, errorText(it)))
            )
        }
    }

    /** 读取既有自检报告(runId 为空时取最新), 不执行任何包代码。 */
    fun loadSelfTestReport(id: PackId, runId: String? = null) {
        execute {
            manager.selfTestReport(id, runId)
        }.onSuccess { report ->
            if (report == null) {
                messageEvent.postValue(PackUiEvent(context.getString(R.string.pack_self_test_none)))
            } else {
                selfTestReportEvent.postValue(PackUiEvent(report))
            }
        }.onError {
            AppLog.put("读取扩展包自检报告失败", it)
            messageEvent.postValue(
                PackUiEvent(context.getString(R.string.pack_self_test_failed, errorText(it)))
            )
        }
    }

    fun uninstall(id: PackId) {
        uninstallBusyLiveData.postValue(true)
        execute {
            manager.uninstall(id)
        }.onSuccess { report ->
            uninstallBusyLiveData.postValue(false)
            refreshPacks()
            uninstallReportEvent.postValue(PackUiEvent(report))
            if (report.clean) {
                messageEvent.postValue(PackUiEvent(context.getString(R.string.pack_uninstall_done)))
            } else {
                messageEvent.postValue(PackUiEvent(context.getString(R.string.pack_uninstall_not_clean)))
            }
        }.onError {
            uninstallBusyLiveData.postValue(false)
            refreshPacks()
            AppLog.put("卸载扩展包失败", it)
            messageEvent.postValue(
                PackUiEvent(context.getString(R.string.pack_uninstall_failed_error, errorText(it)))
            )
        }
    }

    fun upImportSelection(selection: PackGrantSelection) {
        importSelection = selection
    }

    /** 加载某包声明的写工具及当前授权状态(默认全关)。 */
    fun loadWriteTools(id: PackId) {
        execute {
            val info = PackHost.manager.info(id)
                ?: throw PackException(
                    PackDiagnostic(
                        code = PackErrorCode.PACK_NOT_FOUND,
                        message = context.getString(R.string.pack_not_found),
                    )
                )
            WriteToolsUiState(
                packId = id,
                packName = info.manifest.name,
                tools = PackHost.declaredTools(id).filter { it.tool.isWrite },
            )
        }.onSuccess { state ->
            writeToolsLiveData.postValue(state)
        }.onError {
            AppLog.put("读取写工具授权状态失败", it)
            messageEvent.postValue(
                PackUiEvent(context.getString(R.string.pack_write_tools_load_failed, errorText(it)))
            )
        }
    }

    fun setWriteToolEnabled(id: PackId, toolName: String, enabled: Boolean) {
        execute {
            PackHost.setPackWriteToolEnabled(id, toolName, enabled)
        }.onSuccess { accepted ->
            if (!accepted) {
                messageEvent.postValue(
                    PackUiEvent(context.getString(R.string.pack_write_tool_rejected, toolName))
                )
            }
            loadWriteTools(id)
        }.onError {
            AppLog.put("写工具授权修改失败", it)
            messageEvent.postValue(
                PackUiEvent(context.getString(R.string.pack_write_tools_load_failed, errorText(it)))
            )
        }
    }

    fun dismissWriteTools() {
        writeToolsLiveData.postValue(null)
    }

    private fun errorText(throwable: Throwable): String {
        val diagnostic = (throwable as? PackException)?.diagnostic
        return if (diagnostic != null) {
            PackTextFormat.diagnosticText(context, diagnostic)
        } else {
            throwable.message ?: throwable.javaClass.simpleName
        }
    }

    private fun errorTextOf(diagnostic: PackDiagnostic): String =
        PackTextFormat.diagnosticText(context, diagnostic)

    private fun fileNameHint(uri: Uri): String? {
        val raw = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        if (raw.isNullOrBlank()) return null
        val clean = raw.take(255).filterNot { it.isISOControl() || it == '/' || it == '\\' }
        return clean.ifBlank { null }
    }
}
