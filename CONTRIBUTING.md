# 贡献指南 · Contributing to Screenshot AI

感谢你愿意为 **Screenshot AI（截图 AI 助手）** 贡献代码、文档或想法！🎉

本文档说明如何搭建开发环境、遵守哪些规范、以及如何提交一个能被顺利合并的 PR。
请在开始之前花几分钟读完，能省下大量来回沟通的时间。

- [行为准则](#行为准则)
- [开发环境](#开发环境)
- [分支模型](#分支模型)
- [提交信息规范](#提交信息规范)
- [代码风格](#代码风格)
- [如何新增一个 AI Provider](#如何新增一个-ai-provider)
- [测试要求](#测试要求)
- [PR 流程](#pr-流程)
- [Issue 规范](#issue-规范)

---

## 行为准则

- 保持友善与专业，对事不对人。
- 不接受任何形式的骚扰、歧视、人身攻击与政治争论。
- 提交的代码必须是你自己编写的，或来自许可证兼容的开源项目并已注明出处。
- 本项目使用 **GPLv3**，你贡献的代码将同样以 GPLv3 授权。

---

## 开发环境

### 必需

| 项目 | 版本 |
| --- | --- |
| JDK | **17** |
| Android Studio | **Ladybug**（2024.2.1）或更新 |
| Android SDK Platform | **API 34** |
| Gradle | **8.9**（用仓库自带 wrapper，不要用系统 Gradle） |
| 真机 / 模拟器 | Android 11（API 30）及以上 |

> ⚠️ 悬浮球、无障碍截图、前台服务等功能**在模拟器上不完整**，涉及 `service/`、`overlay/`
> 的改动请务必在**真机**上验证。模拟器可以跑 UI 与单元测试。

### 首次准备

```bash
git clone https://github.com/MapleYazuu/Screenshot-AI.git screenshot-ai
cd screenshot-ai

# 让 gradlew 可执行（Windows 上忽略此步，直接用 gradlew.bat）
chmod +x gradlew

# 编译 Debug 包
./gradlew :app:assembleDebug

# 跑单元测试
./gradlew :app:testDebugUnitTest
```

### Gradle 发行版镜像

仓库自带的 `gradle/wrapper/gradle-wrapper.properties` **已默认指向腾讯云镜像**，
国内网络无需任何额外配置：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip
validateDistributionUrl=false
```

如果你在海外或想改用官方源，可以本地改成
`https\://services.gradle.org/distributions/gradle-8.9-bin.zip`，
但**请勿把这个改动提交进 PR**（CI 在 GitHub 上运行，本地换源只会让 diff 变脏）。

### 常用命令

```bash
./gradlew :app:assembleDebug            # 编译 Debug APK
./gradlew :app:testDebugUnitTest        # 运行单元测试
./gradlew :app:testDebugUnitTest --tests "com.screenshot.ai.ai.*"   # 只跑某个包
./gradlew :app:ktlintCheck                   # 代码风格检查
./gradlew :app:ktlintFormat                  # 自动格式化
./gradlew clean                         # 清理构建产物
```

---

## 分支模型

- `main` 是**唯一长期分支**，始终保持可编译、可发布。
- 所有改动通过 **Pull Request** 合入，不直接推送到 `main`。
- 分支命名建议：

| 前缀 | 用途 | 示例 |
| --- | --- | --- |
| `feat/` | 新功能 | `feat/moonshot-provider` |
| `fix/` | 缺陷修复 | `fix/overlay-crash-on-rotate` |
| `docs/` | 仅文档 | `docs/byok-cost-tips` |
| `refactor/` | 重构（行为不变） | `refactor/sse-reader` |
| `chore/` | 构建、依赖、CI | `chore/bump-okhttp` |
| `test/` | 仅测试 | `test/image-utils` |

- 请基于最新的 `main` 开分支，并在提交 PR 前先 `git rebase main`（保持线性历史，避免无意义的
  merge commit）。

---

## 提交信息规范

本项目采用 **[Conventional Commits](https://www.conventionalcommits.org/zh-hans/v1.0.0/)**。
提交信息可以用中文或英文，但**同一仓库内请保持一致**，推荐中文。

### 格式

```text
<type>(<scope>): <subject>

<body>

<footer>
```

- `type`：必填，见下表。
- `scope`：可选，建议使用模块名，如 `ai`、`overlay`、`service`、`ui`、`data`、`ci`、`docs`。
- `subject`：必填，**不超过 72 个字符**，不以句号结尾，使用祈使句（「新增」而不是「新增了」）。
- `body`：可选，说明**为什么**这么改，以及有哪些取舍。
- `footer`：可选，`Closes #12`、`BREAKING CHANGE: ...` 等。

### type 取值

| type | 含义 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `docs` | 仅文档变更 |
| `style` | 格式调整（不影响逻辑，如缩进、空行） |
| `refactor` | 重构（既非新功能也非修复） |
| `perf` | 性能优化 |
| `test` | 新增或修改测试 |
| `build` | 构建系统或依赖变更 |
| `ci` | CI 配置变更 |
| `chore` | 其他杂项 |

### 中文示例

```text
feat(ai): 新增 Moonshot Provider 支持

Moonshot 的接口与 OpenAI 完全兼容，直接继承 OpenAiCompatibleProvider
即可，只需覆盖 displayName 与 baseUrl。

Closes #42
```

```text
fix(overlay): 修复旋转屏幕后结果小窗错位的问题

WindowManager.LayoutParams 未随配置变更更新，导致 x/y 坐标仍是旧值。
改为在 onConfigurationChanged 中重新计算并 updateViewLayout。

Fixes #57
```

```text
docs(byok): 补充火山方舟模型名即接入点的说明
```

```text
refactor(ai)!: 重命名 StreamCallback.onToken 为 onDelta

BREAKING CHANGE: 自定义 Provider 需要同步更新回调方法名。
```

### 提交前自查

- [ ] 一个提交只做一件事，不要把格式化改动和逻辑改动混在一起。
- [ ] 提交信息能让人**不看 diff 就大致知道改了什么**。
- [ ] 不提交 `local.properties`、`*.iml`、`.idea/`、`build/` 等本地产物。

---

## 代码风格

统一风格由 [`.editorconfig`](.editorconfig) 与 **ktlint** 强制约束，请在 IDE 中启用
EditorConfig 支持，并在提交前运行：

```bash
./gradlew :app:ktlintCheck     # 检查
./gradlew :app:ktlintFormat    # 自动修复大部分问题
```

### 硬性规则

| 规则 | 要求 |
| --- | --- |
| 缩进 | **4 个空格**（不使用 Tab；XML / JSON / YAML / TOML 为 2 个空格） |
| 行宽 | **最大 120 列** |
| 换行符 | **LF**，文件末尾保留一个空行，行尾不留空格 |
| 编码 | UTF-8 |
| 注释 | **必须使用中文注释**，且解释「为什么」而不是复述「做了什么」 |
| 命名 | 类 `UpperCamelCase`；函数/属性 `lowerCamelCase`；常量 `UPPER_SNAKE_CASE` |
| 导入 | 不用通配符导入（`no-wildcard-imports` 已按 Android 惯例关闭，但仍请显式导入） |
| 尾随逗号 | 允许（`ij_kotlin_allow_trailing_comma = true`） |

### Kotlin 约定

- **可见性最小化**：默认 `private`，需要跨模块使用才放宽。
- **空安全**：优先用不可空类型与 `?:` 兜底，避免 `!!`。确需 `!!` 时写明为何不可能为空。
- **协程**：IO 操作必须切到 `Dispatchers.IO`；不要在网络回调里直接更新 UI。
- **Compose**：
  - `@Composable` 函数使用 `UpperCamelCase`（ktlint 规则已为此放开）。
  - 状态用 `remember` / `collectAsStateWithLifecycle`，不要在 Composable 里做重活。
  - 可预览的组件加 `@Preview`。
- **字符串**：用户可见文案统一放 `res/values/strings.xml`，不要在 Kotlin 里硬编码中文 UI 文案。
- **日志**：使用统一 Tag（如 `ScreenshotAi-FloatingService`），**绝对不要打印 API Key 或截图 Base64**。

### 提交前格式化

```bash
./gradlew :app:ktlintFormat
./gradlew :app:ktlintCheck
```

CI 会跑 `:app:ktlintCheck`，格式不通过会被直接打回。

---

## 如何新增一个 AI Provider

完整的分步教程与可复制的代码模板见 **[docs/adding-a-provider.md](docs/adding-a-provider.md)**，
这里给出流程概览：

1. **确定接入方式**
   - 接口兼容 OpenAI `/chat/completions` → **继承 `OpenAiCompatibleProvider`**（只声明元数据，
     协议逻辑为零；需要微调时覆写它的钩子）。
   - 协议完全不同（如 Anthropic 的 `/v1/messages`）→ **直接实现 `AiProvider` 接口**，自定义
     请求体、鉴权头与 SSE 解析。

2. **写实现**
   放到 `app/src/main/java/com/screenshot/ai/ai/providers/` 下，声明为 **`open class`**，
   并实现两个方法：流式 `chatStream(apiKey, baseUrl, model, messages, temperature, callback)`
   与非流式连通性测试 `testConnection(apiKey, baseUrl, model): String?`。
   注意两条硬性约定：**失败一律走 `callback.onError`（不要向上抛异常）**，
   而协程取消信号在回调 `AiException.Cancelled` 之后**必须继续上抛**。

3. **声明模型清单**
   每个模型必须是 `AiModel`，并**如实标注 `supportsVision`**：
   支持图片输入填 `true`，纯文本模型填 `false`（应用会自动走 ML Kit OCR 降级）。
   模型 id、展示名、Base URL 必须与厂商文档一致；
   同时用 `override val supportsVision get() = models.any { it.supportsVision }` 汇总到 Provider 级。

4. **注册到 `AiProviderRegistry`**
   在注册表中**追加**新的 Provider 实例（`MoonshotProvider()`），保证设置页能列出该厂商、能切换模型。
   ⚠️ **不要调整已有 Provider 的顺序** —— 顺序影响 `AiProviderRegistry.default`，
   而 `ProviderRegistryTest` 把 id 与顺序钉死了。

5. **补单元测试**
   至少覆盖：请求体拼装、鉴权头、SSE 分片解析（含跨分片截断的事件）、错误码到 `AiException`
   的映射。**测试不得发起真实网络请求**，请用本地构造的响应串或 MockWebServer。
   新增 Provider 后 `ProviderRegistryTest` 会自动遍历到它，请确认通用契约断言全部通过。

6. **更新文档**
   - README 的 [API Key 配置说明 (BYOK)](README.md#api-key-配置说明-byok) 表格中补一行：
     Provider / 申请地址 / 默认 Base URL / 内置模型是否支持视觉。
   - 如协议有特殊之处，在 `docs/adding-a-provider.md` 的「注意事项」中补一段。

7. **自测**
   ```bash
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:assembleDebug
   ```
   再用真实 Key 在真机上跑通「测试连接」与一次完整的截图问答。

---

## 测试要求

| 改动范围 | 要求 |
| --- | --- |
| `ai/`（Provider、SSE 解析、请求拼装） | **必须有单元测试**，放在 `app/src/test/java/...` |
| `data/`（Room DAO、Repo、DataStore 映射） | 建议有单元测试；DAO 可用 Room in-memory 测试 |
| `util/`（ImageUtils、TimeUtils 等纯逻辑） | **必须有单元测试** |
| `ui/`、`overlay/`、`service/` | 无法稳定单测，请在 PR 描述中写明**真机验证步骤与机型** |
| 仅文档 / 注释 | 无需测试 |

```bash
./gradlew :app:testDebugUnitTest
```

新增测试时请遵循：

- 测试类与被测类同包同名 + `Test` 后缀，例如 `SseReaderTest`。
- 测试方法名用中文反引号写法描述行为，例如：
  ```kotlin
  @Test
  fun `跨分片截断的 SSE 事件能被正确拼接`() { ... }
  ```
- 一个测试只验证一个行为；失败信息要能直接看出哪里不对。
- **不要在测试里放真实 API Key，也不要发起真实网络请求。**

---

## PR 流程

1. **先开 Issue**（可选但推荐）：大改动（新增 Provider、改架构、加依赖、改数据表结构）请先开
   Issue 讨论，避免白写。
2. **Fork + 开分支**：从最新 `main` 拉分支，命名见[分支模型](#分支模型)。
3. **小步提交**：提交信息遵循 [Conventional Commits](#提交信息规范)。
4. **自测通过**：
   ```bash
   ./gradlew :app:ktlintCheck
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:assembleDebug
   ```
5. **发起 PR**：填写 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md)，逐项勾选自查清单，
   **截图/录屏说明 UI 变化**。
6. **响应 Review**：维护者会尽量在几天内回复；请用追加提交或 rebase 的方式处理意见，
   不要 force-push 掉已经讨论过的内容。
7. **合入**：通常使用 **Squash merge**，最终提交信息由维护者整理。

### PR 会被直接打回的情况

- 破坏 `main` 的编译或测试。
- 引入与项目定位冲突的功能（例如云端中转服务器、账号体系、内购、
  未声明的统计/广告 SDK）。
- 引入闭源或不兼容 GPLv3 的依赖。
- 代码无中文注释、格式不符合 `.editorconfig`。
- 提交了真实 API Key、密钥文件或他人版权代码。

---

## Issue 规范

- 提交前请先搜索已有 Issue，避免重复。
- 请使用仓库提供的模板：
  - [Bug 报告](.github/ISSUE_TEMPLATE/bug_report.yml)
  - [功能建议](.github/ISSUE_TEMPLATE/feature_request.yml)
- 一个 Issue 只描述一个问题。
- 提问与开放讨论请走
  [Discussions](https://github.com/MapleYazuu/Screenshot-AI/discussions)（Issue 模板页也提供了入口）。

### Bug 报告请务必附上

1. **设备型号 + 系统版本**（例如 小米 14 / HyperOS 1.0 / Android 14）。
2. **App 版本号**（设置页 → 关于）。
3. **AI Provider 与模型名**（例如 DeepSeek / `deepseek-flash`）。
4. **复现步骤**：精确到「点了哪里、看到什么」。
5. **期望结果 vs 实际结果**。
6. **日志或截图**：可用 `adb logcat | grep ScreenshotAi` 抓取相关日志。
   **提交前请务必抹掉 API Key 与其他敏感信息。**
7. **权限状态**：无障碍服务与悬浮窗权限是否已开启、通知权限是否已授予。

### 好的 Issue 标题示例

```text
[BUG] 长按悬浮球展开菜单后，旋转屏幕导致结果小窗错位（小米14 / Android 14）
[FEAT] 希望新增 Moonshot (Kimi) Provider
```

---

再次感谢你的贡献！本项目由 **MapleYazuu** 开发，GPLv3 开源，与社区共同维护。
