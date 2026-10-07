package com.downloader.gui;

import com.downloader.Main;
import com.downloader.config.ConfigManager;
import com.downloader.detector.ProtocolDetector;
import com.downloader.event.DownloadEvent;
import com.downloader.event.EventBus;
import com.downloader.manager.DownloadManager;
import com.downloader.model.DownloadTask;
import com.downloader.model.Protocol;
import com.downloader.util.AppPrefs;
import com.downloader.util.FileUtils;
import com.downloader.util.InstallerLauncher;
import com.downloader.util.OsTheme;
import com.downloader.util.UpdateChecker;
import com.downloader.util.VersionInfo;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.JTextComponent;
import java.awt.BasicStroke;
import java.awt.Desktop;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主窗口 — FlatLaf 风格 GUI
 * 多任务管理：JTable 列表 + 行内表单反馈 + Toast 通知 + 搜索过滤 + 平滑动画。
 */
public class MainWindow extends JFrame implements DownloadManager.TaskListener {

    // --- 语义色（状态徽章 / 协议徽章 / 按钮描边 / Toast 共用，亮暗主题均可读） ---
    static final Color ACCENT  = new Color(61, 126, 255);
    static final Color DANGER  = new Color(229, 72, 77);
    static final Color SUCCESS = new Color(52, 199, 89);
    static final Color WARNING = new Color(240, 153, 17);
    private static final Color BADGE_HTTP    = new Color(50, 120, 200);
    private static final Color BADGE_FTP     = new Color(200, 140, 40);
    private static final Color BADGE_SFTP    = new Color(40, 180, 130);
    private static final Color BADGE_TORRENT = new Color(200, 70, 90);
    private static final Color BADGE_M3U8    = new Color(160, 80, 200);
    private static final Color BADGE_UNKNOWN = new Color(128, 132, 144);

    /** 应用版本号（由 VersionInfo 从 version.properties 读取，与 build.gradle cfgVersion 一致） */
    private static final String APP_VERSION = VersionInfo.getDisplayVersion();

    // --- UI 组件 ---
    private JTextField urlField;
    private JTextField pathField;
    private JTextField userField;
    private JPasswordField passField;
    private BadgeLabel protocolBadge;
    private JButton addBtn;
    private JButton pauseBtn;
    private JButton resumeBtn;
    private JButton deleteBtn;
    private JButton selectAllBtn;
    private JButton exitBtn;
    private JButton contactBtn;
    private JButton updateBtn;
    private JButton browseBtn;
    private JButton themeBtn;
    private JTextField searchField;
    private JButton clearSearchBtn;
    private JTable taskTable;
    private DefaultTableModel taskModel;
    private TableRowSorter<DefaultTableModel> rowSorter;
    private JPanel credentialsPanel;
    private JPanel credentialsWrapper;
    private JPanel controlPanel;
    private JLabel statsLabel;
    private JPanel tableCards;
    private final CardLayout cards = new CardLayout();
    private final Map<String, Boolean> selectedTasks = new HashMap<>();

    // --- 状态 ---
    private final DownloadManager downloadManager;
    /** 主题模式（浅色 / 深色 / 跟随系统），持久化于 AppPrefs */
    private AppPrefs.ThemeMode themeMode = AppPrefs.getThemeMode();
    /** 当前生效的深浅色（SYSTEM 模式下随操作系统切换而变化） */
    private boolean darkMode = Main.resolveDark(themeMode);
    private boolean credsShown = false;
    private Protocol detectedProtocol;
    /** 主界面保存路径是否被用户手动修改过（未修改时跟随配置中的"默认保存路径"自动同步） */
    private boolean pathFieldEdited = false;
    /** 程序化同步路径输入框时抑制"用户已编辑"标记（区分用户输入与代码 setText） */
    private boolean syncingPath = false;
    /** 前景色需跟随主题的次要标签（自定义前景色不会被 updateComponentTreeUI 自动替换） */
    private final List<JLabel> hintLabels = new ArrayList<>();
    /** 进度条渲染器不在组件树中，换肤后需手动刷新其内部组件 */
    private ProgressTableCellRenderer progressRenderer;
    /** 高频进度事件合并刷新：80ms 内的多次事件只触发一次表格刷新 */
    private final Timer refreshTimer = new Timer(80, e -> {
        ((Timer) e.getSource()).stop();
        refreshTasks();
    });

    public MainWindow() {
        downloadManager = DownloadManager.getInstance();
        downloadManager.setTaskListener(this);
        refreshTimer.setRepeats(false);
        Image icon = loadAppIcon();
        if (icon != null) {
            setIconImage(icon);
        }
        initUI();
        initWindowListener();
        syncThemeWatcher();
        registerDownloadEventNotifications();
        registerConfigSync();
    }

    /**
     * 订阅下载事件：任务完成 / 失败时弹 Toast 提示；
     * 开启「完成后自动打开目录」配置时定位到已保存的文件。
     */
    private void registerDownloadEventNotifications() {
        EventBus.getInstance().register(event -> {
            switch (event.getType()) {
                case TASK_COMPLETED -> {
                    DownloadTask task = event.getTask();
                    EventQueue.invokeLater(() -> {
                        Toast.success(this, "下载完成：" + safeFileName(task));
                        if (ConfigManager.getInstance()
                                .getBoolean("download.autoOpenFolder", false)) {
                            revealInFileManager(task);
                        }
                    });
                }
                case TASK_FAILED -> {
                    DownloadTask task = event.getTask();
                    EventQueue.invokeLater(() -> Toast.error(this,
                            "下载失败：" + safeFileName(task)));
                }
                default -> { }
            }
        });
    }

    /** 文件名兜底（任务失败时可能尚未连接成功） */
    private static String safeFileName(DownloadTask task) {
        String name = task.getFileName();
        return (name == null || name.isBlank()) ? "任务 " + task.getId() : name;
    }

    /**
     * 订阅配置变更：高级配置中保存"默认保存路径"（或外部编辑配置文件）后，
     * 若用户未在主界面手动改过保存路径，则即时同步显示，
     * 避免"配置已保存但主界面仍是旧路径"；用户手动改过则保留其选择不被覆盖。
     * 事件可能来自配置文件监视器线程，统一切回 EDT 更新组件。
     */
    private void registerConfigSync() {
        EventBus.getInstance().registerConfigChangeListener(event ->
                EventQueue.invokeLater(() -> {
                    if (pathField == null || pathFieldEdited) {
                        return;
                    }
                    syncingPath = true;
                    try {
                        pathField.setText(resolveDefaultSaveDir());
                    } finally {
                        syncingPath = false;
                    }
                }));
    }

    /** 在系统文件管理器中定位文件（Windows 用 explorer /select 选中文件，其他平台打开所在目录） */
    private void revealInFileManager(DownloadTask task) {
        String path = task.getSaveFilePath();
        if (path == null || path.isBlank()) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                File file = new File(path);
                if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
                    new ProcessBuilder("explorer", "/select," + file.getAbsolutePath()).start();
                } else {
                    File dir = file.getParentFile();
                    if (dir != null && dir.exists()) {
                        Desktop.getDesktop().open(dir);
                    }
                }
            } catch (Exception e) {
                // 自动打开属于增强体验，失败不打扰用户
            }
        });
    }

    /**
     * "打开"按钮：在系统文件管理器中打开主界面当前保存路径目录。
     * 路径为空时提示；路径是普通文件时警告；目录不存在时询问是否创建后打开。
     */
    private void openPathDirectory() {
        String text = pathField.getText().trim();
        if (text.isEmpty()) {
            pathField.putClientProperty("JComponent.outline", "error");
            Toast.warning(this, "保存路径为空");
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

    /**
     * 跨平台打开目录，多级回退（Desktop.open → 系统命令）。
     * @return 成功返回 null；全部失败返回首个错误信息
     */
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

    /**
     * 从 classpath 加载应用图标。
     * ICO 文件内嵌 PNG 图像，JDK 无法直接解码 ICO，故解析 ICONDIR 头取出 PNG 数据再解码。
     */
    static Image loadAppIcon() {
        try (InputStream in = MainWindow.class.getResourceAsStream("/万象抓取.ico")) {
            if (in == null) {
                return null;
            }
            byte[] ico = in.readAllBytes();
            // ICONDIR(6字节) + ICONDIRENTRY(16字节)，取第一个图像条目
            // 条目中：8~11字节为图像数据长度，12~15字节为图像数据偏移（均为小端序）
            if (ico.length > 22 && (ico[2] & 0xFF) == 1) {
                int entry = 6;
                int size = ((ico[entry + 11] & 0xFF) << 24) | ((ico[entry + 10] & 0xFF) << 16)
                        | ((ico[entry + 9] & 0xFF) << 8) | (ico[entry + 8] & 0xFF);
                int offset = ((ico[entry + 15] & 0xFF) << 24) | ((ico[entry + 14] & 0xFF) << 16)
                        | ((ico[entry + 13] & 0xFF) << 8) | (ico[entry + 12] & 0xFF);
                if (offset + size <= ico.length) {
                    return ImageIO.read(new ByteArrayInputStream(ico, offset, size));
                }
            }
        } catch (Exception e) {
            System.err.println("加载窗口图标失败: " + e.getMessage());
        }
        return null;
    }

    /** 次要文字颜色，跟随当前 FlatLaf 主题 */
    private static Color hintForeground() {
        return UIManager.getColor("Label.disabledForeground");
    }

    private void initUI() {
        setTitle("万象抓取" + VersionInfo.getDisplayVersion());
        setSize(940, 650);
        // 关键：禁用 JFrame 默认关闭行为，由 windowClosing 手动处理，
        // 点击取消时窗口不关闭
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 560));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());

        // ═════════ 顶部：工具栏 ═════════
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBorder(BorderFactory.createEmptyBorder(10, 14, 8, 14));

        // 左侧：任务操作按钮
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        addBtn = new JButton("添加", UiIcons.add());
        addBtn.setFocusable(false);
        addBtn.setToolTipText("添加下载任务（回车亦可提交）");
        selectAllBtn = toolButton("全选", UiIcons.selectAll());
        selectAllBtn.setToolTipText("全选 / 反选任务");
        pauseBtn = outlineButton("暂停", UiIcons.pause(), WARNING);
        resumeBtn = outlineButton("继续", UiIcons.resume(), SUCCESS);
        deleteBtn = outlineButton("删除", UiIcons.delete(), DANGER);
        leftButtons.add(addBtn);
        leftButtons.add(selectAllBtn);
        leftButtons.add(pauseBtn);
        leftButtons.add(resumeBtn);
        leftButtons.add(deleteBtn);

        // 右侧：搜索（实时过滤） + 主题切换 + 高级配置
        JPanel rightTools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        searchField = new JTextField(18);
        searchField.putClientProperty("JTextField.placeholderText", "搜索文件名 / ID / 状态");
        searchField.putClientProperty("JTextField.leadingIcon", UiIcons.search());
        clearSearchBtn = new JButton(UiIcons.close(12));
        clearSearchBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        clearSearchBtn.setFocusable(false);
        clearSearchBtn.setToolTipText("清除搜索");
        clearSearchBtn.setVisible(false);
        clearSearchBtn.addActionListener(e -> searchField.setText(""));
        searchField.putClientProperty("JTextField.trailingComponent", clearSearchBtn);
        onTextChange(searchField, this::applySearch);
        rightTools.add(searchField);

        themeBtn = toolButton(themeModeLabel(), themeModeIcon());
        themeBtn.setToolTipText("切换主题模式：浅色 / 深色 / 跟随系统");
        themeBtn.addActionListener(e -> showThemeMenu());
        rightTools.add(themeBtn);

        JButton configBtn = toolButton("高级配置", UiIcons.settings());
        configBtn.addActionListener(e -> new ConfigDialog(this).setVisible(true));
        rightTools.add(configBtn);

        // 中部：协议徽章
        protocolBadge = new BadgeLabel("待检测", BADGE_UNKNOWN);
        JPanel badgeBox = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        badgeBox.add(protocolBadge);

        toolbar.add(leftButtons, BorderLayout.WEST);
        toolbar.add(badgeBox, BorderLayout.CENTER);
        toolbar.add(rightTools, BorderLayout.EAST);

        // ═════════ 状态条：任务统计 ═════════
        statsLabel = createHintLabel("");
        statsLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        JPanel strip = new JPanel(new BorderLayout());
        strip.setBorder(BorderFactory.createEmptyBorder(2, 16, 6, 16));
        strip.add(new JLabel("下载任务"), BorderLayout.WEST);
        strip.add(statsLabel, BorderLayout.EAST);

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(toolbar);
        north.add(new JSeparator());
        north.add(strip);
        north.add(new JSeparator());

        // ═════════ 中部：任务列表（表格 + 空状态双卡片） ═════════
        String[] columnNames = {"", "ID", "文件名", "进度", "速度", "状态"};
        taskModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0; // 只有复选框列可编辑
            }

            @Override
            public Class<?> getColumnClass(int column) {
                if (column == 0) return Boolean.class;
                return super.getColumnClass(column);
            }
        };

        taskTable = new JTable(taskModel);
        taskTable.setRowHeight(40);
        taskTable.setFillsViewportHeight(true);
        taskTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        taskTable.getTableHeader().setReorderingAllowed(false);

        taskTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        taskTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        taskTable.getColumnModel().getColumn(2).setPreferredWidth(360);
        taskTable.getColumnModel().getColumn(3).setPreferredWidth(170);
        taskTable.getColumnModel().getColumn(4).setPreferredWidth(120);
        taskTable.getColumnModel().getColumn(5).setPreferredWidth(100);

        progressRenderer = new ProgressTableCellRenderer();
        taskTable.getColumnModel().getColumn(3).setCellRenderer(progressRenderer);
        taskTable.getColumnModel().getColumn(4).setCellRenderer(new SpeedTableCellRenderer());
        taskTable.getColumnModel().getColumn(5).setCellRenderer(new StatusTableCellRenderer());

        // 复选框选中状态记录
        taskModel.addTableModelListener(e -> {
            if (e.getColumn() == 0) {
                int row = e.getFirstRow();
                Boolean selected = (Boolean) taskModel.getValueAt(row, 0);
                selectedTasks.put((String) taskModel.getValueAt(row, 1), selected);
            }
        });

        // 行排序器 + 搜索实时过滤
        rowSorter = new TableRowSorter<>(taskModel);
        rowSorter.setSortsOnUpdates(false); // 增量更新时不重排序，避免视觉跳动
        taskTable.setRowSorter(rowSorter);

        // 右键菜单（打开 / 复制链接 / 暂停继续 / 删除）+ 双击定位文件
        taskTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showTaskContextMenuIfNeeded(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showTaskContextMenuIfNeeded(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    DownloadTask task = taskAt(e);
                    if (task != null) {
                        revealInFileManager(task);
                    }
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(taskTable);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        tableCards = new JPanel(cards);
        tableCards.add(scrollPane, "table");
        tableCards.add(buildEmptyState(), "empty");

        // ═════════ 底部：新建任务表单 ═════════
        JPanel south = new JPanel(new BorderLayout());
        south.add(new JSeparator(), BorderLayout.NORTH);

        controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        controlPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        applyFormBorder();

        // URL（输入即检测协议，行内反馈）
        urlField = new JTextField();
        onTextChange(urlField, this::detectProtocol);
        controlPanel.add(labeledRow("下载链接 / URL", urlField));
        controlPanel.add(strut(12));

        // 路径
        controlPanel.add(pathRow());
        controlPanel.add(strut(12));

        // 凭据（FTP/SFTP 时平滑展开）
        credentialsWrapper = new JPanel();
        credentialsWrapper.setLayout(new BoxLayout(credentialsWrapper, BoxLayout.Y_AXIS));
        credentialsWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        credentialsPanel = credentialsRow();
        credentialsWrapper.add(credentialsPanel);
        credentialsWrapper.add(strut(10));
        credentialsWrapper.setVisible(false);
        controlPanel.add(credentialsWrapper);

        // 检查更新 + 联系作者 + 退出按钮
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        updateBtn = toolButton("检查更新", UiIcons.update());
        btnRow.add(updateBtn);
        contactBtn = toolButton("联系作者", UiIcons.contact());
        btnRow.add(contactBtn);
        exitBtn = toolButton("退出", UiIcons.power());
        btnRow.add(exitBtn);
        controlPanel.add(btnRow);

        south.add(controlPanel, BorderLayout.CENTER);

        // ═════════ 组装 ═════════
        root.add(north, BorderLayout.NORTH);
        root.add(tableCards, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);

        // 主操作按钮：主色填充 + 全局回车提交
        getRootPane().setDefaultButton(addBtn);

        // --- 绑定事件 ---
        addBtn.addActionListener(e -> addTask());
        selectAllBtn.addActionListener(e -> selectAllTasks());
        pauseBtn.addActionListener(e -> pauseSelectedTasks());
        resumeBtn.addActionListener(e -> resumeSelectedTasks());
        deleteBtn.addActionListener(e -> deleteSelectedTasks());
        exitBtn.addActionListener(e -> handleExit());
        contactBtn.addActionListener(e -> openContactPage());
        updateBtn.addActionListener(e -> checkForUpdate(true));

        // 初始化任务列表
        refreshTasks();

        // 启动后延迟自动检查更新（等窗口完全显示后）
        Timer autoCheckTimer = new Timer(2000, e -> checkForUpdate(false));
        autoCheckTimer.setRepeats(false);
        autoCheckTimer.start();
    }

    // ══════════════════════════════════════════
    //  UI 辅助
    // ══════════════════════════════════════════

    private static Component strut(int h) {
        return Box.createVerticalStrut(h);
    }

    /** 工具栏按钮：FlatLaf toolBarButton 类型（无边框、悬停显边） */
    private JButton toolButton(String text, Icon icon) {
        JButton btn = new JButton(text, icon);
        btn.putClientProperty("JButton.buttonType", "toolBarButton");
        btn.setFocusable(false);
        return btn;
    }

    /** 语义色描边按钮（FlatLaf JComponent.outline：彩色描边 + 悬停自动着色） */
    private JButton outlineButton(String text, Icon icon, Color outline) {
        JButton btn = new JButton(text, icon);
        btn.setFocusable(false);
        if (outline != null) {
            btn.putClientProperty("JComponent.outline", outline);
        }
        return btn;
    }

    /** 文本变化监听（EDT 上回调） */
    private static void onTextChange(JTextComponent field, Runnable callback) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { callback.run(); }
            @Override public void removeUpdate(DocumentEvent e) { callback.run(); }
            @Override public void changedUpdate(DocumentEvent e) { callback.run(); }
        });
    }

    /**
     * 解析配置中的"默认保存路径"为绝对路径：
     * 空值（旧配置升级）兜底为程序根目录/downloads；相对路径按程序根目录解析。
     */
    private static String resolveDefaultSaveDir() {
        String dir = ConfigManager.getInstance()
                .getString("download.defaultSaveDir", "").trim();
        if (dir.isEmpty()) {
            dir = "downloads";
        }
        File f = new File(dir);
        return f.isAbsolute() ? f.getAbsolutePath()
                : new File(System.getProperty("user.dir"), dir).getAbsolutePath();
    }

    private JLabel createHintLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(hintForeground());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        hintLabels.add(label);
        return label;
    }

    private JPanel labeledRow(String label, JTextField field) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel l = createHintLabel(label);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));

        panel.add(l);
        panel.add(strut(5));
        panel.add(field);
        return panel;
    }

    private JPanel pathRow() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel l = createHintLabel("保存路径");

        // 输入框占满剩余宽度（CENTER），右侧仅固定空出"浏览…/打开"按钮的位置
        JPanel row = new JPanel(new BorderLayout(8, 0));

        // 初始值：取配置的"默认保存路径"（空值/旧配置兜底为程序根目录下 downloads，
        // 相对路径按程序根目录解析为绝对路径，所见即所得）；目录在首次下载时自动创建
        pathField = new JTextField(resolveDefaultSaveDir());
        onTextChange(pathField, () -> {
            if (!syncingPath) {
                pathFieldEdited = true;
            }
            pathField.putClientProperty("JComponent.outline", null);
        });

        browseBtn = new JButton("浏览…", UiIcons.folder());
        browseBtn.setFocusable(false);
        browseBtn.setPreferredSize(new Dimension(96, browseBtn.getPreferredSize().height));
        browseBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setCurrentDirectory(new File(pathField.getText()));
            int result = chooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                pathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        // "打开"：在系统文件管理器中打开当前保存路径对应的目录
        JButton openBtn = new JButton("打开");
        openBtn.setFocusable(false);
        openBtn.setPreferredSize(new Dimension(96, openBtn.getPreferredSize().height));
        openBtn.setToolTipText("在系统文件管理器中打开保存目录");
        openBtn.addActionListener(e -> openPathDirectory());

        JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnGroup.add(browseBtn);
        btnGroup.add(openBtn);

        row.add(pathField, BorderLayout.CENTER);
        row.add(btnGroup, BorderLayout.EAST);

        panel.add(l);
        panel.add(strut(5));
        panel.add(row);
        return panel;
    }

    private JPanel credentialsRow() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // 用户名
        JPanel uP = new JPanel();
        uP.setLayout(new BoxLayout(uP, BoxLayout.Y_AXIS));
        uP.setAlignmentX(Component.LEFT_ALIGNMENT);
        uP.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
        JLabel ul = createHintLabel("用户名");
        userField = new JTextField("anonymous");
        userField.setMaximumSize(new Dimension(Integer.MAX_VALUE, userField.getPreferredSize().height));
        uP.add(ul);
        uP.add(strut(3));
        uP.add(userField);

        // 密码
        JPanel pP = new JPanel();
        pP.setLayout(new BoxLayout(pP, BoxLayout.Y_AXIS));
        pP.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel pl = createHintLabel("密码");
        passField = new JPasswordField();
        passField.setMaximumSize(new Dimension(Integer.MAX_VALUE, passField.getPreferredSize().height));
        pP.add(pl);
        pP.add(strut(3));
        pP.add(passField);

        panel.add(uP);
        panel.add(pP);
        return panel;
    }

    /** 表单区标题边框（换肤时重建以刷新标题颜色） */
    private void applyFormBorder() {
        TitledBorder titled = BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(), "新建任务",
                TitledBorder.LEFT, TitledBorder.TOP);
        Font tf = titled.getTitleFont();
        if (tf != null) {
            titled.setTitleFont(tf.deriveFont(Font.BOLD));
        }
        controlPanel.setBorder(BorderFactory.createCompoundBorder(titled,
                BorderFactory.createEmptyBorder(2, 16, 10, 16)));
    }

    /** 空状态占位视图 */
    private JPanel buildEmptyState() {
        JPanel panel = new JPanel(new GridBagLayout());
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setOpaque(false);

        JLabel icon = new JLabel(UiIcons.download(44));
        icon.setForeground(hintForeground());
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        hintLabels.add(icon);

        JLabel title = new JLabel("暂无下载任务");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        Font base = UIManager.getFont("Label.font");
        title.setFont(base != null ? base.deriveFont(Font.PLAIN, 15f)
                : new Font(Font.SANS_SERIF, Font.PLAIN, 15));

        JLabel hint = createHintLabel("在下方输入链接，点击「添加」开始下载");
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);

        stack.add(icon);
        stack.add(strut(12));
        stack.add(title);
        stack.add(strut(6));
        stack.add(hint);
        panel.add(stack);
        return panel;
    }

    // ══════════════════════════════════════════
    //  搜索过滤
    // ══════════════════════════════════════════

    private void applySearch() {
        String q = searchField.getText().trim();
        clearSearchBtn.setVisible(!q.isEmpty());
        if (q.isEmpty()) {
            rowSorter.setRowFilter(null);
        } else {
            rowSorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(q), 1, 2, 5));
        }
    }

    // ══════════════════════════════════════════
    //  主题切换（浅色 / 深色 / 跟随系统）
    // ══════════════════════════════════════════

    private String themeModeLabel() {
        return switch (themeMode) {
            case LIGHT -> "浅色模式";
            case DARK -> "深色模式";
            case SYSTEM -> "跟随系统";
        };
    }

    private Icon themeModeIcon() {
        return switch (themeMode) {
            case LIGHT -> UiIcons.sun();
            case DARK -> UiIcons.moon();
            case SYSTEM -> UiIcons.themeAuto();
        };
    }

    /** 弹出三选一主题菜单（单选），选择后立即切换并持久化 */
    private void showThemeMenu() {
        JPopupMenu menu = new JPopupMenu();
        ButtonGroup group = new ButtonGroup();
        for (AppPrefs.ThemeMode mode : AppPrefs.ThemeMode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(
                    switch (mode) {
                        case LIGHT -> "浅色模式";
                        case DARK -> "深色模式";
                        case SYSTEM -> "跟随系统（自动切换）";
                    },
                    mode == themeMode);
            item.addActionListener(e -> setThemeMode(mode));
            group.add(item);
            menu.add(item);
        }
        menu.show(themeBtn, 0, themeBtn.getHeight());
    }

    /** 切换主题模式：换肤 + 持久化 + 同步系统主题监听 */
    private void setThemeMode(AppPrefs.ThemeMode mode) {
        if (mode == themeMode) {
            return;
        }
        themeMode = mode;
        applyTheme(Main.resolveDark(mode));
        AppPrefs.setThemeMode(mode);
        syncThemeWatcher();
    }

    /** 全局换肤：覆盖所有已创建窗口（主窗口 / 配置 / 更新对话框等） */
    private void applyTheme(boolean dark) {
        darkMode = dark;
        Main.applyLaf(dark);

        // 刷新全部窗口的 UI 委托（含不可见的后台对话框）
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w.isDisplayable()) {
                SwingUtilities.updateComponentTreeUI(w);
            }
        }
        // 单元格渲染器不在组件树中，需单独刷新其内部组件
        if (progressRenderer != null) {
            SwingUtilities.updateComponentTreeUI(progressRenderer.content);
        }
        // 自定义前景色不会被 L&F 替换，按新主题重新取色
        hintLabels.forEach(l -> l.setForeground(hintForeground()));
        // TitledBorder 标题色在创建时快照，需重建
        applyFormBorder();
        // 配置对话框的卡片边框/标题色同样是快照，需刷新
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof ConfigDialog dialog) {
                dialog.refreshTheme();
            }
        }

        themeBtn.setText(themeModeLabel());
        themeBtn.setIcon(themeModeIcon());
        taskTable.repaint();
    }

    /** 「跟随系统」模式下监听操作系统深浅色切换；手动模式下停止监听 */
    private void syncThemeWatcher() {
        if (themeMode == AppPrefs.ThemeMode.SYSTEM) {
            OsTheme.startWatching(this::onOsThemeChanged);
        } else {
            OsTheme.stopWatching();
        }
    }

    /** 操作系统深浅色变化回调（已在 EDT 上） */
    private void onOsThemeChanged() {
        if (themeMode != AppPrefs.ThemeMode.SYSTEM) {
            return;
        }
        boolean osDark = OsTheme.isOsDark();
        if (osDark != darkMode) {
            applyTheme(osDark);
        }
    }

    // ══════════════════════════════════════════
    //  协议检测（行内反馈）
    // ══════════════════════════════════════════

    private void detectProtocol() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            urlField.putClientProperty("JComponent.outline", null);
            protocolBadge.set("待检测", BADGE_UNKNOWN);
            detectedProtocol = null;
            setCredsVisible(false, false);
            return;
        }

        Protocol p = ProtocolDetector.detect(url);
        detectedProtocol = p;
        if (p == Protocol.UNKNOWN) {
            urlField.putClientProperty("JComponent.outline", "error");
            protocolBadge.set("无法识别", DANGER);
            setCredsVisible(false, false);
            return;
        }

        urlField.putClientProperty("JComponent.outline", null);
        protocolBadge.set(p.displayName, badgeColorFor(p));
        setCredsVisible(p == Protocol.FTP || p == Protocol.SFTP, true);
    }

    private static Color badgeColorFor(Protocol p) {
        return switch (p) {
            case HTTP_HTTPS -> BADGE_HTTP;
            case FTP        -> BADGE_FTP;
            case SFTP       -> BADGE_SFTP;
            case M3U8_HLS   -> BADGE_M3U8;
            case MAGNET, TORRENT -> BADGE_TORRENT;
            default         -> BADGE_UNKNOWN;
        };
    }

    /** 凭据区平滑展开 / 收起（高度动画，Box 布局中控制 preferred/maximum 高度） */
    private void setCredsVisible(boolean show, boolean applyDefaults) {
        if (show == credsShown) {
            return;
        }
        credsShown = show;

        if (show && applyDefaults) {
            if (detectedProtocol == Protocol.FTP) {
                userField.setText("anonymous");
                passField.setText("");
            } else if (detectedProtocol == Protocol.SFTP) {
                userField.setText("root");
                passField.setText("");
            }
        }

        int full = credentialsWrapper.getPreferredSize().height;
        int width = credentialsWrapper.getPreferredSize().width;
        if (show) {
            credentialsWrapper.setVisible(true);
        }
        UiAnim.animate(200, t -> {
            float k = show ? t : 1 - t;
            int h = Math.max(0, Math.round(full * k));
            credentialsWrapper.setPreferredSize(new Dimension(width, h));
            credentialsWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
            credentialsWrapper.revalidate();
            credentialsWrapper.getParent().repaint();
        }, () -> {
            if (!show) {
                credentialsWrapper.setVisible(false);
            }
            // 还原自然尺寸约束
            credentialsWrapper.setPreferredSize(null);
            credentialsWrapper.setMaximumSize(null);
            credentialsWrapper.revalidate();
        });
    }

    // ══════════════════════════════════════════
    //  任务管理
    // ══════════════════════════════════════════

    private void addTask() {
        final String url  = urlField.getText().trim();
        final String path = pathField.getText().trim();
        final String user = userField.getText().trim();
        final String pass = new String(passField.getPassword()).trim();

        if (url.isEmpty()) {
            urlField.putClientProperty("JComponent.outline", "error");
            Toast.warning(this, "请输入下载链接");
            return;
        }
        if (path.isEmpty()) {
            pathField.putClientProperty("JComponent.outline", "error");
            Toast.warning(this, "请选择保存路径");
            return;
        }

        Protocol protocol = ProtocolDetector.detect(url);
        if (protocol == Protocol.UNKNOWN) {
            urlField.putClientProperty("JComponent.outline", "error");
            Toast.error(this, "无法识别协议，请检查 URL 格式");
            return;
        }

        downloadManager.addTask(url, path, user, pass);
        urlField.setText(""); // 触发 detectProtocol 重置输入框反馈
        Toast.success(this, "下载任务已添加");
    }

    private void selectAllTasks() {
        boolean select = true;
        if (taskModel.getRowCount() > 0) {
            String firstTaskId = (String) taskModel.getValueAt(0, 1);
            select = !selectedTasks.getOrDefault(firstTaskId, false);
        }

        for (int i = 0; i < taskModel.getRowCount(); i++) {
            String taskId = (String) taskModel.getValueAt(i, 1);
            selectedTasks.put(taskId, select);
            taskModel.setValueAt(select, i, 0);
        }
        taskTable.repaint();
    }

    private void pauseSelectedTasks() {
        for (String taskId : getSelectedTaskIds()) {
            downloadManager.pauseTask(taskId);
        }
    }

    private void resumeSelectedTasks() {
        for (String taskId : getSelectedTaskIds()) {
            downloadManager.resumeTask(taskId);
        }
    }

    private void deleteSelectedTasks() {
        List<String> selectedTaskIds = getSelectedTaskIds();
        if (selectedTaskIds.isEmpty()) {
            return;
        }
        for (String taskId : selectedTaskIds) {
            downloadManager.removeTask(taskId);
        }
        Toast.info(this, "已删除 " + selectedTaskIds.size() + " 个任务");
    }

    private List<String> getSelectedTaskIds() {
        List<String> selectedIds = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : selectedTasks.entrySet()) {
            if (entry.getValue()) {
                selectedIds.add(entry.getKey());
            }
        }
        return selectedIds;
    }

    // ── 任务行右键菜单与快捷定位 ──

    /** 取鼠标事件所在行的任务（自动处理视图行到模型行的换算） */
    private DownloadTask taskAt(MouseEvent e) {
        int viewRow = taskTable.rowAtPoint(e.getPoint());
        if (viewRow < 0) {
            return null;
        }
        int modelRow = taskTable.convertRowIndexToModel(viewRow);
        if (modelRow < 0) {
            return null;
        }
        Object id = taskModel.getValueAt(modelRow, 1);
        return (id instanceof String taskId) ? downloadManager.getTask(taskId) : null;
    }

    private void showTaskContextMenuIfNeeded(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        DownloadTask task = taskAt(e);
        if (task == null) {
            return;
        }
        // 右键未选中的行时，先选中该行，保证菜单操作针对该任务
        int viewRow = taskTable.rowAtPoint(e.getPoint());
        if (taskTable.getSelectedRowCount() <= 1) {
            taskTable.setRowSelectionInterval(viewRow, viewRow);
        }
        buildTaskContextMenu(task).show(taskTable, e.getX(), e.getY());
    }

    /** 按任务当前状态构建右键菜单 */
    private JPopupMenu buildTaskContextMenu(DownloadTask task) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem openItem = new JMenuItem("打开文件", UiIcons.check());
        openItem.setEnabled(task.getStatus() == DownloadTask.TaskStatus.COMPLETED
                && task.getSaveFilePath() != null
                && new File(task.getSaveFilePath()).isFile());
        openItem.addActionListener(e -> openTaskFile(task));
        menu.add(openItem);

        JMenuItem revealItem = new JMenuItem("打开所在文件夹", UiIcons.folder());
        revealItem.setEnabled(task.getSaveFilePath() != null);
        revealItem.addActionListener(e -> revealInFileManager(task));
        menu.add(revealItem);

        JMenuItem copyItem = new JMenuItem("复制下载链接", UiIcons.contact());
        copyItem.addActionListener(e -> copyText(task.getUrl(), "下载链接已复制"));
        menu.add(copyItem);

        menu.addSeparator();
        switch (task.getStatus()) {
            case DOWNLOADING, WAITING -> {
                JMenuItem pauseItem = new JMenuItem("暂停", UiIcons.pause());
                pauseItem.addActionListener(e -> downloadManager.pauseTask(task.getId()));
                menu.add(pauseItem);
            }
            case PAUSED -> {
                JMenuItem resumeItem = new JMenuItem("继续", UiIcons.resume());
                resumeItem.addActionListener(e -> downloadManager.resumeTask(task.getId()));
                menu.add(resumeItem);
            }
            default -> { }
        }

        JMenuItem deleteItem = new JMenuItem("删除任务", UiIcons.delete());
        deleteItem.addActionListener(e -> downloadManager.removeTask(task.getId()));
        menu.add(deleteItem);
        return menu;
    }

    /** 打开已完成的文件（复用 InstallerLauncher 的多级回退，exe 也可正常启动） */
    private void openTaskFile(DownloadTask task) {
        String path = task.getSaveFilePath();
        if (path == null) {
            return;
        }
        File file = new File(path);
        if (!file.isFile()) {
            Toast.warning(this, "文件不存在或已被移动");
            return;
        }
        Thread.ofVirtual().start(() -> {
            String error = InstallerLauncher.launch(file);
            if (error != null) {
                EventQueue.invokeLater(() -> Toast.warning(this,
                        "无法打开文件：" + error));
            }
        });
    }

    /** 复制文本到系统剪贴板并提示 */
    private void copyText(String text, String successMsg) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        Toast.info(this, successMsg);
    }

    // ══════════════════════════════════════════
    //  退出逻辑
    // ══════════════════════════════════════════

    private volatile boolean exiting = false;

    private void handleExit() {
        if (exiting) return;
        exiting = true;
        // Windows 下窗口最小化（挂后台）时直接弹模态对话框，对话框无法被激活：
        // 只有原生标题栏、内容区空白且点击无响应（系统随后标记"无响应"幽灵窗口）。
        // 先恢复窗口并置前；再延迟到 windowClosing 事件处理结束后弹窗。
        if (getExtendedState() == JFrame.ICONIFIED) {
            setExtendedState(JFrame.NORMAL);
        }
        setVisible(true);
        toFront();
        SwingUtilities.invokeLater(this::showExitConfirm);
    }

    private void showExitConfirm() {
        String message = "确定要退出？\n未完成的下载任务将被取消。";
        if (activeUpdateDialog != null && activeUpdateDialog.isDownloadActive()) {
            message += "\n更新包正在后台下载，退出将中断（已下载部分保留，下次可断点续传）。";
        }
        int result;
        try {
            result = JOptionPane.showConfirmDialog(this,
                    message,
                    "确认退出",
                    JOptionPane.OK_CANCEL_OPTION);
        } catch (RuntimeException e) {
            // 弹窗异常时释放防重入标志，允许下次重试退出
            exiting = false;
            throw e;
        }

        if (result == JOptionPane.OK_OPTION) {
            downloadManager.shutdown();
            dispose();
            System.exit(0);
        } else {
            exiting = false;
        }
    }

    private void initWindowListener() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleExit();
            }
        });
    }

    // ══════════════════════════════════════════
    //  联系作者（硬编码 HTML → 临时文件 → 浏览器）
    // ══════════════════════════════════════════

    private void openContactPage() {
        String html = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>联系作者 | 万象抓取</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link href="https://fonts.googleapis.com/css2?family=Syne:wght@400;600;700;800&family=DM+Mono:wght@300;400;500&family=Noto+Sans+SC:wght@300;400;500;700&display=swap" rel="stylesheet">
    <link rel="stylesheet" href="https://cdn.bootcdn.net/ajax/libs/font-awesome/6.4.0/css/all.min.css">
    <style>
        :root {
            --glass-bg: rgba(255, 255, 255, 0.06);
            --glass-bg-hover: rgba(255, 255, 255, 0.1);
            --glass-border: rgba(255, 255, 255, 0.12);
            --glass-border-hover: rgba(255, 255, 255, 0.25);
            --glass-blur: 24px;
            --glass-blur-heavy: 40px;
            --glass-inner-glow: inset 0 1px 0 rgba(255,255,255,0.1);
            --glass-shadow: 0 8px 32px rgba(0,0,0,0.25), 0 2px 8px rgba(0,0,0,0.15);
            --glass-shadow-hover: 0 20px 60px rgba(0,0,0,0.35), 0 4px 12px rgba(0,0,0,0.2);
            --text-bright: #f0f0f5;
            --text-soft: rgba(240, 240, 245, 0.7);
            --text-muted: rgba(240, 240, 245, 0.4);
            --accent: #7dd3fc;
            --radius-xl: 28px;
            --radius-lg: 20px;
            --radius-md: 14px;
            --ease-liquid: cubic-bezier(0.22, 1, 0.36, 1);
        }

        * { margin: 0; padding: 0; box-sizing: border-box; }

        body {
            font-family: 'Noto Sans SC', 'DM Mono', sans-serif;
            min-height: 100vh;
            overflow-x: hidden;
            color: var(--text-bright);
            line-height: 1.7;
            position: relative;
        }

        .liquid-bg {
            position: fixed;
            inset: 0;
            z-index: -2;
            background: #0a0a1a;
        }

        .liquid-blob {
            position: absolute;
            border-radius: 50%;
            filter: blur(120px);
            will-change: transform;
        }
        .blob-1 {
            width: 600px; height: 600px;
            background: radial-gradient(circle, #3b82f6 0%, #1e3a8a 70%, transparent 100%);
            top: -15%; left: -10%;
            animation: drift1 25s ease-in-out infinite;
        }
        .blob-2 {
            width: 500px; height: 500px;
            background: radial-gradient(circle, #8b5cf6 0%, #4c1d95 70%, transparent 100%);
            top: 50%; right: -8%;
            animation: drift2 30s ease-in-out infinite;
        }
        .blob-3 {
            width: 450px; height: 450px;
            background: radial-gradient(circle, #06b6d4 0%, #0e4a6b 70%, transparent 100%);
            bottom: -10%; left: 30%;
            animation: drift3 22s ease-in-out infinite;
        }
        .blob-4 {
            width: 350px; height: 350px;
            background: radial-gradient(circle, #a78bfa 0%, #4c1d95 70%, transparent 100%);
            top: 20%; left: 50%;
            animation: drift4 28s ease-in-out infinite;
        }
        .blob-5 {
            width: 250px; height: 250px;
            background: radial-gradient(circle, #22d3ee 0%, #155e75 70%, transparent 100%);
            top: 70%; left: 10%;
            animation: drift5 20s ease-in-out infinite;
        }

        @keyframes drift1 {
            0%, 100% { transform: translate(0, 0) scale(1); }
            33% { transform: translate(80px, 60px) scale(1.15); }
            66% { transform: translate(-40px, 30px) scale(0.95); }
        }
        @keyframes drift2 {
            0%, 100% { transform: translate(0, 0) scale(1); }
            33% { transform: translate(-60px, -40px) scale(1.1); }
            66% { transform: translate(40px, 50px) scale(0.9); }
        }
        @keyframes drift3 {
            0%, 100% { transform: translate(0, 0) scale(1); }
            33% { transform: translate(50px, -50px) scale(1.2); }
            66% { transform: translate(-30px, -20px) scale(0.85); }
        }
        @keyframes drift4 {
            0%, 100% { transform: translate(0, 0) scale(1); }
            50% { transform: translate(-70px, 40px) scale(1.1); }
        }
        @keyframes drift5 {
            0%, 100% { transform: translate(0, 0) scale(1); }
            40% { transform: translate(60px, -30px) scale(1.15); }
            70% { transform: translate(-20px, 50px) scale(0.9); }
        }

        .liquid-bg::after {
            content: '';
            position: absolute;
            inset: 0;
            background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.04'/%3E%3C/svg%3E");
            background-repeat: repeat;
            opacity: 0.5;
            pointer-events: none;
        }

        header {
            position: relative;
            padding: 7rem 2rem 6rem;
            text-align: center;
            overflow: hidden;
        }

        .header-glass {
            position: absolute;
            inset: 0;
            backdrop-filter: blur(var(--glass-blur-heavy));
            -webkit-backdrop-filter: blur(var(--glass-blur-heavy));
            background: linear-gradient(180deg, rgba(255,255,255,0.03) 0%, rgba(255,255,255,0.01) 100%);
            border-bottom: 1px solid rgba(255,255,255,0.06);
        }

        header::before {
            content: '';
            position: absolute;
            top: 0; left: 0;
            width: 100%; height: 100%;
            background: linear-gradient(135deg, rgba(59,130,246,0.08) 0%, rgba(139,92,246,0.08) 50%, rgba(6,182,212,0.05) 100%);
            pointer-events: none;
        }

        .header-content {
            position: relative;
            z-index: 1;
            max-width: 800px;
            margin: 0 auto;
            animation: revealDown 1s var(--ease-liquid) both;
        }
        @keyframes revealDown {
            from { opacity: 0; transform: translateY(-40px) scale(0.97); filter: blur(8px); }
            to { opacity: 1; transform: translateY(0) scale(1); filter: blur(0); }
        }

        header h1 {
            font-family: 'Syne', 'Noto Sans SC', sans-serif;
            font-size: clamp(2.2rem, 5vw, 3.6rem);
            font-weight: 800;
            letter-spacing: -0.02em;
            margin-bottom: 1rem;
            background: linear-gradient(135deg, #ffffff 0%, #7dd3fc 40%, #a78bfa 70%, #22d3ee 100%);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
            background-size: 200% 200%;
            animation: shimmer 6s ease-in-out infinite;
        }
        @keyframes shimmer {
            0%, 100% { background-position: 0% 50%; }
            50% { background-position: 100% 50%; }
        }

        header p {
            font-family: 'DM Mono', monospace;
            font-size: clamp(0.85rem, 1.5vw, 1.05rem);
            font-weight: 300;
            color: var(--text-soft);
            letter-spacing: 0.05em;
        }

        .header-line {
            display: block;
            width: 80px;
            height: 3px;
            margin: 1.5rem auto 0;
            border-radius: 3px;
            background: linear-gradient(90deg, #7dd3fc, #a78bfa, #22d3ee);
            background-size: 200% 100%;
            animation: shimmer 4s ease-in-out infinite;
        }

        .container {
            max-width: 1200px;
            margin: 0 auto;
            padding: 0 24px 4rem;
        }

        .contact-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 28px;
            animation: revealUp 0.9s var(--ease-liquid) 0.2s both;
        }
        @keyframes revealUp {
            from { opacity: 0; transform: translateY(50px); filter: blur(10px); }
            to { opacity: 1; transform: translateY(0); filter: blur(0); }
        }

        .glass-card {
            position: relative;
            border-radius: var(--radius-xl);
            overflow: hidden;
            transition: all 0.5s var(--ease-liquid);
        }
        .glass-card:hover {
            transform: translateY(-8px);
        }

        .glass-card::before {
            content: '';
            position: absolute;
            inset: 0;
            border-radius: var(--radius-xl);
            border: 1px solid var(--glass-border);
            backdrop-filter: blur(var(--glass-blur));
            -webkit-backdrop-filter: blur(var(--glass-blur));
            background: var(--glass-bg);
            box-shadow: var(--glass-shadow), var(--glass-inner-glow);
            transition: all 0.5s var(--ease-liquid);
            z-index: 0;
        }
        .glass-card:hover::before {
            background: var(--glass-bg-hover);
            border-color: var(--glass-border-hover);
            box-shadow: var(--glass-shadow-hover), var(--glass-inner-glow);
        }

        .glass-card::after {
            content: '';
            position: absolute;
            top: 0; left: 0; right: 0;
            height: 1px;
            background: linear-gradient(90deg, transparent, rgba(255,255,255,0.2), rgba(125,211,252,0.3), rgba(167,139,250,0.2), rgba(255,255,255,0.2), transparent);
            z-index: 1;
            opacity: 0;
            transition: opacity 0.5s var(--ease-liquid);
        }
        .glass-card:hover::after {
            opacity: 1;
        }

        .glass-card .glow-spot {
            position: absolute;
            width: 300px;
            height: 300px;
            border-radius: 50%;
            background: radial-gradient(circle, rgba(125,211,252,0.12) 0%, transparent 70%);
            pointer-events: none;
            opacity: 0;
            transition: opacity 0.6s var(--ease-liquid);
            z-index: 0;
            top: -100px;
            right: -100px;
        }
        .glass-card:hover .glow-spot {
            opacity: 1;
        }

        .card-inner {
            position: relative;
            z-index: 1;
            padding: 2.5rem;
        }

        .card-title {
            font-family: 'Syne', 'Noto Sans SC', sans-serif;
            font-size: 1.4rem;
            font-weight: 700;
            margin-bottom: 2rem;
            display: flex;
            align-items: center;
            gap: 0.8rem;
            color: var(--text-bright);
        }
        .card-title .title-bar {
            display: inline-block;
            width: 4px;
            height: 1.4em;
            border-radius: 4px;
            background: linear-gradient(180deg, #7dd3fc, #a78bfa);
        }

        .info-row {
            display: flex;
            align-items: flex-start;
            gap: 1rem;
            padding: 1rem 1.1rem;
            border-radius: var(--radius-md);
            margin-bottom: 0.6rem;
            transition: all 0.35s var(--ease-liquid);
            position: relative;
        }
        .info-row:last-child { margin-bottom: 0; }
        .info-row:hover {
            background: rgba(255,255,255,0.04);
            transform: translateX(6px);
        }

        .info-icon {
            width: 44px;
            height: 44px;
            border-radius: 12px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 1.1rem;
            flex-shrink: 0;
            position: relative;
            overflow: hidden;
            transition: all 0.35s var(--ease-liquid);
        }
        .info-icon::before {
            content: '';
            position: absolute;
            inset: 0;
            border-radius: 12px;
            background: rgba(125, 211, 252, 0.08);
            border: 1px solid rgba(125, 211, 252, 0.15);
            backdrop-filter: blur(10px);
            -webkit-backdrop-filter: blur(10px);
        }
        .info-icon i {
            position: relative;
            z-index: 1;
            color: #7dd3fc;
        }
        .info-row:hover .info-icon {
            transform: scale(1.1) rotate(-3deg);
        }
        .info-row:hover .info-icon::before {
            background: rgba(125, 211, 252, 0.15);
            border-color: rgba(125, 211, 252, 0.3);
            box-shadow: 0 0 20px rgba(125, 211, 252, 0.15);
        }

        .contact-card .info-icon::before {
            background: rgba(167, 139, 250, 0.08);
            border-color: rgba(167, 139, 250, 0.15);
        }
        .contact-card .info-icon i {
            color: #a78bfa;
        }
        .contact-card .info-row:hover .info-icon::before {
            background: rgba(167, 139, 250, 0.15);
            border-color: rgba(167, 139, 250, 0.3);
            box-shadow: 0 0 20px rgba(167, 139, 250, 0.15);
        }

        .info-body {
            flex: 1;
            min-width: 0;
        }
        .info-label {
            font-family: 'DM Mono', monospace;
            font-size: 0.75rem;
            font-weight: 400;
            color: var(--text-muted);
            text-transform: uppercase;
            letter-spacing: 0.1em;
            margin-bottom: 2px;
        }
        .info-value {
            font-size: 0.98rem;
            font-weight: 400;
            color: var(--text-bright);
            word-break: break-all;
        }

        /* Clickable rows (copy-based) */
        .info-row.clickable {
            cursor: pointer;
        }
        .info-row.clickable:active {
            transform: translateX(6px) scale(0.98);
        }

        .info-value a {
            color: #7dd3fc;
            text-decoration: none;
            position: relative;
            transition: all 0.3s var(--ease-liquid);
        }
        .info-value a::after {
            content: '';
            position: absolute;
            bottom: -2px;
            left: 0;
            width: 0;
            height: 1px;
            background: linear-gradient(90deg, #7dd3fc, #a78bfa);
            border-radius: 2px;
            transition: width 0.4s var(--ease-liquid);
        }
        .info-value a:hover {
            color: #a78bfa;
        }
        .info-value a:hover::after {
            width: 100%;
        }

        .contact-card .info-value a {
            color: #a78bfa;
        }
        .contact-card .info-value a::after {
            background: linear-gradient(90deg, #a78bfa, #22d3ee);
        }
        .contact-card .info-value a:hover {
            color: #22d3ee;
        }

        /* Toast */
        .toast {
            position: fixed;
            top: 24px;
            left: 50%;
            transform: translateX(-50%) translateY(-80px);
            padding: 0.75rem 1.6rem;
            border-radius: 14px;
            font-family: 'DM Mono', monospace;
            font-size: 0.85rem;
            color: var(--text-bright);
            backdrop-filter: blur(24px);
            -webkit-backdrop-filter: blur(24px);
            background: rgba(167, 139, 250, 0.15);
            border: 1px solid rgba(167, 139, 250, 0.25);
            box-shadow: 0 8px 32px rgba(0,0,0,0.3);
            z-index: 9999;
            pointer-events: none;
            opacity: 0;
            transition: all 0.5s var(--ease-liquid);
            white-space: nowrap;
        }
        .toast.show {
            opacity: 1;
            transform: translateX(-50%) translateY(0);
        }

        footer {
            position: relative;
            padding: 2.5rem 2rem;
            text-align: center;
            overflow: hidden;
        }
        .footer-glass {
            position: absolute;
            inset: 0;
            backdrop-filter: blur(var(--glass-blur-heavy));
            -webkit-backdrop-filter: blur(var(--glass-blur-heavy));
            background: rgba(255,255,255,0.02);
            border-top: 1px solid rgba(255,255,255,0.06);
        }
        footer p {
            position: relative;
            z-index: 1;
            font-family: 'DM Mono', monospace;
            font-size: 0.85rem;
            font-weight: 300;
            color: var(--text-muted);
            letter-spacing: 0.05em;
        }
        footer .highlight {
            background: linear-gradient(135deg, #7dd3fc, #a78bfa);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
            font-weight: 500;
        }

        .liquid-divider {
            position: relative;
            height: 60px;
            margin: -30px 0;
            z-index: 3;
            overflow: hidden;
        }
        .liquid-divider svg {
            position: absolute;
            bottom: 0;
            width: 100%;
            height: 60px;
        }

        @media (max-width: 900px) {
            .contact-grid {
                grid-template-columns: 1fr;
                gap: 20px;
            }
            header { padding: 5rem 1.5rem 4.5rem; }
            .card-inner { padding: 2rem 1.6rem; }
        }
        @media (max-width: 480px) {
            header { padding: 4rem 1.2rem 3.5rem; }
            .card-inner { padding: 1.6rem 1.2rem; }
            .info-row { padding: 0.8rem; }
            .info-icon { width: 38px; height: 38px; font-size: 0.95rem; }
        }
    </style>
</head>
<body>

    <div class="liquid-bg">
        <div class="liquid-blob blob-1"></div>
        <div class="liquid-blob blob-2"></div>
        <div class="liquid-blob blob-3"></div>
        <div class="liquid-blob blob-4"></div>
        <div class="liquid-blob blob-5"></div>
    </div>

    <div class="toast" id="toast"></div>

    <header>
        <div class="header-glass"></div>
        <div class="header-content">
            <h1>联系作者 Ivan</h1>
            <p>有任何问题、建议或合作意向，欢迎随时与我取得联系</p>
            <span class="header-line"></span>
        </div>
    </header>

    <div class="liquid-divider">
        <svg viewBox="0 0 1440 60" preserveAspectRatio="none">
            <defs>
                <linearGradient id="wave-grad" x1="0%" y1="0%" x2="100%" y2="0%">
                    <stop offset="0%" style="stop-color:rgba(125,211,252,0.06)"/>
                    <stop offset="50%" style="stop-color:rgba(167,139,250,0.08)"/>
                    <stop offset="100%" style="stop-color:rgba(34,211,238,0.06)"/>
                </linearGradient>
            </defs>
            <path d="M0,40 C360,60 720,0 1080,30 C1260,45 1380,20 1440,30 L1440,60 L0,60 Z" fill="url(#wave-grad)"/>
            <path d="M0,45 C240,20 480,55 720,35 C960,15 1200,50 1440,30 L1440,60 L0,60 Z" fill="rgba(255,255,255,0.02)"/>
        </svg>
    </div>

    <main class="container">
        <div class="contact-grid">

            <div class="glass-card software-card">
                <div class="glow-spot"></div>
                <div class="card-inner">
                    <div class="card-title">
                        <span class="title-bar"></span>
                        软件信息
                    </div>

                    <div class="info-row">
                        <div class="info-icon"><i class="fas fa-file-code"></i></div>
                        <div class="info-body">
                            <div class="info-label">Software Name</div>
                            <div class="info-value">万象抓取</div>
                        </div>
                    </div>

                    <div class="info-row">
                        <div class="info-icon"><i class="fas fa-tag"></i></div>
                        <div class="info-body">
                            <div class="info-label">Version</div>
                            <div class="info-value">$VERSION</div>
                        </div>
                    </div>

                    <div class="info-row">
                        <div class="info-icon"><i class="fas fa-desktop"></i></div>
                        <div class="info-body">
                            <div class="info-label">Platform</div>
                            <div class="info-value">Windows 7/8/10/11（64位）</div>
                        </div>
                    </div>

                    <div class="info-row">
                        <div class="info-icon"><i class="fas fa-info-circle"></i></div>
                        <div class="info-body">
                            <div class="info-label">Description</div>
                            <div class="info-value">基于 Java Flatlaf 的功能强大的下载管理器，支持 HTTP、HTTPS、FTP、SFTP、BitTorrent 和 M3U8 等多种协议，提供直观的图形界面和丰富的下载管理功能，会持续优化和完善更多功能，敬请期待！</div>
                        </div>
                    </div>
                </div>
            </div>

            <div class="glass-card contact-card">
                <div class="glow-spot" style="background: radial-gradient(circle, rgba(167,139,250,0.12) 0%, transparent 70%);"></div>
                <div class="card-inner">
                    <div class="card-title">
                        <span class="title-bar" style="background: linear-gradient(180deg, #a78bfa, #22d3ee);"></span>
                        联系方式
                    </div>

                    <div class="info-row">
                        <div class="info-icon"><i class="fab fa-qq"></i></div>
                        <div class="info-body">
                            <div class="info-label">QQ</div>
                            <div class="info-value"><a href="https://wpa.qq.com/msgrd?v=3&uin=2195928963&site=qq&menu=yes" target="_blank">Moore_Ivan</a></div>
                        </div>
                    </div>

                    <div class="info-row clickable" onclick="copyAndToast('Moore_Ivan', '微信')">
                        <div class="info-icon"><i class="fab fa-weixin"></i></div>
                        <div class="info-body">
                            <div class="info-label">WeChat</div>
                            <div class="info-value"><a href="weixin://contacts/profile/Moore_Ivan">Moore_Ivan</a></div>
                        </div>
                    </div>

                    <div class="info-row clickable" onclick="copyAndToast('Moore_Ivan', '抖音')">
                        <div class="info-icon"><i class="fab fa-tiktok"></i></div>
                        <div class="info-body">
                            <div class="info-label">抖音</div>
                            <div class="info-value"><a href="https://www.douyin.com/user/MS4wLjABAAAAGcu2VmW1wKWUSpbGkLjilKfMymXaK54sxNPaChuPuLg?from_tab_name=main" target="_blank">Moore_Ivan</a></div>
                        </div>
                    </div>

                    <div class="info-row">
                        <div class="info-icon"><i class="fab fa-github"></i></div>
                        <div class="info-body">
                            <div class="info-label">GitHub</div>
                            <div class="info-value"><a href="https://github.com/Moore-Ivan" target="_blank">Moore-Ivan</a></div>
                        </div>
                    </div>

                    <div class="info-row">
                        <div class="info-icon"><i class="fas fa-envelope"></i></div>
                        <div class="info-body">
                            <div class="info-label">Email</div>
                            <div class="info-value"><a href="mailto:Qianhe_Ivan@outlook.com">Qianhe_Ivan@outlook.com</a></div>
                        </div>
                    </div>
                </div>
            </div>

        </div>
    </main>

    <footer>
        <div class="footer-glass"></div>
        <p>&copy; <span class="highlight">Ivan</span> 的个人博客 — 保留所有权利</p>
    </footer>

    <script>
        function copyAndToast(text, platform) {
            var toast = document.getElementById('toast');
            var copied = false;

            if (navigator.clipboard) {
                navigator.clipboard.writeText(text).then(function() { copied = true; showToast(platform + '号 ' + text + ' 已复制到剪贴板'); });
            } else {
                var ta = document.createElement('textarea');
                ta.value = text;
                ta.style.cssText = 'position:fixed;left:-9999px;top:-9999px';
                document.body.appendChild(ta);
                ta.select();
                copied = document.execCommand('copy');
                document.body.removeChild(ta);
                showToast(platform + '号 ' + text + ' 已复制到剪贴板');
            }
        }

        function showToast(msg) {
            var toast = document.getElementById('toast');
            toast.textContent = msg;
            toast.classList.add('show');
            clearTimeout(toast._timer);
            toast._timer = setTimeout(function() {
                toast.classList.remove('show');
            }, 2500);
        }
    </script>

</body>
</html>
""";
        try {
            Path temp = Files.createTempFile("contact-author-", ".html");
            Files.writeString(temp, html.replace("$VERSION", VersionInfo.getDisplayVersion()));
            temp.toFile().deleteOnExit();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(temp.toUri());
            } else {
                Toast.error(this, "当前环境不支持打开浏览器");
            }
        } catch (IOException ex) {
            Toast.error(this, "无法打开联系页面");
        }
    }

    // ══════════════════════════════════════════
    //  检查更新
    // ══════════════════════════════════════════

    /**
     * @param manual true = 手动点击（显示"已是最新"或错误提示）；false = 启动自动检查（仅新版本提示）
     */
    private volatile boolean updateChecking = false;

    /** 当前更新会话（含后台下载中的对话框），null 表示无会话 */
    private UpdateDialog activeUpdateDialog;
    /** 后台下载期间更新「检查更新」按钮文案的节流定时器 */
    private Timer updateBgTimer;

    private void checkForUpdate(boolean manual) {
        // 已有更新会话（前台或后台下载中）：直接恢复窗口，不重复检查
        if (activeUpdateDialog != null) {
            activeUpdateDialog.bringToFront();
            return;
        }
        if (updateChecking) {
            if (manual) Toast.info(this, "正在检查中…");
            return;
        }
        updateChecking = true;
        if (manual) {
            updateBtn.setEnabled(false);
            Toast.info(this, "正在检查更新…");
        }
        Thread.ofVirtual().start(() -> {
            UpdateChecker.Result result = UpdateChecker.check(APP_VERSION);
            EventQueue.invokeLater(() -> {
                updateChecking = false;
                updateBtn.setEnabled(true);
                if (result.hasUpdate) {
                    activeUpdateDialog = new UpdateDialog(this, result, APP_VERSION);
                    activeUpdateDialog.setVisible(true);
                } else if (manual) {
                    if (result.errorMsg != null) {
                        Toast.warning(this, "检查更新失败：" + result.errorMsg);
                    } else {
                        Toast.success(this, "当前已是最新版本 " + APP_VERSION);
                    }
                }
            });
        });
    }

    // ── 更新对话框后台会话回调（EDT） ──

    void onUpdateDialogBackgrounded(UpdateDialog dialog) {
        if (dialog != activeUpdateDialog) {
            return;
        }
        if (updateBgTimer == null) {
            updateBgTimer = new Timer(500, e -> refreshUpdateBgButton());
        }
        updateBgTimer.start();
        refreshUpdateBgButton();
    }

    void onUpdateDialogForeground(UpdateDialog dialog) {
        if (dialog != activeUpdateDialog) {
            return;
        }
        if (updateBgTimer != null) {
            updateBgTimer.stop();
        }
        updateBtn.setText("检查更新");
        updateBtn.setToolTipText(null);
    }

    void onUpdateDialogClosed(UpdateDialog dialog) {
        if (dialog != activeUpdateDialog) {
            return;
        }
        activeUpdateDialog = null;
        if (updateBgTimer != null) {
            updateBgTimer.stop();
        }
        updateBtn.setText("检查更新");
        updateBtn.setToolTipText(null);
    }

    /** 后台下载时把「检查更新」按钮变成实时状态入口 */
    private void refreshUpdateBgButton() {
        UpdateDialog dialog = activeUpdateDialog;
        if (dialog == null || !dialog.isBackground()) {
            return;
        }
        if (dialog.isError()) {
            updateBtn.setText("更新失败，点击查看");
        } else if (dialog.isPaused()) {
            updateBtn.setText("更新已暂停");
        } else if (dialog.isDownloading()) {
            int percent = dialog.getPercent();
            updateBtn.setText(percent >= 0 ? "更新中 " + percent + "%" : "更新中…");
        } else {
            updateBtn.setText("检查更新");
        }
        updateBtn.setToolTipText("点击查看更新下载进度");
    }

    // ══════════════════════════════════════════
    //  任务监听（合并节流刷新）
    // ══════════════════════════════════════════

    @Override
    public void onTaskAdded(DownloadTask task) {
        EventQueue.invokeLater(() -> refreshTimer.restart());
    }

    @Override
    public void onTaskUpdated(DownloadTask task) {
        EventQueue.invokeLater(() -> refreshTimer.restart());
    }

    @Override
    public void onTaskRemoved(String taskId) {
        EventQueue.invokeLater(() -> refreshTimer.restart());
    }

    /**
     * 增量刷新任务列表：按任务 ID 匹配现有行，仅更新变化单元格，
     * 避免全量清空重建导致的闪烁与多余重绘；新增任务追加到末尾。
     */
    private void refreshTasks() {
        List<DownloadTask> tasks = downloadManager.getAllTasks();

        Map<String, Integer> rowById = new HashMap<>();
        for (int i = 0; i < taskModel.getRowCount(); i++) {
            rowById.put((String) taskModel.getValueAt(i, 1), i);
        }
        Set<String> present = new HashSet<>();

        for (DownloadTask task : tasks) {
            present.add(task.getId());
            Object[] data = {
                    selectedTasks.getOrDefault(task.getId(), false),
                    task.getId(),
                    task.getFileName(),
                    task.getProgress(),
                    task.getSpeed(),
                    task.getStatus().getDisplayName()
            };
            Integer row = rowById.get(task.getId());
            if (row == null) {
                taskModel.addRow(data);
            } else {
                for (int c = 0; c < data.length; c++) {
                    if (!Objects.equals(taskModel.getValueAt(row, c), data[c])) {
                        taskModel.setValueAt(data[c], row, c);
                    }
                }
            }
        }

        // 自后向前移除已删除任务的行，避免索引位移
        for (int i = taskModel.getRowCount() - 1; i >= 0; i--) {
            if (!present.contains((String) taskModel.getValueAt(i, 1))) {
                taskModel.removeRow(i);
            }
        }

        tasks.forEach(t -> selectedTasks.putIfAbsent(t.getId(), false));
        selectedTasks.keySet().removeIf(id -> !present.contains(id));

        updateStats(tasks);
    }

    /** 更新状态条统计，并切换表格 / 空状态卡片 */
    private void updateStats(List<DownloadTask> tasks) {
        int downloading = 0, waiting = 0, paused = 0, done = 0, failed = 0;
        long totalSpeed = 0;
        for (DownloadTask t : tasks) {
            switch (t.getStatus()) {
                case DOWNLOADING -> { downloading++; totalSpeed += t.getSpeed(); }
                case WAITING     -> waiting++;
                case PAUSED      -> paused++;
                case COMPLETED   -> done++;
                case FAILED      -> failed++;
            }
        }
        StringBuilder sb = new StringBuilder("共 " + tasks.size() + " 个任务");
        if (downloading > 0) sb.append(" · 下载中 ").append(downloading);
        if (waiting > 0)     sb.append(" · 等待 ").append(waiting);
        if (paused > 0)      sb.append(" · 暂停 ").append(paused);
        if (done > 0)        sb.append(" · 已完成 ").append(done);
        if (failed > 0)      sb.append(" · 失败 ").append(failed);
        if (totalSpeed > 0)  sb.append(" · ").append(FileUtils.formatSize(totalSpeed)).append("/s");
        statsLabel.setText(sb.toString());

        cards.show(tableCards, tasks.isEmpty() ? "empty" : "table");
    }

    // ══════════════════════════════════════════
    //  协议徽章（圆角胶囊 + 颜色过渡动画）
    // ══════════════════════════════════════════

    private final class BadgeLabel extends JComponent {

        private String text;
        private Color color;
        private int animSeq = 0;

        BadgeLabel(String text, Color color) {
            this.text = text;
            this.color = color;
            setOpaque(false);
            // 构造时尚未加入容器，getFont() 为 null，从 UIManager 取全局字体
            Font base = UIManager.getFont("Label.font");
            setFont(base != null ? base.deriveFont(Font.PLAIN, 12f)
                    : new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            setToolTipText("自动识别的下载协议");
        }

        /** 更新文本并平滑过渡到目标颜色 */
        void set(String newText, Color target) {
            Color from = color;
            text = newText;
            final int seq = ++animSeq;
            UiAnim.animate(220, t -> {
                if (seq != animSeq) {
                    return; // 已有更新的动画接管
                }
                color = UiAnim.lerp(from, target, t);
                repaint();
            });
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + 26, Math.max(24, fm.getHeight() + 10));
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();

            g.setColor(UiAnim.withAlpha(color, 26));
            g.fillRoundRect(0, 0, w - 1, h - 1, h, h);
            g.setColor(UiAnim.withAlpha(color, 90));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, w - 1, h - 1, h, h);

            FontMetrics fm = g.getFontMetrics();
            g.setColor(color);
            g.drawString(text, (w - fm.stringWidth(text)) / 2,
                    (h - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    // ══════════════════════════════════════════
    //  渲染器
    // ══════════════════════════════════════════

    /** 进度列：FlatLaf 圆角进度条 + 百分比 */
    private class ProgressTableCellRenderer extends DefaultTableCellRenderer {
        final JPanel content = new JPanel(new BorderLayout(8, 0));
        private final JProgressBar progressBar;
        private final JLabel progressLabel;

        ProgressTableCellRenderer() {
            progressBar = new JProgressBar(0, 100);
            progressBar.setPreferredSize(new Dimension(100, 12));

            progressLabel = new JLabel();
            progressLabel.setHorizontalAlignment(SwingConstants.CENTER);
            progressLabel.setPreferredSize(new Dimension(44, 12));

            content.add(progressBar, BorderLayout.CENTER);
            content.add(progressLabel, BorderLayout.EAST);
            content.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            if (value instanceof Integer progress) {
                progressBar.setValue(progress);
                progressLabel.setText(progress + "%");

                content.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                progressLabel.setForeground(isSelected
                        ? table.getSelectionForeground()
                        : UIManager.getColor("Label.foreground"));
                return content;
            }
            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        }
    }

    /** 速度列：零速度显示弱化的 "–" */
    private class SpeedTableCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            long speed = value instanceof Long l ? l : 0;
            ((JLabel) c).setText(speed > 0 ? FileUtils.formatSize(speed) + "/s" : "–");
            ((JLabel) c).setHorizontalAlignment(SwingConstants.CENTER);
            if (!isSelected) {
                c.setForeground(speed > 0
                        ? UIManager.getColor("Label.foreground")
                        : hintForeground());
            }
            return c;
        }
    }

    /** 状态列：圆角语义色徽章 */
    private class StatusTableCellRenderer extends DefaultTableCellRenderer {

        private Color statusColor(String status) {
            return switch (status) {
                case "下载中" -> ACCENT;
                case "完成"   -> SUCCESS;
                case "失败"   -> DANGER;
                case "暂停"   -> WARNING;
                default       -> hintForeground(); // 等待中及其他
            };
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 行背景（表格在选中时已把背景色设为选中色）
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());

            String text = getText();
            Color color = statusColor(text);
            FontMetrics fm = g.getFontMetrics();
            int bw = fm.stringWidth(text) + 20;
            int bh = fm.getHeight() + 8;
            int bx = (getWidth() - bw) / 2;
            int by = (getHeight() - bh) / 2;

            g.setColor(UiAnim.withAlpha(color, 28));
            g.fillRoundRect(bx, by, bw, bh, bh, bh);
            g.setColor(UiAnim.withAlpha(color, 90));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(bx, by, bw - 1, bh - 1, bh, bh);

            g.setColor(color);
            g.drawString(text, (getWidth() - fm.stringWidth(text)) / 2,
                    by + (bh - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }
}
