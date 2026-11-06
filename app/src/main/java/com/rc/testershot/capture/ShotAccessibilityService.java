package com.rc.testershot.capture;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional capture backend (Android 11+): AccessibilityService#takeScreenshot.
 * Also tracks the foreground package and can dismiss the notification shade (Android 12+).
 */
public class ShotAccessibilityService extends AccessibilityService {
    private static volatile ShotAccessibilityService instance;
    private static volatile String foregroundPackage;
    private static volatile String foregroundClass;

    public static ShotAccessibilityService get() { return instance; }

    public static boolean isRunning() { return instance != null; }

    /** Screenshot capability needs Android 11. */
    public static boolean canCapture() {
        return instance != null && Build.VERSION.SDK_INT >= 30;
    }

    public static String foregroundPackage() { return foregroundPackage; }

    public static String foregroundClass() { return foregroundClass; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        CaptureEngine.get(this).onBackendsChanged();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(getPackageName()) || p.equals("com.android.systemui")) return;
        foregroundPackage = p;
        CharSequence cls = event.getClassName();
        foregroundClass = cls != null ? cls.toString() : null;
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
        CaptureEngine.get(this).onBackendsChanged();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        CaptureEngine.get(this).onBackendsChanged();
        return super.onUnbind(intent);
    }

    /** Collapses the notification shade. Returns true if an action was issued. */
    public boolean dismissShade() {
        if (Build.VERSION.SDK_INT >= 31) {
            return performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        }
        return false;
    }

    /** Blocking screenshot of the default display. */
    @TargetApi(30)
    public Bitmap captureBlocking(long timeoutMs) throws IOException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> result = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult r) {
                HardwareBuffer hb = r.getHardwareBuffer();
                try {
                    ColorSpace cs = r.getColorSpace();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
                    if (hw == null) {
                        error.set("wrapHardwareBuffer failed");
                    } else {
                        result.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                        hw.recycle();
                    }
                } catch (Throwable t) {
                    error.set(t.toString());
                } finally {
                    hb.close();
                    latch.countDown();
                }
            }

            @Override
            public void onFailure(int errorCode) {
                error.set("takeScreenshot error " + errorCode);
                latch.countDown();
            }
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IOException("accessibility screenshot timeout");
        } catch (InterruptedException e) {
            throw new IOException("interrupted", e);
        }
        if (result.get() == null) throw new IOException(error.get());
        return result.get();
    }
}
