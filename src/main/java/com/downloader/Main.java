package com.downloader;

import com.downloader.gui.MainWindow;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.SwingUtilities;
import java.util.Map;

/**
 * 应用入口
 */
public class Main {

    public static void main(String[] args) {
        // Windows 下中文渲染优化：指定雅黑 UI 字体（经 FlatLaf 全局默认值，
        // 保留其 HiDPI 每显示器缩放能力；通过 system property 适配未来多语言扩展）
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            FlatLaf.setGlobalExtraDefaults(Map.of("defaultFont", "13 \"Microsoft YaHei UI\""));
        }

        // 启用 FlatLaf 亮色主题（必须在创建任何 Swing 组件之前设置；
        // 其余全局样式见 classpath 根目录 FlatLaf.properties，FlatLaf 自动加载）
        FlatLightLaf.setup();

        // 在事件 dispatch 线程中创建并显示 UI
        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }
}
