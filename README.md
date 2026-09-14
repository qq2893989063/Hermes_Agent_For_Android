# Hermes Agent for Android

把 [Nous Research Hermes Agent](https://github.com/NousResearch/hermes-agent) 的核心 Agent 架构移植到
Android 手机，产出可直接安装的 **arm64-v8a (Arm V8A)** APK。

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
| `web_search` | 联网搜索（DuckDuckGo 免密钥端点） |
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

环境：Android SDK `D:\Android\Sdk`（platforms;android-35、build-tools;35.0.0）、
JDK 17、Gradle 8.9 wrapper、AGP 8.5.0、Kotlin 1.9.24、compileSdk 35 / minSdk 26。

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
.\gradlew.bat assembleRelease --no-daemon
```

产物：`app/build/outputs/apk/release/app-arm64-v8a-release.apk`（约 5.0 MB）

release 复用 debug 签名，可直接 `adb install`。

## 注意事项（踩过的坑）

- **AGP 8.5.0 要求 Gradle ≥ 8.7**，wrapper 必须指向 8.9；用 8.5 会在配置阶段就失败。
- **`ndk.abiFilters` 与 `splits.abi` 不能同时为同一 ABI 设置**，会报
  "Conflicting configuration"。锁定 ABI 用 `splits.abi` 即可，产物名会自动带 `-arm64-v8a`。
- Kotlin 原始字符串 `"""..."""` 中若出现 `\"` 会提前终止字面量，正则请改用普通字符串 + 转义。
- XML 资源必须无 BOM，否则 AAPT 解析报 `mismatched input '?'`。
- 纯 Kotlin/Java 工程不会有 `lib/` 目录，ABI 归属靠 `splits` 声明。

## 使用

首次启动进入右上角「设置」，填写 Base URL / API Key / 模型名，可点「拉取模型列表」选择。
之后即可对话；底部可附加图片或文件，长按消息复制内容。
