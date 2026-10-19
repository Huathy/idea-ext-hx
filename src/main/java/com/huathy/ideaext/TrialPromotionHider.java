package com.huathy.ideaext;

import com.intellij.ide.AppLifecycleListener;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 插件入口：IDE 主窗口/欢迎屏创建时，按 hx ext settings 中的开关启动对应的隐藏扫描器。
 */
public final class TrialPromotionHider implements AppLifecycleListener {

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        startEnabledHiders();
    }

    @Override
    public void welcomeScreenDisplayed() {
        startEnabledHiders();
    }

    /** 开关默认全部勾选即全部启动；取消勾选后不再启动对应扫描器（需重启 IDE 生效）。 */
    private static void startEnabledHiders() {
        HxExtSettings.State settings = HxExtSettings.getInstance().getState();
        if (settings.hideFreeTrialButton) {
            TrialButtonHider.start();
        }
        if (settings.hideJetBrainsAiButton) {
            AiAssistantEntryHider.start();
        }
    }
}
