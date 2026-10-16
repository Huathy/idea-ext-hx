package com.huathy.ideaext;

import com.intellij.ide.AppLifecycleListener;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 插件入口：IDE 主窗口/欢迎屏创建时启动试用按钮扫描器。
 */
public final class TrialPromotionHider implements AppLifecycleListener {

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        TrialButtonHider.start();
    }

    @Override
    public void welcomeScreenDisplayed() {
        TrialButtonHider.start();
    }
}
