// ============================================================================
// 根项目构建脚本
// 仅声明插件版本（apply false），具体应用在各子模块中。
// ============================================================================
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // 代码风格检查（ktlint 官方 Gradle 插件）
    alias(libs.plugins.ktlint)
}

// ---------------------------------------------------------------------------
// 代码风格（ktlint）
//
// 使用 ktlint 官方 Gradle 插件。之所以不用自建 JavaExec 跑 ktlint-cli：
// ktlint-cli 的「外部依赖」变体缺少 CLI 自身的依赖（clikt 等），
// 而 shadow（fat jar）变体又需要额外的 variant 属性匹配，官方插件已封装好这些细节。
//
// 插件在根项目注册，会自动为所有子项目创建任务，因此用法是：
//   ./gradlew ktlintCheck     检查全部 Kotlin 源码
//   ./gradlew ktlintFormat    自动修复可修复的问题
//
// 具体规则开关（缩进、行长、通配符导入等）统一放在 .editorconfig，
// 避免构建脚本与编辑器配置两处打架。
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 清理任务别名：./gradlew cleanAll
// ---------------------------------------------------------------------------
tasks.register<Delete>("cleanAll") {
    group = "build"
    description = "清理根目录下的 build 产物"
    delete(rootProject.layout.buildDirectory)
}
