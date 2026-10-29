package forge.neo.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.CubicCurveTo;
import javafx.scene.shape.HLineTo;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.QuadCurveTo;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.shape.VLineTo;


public final class ModeArt {

    private ModeArt() {
    }

    
    static final Color W = Color.web("#F3E4BE");
    static final Color U = Color.web("#6FA9DE");
    static final Color B = Color.web("#9A88A8");
    static final Color R = Color.web("#E08A72");
    static final Color G = Color.web("#74BC8E");
    static final Color C = Color.web("#98A5B6");
    static final Color GOLD = Color.web("#D7B057");

    
    public enum Mode {
        COMMANDER(GOLD, new Color[] {B, G}),
        LIBRARY(U, new Color[] {W, U}),
        LOOK(GOLD, new Color[] {U, G});

        private final Color ink;
        private final Color[] colors;

        Mode(final Color ink, final Color[] colors) {
            this.ink = ink;
            this.colors = colors;
        }

        
        public Color ink() {
            return ink;
        }

        
        public List<Color> colors() {
            return List.of(colors);
        }

        
        public Color tint() {
            return colors.length == 1 ? colors[0] : GOLD;
        }
    }

    
    public static void draw(final GraphicsContext g, final Mode mode, final double cx, final double cy,
                            final double side, final Color ink, final Color bg, final double strength) {
        final Pen t = new Pen(g, side, ink, bg, strength);
        g.save();
        g.translate(cx, cy);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        switch (mode) {
            
            case COMMANDER -> crown(t);
            
            
            
            
            
            
            
            
            
            
            
            
            case LIBRARY -> book(t);
            case LOOK -> palette(t);
            
            default -> {
            }
        }
        g.restore();
    }

    
    private static final class Pen {
        final GraphicsContext g;
        final double s;
        final Color ink;
        final Color bg;
        final double f;
        final double line;
        final double thin;
        final Color outline;

        Pen(final GraphicsContext g, final double s, final Color ink, final Color bg, final double f) {
            this.g = g;
            this.s = s;
            this.ink = ink;
            this.bg = bg;
            this.f = f;
            this.line = s * 0.028;
            this.thin = s * 0.016;
            this.outline = alpha(ink, 0.95 * f);
        }

        
        Paint fill() {
            return new LinearGradient(0, -s * 0.5, 0, s * 0.5, false, CycleMethod.NO_CYCLE,
                    new Stop(0, alpha(ink, 0.42 * f)), new Stop(1, alpha(ink, 0.10 * f)));
        }

        Color outline(final double a) {
            return alpha(ink, a * f);
        }

        Color hole(final double a) {
            return alpha(bg, a);
        }
    }

    static Color alpha(final Color c, final double a) {
        return Color.color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(1, a)));
    }

    
    
    

    
    private static void poly(final Pen t, final double... xy) {
        t.g.beginPath();
        t.g.moveTo(xy[0] * t.s, xy[1] * t.s);
        for (int i = 2; i < xy.length; i += 2) {
            t.g.lineTo(xy[i] * t.s, xy[i + 1] * t.s);
        }
        t.g.closePath();
    }

    
    private static void shape(final Pen t) {
        t.g.setFill(t.fill());
        t.g.fill();
        t.g.setStroke(t.outline);
        t.g.setLineWidth(t.line);
        t.g.stroke();
    }

    private static void fillPath(final Pen t, final Paint p) {
        t.g.setFill(p);
        t.g.fill();
    }

    private static void strokePath(final Pen t, final Paint p, final double width) {
        t.g.setStroke(p);
        t.g.setLineWidth(width);
        t.g.stroke();
    }

    private static void dot(final Pen t, final Paint p, final double r, final double x, final double y) {
        t.g.setFill(p);
        t.g.fillOval(x * t.s - r, y * t.s - r, r * 2, r * 2);
    }

    private static void ring(final Pen t, final Paint p, final double r, final double x, final double y,
                             final double width) {
        t.g.setStroke(p);
        t.g.setLineWidth(width);
        t.g.strokeOval(x * t.s - r, y * t.s - r, r * 2, r * 2);
    }

    private static void line(final Pen t, final Paint p, final double x0, final double y0, final double x1,
                             final double y1, final double width) {
        t.g.setStroke(p);
        t.g.setLineWidth(width);
        t.g.strokeLine(x0 * t.s, y0 * t.s, x1 * t.s, y1 * t.s);
    }

    
    private static void roundRect(final Pen t, final double x0, final double y0, final double x1, final double y1,
                                  final double radius) {
        final double arc = radius * 2 * t.s;
        t.g.setFill(t.fill());
        t.g.fillRoundRect(x0 * t.s, y0 * t.s, (x1 - x0) * t.s, (y1 - y0) * t.s, arc, arc);
        t.g.setStroke(t.outline);
        t.g.setLineWidth(t.line);
        t.g.strokeRoundRect(x0 * t.s, y0 * t.s, (x1 - x0) * t.s, (y1 - y0) * t.s, arc, arc);
    }

    
    private static void rotated(final Pen t, final double degrees, final double px, final double py,
                                final Runnable body) {
        t.g.save();
        t.g.translate(px * t.s, py * t.s);
        t.g.rotate(degrees);
        t.g.translate(-px * t.s, -py * t.s);
        body.run();
        t.g.restore();
    }

    
    private static void sparkle(final Pen t, final double cx, final double cy, final double r) {
        final double k = r * 0.22;
        poly(t, cx, cy - r, cx + k, cy - k, cx + r, cy, cx + k, cy + k,
                cx, cy + r, cx - k, cy + k, cx - r, cy, cx - k, cy - k);
        fillPath(t, t.outline);
    }

    
    private static void card(final Pen t, final double cx, final double cy, final double w, final double h) {
        final double arc = 0.035 * 2 * t.s;
        t.g.setFill(t.hole(0.92));
        t.g.fillRoundRect((cx - w / 2) * t.s, (cy - h / 2) * t.s, w * t.s, h * t.s, arc, arc);
        roundRect(t, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 0.035);
        
        t.g.setStroke(t.outline(0.45));
        t.g.setLineWidth(t.thin);
        t.g.strokeRect((cx - w / 2 + 0.04) * t.s, (cy - h / 2 + 0.07) * t.s, (w - 0.08) * t.s, t.s * h * 0.42);
    }

    
    private static void arc(final Pen t, final double cx, final double cy, final double r,
                            final double start, final double sweep, final boolean moveFirst) {
        if (moveFirst) {
            final double a = Math.toRadians(start);
            t.g.moveTo((cx + r * Math.cos(a)) * t.s, (cy + r * Math.sin(a)) * t.s);
        }
        t.g.arc(cx * t.s, cy * t.s, r * t.s, r * t.s, -start, -sweep);
    }

    
    
    

    
    private static void crown(final Pen t) {
        poly(t, -0.38, 0.16, -0.44, -0.22, -0.20, 0.00, 0, -0.34, 0.20, 0.00, 0.44, -0.22, 0.38, 0.16);
        shape(t);
        roundRect(t, -0.40, 0.18, 0.40, 0.34, 0.03);
        for (final double[] p : new double[][] {{-0.44, -0.27}, {0, -0.40}, {0.44, -0.27}}) {
            dot(t, t.outline, t.s * 0.05, p[0], p[1]);
        }
        
        for (final double x : new double[] {-0.22, 0, 0.22}) {
            dot(t, t.hole(0.9), t.s * 0.045, x, 0.26);
            ring(t, t.outline, t.s * 0.045, x, 0.26, t.thin);
        }
    }

    
    

    
    

    
    

    
    

    
    

    
    

    
    private static void replay(final Pen t, final Shape shape) {
        t.g.beginPath();
        if (!(shape instanceof javafx.scene.shape.Path path)) {
            return;
        }
        t.g.setFillRule(path.getFillRule());
        double x = 0;
        double y = 0;
        final List<PathElement> elements = new ArrayList<>(path.getElements());
        for (final PathElement e : elements) {
            if (e instanceof MoveTo m) {
                x = m.getX();
                y = m.getY();
                t.g.moveTo(x, y);
            } else if (e instanceof LineTo l) {
                x = l.getX();
                y = l.getY();
                t.g.lineTo(x, y);
            } else if (e instanceof HLineTo h) {
                x = h.getX();
                t.g.lineTo(x, y);
            } else if (e instanceof VLineTo v) {
                y = v.getY();
                t.g.lineTo(x, y);
            } else if (e instanceof CubicCurveTo c) {
                x = c.getX();
                y = c.getY();
                t.g.bezierCurveTo(c.getControlX1(), c.getControlY1(), c.getControlX2(), c.getControlY2(), x, y);
            } else if (e instanceof QuadCurveTo q) {
                x = q.getX();
                y = q.getY();
                t.g.quadraticCurveTo(q.getControlX(), q.getControlY(), x, y);
            } else if (e instanceof ClosePath) {
                t.g.closePath();
            }
        }
    }

    
    

    
    

    
    

    
    

    
    private static void book(final Pen t) {
        final double s = t.s;
        for (final double side : new double[] {-1, 1}) {
            t.g.beginPath();
            t.g.moveTo(0, -0.26 * s);
            t.g.quadraticCurveTo(0.20 * side * s, -0.34 * s, 0.44 * side * s, -0.28 * s);
            t.g.lineTo(0.44 * side * s, 0.30 * s);
            t.g.quadraticCurveTo(0.20 * side * s, 0.24 * s, 0, 0.32 * s);
            t.g.closePath();
            shape(t);
            
            t.g.setStroke(t.outline(0.55));
            t.g.setLineWidth(t.thin);
            t.g.strokeRect((0.13 * side - 0.08) * s, -0.18 * s, s * 0.16, s * 0.22);
            
            for (int k = 0; k < 3; k++) {
                final double y = 0.10 + k * 0.06;
                line(t, t.outline(0.45), 0.07 * side, y, 0.36 * side, y, s * 0.012);
            }
        }
        
        line(t, t.outline, 0, -0.26, 0, 0.32, s * 0.02);
    }

    
    

    
    

    
    
    

    
    

    
    

    
    private static void palette(final Pen t) {
        final double s = t.s;
        t.g.beginPath();
        t.g.moveTo(0, -0.36 * s);
        t.g.bezierCurveTo(0.30 * s, -0.38 * s, 0.48 * s, -0.12 * s, 0.40 * s, 0.12 * s);
        t.g.bezierCurveTo(0.34 * s, 0.30 * s, 0.12 * s, 0.40 * s, -0.10 * s, 0.36 * s);
        t.g.bezierCurveTo(-0.36 * s, 0.32 * s, -0.46 * s, 0.06 * s, -0.38 * s, -0.14 * s);
        t.g.bezierCurveTo(-0.30 * s, -0.32 * s, -0.14 * s, -0.35 * s, 0, -0.36 * s);
        t.g.closePath();
        shape(t);
        
        dot(t, t.hole(0.9), s * 0.07, 0.10, 0.16);
        ring(t, t.outline, s * 0.07, 0.10, 0.16, t.thin);
        
        final double[][] at = {{-0.24, -0.10}, {-0.08, -0.22}, {0.12, -0.21}, {0.27, -0.06}, {-0.24, 0.12}};
        final Color[] paint = {W, U, B, R, G};
        for (int i = 0; i < at.length; i++) {
            dot(t, alpha(paint[i], 0.95 * t.f), s * 0.06, at[i][0], at[i][1]);
            ring(t, t.outline(0.8), s * 0.06, at[i][0], at[i][1], t.thin);
        }
    }
}
