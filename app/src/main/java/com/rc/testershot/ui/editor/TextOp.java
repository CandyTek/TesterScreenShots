package com.rc.testershot.ui.editor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/** A (possibly multi-line) text label centred on a point, with a contrasting outline. */
public final class TextOp extends Op {
    private final String[] lines;
    private final float cx, cy;
    private final Paint fill;
    private final Paint outline;

    public TextOp(String text, float cx, float cy, float size, int color) {
        this.lines = text.split("\n", -1);
        this.cx = cx;
        this.cy = cy;
        fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        fill.setTextSize(size);
        fill.setColor(color);
        fill.setTypeface(Typeface.DEFAULT_BOLD);
        fill.setStyle(Paint.Style.FILL);
        outline = new Paint(fill);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setStrokeWidth(Math.max(1f, size * 0.12f));
        outline.setColor(luminance(color) > 0.55f ? 0xCC000000 : 0xCCFFFFFF);
    }

    private static float luminance(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    @Override
    public void draw(Canvas c) {
        Paint.FontMetrics fm = fill.getFontMetrics();
        float lh = fm.descent - fm.ascent;
        float maxW = 0;
        for (String l : lines) maxW = Math.max(maxW, fill.measureText(l));
        float top = cy - lh * lines.length / 2f;
        float left = cx - maxW / 2f;
        for (int i = 0; i < lines.length; i++) {
            float baseline = top + i * lh - fm.ascent;
            c.drawText(lines[i], left, baseline, outline);
            c.drawText(lines[i], left, baseline, fill);
        }
    }
}
