package com.rc.testershot.capture;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Optional capture backend: screen-recording permission (MediaProjection).
 * The virtual display stays alive while permission is held, so a capture is just "copy the latest frame".
 * Must be started while the foreground service is running (Android 10+).
 */
@TargetApi(21)
public final class ProjectionCapturer {
    private static final String TAG = "ProjectionCapturer";

    private final Context app;
    private final Object lock = new Object();
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private Image latest;
    private long latestTime;
    private int width, height;
    private HandlerThread thread;
    private Handler handler;

    public ProjectionCapturer(Context c) {
        app = c.getApplicationContext();
    }

    public boolean isActive() {
        synchronized (lock) { return projection != null; }
    }

    /** Called by the service with the result of createScreenCaptureIntent(). */
    public void start(int resultCode, Intent data) {
        stop();
        MediaProjectionManager mpm = (MediaProjectionManager) app.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection mp = mpm.getMediaProjection(resultCode, data);
        if (mp == null) return;
        thread = new HandlerThread("projection");
        thread.start();
        handler = new Handler(thread.getLooper());
        synchronized (lock) { projection = mp; }
        mp.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "projection stopped by system");
                release(false);
                CaptureEngine.get(app).onBackendsChanged();
            }
        }, handler);
        createDisplay();
    }

    private void createDisplay() {
        DisplayMetrics dm = realMetrics();
        synchronized (lock) {
            if (projection == null) return;
            width = dm.widthPixels;
            height = dm.heightPixels;
            ImageReader old = reader;
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(r -> {
                synchronized (lock) {
                    try {
                        Image img = r.acquireLatestImage();
                        if (img == null) return;
                        if (latest != null) latest.close();
                        latest = img;
                        latestTime = System.currentTimeMillis();
                    } catch (IllegalStateException e) {
                        Log.w(TAG, "acquire: " + e);
                    }
                }
            }, handler);
            if (display == null) {
                display = projection.createVirtualDisplay("TesterShot", width, height, dm.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
            } else {
                display.resize(width, height, dm.densityDpi);
                display.setSurface(reader.getSurface());
            }
            if (latest != null) { latest.close(); latest = null; }
            if (old != null) old.close();
        }
    }

    private DisplayMetrics realMetrics() {
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        d.getRealMetrics(dm);
        return dm;
    }

    /** Copies the most recent frame. Waits briefly for a fresh frame after rotation. */
    public Bitmap capture(long timeoutMs) throws IOException {
        DisplayMetrics dm = realMetrics();
        boolean resized = false;
        synchronized (lock) {
            if (projection == null) throw new IOException("no screen-recording permission");
            if (dm.widthPixels != width || dm.heightPixels != height) resized = true;
        }
        if (resized) createDisplay();
        long requestTime = System.currentTimeMillis();
        long end = requestTime + timeoutMs;
        while (true) {
            synchronized (lock) {
                // After a resize we need a frame produced by the new reader.
                if (latest != null && (!resized || latestTime >= requestTime)) return toBitmap(latest);
            }
            if (System.currentTimeMillis() > end) throw new IOException("no frame from virtual display");
            try { Thread.sleep(16); } catch (InterruptedException e) { throw new IOException(e); }
        }
    }

    private static Bitmap toBitmap(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        int w = img.getWidth(), h = img.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPaddingPx = (rowStride - pixelStride * w) / pixelStride;
        ByteBuffer buf = plane.getBuffer();
        buf.rewind();
        Bitmap padded = Bitmap.createBitmap(w + rowPaddingPx, h, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buf);
        Bitmap out;
        if (rowPaddingPx == 0) {
            out = padded;
        } else {
            out = Bitmap.createBitmap(padded, 0, 0, w, h);
            padded.recycle();
        }
        out.setHasAlpha(false);
        return out;
    }

    public void stop() {
        release(true);
    }

    private void release(boolean stopProjection) {
        MediaProjection mp;
        synchronized (lock) {
            if (latest != null) { latest.close(); latest = null; }
            if (display != null) { display.release(); display = null; }
            if (reader != null) { reader.close(); reader = null; }
            mp = projection;
            projection = null;
        }
        if (stopProjection && mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }
}
