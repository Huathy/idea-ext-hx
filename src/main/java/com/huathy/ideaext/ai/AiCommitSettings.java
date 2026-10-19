package com.huathy.ideaext.ai;

import com.intellij.credentialStore.EncryptionSupport;
import com.intellij.credentialStore.EncryptionSupportKt;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AI 提交信息生成器的配置状态（应用级持久化）。
 *
 * <p>应用级 service（{@code <applicationService>}），全 IDE 单例，所有项目共享同一份配置，
 * 配置写入 {@code idea-ext-hx-ai-commit.xml}，重启/换项目都不丢。</p>
 *
 * <p>apiKey 加密后落盘：写时用 {@link EncryptionSupport}（Windows 走 Crypt32/DPAPI，
 * 其他平台走内置 AES）加密字节，base64 存进 {@link State#apiKeyEncrypted}；
 * 读时解密还原。明文从不落盘。</p>
 */
@State(
        name = "com.huathy.ideaext.ai.AiCommitSettings",
        storages = {@Storage("idea-ext-hx-ai-commit.xml")}
)
public final class AiCommitSettings implements PersistentStateComponent<AiCommitSettings.State> {

    /** 加密器：Windows = DPAPI（机器绑定，真加密）；其他平台 = 内置 AES（混淆级）。 */
    private static final EncryptionSupport ENCRYPTION =
            EncryptionSupportKt.createBuiltInOrCrypt32EncryptionSupport(true);

    /** 明文 apiKey 缓存：首次 getApiKey 解密后驻留内存，避免每次请求都解密。 */
    private String cachedApiKey = null;

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
            "{\"model\":${model},\"messages\":[{\"role\":\"system\",\"content\":${prompt}},"
            + "{\"role\":\"user\",\"content\":${diff}}],\"temperature\":0.2,\"stream\":false}";

    public static final String DEFAULT_RESPONSE_PATH = "choices[0].message.content";

    public static final class State {
        /** 开启 AI commits：由 hx ext settings 页面管理；关闭时提交面板不展示 AI 生成按钮。 */
        public boolean enabled = true;
        public String baseUrl = "https://api.openai.com/v1";
        /**
         * apiKey 密文：base64(Encrypt(plaintext))。明文从不落盘。
         * 旧版明文 apiKey 字段已废弃；读到旧值会自动迁移加密。
         */
        public String apiKeyEncrypted = "";
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
        // 迁移旧模板："${model}" 多余引号与 jsonEscape 叠加产出非法 JSON
        if (loaded.requestTemplate != null
                && loaded.requestTemplate.contains("\"${model}\"")) {
            loaded.requestTemplate = loaded.requestTemplate.replace("\"${model}\"", "${model}");
        }
        this.state = loaded;
        // 旧版（或上一版 PasswordSafe）切到加密落盘后，缓存失效，下次 getApiKey 重新解密
        this.cachedApiKey = null;
    }

    /** 读取 apiKey：解密 {@link State#apiKeyEncrypted} 还原明文，解密结果驻留内存缓存。 */
    @NotNull
    public String getApiKey() {
        if (cachedApiKey != null) {
            return cachedApiKey;
        }
        String cipher = state.apiKeyEncrypted;
        if (cipher == null || cipher.isEmpty()) {
            cachedApiKey = "";
            return cachedApiKey;
        }
        try {
            byte[] enc = Base64.getDecoder().decode(cipher);
            byte[] plain = ENCRYPTION.decrypt(enc);
            cachedApiKey = plain == null ? "" : new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // 解密失败（换机器/换 OS 导致 DPAPI 密文无法解）：当作空，让用户重填
            cachedApiKey = "";
        }
        return cachedApiKey;
    }

    /** 写入 apiKey：明文加密后 base64 存进 State，随 @State 落盘；同时刷新内存缓存。 */
    public void setApiKey(@Nullable String apiKey) {
        String safe = apiKey == null ? "" : apiKey;
        if (safe.isEmpty()) {
            state.apiKeyEncrypted = "";
        } else {
            try {
                byte[] enc = ENCRYPTION.encrypt(safe.getBytes(StandardCharsets.UTF_8));
                state.apiKeyEncrypted = enc == null ? "" : Base64.getEncoder().encodeToString(enc);
            } catch (Throwable t) {
                // 加密失败：明文不落盘，宁可让用户重填也不裸存
                state.apiKeyEncrypted = "";
            }
        }
        cachedApiKey = safe;
    }

    /** "开启 AI commits" 开关的当前值；服务不可用时按默认值（开启）处理。 */
    public static boolean isAiCommitsEnabled() {
        AiCommitSettings settings = getInstance();
        return settings == null || settings.getState().enabled;
    }

    @Nullable
    public static AiCommitSettings getInstance() {
        return ApplicationManager.getApplication().getService(AiCommitSettings.class);
    }
}
