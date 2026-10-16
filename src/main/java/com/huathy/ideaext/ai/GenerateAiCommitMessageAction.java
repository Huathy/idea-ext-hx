package com.huathy.ideaext.ai;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.ui.CommitMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 在提交对话框中根据暂存区 diff 生成约定式提交信息。
 *
 * <p>注册位置：commit message 输入框工具栏（Vcs.MessageActionGroup）。</p>
 */
public final class GenerateAiCommitMessageAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(GenerateAiCommitMessageAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.getBasePath() == null) {
            Messages.showWarningDialog("未打开项目", "AI 提交信息");
            return;
        }
        CommitMessage editor = null;
        Object data = e.getDataContext().getData(CommitMessage.DATA_KEY.toString());
        if (data instanceof CommitMessage) {
            editor = (CommitMessage) data;
        }
        AiCommitSettings settings = AiCommitSettings.getInstance();
        AiCommitSettings.State cfg = settings == null ? new AiCommitSettings.State() : settings.getState();
        if (!cfg.enabled) {
            Messages.showWarningDialog("AI 提交信息功能未启用，请在 Settings → Tools → AI Commit Message 中开启。", "AI 提交信息");
            return;
        }
        if (cfg.baseUrl == null || cfg.baseUrl.trim().isEmpty()) {
            notifyUser(project, "未配置 Base URL，请在 Settings → Tools → AI Commit Message 填写并点 Apply 保存。");
            return;
        }
        if (cfg.apiKey == null || cfg.apiKey.trim().isEmpty()) {
            notifyUser(project, "未配置 API Key，请在 Settings → Tools → AI Commit Message 填写并点 Apply 保存。");
            return;
        }
        final CommitMessage editorRef = editor;

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "AI 生成提交信息", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    String diff = collectStagedDiff(project);
                    if (diff.isEmpty()) {
                        diff = collectUnstagedDiff(project);
                    }
                    if (diff.isEmpty()) {
                        notifyUser(project, "未检测到代码变更（暂存区/工作区均为空）");
                        return;
                    }
                    if (diff.length() > cfg.maxDiffBytes) {
                        diff = diff.substring(0, cfg.maxDiffBytes)
                                + "\n...（diff 已截断，共 " + diff.length() + " 字节）";
                    }
                    String message = AiCommitClient.generate(cfg, diff).trim();
                    if (message.isEmpty()) {
                        notifyUser(project, "AI 返回空结果");
                        return;
                    }
                    final String finalMessage = message;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (editorRef != null) {
                            editorRef.setText(finalMessage);
                            notifyUser(project, "已生成并填入提交信息");
                        } else {
                            Messages.showInfoMessage(finalMessage, "AI Commit Message");
                        }
                    });
                } catch (java.io.IOException io) {
                    LOG.warn("AI commit message failed", io);
                    notifyUser(project, "无法连接 AI 服务，请检查 Base URL / 网络 / 代理是否正确：" + io.getMessage());
                } catch (IllegalStateException he) {
                    LOG.warn("AI commit message failed", he);
                    notifyUser(project, "AI 服务返回错误：" + he.getMessage());
                } catch (Exception ex) {
                    LOG.warn("AI commit message failed", ex);
                    notifyUser(project, "生成失败：" + ex.getMessage());
                }
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null && project.getBasePath() != null);
    }

    @Nullable
    private static String collectStagedDiff(@NotNull Project project) {
        return runGit(project, "diff", "--cached");
    }

    @Nullable
    private static String collectUnstagedDiff(@NotNull Project project) {
        return runGit(project, "diff");
    }

    @Nullable
    private static String runGit(@NotNull Project project, @NotNull String... args) {
        try {
            Path workDir = Path.of(project.getBasePath());
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            Process p = new ProcessBuilder(cmd).directory(workDir.toFile())
                    .redirectErrorStream(false).start();
            String out = readAll(p.getInputStream());
            int code = p.waitFor();
            if (code != 0) {
                String err = readAll(p.getErrorStream());
                LOG.warn("git " + String.join(" ", args) + " exit=" + code + " err=" + truncate(err, 200));
                return "";
            }
            return out;
        } catch (Exception ex) {
            LOG.warn("git exec failed", ex);
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

    private static void notifyUser(@Nullable Project project, @NotNull String text) {
        ApplicationManager.getApplication().invokeLater(() ->
                Messages.showInfoMessage(text, "AI 提交信息"));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
