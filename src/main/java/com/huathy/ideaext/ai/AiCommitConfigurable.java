package com.huathy.ideaext.ai;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings → Tools → AI Commit Message 配置页。
 * 支持自定义 AI 供应商（baseUrl/apiKey/model）、请求模板、响应路径与提示词模板。
 * API Key 可切换明文显示；提供"测试连接"按钮验证当前配置。
 */
public final class AiCommitConfigurable implements SearchableConfigurable {

    private static final String ID = "idea-ext-hx.ai-commit";

    private AiCommitSettings settings;
    private AiCommitSettings.State state;
    private JComponent root;

    private JBTextField baseUrl;
    private JPasswordField apiKey;
    private JCheckBox showKey;
    private JBTextField model;
    private JBTextField timeout;
    private JBTextField maxDiffBytes;
    private JBTextArea customInstructions;
    private JBTextArea promptTemplate;
    private JBTextArea requestTemplate;
    private JBTextField responsePath;
    private JBLabel testResult;

    @Override
    @NotNull
    public String getId() {
        return ID;
    }

    @Override
    @Nls(capitalization = Nls.Capitalization.Title)
    public String getDisplayName() {
        return "AI Commit Message";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        settings = AiCommitSettings.getInstance();
        state = settings == null ? new AiCommitSettings.State() : settings.getState();

        root = new JBPanel<>(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = JBUI.insets(4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;

        // "开启 AI commits" 总开关已移至 Settings → Other Settings → hx ext settings
        JBLabel moved = new JBLabel("「开启 AI commits」开关已移至 Settings → Other Settings → HxExt Settings。");
        moved.setForeground(UIManager.getColor("Label.infoForeground"));
        c.gridx = 0; c.gridy = 0; c.gridwidth = 3;
        root.add(moved, c);

        int row = 1;
        baseUrl = new JBTextField(state.baseUrl);
        model = new JBTextField(state.model);
        apiKey = new JPasswordField(settings == null ? "" : settings.getApiKey());
        apiKey.setEchoChar('*');
        showKey = new JCheckBox("显示明文");
        showKey.addActionListener(e -> apiKey.setEchoChar(showKey.isSelected() ? (char) 0 : '*'));
        timeout = new JBTextField(String.valueOf(state.timeoutSeconds));
        maxDiffBytes = new JBTextField(String.valueOf(state.maxDiffBytes));
        responsePath = new JBTextField(state.responsePath);

        addField(root, c, row++, "Base URL（供应商）", baseUrl);
        addKeyField(root, c, row++);
        addField(root, c, row++, "模型", model);
        addField(root, c, row++, "超时（秒）", timeout);
        addField(root, c, row++, "Diff 最大字节数", maxDiffBytes);
        addField(root, c, row++, "响应解析路径", responsePath);

        // 测试连接
        JButton testBtn = new JButton("测试连接");
        testResult = new JBLabel(" ");
        testBtn.addActionListener(e -> runTest());
        c.gridx = 0; c.gridy = row++; c.gridwidth = 1; c.weightx = 0.0;
        root.add(testBtn, c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1.0;
        root.add(testResult, c);

        customInstructions = new JBTextArea(state.customInstructions);
        addArea(root, c, row++, "自定义补充指令（${customInstructions}，可选）", customInstructions);

        requestTemplate = new JBTextArea(state.requestTemplate);
        addArea(root, c, row++, "请求体模板（含 ${model} ${prompt} ${diff}）", requestTemplate);

        promptTemplate = new JBTextArea(state.promptTemplate);
        addArea(root, c, row++, "提示词模板（含 ${customInstructions} ${gitContext}）", promptTemplate);

        c.gridx = 0; c.gridy = row; c.gridwidth = 3; c.weighty = 1.0; c.fill = GridBagConstraints.BOTH;
        root.add(new JBLabel("提示词与模板为 JSON 字符串拼接，${prompt}/${diff} 会自动转义。默认兼容 OpenAI Chat Completions。"), c);

        return root;
    }

    private void addField(JComponent p, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0.0;
        p.add(new JBLabel(label), c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1.0;
        p.add(field, c);
    }

    private void addKeyField(JComponent p, GridBagConstraints c, int row) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0.0;
        p.add(new JBLabel("API Key"), c);
        c.gridx = 1; c.gridwidth = 1; c.weightx = 1.0;
        p.add(apiKey, c);
        c.gridx = 2; c.gridwidth = 1; c.weightx = 0.0;
        p.add(showKey, c);
    }

    private void addArea(JComponent p, GridBagConstraints c, int row, String label, JBTextArea area) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 3; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL;
        p.add(new JBLabel(label), c);
        c.gridy = row + 1; c.fill = GridBagConstraints.BOTH; c.weighty = 0.3;
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(JBUI.size(400, 120));
        p.add(scroll, c);
    }

    /** 用 UI 当前值（未保存）构造配置并测试。 */
    private void runTest() {
        AiCommitSettings.State cfgProbe = new AiCommitSettings.State();
        cfgProbe.enabled = true;
        cfgProbe.baseUrl = baseUrl.getText().trim();
        final String probeApiKey = new String(apiKey.getPassword());
        cfgProbe.model = model.getText().trim();
        cfgProbe.timeoutSeconds = parseInt(timeout.getText(), 30);
        cfgProbe.maxDiffBytes = 1000;
        cfgProbe.responsePath = responsePath.getText().trim();
        cfgProbe.requestTemplate = requestTemplate.getText().isEmpty() ? AiCommitSettings.DEFAULT_REQUEST_TEMPLATE : requestTemplate.getText();
        cfgProbe.promptTemplate = "仅回复：OK";
        cfgProbe.customInstructions = "";

        if (cfgProbe.baseUrl.isEmpty()) { testResult.setText("请先填写 Base URL"); return; }
        if (cfgProbe.model.isEmpty()) { testResult.setText("请先填写模型名"); return; }

        testResult.setText("测试中…");
        testResult.setForeground(UIManager.getColor("Label.disabledForeground"));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String text;
            String proto = null;
            try {
                AiCommitClient.ProbeResult probe = AiCommitClient.test(cfgProbe, probeApiKey);
                proto = probe.protocol;
                String reply = probe.reply;
                String shortReply = reply.length() > 60 ? reply.substring(0, 60) + "…" : reply;
                text = "成功 ✓ 协议=" + proto + "（已记住，下次自动使用）回复：" + shortReply;
            } catch (IllegalStateException he) {
                text = "失败 ✗ HTTP " + he.getMessage();
            } catch (Exception ex) {
                text = "失败 ✗ " + ex.getMessage();
            }
            final String result = text;
            final String rememberedProto = proto;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (rememberedProto != null) {
                    state.httpVersion = rememberedProto;
                }
                testResult.setText(result);
                testResult.setForeground(result.startsWith("成功") ? new Color(0x2E7D32) : new Color(0xC62828));
                Messages.showInfoMessage(result, "AI 连接测试");
            });
        });
    }

    @Override
    public boolean isModified() {
        String currentApiKey = settings == null ? "" : settings.getApiKey();
        return state == null
                || !baseUrl.getText().equals(state.baseUrl)
                || !new String(apiKey.getPassword()).equals(currentApiKey)
                || !model.getText().equals(state.model)
                || !timeout.getText().equals(String.valueOf(state.timeoutSeconds))
                || !maxDiffBytes.getText().equals(String.valueOf(state.maxDiffBytes))
                || !responsePath.getText().equals(state.responsePath)
                || !customInstructions.getText().equals(state.customInstructions)
                || !requestTemplate.getText().equals(state.requestTemplate)
                || !promptTemplate.getText().equals(state.promptTemplate);
    }

    @Override
    public void apply() {
        if (state == null) return;
        state.baseUrl = baseUrl.getText().trim();
        if (settings != null) {
            settings.setApiKey(new String(apiKey.getPassword()));
        }
        state.model = model.getText().trim();
        state.timeoutSeconds = parseInt(timeout.getText(), 30);
        state.maxDiffBytes = parseInt(maxDiffBytes.getText(), 60000);
        state.responsePath = responsePath.getText().trim();
        state.customInstructions = customInstructions.getText();
        state.requestTemplate = requestTemplate.getText();
        state.promptTemplate = promptTemplate.getText();
    }

    @Override
    public void reset() {
        // 由 createComponent 重新读取
    }

    @Override
    public void disposeUIResources() {
        root = null;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
