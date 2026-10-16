package com.huathy.ideaext;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 定位并移除 IDE 主工具栏右上角的试用推广按钮（TrialStateWidget，显示文案 "Start Free Trial"）。
 *
 * <p>实现策略（三层）：</p>
 * <ol>
 *   <li>通过 Action ID {@value #TRIAL_ACTION_ID} 拿到 action 实例，与按钮文字、界面语言无关；</li>
 *   <li>将其从 {@value #MAIN_TOOLBAR_RIGHT} action group 中注销，防止 IDE 重建工具栏时按钮复活；</li>
 *   <li>周期性扫描所有可见主窗口的组件树，把已渲染的按钮从父容器中物理移除。</li>
 * </ol>
 */
public final class TrialButtonHider {

    private static final String TRIAL_ACTION_ID = "TrialStateWidget";
    private static final String MAIN_TOOLBAR_RIGHT = "MainToolbarRight";

    private static final int FAST_SCAN_DELAY_MS = 3_000;
    private static final int SLOW_SCAN_DELAY_MS = 30_000;
    private static final int QUIET_FAST_CYCLES = 5;

    private static final Object LOCK = new Object();

    private static Alarm scanAlarm;
    private static int quietFastCycles;

    private TrialButtonHider() {
    }

    /**
     * 启动周期性扫描。重复调用是幂等的；在 IDE 生命周期内保持运行，随应用关闭自动释放。
     */
    public static void start() {
        synchronized (LOCK) {
            if (scanAlarm != null && !scanAlarm.isDisposed()) {
                return;
            }
            Disposable parent = ApplicationManager.getApplication();
            scanAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, parent);
            quietFastCycles = 0;
            scheduleLocked(1_000L);
        }
    }

    private static void scheduleLocked(long delayMs) {
        Alarm alarm = scanAlarm;
        if (alarm == null || alarm.isDisposed()) {
            return;
        }
        alarm.addRequest(TrialButtonHider::scanOnce, (int) Math.min(delayMs, Integer.MAX_VALUE));
    }

    private static void scanOnce() {
        synchronized (LOCK) {
            try {
                boolean removedAny = scanAllMainWindows();
                if (removedAny) {
                    quietFastCycles = 0;
                    scheduleLocked(FAST_SCAN_DELAY_MS);
                } else {
                    quietFastCycles++;
                    scheduleLocked(quietFastCycles <= QUIET_FAST_CYCLES ? FAST_SCAN_DELAY_MS : SLOW_SCAN_DELAY_MS);
                }
            } catch (Throwable t) {
                // 任何异常都不能影响 IDE 正常运行，退化为低频重试
                scheduleLocked(SLOW_SCAN_DELAY_MS);
            }
        }
    }

    private static boolean scanAllMainWindows() {
        AnAction target = resolveTrialAction();
        if (target == null) {
            return false;
        }
        removeFromActionGroups(target);

        boolean removed = false;
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window instanceof Container) {
                removed |= removeFromContainer((Container) window, target);
            }
        }
        return removed;
    }

    @Nullable
    private static AnAction resolveTrialAction() {
        try {
            return ActionManager.getInstance().getAction(TRIAL_ACTION_ID);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void removeFromActionGroups(@NotNull AnAction target) {
        try {
            AnAction groupAction = ActionManager.getInstance().getAction(MAIN_TOOLBAR_RIGHT);
            if (groupAction instanceof DefaultActionGroup) {
                ((DefaultActionGroup) groupAction).remove(target);
            }
        } catch (Throwable ignored) {
            // group 可能不可变或不存在，忽略；组件级移除仍然有效
        }
    }

    private static boolean removeFromContainer(@NotNull Container root, @NotNull AnAction target) {
        boolean removed = false;
        for (Component component : root.getComponents()) {
            if (component == null) {
                continue;
            }
            if (isTarget(component, target)) {
                Container parent = component.getParent();
                if (parent != null) {
                    parent.remove(component);
                    parent.revalidate();
                    parent.repaint();
                    removed = true;
                    continue;
                }
            }
            if (component instanceof Container) {
                removed |= removeFromContainer((Container) component, target);
            }
        }
        return removed;
    }

    private static boolean isTarget(@NotNull Component component, @NotNull AnAction target) {
        return component instanceof ActionButton && ((ActionButton) component).getAction() == target;
    }
}
