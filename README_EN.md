## 😋😋😋Click to download now --->\[[!\[Download APK](https://img.shields.io/badge/Download-v3.1.0-blue)](https://github.com/MapleYazuu/Screenshot-Ai/releases)<--- Click to download now😋😋😋

# Screenshot AI · Screenshot AI Assistant

A desktop-resident Android floating ball: **Click to screenshot, AI provides streaming answers**. Take a screenshot → send it to the large model → answers appear word-for-word in a floating window, without interrupting what you are doing.

[!\[License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[!\[Android 11+](https://img.shields.io/badge/Android-11%2B%20(API%2030)-3DDC84.svg?logo=android\&logoColor=white)](https://developer.android.com/about/versions/11)
[!\[Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF.svg?logo=kotlin\&logoColor=white)](https://kotlinlang.org/)
[!\[Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg?logo=jetpackcompose\&logoColor=white)](https://developer.android.com/jetpack/compose)
[!\[Android CI](https://github.com/MapleYazuu/Screenshot-AI/actions/workflows/android.yml/badge.svg)](https://github.com/MapleYazuu/Screenshot-AI/actions/workflows/android.yml)
[!\[Download APK](https://img.shields.io/badge/Download-v3.1.0-blue)](https://github.com/MapleYazuu/Screenshot-Ai/releases)

<!--
The CI badge is automatically generated after pushing .github/workflows/android.yml to the main branch.
The warehouse address is also used by the "Check for Updates" function of the app, which is defined in
The GITHUB\_OWNER and GITHUB\_REPO of app/src/main/java/com/screenshot/ai/util/UpdateChecker.kt,
Please modify these three places simultaneously when renaming.

**Screenshot AI** is an open-source Android screenshot AI assistant in the form of a resident floating button on the desktop. It does not involve cloud intermediation, has no account system, and does not collect any user data - you fill in your own API Key, and screenshots are sent directly from your phone to the model vendor of your choice (BYOK, Bring Your Own Key).

\---

catalogue

\-\[Functional Features] (# Functional Features)
-\[Demo] (# Demo)
-\[Architecture] (# Architecture)
-\[Technology Stack] (# Technology Stack)
-\[Project Structure] (# Project Structure)
-\[Compilation Guide] (# Compilation Guide)

* [First Use / Permission Configuration](#First_Use--Permission_Configuration)
* [API Key Configuration Instructions (BYOK)](#api-key-configuration-instructions-byok)
-\[Built in preset commands] (# Built in preset commands)
-\[Permission Description] (# Permission Description)
* [Frequently Asked Questions (FAQ)](#frequently-asked-questions-faq)
* [Participate and Contribute](#Participate_and_Contribute)
-\[Open Source Protocol] (# Open Source Protocol)
* [Acknowledgements](#Acknowledgements)

\---

## Functional Features

### Core Interaction: Single-click to capture a screenshot, long-press to expand the menu

The floating ball is always on the screen, and there are only two ways to operate it. There is no need to memorize any gestures:

|Operation|Behavior|
|-|-|
|**Click on the floating ball**|Immediately capture the current screen → Send it to the AI using **the current preset command** → The answer is **streamed** and rendered into the floating result window|
|**Long press the floating ball**|Pop up 4 operation buttons: **Capture** / **Compose** / **History** / **Settings**|
|**Dragging the floating ball**|Freely move it to any edge of the screen, and its position will be remembered|

Four actions after long press:

|Button|Description|
|-|-|
|**Capture**|Manually triggering a screenshot + AI Q\&A is equivalent to clicking the floating ball|
|**Input Compose**|Manually enter/paste text, conduct pure text-based Q\&A, no screenshot required|
|**History**|Open the Room history page to review previous questions, screenshot thumbnails, and AI responses|
|**Settings**|Floating ball appearance, preset commands, model and API Key, image quality, auto-start at boot, etc.|

## 📱 Interface Preview

|Home|Demo|Button|Button expanded|Software icon|
|:-:|:-:|:-:|:-:|:-:|
|<img src="documents/images/home. jpg" width="250" alt="home"/>|<img src="documents/images/effect. jpg" width="250" alt="effect"/>|<img src="documents/images/button. jpg" width="250" alt="button"/>|<img src="documents/images/buttonunfolded. jpg" width="250" alt="button expand"/>|<img src="documents/images/icon. png" width="250" alt="software icon"/>|

```text
docs/images/
├─ home.png # Main interface
∝ - overlay actions. png # Long press the 4 operation buttons for expanding the floating ball
├─ result-window.png # Floating result window for AI streaming responses
├─ history.png # Room history page
├─ settings.png # Settings page (Models / API Key / Presets)
└── demo.gif # A complete operation screen recording lasting from 5 to 15 seconds
```

### Feature List

* 🎈 **Persistent floating ball on desktop** —— The foreground service remains active, without occupying recent tasks, and is always ready for use.
* 📸 **One-click screenshot** —— Capture the current screen through the accessibility service `canTakeScreenshot="true"` without requiring root access or prompting for system authorization.
* 🖼️ **Multimodal Direct Input** - Models supporting vision can directly receive Base64 JPEG images (maximum long side 1024px, low-quality mode 512px).
* 🔤 **Local OCR fallback** —— Models that do not support vision automatically switch to **ML Kit Chinese Text Recognition**, sending the extracted text to the model, which can also be used with a plain text model.
* ⚡ **SSE Streaming Output** —— Based on hand-written SSE parsing using OkHttp, answers are displayed on the screen word by word, without waiting for the entire paragraph to be generated.
* 🔌 **BYOK Multi-vendor** —— Integrated with **6** providers (DeepSeek / OpenAI / Google Gemini / Anthropic Claude / Tongyi Qianwen / Doubao), all supporting customizable Base URL.
* 🧩 **Pre-set Commands** - Comes with 5 built-in presets (screenshot summary, translate to Chinese, explain code, extract text, and solve problems/answer questions), which can be added, deleted, or modified by users. The first preset is used by default.
* 🗂️ **Room History** - Each Q\&A session (question, screenshot, answer, model, time) is saved locally and can be retrieved, reviewed, and deleted.
* ⚙️ **DataStore Configuration** —— All preferences (models, presets, floating ball position, image quality, streaming switch) are based on DataStore Preferences.
* 🔐 **Key Encryption** —— The API Key is encrypted using the Android Keystore master key with **AES256-GCM** and written to EncryptedSharedPreferences, ensuring that the plaintext is never stored on disk.
* 🚫 **Privacy First** —— Screenshots are only saved in the application's private directory `filesDir/screenshots`, **not in the photo album**; Backup is disabled in the Manifest.
* 🌙 **Compose Main Interface** - The main interface, history, presets, settings, and about pages are all written in Jetpack Compose and follow the system's dark mode.
* 🚀 **Optional auto-start at boot** —— Optionally enable `RECEIVE\_BOOT\_COMPLETED`, and the floating ball will automatically restore after reboot.
* 🔋 **Battery optimization whitelist** - One-click access to `REQUEST\_IGNORE\_BATTERY\_OPTIMIZATIONS` to reduce the probability of being killed by the system in the background.
* 🔄 **Update Checker** —— Built-in `UpdateChecker` that can check for new versions (without automatic download or silent installation).

\---

## Architecture

```mermaid
flowchart TD
U\["User clicks on the floating ball<br/>Single click = screenshot and answer / Long press = expand menu"] --> FS\["FloatingService<br/>Foreground service + floating ball lifecycle"]
FS -->|"Click: Trigger screenshot Q\&A"| SCS\["ScreenCaptureService<br/>AccessibilityService · canTakeScreenshot"]
FS -->|"Long press: Pop up 4 operation buttons"| OM1\["OverlayManager<br/>Screenshot / Input / History / Settings"]

SCS --> IMG \["Capture the current screen bitmap"]
IMG --> VISION{"The current model<br/>supportsVision ? "}

VISION -->|"true Support vision"| IU\["ImageUtils<br/>Compress long edge 1024px / low quality 512px<br/>Encoding Base64 JPEG"]
VISION -->|"false: Vision not supported"| OCR\["OcrUtils · ML Kit Chinese Text Recognition<br/>Extracting plain text for downgrade"]

IU --> REG\["AiProviderRegistry<br/>Resolve implementation by ProviderId"]
OCR --> REG
REG --> SESS\["ChatSession<br/>Assembly system preset + message history + image"]
SESS --> PROV\["Specific Provider<br/>DeepSeek / OpenAI / Gemini / Claude / Qwen / Doubao"]

PROV --> NET\["ai/net · HttpClient + UrlBuilder<br/>OkHttp initiates a request"]
NET --> SSE\["SseReader<br/>parses text/event-stream fragments"]
SSE --> OM2\["OverlayManager result widget<br/>Compose/View word-by-word streaming rendering"]
OM2 --> DONE \["Answer completed<br/>Write to history + Copyable text"]

DONE --> ROOM\["Room Database<br/>screenshots / history<br/>Application Private Directory"]
SCS --> ROOM
FS <--> DS\["DataStore Preferences<br/>Model · Preset · Floating Ball Position · Image Quality"]
PROV -.->|"Read API Key"| KEY\["Android Keystore +<br/>EncryptedSharedPreferences<br/>AES256-GCM"]
DS -.-> KEY

subgraph LOCAL\["Local throughout"]
ROOM
DS
KEY
end

subgraph REMOTE\["HTTPS Direct Connection Manufacturer"]
NET
SSE
end
```

Key points of data flow:

1. **Screenshot**: Click on `FloatingService` → invoke `ScreenCaptureService` (an accessibility service, declared with `canTakeScreenshot="true"`) → obtain the current screen `Bitmap`.
2. **Stream according to model capability**: If `AiModel.supportsVision == true`, use `ImageUtils` for compression + Base64 encoding; otherwise, use `OcrUtils` (ML Kit Chinese recognition) to process the text as input.
3. **Request**: `AiProviderRegistry` retrieves the implementation based on the current `ProviderId` → `ChatSession` encapsulates the preset prompt, historical context, and image into a `ChatMessage` → `HttpClient` issues the request.
4. **Streaming echo**: `SseReader` parses `text/event-stream` block by block, and `OverlayManager` pushes incremental text to the small window, achieving word-by-word output.
5. **Storage**: After the result window is completed, write Room; for configuration changes, write DataStore; API Key only exists in EncryptedSharedPreferences in ciphertext form.

Except for the HTTPS requests sent by `ai/net/`, **no data leaves the device**.

\---

## Technology stack

|Module|Technology Selection|
|-|-|
|Development Language|Kotlin 2.0.21|
|Main interface UI|Jetpack Compose (Material 3, Dark mode)|
|Floating window UI|Traditional View + XML + `WindowManager` (`TYPE\_APPLICATION\_OVERLAY`)|
|Screenshot capability|`AccessibilityService` + `takeScreenshot` (`canTakeScreenshot="true"`)|
|Network|OkHttp + Handwritten SSE Streaming Parser (`SseReader`)|
|Historical Persistence|Room|
|Configuration storage|DataStore (Preferences)|
|Key Security|Android Keystore (AES256-GCM) + EncryptedSharedPreferences|
|Local OCR|ML Kit Chinese Text Recognition (Non-Visual Model Downgrade Solution)|
|Image Loading|Coil|
|Build System|Gradle Kotlin DSL + Version Catalog (`gradle/libs.versions.toml`)|
|Annotation Processing|KSP 2.0.21-1.0.28|
|Build Toolchain|AGP 8.5.2 / Gradle 8.9 / JDK 17|
|Version range|minSdk 30 (Android 11) / targetSdk 34 / compileSdk 34|
|License|GNU General Public License v3.0|

\---

## Project Structure

```text
screenshot-ai/
├── app/
│ └── src/
│ ├── main/
│ │ ├── java/com/screenshot/ai/
│ │ │ ├─ ScreenshotAiApp.kt # Application: Initialize application-level dependencies
│ │ │ ├─ ServiceLocator.kt # Lightweight Dependency Injection (DB / Repo / Prefs)
│ │ │ ├─ ai/ # AI abstraction layer
│ │ │ │ ├─ AiProvider.kt # Provider interface (chatStream / testConnection)
│ │ │ │ ├─ AiModel.kt # Model description (id / display name / supportsVision / context window)
│ │ │ │ ├─ ChatMessage.kt # Message model (system / user / assistant + image Base64)
│ │ │ │ ├─ ChatSession.kt # Session orchestration and cancellation
│ │ │ │ ├─ StreamCallback.kt # Stream callbacks (onStart / onChunk / onComplete / onError)
│ │ │ │ ├─ AiException.kt # Unified exception (HttpError / NetworkError / ParseError …)
│ │ │ │ ├─ AiProviderRegistry.kt # Provider registration and (Provider, Model) parsing
│ │ │ │ ├── net/ # HttpClient.kt · UrlBuilder.kt · SseReader.kt
│ │ │ │ └── providers/ # 6 built-in Providers + OpenAI compatible base class
│ │ │ │ ├─ OpenAiCompatibleProvider.kt # Compatible protocol base class (request assembly / authentication / SSE parsing)
│ │ │ │ ├── DeepSeekProvider.kt
│ │ │ │ ├── OpenAiProvider.kt
│ │ │ │ ├── GeminiProvider.kt
│ │ │ │ ├─ AnthropicProvider.kt # Unique independent implementation: /v1/messages
│ │ │ │ ├── QwenProvider.kt
│ │ │ │ └── DoubaoProvider.kt
│ │ │ ├─ service/ # Background resident services
│ │ │ │ ├─ FloatingService.kt # Floating ball foreground service
│ │ │ │ ├─ ScreenCaptureService.kt # Accessibility screenshot service (canTakeScreenshot)
│ │ │ │ ├─ OverlayManager.kt # Creation and scheduling of all floating windows
│ │ │ │ └── BootReceiver.kt # Boot receiver (optional)
│ │ │ ├─ overlay/ # 6 floating window Views
│ │ │ │ ├─ OverlayBubbleView.kt # The main body and dragging of the floating ball
│ │ │ │ ├─ OverlayActionPanelView.kt # 4 action buttons expanded by long press
│ │ │ │ ├─ OverlayResultWindowView.kt # Streaming result overlay window
│ │ │ │ ├─ OverlayInputPanelView.kt # manually entered input box
│ │ │ │ ├─ OverlayHistoryPanelView.kt # Floating history panel
│ │ │ │ └── OverlayCapturePreviewView.kt # Screenshot Preview
│ │ │ ├─ ui/ # Compose main interface
│ │ │ │ ├── main/ # MainActivity / MainScreen / MainViewModel
│ │ │ │ ├── history/ # HistoryActivity / HistoryScreen / HistoryViewModel
│ │ │ │ ├── preset/ # PresetActivity / PresetScreen / PresetViewModel
│ │ │ │ ├── settings/ # SettingsActivity / SettingsScreen / SettingsViewModel
│ │ │ │ ├── about/ # AboutActivity / AboutScreen / AboutViewModel
│ │ │ │ ├── theme/ # Theme.kt · Shape.kt · Type.kt
│ │ │ │ └── common/ # CommonComponents.kt · PageTransitions.kt
│ │ │ ├─ data/ # data layer
│ │ │ │ ├── db/ # AppDatabase.kt · HistoryDao.kt · HistoryRepositoryImpl.kt
│ │ │ │ ├── preset/ # PresetDao.kt · PresetRepositoryImpl.kt
│ │ │ │ ├─ image/ # ImageStoreImpl.kt (writes to filesDir/screenshots)
│ │ │ │ ├── crypto/ # CryptoRepository.kt · CryptoRepositoryImpl.kt（Keystore）
│ │ │ │ ├── prefs/ # AppSettings.kt · SettingsRepository.kt（DataStore）
│ │ │ │ ├── model/ # HistoryItem.kt · Preset.kt
│ │ │ │ └── repo/ # HistoryRepository.kt · ImageStore.kt · PresetRepository.kt
│ │ │ └── util/ # Toolset
│ │ │ ├─ ImageUtils.kt # Scaling / Compression / Base64 Encoding
│ │ │ ├─ OcrUtils.kt # ML Kit Chinese Text Recognition
│ │ │ ├─ PermissionUtils.kt # Popup Window / Notification / Battery Whitelist Navigation
│ │ │ ├─ FloatingDragListener.kt # Floating ball dragging and edge absorption
│ │ │ ├─ UpdateChecker.kt # version check
│ │ │ ├─ TimeUtils.kt # Time formatting
│ │ │ └── ToastUtils.kt # Unified prompt
│ │ ├─ res/ # Resources: Layout XML / Icons / Themes / Chinese and English Texts
│ │ └── AndroidManifest.xml
│ └── test/java/com/screenshot/ai/ # Unit tests
│ ├── ai/ProviderRegistryTest.kt · ai/SseParserTest.kt
│ ├── data/PresetRepositoryLogicTest.kt
│ └── util/ImageUtilsTest.kt · TimeUtilsTest.kt · UpdateCheckerTest.kt
├── docs/
│ ├─ byok.md # Detailed Explanation and Cost Control of BYOK Mode
│ ├─ adding-a-provider.md # How to add an AI Provider
│ ├─ api-key-security.md # API Key Security Strategy and Threat Model
│ └── images/ # Demo screenshots and GIFs (see docs/images/README.md)
├── gradle/
│ ├── libs.versions.toml # Version Catalog
│ └── wrapper/ # Gradle 8.9 wrapper (default Tencent Cloud image)
├── .github/
│ ├─ workflows/android.yml # CI: Debug build + Tag release
│ ├── ISSUE\_TEMPLATE/ # bug\_report.yml · feature\_request.yml · config.yml
│ └── PULL\_REQUEST\_TEMPLATE.md
├── CONTRIBUTING.md
├── LICENSE # GNU GPL v3.0
└── README.md
```

\---

## Compilation Guide

### Environmental requirements

|Item|Version Requirement|
|-|-|
|Android Studio|**Ladybug** (2024.2.1) or later|
|JDK|**17** (AGP 8.5.2 is mandatory, JDK 21 is also acceptable but please use 17 as the standard)|
|Android SDK Platform|**API 34**（compileSdk / targetSdk）|
|Android Build Tools|34.x (automatically downloaded by AGP)|
|Gradle|**8.9** (using the built-in wrapper in the repository, no manual installation required)|
|Real device system|Android 11 (API 30) and above|

Android Studio will automatically download the required SDK Platform and Build Tools. For command-line compilation, please ensure that `ANDROID\_HOME`
Or, the `sdk.dir` in `local.properties` points to the SDK directory where API 34 has been installed.

### Compilation steps

```bash
# 1.  Clone repository
git clone https://github.com/MapleYazuu/Screenshot-AI.git screenshot-ai
cd screenshot-ai

# 2.  Compile Debug APK (use gradlew.bat for Windows, and ./gradlew for macOS/Linux)
./gradlew :app:assembleDebug

# 3.  Run unit tests
./gradlew :app:testDebugUnitTest
```

Product pathway:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install to the connected real device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `applicationId` of the Debug package carries a `.debug` suffix (`com.screenshot.ai.debug`), so it can be differentiated from the Release version
The packages are installed on the same device simultaneously, without overlapping.

\---

## First Use / Permission Configuration

Complete the following 7 steps in order to start using:

1. **Install the app**
`adb install -r app-debug.apk`, or directly click on the downloaded APK to install. Upon first launch, you will enter the main interface.
2. **Grant permission for floating window (required)**
Click the "Floating Window Permissions" guide item on the homepage, and navigate to System Settings → Allow "Display on top of other apps"
(`SYSTEM\_ALERT\_WINDOW`). Without this permission, the floating ball will not appear at all.
3. **Enable accessibility service (required)**
Click the "Accessibility Services" guide item → System Settings → Installed Services → **Screenshot AI** → Enable.
This service declares `canTakeScreenshot="true"` and is the **sole source of screenshot capability**. Without it enabled, screenshots cannot be taken.
4. **Grant notification permission (required for Android 13+)**
Android 13 and above will pop up a window requesting `POST\_NOTIFICATIONS`. The foreground service requires a persistent notification,
Rejection can lead to unstable service or even system reclamation.
5. **（Optional）Auto-start at Boot + Battery Optimization Whitelist**
* Enable "Auto-start at Boot" on the settings page, and the floating ball will automatically recover after restarting the phone (`RECEIVE\_BOOT\_COMPLETED`).
* Click "Ignore Battery Optimization" and select "Allow" in the system pop-up to reduce the probability of background processes being killed.
6. **Select Provider and paste the API Key**
Settings page → **AI Provider** → Choose one from 6 options → Select a model from the dropdown list
(The ones marked with 👁 indicate support for vision) → Paste your API Key.
The key will be encrypted immediately using the Android Keystore, and the settings page will only display a mask, without echoing the plaintext.
The application addresses for each manufacturer's Key can be found in [API Key Configuration Instructions (BYOK)](#api-key-configuration-instructions-byok).
7. **Test Connection**
Settings page → "Test Connection". If successful, it will prompt that the model is available; if failed, it will provide the HTTP status code.
Refer to the 401/429 instructions in the FAQ (# FAQ faq) for troubleshooting.

After completing the configuration and returning to the desktop, the floating ball should already be ready at the edge of the screen - **give it a try by clicking**.

\---

## API Key Configuration Instructions (BYOK)

**BYOK = Bring Your Own Key.** This project does not provide any official API quota, nor does it act as a request relay,
You need to apply for a Key on the manufacturer's open platform yourself and bear the calling costs.

|Provider|Application Address|Default Base URL|Built-in Model Supports Vision|
|-|-|-|-|
|**DeepSeek**|[https://platform.deepseek.com/api\_keys](https://platform.deepseek.com/api_keys)|`https://api.deepseek.com/`|`deepseek-flash` ✅ Vision<br/>`deepseek-chat` ❌ Plain Text<br/>`deepseek-reasoner` ❌ Plain Text|
|**OpenAI**|[https://platform.openai.com/api-keys](https://platform.openai.com/api-keys)|`https://api.openai.com/`|`gpt-4o` ✅ / `gpt-4o-mini` ✅ / `gpt-4-turbo` ✅<br/>`o3-mini` ❌ Plain text|
|**Google Gemini**|[https://aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)|`https://generativelanguage.googleapis.com/v1beta/openai/`|`gemini-1.5-flash` ✅ / `gemini-1.5-pro` ✅ / `gemini-2.0-flash` ✅|
|**Anthropic Claude**|[https://console.anthropic.com/settings/keys](https://console.anthropic.com/settings/keys)|`https://api.anthropic.com/`|`claude-3-5-sonnet-20241022` ✅ / `claude-3-5-haiku-20241022` ✅ / `claude-3-opus-20240229` ✅|
|**Tongyi Qianwen Qwen** (DashScope)|[https://bailian.console.aliyun.com/?apiKey=1](https://bailian.console.aliyun.com/?apiKey=1)|`https://dashscope.aliyuncs.com/compatible-mode/v1/`|`qwen-vl-max` ✅ / `qwen-vl-plus` ✅<br/>`qwen-plus` ❌ Plain Text|
|**Doubao** (Volcano Ark)|[https://console.volcengine.com/ark](https://console.volcengine.com/ark)|`https://ark.cn-beijing.volces.com/api/v3/`|`doubao-vision-pro-32k` ✅ / `doubao-vision-lite-32k` ✅<br/>`doubao-pro-32k` ❌ Plain Text|

**Protocol Differences**: Except for Anthropic, which uses `/v1/messages`, the other five all use OpenAI compatible protocols
The '/chat/completions' interface, therefore, incurs low costs for adding similar manufacturers
(See [docs/adding-a-provider.md](docs/adding-a-provider.md)).

**Base URL will be automatically formatted**: The default address in the table above is ready to use out of the box, without the need to manually add paths. `UrlBuilder` will handle it uniformly
Situations such as redundant slashes at the end and missing version segments:

|Content you filled in|Actual requested address|
|-|-|
|`https://api.deepseek.com/`|`https://api.deepseek.com/v1/chat/completions`|
|`https://api.deepseek.com`|`https://api.deepseek.com/v1/chat/completions`|
|`https://api.deepseek.com/v1`|`https://api.deepseek.com/v1/chat/completions`|
|`https://my-gw.example.com/v1/`|`https://my-gw.example.com/v1/chat/completions`|
|`https://my-gw.example.com/v1/chat/completions`|Use as is|

So for self-built gateways, it's sufficient to only fill in the **version layer**, and filling in the complete address will not result in repeated concatenation.

### Explanation on key security

* ✅ The API Key is encrypted using the **Android Keystore master key + AES256-GCM** locally on the device and then stored
EncryptedSharedPreferences, **no plaintext is stored on disk**.
* ✅ The Key **will not** be uploaded to any server of this project (this project does not have any server at all).
* ✅ The Key will only appear in the HTTPS request header sent to **the vendor you have chosen**.
* ⚠️ In other words: \*\*Except for the model manufacturer you configured, no third party can obtain your Key. \*\*
* ⚠️ For devices that have been rooted or devices that have been injected by malicious applications, the protection capability of local encryption will be significantly reduced.
For a comprehensive analysis of the threat model, please refer to [docs/api-key-security.md](docs/api-key-security.md).
* ⚠️ Cloud backup has been **disabled** in the Manifest (`allowBackup="false"`), so the ciphertext will not be leaked through system backups.

For more detailed application steps, credit limit explanations, custom Base URL usage, and cost control techniques, please refer to
[**docs/byok.md**](docs/byok.md).

\---

## Built-in preset commands

Built-in with 5 presets, **the first one "Screenshot Summary" is the default preset**. You can add, edit, delete, and sort presets on the settings page,
You can also temporarily switch it in the "Entry" section of the floating menu.

|#|Name|Function|Typical Scenario|
|-|-|-|-|
|1|**Screenshot Summary** (Default)|Summarize the main content of the current screen and provide key points|Quickly capture key points from long texts, announcements, and chat records|
|2|**Translate into Chinese**|Identify foreign languages on the screen and translate them into Simplified Chinese|English documents, Japanese interfaces, foreign language apps|
|3|**Code Explanation**|Explain the function, logic, and potential issues of the code in the screenshot|View open-source code and read error stack traces|
|4|**Extract Text**|Only extract text without additional interpretation|Copy unselectable text and bill information|
|5|**Problem Solving · Answering Questions**|Analyze the problem step by step and provide the solution approach|Math problems, multiple-choice questions, homework guidance|

When using a non-vision model, preset 2/4 is still available - the input will be provided by ML Kit's local OCR.

\---

## Permission description

|Permission|Purpose|Required|
|-|-|-|
|`SYSTEM\_ALERT\_WINDOW`|Display floating ball, action menu, and result pop-up window (`TYPE\_APPLICATION\_OVERLAY`)|✅ **Required**|
|`BIND\_ACCESSIBILITY\_SERVICE`<br/> (Accessibility Service, `canTakeScreenshot="true"`)|The sole source of screen capture capability|✅ **Required**|
|`FOREGROUND\_SERVICE`|Resides in the foreground service form of a floating ball to avoid being reclaimed by the system at any time|✅ **Required**|
|`FOREGROUND\_SERVICE\_SPECIAL\_USE`|Starting from Android 14, foreground services must declare a specific type|✅ **Required**|
|`POST\_NOTIFICATIONS`|Expose foreground service persistent notifications|✅ **Required** (Android 13+ runtime request)|
|`INTERNET`|Access the AI vendor interface you configured via HTTPS|✅ **Required**|
|`RECEIVE\_BOOT\_COMPLETED`|Automatically restore the floating ball after booting|⭕ Optional (switch on settings page)|
|`REQUEST\_IGNORE\_BATTERY\_OPTIMIZATIONS`|Directs to join the battery optimization whitelist, reducing the probability of being killed in the background|⭕ Optional|
|`ACCESS\_NETWORK\_STATE`|Check network availability before making requests and provide a more user-friendly error message|⭕ Optional|

**Do not apply for** permissions (you can verify `AndroidManifest.xml` yourself): read and write external storage/albums, read contacts, location
Reading SMS, `QUERY\_ALL\_PACKAGES`, `READ\_LOGS`, etc. During the screenshot process, only the application's private directory is written.

\---

## Frequently Asked Questions (FAQ)

<details open>
<summary><b>Q1. What should I do if the floating ball disappears / doesn't appear at all?</b></summary>

Troubleshoot in the following order:

1. **Floating window permission**: Settings → Apps → Special Permissions → Display on top of other apps → Allow Screenshot AI.
This is the most common reason. Without the permission, the method `WindowManager.addView` will directly throw an exception.
2. **Is the service still alive**: Enter the main interface of the application and check if the floating ball switch is in the on state; if it is killed by the system, it needs to be restarted.
3. **Did it slide off the screen?**: The floating ball supports dragging and edge-absorption. There is a very small chance it may stop below the status bar/navigation bar, but restarting the service will return it to its default position.
4. **Manufacturer backend restrictions**: See Q6. Xiaomi, Huawei, OPPO, and vivo require additional settings for auto-start and backend whitelists.
5. **Notification permission denied**: Android 13+ rejecting notifications can destabilize foreground services. Please grant the `POST\_NOTIFICATIONS` permission.

</details>

<details>
<summary><b>Q2. What should I do if I fail to capture a screenshot by clicking the floating ball and receive a prompt saying "screenshot failed"? </b></summary>

Screenshot relies on barrier-free service. Please confirm in order:

1. **Accessibility service enabled**: Settings → Accessibility → Installed Services → Screenshot AI → Enable.
System upgrades, forced app closures, or certain cleanup software may shut it down.
2. **Whether the service has been reclaimed by the system**: Simply re-enter the application and enable the floating ball once.
3. **Is it on a protected interface**: Some system interfaces (such as the payment password page, or the `FLAG\_SECURE`-enabled banking/video app interface)
The system will **actively prohibit screen capture**, and at this time, no application can obtain the screen image. This is a system behavior, not a bug.
4. **Whether in split screen/picture-in-picture mode**: In some ROMs, `takeScreenshot` may return an error code when in split screen mode.

</details>

<details>
<summary><b>Q3. What do the error codes 401 / 403 / 429 mean?</b></summary>

|Status Code|Meaning|Handling Method|
|-|-|-|
|**401** Unauthorized|API Key is invalid, misspelled, deleted, or expired|Go back to the settings page to paste the Key again; be careful not to copy the `Bearer` prefix or quotation marks along with it|
|**403** Forbidden|The key is valid but lacks permission for the model; or the model has not been activated/verified|Activate the corresponding model on the manufacturer's console, or switch to another model|
|**404** Not Found|Base URL or model name incorrect|Check if the Base URL is consistent with [BYOK Table](#api-key-configuration-instructions-byok); Volcano Ark requires the correct access point/model name|
|**429** Too Many Requests|Rate limiting triggered or **Insufficient balance**|Reduce the request frequency; check the quota and billing on the vendor console; paid accounts can usually be restored immediately|
|**5xx**|Manufacturer server exception|Retry later; temporarily switch to another provider|

All errors will be accompanied by the original message returned by the manufacturer, and that text should be used as the reference during troubleshooting.

</details>

<details>
<summary><b>Q4. What happens when using a model that does not support vision (such as <code>deepseek-chat</code>)?</b></summary>

It will not report an error or send the image. The application will **automatically downgrade**:

1. `AiModel.supportsVision == false` is detected;
2. Utilize the local **ML Kit Chinese Text Recognition** to extract text from the screenshot;
3. Send the "preset instruction + extracted text" as a plain text message to the model.

Therefore, a plain text model can also be used, but its upper limit of capability depends on the quality of OCR: **charts, formulas, handwriting, and complex layouts will be significantly deteriorated**.
If you primarily use it for reading images, viewing charts, and understanding interface layouts, please choose the visual model marked with ✅ in the table.

</details>

<details>
<summary><b>Q5. Will my screenshot be uploaded? Will it be added to the album?</b></summary>

Let me clarify it in two parts:

* **Local storage**: Screenshots are only written to the application's private directory `filesDir/screenshots`, and **never to the photo album/external storage**,
Other applications cannot read it; `allowBackup="false"` has been set in the Manifest, and cloud backup has also been disabled.
* **Network transmission**: **The screenshot will indeed be sent to the AI vendor you have configured** (this is how this App works).
When using a visual model, send Base64 encoded images; when not using a visual model, send text extracted by local OCR.
In addition, **no other servers receive your data** - this project does not have any relay servers or statistical SDKs.

> ⚠️ Please do not use it to capture and send screenshots containing sensitive information such as ID number, bank card, password, and trade secrets.
Once the data is sent to a third-party model manufacturer, it is subject to the privacy policy of that manufacturer, and the project has no control over it.

</details>

<details>
<summary><b>Q6. What should I do if the background is killed or the floating ball goes offline on Xiaomi / Huawei / OPPO / vivo?</b></summary>

The backend management of ROM for Chinese products is relatively aggressive, requiring manual whitelisting:

* **Xiaomi / Redmi (MIUI / HyperOS)**: Settings → App Settings → App Management → Screenshot AI →
**Self-start** is turned on, **Power-saving strategy** is changed to "Unlimited", and **Lock background tasks** (by pulling down and locking in recent tasks).
* **Huawei / Honor (EMUI / HarmonyOS)**: Settings → Apps → App startup management → Disable "Auto manage",
Manually check **Auto-start / Associated Startup / Background Activity**; then go to Battery → More Battery Settings → turn off the restriction of "Always keep network connection when sleeping".
* **OPPO / OnePlus (ColorOS)**: Settings → Battery → App Power Management → Screenshot AI → Allow **Background Running** +
**Auto-start**; **Lock** the app in recent tasks.
* **vivo (OriginOS)**: Settings → Battery → High Battery Consumption in Background → Allow Screenshot AI;
IManager → Self start Management → Open.
* **General method**: Click "Ignore battery optimization" and allow it in the settings page of the app; add the app in developer options
Outside the whitelist of "non-preserved activities"; do not use cleanup apps to clear the background with one click.

Even with the above settings, apps that have not been used for a long time may still be reclaimed - simply reopening the app will restore the floating ball.

</details>

<details>
<summary><b>Q7. Does it support custom API mediation/proxy/self-built gateway?</b></summary>

Support. The settings page allows you to override the **Base URL** and point it to any OpenAI-compatible relay service, reverse proxy, or self-built gateway.
As long as you fill in the version layer (e.g. `https://your-gateway.example.com/v1/`), the application will automatically complete it
`/chat/completions`; pasting the complete address will not be concatenated repeatedly.
Note: The intermediary can see both your Key and the screenshot content simultaneously. Please assess the trustworthiness yourself.
For details, please refer to [docs/byok.md](docs/byok.md).

</details>

<details>
<summary><b>Q8. Why is it only compatible with Android 11+?</b></summary>

The project utilizes the `takeScreenshot()` API (introduced in API 30) for accessibility services, as well as `FOREGROUND\_SERVICE\_SPECIAL\_USE`
(Mandatory for Android 14) and other capabilities, `minSdk` is therefore set to 30. Lower versions do not have a stable root-free screenshot solution.

</details>

\---

## Participate in contribution

Welcome to submit Issues and PRs! Before you start, please read [**CONTRIBUTING.md**](CONTRIBUTING.md), which includes:

* Setting up the development environment and commonly used Gradle commands
* Branch model and commit message specification (Conventional Commits, with Chinese examples)
* Code style (4-space indentation, 120-column width, Chinese comments, `.editorconfig` + `./gradlew ktlintCheck`)
Step-by-step instructions on "How to add an AI Provider" (see also [docs/adding-a-provider.md](docs/adding-a-provider.md))
* Testing requirements, PR process, and Issue specifications

Other relevant documents:

|Document|Content|
|-|-|
|[CONTRIBUTING.md](CONTRIBUTING.md)|Contribution Guidelines|
|[SECURITY.md](SECURITY.md)|Security vulnerability reporting process and design trade-offs for not requiring reporting|
|[docs/byok.md](docs/byok.md)|Detailed explanation of BYOK mode, Key application, error code comparison, cost control|
|[docs/adding-a-provider.md](docs/adding-a-provider.md)|Multiple Provider Extension: Complete Code Example and Considerations|
|[docs/api-key-security.md](docs/api-key-security.md)|API Key Security Strategy and Threat Model|
|[docs/images/README.md](docs/images/README.md)|Submission convention for demo screenshots/GIFs|

\---

## Open source license

This project is released under the **GNU General Public License v3.0**. For the complete text of the license, please refer to [LICENSE](LICENSE).

In simple terms:

* ✅ You are free to **use, modify, and distribute** this project, including for commercial purposes.
* ✅ When distributing the modified version, it **must also be open sourced under GPLv3** and the complete source code must be provided.
* ✅ The copyright notice and agreement statement must be retained, and **indicate what modifications you have made**.
* ❌ You cannot distribute this project (or its derivative works) after making it **closed source**.
* ❌ No guarantees are provided (see Articles 15 and 16 of the agreement for details).

This project is an open-source work by an individual, and is related to DeepSeek, OpenAI, Google, Anthropic, Alibaba Cloud, Volcano Engine, etc
The manufacturers have **no affiliation or sponsorship relationship**; all trademarks belong to their respective owners.

\---

## Acknowledgements

* Thanks to **Jetpack Compose**, **OkHttp**, **Room**, **DataStore**, **Coil**, **ML Kit**, and others
Open source projects and the excellent infrastructure provided by Google and Square teams.
* Thank you to **DeepSeek / OpenAI / Google Gemini / Anthropic / Tongyi Qianwen / Doubao** for providing model services and
OpenAI's compatible interface enables the BYOK (Bring Your Own Key) model to support multiple vendors with minimal adaptation cost.
* Thank you to all friends who have submitted issues, provided feedback, and contributed code and documentation.

\---

<div align="center">

\*\*This project is developed by [MapleYazuu](https://github.com/MapleYazuu), is open-sourced under GPLv3, and is jointly maintained by the community. \*\*

If this project is helpful to you, feel free to give it a ⭐ Star.

</div>

