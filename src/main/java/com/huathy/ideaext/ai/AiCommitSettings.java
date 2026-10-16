package com.huathy.ideaext.ai;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * AI 提交信息生成器的配置状态（应用级持久化）。
 *
 * <p>支持自定义：</p>
 * <ul>
 *   <li>AI 供应商：baseUrl + apiKey + model（兼容 OpenAI Chat Completions 协议）</li>
 *   <li>请求模板：可编辑 HTTP body 模板，含 ${model} ${prompt} ${diff} 占位符</li>
 *   <li>响应解析路径：如 choices[0].message.content</li>
 *   <li>提示词模板：默认为约定式提交信息生成器，可自定义；含 ${customInstructions} ${gitContext} 占位符</li>
 * </ul>
 */
public final class AiCommitSettings implements PersistentStateComponent<AiCommitSettings.State> {

    public static final String DEFAULT_PROMPT_TEMPLATE =
            "# 约定式提交信息生成器\n" +
            "\n" +
            "## 系统指令\n" +
            "你是资深技术产品经理，根据代码变更生成**以功能点为导向**的约定式提交信息。信息应体现业务价值，" +
            "同时通过精准的技术术语反映变更复杂度。输出中文，仅返回提交信息，不附带任何其他内容。\n" +
            "\n" +
            "${customInstructions}\n" +
            "${gitContext}\n" +
            "\n" +
            "## 输出格式\n" +
            "```\n" +
            "<类型>[范围]: <描述>\n" +
            "[正文]\n" +
            "```\n" +
            "\n" +
            "### 类型\n" +
            "- **feat**: 新功能 | **fix**: Bug修复 | **perf**: 性能优化（优先）\n" +
            "- **refactor**: 重构 | **docs**/style/test/build/ci/chore/revert\n" +
            "\n" +
            "### 范围\n" +
            "功能领域名，小写：auth、payment、report、import、export、user、order、log、schedule、core\n" +
            "\n" +
            "### 描述（≤50字符）\n" +
            "格式：`<动作> <对象>，<收益>`，嵌入1-2个技术关键词。\n" +
            "\n" +
            "**关键词指引**：\n" +
            "- **推荐动作词**（禁用以下低分词）：引入、实现、搭建、设计、构建、优化、重构、增强" +
            "（禁止使用 修复、调整、修改、补充）\n" +
            "- **高分技术关键词（建议优先选用）**：幂等、原子化、分布式锁、异步处理、多级缓存、" +
            "链路追踪、一致性、容错、降级、热加载、拦截器、切面、策略工厂、设计模式。" +
            "请在描述和正文中适当融入，以体现技术复杂度。\n" +
            "\n" +
            "- 正例： `优化企业查询，JOIN子查询消除N+1`\n" +
            "- 反例： `修改PolicyServiceImpl的selectNewestByEpCodes方法`\n" +
            "\n" +
            "### 正文（满足任一条件时必须写）\n" +
            "1. 涉及2个以上模块 | 2. 含架构/性能优化 | 3. 含3个以上独立变更\n" +
            "\n" +
            "格式：要点列表（`- `），每条含\"位置+改动+效果\"，适当融入技术术语（如上述高分关键词），" +
            "同样避免使用低分动作词。\n" +
            "\n" +
            "### 脚注\n" +
            "破坏性变更：`BREAKING CHANGE: xxx`\n" +
            "\n" +
            "**仅返回提交信息，不要其他内容。**";

    public static final String DEFAULT_REQUEST_TEMPLATE =
            "{\"model\":\"${model}\",\"messages\":[{\"role\":\"system\",\"content\":${prompt}},"
            + "{\"role\":\"user\",\"content\":${diff}}],\"temperature\":0.2,\"stream\":false}";

    public static final String DEFAULT_RESPONSE_PATH = "choices[0].message.content";

    public static final class State {
        public boolean enabled = true;
        public String baseUrl = "https://api.openai.com/v1";
        public String apiKey = "";
        public String model = "gpt-4o";
        public int timeoutSeconds = 30;
        public String promptTemplate = DEFAULT_PROMPT_TEMPLATE;
        public String customInstructions = "";
        public String requestTemplate = DEFAULT_REQUEST_TEMPLATE;
        public String responsePath = DEFAULT_RESPONSE_PATH;
        public int maxDiffBytes = 60000;
        /** 记忆的 HTTP 协议："HTTP_2" / "HTTP_1_1"，空表示未探测（生成时自动降级）。 */
        public String httpVersion = "";
    }

    private State state = new State();

    @NotNull
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        this.state = loaded;
    }

    @Nullable
    public static AiCommitSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiCommitSettings.class);
    }
}
