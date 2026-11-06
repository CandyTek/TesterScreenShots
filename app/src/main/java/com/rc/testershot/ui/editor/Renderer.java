package com.rc.testershot.ui.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import java.util.List;

/** Replays ops onto a copy of a bitmap. Safe to call from a background thread. */
public final class Renderer {
    private Renderer() {}

    /**
     * Returns a new mutable bitmap: a copy of {@code start} with ops[from, to) applied.
     * {@code start} itself is never modified or recycled.
     *
     * @throws OutOfMemoryError if a bitmap cannot be allocated
     */
    public static Bitmap render(Bitmap start, List<Op> ops, int from, int to) {
        Bitmap cur = start.copy(Bitmap.Config.ARGB_8888, true);
        if (cur == null) throw new OutOfMemoryError("bitmap copy failed");
        try {
            Canvas c = new Canvas(cur);
            for (int i = from; i < to; i++) {
                Bitmap next = ops.get(i).apply(cur, c);
                if (next != cur) {
                    cur.recycle();
                    cur = next;
                    c = new Canvas(cur);
                }
            }
            return cur;
        } catch (RuntimeException | OutOfMemoryError e) {
            cur.recycle();
            throw e;
        }
    }
}
