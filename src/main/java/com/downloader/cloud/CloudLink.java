package com.downloader.cloud;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网盘解析得到的最终下载直链及下载时必需的请求头。
 * <p>直链通常带签名且有效期较短，任务每次开始下载都应重新解析。
 */
public final class CloudLink {

    private final String url;
    private final Map<String, String> headers;

    public CloudLink(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = (headers == null || headers.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public String url() {
        return url;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
