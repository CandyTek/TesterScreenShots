package com.rc.testershot.ui.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

/** Crops the image; later ops use the cropped image's coordinate space. */
public final class CropOp extends Op {
    private final Rect rect;

    public CropOp(Rect rect) {
        this.rect = new Rect(rect);
    }

    @Override
    public boolean isRaster() {
        return true;
    }

    @Override
    public Bitmap apply(Bitmap bmp, Canvas canvas) {
        Rect r = new Rect(rect);
        if (!r.intersect(0, 0, bmp.getWidth(), bmp.getHeight()) || r.width() < 1 || r.height() < 1) return bmp;
        if (r.left == 0 && r.top == 0 && r.width() == bmp.getWidth() && r.height() == bmp.getHeight()) return bmp;
        Bitmap out = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(bmp, -r.left, -r.top, null);
        return out;
    }
}
