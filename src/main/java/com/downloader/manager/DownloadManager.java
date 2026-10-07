package com.downloader.manager;

import com.downloader.config.ConfigManager;
import com.downloader.event.ConfigChangeEvent;
import com.downloader.event.ConfigChangeListener;
import com.downloader.event.DownloadEvent;
import com.downloader.event.EventBus;
import com.downloader.factory.DownloaderFactory;
import com.downloader.model.DownloadTask;
import com.downloader.model.ProgressCallback;
import com.downloader.detector.ProtocolDetector;
import com.downloader.protocol.DownloadProtocol;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 下载管理器
 * 负责管理任务队列和线程池，处理任务的提交、执行、暂停、取消等操作
 */
public class DownloadManager implements ConfigChangeListener {

    private static DownloadManager instance;
    
    private final BlockingQueue<DownloadTask> taskQueue;
    private final ExecutorService threadPool;
    private final ScheduledExecutorService retryScheduler;
    private final Set<DownloadTask> runningTasks;
    private final Map<String, DownloadTask> taskMap;

    private final AtomicInteger taskIdGenerator = new AtomicInteger(1);
    private final ConfigManager configManager = ConfigManager.getInstance();
    private final EventBus eventBus = EventBus.getInstance();

    // 从配置中获取最大并发任务数
    private int getMaxConcurrentTasks() {
        return configManager.getInt("download.maxConcurrentTasks", 5);
    }

    // 从配置中获取最大线程数
    private int getMaxThreads() {
        return configManager.getInt("download.maxThreads", 20);
    }

    // 从配置中获取失败自动重试次数
    private int getMaxRetryCount() {
        return configManager.getInt("download.maxRetryCount", 3);
    }

    private DownloadManager() {
        taskQueue = new LinkedBlockingQueue<>();
        threadPool = new ThreadPoolExecutor(
                getMaxConcurrentTasks(),
                getMaxThreads(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        // 重试退避定时器：独立于下载线程池，避免重试等待占用下载线程造成饥饿
        retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "retry-backoff");
            t.setDaemon(true);
            return t;
        });
        runningTasks = ConcurrentHashMap.newKeySet();
        taskMap = new ConcurrentHashMap<>();

        // 注册为配置变更监听器
        EventBus.getInstance().registerConfigChangeListener(this);

        // 启动任务处理器
        startTaskProcessor();
    }

    public static synchronized DownloadManager getInstance() {
        if (instance == null) {
            instance = new DownloadManager();
        }
        return instance;
    }

    private void startTaskProcessor() {
        Thread processor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (runningTasks.size() < getMaxConcurrentTasks()) {
                        DownloadTask task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (task != null) {
                            executeTask(task);
                        }
                    } else {
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        // 改为非守护线程，确保任务处理器能够一直运行
        processor.setDaemon(false);
        processor.start();
    }

    public String addTask(String url, String savePath, String username, String password) {
        String taskId = "task_" + taskIdGenerator.getAndIncrement();
        DownloadTask task = new DownloadTask(
                taskId,
                url,
                savePath,
                username,
                password,
                ProtocolDetector.detect(url)
        );
        
        taskMap.put(taskId, task);
        taskQueue.offer(task);
        
        // 发布任务添加事件
        eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_ADDED, task));
        
        return taskId;
    }

    private void executeTask(DownloadTask task) {
        runningTasks.add(task);
        task.setStatus(DownloadTask.TaskStatus.DOWNLOADING);
        task.setStartTime(LocalDateTime.now());
        
        // 创建进度回调
        ProgressCallback callback = new ProgressCallback() {
            @Override
            public void onConnected(String fileName, long totalSize) {
                task.setFileName(fileName);
                task.setTotalSize(totalSize);
                // 构建保存文件路径并保存
                java.io.File saveFile = new java.io.File(task.getSavePath(), fileName);
                task.setSaveFilePath(saveFile.getAbsolutePath());
                notifyTaskUpdated(task);
            }

            @Override
            public void onProgress(long dl, long total, long speed) {
                long current = task.getDownloadedSize();
                if (dl < current) {
                    // 新一轮尝试的累计字节数小于任务已记录值（如分片下载整体重下），直接重置基数
                    task.setDownloadedSize(dl);
                } else if (dl > current) {
                    task.addDownloadedSize(dl - current);
                }
                task.setSpeed(speed);
                notifyTaskUpdated(task);
            }

            @Override
            public void onStatusUpdate(String msg) {
                // 可以添加状态消息处理
            }

            @Override
            public void onComplete() {
                // 确保下载完成时进度显示100%
                if (task.getTotalSize() > 0) {
                    task.setDownloadedSize(task.getTotalSize());
                }
                task.setStatus(DownloadTask.TaskStatus.COMPLETED);
                task.setCompletedTime(LocalDateTime.now());
                task.setSpeed(0);
                runningTasks.remove(task);
                task.close();
                notifyTaskUpdated(task);

                // 发布任务完成事件
                eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_COMPLETED, task));
            }

            @Override
            public void onError(String msg) {
                handleTaskFailure(task, msg);
            }
        };
        
        task.setCallback(callback);
        
        // 提交到线程池执行
        threadPool.submit(() -> {
            try {
                DownloadProtocol downloader = DownloaderFactory.create(
                        task.getProtocol(),
                        task.getUrl(),
                        task.getSavePath(),
                        task.getUsername(),
                        task.getPassword(),
                        callback,
                        task.getDownloadedSize(),
                        task.getSaveFilePath()
                );
                task.setDownloader(downloader);
                downloader.download();
                // 兜底：下载器正常返回但既未回调完成、也不是暂停/取消/失败，
                // 说明有静默异常，标记失败走重试，避免任务永久卡在"下载中"
                if (task.getStatus() == DownloadTask.TaskStatus.DOWNLOADING) {
                    handleTaskFailure(task, "下载未正常完成（下载器提前返回）");
                }
            } catch (InterruptedException e) {
                // 检查任务是否被暂停
                if (task.getStatus() == DownloadTask.TaskStatus.PAUSED) {
                    runningTasks.remove(task);
                    task.close();
                    notifyTaskUpdated(task);
                } else {
                    handleTaskFailure(task, "下载被中断: " + e.getMessage());
                }
            } catch (Exception e) {
                // 检查任务是否被取消、暂停或已完成
                if (task.getStatus() != DownloadTask.TaskStatus.PAUSED && 
                    task.getStatus() != DownloadTask.TaskStatus.FAILED && 
                    task.getStatus() != DownloadTask.TaskStatus.COMPLETED) {
                    handleTaskFailure(task, e.getMessage());
                }
            }
        });
        
        notifyTaskUpdated(task);
    }
    
    /**
     * 处理任务失败，实现指数退避重试（重试次数上限由配置 download.maxRetryCount 决定）
     */
    private void handleTaskFailure(DownloadTask task, String errorMessage) {
        runningTasks.remove(task);
        task.close();

        if (task.getRetryCount() < getMaxRetryCount()) {
            // 计算指数退避时间（1秒、2秒、4秒...）
            int retryCount = task.getRetryCount();
            long backoffTime = (long) Math.pow(2, retryCount) * 1000;

            task.incrementRetryCount();
            task.setStatus(DownloadTask.TaskStatus.WAITING);
            task.setErrorMessage(errorMessage + " (将在 " + (backoffTime / 1000) + "秒后重试，第 " + task.getRetryCount() + " 次)");

            // 延迟后重新加入队列（独立调度线程，不占用下载线程池）
            retryScheduler.schedule(() -> {
                if (task.getStatus() == DownloadTask.TaskStatus.WAITING) {
                    taskQueue.offer(task);
                    notifyTaskUpdated(task);
                    // 发布任务重试事件
                    eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_RETRYING, task, errorMessage));
                }
            }, backoffTime, TimeUnit.MILLISECONDS);
        } else {
            // 达到最大重试次数，标记为失败
            task.setStatus(DownloadTask.TaskStatus.FAILED);
            task.setErrorMessage(errorMessage + " (已达到最大重试次数)");
            notifyTaskUpdated(task);
            // 发布任务失败事件
            eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_FAILED, task, errorMessage));
        }
    }

    public void pauseTask(String taskId) {
        DownloadTask task = taskMap.get(taskId);
        if (task != null && task.getStatus() == DownloadTask.TaskStatus.DOWNLOADING) {
            task.pause();
            runningTasks.remove(task);
            notifyTaskUpdated(task);
            // 发布任务暂停事件
            eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_PAUSED, task));
        }
    }

    public void resumeTask(String taskId) {
        DownloadTask task = taskMap.get(taskId);
        if (task != null && task.getStatus() == DownloadTask.TaskStatus.PAUSED) {
            task.setStatus(DownloadTask.TaskStatus.WAITING);
            taskQueue.offer(task);
            notifyTaskUpdated(task);
            // 发布任务恢复事件
            eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_RESUMED, task));
        }
    }

    public void cancelTask(String taskId) {
        DownloadTask task = taskMap.get(taskId);
        if (task != null) {
            task.cancel();
            runningTasks.remove(task);
            taskQueue.remove(task);
            task.close();
            notifyTaskUpdated(task);
        }
    }

    public void removeTask(String taskId) {
        DownloadTask task = taskMap.remove(taskId);
        if (task != null) {
            runningTasks.remove(task);
            taskQueue.remove(task);
            task.close();
            // 发布任务移除事件
            eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_REMOVED, task));
        }
    }

    public List<DownloadTask> getAllTasks() {
        return new ArrayList<>(taskMap.values());
    }

    public DownloadTask getTask(String taskId) {
        return taskMap.get(taskId);
    }

    private void notifyTaskUpdated(DownloadTask task) {
        // 发布任务更新事件
        eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_UPDATED, task));
    }

    public void setTaskListener(TaskListener listener) {
        // 保持向后兼容，将旧的TaskListener包装为DownloadListener
        if (listener != null) {
            eventBus.register(new DownloadListenerAdapter(listener));
        }
    }
    
    /**
     * 适配旧的TaskListener到新的DownloadListener接口
     */
    private static class DownloadListenerAdapter implements com.downloader.event.DownloadListener {
        private final TaskListener taskListener;
        
        public DownloadListenerAdapter(TaskListener taskListener) {
            this.taskListener = taskListener;
        }
        
        @Override
        public void onDownloadEvent(DownloadEvent event) {
            switch (event.getType()) {
                case TASK_ADDED:
                    taskListener.onTaskAdded(event.getTask());
                    break;
                case TASK_UPDATED:
                    taskListener.onTaskUpdated(event.getTask());
                    break;
                case TASK_REMOVED:
                    taskListener.onTaskRemoved(event.getTask().getId());
                    break;
                default:
                    // 其他事件类型忽略
                    break;
            }
        }
    }

    public void shutdown() {
        // 停止重试退避调度
        retryScheduler.shutdownNow();

        // 取消所有正在执行的任务
        for (DownloadTask task : runningTasks) {
            task.cancel();
            task.close();
        }
        runningTasks.clear();
        
        // 清空任务队列
        taskQueue.clear();
        
        // 立即关闭线程池
        threadPool.shutdownNow();
        try {
            // 减少等待时间，避免退出卡顿
            threadPool.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public interface TaskListener {
        void onTaskAdded(DownloadTask task);
        void onTaskUpdated(DownloadTask task);
        void onTaskRemoved(String taskId);
    }
    
    @Override
    public void onConfigChange(ConfigChangeEvent event) {
        System.out.println("配置文件变更: " + event.getConfigFile());
        // 重新配置线程池大小
        int newMaxConcurrentTasks = getMaxConcurrentTasks();
        int newMaxThreads = getMaxThreads();
        
        System.out.println("更新线程池配置: 最大并发任务数=" + newMaxConcurrentTasks + ", 最大线程数=" + newMaxThreads);
        
        // 这里可以添加线程池重新配置的逻辑
        // 注意：ThreadPoolExecutor 的核心线程数和最大线程数是可以动态调整的
        if (threadPool instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) threadPool;
            executor.setCorePoolSize(newMaxConcurrentTasks);
            executor.setMaximumPoolSize(newMaxThreads);
        }
    }
}