package com.downloader.gui;

import com.downloader.cloud.CloudContext;
import com.downloader.cloud.CloudDrive;
import com.downloader.cloud.CloudFile;
import com.downloader.cloud.CloudProvider;
import com.downloader.cloud.CloudRegistry;
import com.downloader.cloud.CloudShare;
import com.downloader.cloud.CloudTaskCodec;
import com.downloader.config.ConfigManager;
import com.downloader.manager.DownloadManager;
import com.downloader.util.FileUtils;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 网盘分享解析下载窗口。
 * <p>粘贴分享链接（可带提取码文案）→ 选择平台自动解析 → 浏览目录/勾选文件 →
 * 以 cloud:// 任务描述加入主下载列表，实际传输复用主窗口的 Range 分片引擎。
 */
public class CloudParseDialog extends JDialog {

    private static final int MAX_RECURSIVE_FILES = 2000;

    private final DownloadManager downloadManager;

    private final JTextField linkField = new JTextField();
    private final JTextField pwdField = new JTextField(6);
    private final JLabel providerLabel = new JLabel("待识别");
    private final JTextField pathField = new JTextField();
    private final JLabel pathLabel = new JLabel("/");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel hintLabel = new JLabel(" ");
    private final JTextArea cookieArea = new JTextArea(2, 40);
    private final JButton saveCredBtn = new JButton("保存凭据");
    private final JButton parseBtn = new JButton("解析");
    private final JButton upBtn = new JButton("上一级");
    private final JButton selectAllBtn = new JButton("全选");
    private final JButton clearAllBtn = new JButton("清空");
    private final JButton downloadSelectedBtn = new JButton("下载选中");
    private final JButton downloadAllBtn = new JButton("下载全部");
    private final JButton closeBtn = new JButton("关闭");

    private final DefaultTableModel fileModel;
    private final JTable fileTable;

    private final List<CloudFile> currentFiles = new ArrayList<>();
    /** 目录栈；null 元素表示分享根目录 */
    private final List<CloudFile> dirStack = new ArrayList<>();
    private CloudProvider provider;
    private CloudShare share;
    private CloudFile currentDir;
    /** 上次自动带入输入框的提取码，用于切换链接时清掉过期值 */
    private String autoPasscode = "";
    private boolean busy;

    public CloudParseDialog(Frame owner, DownloadManager downloadManager, String initialText) {
        // 与主窗口的模态框处理保持一致：不绑定 owner，避免主窗口最小化/恢复时
        // Windows 下出现只有标题栏的空壳模态框
        super((Frame) null, "网盘解析下载", true);
        this.downloadManager = downloadManager;

        fileModel = new DefaultTableModel(new Object[]{"", "名称", "大小", "类型"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                // 勾选完全由鼠标点击切换，避免编辑器抢占点击事件
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }
        };
        fileTable = new JTable(fileModel);
        fileTable.setRowHeight(26);
        fileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileTable.getColumnModel().getColumn(0).setMaxWidth(38);
        fileTable.getColumnModel().getColumn(2).setMaxWidth(110);
        fileTable.getColumnModel().getColumn(3).setMaxWidth(80);

        buildUI();
        setSize(840, 600);
        setMinimumSize(new Dimension(760, 520));
        setLocationRelativeTo(owner);

        if (initialText != null && !initialText.isBlank()) {
            linkField.setText(initialText.trim());
            parseShare();
        }
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        // ── 分享链接输入区 ──
        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 0, 2, 8);
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0;
        gc.gridy = 0;
        top.add(new JLabel("分享链接"), gc);

        gc.gridx = 1;
        gc.gridy = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        linkField.putClientProperty("JTextField.placeholderText",
                "粘贴网盘分享链接或整段分享文案（支持夸克/UC/迅雷/百度/139/123/115/光鸭/蓝奏云）");
        top.add(linkField, gc);

        JPanel parseBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        providerLabel.setFont(providerLabel.getFont().deriveFont(Font.BOLD));
        parseBox.add(providerLabel);
        parseBtn.setFocusable(false);
        parseBox.add(parseBtn);
        gc.gridx = 2;
        gc.gridy = 0;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        top.add(parseBox, gc);

        gc.gridx = 0;
        gc.gridy = 1;
        top.add(new JLabel("提取码"), gc);
        JPanel pwdBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        pwdField.putClientProperty("JTextField.placeholderText", "自动识别，可手动修正");
        pwdBox.add(pwdField);
        pwdBox.add(new JLabel("保存到"));
        pathField.setPreferredSize(new Dimension(430, pathField.getPreferredSize().height));
        pathField.setText(MainWindow.resolveDefaultSaveDir());
        pwdBox.add(pathField);
        JButton browseBtn = new JButton("浏览…");
        browseBtn.setFocusable(false);
        pwdBox.add(browseBtn);
        JButton openBtn = new JButton("打开");
        openBtn.setFocusable(false);
        openBtn.setToolTipText("在系统文件管理器中打开保存目录");
        openBtn.addActionListener(e -> openSavePathDirectory());
        pwdBox.add(openBtn);
        gc.gridx = 1;
        gc.gridy = 1;
        gc.gridwidth = 2;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        top.add(pwdBox, gc);

        root.add(top, BorderLayout.NORTH);

        // ── 文件列表 ──
        JPanel center = new JPanel(new BorderLayout(0, 6));
        JPanel pathBar = new JPanel(new BorderLayout(8, 0));
        upBtn.setFocusable(false);
        pathBar.add(upBtn, BorderLayout.WEST);
        pathLabel.setFont(pathLabel.getFont().deriveFont(Font.PLAIN));
        pathBar.add(pathLabel, BorderLayout.CENTER);
        JPanel selectBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        selectAllBtn.setFocusable(false);
        clearAllBtn.setFocusable(false);
        selectBox.add(selectAllBtn);
        selectBox.add(clearAllBtn);
        pathBar.add(selectBox, BorderLayout.EAST);
        center.add(pathBar, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(fileTable);
        center.add(scroll, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        // ── 凭据 + 操作区 ──
        JPanel south = new JPanel();
        south.setLayout(new javax.swing.BoxLayout(south, javax.swing.BoxLayout.Y_AXIS));

        JPanel credPanel = new JPanel(new BorderLayout(8, 4));
        credPanel.setBorder(BorderFactory.createTitledBorder("网盘凭据（可选）"));
        cookieArea.setLineWrap(true);
        cookieArea.setWrapStyleWord(true);
        cookieArea.putClientProperty("JTextArea.placeholderText",
                "浏览器登录对应网盘后，复制 Cookie 粘贴到这里；公开分享可留空");
        JScrollPane cookieScroll = new JScrollPane(cookieArea);
        cookieScroll.setPreferredSize(new Dimension(10, 58));
        credPanel.add(cookieScroll, BorderLayout.CENTER);

        JPanel credSide = new JPanel(new BorderLayout(0, 4));
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, 12));
        hintLabel.setForeground(new Color(120, 125, 138));
        hintLabel.setPreferredSize(new Dimension(220, 40));
        credSide.add(hintLabel, BorderLayout.CENTER);
        saveCredBtn.setFocusable(false);
        credSide.add(saveCredBtn, BorderLayout.SOUTH);
        credPanel.add(credSide, BorderLayout.EAST);
        south.add(credPanel);

        JPanel bottom = new JPanel(new BorderLayout());
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12));
        bottom.add(statusLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        downloadSelectedBtn.setFocusable(false);
        downloadAllBtn.setFocusable(false);
        closeBtn.setFocusable(false);
        actions.add(downloadSelectedBtn);
        actions.add(downloadAllBtn);
        actions.add(closeBtn);
        bottom.add(actions, BorderLayout.EAST);
        south.add(bottom);
        root.add(south, BorderLayout.SOUTH);

        setContentPane(root);

        // ── 事件 ──
        parseBtn.addActionListener(e -> parseShare());
        linkField.addActionListener(e -> parseShare());
        upBtn.addActionListener(e -> goUp());
        browseBtn.addActionListener(e -> chooseSavePath());
        selectAllBtn.addActionListener(e -> setAllChecked(true));
        clearAllBtn.addActionListener(e -> setAllChecked(false));
        downloadSelectedBtn.addActionListener(e -> downloadSelected());
        downloadAllBtn.addActionListener(e -> downloadCurrentTree());
        closeBtn.addActionListener(e -> dispose());
        saveCredBtn.addActionListener(e -> saveCredential());
        fileTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewRow = fileTable.rowAtPoint(e.getPoint());
                if (viewRow < 0) {
                    return;
                }
                int modelRow = fileTable.convertRowIndexToModel(viewRow);
                if (modelRow < 0 || modelRow >= currentFiles.size()) {
                    return;
                }
                int viewCol = fileTable.columnAtPoint(e.getPoint());
                if (viewCol == 0) {
                    Object value = fileModel.getValueAt(modelRow, 0);
                    fileModel.setValueAt(!(value instanceof Boolean b && b), modelRow, 0);
                    return;
                }
                if (e.getClickCount() == 2) {
                    CloudFile file = currentFiles.get(modelRow);
                    if (file.folder()) {
                        loadDirectory(file);
                    }
                }
            }
        });
        setBusy(false);
        statusLabel.setText("粘贴分享链接后点击「解析」");
    }

    // ══════════════════════════════════════════
    //  解析与目录浏览
    // ══════════════════════════════════════════

    private void parseShare() {
        if (busy) {
            return;
        }
        String text = linkField.getText().trim();
        if (text.isEmpty()) {
            statusLabel.setText("请先粘贴分享链接");
            return;
        }
        CloudProvider detected = CloudRegistry.detect(text);
        if (detected == null) {
            statusLabel.setText("无法识别的分享链接，请确认链接完整或平台是否受支持");
            providerLabel.setText("未识别");
            return;
        }
        provider = detected;
        providerLabel.setText(provider.drive().displayName);
        updateCredentialPanel(provider.drive());

        CloudShare parsed;
        try {
            parsed = provider.parse(text);
        } catch (Exception ex) {
            statusLabel.setText("链接解析失败: " + ex.getMessage());
            return;
        }
        // 手动修正提取码时重建分享对象（保留平台解析出的附加字段）
        String manualPwd = pwdField.getText().trim();
        if (manualPwd.equals(autoPasscode) && parsed.passcode().isEmpty()) {
            // 输入框里是上一条链接自动带入的旧提取码，切换到无提取码的新链接时丢弃
            manualPwd = "";
            pwdField.setText("");
        }
        if (!manualPwd.isEmpty() && !manualPwd.equals(parsed.passcode())) {
            CloudShare fixed = new CloudShare(parsed.drive(), parsed.shareKey(),
                    manualPwd, parsed.rawUrl(), parsed.host());
            fixed.extra().putAll(parsed.extra());
            share = fixed;
            pwdField.setText(manualPwd);
            autoPasscode = "";
        } else {
            share = parsed;
            if (!parsed.passcode().isEmpty()) {
                pwdField.setText(parsed.passcode());
                autoPasscode = parsed.passcode();
            } else {
                pwdField.setText("");
                autoPasscode = "";
            }
        }

        dirStack.clear();
        loadDirectory(null, false);
    }

    private void goUp() {
        if (busy || dirStack.isEmpty()) {
            return;
        }
        CloudFile parent = dirStack.remove(dirStack.size() - 1);
        loadDirectory(parent, false);
    }

    private void loadDirectory(CloudFile dir) {
        loadDirectory(dir, true);
    }

    private void loadDirectory(CloudFile dir, boolean pushCurrent) {
        if (provider == null || share == null) {
            return;
        }
        if (pushCurrent) {
            dirStack.add(currentDir);
        }
        setBusy(true);
        statusLabel.setText("正在读取目录…");
        String typedCookie = typedCookie();
        new SwingWorker<List<CloudFile>, Void>() {
            @Override
            protected List<CloudFile> doInBackground() throws Exception {
                try (CloudContext ctx = CloudContext.create(share.drive(), typedCookie)) {
                    ctx.seedCookiesForHost(share.host());
                    return provider.list(ctx, share, dir);
                }
            }

            @Override
            protected void done() {
                try {
                    List<CloudFile> files = get();
                    currentDir = dir;
                    currentFiles.clear();
                    currentFiles.addAll(files);
                    fileModel.setRowCount(0);
                    for (CloudFile file : files) {
                        fileModel.addRow(new Object[]{
                                Boolean.FALSE,
                                file.name(),
                                file.folder() ? "" : FileUtils.formatSize(file.size()),
                                file.folder() ? "目录" : "文件"
                        });
                    }
                    pathLabel.setText(dir == null ? "/" : dir.displayPath());
                    upBtn.setEnabled(!dirStack.isEmpty());
                    statusLabel.setText("共 " + files.size() + " 项，双击目录进入，勾选文件后下载");
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    statusLabel.setText("读取目录失败: " + readable(cause));
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void setAllChecked(boolean checked) {
        for (int row = 0; row < fileModel.getRowCount(); row++) {
            fileModel.setValueAt(checked, row, 0);
        }
    }

    private void chooseSavePath() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择保存目录");
        File current = new File(pathField.getText().trim());
        chooser.setCurrentDirectory(current.isDirectory() ? current : MainWindow.defaultBrowseDir());
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            if (selected != null) {
                pathField.setText(selected.getAbsolutePath());
            }
        }
    }

    /**
     * "打开"：在系统文件管理器中打开当前保存目录。
     * 路径为空提示；路径是普通文件时警告；目录不存在时询问是否创建后打开。
     */
    private void openSavePathDirectory() {
        String text = pathField.getText().trim();
        if (text.isEmpty()) {
            statusLabel.setText("保存路径为空");
            return;
        }
        File dir = new File(text);
        if (dir.exists() && !dir.isDirectory()) {
            JOptionPane.showMessageDialog(this,
                    "保存路径不是一个目录：\n" + dir.getAbsolutePath(),
                    "无法打开", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!dir.exists()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "目录不存在，是否创建并打开？\n" + dir.getAbsolutePath(),
                    "目录不存在", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
            if (!dir.mkdirs()) {
                JOptionPane.showMessageDialog(this,
                        "无法创建目录：\n" + dir.getAbsolutePath(),
                        "创建失败", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        File target = dir;
        Thread.ofVirtual().start(() -> {
            String error = openSystemDirectory(target);
            if (error != null) {
                EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "无法自动打开目录，请手动访问：\n" + target.getAbsolutePath()
                                + "\n\n原因：" + error,
                        "打开失败", JOptionPane.WARNING_MESSAGE));
            }
        });
    }

    /** 跨平台打开目录，多级回退（Desktop.open → 系统命令）；成功返回 null。 */
    private static String openSystemDirectory(File dir) {
        Exception firstError = null;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir);
                return null;
            }
            firstError = new UnsupportedOperationException("当前平台不支持桌面打开操作");
        } catch (Exception e) {
            firstError = e;
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "start", "", dir.getAbsolutePath());
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", dir.getAbsolutePath());
            } else {
                pb = new ProcessBuilder("xdg-open", dir.getAbsolutePath());
            }
            if (pb.start().waitFor() == 0) {
                return null;
            }
        } catch (Exception ignored) {
        }
        return firstError != null ? firstError.getMessage() : "未知错误";
    }

    private void updateCredentialPanel(CloudDrive drive) {
        cookieArea.setText(ConfigManager.getInstance().getString(drive.cookieKey, ""));
        hintLabel.setText("<html>" + drive.credentialHint + "</html>");
    }

    private void saveCredential() {
        if (provider == null) {
            statusLabel.setText("请先解析链接，确定网盘平台后再保存凭据");
            return;
        }
        try {
            ConfigManager.getInstance().setValue(provider.drive().cookieKey,
                    cookieArea.getText().trim());
            statusLabel.setText(provider.drive().displayName + " 凭据已保存，重新解析后生效");
        } catch (IOException ex) {
            statusLabel.setText("保存凭据失败: " + ex.getMessage());
        }
    }

    // ══════════════════════════════════════════
    //  添加下载任务
    // ══════════════════════════════════════════

    private void downloadSelected() {
        if (busy || share == null) {
            return;
        }
        String savePath = pathField.getText().trim();
        if (savePath.isEmpty()) {
            statusLabel.setText("请先选择保存目录");
            return;
        }
        List<CloudFile> files = new ArrayList<>();
        for (int row = 0; row < fileModel.getRowCount(); row++) {
            Object checked = fileModel.getValueAt(row, 0);
            if (checked instanceof Boolean b && b) {
                CloudFile file = currentFiles.get(row);
                if (!file.folder()) {
                    files.add(file);
                }
            }
        }
        if (files.isEmpty()) {
            statusLabel.setText("请勾选要下载的文件（目录请双击进入后再选择）");
            return;
        }
        int added = enqueue(files, savePath);
        statusLabel.setText("已添加 " + added + " 个下载任务");
    }

    private void downloadCurrentTree() {
        if (busy || share == null) {
            return;
        }
        String savePath = pathField.getText().trim();
        if (savePath.isEmpty()) {
            statusLabel.setText("请先选择保存目录");
            return;
        }
        setBusy(true);
        statusLabel.setText("正在遍历目录…");
        CloudFile startDir = currentDir;
        String typedCookie = typedCookie();
        new SwingWorker<List<CloudFile>, Void>() {
            @Override
            protected List<CloudFile> doInBackground() throws Exception {
                List<CloudFile> result = new ArrayList<>();
                try (CloudContext ctx = CloudContext.create(share.drive(), typedCookie)) {
                    ctx.seedCookiesForHost(share.host());
                    collect(ctx, startDir, result, 0);
                }
                return result;
            }

            private void collect(CloudContext ctx, CloudFile dir,
                                 List<CloudFile> result, int depth) throws Exception {
                if (depth > 10 || result.size() >= MAX_RECURSIVE_FILES) {
                    return;
                }
                for (CloudFile file : provider.list(ctx, share, dir)) {
                    if (result.size() >= MAX_RECURSIVE_FILES) {
                        return;
                    }
                    if (file.folder()) {
                        collect(ctx, file, result, depth + 1);
                    } else {
                        result.add(file);
                    }
                }
            }

            @Override
            protected void done() {
                try {
                    List<CloudFile> files = get();
                    if (files.isEmpty()) {
                        statusLabel.setText("当前目录没有可下载的文件");
                    } else {
                        int added = enqueue(files, savePath);
                        statusLabel.setText("已添加 " + added + " 个下载任务"
                                + (files.size() >= MAX_RECURSIVE_FILES
                                   ? "（已达单次上限 " + MAX_RECURSIVE_FILES + "）" : ""));
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    statusLabel.setText("遍历目录失败: " + readable(cause));
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private int enqueue(List<CloudFile> files, String savePath) {
        // 任务真正下载时由后台线程按配置读取凭据，因此这里把界面输入的凭据落盘，
        // 避免用户填了 Cookie 但忘记点「保存凭据」导致下载阶段鉴权失败
        String typedCookie = typedCookie();
        if (typedCookie != null && share != null) {
            try {
                ConfigManager.getInstance().setValue(share.drive().cookieKey, typedCookie);
            } catch (IOException ignored) {
                // 保存失败不影响本次任务，下载时仍可从默认配置读取
            }
        }
        int added = 0;
        for (CloudFile file : files) {
            try {
                downloadManager.addTask(CloudTaskCodec.encode(share, file),
                        savePath, "", "");
                added++;
            } catch (Exception ex) {
                statusLabel.setText("部分任务添加失败: " + readable(ex));
                break;
            }
        }
        return added;
    }

    /** 界面里临时输入的凭据；为空时返回 null，让 CloudContext 回落到已保存配置 */
    private String typedCookie() {
        String text = cookieArea.getText().trim();
        return text.isEmpty() ? null : text;
    }

    private void setBusy(boolean value) {
        busy = value;
        parseBtn.setEnabled(!value);
        upBtn.setEnabled(!value && !dirStack.isEmpty());
        downloadSelectedBtn.setEnabled(!value);
        downloadAllBtn.setEnabled(!value);
        saveCredBtn.setEnabled(!value);
    }

    private static String readable(Throwable ex) {
        if (ex == null) {
            return "未知错误";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
