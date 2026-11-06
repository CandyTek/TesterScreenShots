package com.rc.testershot.ui.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/** Pixelates a rectangular region. */
public final class MosaicOp extends Op {
    private final RectF rect;
    private final int block;

    public MosaicOp(RectF rect, int block) {
        this.rect = new RectF(rect);
        this.block = Math.max(2, block);
    }

    /** A sensible block size (in image pixels) for an image of the given size. */
    public static int defaultBlock(int w, int h) {
        return Math.max(6, Math.round(Math.min(w, h) / 48f));
    }

    @Override
    public boolean isRaster() {
        return true;
    }

    @Override
    public Bitmap apply(Bitmap bmp, Canvas canvas) {
        Rect r = new Rect();
        rect.roundOut(r);
        if (!r.intersect(0, 0, bmp.getWidth(), bmp.getHeight()) || r.width() < 1 || r.height() < 1) return bmp;
        int bw = Math.max(1, (r.width() + block / 2) / block);
        int bh = Math.max(1, (r.height() + block / 2) / block);
        Bitmap region = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height());
        Bitmap small = Bitmap.createScaledBitmap(region, bw, bh, true);
        if (region != bmp && region != small) region.recycle();
        Paint p = new Paint();
        p.setFilterBitmap(false);
        p.setAntiAlias(false);
        canvas.drawBitmap(small, null, r, p);
        if (small != bmp) small.recycle();
        return bmp;
    }
}
