package com.downloader;

import com.downloader.gui.MainWindow;
import com.downloader.util.AppPrefs;
import com.downloader.util.AppPrefs.ThemeMode;
import com.downloader.util.OsTheme;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.Map;

/**
 * 应用入口
 */
public class Main {

    /** 按主题模式解析当前实际是否使用深色（SYSTEM 模式跟随操作系统） */
    public static boolean resolveDark(ThemeMode mode) {
        return switch (mode) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> OsTheme.isOsDark();
        };
    }

    public static void main(String[] args) {
        // 桌面应用跟随操作系统代理设置（Clash/v2ray、公司代理等）。
        // JVM 默认忽略系统代理强制直连，会出现"浏览器能访问 GitHub，
        // 打包应用却连接被拒/超时"；未配置系统代理时代理选择器返回直连，无副作用。
        // 必须在任何网络类加载前设置（DefaultProxySelector 初始化时读取该属性）
        System.setProperty("java.net.useSystemProxies", "true");

        // Windows 下中文渲染优化：指定雅黑 UI 字体（经 FlatLaf 全局默认值，
        // 保留其 HiDPI 每显示器缩放能力；通过 system property 适配未来多语言扩展）
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            FlatLaf.setGlobalExtraDefaults(Map.of("defaultFont", "13 \"Microsoft YaHei UI\""));
        }

        // 启用 FlatLaf 主题（必须在创建任何 Swing 组件之前设置；
        // 其余全局样式见 classpath 根目录 FlatLaf.properties，FlatLaf 自动加载）；
        // 主题模式由用户持久化偏好决定：浅色 / 深色 / 跟随操作系统
        applyLaf(resolveDark(AppPrefs.getThemeMode()));

        // 在事件 dispatch 线程中创建并显示 UI
        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }

    /** 安装 FlatLaf 主题（供启动与运行时换肤共用） */
    public static void applyLaf(boolean dark) {
        try {
            UIManager.setLookAndFeel(dark ? new FlatDarkLaf() : new FlatLightLaf());
        } catch (javax.swing.UnsupportedLookAndFeelException e) {
            System.err.println("设置主题失败: " + e.getMessage());
        }
    }
}
