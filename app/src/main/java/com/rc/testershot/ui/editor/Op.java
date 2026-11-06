package com.rc.testershot.ui.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/**
 * One undoable editing step. All coordinates are in bitmap pixels of the image as it was when the
 * step was made (i.e. after all earlier crops). Instances are immutable once created, so they can
 * be drawn from the UI thread and the save thread at the same time.
 */
public abstract class Op {

    /** Raster ops (crop, mosaic) modify pixels and are baked into the cached bitmap. */
    public boolean isRaster() {
        return false;
    }

    /** Draws a vector op onto a canvas whose coordinate space is the image's pixel space. */
    public void draw(Canvas c) {
    }

    /**
     * Applies the op to a mutable bitmap (with {@code canvas} drawing into it). Returns the bitmap
     * to continue with; when that is a different bitmap the caller recycles the old one.
     */
    public Bitmap apply(Bitmap bmp, Canvas canvas) {
        draw(canvas);
        return bmp;
    }
}
