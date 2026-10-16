package com.huathy.ideaext.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用 AI 供应商生成提交信息。兼容 OpenAI Chat Completions 协议，并支持自定义请求/响应模板。
 *
 * <p>HTTP 协议自适应：测试时先试 HTTP/2，失败降级 HTTP/1.1，成功协议记入配置，
 * 下次生成直接复用记忆协议，避免每次降级重试。</p>
 *
 * <p>请求模板占位符（值会被 JSON 字符串转义）：${model} ${prompt} ${diff}</p>
 * <p>响应路径示例：choices[0].message.content</p>
 */
public final class AiCommitClient {

    public static final String PROTO_HTTP_1_1 = "HTTP_1_1";
    public static final String PROTO_HTTP_2 = "HTTP_2";

    private AiCommitClient() {
    }

    /** 用记忆协议（未记忆则先 HTTP/2 降级 HTTP/1.1）生成提交信息。 */
    public static String generate(@NotNull AiCommitSettings.State cfg, @NotNull String gitDiff) throws Exception {
        HttpClient.Version ver = resolveVersion(cfg.httpVersion);
        if (ver != null) {
            return doGenerate(cfg, gitDiff, ver);
        }
        // 未探测：先试 HTTP/2，连接级失败降级 HTTP/1.1
        try {
            return doGenerate(cfg, gitDiff, HttpClient.Version.HTTP_2);
        } catch (IOException io) {
            return doGenerate(cfg, gitDiff, HttpClient.Version.HTTP_1_1);
        }
    }

    /** 探测可用协议：先 HTTP/2，连接级失败降级 HTTP/1.1，返回成功协议与回复。 */
    public static ProbeResult test(@NotNull AiCommitSettings.State cfg) throws Exception {
        String fakeDiff = "diff --git a/test.txt b/test.txt\n"
                + "index 0000000..1111111 100644\n--- /dev/null\n+++ b/test.txt\n"
                + "@@ -0,0 +1 @@\n+hello world\n";
        try {
            String reply = doGenerate(cfg, fakeDiff, HttpClient.Version.HTTP_2);
            return new ProbeResult(PROTO_HTTP_2, reply);
        } catch (IOException ignore) {
            // HTTP/2 不通，降级 HTTP/1.1
        }
        String reply = doGenerate(cfg, fakeDiff, HttpClient.Version.HTTP_1_1);
        return new ProbeResult(PROTO_HTTP_1_1, reply);
    }

    public static final class ProbeResult {
        public final String protocol;
        public final String reply;

        public ProbeResult(String protocol, String reply) {
            this.protocol = protocol;
            this.reply = reply;
        }
    }

    @NotNull
    private static HttpClient.Version resolveVersion(String stored) {
        if (PROTO_HTTP_1_1.equals(stored)) return HttpClient.Version.HTTP_1_1;
        if (PROTO_HTTP_2.equals(stored)) return HttpClient.Version.HTTP_2;
        return null;
    }

    private static String doGenerate(@NotNull AiCommitSettings.State cfg, @NotNull String gitDiff,
                                    @NotNull HttpClient.Version version) throws Exception {
        String url = stripTrailingSlash(cfg.baseUrl) + "/chat/completions";
        String body = buildRequestBody(cfg, gitDiff);

        HttpClient client = HttpClient.newBuilder()
                .version(version)
                .connectTimeout(Duration.ofSeconds(Math.max(5, cfg.timeoutSeconds)))
                .build();

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + cfg.apiKey);
        }

        HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = resp.statusCode();
        String payload = resp.body();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + truncate(payload, 500));
        }
        return extractByPath(payload, cfg.responsePath);
    }

    private static String buildRequestBody(@NotNull AiCommitSettings.State cfg, @NotNull String gitDiff) {
        String prompt = cfg.promptTemplate
                .replace("${customInstructions}", nullToEmpty(cfg.customInstructions))
                .replace("${gitContext}", "## Git Diff\n```\n" + gitDiff + "\n```");
        String template = cfg.requestTemplate == null || cfg.requestTemplate.isEmpty()
                ? AiCommitSettings.DEFAULT_REQUEST_TEMPLATE : cfg.requestTemplate;
        return template
                .replace("${model}", jsonEscape(cfg.model))
                .replace("${prompt}", jsonEscape(prompt))
                .replace("${diff}", jsonEscape(gitDiff));
    }

    /** 把字符串转义为 JSON 字符串字面量（含双引号）。 */
    private static String jsonEscape(@NotNull String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 按点+索引路径从 JSON 中提取字符串。路径形如 a.b[0].c */
    static String extractByPath(@NotNull String json, @NotNull String path) throws Exception {
        JsonElement el = JsonParser.parseString(json);
        Pattern token = Pattern.compile("([^\\.\\[]+)(\\[(\\d+)\\])?");
        for (String raw : path.split("\\.")) {
            Matcher m = token.matcher(raw);
            if (!m.matches()) throw new IllegalStateException("非法路径段: " + raw);
            String key = m.group(1);
            el = el.getAsJsonObject().get(key);
            if (el == null) throw new IllegalStateException("路径 " + key + " 不存在");
            if (m.group(2) != null) {
                JsonArray arr = el.getAsJsonArray();
                int idx = Integer.parseInt(m.group(3));
                el = arr.get(idx);
            }
        }
        if (el == null || el.isJsonNull()) throw new IllegalStateException("响应节点为空");
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
