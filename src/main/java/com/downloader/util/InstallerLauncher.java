package com.downloader.util;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 安装包启动器。
 * <p>
 * {@link Desktop#open(File)} 在个别 Windows 环境会失败（杀软实时扫描刚下载的
 * exe 导致短暂拒绝访问、ShellExecute 返回错误码等），因此采用多级回退策略：
 * <ol>
 *   <li>AWT Desktop.open（内部走 ShellExecute，可正常触发 UAC 提权）；
 *       失败时等待片刻重试一次，规避文件刚落盘被安全软件占用的瞬时错误；</li>
 *   <li>Windows：{@code cmd /c start}（同样经 ShellExecute，支持 UAC）；</li>
 *   <li>直接 CreateProcess 启动（最后手段；需要提权的安装包可能返回 740）。</li>
 * </ol>
 * macOS 回退 {@code open}，Linux 回退 {@code xdg-open}。
 * <p>
 * 所有路径失败时汇总每一级的真实错误信息返回，便于界面展示与排查，
 * 不再吞掉异常只给一个笼统的“启动失败”。
 */
public final class InstallerLauncher {

    private InstallerLauncher() {
    }

    /**
     * 尝试启动安装包。
     *
     * @return 成功返回 {@code null}；全部失败时返回各级错误原因的汇总
     */
    public static String launch(File file) {
        List<String> errors = new ArrayList<>();

        // 1) AWT Desktop.open（ShellExecute 语义，支持 UAC 提权）
        String desktopError = tryDesktopOpen(file);
        if (desktopError == null) {
            return null;
        }
        errors.add(desktopError);

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");

        // 2) 平台原生命令（Windows 下 cmd start 仍走 ShellExecute，可提权）
        String fallbackError;
        if (windows) {
            fallbackError = runBlocking("cmd.exe", "/c", "start", "", file.getAbsolutePath());
            if (fallbackError == null) {
                return null;
            }
            errors.add("cmd start：" + fallbackError);

            // 3) 直接 CreateProcess（需要提权时会报 740，仅作最后手段）
            String directError = runDetached(file.getAbsolutePath());
            if (directError == null) {
                return null;
            }
            errors.add("直接运行：" + directError);
        } else if (os.contains("mac")) {
            fallbackError = runBlocking("open", file.getAbsolutePath());
            if (fallbackError == null) {
                return null;
            }
            errors.add("open：" + fallbackError);
        } else {
            fallbackError = runBlocking("xdg-open", file.getAbsolutePath());
            if (fallbackError == null) {
                return null;
            }
            errors.add("xdg-open：" + fallbackError);
        }

        return String.join("；", errors);
    }

    /** Desktop.open，失败后等待 600ms 重试一次（杀软扫描占用通常是瞬时的）。 */
    private static String tryDesktopOpen(File file) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file);
                return null;
            }
            return "Desktop.open：当前环境不支持桌面打开操作";
        } catch (Exception first) {
            try {
                Thread.sleep(600);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "Desktop.open：" + rootReason(first);
            }
            try {
                Desktop.getDesktop().open(file);
                return null;
            } catch (Exception second) {
                return "Desktop.open：" + rootReason(second);
            }
        }
    }

    /** 启动并等待退出，退出码 0 视为成功（start 触发 UAC 时也会立即返回 0）。 */
    private static String runBlocking(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code == 0) {
                return null;
            }
            String detail = output == null ? "" : output.trim();
            return "退出码 " + code + (detail.isEmpty() ? "" : "（" + abbreviate(detail) + "）");
        } catch (Exception e) {
            return rootReason(e);
        }
    }

    /** 直接启动但不等待（安装程序会长期运行）；进程能拉起即视为成功。 */
    private static String runDetached(String path) {
        try {
            Process p = new ProcessBuilder(path).start();
            // 极短时间内异常退出（如立刻报错）也尽量感知
            Thread.sleep(300);
            if (!p.isAlive()) {
                int code = p.exitValue();
                return code == 0 ? null : "进程立即退出，退出码 " + code;
            }
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return rootReason(e);
        }
    }

    private static String rootReason(Throwable t) {
        Throwable cur = t;
        String msg = null;
        while (cur != null) {
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                msg = cur.getMessage();
            }
            cur = cur.getCause();
        }
        if (msg == null) {
            msg = t.getClass().getSimpleName();
        }
        return abbreviate(msg);
    }

    private static String abbreviate(String s) {
        s = s.strip();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
