package com.rc.testershot.ui.editor;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Freehand pen, straight line, arrow, rectangle or oval. */
public final class StrokeOp extends Op {
    public static final int PEN = 1;
    public static final int LINE = 2;
    public static final int ARROW = 3;
    public static final int RECT = 4;
    public static final int OVAL = 5;

    private final int kind;
    private final Path path;
    private final float x0, y0, x1, y1;
    private final float width;
    private final Paint stroke;
    private final Paint fill;

    /** Freehand stroke; the path is owned by the op afterwards and must not be modified. */
    public static StrokeOp pen(Path path, int color, float width) {
        return new StrokeOp(PEN, path, 0, 0, 0, 0, color, width);
    }

    public static StrokeOp shape(int kind, float x0, float y0, float x1, float y1, int color, float width) {
        return new StrokeOp(kind, null, x0, y0, x1, y1, color, width);
    }

    private StrokeOp(int kind, Path path, float x0, float y0, float x1, float y1, int color, float width) {
        this.kind = kind;
        this.path = path;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.width = width;
        this.stroke = makeStroke(color, width);
        this.fill = makeFill(color);
    }

    @Override
    public void draw(Canvas c) {
        drawShape(c, kind, path, x0, y0, x1, y1, width, stroke, fill);
    }

    public static Paint makeStroke(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        p.setStrokeWidth(width);
        return p;
    }

    public static Paint makeFill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    /** Shared by committed ops and the live preview while dragging. */
    public static void drawShape(Canvas c, int kind, Path path, float x0, float y0, float x1, float y1,
                                 float width, Paint stroke, Paint fill) {
        switch (kind) {
            case PEN:
                if (path != null) c.drawPath(path, stroke);
                break;
            case LINE:
                c.drawLine(x0, y0, x1, y1, stroke);
                break;
            case ARROW:
                drawArrow(c, x0, y0, x1, y1, width, stroke, fill);
                break;
            case RECT:
                c.drawRect(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), stroke);
                break;
            case OVAL:
                c.drawOval(new RectF(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)), stroke);
                break;
            default:
                break;
        }
    }

    private static void drawArrow(Canvas c, float x0, float y0, float x1, float y1, float width,
                                  Paint stroke, Paint fill) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        if (len < 0.5f) return;
        float ux = dx / len, uy = dy / len;
        float head = Math.min(width * 4.5f, len);
        float half = head * 0.55f;
        float bx = x1 - ux * head, by = y1 - uy * head;
        // stop the shaft inside the head so the round cap does not poke out of the tip
        float sx = x1 - ux * head * 0.6f, sy = y1 - uy * head * 0.6f;
        if (len > head * 0.6f) c.drawLine(x0, y0, sx, sy, stroke);
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(bx - uy * half, by + ux * half);
        p.lineTo(bx + uy * half, by - ux * half);
        p.close();
        c.drawPath(p, fill);
    }
}
