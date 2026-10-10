// ============================================================================
// Gradle 设置文件：声明插件仓库、依赖仓库与参与构建的模块
// ============================================================================
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 禁止模块自行声明仓库，统一在此处管理
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ScreenshotAI"

// 主 App 模块
include(":app")
