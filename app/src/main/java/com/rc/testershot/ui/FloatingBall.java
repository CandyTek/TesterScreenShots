package com.rc.testershot.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import com.rc.testershot.Prefs;
import com.rc.testershot.R;

/**
 * Floating screenshot button. Tap = capture now. Long-press then drag = move; released near the
 * left/right edge it snaps there half-hidden, released elsewhere it stays fully visible where dropped.
 * Hidden while a capture runs so it never appears in screenshots.
 */
public final class FloatingBall {
    /** Edge state: docked half-hidden at the left/right edge, or free-floating. */
    private static final int EDGE_NONE = 0, EDGE_LEFT = -1, EDGE_RIGHT = 1;

    public interface OnTap { void onTap(); }

    private final Context app;
    private final WindowManager wm;
    private final SharedPreferences sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int size;
    private final OnTap onTap;
    private BallView view;
    private WindowManager.LayoutParams lp;
    private int edge;
    private boolean hiddenForCapture;

    public FloatingBall(Context c, OnTap onTap) {
        app = c.getApplicationContext();
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        sp = app.getSharedPreferences("floating_ball", Context.MODE_PRIVATE);
        size = dp(52);
        this.onTap = onTap;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.getResources().getDisplayMetrics());
    }

    private boolean canDraw() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app);
    }

    /** Shows or removes the ball according to settings and permission. Main thread. */
    public void apply() {
        boolean want = Prefs.get(app).floatingBall() && canDraw();
        if (want && view == null) show();
        else if (!want && view != null) remove();
    }

    private void show() {
        view = new BallView(app);
        lp = new WindowManager.LayoutParams(size, size,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        restorePosition();
        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            view = null;
            return;
        }
        if (hiddenForCapture) view.setVisibility(View.INVISIBLE);
        updateLook();
    }

    /** Removes the ball (service stopping). Main thread. */
    public void remove() {
        if (view == null) return;
        try { wm.removeViewImmediate(view); } catch (Exception ignored) {}
        view = null;
    }

    // ------------------------------------------------------------------ capture hooks

    /** Hide instantly before a capture. Main thread. */
    public void hideForCapture() {
        hiddenForCapture = true;
        if (view != null) view.setVisibility(View.INVISIBLE);
    }

    /** Show again once the pixels are grabbed. Main thread. */
    public void restoreAfterCapture() {
        hiddenForCapture = false;
        if (view != null) view.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ geometry

    private DisplayMetrics screen() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    /** Re-clamp after rotation / display size change. */
    public void onConfigurationChanged() {
        if (view == null) return;
        restorePosition();
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
        updateLook();
    }

    private void restorePosition() {
        DisplayMetrics dm = screen();
        edge = sp.getInt("edge", EDGE_RIGHT);
        float fy = sp.getFloat("fy", 0.6f);
        float fx = sp.getFloat("fx", 1f);
        lp.y = clamp((int) (fy * (dm.heightPixels - size)), 0, dm.heightPixels - size);
        if (edge == EDGE_LEFT) lp.x = -size / 2;
        else if (edge == EDGE_RIGHT) lp.x = dm.widthPixels - size / 2;
        else lp.x = clamp((int) (fx * (dm.widthPixels - size)), 0, dm.widthPixels - size);
    }

    private void savePosition() {
        DisplayMetrics dm = screen();
        sp.edit()
                .putInt("edge", edge)
                .putFloat("fx", dm.widthPixels > size ? (float) clamp(lp.x, 0, dm.widthPixels - size) / (dm.widthPixels - size) : 0f)
                .putFloat("fy", dm.heightPixels > size ? (float) lp.y / (dm.heightPixels - size) : 0f)
                .apply();
    }

    /** After a drag: dock to an edge if released close to it, otherwise stay where dropped. */
    private void settle() {
        DisplayMetrics dm = screen();
        int threshold = dp(24);
        lp.y = clamp(lp.y, 0, dm.heightPixels - size);
        if (lp.x < threshold) {
            edge = EDGE_LEFT;
            lp.x = -size / 2;
        } else if (lp.x + size > dm.widthPixels - threshold) {
            edge = EDGE_RIGHT;
            lp.x = dm.widthPixels - size / 2;
        } else {
            edge = EDGE_NONE;
        }
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) {}
        updateLook();
        savePosition();
    }

    private void updateLook() {
        if (view == null) return;
        view.setAlpha(edge == EDGE_NONE ? 0.9f : 0.6f);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ view

    private final class BallView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable icon;
        private final int slop;
        private float downRawX, downRawY;
        private int startX, startY;
        private boolean dragging, moved, longPressed;
        private final Runnable longPress = () -> {
            longPressed = true;
            dragging = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setScaleX(1.15f);
            setScaleY(1.15f);
            setAlpha(1f);
            // Pull a docked ball fully on screen while dragging.
            DisplayMetrics dm = screen();
            lp.x = clamp(lp.x, 0, dm.widthPixels - size);
            startX = lp.x;
            try { wm.updateViewLayout(this, lp); } catch (Exception ignored) {}
        };

        BallView(Context c) {
            super(c);
            fill.setColor(0xCC202124);
            ring.setColor(0x66FFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            icon = c.getDrawable(R.drawable.ic_camera);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setContentDescription(c.getString(R.string.action_screenshot));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getWidth() / 2f;
            canvas.drawCircle(r, r, r - dp(2), fill);
            canvas.drawCircle(r, r, r - dp(2), ring);
            if (icon != null) {
                int s = (int) (getWidth() * 0.46f), o = (getWidth() - s) / 2;
                icon.setBounds(o, o, o + s, o + s);
                icon.draw(canvas);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = moved = longPressed = false;
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                    if (!dragging) {
                        // Moving before the long-press fires cancels it (no accidental drags or shots).
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                            moved = true;
                            main.removeCallbacks(longPress);
                        }
                        return true;
                    }
                    lp.x = startX + (int) dx;
                    lp.y = startY + (int) dy;
                    try { wm.updateViewLayout(this, lp); } catch (Exception ignored) {}
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    if (dragging) {
                        endDrag();
                    } else if (!moved && !longPressed) {
                        performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    if (dragging) endDrag();
                    return true;
            }
            return false;
        }

        private void endDrag() {
            dragging = false;
            setScaleX(1f);
            setScaleY(1f);
            settle();
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (onTap != null) onTap.onTap();
            return true;
        }
    }
}
