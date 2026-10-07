package com.downloader.util;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 应用级用户偏好（界面外观等与下载配置无关、无需暴露在配置界面的设置）。
 * 基于 {@link Preferences} 用户节点持久化，重启后保留。
 */
public final class AppPrefs {

    /** 主题模式：浅色 / 深色 / 跟随操作系统 */
    public enum ThemeMode { LIGHT, DARK, SYSTEM }

    private static final Preferences PREFS =
            Preferences.userRoot().node("com/downloader/omnifetch");

    private static final String KEY_THEME_MODE = "ui.themeMode";
    /** 旧版主题键（布尔），仅用于一次性迁移 */
    private static final String KEY_LEGACY_DARK = "ui.darkMode";

    private AppPrefs() {
    }

    /**
     * 读取主题模式，默认跟随系统。
     * 兼容旧版本只存 ui.darkMode 布尔键的情况：首次读取时迁移为新键。
     */
    public static ThemeMode getThemeMode() {
        String stored = PREFS.get(KEY_THEME_MODE, null);
        if (stored != null) {
            try {
                return ThemeMode.valueOf(stored);
            } catch (IllegalArgumentException e) {
                // 存储值非法，落到默认
            }
        }
        // 迁移旧布尔键
        if (PREFS.get(KEY_LEGACY_DARK, null) != null) {
            ThemeMode migrated = PREFS.getBoolean(KEY_LEGACY_DARK, false)
                    ? ThemeMode.DARK : ThemeMode.LIGHT;
            setThemeMode(migrated);
            PREFS.remove(KEY_LEGACY_DARK);
            flushQuietly();
            return migrated;
        }
        return ThemeMode.SYSTEM;
    }

    /** 持久化主题模式；写入失败仅记录，不影响界面切换。 */
    public static void setThemeMode(ThemeMode mode) {
        PREFS.put(KEY_THEME_MODE, mode.name());
        flushQuietly();
    }

    private static void flushQuietly() {
        try {
            PREFS.flush();
        } catch (BackingStoreException e) {
            System.err.println("保存主题偏好失败: " + e.getMessage());
        }
    }
}
