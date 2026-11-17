package com.downloader.cloud;

import com.downloader.cloud.providers.*;

import java.util.List;

/**
 * 网盘解析器注册表：链接识别、平台查找的统一入口。
 */
public final class CloudRegistry {

    private static final List<CloudProvider> PROVIDERS = List.of(
            new QuarkProvider(),
            new UcProvider(),
            new XunleiProvider(),
            new BaiduProvider(),
            new Yun139Provider(),
            new Pan123Provider(),
            new Pan115Provider(),
            new GuangYaProvider(),
            new ILanzouProvider(),   // 优享版域名与普通蓝奏不重叠，但先匹配更具体的
            new LanzouProvider()
    );

    private CloudRegistry() {
    }

    /** 返回识别到该文本的解析器；不属于任何网盘时返回 null */
    public static CloudProvider detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (CloudProvider provider : PROVIDERS) {
            try {
                if (provider.matches(text)) {
                    return provider;
                }
            } catch (Exception ignored) {
                // 单个解析器的正则异常不应影响其他平台识别
            }
        }
        return null;
    }

    public static CloudProvider byDrive(CloudDrive drive) {
        for (CloudProvider provider : PROVIDERS) {
            if (provider.drive() == drive) {
                return provider;
            }
        }
        return null;
    }

    public static boolean isSupported(String text) {
        return detect(text) != null || CloudTaskCodec.isCloudTask(text);
    }
}
