package com.rc.testershot.util;

import android.graphics.Bitmap;

/** Renders QrCode modules to a crisp bitmap (integer pixels per module, 4-module quiet zone). */
public final class QrBitmap {
    private QrBitmap() {}

    public static Bitmap render(String text, int approxSizePx) {
        boolean[][] m = QrCode.encode(text, QrCode.Ecc.M);
        int quiet = 4;
        int modules = m.length + quiet * 2;
        int scale = Math.max(1, approxSizePx / modules);
        int size = modules * scale;
        int[] px = new int[size * size];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m.length; x++) {
                if (!m[y][x]) continue;
                int x0 = (x + quiet) * scale, y0 = (y + quiet) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    int row = (y0 + dy) * size + x0;
                    for (int dx = 0; dx < scale; dx++) px[row + dx] = 0xFF000000;
                }
            }
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
    }
}
