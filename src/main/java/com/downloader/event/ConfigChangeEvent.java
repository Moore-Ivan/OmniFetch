package com.downloader.event;

/**
 * 配置变更事件
 * 当配置文件更新时发布此事件
 */
public class ConfigChangeEvent {
    
    private final String configFile;
    
    public ConfigChangeEvent(String configFile) {
        this.configFile = configFile;
    }
    
    public String getConfigFile() {
        return configFile;
    }
}