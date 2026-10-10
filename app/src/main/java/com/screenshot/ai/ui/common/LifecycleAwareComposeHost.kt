package com.screenshot.ai.ui.common

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 自带完整宿主能力的 Compose 容器。
 *
 * 为什么需要它：
 * Release 版曾在「非 Activity 窗口挂载 Compose 内容」时崩溃：
 *   IllegalStateException: CompositionLocal LocalLifecycleOwner not present
 * 根因是 Compose 在普通窗口里运行时，ViewTree 上没有 LifecycleOwner /
 * ViewModelStoreOwner / SavedStateRegistryOwner 三个宿主 ——
 * Activity 会由 ComponentActivity.setContent 自动注入，普通窗口不会。
 *
 * 实现要点：
 * - 容器本身实现三个 Owner 接口，并在构造时通过 ViewTree 扩展函数
 *   把宿主注入到自己身上 —— 内部的 ComposeView 沿视图树向上即可找到；
 * - 不直接继承 ComposeView：ComposeView 是 final 类，
 *   AbstractComposeView 的 Content() 是 internal 抽象方法，外部无法实现，
 *   因此采用「FrameLayout 容器 + 内置 ComposeView」的组合方式；
 * - attach / detach 时驱动 LifecycleRegistry，
 *   使 collectAsStateWithLifecycle、rememberSaveable 等 API 正常工作。
 */
class LifecycleAwareComposeHost(
    context: Context,
) : FrameLayout(context),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    /** 承载 Compose 内容的视图 */
    private val composeView = ComposeView(context)

    init {
        // 先恢复（无保存状态），再标记 CREATED
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // 三大 Owner 注入自身视图树，ComposeView 沿树向上即可找到
        setViewTreeLifecycleOwner(this)
        setViewTreeViewModelStoreOwner(this)
        setViewTreeSavedStateRegistryOwner(this)

        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        addView(composeView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** 设置 Compose 内容（等价于 ComposeView.setContent） */
    fun setContent(content: @Composable () -> Unit) {
        composeView.setContent(content)
    }

    /** 立即销毁组合，宿主销毁时调用 */
    fun disposeComposition() {
        composeView.disposeComposition()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    /** 销毁组合与 ViewModel 存储（宿主销毁时调用） */
    fun destroy() {
        disposeComposition()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }
}
