package com.rc.testershot.capture;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Best-effort detection of a visible navigation bar: briefly adds an invisible, untouchable
 * full-screen overlay and reads its insets. Returns the screen rect without the navigation bar,
 * or null if unknown / no navigation bar visible. Needs the overlay permission.
 */
final class NavBarProbe {
    private NavBarProbe() {}

    @SuppressWarnings("deprecation")
    static Rect contentRect(Context c, long timeoutMs) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) return null;
        final WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        final DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        final int sw = dm.widthPixels, sh = dm.heightPixels;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Rect> result = new AtomicReference<>();
        final View v = new View(c);
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                    PixelFormat.TRANSPARENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.alpha = 0f;
            v.setOnApplyWindowInsetsListener((view, insets) -> {
                Rect r = null;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                    if (nav.bottom > 0 || nav.left > 0 || nav.right > 0) {
                        r = new Rect(nav.left, 0, sw - nav.right, sh - nav.bottom);
                    }
                } else {
                    int b = insets.getSystemWindowInsetBottom(), l = insets.getSystemWindowInsetLeft(), rr = insets.getSystemWindowInsetRight();
                    // IME also counts as a bottom inset on old versions; a nav bar is small (< 1/8 of the screen).
                    if (b > sh / 8) b = 0;
                    if (b > 0 || l > 0 || rr > 0) r = new Rect(l, 0, sw - rr, sh - b);
                }
                result.set(r);
                latch.countDown();
                return insets;
            });
            try {
                wm.addView(v, lp);
            } catch (Exception e) {
                latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        main.post(() -> {
            try { wm.removeViewImmediate(v); } catch (Exception ignored) {}
        });
        return result.get();
    }
}
