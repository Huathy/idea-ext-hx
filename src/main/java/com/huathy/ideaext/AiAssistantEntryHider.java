package com.huathy.ideaext;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.impl.AiInternalUiComponentMarker;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.Alarm;

import javax.swing.*;
import java.awt.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 隐藏 JetBrains AI 的入口，除非真正安装并启用了 AI 插件。
 *
 * <p>入口形态（2026.1 平台内置，AI 为 stub；入口由内置 fullLine 插件注册，已核对其 plugin.xml 与 intellij.llmInstaller.xml）：</p>
 * <ul>
 *   <li>顶部图标：action {@value #AI_HUB_POPUP_ACTION_ID}，注册在 MainToolbarRight 与 NavBarToolBar；</li>
 *   <li>右侧按钮及其展开的 "Install Plugin" 推广面板：工具窗口 {@value #AI_TOOL_WINDOW_ID}；</li>
 *   <li>AiInternalUiComponentMarker 标记的 action 与组件（仅真实 AI 插件会标记）。</li>
 * </ul>
 * 平台内置时 AiInternalUiComponentMarker 是 stub，isAiComponent 恒为 false，实际生效的是前两项；
 * 装了真实 AI 插件后整体跳过，保留入口。
 */
public final class AiAssistantEntryHider {

    /** 顶部 AI 入口 action id。 */
    private static final String AI_HUB_POPUP_ACTION_ID = "AIAssistantHubPopupAction";
    /** 右侧 AI Chat 工具窗口 id。 */
    private static final String AI_TOOL_WINDOW_ID = "AIAssistant";
    /** 状态栏 AI Assistant widget id（平台内置时仅作为排序锚点，保留以兼容真实 AI 插件）。 */
    private static final String AI_ASSISTANT_WIDGET_ID = "AIAssistant";
    /** 主工具栏右侧 group，与 TrialButtonHider 一致。 */
    private static final String MAIN_TOOLBAR_RIGHT = "MainToolbarRight";
    /** 导航栏工具栏 group，顶部 AI 图标同样注册于此。 */
    private static final String NAV_BAR_TOOL_BAR = "NavBarToolBar";

    private static final Logger LOG = Logger.getInstance(AiAssistantEntryHider.class);

    private static final int FAST = 3_000;
    private static final int SLOW = 30_000;
    private static final int QUIET = 5;

    private static final Object LOCK = new Object();
    private static Alarm alarm;
    private static int quietCycles;
    private static Boolean aiPluginInstalled;
    private static final AiInternalUiComponentMarker AI_MARKER = new AiInternalUiComponentMarker();
    /** 已记录过的隐藏事件 key，同一事件只记录一次，避免周期扫描刷屏。 */
    private static final Set<String> LOGGED = new HashSet<>();

    private AiAssistantEntryHider() {}

    public static void start() {
        synchronized (LOCK) {
            if (alarm != null && !alarm.isDisposed()) return;
            Disposable parent = ApplicationManager.getApplication();
            alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, parent);
            quietCycles = 0;
            aiPluginInstalled = null;
            schedule(1_000L);
        }
    }

    private static void schedule(long ms) {
        Alarm a;
        synchronized (LOCK) { a = alarm; }
        if (a == null || a.isDisposed()) return;
        a.addRequest(AiAssistantEntryHider::scan, (int) Math.min(ms, Integer.MAX_VALUE));
    }

    private static void scan() {
        synchronized (LOCK) {
            try {
                if (isAiAssistantPluginInstalled()) {
                    // 真正安装并启用了 AI 插件：保留入口，仅低频复查。
                    schedule(SLOW);
                    return;
                }
                boolean removed = hideFromStatusBars();
                removed |= removeFromToolbarGroup();
                removed |= removeFromComponentTree();
                removed |= hideAiToolWindow();
                if (removed) {
                    quietCycles = 0;
                    schedule(FAST);
                } else {
                    quietCycles++;
                    schedule(quietCycles <= QUIET ? FAST : SLOW);
                }
            } catch (Throwable t) {
                schedule(SLOW);
            }
        }
    }

    /**
     * 检测是否真正安装并启用了 JetBrains AI Assistant 插件。
     * plugin id 在 2026.1 内置后可能变化，故用 name 与已知 id 双重判断。
     */
    private static boolean isAiAssistantPluginInstalled() {
        if (aiPluginInstalled != null) return aiPluginInstalled;
        boolean result = false;
        try {
            List<IdeaPluginDescriptor> plugins = PluginManagerCore.getLoadedPlugins();
            if (plugins != null) {
                for (IdeaPluginDescriptor p : plugins) {
                    if (p == null) continue;
                    String name = p.getName();
                    if (name != null && name.toLowerCase().contains("ai assistant")) {
                        result = true; break;
                    }
                    String id = p.getPluginId() == null ? null : p.getPluginId().getIdString();
                    if ("com.intellij.aiAssistant".equals(id) || "com.intellij.ai".equals(id)) {
                        result = true; break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        // 插件安装状态运行时不变，缓存结果。
        if (result) aiPluginInstalled = true;
        return result;
    }

    private static boolean hideFromStatusBars() {
        boolean removed = false;
        for (Window w : Window.getWindows()) {
            if (w instanceof IdeFrame) {
                StatusBar sb = ((IdeFrame) w).getStatusBar();
                if (sb == null) continue;
                try {
                    if (sb.getWidget(AI_ASSISTANT_WIDGET_ID) != null) {
                        sb.removeWidget(AI_ASSISTANT_WIDGET_ID);
                        removed = true;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return removed;
    }

    /**
     * 从主工具栏与导航栏 group 中移除 AI 入口 action：
     * AI 插件标记的 action，以及顶部 AI 图标 {@value #AI_HUB_POPUP_ACTION_ID}。
     * 后者按 ID 移除（DefaultActionGroup.remove(action, ActionManager) 内部按 getId 匹配，兼容 ActionStub）。
     */
    private static boolean removeFromToolbarGroup() {
        boolean removed = false;
        ActionManager actionManager;
        try {
            actionManager = ActionManager.getInstance();
        } catch (Throwable t) {
            return false;
        }
        for (String groupId : new String[]{MAIN_TOOLBAR_RIGHT, NAV_BAR_TOOL_BAR}) {
            try {
                AnAction groupAction = actionManager.getAction(groupId);
                if (!(groupAction instanceof DefaultActionGroup)) {
                    continue;
                }
                DefaultActionGroup group = (DefaultActionGroup) groupAction;
                for (AnAction child : group.getChildren(actionManager)) {
                    try {
                        if (child instanceof AiInternalUiComponentMarker.AIAction) {
                            group.remove(child);
                            logOnce("marker:" + groupId, "removed AI marker action from " + groupId);
                            removed = true;
                        } else if (AI_HUB_POPUP_ACTION_ID.equals(actionManager.getId(child))) {
                            group.remove(child, actionManager);
                            logOnce("hub:" + groupId, "removed " + AI_HUB_POPUP_ACTION_ID + " from " + groupId);
                            removed = true;
                        }
                    } catch (Throwable ignored) {
                        // 单个 action 移除失败不影响其余项
                    }
                }
            } catch (Throwable ignored) {
                // group 不存在或不可用，忽略；组件级移除仍然有效
            }
        }
        return removed;
    }

    private static boolean removeFromComponentTree() {
        boolean removed = false;
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w instanceof Container) {
                removed |= removeAi((Container) w);
            }
        }
        return removed;
    }

    /**
     * 隐藏右侧 AI Chat 工具窗口：关闭其 stripe 按钮并设为不可用，已展开的窗口会被收起。
     * 工具窗口按项目注册，因此遍历所有已打开项目。返回本轮是否真的做了修改。
     */
    private static boolean hideAiToolWindow() {
        ProjectManager projectManager = ProjectManager.getInstanceIfCreated();
        if (projectManager == null) {
            return false;
        }
        boolean changed = false;
        for (Project project : projectManager.getOpenProjects()) {
            try {
                ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(AI_TOOL_WINDOW_ID);
                if (toolWindow != null && (toolWindow.isShowStripeButton() || toolWindow.isAvailable())) {
                    toolWindow.setShowStripeButton(false);
                    toolWindow.setAvailable(false);
                    logOnce("toolwindow:" + AI_TOOL_WINDOW_ID, "hid tool window " + AI_TOOL_WINDOW_ID);
                    changed = true;
                }
            } catch (Throwable ignored) {
                // 项目正在初始化或关闭、工具窗口尚未注册：下一轮扫描重试
            }
        }
        return changed;
    }

    private static boolean removeAi(Container root) {
        boolean removed = false;
        for (Component c : root.getComponents()) {
            if (c == null) continue;
            if (isAiEntry(c)) {
                Container parent = c.getParent();
                if (parent != null) {
                    parent.remove(c);
                    parent.revalidate();
                    parent.repaint();
                    removed = true;
                    continue;
                }
            }
            if (c instanceof Container) {
                removed |= removeAi((Container) c);
            }
        }
        return removed;
    }

    /** 需要从界面移除的 AI 入口组件：AI 插件标记的组件，或顶部 AI 图标按钮。 */
    private static boolean isAiEntry(Component c) {
        return isAiComponent(c) || isAiHubButton(c);
    }

    private static boolean isAiComponent(Component c) {
        try {
            return c instanceof JComponent && AI_MARKER.isAiComponent((JComponent) c);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 顶部 AI 图标在工具栏上的渲染按钮：ActionButton 绑定的 action 即 AI 入口 action。 */
    private static boolean isAiHubButton(Component c) {
        if (!(c instanceof ActionButton)) {
            return false;
        }
        try {
            AnAction action = ((ActionButton) c).getAction();
            return action != null && AI_HUB_POPUP_ACTION_ID.equals(ActionManager.getInstance().getId(action));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 同一事件只记录一次日志，用于在 idea.log 中核对隐藏是否生效。 */
    private static void logOnce(String key, String message) {
        if (LOGGED.add(key)) {
            LOG.info("[idea-ext-hx] " + message);
        }
    }
}
