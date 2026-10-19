package com.huathy.ideaext;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/**
 * hx ext settings 页面的全局开关（应用级持久化，写入 idea-ext-hx.xml）。
 *
 * <p>所有开关默认勾选。"开启 AI commits" 不在此处重复存储，
 * 复用 {@link com.huathy.ideaext.ai.AiCommitSettings.State#enabled}，避免两处状态不一致。</p>
 */
@State(
        name = "com.huathy.ideaext.HxExtSettings",
        storages = {@Storage("idea-ext-hx.xml")}
)
public final class HxExtSettings implements PersistentStateComponent<HxExtSettings.State> {

    public static final class State {
        /** 隐藏主工具栏右上角 "Start Free Trial" 免费使用按钮，见 {@link TrialButtonHider}。 */
        public boolean hideFreeTrialButton = true;
        /** 隐藏 JetBrains AI 入口，见 {@link AiAssistantEntryHider}。 */
        public boolean hideJetBrainsAiButton = true;
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

    @NotNull
    public static HxExtSettings getInstance() {
        return ApplicationManager.getApplication().getService(HxExtSettings.class);
    }
}
