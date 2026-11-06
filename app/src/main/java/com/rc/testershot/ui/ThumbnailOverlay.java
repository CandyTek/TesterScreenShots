package com.rc.testershot.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.rc.testershot.Prefs;

import java.io.File;

/**
 * The small floating preview in the bottom-left corner after a capture. Tap opens the editor,
 * swipe left/down dismisses, auto-hides after a few seconds. Needs the overlay permission.
 */
public final class ThumbnailOverlay {
    /** Identifies one capture, so a late "file saved" doesn't attach to a newer thumbnail. */
    public static final class Token {
        File file;
        boolean saveFinished;
        boolean openWhenSaved;
    }

    private final Context app;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View view;
    private ImageView image;
    private ProgressBar progress;
    private Token current;
    private final Runnable autoHide = this::animateOut;

    public ThumbnailOverlay(Context app) {
        this.app = app.getApplicationContext();
        this.wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean canDraw() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app);
    }

    public Token newToken() { return new Token(); }

    /** Downscaled copy for the overlay (longest side ~ 30% of the screen's short side). */
    public static Bitmap makeThumb(Bitmap src, Context c) {
        int shortSide = Math.min(c.getResources().getDisplayMetrics().widthPixels, c.getResources().getDisplayMetrics().heightPixels);
        int target = Math.max(120, (int) (shortSide * 0.30f));
        float scale = Math.min(1f, (float) target / Math.max(src.getWidth(), src.getHeight()));
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * scale)),
                Math.max(1, Math.round(src.getHeight() * scale)), true);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, app.getResources().getDisplayMetrics());
    }

    /** Main thread. */
    public void show(Bitmap thumb, Token token) {
        if (!canDraw()) {
            thumb.recycle();
            return;
        }
        removeNow();
        current = token;

        FrameLayout frame = new FrameLayout(app);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), 0x33000000);
        frame.setBackground(bg);
        int pad = dp(3);
        frame.setPadding(pad, pad, pad, pad);
        frame.setElevation(dp(6));

        image = new ImageView(app);
        image.setImageBitmap(thumb);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(image, new FrameLayout.LayoutParams(thumb.getWidth(), thumb.getHeight()));

        progress = new ProgressBar(app, null, android.R.attr.progressBarStyleSmall);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END | Gravity.BOTTOM);
        plp.setMargins(0, 0, dp(4), dp(4));
        frame.addView(progress, plp);
        progress.setVisibility(token.saveFinished ? View.GONE : View.VISIBLE);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.x = dp(16);
        lp.y = dp(64);
        lp.windowAnimations = android.R.style.Animation_Toast;

        frame.setOnTouchListener(new SwipeTouch(frame, token));
        view = frame;
        try {
            wm.addView(frame, lp);
        } catch (Exception e) {
            view = null;
            return;
        }
        frame.setAlpha(0f);
        frame.setTranslationX(-dp(40));
        frame.animate().alpha(1f).translationX(0).setDuration(180).start();
        scheduleHide();
    }

    private void scheduleHide() {
        main.removeCallbacks(autoHide);
        main.postDelayed(autoHide, Math.max(1, Prefs.get(app).thumbnailSeconds()) * 1000L);
    }

    /** Called (main thread) when the background save finishes; file may be null on failure. */
    public void attachFile(Token token, File file) {
        token.file = file;
        token.saveFinished = true;
        if (token == current && progress != null) progress.setVisibility(View.GONE);
        if (token.openWhenSaved && file != null) open(file);
        if (file == null && token == current) removeNow();
    }

    private void open(File f) {
        Intent i = PreviewActivity.intent(app, f).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        app.startActivity(i);
    }

    private void animateOut() {
        final View v = view;
        if (v == null) return;
        v.animate().alpha(0f).translationX(-v.getWidth()).setDuration(160).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (view == v) removeNow();
            }
        }).start();
    }

    /** Removes the thumbnail instantly (before a capture). Main thread. */
    public void hideNow() {
        removeNow();
    }

    private void removeNow() {
        main.removeCallbacks(autoHide);
        if (view != null) {
            try { wm.removeViewImmediate(view); } catch (Exception ignored) {}
            view = null;
        }
        current = null;
        progress = null;
        image = null;
    }

    private final class SwipeTouch implements View.OnTouchListener {
        private final View v;
        private final Token token;
        private final int slop;
        private float downX, downY;
        private boolean dragging;

        SwipeTouch(View v, Token token) {
            this.v = v;
            this.token = token;
            this.slop = ViewConfiguration.get(app).getScaledTouchSlop();
        }

        @Override
        public boolean onTouch(View view, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    main.removeCallbacks(autoHide);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) dragging = true;
                    if (dragging) {
                        v.setTranslationX(Math.min(0, dx));
                        v.setTranslationY(Math.max(0, dy));
                        v.setAlpha(1f - Math.min(0.8f, (Math.abs(Math.min(0, dx)) + Math.max(0, dy)) / v.getWidth()));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging) {
                        removeNow();
                        if (token.file != null) open(token.file);
                        else if (!token.saveFinished) token.openWhenSaved = true;
                        return true;
                    }
                    if (-dx > v.getWidth() / 3f || dy > v.getHeight() / 3f) {
                        animateOut();
                    } else {
                        v.animate().translationX(0).translationY(0).alpha(1f).setDuration(120).start();
                        scheduleHide();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    v.animate().translationX(0).translationY(0).alpha(1f).setDuration(120).start();
                    scheduleHide();
                    return true;
            }
            return false;
        }
    }
}
