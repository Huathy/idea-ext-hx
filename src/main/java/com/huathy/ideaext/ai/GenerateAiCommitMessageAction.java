package com.huathy.ideaext.ai;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.CommitMessageI;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.vcsUtil.VcsUtil;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcs.commit.CommitWorkflowUi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在提交对话框中根据勾选文件的 diff 生成约定式提交信息。
 *
 * <p>注册位置：commit message 输入框工具栏（Vcs.MessageActionGroup）。</p>
 * <p>优先使用提交面板中勾选（included）的变更；未勾选则使用全部变更。</p>
 *
 * <p>请求中按钮变为「⏹ 停止生成」可点击，点击即取消当前请求（手动暂停）；
 * 生成完成直接填入提交信息，不再弹窗提示。</p>
 */
public final class GenerateAiCommitMessageAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(GenerateAiCommitMessageAction.class);

    /** 与 plugin.xml 注册 text 一致，请求结束后恢复。 */
    private static final String NORMAL_TEXT = "AI 生成提交信息";
    private static final String BUSY_TEXT = "⏹ 停止生成";
    private static final String BUSY_DESC = "点击停止当前生成";

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile ProgressIndicator currentIndicator;
    private static volatile Presentation currentPresentation;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.getBasePath() == null) {
            Messages.showWarningDialog("未打开项目", "AI 提交信息");
            return;
        }

        // 请求中：再次点击即手动暂停（取消当前请求）
        if (running.get()) {
            ProgressIndicator ind = currentIndicator;
            if (ind != null) {
                ind.cancel();
            }
            return;
        }

        CommitMessageI cmi = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL);
        if (cmi == null) {
            notifyUser(project, "未检测到提交消息面板");
            return;
        }
        AiCommitSettings settings = AiCommitSettings.getInstance();
        AiCommitSettings.State cfg = settings == null ? new AiCommitSettings.State() : settings.getState();
        if (!cfg.enabled) {
            Messages.showWarningDialog("AI 提交信息功能未启用，请在 Settings → Other Settings → HxExt Settings 中勾选「开启 AI commits」。", "AI 提交信息");
            return;
        }
        if (cfg.baseUrl == null || cfg.baseUrl.trim().isEmpty()) {
            notifyUser(project, "未配置 Base URL，请在 Settings → Tools → AI Commit Message 填写并点 Apply 保存。");
            return;
        }
        // ponytail: apiKey 走 PasswordSafe 加密存储，不放在 State 里，故从 settings 单独读取
        String apiKey = settings == null ? "" : settings.getApiKey();
        if (apiKey.trim().isEmpty()) {
            notifyUser(project, "未配置 API Key，请在 Settings → Tools → AI Commit Message 填写并点 Apply 保存。");
            return;
        }

        // 从提交面板收集勾选的变更；未勾选则取全部
        Collection<Change> changes = collectChanges(e, cmi, project);

        final CommitMessageI panel = cmi;
        final Presentation pres = e.getPresentation();
        currentPresentation = pres;
        setBusy(pres);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "AI 生成提交信息", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                running.set(true);
                currentIndicator = indicator;
                indicator.setIndeterminate(true);
                indicator.setText("正在收集代码变更…");
                try {
                    String diff = collectDiffFromChanges(project, changes);
                    if (diff.isEmpty()) {
                        notifyUser(project, "未检测到代码变更");
                        return;
                    }
                    if (diff.length() > cfg.maxDiffBytes) {
                        diff = diff.substring(0, cfg.maxDiffBytes)
                                + "\n...（diff 已截断，共 " + diff.length() + " 字节）";
                    }
                    indicator.setText("正在请求 AI 服务…");
                    String message = stripCodeFence(AiCommitClient.generate(cfg, apiKey, diff).trim());
                    if (message.isEmpty()) {
                        notifyUser(project, "AI 返回空结果");
                        return;
                    }
                    // 用户已手动暂停：丢弃结果，不再填入
                    if (indicator.isCanceled()) {
                        return;
                    }
                    final String finalMessage = message;
                    ApplicationManager.getApplication().invokeLater(() -> panel.setCommitMessage(finalMessage));
                } catch (ProcessCanceledException pce) {
                    // 手动暂停：静默丢弃
                } catch (java.io.IOException io) {
                    LOG.warn("AI commit message failed", io);
                    notifyUser(project, "无法连接 AI 服务，请检查 Base URL / 网络 / 代理是否正确：" + io.getMessage());
                } catch (IllegalStateException he) {
                    LOG.warn("AI commit message failed", he);
                    notifyUser(project, "AI 服务返回错误：" + he.getMessage());
                } catch (Exception ex) {
                    LOG.warn("AI commit message failed", ex);
                    notifyUser(project, "生成失败：" + ex.getMessage());
                } finally {
                    running.set(false);
                    currentIndicator = null;
                    ApplicationManager.getApplication().invokeLater(
                            () -> setNormal(currentPresentation));
                }
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        // 项目已打开，且 hx ext settings 中开启了 AI commits 时才在提交面板展示
        boolean visible = project != null && project.getBasePath() != null && AiCommitSettings.isAiCommitsEnabled();
        Presentation p = e.getPresentation();
        p.setEnabledAndVisible(visible);
        // 请求中显示停止态；否则恢复常态
        if (visible && running.get()) {
            setBusy(p);
        } else {
            setNormal(p);
        }
    }

    private static void setBusy(@NotNull Presentation p) {
        p.setText(BUSY_TEXT);
        p.setDescription(BUSY_DESC);
    }

    private static void setNormal(@Nullable Presentation p) {
        if (p == null) return;
        p.setText(NORMAL_TEXT);
        p.setDescription("根据暂存区 diff 生成约定式提交信息");
    }

    /**
     * 优先从提交面板拿勾选（included）的变更；未勾选则取默认 changelist 全部变更。
     *
     * <p>三层取法：</p>
     * <ol>
     *   <li>反射 {@code getIncludedChanges()}：modal commit dialog（老 UI）的勾选项</li>
     *   <li>{@link CheckinProjectPanel#getVirtualFiles()}：接口方法，2026.1 新版 commit tool window
     *       （CommitProjectPanelAdapter 无 getIncludedChanges）也覆盖，included 文件经
     *       ChangeListManager 转回 Change</li>
     *   <li>全空 → 默认 changelist 全部变更</li>
     * </ol>
     *
     * <p>注意：{@link CheckinProjectPanel#getSelectedChanges()} 是 UI"高亮选中"，
     * 不是复选框"勾选"，不用它。</p>
     */
    @NotNull
    private static Collection<Change> collectChanges(@NotNull AnActionEvent e,
                                                     @NotNull Object cmi,
                                                     @NotNull Project project) {
        // 1. 新 UI（2025.x+ commit tool window）：直接取 CommitWorkflowUi 的勾选变更
        CommitWorkflowUi ui = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI);
        if (ui != null) {
            List<Change> included = ui.getIncludedChanges();
            LOG.info("[AI commit] COMMIT_WORKFLOW_UI included=" + included.size());
            if (!included.isEmpty()) {
                return included;
            }
        }
        // 2. 老 UI（CommitChangeListDialog）：反射 getIncludedChanges
        Collection<Change> reflected = invokeGetIncludedChanges(cmi);
        LOG.info("[AI commit] reflect getIncludedChanges=" + (reflected == null ? "n/a" : reflected.size()));
        if (reflected != null && !reflected.isEmpty()) {
            return reflected;
        }
        // 3. CheckinProjectPanel.getSelectedChanges()：新 UI adapter 中等价于勾选项
        if (cmi instanceof CheckinProjectPanel) {
            Collection<Change> sel = ((CheckinProjectPanel) cmi).getSelectedChanges();
            LOG.info("[AI commit] CheckinProjectPanel.getSelectedChanges=" + (sel == null ? "null" : sel.size())
                    + " cmi=" + cmi.getClass().getName());
            if (sel != null && !sel.isEmpty()) {
                return sel;
            }
        } else {
            LOG.info("[AI commit] cmi is not CheckinProjectPanel: " + cmi.getClass().getName());
        }
        // 4. 没勾选 → 默认 changelist 全部变更
        LOG.info("[AI commit] no included change, fallback to default changelist");
        return ChangeListManager.getInstance(project).getDefaultChangeList().getChanges();
    }

    /** 反射调用 getIncludedChanges()，兼容不同 commit 面板实现。 */
    @Nullable
    @SuppressWarnings("unchecked")
    private static Collection<Change> invokeGetIncludedChanges(@NotNull Object panel) {
        try {
            java.lang.reflect.Method m = panel.getClass().getMethod("getIncludedChanges");
            Object result = m.invoke(panel);
            if (result instanceof Collection) {
                return (Collection<Change>) result;
            }
        } catch (NoSuchMethodException ignored) {
            // 实现类无 getIncludedChanges（非 CommitChangeListDialog），回退全部
        } catch (Throwable t) {
            LOG.warn("invoke getIncludedChanges failed", t);
        }
        return null;
    }

    /**
     * 从 Change 集合构建 diff 字符串。按 VCS root 分组跑 git diff；
     * 新文件（untracked）用 ContentRevision 取内容。
     */
    @NotNull
    private static String collectDiffFromChanges(@NotNull Project project,
                                                 @NotNull Collection<Change> changes) {
        if (changes.isEmpty()) return "";

        Map<VirtualFile, List<String>> rootToFiles = new LinkedHashMap<>();
        List<Change> newFiles = new ArrayList<>();

        for (Change change : changes) {
            ContentRevision after = change.getAfterRevision();
            ContentRevision before = change.getBeforeRevision();
            VirtualFile vf = null;
            if (after != null) {
                vf = after.getFile().getVirtualFile();
            }
            if (vf == null && before != null) {
                vf = before.getFile().getVirtualFile();
            }
            if (vf == null) continue;

            if (change.getType() == Change.Type.NEW) {
                newFiles.add(change);
                continue;
            }

            VirtualFile root = VcsUtil.getVcsRootFor(project, vf);
            if (root == null) {
                root = vf.getParent() != null ? vf.getParent() : vf;
            }

            String rootPath = root.getPath();
            String filePath = vf.getPath();
            String relPath = filePath.startsWith(rootPath + "/")
                    ? filePath.substring(rootPath.length() + 1)
                    : vf.getName();

            rootToFiles.computeIfAbsent(root, k -> new ArrayList<>()).add(relPath);
        }

        StringBuilder sb = new StringBuilder();

        for (Map.Entry<VirtualFile, List<String>> entry : rootToFiles.entrySet()) {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("-C");
            cmd.add(entry.getKey().getPath());
            cmd.add("diff");
            cmd.add("HEAD");
            cmd.add("--");
            cmd.addAll(entry.getValue());
            String diff = runCommand(cmd);
            if (!diff.isEmpty()) {
                sb.append(diff);
                if (!diff.endsWith("\n")) sb.append("\n");
            }
        }

        // 新文件：直接取内容拼成 diff
        for (Change change : newFiles) {
            try {
                ContentRevision after = change.getAfterRevision();
                if (after != null) {
                    String content = after.getContent();
                    String path = after.getFile().getPath();
                    sb.append("--- /dev/null\n+++ ").append(path).append("\n");
                    if (content != null) {
                        for (String line : content.split("\n", -1)) {
                            sb.append("+").append(line).append("\n");
                        }
                    }
                    sb.append("\n");
                }
            } catch (VcsException ex) {
                LOG.warn("Failed to get content for new file", ex);
            }
        }

        return sb.toString();
    }

    @Nullable
    private static String runCommand(@NotNull List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true).start();
            String out = readAll(p.getInputStream());
            int code = p.waitFor();
            if (code != 0) {
                LOG.warn(String.join(" ", cmd) + " exit=" + code + " out=" + truncate(out, 200));
                return "";
            }
            return out;
        } catch (Exception ex) {
            LOG.warn("exec failed: " + String.join(" ", cmd), ex);
            return "";
        }
    }

    private static String readAll(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString(StandardCharsets.UTF_8);
    }

    /** 剥掉 AI 输出首尾 ``` 围栏（含 ```lang 语言标注）。 */
    @NotNull
    private static String stripCodeFence(@NotNull String s) {
        return s.replaceAll("^\\s*```[A-Za-z0-9_-]*\\s*", "")
                .replaceAll("\\s*```\\s*$", "")
                .trim();
    }

    private static void notifyUser(@Nullable Project project, @NotNull String text) {
        ApplicationManager.getApplication().invokeLater(() -> {
            // ponytail: 动态 notification group，免注册；如需自定义分组样式可在 plugin.xml 注册同 id
            com.intellij.notification.Notification n = new com.intellij.notification.Notification(
                    "idea-ext-hx-ai-commit",
                    "AI 提交信息",
                    text,
                    com.intellij.notification.NotificationType.WARNING);
            com.intellij.notification.Notifications.Bus.notify(n, project);
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
