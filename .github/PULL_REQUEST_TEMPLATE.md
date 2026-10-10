# Pull Request

<!--
  感谢你的贡献！请填写以下内容，并逐项确认自查清单。
  提交前请确保标题符合 Conventional Commits 规范，例如：
    feat(ai): 新增 Moonshot Provider 支持
    fix(overlay): 修复旋转屏幕后结果小窗错位的问题
-->

## 变更说明

<!-- 这个 PR 做了什么？为什么这么做？ -->

-

## 变更类型

<!-- 勾选一项或多项 -->

- [ ] 🐞 Bug 修复（fix）
- [ ] ✨ 新功能（feat）
- [ ] ♻️ 重构（refactor，行为不变）
- [ ] ⚡ 性能优化（perf）
- [ ] 📝 文档（docs）
- [ ] 🎨 代码格式 / 风格（style）
- [ ] 🔧 构建 / 依赖 / CI（build / ci）
- [ ] ✅ 测试（test）

## 关联 Issue

<!-- 用 Closes / Fixes / Refs 关联，没有则填「无」 -->

Closes #

## 测试方式

<!-- 说明你如何验证这次改动，越具体越好；UI 改动请附截图或 GIF -->

### 已执行的命令

```bash
./gradlew ktlintCheck
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

### 验证步骤

1.
2.
3.

### 验证环境

| 项目 | 内容 |
| --- | --- |
| 设备型号 | |
| 系统版本 | |
| App 版本 / 分支 | |
| 相关 Provider 与模型 | |

## 截图 / 录屏

<!-- UI 或悬浮窗相关改动请务必附上；无关可删除本节 -->

| 改动前 | 改动后 |
| --- | --- |
|  |  |

## 自查清单

- [ ] 代码可以编译通过（`./gradlew :app:assembleDebug`）
- [ ] **已运行 `./gradlew ktlint`**（`ktlintCheck` 无报错）
- [ ] **代码包含中文注释**，且注释解释的是「为什么」而非复述「做了什么」
- [ ] 缩进 4 空格、行宽不超过 120 列、文件以 LF 换行并保留末尾空行
- [ ] 已运行 `./gradlew :app:testDebugUnitTest`，全部通过
- [ ] 新增/修改的核心逻辑（`ai/`、`data/`、`util/`）已补充单元测试
- [ ] 未提交任何真实 API Key、密钥文件、`local.properties` 或本地网络专用配置
- [ ] 未引入闭源、与 GPLv3 不兼容的依赖，也没有新增与项目定位冲突的功能
      （云端中转、账号体系、内购、统计/广告 SDK）
- [ ] 提交信息符合 [Conventional Commits](https://github.com/MapleYazuu/Screenshot-AI/blob/main/CONTRIBUTING.md#提交信息规范) 规范
- [ ] 已同步更新相关文档
      （[README](https://github.com/MapleYazuu/Screenshot-AI/blob/main/README.md) /
      [docs/](https://github.com/MapleYazuu/Screenshot-AI/tree/main/docs) /
      [CONTRIBUTING](https://github.com/MapleYazuu/Screenshot-AI/blob/main/CONTRIBUTING.md)）
- [ ] 如果新增了 AI Provider，已更新 README 的 Provider 表格，并如实标注 `supportsVision`

## 补充说明

<!-- 需要 Reviewer 特别关注的地方、已知限制、后续计划等 -->
