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

    /** 任务队列必须有界：无界队列会让 ThreadPoolExecutor 的 maximumPoolSize 永远不生效 */
    private static final int TASK_QUEUE_CAPACITY = 1000;

    private final BlockingQueue<DownloadTask> taskQueue;
    private final ExecutorService threadPool;
    private final ScheduledExecutorService retryScheduler;
    private final Set<DownloadTask> runningTasks;
    private final Map<String, DownloadTask> taskMap;
    /** 任务插入顺序（ConcurrentHashMap 不保证迭代顺序，界面会跳动） */
    private final List<String> taskIdOrder = new CopyOnWriteArrayList<>();
    /** 任务处理器线程引用，shutdown 时需要显式中断 */
    private volatile Thread taskProcessorThread;

    private final AtomicInteger taskIdGenerator = new AtomicInteger(1);
    private final ConfigManager configManager = ConfigManager.getInstance();
    private final EventBus eventBus = EventBus.getInstance();
    /** 历史下载记录持久化（config/history.json） */
    private final HistoryStore historyStore;
    /** 启动时从历史记录恢复的任务数（界面用于提示） */
    private volatile int restoredTaskCount = 0;

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
        taskQueue = new LinkedBlockingQueue<>(TASK_QUEUE_CAPACITY);
        int corePoolSize = Math.max(1,
                Math.min(getMaxConcurrentTasks(), getMaxThreads()));
        threadPool = new ThreadPoolExecutor(
                corePoolSize,
                Math.max(1, getMaxThreads()),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(TASK_QUEUE_CAPACITY),
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

        // 历史记录持久化：启动时加载恢复，状态变化后定时落盘
        historyStore = new HistoryStore(this::getAllTasks);

        // 注册为配置变更监听器
        EventBus.getInstance().registerConfigChangeListener(this);

        // 启动任务处理器
        startTaskProcessor();

        // 恢复历史任务（默认暂停，不自动入队下载）
        restoreHistory();
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
        }, "task-processor");
        taskProcessorThread = processor;
        // 改为非守护线程，确保任务处理器能够一直运行；shutdown() 时显式中断
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
        
        // 有界队列：极端积压时明确失败，避免任务进了列表却永远不会被调度
        if (!taskQueue.offer(task)) {
            throw new IllegalStateException(
                    "下载任务队列已满（上限 " + TASK_QUEUE_CAPACITY + "），请先清理任务后再试");
        }
        taskMap.put(taskId, task);
        taskIdOrder.add(taskId);

        // 发布任务添加事件
        eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_ADDED, task));
        historyStore.markDirty();

        return taskId;
    }

    /**
     * 启动时从 config/history.json 恢复历史任务：
     * COMPLETED/FAILED 原样保留（失败任务保留错误信息），其余一律恢复为 PAUSED；
     * 任务 ID 序号从历史最大值继续，避免与恢复的任务冲突。
     */
    private void restoreHistory() {
        try {
            List<Map<String, Object>> records = historyStore.load();
            int maxId = 0;
            int count = 0;
            for (Map<String, Object> record : records) {
                DownloadTask task = HistoryStore.fromRecord(record);
                if (task == null) {
                    continue;
                }
                taskMap.put(task.getId(), task);
                taskIdOrder.add(task.getId());
                maxId = Math.max(maxId, parseTaskIdSuffix(task.getId()));
                count++;
            }
            if (maxId > 0) {
                taskIdGenerator.set(maxId + 1);
            }
            restoredTaskCount = count;
            if (count > 0) {
                System.out.println("已恢复 " + count + " 条历史下载记录");
            }
        } catch (Exception e) {
            System.err.println("恢复历史下载记录失败: " + e.getMessage());
        }
    }

    /** 解析 "task_N" 形式 ID 的数字后缀（非该格式返回 0） */
    private static int parseTaskIdSuffix(String id) {
        if (id == null || !id.startsWith("task_")) {
            return 0;
        }
        try {
            return Integer.parseInt(id.substring(5));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 启动时恢复的历史任务数（无恢复时为 0） */
    public int getRestoredTaskCount() {
        return restoredTaskCount;
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
                    if (!taskQueue.offer(task)) {
                        // 队列满（极端积压）：直接标记失败而不是静默丢弃
                        task.setStatus(DownloadTask.TaskStatus.FAILED);
                        task.setErrorMessage("任务队列已满，重试无法入队");
                        notifyTaskUpdated(task);
                        eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_FAILED, task, "任务队列已满"));
                        return;
                    }
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
        if (task == null) {
            return;
        }
        DownloadTask.TaskStatus status = task.getStatus();
        // 暂停任务继续；失败任务手动重试（清空错误、重置重试计数重新入队）
        if (status != DownloadTask.TaskStatus.PAUSED
                && status != DownloadTask.TaskStatus.FAILED) {
            return;
        }
        if (status == DownloadTask.TaskStatus.FAILED) {
            task.resetRetryCount();
            task.setErrorMessage(null);
        }
        task.setStatus(DownloadTask.TaskStatus.WAITING);
        if (!taskQueue.offer(task)) {
            task.setStatus(DownloadTask.TaskStatus.FAILED);
            task.setErrorMessage("任务队列已满，无法开始任务");
            notifyTaskUpdated(task);
            return;
        }
        notifyTaskUpdated(task);
        // 发布任务恢复事件
        eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_RESUMED, task));
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
        taskIdOrder.remove(taskId);
        if (task != null) {
            runningTasks.remove(task);
            taskQueue.remove(task);
            task.close();
            // 发布任务移除事件
            eventBus.publish(new DownloadEvent(DownloadEvent.EventType.TASK_REMOVED, task));
            historyStore.markDirty();
        }
    }

    /** 按任务添加顺序返回（ConcurrentHashMap 自身顺序不确定） */
    public List<DownloadTask> getAllTasks() {
        List<DownloadTask> ordered = new ArrayList<>(taskIdOrder.size());
        for (String id : taskIdOrder) {
            DownloadTask task = taskMap.get(id);
            if (task != null) {
                ordered.add(task);
            }
        }
        return ordered;
    }

    public DownloadTask getTask(String taskId) {
        return taskMap.get(taskId);
    }

    private void notifyTaskUpdated(DownloadTask task) {
        // 高频进度更新只打脏标记（volatile 写），由 history-saver 每 2 秒节流落盘
        historyStore.markDirty();
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
        // 先落盘历史记录（此时 DOWNLOADING 状态仍真实，保存时映射为 PAUSED），再取消任务，
        // 避免"取消"把任务标成 FAILED 后被当作真实失败写入历史
        historyStore.shutdown();

        // 停止重试退避调度
        retryScheduler.shutdownNow();

        // 中断任务处理器线程（非守护线程，不中断会阻止 JVM 退出）
        Thread processor = taskProcessorThread;
        if (processor != null) {
            processor.interrupt();
        }

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
        // 重新配置线程池大小
        int newMaxConcurrentTasks = Math.max(1, getMaxConcurrentTasks());
        int newMaxThreads = Math.max(1, getMaxThreads());

        // 外部直接编辑配置可能产生 core > max，钳制到合法关系（界面保存有交叉校验，这里兜底）
        if (newMaxConcurrentTasks > newMaxThreads) {
            newMaxConcurrentTasks = newMaxThreads;
        }

        // ThreadPoolExecutor 始终要求 core <= max，扩容与缩容的调整顺序相反：
        // 扩容先抬 max，缩容先压 core，否则会抛 IllegalArgumentException
        if (threadPool instanceof ThreadPoolExecutor executor) {
            try {
                if (newMaxThreads >= executor.getCorePoolSize()) {
                    executor.setMaximumPoolSize(newMaxThreads);
                    executor.setCorePoolSize(newMaxConcurrentTasks);
                } else {
                    executor.setCorePoolSize(newMaxConcurrentTasks);
                    executor.setMaximumPoolSize(newMaxThreads);
                }
            } catch (IllegalArgumentException e) {
                System.err.println("线程池配置更新失败: " + e.getMessage());
            }
        }
    }
}