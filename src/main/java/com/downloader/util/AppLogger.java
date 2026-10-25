package com.downloader.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 轻量级统一日志工具（零依赖，刻意不引入 SLF4J/Logback）。
 * <ul>
 *   <li>按天滚动：logs/app-yyyy-MM-dd.log（程序根目录下，已加入 .gitignore）；</li>
 *   <li>控制台镜像：INFO/DEBUG 走 stdout，WARN/ERROR 走 stderr；</li>
 *   <li>每行刷盘：下载日志量小（级别过滤 + 事件驱动），实时性优先；</li>
 *   <li>日志系统自身故障绝不影响业务：写文件失败时静默禁用文件输出。</li>
 * </ul>
 * 注意：本类不得依赖 ConfigManager（ConfigManager 初始化期间就要打日志，避免循环依赖）。
 */
public final class AppLogger {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static final Object LOCK = new Object();
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Path LOG_DIR = Paths.get("logs");

    /** 最低输出级别，低于该级别的日志直接丢弃（可用 setLevel 调整） */
    private static volatile Level minLevel = Level.INFO;
    private static volatile boolean fileLoggingEnabled = true;
    private static String currentDate;
    private static BufferedWriter writer;

    private AppLogger() {
    }

    /** 调整最低输出级别（如启动后从配置读取） */
    public static void setLevel(Level level) {
        if (level != null) {
            minLevel = level;
        }
    }

    public static void debug(String message) {
        log(Level.DEBUG, message, null);
    }

    public static void info(String message) {
        log(Level.INFO, message, null);
    }

    public static void warn(String message) {
        log(Level.WARN, message, null);
    }

    public static void warn(String message, Throwable t) {
        log(Level.WARN, message, t);
    }

    public static void error(String message) {
        log(Level.ERROR, message, null);
    }

    public static void error(String message, Throwable t) {
        log(Level.ERROR, message, t);
    }

    private static void log(Level level, String message, Throwable t) {
        Level min = minLevel;
        if (level.ordinal() < min.ordinal()) {
            return;
        }
        String line = TIME_FMT.format(LocalDateTime.now())
                + " [" + level + "] [" + Thread.currentThread().getName() + "] "
                + (message != null ? message : "");
        if (t != null) {
            line += " | " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        // 控制台镜像（stderr 用于警告/错误，便于脚本分离）
        if (level.ordinal() >= Level.WARN.ordinal()) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
        if (t != null) {
            StackTraceElement[] trace = t.getStackTrace();
            for (int i = 0; i < Math.min(trace.length, 10); i++) {
                String traceLine = "    at " + trace[i];
                if (level.ordinal() >= Level.WARN.ordinal()) {
                    System.err.println(traceLine);
                } else {
                    System.out.println(traceLine);
                }
            }
        }

        writeToFile(level, line);
    }

    private static void writeToFile(Level level, String line) {
        if (!fileLoggingEnabled) {
            return;
        }
        String today = LocalDate.now().toString();
        synchronized (LOCK) {
            try {
                if (writer == null || !today.equals(currentDate)) {
                    rollToDate(today);
                }
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                // 日志系统自身故障静默降级：只保留控制台输出，绝不妨碍业务
                fileLoggingEnabled = false;
                closeQuietly();
            }
        }
    }

    /** 打开（或按天滚动到）当天的日志文件 */
    private static void rollToDate(String today) throws IOException {
        closeQuietly();
        Files.createDirectories(LOG_DIR);
        writer = Files.newBufferedWriter(
                LOG_DIR.resolve("app-" + today + ".log"),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        currentDate = today;
    }

    private static void closeQuietly() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
        }
    }
}
