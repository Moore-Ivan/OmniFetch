package com.downloader.config;

import com.downloader.event.ConfigChangeEvent;
import com.downloader.event.EventBus;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 配置管理器
 * 启动时加载 config/application.properties；文件不存在时自动生成默认配置。
 * 所有数值配置项均带阈值，读取与保存时自动钳制，避免出现不合理的超大/超小值。
 */
public class ConfigManager {

    /** 配置项类型 */
    public enum ConfigType { NUMBER, BOOL, STRING }

    /** 配置项元数据（标签、分组、阈值、默认值） */
    public static final class ConfigItem {
        public final String key;
        public final String label;
        public final String group;
        public final String unit;
        public final ConfigType type;
        public final long min;
        public final long max;
        public final long step;
        public final String defaultValue;

        private ConfigItem(String group, String key, String label, String unit, ConfigType type,
                           long min, long max, long step, String defaultValue) {
            this.group = group;
            this.key = key;
            this.label = label;
            this.unit = unit;
            this.type = type;
            this.min = min;
            this.max = max;
            this.step = step;
            this.defaultValue = defaultValue;
        }

        public long getDefaultAsLong() {
            return Long.parseLong(defaultValue);
        }
    }

    // ── 配置项注册表（顺序即文件与界面中的展示顺序，是默认值/阈值的单一事实来源） ──
    private static final List<ConfigItem> ITEMS = new ArrayList<>();
    private static final Map<String, ConfigItem> ITEM_MAP = new HashMap<>();

    private static ConfigItem number(String group, String key, String label, String unit,
                                     long min, long max, long step, long def) {
        ConfigItem item = new ConfigItem(group, key, label, unit, ConfigType.NUMBER,
                min, max, step, Long.toString(def));
        register(item);
        return item;
    }

    private static void bool(String group, String key, String label, boolean def) {
        register(new ConfigItem(group, key, label, null, ConfigType.BOOL, 0, 0, 1,
                Boolean.toString(def)));
    }

    private static void text(String group, String key, String label, String def) {
        register(new ConfigItem(group, key, label, null, ConfigType.STRING, 0, 0, 1, def));
    }

    private static void register(ConfigItem item) {
        ITEMS.add(item);
        ITEM_MAP.put(item.key, item);
    }

    static {
        // 下载配置
        number("下载配置", "download.maxConcurrentTasks", "最大并发任务数", "个", 1, 20, 1, 5);
        number("下载配置", "download.maxThreads", "最大线程数", "个", 1, 256, 1, 20);
        number("下载配置", "download.bufferSize", "缓冲区大小", "字节", 4096, 16_777_216, 4096, 262_144);
        number("下载配置", "download.connectTimeoutSec", "连接超时", "秒", 1, 300, 1, 15);
        number("下载配置", "download.progressIntervalMs", "进度更新间隔", "毫秒", 50, 5_000, 50, 200);
        number("下载配置", "download.flushIntervalMs", "磁盘刷新间隔", "毫秒", 1_000, 120_000, 1_000, 10_000);
        // HTTP配置
        number("HTTP配置", "http.maxThreads", "HTTP最大线程数", "个", 1, 128, 1, 16);
        number("HTTP配置", "http.minChunkSize", "最小分片大小", "字节", 65_536, 104_857_600, 65_536, 5_242_880);
        number("HTTP配置", "http.maxChunkSize", "最大分片大小", "字节", 1_048_576, 536_870_912, 1_048_576, 52_428_800);
        // FTP/SFTP配置
        number("FTP/SFTP配置", "ftp.maxConnections", "最大连接数", "个", 1, 50, 1, 10);
        number("FTP/SFTP配置", "ftp.connectionTimeoutSec", "连接超时", "秒", 1, 300, 1, 30);
        number("FTP/SFTP配置", "ftp.idleTimeoutSec", "空闲超时", "秒", 5, 600, 5, 60);
        // 插件配置
        bool("插件配置", "plugin.enabled", "是否启用插件", true);
        text("插件配置", "plugin.dir", "插件目录", "plugins");
    }

    private static ConfigManager instance;
    private final Map<String, Object> configMap;
    private final ScheduledExecutorService scheduler;
    private long lastModifiedTime;
    private Path configPath;

    private ConfigManager() {
        configMap = new HashMap<>();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "config-watcher");
            t.setDaemon(true);
            return t;
        });
        loadConfig();
        startConfigWatcher();
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    /** 全部配置项元数据（按注册顺序） */
    public List<ConfigItem> getConfigItems() {
        return ITEMS;
    }

    /** 配置文件路径（程序根目录下 config/application.properties） */
    public Path getConfigPath() {
        return configPath;
    }

    /**
     * 加载配置文件：不存在则在程序根目录自动生成 config/application.properties。
     */
    private void loadConfig() {
        Path propsPath = Paths.get("config", "application.properties");
        try {
            if (!Files.exists(propsPath)) {
                Files.createDirectories(propsPath.getParent());
                writeConfigFile(propsPath, defaultProperties());
                System.out.println("未找到配置文件，已生成默认配置: " + propsPath.toAbsolutePath());
            }
            loadPropertiesConfig(propsPath);
            configPath = propsPath;
        } catch (Exception e) {
            System.err.println("初始化配置文件失败，改用内存默认配置: " + e.getMessage());
            loadDefaultConfig();
        }
    }

    /**
     * 加载Properties配置文件（UTF-8）
     */
    private void loadPropertiesConfig(Path path) {
        try (java.io.Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);
            configMap.clear();
            for (String key : props.stringPropertyNames()) {
                configMap.put(key, props.getProperty(key));
            }
            lastModifiedTime = Files.getLastModifiedTime(path).toMillis();
            System.out.println("加载配置文件: " + path);
        } catch (Exception e) {
            System.err.println("加载配置文件失败: " + e.getMessage());
            loadDefaultConfig();
        }
    }

    /**
     * 加载默认配置（兜底，正常流程下启动时已生成配置文件）
     */
    private void loadDefaultConfig() {
        configMap.clear();
        for (ConfigItem item : ITEMS) {
            configMap.put(item.key, item.defaultValue);
        }
        configPath = Paths.get("config", "application.properties");
    }

    private Properties defaultProperties() {
        Properties props = new Properties();
        for (ConfigItem item : ITEMS) {
            props.setProperty(item.key, item.defaultValue);
        }
        return props;
    }

    /**
     * 将配置按分组与注释格式渲染并写入文件（UTF-8，保留中文注释）
     */
    private void writeConfigFile(Path path, Properties props) throws IOException {
        StringBuilder sb = new StringBuilder();
        String lastGroup = null;
        for (ConfigItem item : ITEMS) {
            if (!item.group.equals(lastGroup)) {
                if (lastGroup != null) {
                    sb.append(System.lineSeparator());
                }
                sb.append("# ").append(item.group).append(System.lineSeparator());
                lastGroup = item.group;
            }
            sb.append("# ").append(item.label);
            if (item.unit != null) {
                sb.append("（").append(item.unit).append('）');
            }
            sb.append(System.lineSeparator());
            String value = props.getProperty(item.key, item.defaultValue);
            sb.append(item.key).append('=').append(value).append(System.lineSeparator());
        }
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(sb.toString());
        }
    }

    /**
     * 保存界面配置：数值先按阈值钳制，再写文件并热加载、发布变更事件。
     *
     * @param newValues 配置项键值（均为字符串）
     */
    public void saveConfig(Map<String, String> newValues) throws IOException {
        Properties props = new Properties();
        for (ConfigItem item : ITEMS) {
            String raw = newValues.get(item.key);
            String value = (raw != null) ? raw.trim() : getString(item.key, item.defaultValue);
            if (item.type == ConfigType.NUMBER) {
                value = Long.toString(clamp(parseLongSafe(value, item.getDefaultAsLong()), item));
            } else if (item.type == ConfigType.BOOL) {
                value = Boolean.toString(Boolean.parseBoolean(value));
            } else if (value.isEmpty()) {
                value = item.defaultValue;
            }
            props.setProperty(item.key, value);
        }

        Files.createDirectories(configPath.getParent());
        writeConfigFile(configPath, props);
        loadPropertiesConfig(configPath);
        // 通知监听者（如下载管理器线程池）热更新
        EventBus.getInstance().publishConfigChangeEvent(new ConfigChangeEvent(configPath.toString()));
    }

    /**
     * 启动配置文件监视器
     */
    private void startConfigWatcher() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (configPath != null && Files.exists(configPath)) {
                try {
                    long currentModifiedTime = Files.getLastModifiedTime(configPath).toMillis();
                    if (currentModifiedTime > lastModifiedTime) {
                        System.out.println("配置文件已更新，重新加载...");
                        loadPropertiesConfig(configPath);
                        EventBus.getInstance().publishConfigChangeEvent(
                                new ConfigChangeEvent(configPath.toString()));
                    }
                } catch (Exception e) {
                    System.err.println("监控配置文件失败: " + e.getMessage());
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    // ── 取值（数值均按注册阈值钳制） ──

    public Object get(String key) {
        return configMap.get(key);
    }

    public String getString(String key, String defaultValue) {
        Object value = configMap.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object value = configMap.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.toString());
            ConfigItem item = ITEM_MAP.get(key);
            if (item != null && item.type == ConfigType.NUMBER) {
                parsed = clamp(parsed, item);
            }
            return (int) parsed;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        Object value = configMap.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value.toString());
            ConfigItem item = ITEM_MAP.get(key);
            if (item != null && item.type == ConfigType.NUMBER) {
                parsed = clamp(parsed, item);
            }
            return parsed;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = configMap.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static long parseLongSafe(String s, long fallback) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long clamp(long v, ConfigItem item) {
        return Math.max(item.min, Math.min(item.max, v));
    }

    /**
     * 关闭配置管理器
     */
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
