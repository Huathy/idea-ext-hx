# Idea Ext HX

IntelliJ IDEA 实用增强工具集。零 Gradle/Maven/网络，直接以本机 IDEA `lib/*.jar` 编译。

## 功能清单

### 1. 隐藏 "Start Free Trial" 试用按钮

移除主工具栏右上角的 **"Start Free Trial"** 试用推广按钮（IDE 内置 `TrialStateWidget`）。

| 项 | 值 |
|---|---|
| 所属模块 | `intellij.platform.trialPromotion.*`（IDEA Ultimate 内置） |
| Action ID | `TrialStateWidget` |
| 注册位置 | `MainToolbarRight` 工具栏组 |
| 文案 key | `trial.state.widget.trial.available=Start Free Trial` |

**原理（三层，与界面语言无关）**：
1. 按 Action ID 拿实例，组件树匹配 `ActionButton.getAction() == target`，不看按钮文字，中文界面同样生效，不误伤其他按钮。
2. 从 `MainToolbarRight` action group 注销，IDE 重建工具栏时按钮不会复活。
3. `Alarm` 周期扫描所有可见主窗口（欢迎屏 + 项目窗口）物理移除按钮。快扫 3s / 安静后 30s 兜底。

### 2. AI 生成约定式提交信息

在提交对话框的提交信息输入框工具栏新增 **"AI 生成提交信息"** 按钮（注册于 `Vcs.MessageActionGroup`）。

**工作流**：自动读取暂存区 `git diff --cached`（无暂存则用 `git diff`）→ 调用 AI → 生成中文约定式提交信息 → 填入输入框。

**可自定义项**（Settings → Tools → AI Commit Message）：

| 配置 | 说明 | 默认值 |
|---|---|---|
| Base URL | AI 供应商地址 | `https://api.openai.com/v1` |
| API Key | 鉴权密钥（Bearer） | 空 |
| 模型 | 模型名 | `gpt-4o` |
| 超时 | 请求超时秒数 | 30 |
| Diff 最大字节数 | 超长截断 | 60000 |
| 响应解析路径 | 从响应 JSON 提取文本 | `choices[0].message.content` |
| 请求体模板 | HTTP body，占位符 `${model}` `${prompt}` `${diff}`（自动 JSON 转义） | OpenAI Chat Completions 格式 |
| 提示词模板 | 系统提示，占位符 `${customInstructions}` `${gitContext}` | 约定式提交信息生成器 |
| 自定义补充指令 | 注入 `${customInstructions}` 位置 | 空 |

兼容 OpenAI Chat Completions 协议；改 Base URL/模型/请求模板/响应路径即可适配其他兼容服务。

## 构建与安装

依赖：JDK 17+（开发机 JDK 21 可用）。

```powershell
cd D:\works\temp\idea-ext-hx
.\build.ps1            # 编译 -> 打包 jar -> 安装到 IDEA 2026.1 与 2025.1
.\build.ps1 -NoInstall # 只构建 jar
```

安装位置：

```
C:\Users\huathy\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\idea-ext-hx\lib\idea-ext-hx-1.1.0.jar
C:\Users\huathy\AppData\Roaming\JetBrains\IntelliJIdea2025.1\plugins\idea-ext-hx\lib\idea-ext-hx-1.1.0.jar
```

**安装后重启 IDEA 生效**。首次加载未签名插件若弹"未知来源"提示，选信任即可。

## 卸载

删除 `plugins\idea-ext-hx` 文件夹，重启 IDE 即恢复，无残留。

## 目录结构

```
idea-ext-hx/
├── build.ps1                                  # 一键构建+安装
├── build/idea-ext-hx-1.1.0.jar                # 产物
└── src/main/
    ├── java/com/huathy/ideaext/
    │   ├── TrialPromotionHider.java            # 入口：AppLifecycleListener
    │   ├── TrialButtonHider.java               # 隐藏试用按钮
    │   └── ai/
    │       ├── AiCommitSettings.java           # 配置状态（持久化）
    │       ├── AiCommitConfigurable.java       # Settings UI
    │       ├── AiCommitClient.java             # HTTP 调用+响应解析
    │       └── GenerateAiCommitMessageAction.java # 提交信息生成 Action
    └── resources/META-INF/plugin.xml
```

## 兼容性

IntelliJ IDEA 2025.1 ~ 2026.1（`since-build="251" until-build="261.*"`）。

## 已知边界

- AI 提交信息需 git 在 PATH 中可用；暂存区为空时回退工作区 diff。
- 试用按钮隐藏依赖 Action ID `TrialStateWidget`，若 JetBrains 改名需更新常量后重建。
