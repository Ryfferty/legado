package io.legado.app.ui.pack

/**
 * 一次性 UI 事件包装。
 * LiveData 会在界面重建后重放最后一次的值, 用本包装保证
 * 提示类消息(Toast/对话框)在一次操作周期内只被消费一次。
 */
class PackUiEvent<out T>(private val value: T) {

    private var handled = false

    fun getIfNotHandled(): T? {
        if (handled) return null
        handled = true
        return value
    }

    fun peek(): T = value
}
