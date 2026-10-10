# BYOK 模式详解

> **BYOK = Bring Your Own Key（自带密钥）**
> Screenshot AI 不提供任何官方额度，也不做请求中转。你到模型厂商开放平台申请一个 API Key，
> 填进 App，截图就从你的手机**直接**发往那家厂商。

- [为什么采用 BYOK](#为什么采用-byok)
- [各厂商 Key 申请步骤与额度说明](#各厂商-key-申请步骤与额度说明)
- [自定义 Base URL](#自定义-base-url)
- [常见错误码对照表](#常见错误码对照表)
- [成本控制建议](#成本控制建议)
- [常见疑问](#常见疑问)

---

## 为什么采用 BYOK

| 维度 | BYOK（本项目采用） | 传统「内置额度」方案 |
| --- | --- | --- |
| 成本承担 | 用户自付，用多少花多少 | 作者垫付，额度用完就得停服 |
| 密钥存放 | 只在你手机本地（Keystore 加密） | 服务端集中存放大量用户密钥，一旦泄露影响面极大 |
| 数据链路 | 手机 → 厂商，**两跳** | 手机 → 中转服务器 → 厂商，**三跳**，中转方能看到全部截图 |
| 可用性 | 只要厂商在服务就能用，不依赖作者 | 作者服务器挂掉 = 全量用户不可用 |
| 模型自由度 | 6 家厂商 18 个内置模型随便切，可换 Base URL 接任意兼容网关 | 只能用作者选定的一两个模型 |
| 合规与隐私 | 作者不接触任何用户数据，无数据留存责任 | 中转方需承担数据留存与合规责任 |

对开源个人项目来说，BYOK 是**唯一能把「免费开源」和「不烧钱、不碰用户数据」同时做到**的模式。
代价是你要自己花 5 分钟申请一个 Key —— 下面是每一步的操作指引。

---

## 各厂商 Key 申请步骤与额度说明

> 💡 **通用提示**：申请时多数平台要求**实名认证**；新账号通常有免费额度或体验金，
> 但**免费额度的具体数值与有效期随时可能调整**，请以厂商控制台的实际显示为准。
> 本文不承诺任何具体额度的长期有效性。

### 1. DeepSeek（深度求索）

1. 打开 <https://platform.deepseek.com/>，用手机号或邮箱注册并登录。
2. 左侧菜单进入 **API Keys** → 点击「创建 API Key」。
3. 给 Key 起个名字（例如 `screenshot-ai`），创建后**立刻复制** —— 明文只显示这一次。
4. 到 **充值 / 账户余额** 页面按需充值（支付宝/微信）。

- **Base URL**：`https://api.deepseek.com/`
- **内置模型**：`deepseek-flash`（✅ 视觉）、`deepseek-chat`（纯文本）、`deepseek-reasoner`（纯文本）
- **额度说明**：新账号通常有一定体验额度；正式使用按 token 计费，价格相对低廉。
- **提示**：`deepseek-chat` / `deepseek-reasoner` 不支持图片，会被自动降级到 ML Kit 本地 OCR。

### 2. OpenAI

1. 打开 <https://platform.openai.com/> 注册并登录。
2. 进入 <https://platform.openai.com/api-keys> → **Create new secret key** → 复制保存。
3. 到 **Settings → Billing** 绑定支付方式并预充值（OpenAI 采用预付费额度制）。
4. 部分模型需要账号完成组织验证（Organization verification）后才能调用。

- **Base URL**：`https://api.openai.com/`
- **内置模型**：`gpt-4o`（✅）、`gpt-4o-mini`（✅）、`gpt-4-turbo`（✅）、`o3-mini`（纯文本）
- **额度说明**：无长期免费额度，必须充值；`gpt-4o-mini` 性价比最高。
- **提示**：国内直连 `api.openai.com` 通常**不可达**，需要使用自定义 Base URL 指向中转或代理
  （见下一节），并自行评估中转方是否可信。

### 3. Google Gemini

1. 打开 <https://aistudio.google.com/app/apikey>，用 Google 账号登录（部分区域需网络条件）。
2. 点击 **Create API key**，选择或新建一个 Google Cloud 项目。
3. 复制生成的 Key，形如 `AIza...`。

- **Base URL**：`https://generativelanguage.googleapis.com/v1beta/openai/`
  （注意：走的是 Google 提供的 **OpenAI 兼容端点**）
- **内置模型**：`gemini-1.5-flash`（✅）、`gemini-1.5-pro`（✅）、`gemini-2.0-flash`（✅）
- **额度说明**：AI Studio 提供免费层（有每分钟/每天请求数限制），超出后可绑定结算账号升级为付费层。
- **提示**：三个内置模型**全部支持视觉**，是最省事的入门选择。

### 4. Anthropic Claude

1. 打开 <https://console.anthropic.com/> 注册并登录。
2. 进入 **Settings → API Keys** → **Create Key** → 复制保存。
3. 到 **Plans & Billing** 充值（预付费额度制）。

- **Base URL**：`https://api.anthropic.com/`
- **内置模型**：`claude-3-5-sonnet-20241022`（✅）、`claude-3-5-haiku-20241022`（✅）、
  `claude-3-opus-20240229`（✅）
- **协议差异**：Anthropic 是**唯一**不走 OpenAI 兼容接口的厂商，使用 `/v1/messages`，
  鉴权头为 `x-api-key` + `anthropic-version`，而非 `Authorization: Bearer`。
  本项目已在 `AnthropicProvider` 中单独适配，你无需关心；但**自定义 Base URL 时必须指向
  支持原生 Anthropic 协议的端点**，OpenAI 兼容网关通常不适用。
- **额度说明**：无免费层，需充值。

### 5. 通义千问 Qwen（阿里云百炼 / DashScope）

1. 打开 <https://bailian.console.aliyun.com/?apiKey=1>，用阿里云账号登录（需实名认证）。
2. 在 **API-KEY 管理** 页创建 API-KEY → 复制保存。
3. 首次开通通常赠送一定量的免费 token 额度，用完后在控制台按量付费。

- **Base URL**：`https://dashscope.aliyuncs.com/compatible-mode/v1/`
  （`compatible-mode` 即 OpenAI 兼容模式）
- **内置模型**：`qwen-vl-max`（✅）、`qwen-vl-plus`（✅）、`qwen-plus`（纯文本）
- **提示**：`qwen-vl-*` 系列对**中文表格、票据、手写体**的识别效果不错，适合中文场景。

### 6. 豆包 Doubao（火山方舟 / 火山引擎）

1. 打开 <https://console.volcengine.com/ark>，用火山引擎账号登录（需实名认证）。
2. 进入 **API Key 管理** → 创建 API Key → 复制保存。
3. 在 **开通管理** 中开通你要用的模型（如 `doubao-vision-pro-32k`）。
4. 到 **在线推理** 页面确认模型的调用名称。

- **Base URL**：`https://ark.cn-beijing.volces.com/api/v3/`
- **内置模型**：`doubao-vision-pro-32k`（✅）、`doubao-vision-lite-32k`（✅）、`doubao-pro-32k`（纯文本）
- **⚠️ 特别注意**：火山方舟的模型调用名**可能不是模型名本身，而是你在控制台创建的
  「推理接入点 ID」**（形如 `ep-2024xxxxxx-xxxxx`）。如果调用返回 404，请到控制台复制接入点 ID，
  在设置页的**模型名**栏手动填入。
- **额度说明**：新用户通常有免费 token 额度，之后按量计费。

---

## 自定义 Base URL

设置页的 **Base URL** 可以覆盖内置默认值，指向任何兼容的端点。典型用途：

| 场景 | 说明 | 例子 |
| --- | --- | --- |
| **网络中转** | 国内无法直连某厂商时，通过第三方中转服务转发 | `https://your-relay.example.com/v1/` |
| **反向代理** | 自建 Nginx / Caddy 反代，统一收敛出口 IP、做访问日志与限流 | `https://ai-proxy.example.com/v1/` |
| **自建网关** | 用 one-api、LiteLLM 等网关把多家厂商聚合成一个 OpenAI 兼容端点，一套 Key 管全部模型 | `https://gateway.example.com/v1/` |
| **本地推理** | 局域网内的 Ollama / vLLM / LM Studio 等 OpenAI 兼容服务 | `http://192.168.1.10:11434/v1/` |
| **企业内网** | 公司统一提供的模型服务入口 | `https://llm.corp.internal/v1/` |

### 填写要求

**几乎不用操心** —— `UrlBuilder` 会把各种写法统一规整到同一个地址，同一份配置永远拼出同一个 URL：

| 你填的内容 | 实际请求的地址 |
| --- | --- |
| `https://api.deepseek.com/` | `https://api.deepseek.com/v1/chat/completions` |
| `https://api.deepseek.com` | `https://api.deepseek.com/v1/chat/completions` |
| `https://api.deepseek.com//`（多余斜杠） | `https://api.deepseek.com/v1/chat/completions` |
| `https://api.deepseek.com/v1` | `https://api.deepseek.com/v1/chat/completions` |
| `https://gw.example.com/openai/v1/` | `https://gw.example.com/openai/v1/chat/completions` |
| `https://gw.example.com/v1/chat/completions` | 原样使用（识别为完整地址） |

规则很简单：**填到版本层就够了**；如果路径里已经包含 `/v1`（`/v1beta`、`/v1/openai` 等也算），
就只追加 `/chat/completions`，否则追加 `/v1/chat/completions`。
自己把 `/chat/completions` 也拼上同样没问题，不会被重复拼接。

⚠️ 两个例外：

- **豆包（火山方舟）** 的地址自带 `/api/v3`，应用会直接追加 `/chat/completions`
  而**不会**再补 `/v1`，因此用默认地址即可；只有当你粘贴的是含 `/v1` 的 OpenAI 风格地址时，
  才会切回通用规则。
- **Anthropic** 走的是 `/v1/messages`，与上面这套 `/chat/completions` 规则无关。

### 安全提醒

⚠️ **中转方能看到你的 API Key 和每一次请求的完整内容（包括截图）。**
代理链路越长，信任面越大。建议：

- 优先直连官方端点；
- 必须用中转时，选择可自建、可审计的开源网关，并在网关侧单独签发子 Key；
- 不要把公司主账号的高权限 Key 交给第三方中转。

---

## 常见错误码对照表

| 状态码 | 厂商 message 关键词（示例） | 含义 | 处理方式 |
| --- | --- | --- | --- |
| **400** | `invalid_request_error` / `messages: ...` | 请求体不合法，常见于模型名写错或图片格式不被接受 | 核对模型名；换用支持视觉的模型；确认 Base URL 与厂商匹配 |
| **401** | `Authentication Fails` / `Incorrect API key` | Key 无效、拼错、被删除或已过期 | 重新复制 Key，注意**不要**带 `Bearer ` 前缀、引号或首尾空格 |
| **402** | `Insufficient Balance` | 账户余额不足（DeepSeek 常见） | 到厂商控制台充值 |
| **403** | `permission denied` / `model not enabled` | Key 有效但无权调用该模型，或未实名/未开通 | 在控制台开通对应模型；完成实名认证 |
| **404** | `model not found` / `Not Found` | 模型名或 Base URL 错误 | 优先检查模型名；火山方舟需填**推理接入点 ID**（`ep-xxxxxxxx`） |
| **413** | `Request entity too large` | 请求体过大（图片太大或上下文太长） | 打开**低质量模式**（长边 512px）；精简预设 Prompt；清理历史上下文 |
| **422** | `Unprocessable Entity` | 参数语义错误，常见于多模态字段格式不符 | 确认该模型 `supportsVision` 标注是否正确 |
| **429** | `Rate limit reached` / `quota exceeded` | 触发限流**或**额度耗尽 | 降低请求频率；查看控制台账单；付费账户通常立即恢复 |
| **500 / 502 / 503 / 504** | `internal error` / `overloaded` | 厂商服务端异常或过载 | 稍后重试；临时切换到其他 Provider |
| **网络层错误** | `Unable to resolve host` / `timeout` | DNS 失败、无网络、需要代理 | 检查网络；为该厂商配置自定义 Base URL |
| **SSL 错误** | `Certificate verify failed` | 中间人证书或系统时间错误 | 校正系统时间；避免使用来路不明的 TLS 中间代理 |

所有错误都会在界面上附带**厂商返回的原始 message**，排查时以那段原文为准。

---

## 成本控制建议

截图问答的成本主要来自**图片 token**——一张 1024px 的截图在部分厂商那里可能抵得上几百到上千个
文字 token。以下手段按见效程度排序：

### 1. 打开「低质量模式」（长边 512px）

- 默认图片长边压缩到 **1024px**；开启低质量模式后压到 **512px**。
- 视觉模型的图片 token 消耗大致与像素面积成正比，**512px 相比 1024px 面积只剩约 1/4**，
  图片部分的成本通常能降到三成左右。
- 512px 对**阅读正文文字**已经足够；只有需要看清小字、图表细节时才建议用 1024px。

### 2. 精简预设 Prompt

- 预设指令会**随每次请求发送**。一条 300 字的预设，乘以你的使用次数就是纯浪费。
- 把「你是一个专业的、善于总结的、乐于助人的 AI 助手……」这类角色铺垫删掉，只留**任务本身**。
- 反例（约 120 字）：
  > 请你作为一名专业的、经验丰富的、善于抓重点的 AI 助手，仔细阅读我发给你的这张屏幕截图，
  > 然后从多个角度、多个层次、尽可能全面地为我总结出其中最重要的内容……
- 正例（约 20 字）：
  > 总结这张截图的主要内容，输出 3 条要点。

### 3. 选对模型

| 需求 | 建议 |
| --- | --- |
| 日常总结、翻译 | 用便宜的小模型：`gpt-4o-mini`、`gemini-1.5-flash`、`qwen-vl-plus`、`doubao-vision-lite-32k` |
| 复杂推理、代码解释 | 再切到 `deepseek-reasoner`、`claude-3-5-sonnet-20241022` 等强模型 |
| 只是提取文字 | **不需要视觉模型** —— 选纯文本模型走本地 ML Kit OCR，图片部分成本直接归零 |

### 4. 用本地 OCR 替代图片

如果你的截图内容**以文字为主**（聊天记录、公告、文章），刻意选择一个
`supportsVision = false` 的模型（如 `deepseek-chat`），应用会用 ML Kit 在本地提取文字再发送。
好处是**图片 token 完全消失**，成本断崖式下降；代价是丢失版式、图表与图形信息。

### 5. 其他习惯

- **别把悬浮球当搜索框连点**：每次点击都是一次完整请求。
- **及时清理历史**：历史记录只影响本地存储，但把无关上下文带进请求会推高输入 token。
- **给 Key 设消费上限**：多数厂商控制台支持设置月度预算或告警，建议开启。
- **先用小模型试预设**：调 Prompt 的阶段用最便宜的模型，定型后再换强模型。

---

## 常见疑问

<details>
<summary><b>把 Key 填进 App 安全吗？</b></summary>

Key 使用 Android Keystore 中的主密钥做 **AES256-GCM** 加密后存入 EncryptedSharedPreferences，
明文不落盘；本项目没有任何服务器，Key 只会出现在发往你所选厂商的 HTTPS 请求头里。
完整分析见 [api-key-security.md](api-key-security.md)。
</details>

<details>
<summary><b>一个 Key 能用多家厂商吗？</b></summary>

不能。各家 Key 互不通用，需要分别申请，在设置页切换 Provider 时填入对应 Key（每个 Provider 各存一份）。
</details>

<details>
<summary><b>可以同时配置多个 Provider 快速切换吗？</b></summary>

可以。每个 Provider 的 Key 与 Base URL 独立保存，在设置页切换即可，已填的 Key 不会丢失。
</details>

<details>
<summary><b>免费额度用完了会怎样？</b></summary>

通常表现为 **429**（限流/额度耗尽）或 **402**（余额不足）。这不是 App 的 bug，
到厂商控制台充值或改换其他 Provider 即可。
</details>

<details>
<summary><b>为什么我看到的是「测试连接失败」而不是具体错误？</b></summary>

测试连接会同时校验网络连通性与鉴权。界面下方会附带 HTTP 状态码和厂商返回的原始 message，
请对照上文的[错误码对照表](#常见错误码对照表)排查。
</details>

---

[← 返回 README](../README.md) · [新增 Provider 指南 →](adding-a-provider.md) ·
[API Key 安全策略 →](api-key-security.md)
