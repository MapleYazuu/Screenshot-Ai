// ============================================================================
// app 模块构建脚本
// 截图 AI 助手：Compose 主界面 + 传统 View 悬浮窗 + Room + DataStore
// ============================================================================
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // 代码风格检查：必须应用在真正承载 Kotlin 源码的模块上，
    // 插件才会为各 source set 建出 ktlintMainSourceSetCheck 等任务。
    alias(libs.plugins.ktlint)
}

// ---------------------------------------------------------------------------
// ktlint：只检查源码，跳过 build 产物；具体规则开关见根目录 .editorconfig
//   ./gradlew :app:ktlintCheck    检查
//   ./gradlew :app:ktlintFormat   自动修复
// ---------------------------------------------------------------------------
ktlint {
    version.set(libs.versions.ktlint.get())
    filter {
        exclude { element -> element.file.path.contains("${File.separator}build${File.separator}") }
    }
    // Android 源码目录不在默认的 src/main/kotlin 下，交由插件识别 android sourceSets
    android.set(true)
}

android {
    namespace = "com.screenshot.ai"
    // compileSdk 34（Android 14）：满足 Compose BOM 2024.05 与 ML Kit 的要求，
    // 同时避免依赖 Android 15 平台，方便更多环境离线构建。
    compileSdk = 34

    // ---------------------------------------------------------------------
    // 发布签名
    //
    // 本地开发不接触任何密钥：找不到密钥库时直接回退到 debug 签名，保证
    // `./gradlew assembleRelease` 在没配密钥的机器上也能跑通（用于验证 R8 规则）。
    // CI（.github/workflows/android.yml）在打 tag 时会从 Secrets 还原密钥库，
    // 并通过下列环境变量注入，因此这里的变量名必须与 workflow 保持一致：
    //   KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
    // ---------------------------------------------------------------------
    val releaseKeystorePath: String? =
        System.getenv("KEYSTORE_PATH")
            ?: rootProject.file("keystore/release.jks").takeIf { it.exists() }?.absolutePath
    val hasReleaseSigning = releaseKeystorePath != null && file(releaseKeystorePath).exists()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.screenshot.ai"
        minSdk = 30 // 最低支持 Android 11
        targetSdk = 34
        versionCode = 310
        versionName = "3.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 资源裁剪：必须覆盖全部 5 种界面语言。
        // 之前只保留了 zh/en，导致 Release 包里的 ja/ko/ru 字符串被直接裁掉，
        // 用户在设置里切到日/韩/俄时界面仍显示英文（资源回退），
        // 这也是 Release 与 Debug 行为不一致的一个来源。
        resourceConfigurations += listOf("zh", "en", "ja", "ko", "ru")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有正式密钥就用正式密钥，否则退回 debug 签名，
            // 让「验证 release 构建」不必先准备密钥库。
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
        // 诊断构建类型：与 release 完全一致，但关闭 R8 混淆与资源收缩。
        // 用途：当 Release 出现「打开即闪退」而 Debug 正常时，
        // 安装本 APK 可快速确认崩溃是否由混淆/收缩引起。
        // 签名与 release 相同（CI 无密钥时回退 debug 签名以便本地构建）。
        create("releaseNoMinify") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles("proguard-rules.pro")
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs +=
            listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-Xjvm-default=all",
            )
    }

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/DEPENDENCIES",
                    "/META-INF/LICENSE*",
                    "/META-INF/*.kotlin_module",
                )
        }
    }

    // 单元测试使用真实 JSON 实现
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // ---------- AndroidX 基础 ----------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ---------- Compose ----------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---------- 数据存储 ----------
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // ---------- 网络 ----------
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // ---------- 图片 / OCR ----------
    implementation(libs.coil.compose)
    // 中文 OCR 模型同时支持中英文识别
    implementation(libs.mlkit.text.recognition.chinese)

    // ---------- Markdown 渲染 ----------
    // AI 回复悬浮窗（View）与提问历史（Compose 内嵌 AndroidView）统一用 Markwon 渲染
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)

    // ---------- 单元测试 ----------
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
    testImplementation(libs.mockk)

    // ---------- 仪器测试 ----------
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
