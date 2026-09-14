# Hermes Agent for Android
适配与调试工作主要由Deepseek V4.1 flash完成，GPT5.6luna辅助编辑代码
把 [Nous Research Hermes Agent](https://github.com/NousResearch/hermes-agent) 的核心 Agent 架构移植到
Android 手机，产出可直接安装的 **arm64-v8a (Arm V8A)** APK。

界面全中文，支持任意 OpenAI 兼容端点（OpenAI、DeepSeek、OpenRouter、各类中转网关）。

> **本项目是派生作品。** 上游 Hermes Agent 采用 MIT 协议（Copyright (c) 2025 Nous Research）。
> 本项目在 **Apache License 2.0** 下发布，并同时保留上游 MIT 许可。详见 [LICENSE](LICENSE)
> 与 [NOTICE](NOTICE)。

## 为什么是 Kotlin 重写，而不是移植 Python

上游 Hermes 是一个 162 MB 的 Python 单体（`agent/` 282 文件、`tools/` 308、`hermes_cli/` 492、
`gateway/` 159，另有 205 个 SKILL.md）。核心依赖 `cryptography`、`pydantic-core` 等 Rust 扩展
在 Android/Termux 上没有预编译 wheel，必须现场源码编译；再加上体积、内存占用与 Android 权限模型
都不适配。因此这里**保留架构、重写实现**。

## 保留 vs 删除

保留（Hermes 的骨架）：

| 架构 | 移植位置 |
|---|---|
| Agent 主循环（工具调用 → 结果回注 → 再循环） | `core/Agent.kt` |
| ToolRegistry（name / toolset / JSON-Schema / handler / checkFn） | `core/ToolRegistry.kt` |
| Skill 系统（SKILL.md frontmatter + 索引注入 + 按需加载全文） | `core/SkillRegistry.kt` |
| 模型配置（任意 OpenAI 兼容端点） | `core/ModelConfig.kt` |
| 身份与行为契约（源自 `SOUL.md`） | `core/Soul.kt` |
| 跨会话记忆 | `core/MemoryStore.kt` |
| 流式输出（SSE + 分片工具调用聚合） | `core/LlmClient.kt` |

删除（依赖服务器 / 桌面 / 手机拿不到的权限）：

消息平台（Telegram、Discord、Slack、Matrix、WeCom…）、语音与唤醒词、cron 调度、
浏览器 CDP、七种终端后端、MCP、插件市场、TUI、desktop 模块、所有 desktop-only 工具。

## 可用工具

| 工具 | 说明 |
|---|---|
| `web_search` | 联网搜索，多引擎自动降级（DuckDuckGo → Bing CN → Bing → 360 → 百度） |
| `web_fetch` | 抓取 URL 并转纯文本 |
| `read_file` | 读文本/代码/配置文件，支持路径、`content://`、http(s) |
| `read_image` | 读图并交给视觉模型（自动缩放 + JPEG 压缩 + 体积预算） |
| `list_files` | 列目录 |
| `skills_list` / `skill_view` | 技能索引与全文加载 |
| `memory` | 长期记忆（去重、上限、渲染截断） |
| `todo` | 多步任务清单 |

内置 8 个技能：arxiv、llm-wiki、maps、obsidian、humanizer、test-driven-development、
systematic-debugging、blocked-page-recovery。

## 构建

环境：Android SDK（platforms;android-35、build-tools;35.0.0）、JDK 17、
Gradle 8.9 wrapper、AGP 8.5.0、Kotlin 1.9.24、compileSdk 35 / minSdk 26。

先创建 `local.properties` 指向你的 SDK：

```properties
sdk.dir=/path/to/Android/Sdk
```

然后构建：

```bash
./gradlew assembleRelease
```

Windows PowerShell：

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
.\gradlew.bat assembleRelease --no-daemon
```

产物：`app/build/outputs/apk/release/app-arm64-v8a-release.apk`（约 5 MB）

> release 复用 debug 签名，仅便于自测安装。**正式分发请替换为你自己的签名密钥**
> （见 `app/build.gradle.kts` 的 `signingConfigs`）。

## 使用

首次启动进入右上角「设置」：

1. **接口地址 Base URL** —— 可填域名（自动补 `/v1`）或带路径的完整地址（原样使用）。
   例如 `https://api.openai.com/v1`、`https://cn.clawnode.cn`。
2. **自定义请求地址**（可选）—— 若中转站路径特殊，填完整 chat/completions 地址，将逐字使用。
3. **API Key** / **模型名称** —— 可点「拉取模型列表」从端点获取后选择。
4. 点「保存」，返回即可对话。

设置页会实时显示「实际请求」将使用哪些地址（按顺序尝试，仅在 404/405 时自动降级）。

对话界面底部可附加图片或文件（交给 `read_image` / `read_file`），长按消息复制内容，
右上角菜单可开新会话或进入设置。

## 运行环境要求

- Android 8.0 (API 26) 及以上
- arm64-v8a 设备
- 需要访问所配置的模型端点

## 注意事项（踩过的坑）

- **AGP 8.5.0 要求 Gradle ≥ 8.7**，wrapper 必须指向 8.9；用 8.5 会在配置阶段就失败。
- **`ndk.abiFilters` 与 `splits.abi` 不能同时为同一 ABI 设置**，会报
  "Conflicting configuration"。锁定 ABI 用 `splits.abi` 即可，产物名会自动带 `-arm64-v8a`。
- **主题必须与 Toolbar 归属匹配**：自装 Toolbar 的界面用 `NoActionBar` 主题，否则
  AppCompat 在 `onCreate` 抛 `IllegalStateException` 直接崩溃。
- **`targetSdk 35` 强制 edge-to-edge**：status bar 不再自动让位，需显式消费 window insets。
  且 inset 不能加在传给 `setSupportActionBar()` 的 Toolbar 上（AppCompat 会重置其 padding）。
- Kotlin 原始字符串 `"""..."""` 中若出现 `\"` 会提前终止字面量，正则请改用普通字符串 + 转义。
- XML 资源必须无 BOM，否则 AAPT 解析报 `mismatched input '?'`。
- 纯 Kotlin/Java 工程不会有 `lib/` 目录，ABI 归属靠 `splits` 声明。
- **搜索源需多引擎降级**：`lite.duckduckgo.com` 在部分网络下不可达（连接超时），
  必须准备 Bing 等国内可达的备选源。

## 许可证

本项目采用 **Apache License 2.0**。详见 [LICENSE](LICENSE)。

其中派生自上游 Hermes Agent 的部分（Agent 循环设计、工具注册表契约、SKILL.md 技能格式、
`assets/skills/` 下的技能文档、`Soul.kt` 的身份文本）同时受上游 **MIT 协议**
（Copyright (c) 2025 Nous Research）约束，完整声明见 [NOTICE](NOTICE)。
