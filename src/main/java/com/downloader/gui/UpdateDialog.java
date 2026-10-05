package com.downloader.gui;

import com.downloader.util.FileUtils;
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
import java.net.URI;
import java.util.Locale;

/**
 * 应用更新对话框。
 * 状态机：READY → DOWNLOADING ⇄ PAUSED → COMPLETED；
 * 任意下载阶段可取消（回到 READY），出错进入 ERROR 可重试。
 * 支持应用内下载安装包（断点续传、实时速度与进度），并可随时"查看发布页"。
 */
final class UpdateDialog extends JDialog {

    private enum State { READY, DOWNLOADING, PAUSED, COMPLETED, ERROR }

    private final Result result;
    private final Asset asset;
    private final File installerFile;

    private UpdateDownloader downloader;
    private State state = State.READY;
    private String errorMessage;

    private JProgressBar progressBar;
    private JLabel percentLabel;
    private JLabel sizeLabel;
    private JLabel speedLabel;
    private JLabel statusLabel;
    private JPanel buttonBar;
    private JButton primaryButton;

    UpdateDialog(Window owner, Result result, String currentVersionDisplay) {
        super(owner, "检查更新", ModalityType.APPLICATION_MODAL);
        this.result = result;
        this.asset = pickWindowsAsset(result);
        File dir = UpdateDownloader.defaultDownloadDir();
        dir.mkdirs();
        this.installerFile = asset != null ? new File(dir, asset.name) : null;

        Image icon = MainWindow.loadAppIcon();
        if (icon != null) {
            setIconImage(icon);
        }
        initUI(currentVersionDisplay);
    }

    private static Asset pickWindowsAsset(Result result) {
        Asset fallback = null;
        for (Asset a : result.assets) {
            String name = a.name.toLowerCase(Locale.ROOT);
            if (name.endsWith(".exe")) {
                return a;
            }
            if (fallback == null) {
                fallback = a;
            }
        }
        return fallback;
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
        JLabel subtitle = new JLabel("当前版本 " + currentVersionDisplay
                + "    安装包将保存到：" + UpdateDownloader.defaultDownloadDir().getAbsolutePath());
        subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleBox.add(title);
        titleBox.add(Box.createVerticalStrut(3));
        titleBox.add(subtitle);
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
            JScrollPane notesScroll = new JScrollPane(notesArea);
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
        JPanel progressPanel = new JPanel();
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

        statusLabel = new JLabel(" ");
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

        // 若安装包已在目录中（上次已下载完成），直接进入完成态
        if (installerFile != null && installerFile.exists()
                && (asset.size <= 0 || installerFile.length() == asset.size)) {
            setState(State.COMPLETED);
        } else {
            setState(State.READY);
        }

        setSize(620, 460);
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
                primaryButton = primaryButton("暂停", UiIcons.pause(), e -> pauseDownload());
                buttonBar.add(primaryButton);
            }
            case PAUSED -> {
                status("已暂停，可继续或取消下载。", MainWindow.WARNING);
                addSecondaryButton("取消下载", e -> cancelDownload());
                primaryButton = primaryButton("继续", UiIcons.resume(), e -> resumeDownload());
                buttonBar.add(primaryButton);
                speedLabel.setText("已暂停");
            }
            case COMPLETED -> {
                status("下载完成：" + installerFile.getName(), MainWindow.SUCCESS);
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
        statusLabel.setText(text);
        statusLabel.setForeground(color != null ? color : UIManager.getColor("Label.foreground"));
    }

    // ══════ 下载控制 ══════

    private void beginDownload() {
        if (asset == null || installerFile == null) {
            return;
        }
        if (installerFile.exists() && (asset.size <= 0 || installerFile.length() == asset.size)) {
            setState(State.COMPLETED);
            return;
        }
        progressBar.setIndeterminate(false);
        downloader = new UpdateDownloader(asset.downloadUrl, installerFile, new UpdateDownloader.Listener() {
            @Override
            public void onStart(long totalBytes) {
                EventQueue.invokeLater(() -> {
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
                EventQueue.invokeLater(() -> {
                    updateProgress(file.length(), asset.size > 0 ? asset.size : file.length(), 0, true);
                    setState(State.COMPLETED);
                });
            }

            @Override
            public void onError(String message) {
                EventQueue.invokeLater(() -> {
                    errorMessage = message;
                    setState(State.ERROR);
                });
            }

            @Override
            public void onCancelled() {
                EventQueue.invokeLater(() -> {
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
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(result.releaseUrl));
            } else {
                JOptionPane.showMessageDialog(this,
                        "当前环境不支持打开浏览器，请手动访问：\n" + result.releaseUrl,
                        "无法打开浏览器", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "无法打开发布页，请手动访问：\n" + result.releaseUrl,
                    "打开失败", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void launchInstaller() {
        if (installerFile == null || !installerFile.exists()) {
            errorMessage = "安装包文件不存在";
            setState(State.ERROR);
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(installerFile);
                JOptionPane.showMessageDialog(this,
                        "安装程序已启动，请按向导完成更新。\n如安装失败，请先退出本程序后重试。",
                        "开始安装", JOptionPane.INFORMATION_MESSAGE);
                dispose();
            } else {
                throw new UnsupportedOperationException("当前平台不支持桌面打开操作");
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "无法自动启动安装程序，请手动双击运行：\n" + installerFile.getAbsolutePath(),
                    "启动失败", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 关闭确认：下载进行中/暂停时先取消下载再关闭。 */
    private void requestClose() {
        if (state == State.DOWNLOADING || state == State.PAUSED) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "更新包尚未下载完成，关闭后将取消本次下载。确定关闭吗？",
                    "取消下载", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            if (downloader != null) {
                downloader.cancel();
            }
        }
        dispose();
    }
}
