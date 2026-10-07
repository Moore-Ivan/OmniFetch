package com.downloader.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 操作系统深浅色模式检测与变化监听。
 * - Windows：注册表 HKCU\...\Themes\Personalize\AppsUseLightTheme（0=深色）
 * - macOS：defaults read -g AppleInterfaceStyle（输出 Dark 即深色）
 * - Linux：gsettings gtk-theme 名称含 dark 视为深色，其余回退浅色
 *
 * 「跟随系统」模式下由 {@link #startWatching(Runnable)} 以 3 秒轮询感知系统切换；
 * 检测失败一律回退浅色，保证任何环境下都有可用结果。
 */
public final class OsTheme {

    private static final long POLL_INTERVAL_MS = 3_000;

    private static final AtomicReference<Boolean> cachedDark = new AtomicReference<>(detectOsDark());
    private static final AtomicBoolean watching = new AtomicBoolean(false);
    private static volatile Thread watcherThread;

    private OsTheme() {
    }

    /** 当前操作系统是否为深色模式（读取缓存值，零开销）。 */
    public static boolean isOsDark() {
        return Boolean.TRUE.equals(cachedDark.get());
    }

    /**
     * 开始监听系统主题变化（仅「跟随系统」模式需要）。
     * 感知到变化后在 EDT 上回调 {@code onThemeChanged}；重复调用无副作用。
     */
    public static synchronized void startWatching(Runnable onThemeChanged) {
        if (watching.compareAndSet(false, true)) {
            watcherThread = new Thread(() -> {
                while (watching.get()) {
                    boolean dark = detectOsDark();
                    boolean prev = cachedDark.getAndSet(dark);
                    if (dark != prev) {
                        java.awt.EventQueue.invokeLater(onThemeChanged);
                    }
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "os-theme-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
        }
    }

    /** 停止监听（切到手动模式或退出时调用）。 */
    public static synchronized void stopWatching() {
        watching.set(false);
        Thread t = watcherThread;
        if (t != null) {
            t.interrupt();
            watcherThread = null;
        }
    }

    // ── 平台检测 ──

    private static boolean detectOsDark() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("windows")) {
                return detectWindowsDark();
            }
            if (os.contains("mac")) {
                return detectMacDark();
            }
            return detectLinuxDark();
        } catch (Exception e) {
            return false;
        }
    }

    /** 读注册表 AppsUseLightTheme：0 = 深色 */
    private static boolean detectWindowsDark() throws Exception {
        String output = exec(6_000,
                "reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme");
        if (output == null) {
            return false;
        }
        for (String line : output.split("\n")) {
            // 形如 "    AppsUseLightTheme    REG_DWORD    0x1"
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)")
                    .matcher(line);
            if (m.find()) {
                return Integer.parseInt(m.group(1), 16) == 0;
            }
        }
        return false;
    }

    /** macOS：命令输出 Dark 表示深色；浅色模式下该键不存在（非零退出码） */
    private static boolean detectMacDark() throws Exception {
        String output = exec(4_000, "defaults", "read", "-g", "AppleInterfaceStyle");
        return output != null && output.trim().equalsIgnoreCase("Dark");
    }

    /** Linux：优先 GNOME gsettings 的 gtk-theme 名；其他桌面回退浅色 */
    private static boolean detectLinuxDark() {
        try {
            String output = exec(4_000,
                    "gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
            return output != null && output.toLowerCase().contains("dark");
        } catch (Exception e) {
            return false;
        }
    }

    /** 执行外部命令并返回 stdout（读取失败/超时返回 null，不抛出） */
    private static String exec(long timeoutMs, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                } catch (Exception ignored) {
                }
            }, "os-theme-exec-reader");
            reader.setDaemon(true);
            reader.start();
            if (!proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                return null;
            }
            reader.join(1_000);
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
