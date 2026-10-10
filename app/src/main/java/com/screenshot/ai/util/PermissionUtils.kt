package com.screenshot.ai.util

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.screenshot.ai.R
import android.util.Log
import androidx.core.content.ContextCompat
import com.screenshot.ai.service.ScreenCaptureService

/**
 * 权限与系统设置跳转工具。
 *
 * 本应用依赖三类「特殊权限」，它们都拿不到运行时授权弹窗，只能跳到系统设置页让用户手动开启：
 * 1. 悬浮窗（SYSTEM_ALERT_WINDOW）——悬浮球需要它才能显示在别的应用之上；
 * 2. 无障碍服务——截屏靠 AccessibilityService 的 takeScreenshot；
 * 3. 电池优化白名单 / 厂商自启动——否则后台服务会被 ROM 杀掉，悬浮球消失。
 *
 * 所有 open* 方法都不抛异常：跳转失败最多是用户没看到设置页，
 * 不应该让应用崩溃（不同 ROM 的 Action 支持情况差异极大）。
 */
object PermissionUtils {
    private const val TAG = "PermissionUtils"

    /**
     * 无障碍服务在系统设置里的完整组件名。
     *
     * 用类引用而不是硬编码字符串：包名与类名重构时编译器会立刻报错，
     * 而不是等到线上出现「权限明明开了却判定未开启」才发现。
     */
    private val SCREEN_CAPTURE_SERVICE: String = ScreenCaptureService::class.java.name

    /**
     * 是否已获得悬浮窗权限。
     *
     * [Settings.canDrawOverlays] 在 API 23 以下不存在，本项目 minSdk 30 可直接调用。
     */
    fun canDrawOverlays(context: Context): Boolean =
        try {
            Settings.canDrawOverlays(context)
        } catch (e: Exception) {
            Log.w(TAG, "canDrawOverlays: 查询失败", e)
            false
        }

    /**
     * 无障碍服务是否已开启。
     *
     * 直接读 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 而不是用 AccessibilityManager，
     * 因为前者不依赖服务进程存活状态，冷启动时也能判断。
     *
     * 关键点：ROM 写入的是「扁平的组件名」，格式并不统一——
     * 原生与多数 ROM 写全限定名 `包名/全限定类名`，
     * 部分 ROM（以及开发者手动开启后）写短名 `包名/.类名`，
     * 所以两种形式都要匹配，否则会出现「明明开了却显示未开启」。
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean =
        try {
            val enabledServices =
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            if (enabledServices.isNullOrBlank()) {
                false
            } else {
                val packageName = context.packageName
                val fullName = "$packageName/$SCREEN_CAPTURE_SERVICE"
                val shortName = "$packageName/.service.ScreenCaptureService"
                enabledServices.split(SERVICE_SEPARATOR).any { entry ->
                    val normalized = entry.trim()
                    normalized.equals(fullName, ignoreCase = true) ||
                        normalized.equals(shortName, ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "isAccessibilityServiceEnabled: 查询失败", e)
            false
        }

    /**
     * 跳转悬浮窗权限设置页。
     *
     * 必须用 package: 形式的 data uri，否则部分 ROM 会打开「所有应用的悬浮窗列表」，
     * 用户还要再找一次本应用。
     */
    fun openOverlaySettings(context: Context) {
        val intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startSafely(context, intent, "openOverlaySettings")
    }

    /**
     * 跳转无障碍设置页。
     *
     * ACTION_ACCESSIBILITY_SETTINGS 不带 data，只能落到无障碍服务总列表，
     * 这是系统限制（没有公开的「直达某个服务」Action）。
     */
    fun openAccessibilitySettings(context: Context) {
        val intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        startSafely(context, intent, "openAccessibilitySettings")
    }

    /**
     * 跳转通知设置页。
     *
     * ACTION_APP_NOTIFICATION_SETTINGS 要求 API 26+；本应用 minSdk 30，
     * 但仍保留版本分支以便未来降低 minSdk 时不至于崩溃。
     */
    fun openNotificationSettings(context: Context) {
        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    // EXTRA_APP_PACKAGE 是 API 26+ 的官方写法，旧键名只是为了兼容更老 ROM
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startSafely(context, intent, "openNotificationSettings")
    }

    /**
     * 通知权限是否已授予。
     *
     * POST_NOTIFICATIONS 是 API 33 才引入的运行时权限，低版本没有这个概念，
     * 因此低版本恒为 true，否则会让前台服务在 Android 11/12 上误判为「无通知权限」。
     */
    fun isNotificationPermissionGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * 是否已加入电池优化白名单。
     *
     * 未加入白名单时，Doze / 省电策略会在息屏后冻结进程，
     * 悬浮球与前台服务会被回收，用户表现为「过一会儿就失效了」。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "isIgnoringBatteryOptimizations: 查询失败", e)
            false
        }

    /**
     * 请求加入电池优化白名单。
     *
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 会弹出系统对话框（需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * 权限，已在清单中声明）；部分 ROM 移除了该对话框，这时回退到白名单列表页让用户手动勾选。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        val directIntent =
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                packageUri,
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(directIntent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "requestIgnoreBatteryOptimizations: 直接请求不可用，回退到列表页", e)
            val fallback =
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startSafely(context, fallback, "requestIgnoreBatteryOptimizations")
        } catch (e: SecurityException) {
            // 个别 ROM 即使声明了权限也会抛 SecurityException，同样回退
            Log.w(TAG, "requestIgnoreBatteryOptimizations: 权限被拒绝，回退到列表页", e)
            val fallback =
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startSafely(context, fallback, "requestIgnoreBatteryOptimizations")
        } catch (e: Exception) {
            Log.w(TAG, "requestIgnoreBatteryOptimizations: 跳转失败", e)
        }
    }

    /**
     * 尝试跳转厂商自启动管理页。
     *
     * 每个 ROM 的组件名都不一样且都是「非公开 API」，Google 没有统一入口，
     * 所以只能按 Build.MANUFACTURER 逐个尝试；找不到时返回 false，
     * 由调用方回退到应用详情页（用户可在这里手动找「自启动」开关）。
     *
     * @return true 表示成功打开了某个厂商页面
     */
    fun openVendorAutoStartSettings(context: Context): Boolean {
        for (component in autoStartComponents(vendorName())) {
            if (startComponent(context, component)) {
                return true
            }
        }
        // 所有厂商页面都打不开时，至少把用户送到应用详情页
        return startComponent(
            context,
            ComponentName(
                "com.android.settings",
                "com.android.settings.applications.InstalledAppDetails",
            ),
        ) ||
            startAppDetails(context)
    }

    /**
     * 厂商标识（稳定 ASCII，用于路由与判断）。
     *
     * 刻意不返回「小米 / 华为」这类可读名称：那样会把**展示文案**和**路由键**混在一起，
     * 界面换成日文时既没法翻译，也容易在 when 分支里写错字符串。
     * 展示名称请用 [vendorDisplayName]。
     *
     * 注意 Honor 已从华为独立，但系统仍沿用 EMUI 的包名，因此与华为合并处理。
     */
    fun vendorKey(): String {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: return VENDOR_OTHER
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> VENDOR_XIAOMI
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> VENDOR_HUAWEI
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> VENDOR_OPPO
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> VENDOR_VIVO
            manufacturer.contains("samsung") -> VENDOR_SAMSUNG
            else -> VENDOR_OTHER
        }
    }

    /**
     * 厂商展示名称（按当前界面语言）。
     *
     * @return null 表示非定制 ROM（原生 Android / 未识别厂商），调用方据此决定是否展示提示
     */
    fun vendorDisplayName(context: Context): String? =
        when (vendorKey()) {
            VENDOR_XIAOMI -> context.getString(R.string.vendor_xiaomi)
            VENDOR_HUAWEI -> context.getString(R.string.vendor_huawei)
            VENDOR_OPPO -> context.getString(R.string.vendor_oppo)
            VENDOR_VIVO -> context.getString(R.string.vendor_vivo)
            VENDOR_SAMSUNG -> context.getString(R.string.vendor_samsung)
            else -> null
        }

    /** 兼容旧调用：厂商展示名称（中文），仅在没有 Context 的场景使用 */
    @Deprecated("请改用 vendorDisplayName(context) 以支持多语言", ReplaceWith("vendorDisplayName(context)"))
    fun vendorName(): String = vendorKey()

    /**
     * 按厂商返回自启动页组件候选列表（有序，命中即返回）。
     *
     * 同一厂商不同系统版本会换 Activity 名，所以每个厂商都可能给多个候选；
     * 这些类名都是逆向 ROM 得到的，随时可能失效，因此必须整体包在 try/catch 里。
     */
    private fun autoStartComponents(vendor: String): List<ComponentName> =
        when (vendor) {
            VENDOR_XIAOMI ->
                listOf(
                    // MIUI 的自启动管理在安全中心内，组件名多年来保持稳定
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    ),
                )

            VENDOR_HUAWEI ->
                listOf(
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    ),
                    // EMUI 老版本路径
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity",
                    ),
                )

            VENDOR_OPPO ->
                listOf(
                    // ColorOS 新版本
                    ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    ),
                    // ColorOS 与 realme UI 的老包名
                    ComponentName(
                        "com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity",
                    ),
                )

            VENDOR_VIVO ->
                listOf(
                    // Funtouch OS / OriginOS 的后台启动管理
                    ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    ),
                )

            VENDOR_SAMSUNG ->
                listOf(
                    // One UI 的「省电模式」页，其中包含后台运行限制开关
                    ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.lool.activity.SmpsPowerSavingActivity",
                    ),
                )

            else -> emptyList()
        }

    private fun startComponent(
        context: Context,
        component: ComponentName,
    ): Boolean =
        try {
            val intent =
                Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // 组件不存在是正常情况（机型/版本不匹配），继续试下一个候选
            false
        } catch (e: SecurityException) {
            // 该 Activity 未导出时会被系统拦截
            false
        } catch (e: Exception) {
            Log.w(TAG, "startComponent: 跳转失败, component=$component", e)
            false
        }

    /** 最终兜底：应用详情页，用户可在此找到「自启动 / 后台运行」相关开关 */
    private fun startAppDetails(context: Context): Boolean =
        try {
            val intent =
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startAppDetails: 跳转失败", e)
            false
        }

    /** 统一的「安全启动」封装：任何失败都只写日志，不向上抛 */
    private fun startSafely(
        context: Context,
        intent: Intent,
        tag: String,
    ) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "$tag: 系统未提供该设置页", e)
        } catch (e: Exception) {
            Log.w(TAG, "$tag: 跳转失败", e)
        }
    }

    /** 未识别厂商的兜底标识 */
    private const val VENDOR_OTHER = "other"

    private const val VENDOR_XIAOMI = "xiaomi"
    private const val VENDOR_HUAWEI = "huawei"
    private const val VENDOR_OPPO = "oppo"
    private const val VENDOR_VIVO = "vivo"
    private const val VENDOR_SAMSUNG = "samsung"

    /** ENABLED_ACCESSIBILITY_SERVICES 里多个服务用冒号分隔 */
    private const val SERVICE_SEPARATOR = ":"
}
