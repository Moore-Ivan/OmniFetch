package com.downloader.gui;

import com.downloader.detector.ProtocolDetector;
import com.downloader.model.Protocol;

import javax.swing.Timer;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * 系统剪贴板轮询监视器：剪贴板中出现新的、可识别的下载链接时回调通知。
 * 轮询基于 Swing Timer，回调天然运行在 EDT 上，调用方可直接操作界面。
 * 仅在内容变化且本身就是受支持的下载链接时才回调，其余剪贴板内容静默忽略。
 */
final class ClipboardMonitor {

    /** 剪贴板文本长度上限：超过即认为不是单个链接，直接忽略 */
    private static final int MAX_LENGTH = 4096;

    private final Consumer<String> onLinkDetected;
    private final Timer timer;

    /** 最近一次看到的剪贴板文本（去重：同一链接只提示一次） */
    private String lastText;
    /** 首轮只记录当前剪贴板内容、不提示：启动时已存在的内容并非"新复制" */
    private boolean firstPoll = true;

    ClipboardMonitor(Consumer<String> onLinkDetected) {
        this.onLinkDetected = onLinkDetected;
        this.timer = new Timer(1_000, e -> poll());
    }

    void start() {
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    void stop() {
        if (timer.isRunning()) {
            timer.stop();
        }
    }

    /** 调整检测间隔（配置变更时即时生效，无需重建 Timer） */
    void setIntervalMs(int intervalMs) {
        timer.setDelay(intervalMs);
        timer.setInitialDelay(intervalMs);
    }

    /** 标记某段文本已由本程序处理（如"复制链接"），避免自己触发的剪贴板变化又被自己提示 */
    void markSeen(String text) {
        if (text != null) {
            lastText = text.trim();
        }
    }

    private void poll() {
        String text = readClipboardText();
        if (text == null) {
            return;
        }
        text = text.trim();
        // 空文本、多行文本、超长文本都不可能是单个下载链接：只记录，不做协议检测
        if (text.isEmpty() || text.contains("\n") || text.contains("\r")
                || text.length() > MAX_LENGTH) {
            lastText = text;
            firstPoll = false;
            return;
        }
        if (text.equals(lastText)) {
            return;
        }
        lastText = text;
        if (firstPoll) {
            firstPoll = false;
            return;
        }
        if (ProtocolDetector.detect(text) == Protocol.UNKNOWN) {
            return;
        }
        onLinkDetected.accept(text);
    }

    /** 读取剪贴板文本；非文本内容（图片、文件等）或剪贴板暂不可用时返回 null */
    private static String readClipboardText() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return null;
            }
            return (String) clipboard.getData(DataFlavor.stringFlavor);
        } catch (IllegalStateException | HeadlessException | UnsupportedFlavorException
                 | IOException e) {
            // Windows 上剪贴板被其他进程占用时会抛 IllegalStateException，属正常抖动，跳过本次即可
            return null;
        }
    }
}
