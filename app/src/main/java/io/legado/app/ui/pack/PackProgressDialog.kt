package io.legado.app.ui.pack

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.PackDialogProgressBinding
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 后台操作进度对话框(自检/卸载)。
 * 使用 DialogFragment 承载, 旋转屏幕后随界面自动重建, 不持有 Activity 引用。
 * 由 [PackManageActivity] 以固定 tag 管理显示与关闭。
 */
class PackProgressDialog : BaseDialogFragment(R.layout.pack_dialog_progress) {

    private val binding by viewBinding(PackDialogProgressBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        isCancelable = false
        arguments?.getInt("messageRes")?.let { res ->
            binding.tvMsg.setText(res)
        }
    }
}
