package com.downloader.gui;

import com.downloader.util.AppLogger;
import com.downloader.util.FileUtils;
import com.downloader.util.HttpTrust;
import com.downloader.util.InstallerLauncher;
import com.downloader.util.UpdateChecker;
import com.downloader.util.UpdateChecker.Result;
import com.downloader.util.UpdateChecker.Asset;
import com.downloader.util.UpdateDownloader;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用更新对话框。
 * 状态机：READY → DOWNLOADING ⇄ PAUSED → COMPLETED；
 * 任意下载阶段可取消（回到 READY），出错进入 ERROR 可重试。
 * 支持应用内下载安装包（断点续传、实时速度与进度），并可随时"查看发布页"。
 * 非模态对话框，下载过程中可「后台下载」隐藏窗口继续使用主程序：
 * 下载线程独立运行，完成后自动启动安装程序。
 */
final class UpdateDialog extends JDialog {

    private enum State { READY, DOWNLOADING, PAUSED, COMPLETED, ERROR }

    private final MainWindow owner;
    private final Result result;
    private final Asset asset;
    private final File installerFile;

    private UpdateDownloader downloader;
    private State state = State.READY;
    private String errorMessage;
    /** 最近一次安装程序启动失败原因（展示在完成态状态栏，便于重试/排查） */
    private String launchError;

    /** 是否处于后台模式（窗口隐藏，下载继续） */
    private boolean background;
    /** 最近一次进度百分比，-1 表示未知（供主窗口后台指示读取） */
    private volatile int lastPercent = -1;

    private JProgressBar progressBar;
    private JLabel percentLabel;
    private JLabel sizeLabel;
    private JLabel speedLabel;
    private JLabel statusLabel;
    private JPanel buttonBar;
    private JButton primaryButton;

    // 主题换肤支持：updateComponentTreeUI 不会重建自绘 LineBorder / 显式前景色，
    // 需要在组件 updateUI 时按新主题色重建
    private JScrollPane notesScroll;
    private JPanel progressPanel;
    private JLabel subtitleLabel;
    private String lastStatusText = " ";
    private Color lastStatusColor;

    /** sha256sum 文本中的 64 位十六进制摘要 */
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    UpdateDialog(MainWindow owner, Result result, String currentVersionDisplay) {
        // 非模态：更新窗口打开期间主窗口仍可正常使用
        super(owner, "检查更新", ModalityType.MODELESS);
        this.owner = owner;
        this.result = result;
        this.asset = pickWindowsAsset(result);
        File dir = UpdateDownloader.defaultDownloadDir();
        dir.mkdirs();
        // 文件名来自 GitHub API 响应，清洗防止异常字符写入非法路径
        this.installerFile = asset != null
                ? new File(dir, FileUtils.sanitizeFileName(asset.name)) : null;

        Image icon = MainWindow.loadAppIcon();
        if (icon != null) {
            setIconImage(icon);
        }
        initUI(currentVersionDisplay);
    }

    private static Asset pickWindowsAsset(Result result) {
        // 仅接受 Windows 安装包且优先 .exe：其他平台资产（.dmg/.deb/.zip 等）
        // 下载后无法自动安装，返回 null 让对话框退化为仅提供「查看发布页」
        Asset msi = null;
        for (Asset a : result.assets) {
            String name = a.name.toLowerCase(Locale.ROOT);
            if (name.endsWith(".exe")) {
                return a;
            }
            if (msi == null && name.endsWith(".msi")) {
                msi = a;
            }
        }
        return msi;
    }

    private void initUI(String currentVersionDisplay) {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(new EmptyBorder(18, 20, 16, 20));

        // ── 标题区 ──
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel iconLabel = new JLabel(UiIcons.download(34));
        iconLabel.setForeground(MainWindow.ACCENT);
        header.add(iconLabel, BorderLayout.WEST);

        JPanel titleBox = new JPanel();
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("发现新版本 v" + result.latestVersion);
        Font baseFont = UIManager.getFont("Label.font");
        title.setFont(baseFont != null ? baseFont.deriveFont(Font.BOLD, 16f)
                : new Font(Font.SANS_SERIF, Font.BOLD, 16));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        // updateUI 时按新主题重建前景色（显式 setForeground 不会被换肤覆盖）
        subtitleLabel = new JLabel("当前版本 " + currentVersionDisplay
                + "    安装包将保存到：" + UpdateDownloader.defaultDownloadDir().getAbsolutePath()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setForeground(UIManager.getColor("Label.disabledForeground"));
            }
        };
        subtitleLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleBox.add(title);
        titleBox.add(Box.createVerticalStrut(3));
        titleBox.add(subtitleLabel);
        header.add(titleBox, BorderLayout.CENTER);
        root.add(header);
        root.add(Box.createVerticalStrut(12));

        // ── 发布说明 ──
        String notes = result.releaseNotes;
        if (notes != null && !notes.isBlank()) {
            JTextArea notesArea = new JTextArea(notes.trim());
            notesArea.setEditable(false);
            notesArea.setLineWrap(true);
            notesArea.setWrapStyleWord(true);
            notesArea.setFont(baseFont);
            notesArea.setBackground(UIManager.getColor("Panel.background"));
            notesArea.setCaretPosition(0);
            notesScroll = new JScrollPane(notesArea) {
                @Override
                public void updateUI() {
                    super.updateUI();
                    // 显式设置的 LineBorder 非 UIResource，换肤时不会被重置，
                    // 必须在 updateUI 里按新主题色重建
                    setBorder(BorderFactory.createCompoundBorder(
                            new LineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                            new EmptyBorder(8, 10, 8, 10)));
                }
            };
            notesScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            notesScroll.setBorder(BorderFactory.createCompoundBorder(
                    new LineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                    new EmptyBorder(8, 10, 8, 10)));
            notesScroll.setPreferredSize(new Dimension(560, 132));
            notesScroll.getVerticalScrollBar().setUnitIncrement(12);
            root.add(notesScroll);
            root.add(Box.createVerticalStrut(12));
        }

        // ── 下载进度区 ──
        progressPanel = new JPanel() {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                        new EmptyBorder(12, 14, 12, 14)));
            }
        };
        progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));
        progressPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressPanel.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                new EmptyBorder(12, 14, 12, 14)));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, progressBar.getPreferredSize().height));

        JPanel infoRow = new JPanel(new BorderLayout());
        infoRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        infoRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        percentLabel = new JLabel("0%");
        sizeLabel = new JLabel(asset != null && asset.size > 0
                ? "0 B / " + FileUtils.formatSize(asset.size) : "0 B");
        speedLabel = new JLabel(" ");
        speedLabel.setForeground(MainWindow.ACCENT);
        infoRow.add(percentLabel, BorderLayout.WEST);
        infoRow.add(speedLabel, BorderLayout.CENTER);
        infoRow.add(sizeLabel, BorderLayout.EAST);

        statusLabel = new JLabel(" ") {
            @Override
            public void updateUI() {
                super.updateUI();
                // 状态行文本/颜色随状态变化，换肤后按最近一次状态重建
                setText(lastStatusText);
                setToolTipText(lastStatusText);
                setForeground(lastStatusColor != null
                        ? lastStatusColor : UIManager.getColor("Label.foreground"));
            }
        };
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        progressPanel.add(progressBar);
        progressPanel.add(Box.createVerticalStrut(7));
        progressPanel.add(infoRow);
        progressPanel.add(Box.createVerticalStrut(5));
        progressPanel.add(statusLabel);
        root.add(progressPanel);
        root.add(Box.createVerticalStrut(14));

        // ── 按钮区（按状态动态重建） ──
        buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.add(buttonBar);

        setContentPane(root);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });

        // 若安装包已在目录中且可信（上次已下载完成），直接进入完成态；
        // 大小不匹配/内容无效的残留（上次下载中断）先删除，避免被误判为已完成
        if (installerFile != null && existingInstallerLooksValid()) {
            setState(State.COMPLETED);
        } else {
            if (installerFile != null && installerFile.exists()
                    && !installerFile.delete()) {
                // 删除失败仅记录：READY 态点击「立即更新」时 beginDownload 会再尝试
                AppLogger.warn("无法删除无效的安装包残留: " + installerFile.getAbsolutePath());
            }
            setState(State.READY);
        }

        setSize(620, 380);
        setMinimumSize(new Dimension(480, 380));
        setLocationRelativeTo(getOwner());
    }

    // ══════════ 状态切换与按钮 ══════════

    private void setState(State newState) {
        this.state = newState;
        buttonBar.removeAll();

        JButton releasePageBtn = flatButton("查看发布页", UiIcons.contact());
        releasePageBtn.addActionListener(e -> openReleasePage());
        buttonBar.add(releasePageBtn);

        switch (newState) {
            case READY -> {
                if (asset == null) {
                    status("未找到可自动下载的安装包，请前往发布页手动下载。", null);
                    progressBar.setValue(0);
                    percentLabel.setText("0%");
                    speedLabel.setText(" ");
                    sizeLabel.setText(" ");
                    addSecondaryButton("关闭", e -> dispose());
                } else {
                    status("准备好后点击「立即更新」开始下载。", null);
                    JButton laterBtn = addSecondaryButton("以后再说", e -> dispose());
                    laterBtn.setToolTipText("本次不更新");
                    primaryButton = primaryButton("立即更新", e -> beginDownload());
                    buttonBar.add(primaryButton);
                }
            }
            case DOWNLOADING -> {
                status("正在下载更新包…", null);
                addSecondaryButton("取消下载", e -> cancelDownload());
                JButton bgBtn = flatButton("后台下载", UiIcons.background());
                bgBtn.addActionListener(e -> hideToBackground());
                bgBtn.setToolTipText("隐藏窗口并在后台继续下载，完成后自动安装，不影响当前使用");
                buttonBar.add(bgBtn);
                primaryButton = primaryButton("暂停", UiIcons.pause(), e -> pauseDownload());
                buttonBar.add(primaryButton);
            }
            case PAUSED -> {
                status("已暂停，可继续、转后台或取消下载。", MainWindow.WARNING);
                addSecondaryButton("取消下载", e -> cancelDownload());
                JButton bgBtn = flatButton("后台下载", UiIcons.background());
                bgBtn.addActionListener(e -> hideToBackground());
                bgBtn.setToolTipText("隐藏窗口保留下载进度，可随时从主窗口「检查更新」入口恢复");
                buttonBar.add(bgBtn);
                primaryButton = primaryButton("继续", UiIcons.resume(), e -> resumeDownload());
                buttonBar.add(primaryButton);
                speedLabel.setText("已暂停");
            }
            case COMPLETED -> {
                if (launchError != null) {
                    status("自动启动安装程序失败，可点「立即安装」重试（原因：" + launchError + "）",
                            MainWindow.WARNING);
                } else {
                    status("下载完成：" + installerFile.getName(), MainWindow.SUCCESS);
                }
                speedLabel.setText(" ");
                addSecondaryButton("关闭", e -> dispose());
                primaryButton = primaryButton("立即安装", UiIcons.download(16), e -> launchInstaller());
                buttonBar.add(primaryButton);
            }
            case ERROR -> {
                status("下载失败：" + errorMessage, MainWindow.DANGER);
                speedLabel.setText(" ");
                addSecondaryButton("关闭", e -> dispose());
                primaryButton = primaryButton("重试", UiIcons.update(), e -> beginDownload());
                buttonBar.add(primaryButton);
            }
        }

        if (primaryButton != null) {
            getRootPane().setDefaultButton(primaryButton);
        }
        buttonBar.revalidate();
        buttonBar.repaint();
    }

    private JButton flatButton(String text, Icon icon) {
        JButton btn = new JButton(text, icon);
        btn.setFocusable(false);
        return btn;
    }

    private JButton addSecondaryButton(String text, java.awt.event.ActionListener action) {
        JButton btn = flatButton(text, null);
        btn.addActionListener(action);
        buttonBar.add(btn);
        return btn;
    }

    private JButton primaryButton(String text, java.awt.event.ActionListener action) {
        return primaryButton(text, null, action);
    }

    private JButton primaryButton(String text, Icon icon, java.awt.event.ActionListener action) {
        JButton btn = new JButton(text, icon);
        btn.setFocusable(false);
        btn.addActionListener(action);
        return btn;
    }

    private void status(String text, Color color) {
        // 记录最近一次状态：组件 updateUI（主题切换）时据此重建状态行
        lastStatusText = text;
        lastStatusColor = color;
        statusLabel.setText(text);
        statusLabel.setForeground(color != null ? color : UIManager.getColor("Label.foreground"));
        // 状态行空间有限（如 PKIX 长报错会被截断），悬停可查看完整内容
        statusLabel.setToolTipText(text);
    }

    // ══════ 下载控制 ══════

    private void beginDownload() {
        if (asset == null || installerFile == null) {
            return;
        }
        if (existingInstallerLooksValid()) {
            setState(State.COMPLETED);
            return;
        }
        // 已存在的安装包是无效残留（大小不匹配/内容损坏）：
        // UpdateDownloader.start() 见到最终文件存在会直接返回，
        // 不先删除会导致界面进入 DOWNLOADING 却永远没有进度
        boolean hadStaleFinal = installerFile.exists();
        if (hadStaleFinal && !installerFile.delete()) {
            status("无法覆盖已存在的安装包文件：" + installerFile.getAbsolutePath(),
                    MainWindow.DANGER);
            return;
        }
        // 仅当最终文件是无效残留时才丢弃 .part（其断点内容已不可信）；
        // 最终文件不存在时保留 .part，支持跨会话断点续传
        if (hadStaleFinal) {
            File stalePart = new File(installerFile.getAbsolutePath() + ".part");
            if (stalePart.exists() && !stalePart.delete()) {
                status("无法清理旧的下载临时文件：" + stalePart.getAbsolutePath(),
                        MainWindow.DANGER);
                return;
            }
        }
        progressBar.setIndeterminate(false);
        downloader = new UpdateDownloader(asset.downloadUrl, installerFile, new UpdateDownloader.Listener() {
            @Override
            public void onStart(long totalBytes) {
                EventQueue.invokeLater(() -> {
                    lastPercent = totalBytes > 0 ? 0 : -1;
                    if (totalBytes <= 0) {
                        progressBar.setIndeterminate(true);
                        percentLabel.setText("--");
                        sizeLabel.setText("0 B");
                    } else {
                        progressBar.setIndeterminate(false);
                        progressBar.setValue(0);
                        percentLabel.setText("0%");
                        sizeLabel.setText("0 B / " + FileUtils.formatSize(totalBytes));
                    }
                });
            }

            @Override
            public void onProgress(long downloaded, long totalBytes, long bytesPerSec) {
                EventQueue.invokeLater(() -> updateProgress(downloaded, totalBytes, bytesPerSec, false));
            }

            @Override
            public void onPaused() {
                EventQueue.invokeLater(() -> {
                    if (state == State.DOWNLOADING) {
                        setState(State.PAUSED);
                    }
                });
            }

            @Override
            public void onComplete(File file) {
                EventQueue.invokeLater(() ->
                        updateProgress(file.length(),
                                asset.size > 0 ? asset.size : file.length(), 0, true));
                // SHA-256 校验含网络拉取与流式摘要，放虚拟线程执行，完成后再回 EDT 收尾
                Thread.ofVirtual().start(() -> {
                    String error = verifySha256(file);
                    EventQueue.invokeLater(() -> {
                        if (error != null) {
                            errorMessage = error;
                            setState(State.ERROR);
                            if (background) {
                                Toast.warning(owner, "更新包校验失败：" + error
                                        + "，点击「检查更新」查看并重试");
                            }
                            return;
                        }
                        setState(State.COMPLETED);
                        // 后台模式下下载完成：自动启动安装程序，不打断用户操作
                        if (background) {
                            autoInstallInBackground();
                        }
                    });
                });
            }

            @Override
            public void onError(String message) {
                EventQueue.invokeLater(() -> {
                    errorMessage = message;
                    setState(State.ERROR);
                    if (background) {
                        // 后台下载失败：Toast 提醒，会话保留，点击主窗口按钮可回窗口重试
                        Toast.warning(owner, "更新包后台下载失败：" + message
                                + "，点击「检查更新」查看并重试");
                    }
                });
            }

            @Override
            public void onCancelled() {
                EventQueue.invokeLater(() -> {
                    lastPercent = -1;
                    progressBar.setValue(0);
                    percentLabel.setText("0%");
                    sizeLabel.setText(asset.size > 0 ? "0 B / " + FileUtils.formatSize(asset.size) : "0 B");
                    speedLabel.setText(" ");
                    setState(State.READY);
                });
            }
        });
        setState(State.DOWNLOADING);
        downloader.start();
    }

    private void updateProgress(long downloaded, long totalBytes, long bytesPerSec, boolean forceComplete) {
        if (totalBytes > 0) {
            progressBar.setIndeterminate(false);
            int percent = forceComplete ? 100 : (int) Math.min(100, downloaded * 100 / totalBytes);
            progressBar.setValue(percent);
            lastPercent = percent;
            percentLabel.setText(percent + "%");
            sizeLabel.setText(FileUtils.formatSize(downloaded) + " / " + FileUtils.formatSize(totalBytes));
        } else {
            sizeLabel.setText(FileUtils.formatSize(downloaded));
        }
        speedLabel.setText(bytesPerSec > 0 ? FileUtils.formatSize(bytesPerSec) + "/s" : " ");
    }

    private void pauseDownload() {
        if (downloader != null) {
            downloader.pause();
            setState(State.PAUSED);
        }
    }

    private void resumeDownload() {
        if (downloader != null) {
            setState(State.DOWNLOADING);
            downloader.resume();
        }
    }

    private void cancelDownload() {
        if (downloader != null) {
            downloader.cancel();
        }
        // onCancelled 回调会回到 READY；网络阻塞时也先给即时反馈
        setState(State.READY);
        status("正在取消下载…", null);
    }

    // ══════ 发布页 / 安装 / 关闭 ══════

    private void openReleasePage() {
        // 直接进入本次更新对应的具体版本页
        String pageUrl = UpdateChecker.releasePageUrl(result.latestVersion);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(pageUrl));
            } else {
                JOptionPane.showMessageDialog(this,
                        "当前环境不支持打开浏览器，请手动访问：\n" + pageUrl,
                        "无法打开浏览器", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "无法打开发布页，请手动访问：\n" + pageUrl,
                    "打开失败", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void launchInstaller() {
        if (installerFile == null || !installerFile.exists()) {
            errorMessage = "安装包文件不存在";
            setState(State.ERROR);
            return;
        }
        // 有效性校验：非 Windows 可执行文件（如下载到错误页面）时删除并允许重新下载
        String invalid = checkInstallerValid();
        if (invalid != null) {
            if (!installerFile.delete()) {
                AppLogger.warn("无法删除无效安装包: " + installerFile.getAbsolutePath());
            }
            errorMessage = invalid;
            setState(State.ERROR);
            return;
        }
        String error = InstallerLauncher.launch(installerFile);
        if (error == null) {
            JOptionPane.showMessageDialog(this,
                    "安装程序已启动，请按向导完成更新。\n如安装失败，请先退出本程序后重试。",
                    "开始安装", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } else {
            launchError = error;
            JOptionPane.showMessageDialog(this,
                    "无法自动启动安装程序，请手动双击运行：\n" + installerFile.getAbsolutePath()
                            + "\n\n失败原因：" + error,
                    "启动失败", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * 目录中的安装包残留是否可信：
     * 已知大小（asset.size&gt;0）时比对实际长度；大小未知时对 .exe 做 MZ 头校验。
     * 无效残留（上次下载中断/损坏）必须删除，否则会被误判为"已下载完成"。
     */
    private boolean existingInstallerLooksValid() {
        if (!installerFile.exists() || installerFile.length() <= 0) {
            return false;
        }
        if (asset.size > 0) {
            return installerFile.length() == asset.size;
        }
        return checkInstallerValid() == null;
    }

    /** 校验 .exe 安装包的 PE 头（MZ）；有效返回 null，异常返回原因。 */
    private String checkInstallerValid() {
        if (!installerFile.getName().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            return null;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(installerFile, "r")) {
            int b0 = raf.read();
            int b1 = raf.read();
            if (b0 != 'M' || b1 != 'Z') {
                return "安装包不是有效的 Windows 可执行文件（可能下载到的是错误页面），已删除，请重试";
            }
        } catch (java.io.IOException e) {
            return "无法读取安装包：" + e.getMessage();
        }
        return null;
    }

    /**
     * 安装包 SHA-256 完整性校验（后台线程调用，完成后回 EDT）：
     * 发布资产附带同名 .sha256 文件时拉取期望摘要并与本地文件流式摘要比对；
     * 不匹配删除安装包并返回错误原因（回到可重新下载状态）。
     * 无 .sha256 资产返回 null（沿用大小 + MZ 头校验）；
     * 校验值拉取失败仅提示降级、不阻断安装（避免发布侧失误导致无法更新）。
     */
    private String verifySha256(File file) {
        Asset shaAsset = findSha256Asset();
        if (shaAsset == null) {
            return null;
        }
        edtStatus("正在校验安装包完整性（SHA-256）…", null);
        String expected;
        try {
            expected = fetchExpectedSha256(shaAsset);
        } catch (Exception e) {
            edtStatus("SHA-256 校验值获取失败，已跳过校验：" + e.getMessage(), MainWindow.WARNING);
            return null;
        }
        if (expected == null) {
            edtStatus("校验值文件格式无法识别，已跳过 SHA-256 校验", MainWindow.WARNING);
            return null;
        }
        String actual;
        try {
            actual = sha256Hex(file);
        } catch (IOException e) {
            return "无法读取安装包计算 SHA-256：" + e.getMessage();
        }
        if (!actual.equalsIgnoreCase(expected)) {
            if (file.exists() && !file.delete()) {
                return "安装包 SHA-256 校验不匹配，且删除损坏文件失败，请手动删除："
                        + file.getAbsolutePath();
            }
            return "安装包 SHA-256 校验不匹配（下载不完整或被篡改），已删除，请重新下载";
        }
        return null;
    }

    /** 在发布资产中查找安装包同名的 .sha256 校验文件。 */
    private Asset findSha256Asset() {
        if (installerFile == null || result.assets == null) {
            return null;
        }
        String wanted = installerFile.getName().toLowerCase(Locale.ROOT) + ".sha256";
        for (Asset a : result.assets) {
            if (a.name != null && a.name.toLowerCase(Locale.ROOT).equals(wanted)) {
                return a;
            }
        }
        return null;
    }

    /** 拉取 .sha256 文本并提取首个 64 位十六进制摘要（兼容 sha256sum 的 "hex  filename" 格式）。 */
    private static String fetchExpectedSha256(Asset shaAsset) throws Exception {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        // 与更新链路一致：信任所有证书（精简 JRE cacerts 不全 / 本地加速代理自签证书）
        if (HttpTrust.SSL_CONTEXT != null) {
            builder.sslContext(HttpTrust.SSL_CONTEXT);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(shaAsset.downloadUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "text/plain,*/*")
                .GET()
                .build();
        HttpResponse<String> resp = builder.build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode());
        }
        Matcher m = SHA256_HEX.matcher(resp.body());
        return m.find() ? m.group() : null;
    }

    /** 流式计算文件 SHA-256（64KB 缓冲，避免大文件占内存）。 */
    private static String sha256Hex(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("JVM 不支持 SHA-256", e);
        }
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 非 EDT 安全的 status() 包装：校验线程经此回调状态行 */
    private void edtStatus(String text, Color color) {
        EventQueue.invokeLater(() -> status(text, color));
    }

    // ══════ 后台模式 ══════

    /** 隐藏窗口转后台：下载线程独立运行，不影响主程序使用，完成后自动安装。 */
    private void hideToBackground() {
        if (state != State.DOWNLOADING && state != State.PAUSED) {
            dispose();
            return;
        }
        background = true;
        setVisible(false);
        owner.onUpdateDialogBackgrounded(this);
    }

    /** 从后台恢复显示（由主窗口「检查更新」入口调用）。 */
    void bringToFront() {
        if (!isDisplayable()) {
            return;
        }
        background = false;
        // 对话框随属主窗口最小化；恢复属主后再显示，避免挂后台时弹出不可点击的幽灵窗口
        if (owner.getExtendedState() == JFrame.ICONIFIED) {
            owner.setExtendedState(JFrame.NORMAL);
        }
        owner.setVisible(true);
        owner.toFront();
        setVisible(true);
        toFront();
        requestFocus();
        owner.onUpdateDialogForeground(this);
    }

    /** 后台下载完成后自动启动安装程序；启动失败则恢复窗口交由用户手动安装。 */
    private void autoInstallInBackground() {
        if (installerFile == null || !installerFile.exists()) {
            return;
        }
        String invalid = checkInstallerValid();
        if (invalid != null) {
            if (!installerFile.delete()) {
                AppLogger.warn("无法删除无效安装包: " + installerFile.getAbsolutePath());
            }
            errorMessage = invalid;
            setState(State.ERROR);
            Toast.warning(owner, "更新安装包内容异常，已打开更新窗口，请重新下载");
            bringToFront();
            return;
        }
        String error = InstallerLauncher.launch(installerFile);
        if (error == null) {
            Toast.success(owner, "更新安装包已下载完成，安装程序已启动，请按向导完成更新");
            dispose();
        } else {
            launchError = error;
            status("无法自动启动安装程序，可点「立即安装」重试或手动运行：" + installerFile.getAbsolutePath()
                    + "（原因：" + error + "）", MainWindow.WARNING);
            Toast.warning(owner, "无法自动启动安装程序，已打开更新窗口");
            bringToFront();
        }
    }

    // ══════ 供主窗口读取的后台状态 ══════

    boolean isBackground() {
        return background;
    }

    boolean isDownloadActive() {
        return state == State.DOWNLOADING || state == State.PAUSED;
    }

    boolean isDownloading() {
        return state == State.DOWNLOADING;
    }

    boolean isPaused() {
        return state == State.PAUSED;
    }

    boolean isError() {
        return state == State.ERROR;
    }

    int getPercent() {
        return lastPercent;
    }

    @Override
    public void dispose() {
        super.dispose();
        owner.onUpdateDialogClosed(this);
    }

    /**
     * 关闭选择：下载进行中/暂停时提供三种处置方式——
     * 后台继续下载（推荐）、取消下载并关闭、留在当前窗口。
     */
    private void requestClose() {
        if (state == State.DOWNLOADING || state == State.PAUSED) {
            Object[] options = {"后台下载", "取消下载并关闭", "继续等待"};
            int choice = JOptionPane.showOptionDialog(this,
                    "更新包尚未下载完成。\n可以最小化到后台继续下载，不影响正常使用，下载完成后将自动安装。",
                    "更新下载中",
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            if (choice == 0) {
                hideToBackground();
                return;
            }
            if (choice != 1) {
                return;
            }
            if (downloader != null) {
                downloader.cancel();
            }
        }
        dispose();
    }
}
