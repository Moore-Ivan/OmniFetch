package com.downloader.util;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;

/**
 * 文件操作工具类
 */
public final class FileUtils {

    private FileUtils() {}

    /**
     * 构建保存文件路径，自动创建目录，自动处理重名
     */
    public static File buildSaveFile(String dir, String fileName) {
        File d = new File(dir);
        if (!d.exists()) {
            d.mkdirs();
        }

        File f = new File(d, fileName);
        if (!f.exists()) {
            return f;
        }

        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }

        int i = 1;
        while (f.exists()) {
            f = new File(d, base + " (" + i + ")" + ext);
            i++;
        }
        return f;
    }

    /**
     * 从 HTTP 响应头和 URL 中提取文件名
     */
    public static String extractFileName(String contentDisposition, String url) {
        // 从 Content-Disposition 提取
        if (contentDisposition != null && contentDisposition.contains("filename=")) {
            String name = contentDisposition
                    .substring(contentDisposition.indexOf("filename=") + 9)
                    .replaceAll("[\"';]", "")
                    .trim();
            if (!name.isEmpty()) {
                return name;
            }
        }

        // 从 URL 路径提取
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path != null) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isEmpty() && name.contains(".")) {
                    return URLDecoder.decode(name, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {}

        return "download_" + System.currentTimeMillis();
    }

    /**
     * 安静关闭 Closeable
     */
    public static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 递归删除目录
     */
    public static void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ignored) {}
    }

    /**
     * 解析 URL 中的主机名
     */
    public static String extractHost(String url) throws URISyntaxException {
        return new URI(url).getHost();
    }

    /**
     * 解析 URL 中的端口，提供默认值
     */
    public static int extractPort(String url, int defaultPort)
            throws URISyntaxException {
        int port = new URI(url).getPort();
        return port > 0 ? port : defaultPort;
    }

    /**
     * 解析 URL 中的路径
     */
    public static String extractPath(String url) throws URISyntaxException {
        return new URI(url).getPath();
    }

    /**
     * 解析 URL 中的用户名
     */
    public static String extractUser(String url, String defaultUser)
            throws URISyntaxException {
        String userInfo = new URI(url).getUserInfo();
        if (userInfo != null && userInfo.contains(":")) {
            return userInfo.split(":")[0];
        }
        return defaultUser;
    }

    /**
     * 解析 URL 中的密码
     */
    public static String extractPass(String url, String defaultPass)
            throws URISyntaxException {
        String userInfo = new URI(url).getUserInfo();
        if (userInfo != null && userInfo.contains(":")) {
            return userInfo.split(":")[1];
        }
        return defaultPass;
    }

    /**
     * 格式化字节数为可读字符串
     */
    public static String formatSize(long bytes) {
        if (bytes < 0) return "--";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1_048_576) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1_073_741_824L) return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%.2f GB", bytes / 1_073_741_824.0);
    }
}
