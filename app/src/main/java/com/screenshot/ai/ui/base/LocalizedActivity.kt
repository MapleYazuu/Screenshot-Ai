package com.screenshot.ai.ui.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 支持 per-app 语言的 Activity 基类。
 *
 * ## 历史教训（曾导致语言切换 ANR / 启动死锁）
 *
 * 之前为了在 ComponentActivity 上实现 per-app 语言，做过三件事：
 * 1. attachBaseContext 里包自定义 ContextWrapper 强制改 locale；
 * 2. 语言版本号 + onResume 对比后手动 recreate()；
 * 3. 设置页观察版本号事件再 recreate()。
 *
 * 结果：`setApplicationLocales` 本身就会触发 Activity 重建，
 * 外面再包一层 recreate 形成**无限重建循环 → ANR**；
 * ViewModel 在 recreate 之间存活，版本号事件永远非零，
 * 每次重建后的新实例又立刻 recreate —— 重启 App 也救不回来。
 *
 * ## 现在的方案（零重建逻辑、零 ContextWrapper）
 *
 * - 基类改成 AppCompatActivity（本 App 主题是 Theme.AppCompat 后代）；
 * - Android 13+ 由系统 LocaleManager 全局生效并自动重建 Activity；
 * - Android 12 及以下由 AppCompatDelegate 在 AppCompatActivity 上
 *   自动应用 locale 并自动重建 Activity；
 * - 本类不再重写 attachBaseContext、不再手动 recreate ——
 *   所有重建交给平台，杜绝循环。
 */
abstract class LocalizedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
