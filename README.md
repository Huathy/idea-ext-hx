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

### 3. 隐藏 JetBrains AI 入口图标

移除状态栏的 **AI Assistant** 入口图标，除非真正安装并启用了 AI 插件。

| 项 | 值 |
|---|---|
| 状态栏 widget id | `AIAssistant`（`LangExtensions.xml` 中作为 order 锚点） |
| 主工具栏 group | `MainToolbarRight`，注销实现 `AiInternalUiComponentMarker.AIAction` 的 action |
| 组件兜底 | 递归移除被 `AiInternalUiComponentMarker.isAiComponent` 标记的组件 |

**原理**：
1. 检测是否安装并启用了 AI 插件：遍历 `PluginManagerCore.getLoadedPlugins()`，匹配 name/id 含 `AI Assistant`（`com.intellij.aiAssistant` / `com.intellij.ai`）且 `isEnabled`。命中则保留入口、仅低频复查。
2. 未命中则 `StatusBar.removeWidget("AIAssistant")` 移除状态栏入口，并注销主工具栏右侧的 AI action。
3. `Alarm` 周期扫描（3s 快扫 / 30s 兜底）所有 `IdeFrame`，防止 widget 被平台重建复活；组件树递归兜底移除被标记的 AI 组件。

> 注：IDEA 2026.1 起 JetBrains AI 为平台内置（`AiInternalUiComponentMarker` 为 stub，`isAiComponent` 恒 false），无独立插件 descriptor。此时检测恒不命中 → 持续隐藏入口。若安装了 Marketplace 独立 AI 插件（name/id 命中），入口自动保留。

### 4. hx ext settings（全局开关）

Settings → Other Settings → **HxExt Settings**，三个开关默认全部勾选：

| 开关 | 作用 | 生效方式 |
|---|---|---|
| 隐藏免费使用按钮 | 隐藏主工具栏 "Start Free Trial"（见 1） | 重启 IDE |
| 隐藏 JetBrains AI 按钮 | 隐藏 JetBrains AI 入口（见 3） | 重启 IDE |
| 开启 AI commits | 勾选时提交面板显示 "AI 生成提交信息" 按钮（见 2），取消则隐藏 | 无需重启 |

> "开启 AI commits" 原位于 Settings → Tools → AI Commit Message，现统一由 HxExt Settings 管理；AI Commit Message 页保留供应商、模型、提示词等配置。

### 5. Remove from VCS（从版本控制移除）

补齐 IDEA 缺失的反向操作：内置只有 **"Add to VCS"**，没有 **"Remove from VCS"**。本插件在文件列表（Git 子菜单）与提交面板右键菜单中，紧跟 "Add to VCS" 补上该动作。

| 项 | 值 |
|---|---|
| Action ID | `IdeaExtHx.RemoveFromVcs` |
| 注册位置 | `Vcs.Operations.Popup.VcsAware`（跟随 `ChangesView.AddUnversioned`，即项目视图/编辑器右键的 Git 子菜单）、`ChangesViewPopupMenu`（跟随 `ChangesView.AddUnversioned`，即提交面板与变更列表右键） |
| 底层命令 | `git rm --cached -f [-r]`（取消跟踪，**磁盘文件保留**） |
| 可选依赖 | `Git4Idea`（Git 插件缺失时该动作不加载，其他功能不受影响） |

**行为**：
1. 入口与内置 "Add to VCS" 并排：文件列表右键 → **Git → Add to VCS** 下方；提交面板/变更列表右键 → **Add to VCS** 下方。
2. 多选文件/文件夹均可；提交面板中勾选的变更也会映射为对应文件（已删除的条目忽略）。目录会向下最多 3 层探测是否含已跟踪文件，避免对未跟踪目录误显示。
3. 按所属 Git 仓库分组（取最内层仓库），逐个执行 `git rm --cached -f`，执行前弹窗确认并列出条目。
4. `-f` 用于绕过 git 的 "has staged content different from both the file and the HEAD" 保护（新
   增后又改、暂存后又改等场景）；`--cached` 只动索引、不动工作区，故强制安全，但**已暂存未提交
   的内容会随索引条目一起丢弃**，确认框中已明确提示。
5. 执行后标记 VCS 状态脏（`VcsDirtyScopeManager`），IDE 立即把文件重新归类为「未纳入版本控制」。

## 构建与安装

依赖：JDK 17+（开发机 JDK 21 可用）。

```powershell
cd D:\works\mystudy\my_open\hx-ext-idea
.\build.ps1                                  # 编译 -> 打包 jar -> 安装到 $IdeaHomes 中存在的 IDE
.\build.ps1 -NoInstall                       # 只构建 jar
.\build.ps1 -IdeaHomes "D:\...\2025.1"       # 指定其他 IDE 安装目录（可传多个）
```

安装位置（默认 `$IdeaHomes = D:\Program Files\JetBrains\IntelliJ IDEA 2026.1`）：

```
C:\Users\huathy\AppData\Roaming\JetBrains\IntelliJIdea2026.1\plugins\idea-ext-hx\lib\idea-ext-hx-1.2.0.jar
```

**安装后重启 IDEA 生效**。首次加载未签名插件若弹"未知来源"提示，选信任即可。

## 卸载

删除 `plugins\idea-ext-hx` 文件夹，重启 IDE 即恢复，无残留。

## 目录结构

```
idea-ext-hx/
├── build.ps1                                  # 一键构建+安装
├── build/idea-ext-hx-1.2.0.jar                # 产物
└── src/main/
    ├── java/com/huathy/ideaext/
    │   ├── TrialPromotionHider.java            # 入口：AppLifecycleListener
    │   ├── TrialButtonHider.java               # 隐藏试用按钮
    │   ├── AiAssistantEntryHider.java          # 隐藏 JetBrains AI 入口
    │   ├── HxExtSettings.java                  # 全局开关状态（持久化）
    │   ├── HxExtConfigurable.java              # hx ext settings 设置页
    │   ├── ai/
    │   │   ├── AiCommitSettings.java           # 配置状态（持久化）
    │   │   ├── AiCommitConfigurable.java       # Settings UI
    │   │   ├── AiCommitClient.java             # HTTP 调用+响应解析
    │   │   └── GenerateAiCommitMessageAction.java # 提交信息生成 Action
    │   └── vcs/
    │       └── RemoveFromVcsAction.java        # Remove from VCS（git rm --cached -f）
    └── resources/META-INF/
        ├── plugin.xml                          # 主描述符
        └── idea-ext-hx-git.xml                 # 可选依赖 Git4Idea 的动作注册
```

## 兼容性

IntelliJ IDEA 2025.1 ~ 2026.1（`since-build="251" until-build="261.*"`）。

## 已知边界

- AI 提交信息需 git 在 PATH 中可用；暂存区为空时回退工作区 diff。
- 试用按钮隐藏依赖 Action ID `TrialStateWidget`，若 JetBrains 改名需更新常量后重建。
- Remove from VCS 仅支持 Git（`git rm --cached -f`），SVN/Perforce 等未实现；目录判定为浅层探测（≤3 层），全未跟踪的深层目录不会显示该动作。
- Remove from VCS 用 `-f` 绕过 git 的 staged content 保护，**已暂存未提交的内容会随索引条目一起丢弃**（磁盘文件不受影响），确认框中已提示。
