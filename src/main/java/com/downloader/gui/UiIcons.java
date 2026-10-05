package com.downloader.gui;

import com.formdev.flatlaf.icons.FlatAbstractIcon;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * 应用矢量图标集。
 * 基于 FlatLaf FlatAbstractIcon，按 16x16 网格用路径绘制线性图标，
 * 颜色实时取自组件前景色（color=null 时基类行为），因此随亮/暗主题自动切换。
 * 支持任意尺寸（内部按 16 网格等比缩放），加载零 IO 开销。
 */
public final class UiIcons {

    private UiIcons() {
    }

    /** 线性图标基类：等比缩放到目标尺寸，圆角笔帽，抗锯齿由基类处理 */
    private abstract static class Base extends FlatAbstractIcon {
        Base(int size, float stroke) {
            super(size, size, null);
            this.stroke = stroke;
        }

        final float stroke;

        @Override
        protected void paintIcon(Component c, Graphics2D g) {
            double scale = getIconWidth() / 16.0;
            g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            paint(c, g);
        }

        protected abstract void paint(Component c, Graphics2D g);
    }

    // ---- 绘制辅助 ----

    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }

    private static void circle(Graphics2D g, double cx, double cy, double r) {
        g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }

    private static void dot(Graphics2D g, double cx, double cy, double r) {
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }

    /** 添加（加号） */
    public static Icon add() {
        return icon(16, g -> {
            line(g, 8, 3.2, 8, 12.8);
            line(g, 3.2, 8, 12.8, 8);
        });
    }

    /** 暂停（双竖条） */
    public static Icon pause() {
        return icon(16, 2f, g -> {
            line(g, 5.6, 4.2, 5.6, 11.8);
            line(g, 10.4, 4.2, 10.4, 11.8);
        });
    }

    /** 继续（实心三角） */
    public static Icon resume() {
        return icon(16, g -> {
            Path2D p = new Path2D.Float();
            p.moveTo(5.8, 3.8);
            p.lineTo(12.4, 8);
            p.lineTo(5.8, 12.2);
            p.closePath();
            g.fill(p);
        });
    }

    /** 删除（垃圾桶） */
    public static Icon delete() {
        return icon(16, g -> {
            // 桶盖与提手
            line(g, 3.2, 4.8, 12.8, 4.8);
            Path2D handle = new Path2D.Float();
            handle.moveTo(6.3, 4.8);
            handle.lineTo(6.3, 2.8);
            handle.lineTo(9.7, 2.8);
            handle.lineTo(9.7, 4.8);
            g.draw(handle);
            // 桶身（顶部开口、圆角底）
            Path2D body = new Path2D.Float();
            body.moveTo(4.9, 6.5);
            body.lineTo(4.9, 12.4);
            body.quadTo(4.9, 13.1, 5.6, 13.1);
            body.lineTo(10.4, 13.1);
            body.quadTo(11.1, 13.1, 11.1, 12.4);
            body.lineTo(11.1, 6.5);
            g.draw(body);
            // 内部纹理
            line(g, 6.8, 8.6, 6.8, 11);
            line(g, 9.2, 8.6, 9.2, 11);
        });
    }

    /** 全选（方框内对勾） */
    public static Icon selectAll() {
        return icon(16, g -> {
            g.draw(new RoundRectangle2D.Double(2.8, 2.8, 10.4, 10.4, 3.2, 3.2));
            Path2D check = new Path2D.Float();
            check.moveTo(5.5, 8.2);
            check.lineTo(7.4, 10.1);
            check.lineTo(10.6, 6.1);
            g.draw(check);
        });
    }

    /** 搜索（放大镜） */
    public static Icon search() {
        return icon(16, g -> {
            circle(g, 7.1, 7.1, 4.2);
            line(g, 10.3, 10.3, 13.2, 13.2);
        });
    }

    /** 关闭（×，用于搜索框内清除按钮） */
    public static Icon close(int size) {
        return icon(size, g -> {
            line(g, 4.6, 4.6, 11.4, 11.4);
            line(g, 11.4, 4.6, 4.6, 11.4);
        });
    }

    /** 设置（滑杆调音台样式） */
    public static Icon settings() {
        return icon(16, g -> {
            // 第一行，旋钮在左
            line(g, 3, 5, 4.5, 5);
            line(g, 7.9, 5, 13, 5);
            circle(g, 6.2, 5, 1.7);
            // 第二行，旋钮在中
            line(g, 3, 8, 8.1, 8);
            line(g, 11.5, 8, 13, 8);
            circle(g, 9.8, 8, 1.7);
            // 第三行，旋钮在左
            line(g, 3, 11, 3.5, 11);
            line(g, 6.9, 11, 13, 11);
            circle(g, 5.2, 11, 1.7);
        });
    }

    /** 太阳（浅色主题） */
    public static Icon sun() {
        return icon(16, g -> {
            circle(g, 8, 8, 2.9);
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45);
                line(g,
                        8 + 4.5 * Math.cos(a), 8 + 4.5 * Math.sin(a),
                        8 + 6.1 * Math.cos(a), 8 + 6.1 * Math.sin(a));
            }
        });
    }

    /** 月亮（深色主题） */
    public static Icon moon() {
        return icon(16, g -> {
            Path2D crescent = new Path2D.Float();
            crescent.moveTo(11.2, 3.0);
            crescent.append(new Arc2D.Double(2.5, 2.5, 11, 11, 90, 250, Arc2D.OPEN), true);
            crescent.append(new Arc2D.Double(4.5, 4.5, 12, 12, 200, -140, Arc2D.OPEN), true);
            crescent.closePath();
            g.fill(crescent);
        });
    }

    /** 退出（电源符号） */
    public static Icon power() {
        return icon(16, g -> {
            g.draw(new Arc2D.Double(3.4, 4.1, 9.2, 9.2, 40, 280, Arc2D.OPEN));
            line(g, 8, 2.4, 8, 8.2);
        });
    }

    /** 文件夹（浏览目录） */
    public static Icon folder() {
        return icon(16, g -> {
            Path2D p = new Path2D.Float();
            p.moveTo(2.6, 13);
            p.lineTo(2.6, 4.6);
            p.lineTo(6.4, 4.6);
            p.lineTo(8, 6.5);
            p.lineTo(13.4, 6.5);
            p.lineTo(13.4, 13);
            p.closePath();
            g.draw(p);
        });
    }

    /** 下载（空状态占位图标） */
    public static Icon download(int size) {
        return icon(size, g -> {
            line(g, 8, 3, 8, 10);
            Path2D chevron = new Path2D.Float();
            chevron.moveTo(4.6, 7.4);
            chevron.lineTo(8, 11);
            chevron.lineTo(11.4, 7.4);
            g.draw(chevron);
            line(g, 4.4, 13.4, 11.6, 13.4);
        });
    }

    /** 成功（对勾） */
    public static Icon check() {
        return icon(16, g -> {
            Path2D p = new Path2D.Float();
            p.moveTo(3.6, 8.6);
            p.lineTo(6.8, 11.8);
            p.lineTo(12.4, 5.2);
            g.draw(p);
        });
    }

    /** 警告（三角+叹号） */
    public static Icon warning() {
        return icon(16, g -> {
            Path2D p = new Path2D.Float();
            p.moveTo(8, 3);
            p.lineTo(13.4, 12.6);
            p.lineTo(2.6, 12.6);
            p.closePath();
            g.draw(p);
            line(g, 8, 7, 8, 9.4);
            dot(g, 8, 11.2, 0.9);
        });
    }

    /** 错误（圆+×） */
    public static Icon error() {
        return icon(16, g -> {
            circle(g, 8, 8, 5.2);
            line(g, 5.9, 5.9, 10.1, 10.1);
            line(g, 10.1, 5.9, 5.9, 10.1);
        });
    }

    /** 提示（圆+i） */
    public static Icon info() {
        return icon(16, g -> {
            circle(g, 8, 8, 5.2);
            dot(g, 8, 5.6, 0.9);
            line(g, 8, 7.6, 8, 11);
        });
    }

    /** 联系作者（信封） */
    public static Icon contact() {
        return icon(16, g -> {
            g.draw(new RoundRectangle2D.Double(2.5, 4.8, 11, 7.2, 2.5, 2.5));
            Path2D flap = new Path2D.Float();
            flap.moveTo(3.2, 5.4);
            flap.lineTo(8, 9.2);
            flap.lineTo(12.8, 5.4);
            g.draw(flap);
        });
    }

    /** 后台下载（向下箭头收入底部托盘） */
    public static Icon background() {
        return icon(16, g -> {
            line(g, 8, 2.8, 8, 8.4);
            line(g, 5.8, 6.4, 8, 8.6);
            line(g, 10.2, 6.4, 8, 8.6);
            line(g, 3.4, 12.4, 12.6, 12.4);
        });
    }

    /** 检查更新（圆形箭头） */
    public static Icon update() {
        return icon(16, g -> {
            Path2D arrow = new Path2D.Float();
            arrow.moveTo(10.5, 3.5);
            arrow.lineTo(13, 6);
            arrow.lineTo(10.5, 8.5);
            g.draw(arrow);
            g.draw(new Arc2D.Double(3.5, 3.5, 9, 9, 330, 280, Arc2D.OPEN));
        });
    }

    // ---- 工厂 ----

    private interface Painter {
        void paint(Graphics2D g);
    }

    private static Icon icon(int size, Painter painter) {
        return icon(size, 1.5f, painter);
    }

    private static Icon icon(int size, float stroke, Painter painter) {
        return new Base(size, stroke) {
            @Override
            protected void paint(Component c, Graphics2D g) {
                painter.paint(g);
            }
        };
    }
}
