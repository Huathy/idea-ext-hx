package com.huathy.ideaext.vcs;

import com.intellij.icons.AllIcons;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsFileUtil;
import com.intellij.vcsUtil.VcsUtil;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 从版本控制中移除所选文件 / 文件夹（保留磁盘文件，仅取消跟踪）。
 *
 * <p>IDEA 内置只有 "Add to VCS"（{@code ChangesView.AddUnversioned}），没有反向操作；
 * 本 Action 补上 "Remove from VCS"，并与之并排显示：项目视图/编辑器右键的 Git 子菜单、
 * 提交面板与变更列表的右键菜单。</p>
 *
 * <p>实现：按所选路径所属 Git 仓库执行 {@code git rm --cached -f [-r]}，然后标记 VCS 状态脏，
 * 让 IDE 立即把文件重新归类为「未纳入版本控制」。仅支持 Git 仓库（{@code Git4Idea} 插件），
 * 其他版本控制会提示不支持。</p>
 */
public final class RemoveFromVcsAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(RemoveFromVcsAction.class);

    private static final String TITLE = "Remove from VCS";
    private static final String NOTIFICATION_GROUP = "idea-ext-hx-vcs";

    /** 确认框里最多列出的条目数。 */
    private static final int PREVIEW_LIMIT = 15;
    /** 目录向下探测是否含已跟踪文件的层数上限，避免超大目录卡住菜单。 */
    private static final int MAX_DIRECTORY_DEPTH = 3;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        presentation.setVisible(true);
        presentation.setEnabled(false);

        Project project = e.getProject();
        if (project == null) {
            presentation.setVisible(false);
            return;
        }

        List<VirtualFile> versioned = collectVersioned(project, collectCandidates(e));
        if (versioned.isEmpty()) {
            presentation.setVisible(false);
            return;
        }
        presentation.setEnabled(true);
        presentation.setDescription("从版本控制移除 " + versioned.size() + " 项（保留磁盘文件）");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        List<VirtualFile> versioned = collectVersioned(project, collectCandidates(e));
        if (versioned.isEmpty()) {
            notifyUser(project, "所选条目未纳入版本控制，无需移除。", NotificationType.WARNING);
            return;
        }

        List<GitRepository> repositories = GitRepositoryManager.getInstance(project).getRepositories();
        Map<GitRepository, List<VirtualFile>> byRepository = new LinkedHashMap<>();
        for (VirtualFile file : versioned) {
            GitRepository repository = findRepository(repositories, file);
            if (repository == null) {
                continue;
            }
            byRepository.computeIfAbsent(repository, key -> new ArrayList<>()).add(file);
        }

        if (byRepository.isEmpty()) {
            notifyUser(project, "所选条目不在 Git 仓库中，当前版本仅支持 Git。", NotificationType.WARNING);
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("将从版本控制中移除以下条目（文件保留在磁盘上）：\n");
        appendPreview(message, versioned);
        message.append("\n注意：已暂存（staged）但尚未提交的内容会随索引条目一起丢弃。\n");
        message.append("继续？");

        int answer = Messages.showYesNoDialog(project, message.toString(), TITLE, "Remove", "Cancel",
                AllIcons.General.Remove);
        if (answer != Messages.YES) {
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, TITLE, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);

                int removed = 0;
                List<String> failures = new ArrayList<>();
                for (Map.Entry<GitRepository, List<VirtualFile>> entry : byRepository.entrySet()) {
                    indicator.setText("git rm --cached @ " + entry.getKey().getRoot().getPath());
                    GitCommandResult result = removeFromIndex(project, entry.getKey(), entry.getValue(), failures);
                    if (result != null && result.success()) {
                        removed += entry.getValue().size();
                    } else if (result != null) {
                        failures.add(entry.getKey().getRoot().getPath() + ": "
                                + result.getErrorOutputAsJoinedString());
                    }
                }

                final int removedCount = removed;
                final List<String> failureList = failures;
                ApplicationManager.getApplication().invokeLater(() -> {
                    for (VirtualFile file : versioned) {
                        markDirty(project, file);
                    }
                    if (failureList.isEmpty()) {
                        notifyUser(project, "已从版本控制移除 " + removedCount + " 项，文件仍保留在磁盘上。",
                                NotificationType.INFORMATION);
                    } else {
                        notifyUser(project, "部分条目移除失败：\n" + String.join("\n", failureList), NotificationType.ERROR);
                    }
                });
            }
        });
    }

    /**
     * 对单个仓库执行 {@code git rm --cached -f [-r]}，即取消跟踪但保留本地文件。
     *
     * <p>必须带 {@code -f}：当条目的暂存内容既不等于工作区文件也不等于 HEAD 时（例如新
     * 增后又有改动、或暂存后又改了文件），git 会以 "has staged content different from both
     * the file and the HEAD" 拒绝执行；{@code --cached} 只动索引、不动工作区，故强制安全。</p>
     *
     * <p>返回 null 表示无需执行（无有效路径）或执行异常（失败信息已写入 {@code failures}）。</p>
     */
    @Nullable
    private static GitCommandResult removeFromIndex(@NotNull Project project, @NotNull GitRepository repository,
                                                    @NotNull List<VirtualFile> files,
                                                    @NotNull List<String> failures) {
        VirtualFile root = repository.getRoot();
        if (root == null) {
            return null;
        }

        List<String> relativePaths = new ArrayList<>(files.size());
        boolean recursive = false;
        for (VirtualFile file : files) {
            if (!file.isValid()) {
                continue;
            }
            String relativePath = VcsFileUtil.relativePath(new File(root.getPath()), VcsUtil.getFilePath(file));
            if (relativePath == null || relativePath.isEmpty()) {
                // 兜底：仓库根目录本身（relativePath 通常已返回 "."）
                relativePath = ".";
                recursive = true;
            }
            relativePaths.add(relativePath);
            recursive |= file.isDirectory();
        }
        if (relativePaths.isEmpty()) {
            return null;
        }

        GitLineHandler handler = new GitLineHandler(project, root, GitCommand.RM);
        handler.setSilent(true);
        handler.addParameters("--cached");
        // -f 绕过 "has staged content different from both the file and the HEAD" 保护
        handler.addParameters("-f");
        if (recursive) {
            handler.addParameters("-r");
        }
        handler.endOptions();
        handler.addParameters(relativePaths.toArray(new String[0]));

        try {
            return Git.getInstance().runCommand(handler);
        } catch (RuntimeException ex) {
            LOG.warn("git rm --cached failed for " + root.getPath(), ex);
            failures.add(root.getPath() + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * 过滤出「已纳入版本控制且位于 Git 仓库内」的条目；目录按其子树中是否存在已跟踪文件判断。
     *
     * <p>必须先确认位于 Git 仓库内：无 VCS 映射的文件 {@code getStatus} 会返回 NOT_CHANGED，
     * 仅凭状态无法区分「已跟踪」与「根本不在版本控制内」。</p>
     */
    @NotNull
    private static List<VirtualFile> collectVersioned(@NotNull Project project, @NotNull List<VirtualFile> files) {
        List<GitRepository> repositories = GitRepositoryManager.getInstance(project).getRepositories();
        List<VirtualFile> result = new ArrayList<>(files.size());
        for (VirtualFile file : files) {
            if (isVersioned(project, repositories, file)) {
                result.add(file);
            }
        }
        return result;
    }

    private static boolean isVersioned(@NotNull Project project, @NotNull List<GitRepository> repositories,
                                       @NotNull VirtualFile file) {
        if (!file.isValid()) {
            return false;
        }
        if (findRepository(repositories, file) == null) {
            return false;
        }
        return file.isDirectory() ? isVersionedDirectory(project, file, 0) : isTracked(project, file);
    }

    /**
     * 文件是否已被版本控制跟踪：被忽略、未跟踪、无 VCS 映射时返回 false。
     */
    private static boolean isTracked(@NotNull Project project, @NotNull VirtualFile file) {
        ChangeListManager changeListManager = ChangeListManager.getInstance(project);
        if (changeListManager.isIgnoredFile(file)) {
            return false;
        }
        if (changeListManager.isUnversioned(file)) {
            return false;
        }
        return changeListManager.getStatus(file) != FileStatus.UNKNOWN;
    }

    /**
     * 目录是否含有已跟踪内容（Git 不跟踪空目录，故向下探测有限层级）。
     */
    private static boolean isVersionedDirectory(@NotNull Project project, @NotNull VirtualFile directory, int depth) {
        if (depth > MAX_DIRECTORY_DEPTH) {
            return false;
        }
        ChangeListManager changeListManager = ChangeListManager.getInstance(project);
        if (changeListManager.isIgnoredFile(directory)) {
            return false;
        }
        for (VirtualFile child : directory.getChildren()) {
            if (child.isDirectory()) {
                if (".git".equals(child.getName())) {
                    continue;
                }
                if (isVersionedDirectory(project, child, depth + 1)) {
                    return true;
                }
            } else if (isTracked(project, child)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 找出包含该文件的最内层 Git 仓库。
     */
    @Nullable
    private static GitRepository findRepository(@NotNull List<GitRepository> repositories, @NotNull VirtualFile file) {
        String path = file.getPath();
        GitRepository best = null;
        for (GitRepository repository : repositories) {
            VirtualFile root = repository.getRoot();
            if (root == null) {
                continue;
            }
            String rootPath = root.getPath();
            if (path.equals(rootPath) || path.startsWith(rootPath + "/")) {
                if (best == null || best.getRoot().getPath().length() < rootPath.length()) {
                    best = repository;
                }
            }
        }
        return best;
    }

    private static void markDirty(@NotNull Project project, @NotNull VirtualFile file) {
        VcsDirtyScopeManager dirtyScopeManager = VcsDirtyScopeManager.getInstance(project);
        if (file.isDirectory()) {
            dirtyScopeManager.dirDirtyRecursively(file);
        } else {
            dirtyScopeManager.fileDirty(file);
        }
    }

    private static void appendPreview(@NotNull StringBuilder builder, @NotNull List<VirtualFile> files) {
        int limit = Math.min(files.size(), PREVIEW_LIMIT);
        for (int i = 0; i < limit; i++) {
            builder.append("  • ").append(files.get(i).getPresentableUrl()).append('\n');
        }
        if (files.size() > limit) {
            builder.append("  … 共 ").append(files.size()).append(" 项\n");
        }
    }

    private static void notifyUser(@NotNull Project project, @NotNull String content, @NotNull NotificationType type) {
        Notification notification = new Notification(NOTIFICATION_GROUP, TITLE, content, type);
        Notifications.Bus.notify(notification, project);
    }

    /**
     * 收集当前右键上下文选中的文件，兼容三类入口：
     * <ul>
     *     <li>项目视图 / 文件列表：{@link PlatformDataKeys#VIRTUAL_FILE_ARRAY}</li>
     *     <li>提交面板 / 变更列表：{@link VcsDataKeys#VIRTUAL_FILES}、{@link VcsDataKeys#CHANGES}</li>
     * </ul>
     * 变更中已删除的文件没有工作区文件（{@code getVirtualFile()} 为 null），会被忽略。
     */
    private static List<VirtualFile> collectCandidates(@NotNull AnActionEvent e) {
        LinkedHashSet<VirtualFile> result = new LinkedHashSet<>();

        VirtualFile[] files = e.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY);
        if (files != null) {
            for (VirtualFile file : files) {
                addIfValid(result, file);
            }
        }

        Iterable<VirtualFile> vcsFiles = e.getData(VcsDataKeys.VIRTUAL_FILES);
        if (vcsFiles != null) {
            for (VirtualFile file : vcsFiles) {
                addIfValid(result, file);
            }
        }

        addFromChanges(result, e.getData(VcsDataKeys.CHANGES));
        addFromChanges(result, e.getData(VcsDataKeys.SELECTED_CHANGES));
        return new ArrayList<>(result);
    }

    private static void addFromChanges(@NotNull LinkedHashSet<VirtualFile> result, @Nullable Change[] changes) {
        if (changes == null) {
            return;
        }
        for (Change change : changes) {
            if (change != null) {
                addIfValid(result, change.getVirtualFile());
            }
        }
    }

    private static void addIfValid(@NotNull LinkedHashSet<VirtualFile> result, @Nullable VirtualFile file) {
        if (file != null && file.isValid()) {
            result.add(file);
        }
    }
}