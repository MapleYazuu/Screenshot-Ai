package com.screenshot.ai.ui.common

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties

/**
 * 基于 [LifecycleAwareComposeHost] 的对话框。
 *
 * 替代 `androidx.compose.ui.window.Dialog` 在无 Activity 宿主场景（如悬浮窗口
 * 弹出的全屏查看）的用法：后者创建的 ComposeView 没有 LifecycleOwner，
 * Release 混淆后曾触发「CompositionLocal LocalLifecycleOwner not present」崩溃。
 * 这里所有 Compose 内容都挂在自带三大 Owner 的容器上。
 *
 * @param onDismissRequest 点击返回键或外部关闭时的回调
 * @param properties 窗口属性（如 usePlatformDefaultWidth = false 实现全屏）
 */
@Composable
fun LifecycleAwareDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 用 rememberUpdatedState 避免 DisposableEffect 捕获过期的 content/回调闭包
    val currentContent by rememberUpdatedState(content)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentProperties by rememberUpdatedState(properties)

    val composeHost = remember { LifecycleAwareComposeHost(context) }
    val dialog =
        remember(composeHost) {
            object : Dialog(context) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(composeHost)
                }

                override fun onBackPressed() {
                    currentOnDismiss()
                }
            }
        }

    DisposableEffect(dialog) {
        // 透明底避免对话框自带白底；全屏/占满窗口由调用方内容自行控制
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (!currentProperties.usePlatformDefaultWidth) {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
        composeHost.setContent { currentContent() }
        dialog.show()
        onDispose {
            dialog.dismiss()
            composeHost.destroy()
        }
    }
}
