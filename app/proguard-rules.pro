# ============================================================================
# R8 / ProGuard 规则
#
# 背景：Release 开启 isMinifyEnabled + isShrinkResources 后曾出现「打开即闪退」。
# 排查原则：宁可多保留、不可漏保留。以下规则按库分类补齐，
# 每一条都标注了保留原因，后续删减时先读注释。
# ============================================================================

# ---------- 通用：行号 / 注解 / 泛型签名 ----------
# 注解与泛型签名是 kotlinx.serialization / Room 反射查找的根基，缺一不可
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-keepattributes Signature, Exceptions
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-renamesourcefileattribute SourceFile

# ---------- Kotlin ----------
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# ---------- kotlinx.coroutines ----------
# 协程内部有 volatile 字段参与状态机调试与反射，官方推荐规则
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ---------- kotlinx.serialization ----------
# @Serializable 类的序列化器由编译器插件生成，
# R8 若移除 serializer() 方法，DataStore 里存储的 Preset JSON 会解码失败
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.screenshot.ai.**$$serializer { *; }
-keepclassmembers class com.screenshot.ai.** {
    *** Companion;
}
-keepclasseswithmembers class com.screenshot.ai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------- OkHttp（直接 SSE 流式请求） ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
# @Entity / @Dao 由 KSP 生成的实现类直接引用，加保守规则防止优化阶段误删
-keep class com.screenshot.ai.data.db.** { *; }

# ---------- AndroidX Lifecycle / SavedState（Release 闪退核心修复） ----------
# 「CompositionLocal LocalLifecycleOwner not present」的直接诱因：
# R8 混淆/优化后 ViewTreeLifecycleOwner、SavedStateRegistry 等被改写或裁剪，
# 导致非 Activity 宿主里的 Compose 内容找不到生命周期宿主。
# 用户明确要求以下三条保留规则，缺一不可。
-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.compose.** { *; }

# ---------- ML Kit OCR（中文识别模型，加载失败会直接闪退） ----------
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-dontwarn com.google.android.gms.internal.mlkit_vision_**

# ---------- AndroidX Security (EncryptedSharedPreferences) ----------
# Keystore 加解密经反射调用，混淆后 API Key 会直接读不出来
-keep class androidx.security.crypto.** { *; }

# ---------- 本项目的「数据类 / sealed / enum」（用户要求的保守保留） ----------
# 模型层参与序列化与 Room，全部按字段级保留
-keep class com.screenshot.ai.data.model.** { *; }
-keep class com.screenshot.ai.data.prefs.** { *; }
-keep class com.screenshot.ai.ai.AiModel { *; }
-keep class com.screenshot.ai.ai.ChatMessage { *; }
-keep class com.screenshot.ai.ai.ChatMessage$** { *; }
# sealed class 子类会被 when 穷举分支引用，保留整个继承树防止 IllegalAccessError
-keep class com.screenshot.ai.ai.AiException { *; }
-keep class com.screenshot.ai.ai.AiException$* { *; }

# ---------- Provider 注册表与实现 ----------
-keep class com.screenshot.ai.ai.AiProviderRegistry { *; }
-keep class com.screenshot.ai.ai.providers.** { *; }

# ---------- 自定义 View（布局 XML 按类名实例化，混淆后找不到类） ----------
-keep class com.screenshot.ai.overlay.** { *; }

# ---------- 无障碍服务 / 前台服务 / 广播（由系统反射实例化） ----------
-keep class com.screenshot.ai.service.** { *; }
-keep class com.screenshot.ai.ScreenshotAiApp { *; }

# ---------- Compose ----------
# Compose 自带 AAR consumer 规则；此处仅兜底保留运行时注解
-dontnote androidx.compose.**

# ---------- 快捷设置 Tile（manifest 组件，显式保留以防 R8 收缩） ----------
-keep class com.screenshot.ai.service.FloatingToolbarTileService { *; }
-keep class com.screenshot.ai.service.FloatingServiceController { *; }

# ---------- Markdown 渲染（Markwon + commonmark，SpanFactory 反射/节点类需保留） ----------
-keep class io.noties.markwon.** { *; }
-keep class org.commonmark.** { *; }
-dontwarn io.noties.markwon.**
-dontwarn org.commonmark.**
