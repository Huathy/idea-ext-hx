package com.huathy.ideaext;

import com.huathy.ideaext.ai.AiCommitSettings;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Settings → Other Settings → hx ext settings 页面：插件全局开关，默认全部勾选。
 *
 * <ul>
 *   <li>隐藏免费使用按钮：{@link HxExtSettings.State#hideFreeTrialButton}，重启 IDE 生效；</li>
 *   <li>隐藏 JetBrains AI 按钮：{@link HxExtSettings.State#hideJetBrainsAiButton}，重启 IDE 生效；</li>
 *   <li>开启 AI commits：{@link AiCommitSettings.State#enabled}，控制提交面板的 AI 生成按钮，无需重启。</li>
 * </ul>
 */
public final class HxExtConfigurable implements SearchableConfigurable {

    private static final String ID = "idea-ext-hx.settings";

    private JBCheckBox hideFreeTrial;
    private JBCheckBox hideJetBrainsAi;
    private JBCheckBox enableAiCommit;
    private JComponent root;

    @Override
    @NotNull
    public String getId() {
        return ID;
    }

    @Override
    @Nls
    public String getDisplayName() {
        return "HxExt Settings";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        hideFreeTrial = new JBCheckBox("隐藏免费使用按钮（主工具栏 Start Free Trial）");
        hideJetBrainsAi = new JBCheckBox("隐藏 JetBrains AI 按钮（状态栏 / 工具栏 AI 入口）");
        enableAiCommit = new JBCheckBox("开启 AI commits（在提交面板显示「AI 生成提交信息」按钮）");
        loadFromState();

        JBLabel tip = new JBLabel("隐藏类选项需重启 IDE 生效；AI commits 无需重启。");
        tip.setForeground(UIManager.getColor("Label.infoForeground"));

        root = new JBPanel<>(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = JBUI.insets(4);
        c.gridy = 0;
        root.add(hideFreeTrial, c);
        c.gridy = 1;
        root.add(hideJetBrainsAi, c);
        c.gridy = 2;
        root.add(enableAiCommit, c);
        c.gridy = 3;
        c.insets = JBUI.insets(12, 4, 4, 4);
        root.add(tip, c);
        // 底部填充，使选项顶部对齐
        c.gridy = 4;
        c.weighty = 1.0;
        c.fill = GridBagConstraints.BOTH;
        root.add(Box.createVerticalGlue(), c);
        return root;
    }

    @Override
    public boolean isModified() {
        if (hideFreeTrial == null) {
            return false;
        }
        HxExtSettings.State ext = HxExtSettings.getInstance().getState();
        return hideFreeTrial.isSelected() != ext.hideFreeTrialButton
                || hideJetBrainsAi.isSelected() != ext.hideJetBrainsAiButton
                || enableAiCommit.isSelected() != AiCommitSettings.isAiCommitsEnabled();
    }

    @Override
    public void apply() {
        if (hideFreeTrial == null) {
            return;
        }
        HxExtSettings.State ext = HxExtSettings.getInstance().getState();
        ext.hideFreeTrialButton = hideFreeTrial.isSelected();
        ext.hideJetBrainsAiButton = hideJetBrainsAi.isSelected();
        AiCommitSettings ai = AiCommitSettings.getInstance();
        if (ai != null) {
            ai.getState().enabled = enableAiCommit.isSelected();
        }
    }

    @Override
    public void reset() {
        if (hideFreeTrial != null) {
            loadFromState();
        }
    }

    @Override
    public void disposeUIResources() {
        root = null;
        hideFreeTrial = null;
        hideJetBrainsAi = null;
        enableAiCommit = null;
    }

    /** 将持久化的当前值写入界面控件。 */
    private void loadFromState() {
        HxExtSettings.State ext = HxExtSettings.getInstance().getState();
        hideFreeTrial.setSelected(ext.hideFreeTrialButton);
        hideJetBrainsAi.setSelected(ext.hideJetBrainsAiButton);
        enableAiCommit.setSelected(AiCommitSettings.isAiCommitsEnabled());
    }
}
