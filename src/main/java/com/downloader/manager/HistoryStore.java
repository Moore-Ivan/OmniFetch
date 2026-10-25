package com.downloader.manager;

import com.downloader.model.DownloadTask;
import com.downloader.model.Protocol;
import com.downloader.util.AppLogger;
import com.downloader.util.MiniJson;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 历史下载记录持久化（config/history.json，与 application.properties 同级）。
 * <p>设计要点：
 * <ul>
 *   <li>不直接序列化 DownloadTask（含 downloader/callback 等运行时字段），
 *       保存时按字段抽取为 Map，加载时由 {@link #fromRecord} 重建任务；</li>
 *   <li>不保存密码，FTP/SFTP 恢复后由界面在"继续/重试"时补输；</li>
 *   <li>脏标记 + 守护单线程调度器每 2 秒检查落盘，进度高频更新只写 volatile 开销极低；</li>
 *   <li>原子写：先写 history.json.tmp 再 move（优先 ATOMIC_MOVE），防止写一半崩溃损坏文件；</li>
 *   <li>加载失败（损坏 JSON）自动备份为 history.json.bad-时间戳 后重建空历史；</li>
 *   <li>记录上限 {@value #MAX_RECORDS} 条，超出时优先淘汰最早的已完成记录；</li>
 *   <li>WAITING/DOWNLOADING 状态保存时统一映射为 PAUSED（非终态跨进程不可延续）。</li>
 * </ul>
 */
public class HistoryStore {

    /** 历史记录条数上限 */
    private static final int MAX_RECORDS = 1000;

    private final Path file;
    /** 任务快照提供者（DownloadManager::getAllTasks，保存时取当前全量任务） */
    private final Supplier<List<DownloadTask>> tasksSupplier;
    private final ScheduledExecutorService saver;
    private volatile boolean dirty = false;

    public HistoryStore(Supplier<List<DownloadTask>> tasksSupplier) {
        this.file = Paths.get("config", "history.json");
        this.tasksSupplier = tasksSupplier;
        saver = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "history-saver");
            t.setDaemon(true);
            return t;
        });
        // 每 2 秒检查一次脏标记，有变更才全量落盘（节流，避免进度更新频繁写盘）
        saver.scheduleWithFixedDelay(this::flushIfDirty, 2, 2, TimeUnit.SECONDS);
    }

    /** 标记历史记录有变更，等待下一次定时落盘 */
    public void markDirty() {
        dirty = true;
    }

    private void flushIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        try {
            saveNow(tasksSupplier.get());
        } catch (Exception e) {
            AppLogger.warn("保存历史下载记录失败: " + e.getMessage());
        }
    }

    /** 停止定时落盘并同步保存一次（程序退出时调用，必须在取消任务前执行） */
    public void shutdown() {
        saver.shutdownNow();
        dirty = false;
        try {
            saveNow(tasksSupplier.get());
        } catch (Exception e) {
            AppLogger.warn("退出时保存历史下载记录失败: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════
    //  加载
    // ══════════════════════════════════════════

    /**
     * 加载历史记录（原始 Map 列表）。
     * 文件不存在 / 为空 / 结构不符均返回空列表；
     * JSON 损坏时备份为 history.json.bad-时间戳 后重建空历史。
     */
    public List<Map<String, Object>> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AppLogger.warn("读取历史下载记录失败: " + e.getMessage());
            return List.of();
        }
        if (json.isBlank()) {
            return List.of();
        }
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (IOException e) {
            backupCorruptedFile();
            return List.of();
        }
        if (!(root instanceof Map<?, ?> rootMap)
                || !(rootMap.get("tasks") instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> records = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> record) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) record;
                records.add(typed);
            }
        }
        return records;
    }

    /** 损坏文件备份后重建空历史（备份失败时删除原文件，避免每次启动都报损坏） */
    private void backupCorruptedFile() {
        AppLogger.warn("历史下载记录文件损坏: " + file);
        Path bad = file.resolveSibling("history.json.bad-"
                + LocalDateTime.now().toString().replace(':', '-'));
        try {
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
            AppLogger.warn("已备份损坏文件为: " + bad.getFileName());
        } catch (IOException e) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
    }

    // ══════════════════════════════════════════
    //  保存
    // ══════════════════════════════════════════

    private void saveNow(List<DownloadTask> tasks) throws IOException {
        List<Map<String, Object>> records = new ArrayList<>(tasks.size());
        for (DownloadTask task : tasks) {
            records.add(toRecord(task));
        }
        // 超出上限时优先淘汰最早的已完成记录，仍不足则从最旧记录开始截断
        while (records.size() > MAX_RECORDS) {
            int removed = -1;
            for (int i = 0; i < records.size(); i++) {
                if ("COMPLETED".equals(records.get(i).get("status"))) {
                    removed = i;
                    break;
                }
            }
            records.remove(removed >= 0 ? removed : 0);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1L);
        root.put("savedAt", LocalDateTime.now().toString());
        root.put("tasks", records);

        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            writer.write(MiniJson.write(root));
        }
        try {
            Files.move(tmp, file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 任务 → 记录字段（不含密码；非终态统一存为 PAUSED） */
    private static Map<String, Object> toRecord(DownloadTask task) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", task.getId());
        record.put("url", task.getUrl());
        record.put("protocol", task.getProtocol().name());
        record.put("savePath", task.getSavePath());
        record.put("fileName", task.getFileName());
        record.put("saveFilePath", task.getSaveFilePath());
        record.put("totalSize", task.getTotalSize());
        record.put("downloadedSize", task.getDownloadedSize());
        record.put("status", persistedStatus(task));
        record.put("errorMessage", task.getErrorMessage());
        record.put("retryCount", (long) task.getRetryCount());
        record.put("startTime", task.getStartTime() != null ? task.getStartTime().toString() : null);
        record.put("completedTime", task.getCompletedTime() != null ? task.getCompletedTime().toString() : null);
        record.put("username", task.getUsername());
        return record;
    }

    private static String persistedStatus(DownloadTask task) {
        DownloadTask.TaskStatus status = task.getStatus();
        return (status == DownloadTask.TaskStatus.WAITING
                || status == DownloadTask.TaskStatus.DOWNLOADING)
                ? DownloadTask.TaskStatus.PAUSED.name()
                : status.name();
    }

    // ══════════════════════════════════════════
    //  记录 → 任务重建
    // ══════════════════════════════════════════

    /**
     * 记录 → DownloadTask（不重建 downloader/callback，由真正执行下载时创建）。
     * 关键字段缺失、协议未知时返回 null（跳过该条）。
     * 恢复状态映射：COMPLETED/FAILED 保留，其余（含 WAITING/DOWNLOADING）一律 PAUSED。
     */
    public static DownloadTask fromRecord(Map<String, Object> record) {
        String id = str(record, "id");
        String url = str(record, "url");
        String protocolName = str(record, "protocol");
        String savePath = str(record, "savePath");
        if (id == null || url == null || protocolName == null
                || savePath == null || savePath.isBlank()) {
            return null;
        }
        Protocol protocol;
        try {
            protocol = Protocol.valueOf(protocolName);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (protocol == Protocol.UNKNOWN) {
            return null;
        }

        // 密码不落盘，恢复为空（界面在继续/重试时按需补输）
        DownloadTask task = new DownloadTask(id, url, savePath, str(record, "username"), "", protocol);

        DownloadTask.TaskStatus status;
        try {
            status = DownloadTask.TaskStatus.valueOf(str(record, "status"));
        } catch (IllegalArgumentException e) {
            status = DownloadTask.TaskStatus.PAUSED;
        }
        if (status == DownloadTask.TaskStatus.FAILED) {
            task.setStatus(DownloadTask.TaskStatus.FAILED);
            task.setErrorMessage(str(record, "errorMessage"));
        } else if (status != DownloadTask.TaskStatus.COMPLETED) {
            task.setStatus(DownloadTask.TaskStatus.PAUSED);
        } else {
            task.setStatus(DownloadTask.TaskStatus.COMPLETED);
        }

        task.setFileName(str(record, "fileName"));
        task.setSaveFilePath(str(record, "saveFilePath"));
        task.setTotalSize(longOf(record, "totalSize", -1));
        task.setDownloadedSize(Math.max(0, longOf(record, "downloadedSize", 0)));
        task.setRetryCount((int) Math.max(0, longOf(record, "retryCount", 0)));
        task.setStartTime(dateTime(str(record, "startTime")));
        task.setCompletedTime(dateTime(str(record, "completedTime")));
        return task;
    }

    private static String str(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s && !s.isBlank() ? s : null;
    }

    private static long longOf(Map<String, Object> map, String key, long fallback) {
        return map.get(key) instanceof Number num ? num.longValue() : fallback;
    }

    private static LocalDateTime dateTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
