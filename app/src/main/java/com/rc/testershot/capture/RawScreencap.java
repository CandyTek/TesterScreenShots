package com.rc.testershot.capture;

import android.graphics.Bitmap;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Decodes the raw output of `screencap` (no -p): little-endian header w, h, format
 * [, dataspace on Android 12+] followed by w*h pixels. The header length is derived from the
 * total size, which works for every Android version without knowing the exact layout.
 */
public final class RawScreencap {
    private static final int PIXEL_FORMAT_RGBA_8888 = 1;
    private static final int PIXEL_FORMAT_RGBX_8888 = 2;
    private static final int PIXEL_FORMAT_RGB_888 = 3;
    private static final int PIXEL_FORMAT_RGB_565 = 4;
    private static final int PIXEL_FORMAT_BGRA_8888 = 5;

    private RawScreencap() {}

    public static Bitmap decode(byte[] data) throws IOException {
        if (data == null || data.length < 12) throw new IOException("screencap returned " + (data == null ? 0 : data.length) + " bytes");
        int w = le(data, 0), h = le(data, 4), f = le(data, 8);
        int bpp;
        switch (f) {
            case PIXEL_FORMAT_RGBA_8888:
            case PIXEL_FORMAT_RGBX_8888:
            case PIXEL_FORMAT_BGRA_8888: bpp = 4; break;
            case PIXEL_FORMAT_RGB_888: bpp = 3; break;
            case PIXEL_FORMAT_RGB_565: bpp = 2; break;
            default: throw new IOException("unsupported screencap format " + f);
        }
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) throw new IOException("bad screencap size " + w + "x" + h);
        long expected = (long) w * h * bpp;
        long header = data.length - expected;
        if (header < 12 || header > 64) {
            throw new IOException("screencap size mismatch: " + data.length + " bytes for " + w + "x" + h + " fmt " + f);
        }
        int off = (int) header;

        Bitmap bmp;
        if (bpp == 4 && f != PIXEL_FORMAT_BGRA_8888) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            // Bitmap ARGB_8888 memory layout is R,G,B,A bytes — identical to RGBA_8888.
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(data, off, (int) expected));
        } else if (bpp == 2) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(data, off, (int) expected));
        } else {
            int[] row = new int[w];
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int p = off;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int r, g, b;
                    if (f == PIXEL_FORMAT_BGRA_8888) {
                        b = data[p] & 0xff; g = data[p + 1] & 0xff; r = data[p + 2] & 0xff; p += 4;
                    } else {
                        r = data[p] & 0xff; g = data[p + 1] & 0xff; b = data[p + 2] & 0xff; p += 3;
                    }
                    row[x] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
                bmp.setPixels(row, 0, w, 0, y, w, 1);
            }
        }
        // The screen is opaque; RGBX may carry garbage in the X byte.
        bmp.setHasAlpha(false);
        return bmp;
    }

    private static int le(byte[] d, int o) {
        return (d[o] & 0xff) | (d[o + 1] & 0xff) << 8 | (d[o + 2] & 0xff) << 16 | (d[o + 3] & 0xff) << 24;
    }
}
