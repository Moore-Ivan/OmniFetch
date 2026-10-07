package com.downloader.plugin;

import com.downloader.config.ConfigManager;
import com.downloader.protocol.DownloadProtocol;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 插件管理器
 * 负责加载和管理第三方协议插件
 */
public class PluginManager {
    
    private static PluginManager instance;
    private final List<ProtocolPlugin> plugins;
    private final List<URLClassLoader> classLoaders;
    
    private PluginManager() {
        plugins = new ArrayList<>();
        classLoaders = new ArrayList<>();
        loadPlugins();
    }
    
    public static synchronized PluginManager getInstance() {
        if (instance == null) {
            instance = new PluginManager();
        }
        return instance;
    }
    
    /**
     * 加载插件
     */
    private void loadPlugins() {
        // 从classpath加载插件
        loadPluginsFromClasspath();
        
        // 从plugins目录加载插件
        loadPluginsFromDirectory();
    }
    
    /**
     * 从classpath加载插件
     */
    private void loadPluginsFromClasspath() {
        ServiceLoader<ProtocolPlugin> loader = ServiceLoader.load(ProtocolPlugin.class);
        for (ProtocolPlugin plugin : loader) {
            plugins.add(plugin);
            System.out.println("加载插件: " + plugin.getName() + " (支持协议: " + plugin.getProtocolName() + ")");
        }
    }
    
    /**
     * 从plugins目录加载插件
     */
    private void loadPluginsFromDirectory() {
        // 插件目录走配置（plugin.dir），与配置界面展示一致
        File pluginsDir = new File(ConfigManager.getInstance().getString("plugin.dir", "plugins"));
        if (pluginsDir.exists() && pluginsDir.isDirectory()) {
            File[] jars = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar"));
            if (jars != null) {
                for (File jar : jars) {
                    URLClassLoader classLoader = null;
                    try {
                        classLoader = new URLClassLoader(new URL[]{jar.toURI().toURL()});
                        ServiceLoader<ProtocolPlugin> loader = ServiceLoader.load(ProtocolPlugin.class, classLoader);
                        boolean found = false;
                        for (ProtocolPlugin plugin : loader) {
                            plugins.add(plugin);
                            classLoaders.add(classLoader);
                            found = true;
                            System.out.println("加载插件: " + plugin.getName() + " (支持协议: " + plugin.getProtocolName() + ")");
                        }
                        if (!found) {
                            // jar 中没有插件实现，及时释放 classLoader
                            classLoader.close();
                        }
                    } catch (Exception e) {
                        System.err.println("加载插件失败: " + jar.getName() + ", 错误: " + e.getMessage());
                        // 加载失败的 jar 关闭 classLoader，避免资源泄漏
                        if (classLoader != null) {
                            try {
                                classLoader.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 获取支持指定URL的插件
     * @param url 下载URL
     * @return 协议插件
     */
    public ProtocolPlugin getPluginForUrl(String url) {
        for (ProtocolPlugin plugin : plugins) {
            if (plugin.canHandle(url)) {
                return plugin;
            }
        }
        return null;
    }
    
    /**
     * 创建下载器实例
     * @param url 下载URL
     * @param savePath 保存路径
     * @param username 用户名
     * @param password 密码
     * @param callback 进度回调
     * @param downloadedSize 已下载大小
     * @param saveFilePath 保存文件路径
     * @return 下载器实例
     * @throws Exception 异常
     */
    public DownloadProtocol createDownloader(String url, String savePath, String username, String password,
                                           Object callback, long downloadedSize, String saveFilePath) throws Exception {
        // 插件功能开关走配置（plugin.enabled），关闭时不走插件下载
        if (!ConfigManager.getInstance().getBoolean("plugin.enabled", true)) {
            return null;
        }
        ProtocolPlugin plugin = getPluginForUrl(url);
        if (plugin != null) {
            return plugin.createDownloader(url, savePath, username, password, 
                                         (com.downloader.model.ProgressCallback) callback, 
                                         downloadedSize, saveFilePath);
        }
        return null;
    }
    
    /**
     * 获取所有已加载的插件
     * @return 插件列表
     */
    public List<ProtocolPlugin> getPlugins() {
        return new ArrayList<>(plugins);
    }
    
    /**
     * 刷新插件列表
     */
    public void refreshPlugins() {
        plugins.clear();
        classLoaders.forEach(classLoader -> {
            try {
                classLoader.close();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        });
        classLoaders.clear();
        loadPlugins();
    }
    
    /**
     * 关闭插件管理器
     */
    public void close() {
        classLoaders.forEach(classLoader -> {
            try {
                classLoader.close();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        });
        plugins.clear();
        classLoaders.clear();
    }
}
