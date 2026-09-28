package com.downloader.gui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.Timer;

/**
 * 轻量级 Toast 通知（替代打断式弹窗的行内反馈）。
 * 挂载在窗口 glassPane 上，右下角滑入 + 淡出；
 * glassPane 区域外点击可穿透（contains 返回 false），不打断正常操作。
 */
final class Toast {

    private static final int SLIDE_IN_MS = 160;
    private static final int HOLD_MS = 1800;
    private static final int FADE_OUT_MS = 240;

    private Toast() {
    }

    public static void success(JFrame frame, String text) {
        show(frame, text, MainWindow.SUCCESS, UiIcons.check());
    }

    public static void error(JFrame frame, String text) {
        show(frame, text, MainWindow.DANGER, UiIcons.error());
    }

    public static void warning(JFrame frame, String text) {
        show(frame, text, MainWindow.WARNING, UiIcons.warning());
    }

    public static void info(JFrame frame, String text) {
        show(frame, text, MainWindow.ACCENT, UiIcons.info());
    }

    public static void show(JFrame frame, String text, Color accent, Icon icon) {
        JRootPane rootPane = frame.getRootPane();
        JComponent glass = (JComponent) rootPane.getGlassPane();
        if (!(glass instanceof Layer)) {
            glass = new Layer();
            rootPane.setGlassPane(glass);
        }
        Layer layer = (Layer) glass;
        layer.toast(text, accent, icon);
    }

    /** 玻璃层：自身不拦截鼠标（区域外点击穿透），仅 Toast 视图拦截 */
    private static final class Layer extends JComponent {

        private Timer dismissTimer;

        Layer() {
            setLayout(null);
        }

        void toast(String text, Color accent, Icon icon) {
            // 重置：取消上一次未完成的消失流程
            removeAll();
            if (dismissTimer != null) {
                dismissTimer.stop();
            }

            View view = new View(text, accent, icon);
            add(view);
            setVisible(true);

            int w = view.measure();
            int h = view.fixedHeight();
            int x = getWidth() - w - 28;
            int y = getHeight() - h - 26;
            view.setBounds(x, y + 14, w, h);

            // 滑入 + 淡入
            UiAnim.animate(SLIDE_IN_MS, t -> {
                view.alpha = t;
                view.setLocation(x, y + Math.round(14 * (1 - t)));
                view.repaint();
            });

            // 停留后淡出
            dismissTimer = new Timer(HOLD_MS, e -> {
                dismissTimer.stop();
                UiAnim.animate(FADE_OUT_MS, t -> view.alpha = 1 - t, () -> {
                    removeAll();
                    setVisible(false);
                });
            });
            dismissTimer.setRepeats(false);
            dismissTimer.start();
        }

        /** 玻璃层不包含任何点 → 鼠标事件穿透到下层组件 */
        @Override
        public boolean contains(int x, int y) {
            return false;
        }
    }

    /** 单条 Toast 视图：圆角底 + 语义色描边 + 图标 + 文本 */
    private static final class View extends JComponent {

        float alpha = 0;
        private final String text;
        private final Color accent;
        private final Icon icon;

        View(String text, Color accent, Icon icon) {
            this.text = text;
            this.accent = accent;
            this.icon = icon;
            setOpaque(false);
        }

        /** 依据文本计算固定宽度 */
        int measure() {
            FontMetrics fm = getFontMetrics(font());
            return Math.max(220, fm.stringWidth(text) + 58);
        }

        int fixedHeight() {
            return 40;
        }

        private Font font() {
            Font f = javax.swing.UIManager.getFont("Label.font");
            return f.deriveFont(Math.max(12f, f.getSize2D() - 0.5f));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.SrcOver.derive(alpha));

            int w = getWidth(), h = getHeight();
            // 底色取主题面板色，语义色描边
            Color bg = javax.swing.UIManager.getColor("Panel.background");
            g.setColor(bg != null ? bg : Color.WHITE);
            g.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);
            g.setColor(UiAnim.withAlpha(accent, 150));
            g.setStroke(new java.awt.BasicStroke(1.2f));
            g.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);

            Color fg = javax.swing.UIManager.getColor("Label.foreground");
            g.setColor(fg != null ? fg : Color.BLACK);
            icon.paintIcon(this, g, 15, (h - icon.getIconHeight()) / 2);

            FontMetrics fm = g.getFontMetrics(font());
            g.drawString(text, 15 + icon.getIconWidth() + 8,
                    (h - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }

        /** Toast 区域本身拦截点击，避免误触下层控件 */
        @Override
        public boolean contains(int x, int y) {
            return true;
        }
    }
}
