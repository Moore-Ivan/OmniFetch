package com.downloader.gui;

import com.downloader.config.ConfigManager;
import com.downloader.config.ConfigManager.ConfigItem;
import com.downloader.config.ConfigManager.ConfigType;

import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 高级配置对话框
 * 分组卡片展示全部配置项；数值项使用带最小/最大阈值的 JSpinner，
 * 提供"恢复默认"和"保存当前"操作，保存后即时写入 config/application.properties 并热生效。
 */
public class ConfigDialog extends JDialog {

    private final ConfigManager configManager = ConfigManager.getInstance();
    private final Map<ConfigItem, JSpinner> spinners = new HashMap<>();
    /** 各分组面板已使用的行数 */
    private final Map<JPanel, Integer> rowCounters = new HashMap<>();
    private JCheckBox pluginEnabledBox;
    private JTextField pluginDirField;

    public ConfigDialog(Window owner) {
        super(owner, "高级配置", ModalityType.APPLICATION_MODAL);
        Image icon = MainWindow.loadAppIcon();
        if (icon != null) {
            setIconImage(icon);
        }
        initUI();
    }

    private void initUI() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(18, 18, 16, 18));

        // 配置文件位置提示
        JLabel pathHint = new JLabel("<html><body>配置文件：<code>"
                + configManager.getConfigPath().toAbsolutePath()
                + "</code></body></html>");
        pathHint.setForeground(UIManager.getColor("Label.disabledForeground"));
        pathHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        pathHint.setBorder(BorderFactory.createEmptyBorder(0, 2, 12, 0));
        content.add(pathHint);

        // 按分组构建表单（保持注册顺序）
        Map<String, JPanel> groupPanels = new LinkedHashMap<>();
        for (ConfigItem item : configManager.getConfigItems()) {
            JPanel groupPanel = groupPanels.computeIfAbsent(item.group, g -> createGroupPanel(g));
            addItemRow(groupPanel, item);
        }

        // 插件分组追加"添加插件"按钮行：点击直接打开插件目录
        JPanel pluginPanel = groupPanels.get("插件配置");
        if (pluginPanel != null) {
            addAddPluginRow(pluginPanel);
        }

        for (JPanel groupPanel : groupPanels.values()) {
            groupPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(groupPanel);
            content.add(Box.createVerticalStrut(12));
        }

        // 底部按钮（分隔线 + 主操作默认按钮）
        JButton resetBtn = new JButton("恢复默认");
        JButton saveBtn = new JButton("保存当前");
        JButton closeBtn = new JButton("关闭");
        resetBtn.setFocusable(false);
        saveBtn.setFocusable(false);
        closeBtn.setFocusable(false);
        resetBtn.setToolTipText("将所有配置项恢复为默认值并立即保存");
        saveBtn.setToolTipText("校验并保存配置，即时生效");
        resetBtn.addActionListener(e -> confirmResetToDefaults());
        saveBtn.addActionListener(e -> save());
        closeBtn.addActionListener(e -> dispose());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnPanel.add(resetBtn);
        btnPanel.add(saveBtn);
        btnPanel.add(closeBtn);

        content.add(Box.createVerticalStrut(4));
        content.add(new JSeparator());
        content.add(Box.createVerticalStrut(14));
        content.add(btnPanel);

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        setContentPane(scrollPane);

        getRootPane().setDefaultButton(saveBtn);
        setSize(720, 640);
        setMinimumSize(new Dimension(580, 440));
        setLocationRelativeTo(getOwner());
    }

    /**
     * 创建分组面板：圆角边框 + 粗体标题，视觉层次清晰。
     * 标题色取自 FlatLaf 前景色，换肤时随主题刷新。
     */
    private JPanel createGroupPanel(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        TitledBorder titled = BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(8, 10, 8, 10), title,
                TitledBorder.LEFT, TitledBorder.TOP);
        Font tf = titled.getTitleFont();
        if (tf != null) {
            titled.setTitleFont(tf.deriveFont(Font.BOLD));
        }
        Color titleColor = UIManager.getColor("Label.foreground");
        if (titleColor != null) {
            titled.setTitleColor(titleColor);
        }
        // 圆角淡边框卡片效果（配合 FlatLaf Panel.background）
        p.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(UIManager.getColor("Component.borderColor"), 1, true),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createEmptyBorder(4, 4, 4, 4),
                        titled)));
        p.setOpaque(true);
        Color bg = UIManager.getColor("Panel.background");
        if (bg != null) {
            p.setBackground(bg);
        }
        return p;
    }

    private void addItemRow(JPanel groupPanel, ConfigItem item) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.gridy = nextRow(groupPanel);

        // 标签列
        gbc.gridx = 0;
        gbc.weightx = 0;
        JLabel label = new JLabel(item.label + (item.unit != null ? "（" + item.unit + "）" : ""));
        label.setPreferredSize(new Dimension(180, label.getPreferredSize().height));
        groupPanel.add(label, gbc);

        // 输入控件列（不拉伸，保持自然宽度，避免挤压范围列）
        gbc.gridx = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        if (item.type == ConfigType.NUMBER) {
            long current = parseClamped(item, configManager.getString(item.key, item.defaultValue));
            // 注意：四个 long 实参命中 SpinnerNumberModel(double,...) 重载（宽化优先于装箱），
            // 模型值为 Double，取值时必须按 Number.longValue() 转换（见 save()），
            // 不能直接 toString 后 Long.parseLong（"5.0" 会解析失败）
            SpinnerNumberModel model = new SpinnerNumberModel(
                    current, item.min, item.max, item.step);
            JSpinner spinner = new JSpinner(model);
            // 整数显示，带千分位分隔符，大数字更易读
            JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "#,###");
            spinner.setEditor(editor);
            editor.getTextField().setColumns(8);
            spinner.setToolTipText(String.format("允许范围：%s ~ %s，默认值：%s",
                    fmt(item.min), fmt(item.max), fmt(item.getDefaultAsLong())));
            spinners.put(item, spinner);
            groupPanel.add(spinner, gbc);

            // 范围提示列（靠右，不设固定宽度，用文本自然宽度避免被截断）
            gbc.gridx = 2;
            gbc.weightx = 1;
            gbc.anchor = GridBagConstraints.EAST;
            gbc.fill = GridBagConstraints.NONE;
            JLabel range = new JLabel(fmt(item.min) + " ~ " + fmt(item.max));
            range.setForeground(UIManager.getColor("Label.disabledForeground"));
            groupPanel.add(range, gbc);
            // 恢复默认锚点
            gbc.anchor = GridBagConstraints.WEST;
        } else if (item.type == ConfigType.BOOL) {
            JCheckBox checkBox = new JCheckBox("启用");
            checkBox.setSelected(configManager.getBoolean(item.key,
                    Boolean.parseBoolean(item.defaultValue)));
            pluginEnabledBox = checkBox;
            groupPanel.add(checkBox, gbc);
        } else {
            JTextField textField = new JTextField(
                    configManager.getString(item.key, item.defaultValue), 14);
            pluginDirField = textField;
            groupPanel.add(textField, gbc);
        }
    }

    /**
     * 在插件分组追加"添加插件"按钮行。
     * 按钮与上方输入控件列对齐，点击后在系统文件管理器中打开插件目录，
     * 用户将插件 JAR 放入即可完成安装。
     */
    private void addAddPluginRow(JPanel groupPanel) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.gridy = nextRow(groupPanel);

        // 占位标签列，保持与上方输入控件对齐
        gbc.gridx = 0;
        gbc.weightx = 0;
        JLabel spacer = new JLabel();
        spacer.setPreferredSize(new Dimension(180, spacer.getPreferredSize().height));
        groupPanel.add(spacer, gbc);

        gbc.gridx = 1;
        JButton addPluginBtn = new JButton("添加插件", UiIcons.folder());
        addPluginBtn.setFocusable(false);
        addPluginBtn.setToolTipText("打开插件目录，将插件 JAR 放入后重启程序即可生效");
        addPluginBtn.addActionListener(e -> openPluginDirectory());
        groupPanel.add(addPluginBtn, gbc);

        // 操作说明列
        gbc.gridx = 2;
        gbc.weightx = 1;
        JLabel hint = new JLabel("打开目录，放入插件 JAR");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        groupPanel.add(hint, gbc);
    }

    /**
     * 打开插件目录：优先使用界面中当前填写的目录（未保存也可打开），
     * 目录不存在时自动创建；通过系统文件管理器打开，失败时提示手动访问路径。
     */
    private void openPluginDirectory() {
        String dirText = pluginDirField != null ? pluginDirField.getText().trim() : "";
        if (dirText.isEmpty()) {
            dirText = configManager.getString("plugin.dir", "plugins");
        }
        File dir = new File(dirText);
        if (!dir.exists() && !dir.mkdirs()) {
            JOptionPane.showMessageDialog(this,
                    "无法创建插件目录：\n" + dir.getAbsolutePath(), "打开失败",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new UnsupportedOperationException("当前平台不支持桌面打开操作");
            }
            Desktop.getDesktop().open(dir);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                    "无法自动打开插件目录，请手动访问：\n" + dir.getAbsolutePath(),
                    "打开失败", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 数值格式化：带千分位分隔符，提升大数字可读性 */
    private static String fmt(long v) {
        return String.format("%,d", v);
    }

    /** 取该分组面板的下一行行号 */
    private int nextRow(JPanel panel) {
        return rowCounters.merge(panel, 1, Integer::sum) - 1;
    }

    /**
     * "恢复默认"确认：点"否"或关闭弹窗则取消，不做任何改动；
     * 点"是"则把界面重置为默认值并立即走保存流程写盘生效。
     */
    private void confirmResetToDefaults() {
        int choice = JOptionPane.showOptionDialog(this,
                "确定要将所有配置项恢复为默认值吗？\n恢复后将立即保存并生效，当前未保存的修改会丢失。",
                "恢复默认",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                new Object[]{"是", "否"},
                "是");
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        resetToDefaults();
        save();
    }

    private void resetToDefaults() {
        for (Map.Entry<ConfigItem, JSpinner> entry : spinners.entrySet()) {
            // 模型为 Double 类型（见 addItemRow），传 Long 会与边界比较时 ClassCastException
            entry.getValue().setValue((double) entry.getKey().getDefaultAsLong());
        }
        if (pluginEnabledBox != null) {
            pluginEnabledBox.setSelected(true);
        }
        if (pluginDirField != null) {
            pluginDirField.setText("plugins");
        }
    }

    private void save() {
        // 提交所有 spinner 正在编辑的文本
        for (JSpinner spinner : spinners.values()) {
            try {
                spinner.commitEdit();
            } catch (ParseException e) {
                JOptionPane.showMessageDialog(this,
                        "存在无法解析的数值，请检查输入。", "输入无效", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        Map<String, String> values = new HashMap<>();
        for (ConfigItem item : configManager.getConfigItems()) {
            if (item.type == ConfigType.NUMBER) {
                // 以 Number 通用取值，兼容编辑器可能返回的 Long/Integer/Double
                Number n = (Number) spinners.get(item).getValue();
                values.put(item.key, Long.toString(n.longValue()));
            }
        }
        if (pluginEnabledBox != null) {
            values.put("plugin.enabled", Boolean.toString(pluginEnabledBox.isSelected()));
        }
        if (pluginDirField != null) {
            String dir = pluginDirField.getText().trim();
            if (dir.isEmpty()) {
                JOptionPane.showMessageDialog(this, "插件目录不能为空。", "输入无效",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            values.put("plugin.dir", dir);
        }

        // 交叉校验：线程池要求并发任务数不超过最大线程数
        long concurrent = Long.parseLong(values.get("download.maxConcurrentTasks"));
        long maxThreads = Long.parseLong(values.get("download.maxThreads"));
        if (concurrent > maxThreads) {
            JOptionPane.showMessageDialog(this,
                    "最大并发任务数不能大于最大线程数。", "配置冲突", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // 交叉校验：最小分片不能大于最大分片
        long minChunk = Long.parseLong(values.get("http.minChunkSize"));
        long maxChunk = Long.parseLong(values.get("http.maxChunkSize"));
        if (minChunk > maxChunk) {
            JOptionPane.showMessageDialog(this,
                    "最小分片大小不能大于最大分片大小。", "配置冲突", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            configManager.saveConfig(values);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "保存配置失败：" + e.getMessage(), "保存失败", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(this, "配置已保存并即时生效。", "保存成功",
                JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    private static long parseClamped(ConfigItem item, String raw) {
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            value = item.getDefaultAsLong();
        }
        return Math.max(item.min, Math.min(item.max, value));
    }
}
