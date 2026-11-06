package com.rc.testershot.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny async thumbnail loader with a memory cache, for the workspace grid. */
final class ThumbLoader {
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService exec = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "thumbs");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    ThumbLoader() {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    private static String key(File f) {
        return f.getAbsolutePath() + "|" + f.lastModified() + "|" + f.length();
    }

    void load(File f, ImageView iv, int targetPx) {
        final String k = key(f);
        iv.setTag(k);
        Bitmap b = cache.get(k);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageDrawable(null);
        exec.execute(() -> {
            Bitmap bmp = decode(f, Math.max(64, targetPx));
            if (bmp == null) return;
            cache.put(k, bmp);
            main.post(() -> {
                if (k.equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }

    static Bitmap decode(File f, int target) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        int minSide = Math.min(o.outWidth, o.outHeight);
        while (minSide / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
    }

    void clear() {
        cache.evictAll();
    }

    void shutdown() {
        exec.shutdownNow();
        cache.evictAll();
    }
}
