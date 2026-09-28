package com.downloader.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 版本信息工具类
 * <p>
 * 从 classpath 下的 version.properties 读取版本号，该文件由 Gradle 在
 * processResources 阶段用 build.gradle 中的 cfgVersion 填充，确保打包后的
 * 应用、安装包版本号与构建脚本中的版本号完全一致。
 * <p>
 * 这样整个项目只有一个版本号来源 (build.gradle 的 cfgVersion)，避免多处硬编码
 * 不同步的问题，更新检查也基于此版本号进行。
 */
public final class VersionInfo {

    private static final String VERSION;

    static {
        Properties props = new Properties();
        try (InputStream in = VersionInfo.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            // 忽略异常，降级为 unknown
        }
        VERSION = props.getProperty("app.version", "unknown");
    }

    private VersionInfo() {
    }

    /**
     * @return 应用版本号，如 "1.0.0"
     */
    public static String getVersion() {
        return VERSION;
    }

    /**
     * @return 带 v 前缀的版本号，如 "v1.0.0"
     */
    public static String getDisplayVersion() {
        return "v" + VERSION;
    }
}
