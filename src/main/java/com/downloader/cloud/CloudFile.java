package com.downloader.cloud;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网盘分享中的一个文件或目录节点。
 */
public final class CloudFile {

    private final String id;
    private String parentId = "";
    private String name;
    private long size = -1;
    private boolean folder;
    /** 展示用相对路径（如 /相册/2026/） */
    private String path = "/";
    /** 平台私有下载所需字段（如 123 的 etag/s3keyFlag，115 的 sha 等） */
    private final Map<String, Object> extra = new LinkedHashMap<>();

    public CloudFile(String id, String name, long size, boolean folder) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.size = size;
        this.folder = folder;
    }

    public String id() {
        return id;
    }

    public String parentId() {
        return parentId;
    }

    public CloudFile parentId(String value) {
        this.parentId = value == null ? "" : value;
        return this;
    }

    public String name() {
        return name;
    }

    public CloudFile name(String value) {
        this.name = value == null ? "" : value;
        return this;
    }

    public long size() {
        return size;
    }

    public CloudFile size(long value) {
        this.size = value;
        return this;
    }

    public boolean folder() {
        return folder;
    }

    public CloudFile folder(boolean value) {
        this.folder = value;
        return this;
    }

    public String path() {
        return path;
    }

    public CloudFile path(String value) {
        this.path = value == null ? "/" : value;
        return this;
    }

    public Map<String, Object> extra() {
        return extra;
    }

    public CloudFile put(String key, Object value) {
        extra.put(key, value);
        return this;
    }

    /** 该文件在同级目录中的展示路径（目录名以 / 结尾） */
    public String displayPath() {
        String base = path == null || path.isEmpty() ? "/" : path;
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base + name + (folder ? "/" : "");
    }

    @Override
    public String toString() {
        return (folder ? "[目录] " : "[文件] ") + name + " (" + size + ")";
    }
}
