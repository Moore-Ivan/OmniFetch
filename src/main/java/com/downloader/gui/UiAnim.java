package com.downloader.gui;

import java.awt.Color;
import javax.swing.Timer;

/**
 * 轻量级 UI 动画工具。
 * 基于 Swing Timer 逐帧插值（约 60fps），缓动函数为 ease-out cubic，
 * 动画参数均极小（200ms 级），不引入额外线程与依赖。
 */
final class UiAnim {

    private UiAnim() {
    }

    /** 动画回调：t ∈ [0,1]，已应用缓动 */
    interface Step {
        void run(float t);
    }

    private static final int FRAME_MS = 16;

    /**
     * 播放一段从 0 到 1 的缓动动画（ease-out cubic）。
     *
     * @param ms   动画时长（毫秒）
     * @param step 每帧回调（在 EDT 上执行）
     */
    static void animate(int ms, Step step) {
        animate(ms, step, null);
    }

    /**
     * 播放一段从 0 到 1 的缓动动画（ease-out cubic），结束后回调。
     *
     * @param ms    动画时长（毫秒）
     * @param step  每帧回调（在 EDT 上执行）
     * @param onEnd 动画完成回调（在 EDT 上执行，可为 null）
     */
    static void animate(int ms, Step step, Runnable onEnd) {
        Timer timer = new Timer(FRAME_MS, null);
        long start = System.currentTimeMillis();
        timer.addActionListener(e -> {
            float raw = Math.min(1f, (System.currentTimeMillis() - start) / (float) ms);
            float t = 1f - (1f - raw) * (1f - raw) * (1f - raw);
            step.run(t);
            if (raw >= 1f) {
                timer.stop();
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });
        timer.start();
    }

    /** 颜色线性插值（含 alpha 通道） */
    static Color lerp(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    /** 返回带指定 alpha 的颜色副本 */
    static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
