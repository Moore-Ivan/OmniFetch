package com.downloader.util;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 应用级用户偏好（界面外观等与下载配置无关、无需暴露在配置界面的设置）。
 * 基于 {@link Preferences} 用户节点持久化，重启后保留。
 */
public final class AppPrefs {

    private static final Preferences PREFS =
            Preferences.userRoot().node("com/downloader/omnifetch");

    private static final String KEY_DARK_MODE = "ui.darkMode";

    private AppPrefs() {
    }

    /** 是否使用深色主题，默认浅色。 */
    public static boolean isDarkMode() {
        return PREFS.getBoolean(KEY_DARK_MODE, false);
    }

    /** 持久化主题选择；写入失败仅记录，不影响界面切换。 */
    public static void setDarkMode(boolean darkMode) {
        PREFS.putBoolean(KEY_DARK_MODE, darkMode);
        try {
            PREFS.flush();
        } catch (BackingStoreException e) {
            System.err.println("保存主题偏好失败: " + e.getMessage());
        }
    }
}
