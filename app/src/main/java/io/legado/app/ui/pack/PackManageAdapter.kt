package io.legado.app.ui.pack

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.PackItemPackBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.utils.ColorUtils
import io.legado.pack.api.NetworkPolicy
import io.legado.pack.api.PackInfo
import io.legado.pack.api.PackState

class PackManageAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<PackInfo, PackItemPackBinding>(context) {

    val diffItemCallBack = object : DiffUtil.ItemCallback<PackInfo>() {

        override fun areItemsTheSame(oldItem: PackInfo, newItem: PackInfo): Boolean {
            return oldItem.ref.id == newItem.ref.id
        }

        override fun areContentsTheSame(oldItem: PackInfo, newItem: PackInfo): Boolean {
            return oldItem == newItem
        }
    }

    override fun getViewBinding(parent: ViewGroup): PackItemPackBinding {
        return PackItemPackBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: PackItemPackBinding,
        item: PackInfo,
        payloads: MutableList<Any>,
    ) {
        binding.run {
            root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
            tvName.text = item.manifest.name
            val stateText = PackTextFormat.stateText(context, item.state)
            val networkText = when (item.manifest.network) {
                is NetworkPolicy.Open -> context.getString(R.string.pack_network_open_short)
                is NetworkPolicy.Allowlist ->
                    context.getString(R.string.pack_network_allowlist_short)
                else -> ""
            }
            val statusLine = buildString {
                append(item.manifest.id.value)
                append(" · ").append(stateText)
                if (networkText.isNotEmpty()) {
                    append(" · ").append(networkText)
                }
            }
            tvStatus.text = statusLine
            if (item.state == PackState.BROKEN) {
                tvStatus.setTextColor(context.getColor(R.color.error))
            } else {
                tvStatus.setTextColor(context.getColor(R.color.secondaryText))
            }
            swtEnabled.isEnabled = true
            swtEnabled.isChecked = item.state == PackState.ENABLED
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: PackItemPackBinding) {
        binding.apply {
            root.setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.showDetail(it)
                }
            }
            swtEnabled.setOnUserCheckedChangeListener { isChecked ->
                getItem(holder.layoutPosition)?.let {
                    callBack.enable(isChecked, it)
                }
            }
            ivMenuMore.setOnClickListener {
                showMenu(ivMenuMore, holder.layoutPosition)
            }
        }
    }

    private fun showMenu(view: View, position: Int) {
        val item = getItem(position) ?: return
        val hasWriteTools = item.manifest.mcpTools.any { it.isWrite }
        popupActionMenu(context) {
            item(context.getString(R.string.pack_detail), "detail")
            item(context.getString(R.string.pack_write_tools_menu), "writeTools", hasWriteTools)
            item(context.getString(R.string.pack_run_self_test), "selfTest")
            item(context.getString(R.string.pack_view_self_test), "viewSelfTest")
            danger("uninstall")
            item(context.getString(R.string.pack_uninstall), "uninstall")
        }.show(view) { action ->
            when (action) {
                "detail" -> callBack.showDetail(item)
                "writeTools" -> callBack.manageWriteTools(item)
                "selfTest" -> callBack.runSelfTest(item)
                "viewSelfTest" -> callBack.viewSelfTest(item)
                "uninstall" -> callBack.uninstall(item)
            }
        }
    }

    interface CallBack {

        fun enable(enable: Boolean, item: PackInfo)

        fun showDetail(item: PackInfo)

        fun manageWriteTools(item: PackInfo)

        fun runSelfTest(item: PackInfo)

        fun viewSelfTest(item: PackInfo)

        fun uninstall(item: PackInfo)
    }
}
