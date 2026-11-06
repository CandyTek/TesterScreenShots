package com.rc.testershot.capture;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.rc.testershot.Prefs;
import com.rc.testershot.R;
import com.rc.testershot.capture.adb.AdbConnection;
import com.rc.testershot.capture.adb.AdbKey;
import com.rc.testershot.ui.FloatingBall;
import com.rc.testershot.ui.ThumbnailOverlay;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates a capture: pick backend (root → network adb → accessibility → screen recording),
 * collapse the shade, wait, grab pixels, show the thumbnail immediately, save in the background.
 */
public final class CaptureEngine {
    private static final String TAG = "CaptureEngine";

    public enum Backend { ROOT, ADB, ACCESSIBILITY, PROJECTION }

    public interface Listener {
        /** Detection state or availability of a backend changed. */
        default void onBackendsChanged() {}

        /** A screenshot file was written (main thread). */
        default void onShotSaved(File file) {}
    }

    @SuppressLint("StaticFieldLeak") // application context only
    private static CaptureEngine instance;

    public static synchronized CaptureEngine get(Context c) {
        if (instance == null) instance = new CaptureEngine(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService captureExec = Executors.newSingleThreadExecutor(r -> new Thread(r, "capture"));
    private final ExecutorService detectExec = Executors.newSingleThreadExecutor(r -> new Thread(r, "detect"));
    private final AtomicBoolean busy = new AtomicBoolean();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final RootShell rootShell = new RootShell();
    private final ProjectionCapturer projection;
    private final ShotSaver saver;
    private final ThumbnailOverlay overlay;
    private final FloatingBall ball;

    /** null = not probed yet. */
    private volatile Boolean rootOk;
    private volatile AdbConnection adb;
    private volatile String adbState = "";
    private volatile String lastError;
    private volatile Backend lastBackend;

    private CaptureEngine(Context app) {
        this.app = app;
        this.prefs = Prefs.get(app);
        this.projection = new ProjectionCapturer(app);
        this.saver = new ShotSaver(app);
        this.overlay = new ThumbnailOverlay(app);
        this.ball = new FloatingBall(app, () -> trigger(0));
    }

    /** The floating screenshot button (shown/hidden by ShotService). */
    public FloatingBall floatingBall() { return ball; }

    // ------------------------------------------------------------------ listeners

    public void addListener(Listener l) { listeners.addIfAbsent(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    public void onBackendsChanged() {
        main.post(() -> { for (Listener l : listeners) l.onBackendsChanged(); });
    }

    void notifySaved(File f) {
        main.post(() -> { for (Listener l : listeners) l.onShotSaved(f); });
    }

    // ------------------------------------------------------------------ state for UI

    public ProjectionCapturer projection() { return projection; }

    public Boolean rootState() { return rootOk; }

    public boolean adbConnected() { AdbConnection a = adb; return a != null && !a.isClosed(); }

    public String adbState() { return adbState; }

    public String lastError() { return lastError; }

    public Backend lastBackend() { return lastBackend; }

    /** True if a root or adb shell is known to work (used to decide how notification buttons collapse the shade). */
    public boolean shellAvailable() {
        return Boolean.TRUE.equals(rootOk) || adbConnected();
    }

    /** Which backend would be used right now, without probing. May be null. */
    public Backend predictBackend() {
        String m = prefs.method();
        switch (m) {
            case Prefs.METHOD_ROOT: return Boolean.TRUE.equals(rootOk) ? Backend.ROOT : null;
            case Prefs.METHOD_ADB: return adbConnected() ? Backend.ADB : null;
            case Prefs.METHOD_ACCESSIBILITY: return ShotAccessibilityService.canCapture() ? Backend.ACCESSIBILITY : null;
            case Prefs.METHOD_PROJECTION: return projection.isActive() ? Backend.PROJECTION : null;
            default:
                if (Boolean.TRUE.equals(rootOk)) return Backend.ROOT;
                if (adbConnected()) return Backend.ADB;
                if (prefs.fallbackAccessibility() && ShotAccessibilityService.canCapture()) return Backend.ACCESSIBILITY;
                if (prefs.fallbackProjection() && projection.isActive()) return Backend.PROJECTION;
                return null;
        }
    }

    // ------------------------------------------------------------------ detection

    /** Probes root and network adb in the background. force = re-probe even if already known. */
    public void detectAsync(boolean force) {
        detectExec.execute(() -> {
            if (force) {
                rootOk = null;
                AdbConnection a = adb;
                if (a != null) a.close();
                adb = null;
            }
            String m = prefs.method();
            if (m.equals(Prefs.METHOD_AUTO) || m.equals(Prefs.METHOD_ROOT)) probeRoot();
            if (m.equals(Prefs.METHOD_AUTO) && Boolean.TRUE.equals(rootOk)) {
                // root wins in auto mode; no need to touch adb
            } else if (m.equals(Prefs.METHOD_AUTO) || m.equals(Prefs.METHOD_ADB)) {
                ensureAdb();
            }
            onBackendsChanged();
        });
    }

    private synchronized void probeRoot() {
        if (rootOk != null) return;
        rootOk = rootShell.probe();
        Log.i(TAG, "root: " + rootOk);
    }

    /** Reads the adbd TCP port via the hidden SystemProperties API (reflection). */
    public static int systemAdbPort() {
        try {
            @SuppressLint("PrivateApi")
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            for (String key : new String[]{"service.adb.tcp.port", "persist.adb.tcp.port"}) {
                String v = (String) get.invoke(null, key, "");
                if (v != null && !v.isEmpty()) {
                    int p = Integer.parseInt(v.trim());
                    if (p > 0 && p < 65536) return p;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "SystemProperties: " + t);
        }
        return 0;
    }

    public int effectiveAdbPort() {
        int p = prefs.adbPort();
        if (p > 0) return p;
        p = systemAdbPort();
        return p > 0 ? p : 5555;
    }

    private synchronized AdbConnection ensureAdb() {
        AdbConnection a = adb;
        if (a != null && !a.isClosed()) return a;
        adb = null;
        int port = effectiveAdbPort();
        if (!AdbConnection.isPortOpen(port, 500)) {
            adbState = app.getString(R.string.adb_state_closed, port);
            return null;
        }
        try {
            AdbKey key = AdbKey.loadOrCreate(app);
            a = AdbConnection.connect(port, key, 60000, () -> {
                adbState = app.getString(R.string.adb_state_waiting, port);
                onBackendsChanged();
                toast(app.getString(R.string.toast_adb_allow));
            });
            adb = a;
            adbState = app.getString(R.string.adb_state_ok, port);
            Log.i(TAG, "adb connected on " + port);
            return a;
        } catch (IOException e) {
            adbState = app.getString(R.string.adb_state_failed, port, e.getMessage());
            Log.w(TAG, "adb: " + e);
            return null;
        }
    }

    /** Resolves the backend for a capture, probing lazily. Runs on the capture thread. */
    private Backend resolve() {
        String m = prefs.method();
        switch (m) {
            case Prefs.METHOD_ROOT:
                probeRoot();
                return Boolean.TRUE.equals(rootOk) ? Backend.ROOT : null;
            case Prefs.METHOD_ADB:
                return ensureAdb() != null ? Backend.ADB : null;
            case Prefs.METHOD_ACCESSIBILITY:
                return ShotAccessibilityService.canCapture() ? Backend.ACCESSIBILITY : null;
            case Prefs.METHOD_PROJECTION:
                return projection.isActive() ? Backend.PROJECTION : null;
            default:
                probeRoot();
                if (Boolean.TRUE.equals(rootOk)) return Backend.ROOT;
                if (ensureAdb() != null) return Backend.ADB;
                if (prefs.fallbackAccessibility() && ShotAccessibilityService.canCapture()) return Backend.ACCESSIBILITY;
                if (prefs.fallbackProjection() && projection.isActive()) return Backend.PROJECTION;
                return null;
        }
    }

    // ------------------------------------------------------------------ capture

    /** Entry point from notification / tile / UI. delaySeconds = extra wait before capturing. */
    public void trigger(int delaySeconds) {
        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "capture already running; ignored");
            return;
        }
        // Hide our own thumbnail first so it never ends up in the next screenshot.
        main.post(overlay::hideNow);
        main.post(ball::hideForCapture);
        captureExec.execute(() -> {
            try {
                runCapture(delaySeconds);
            } catch (Throwable t) {
                lastError = t.toString();
                Log.e(TAG, "capture failed", t);
            } finally {
                main.post(ball::restoreAfterCapture);
                busy.set(false);
            }
        });
    }

    private void runCapture(int delaySeconds) throws Exception {
        long t0 = SystemClock.uptimeMillis();
        Backend b = resolve();
        if (b == null) {
            lastError = app.getString(R.string.err_no_method);
            toast(app.getString(R.string.err_no_method));
            onBackendsChanged();
            return;
        }
        Shell shell = b == Backend.ROOT ? rootShell : b == Backend.ADB ? adb : null;

        collapseShade(shell);
        long wait = prefs.collapseWaitMs() + delaySeconds * 1000L - (SystemClock.uptimeMillis() - t0);
        // At least a couple of frames so the hidden floating ball / thumbnail are really off screen.
        if (wait < 80) wait = 80;
        Thread.sleep(wait);

        Rect crop = prefs.cropNavBar() ? NavBarProbe.contentRect(app, 600) : null;

        long tCap = SystemClock.uptimeMillis();
        Bitmap bmp;
        try {
            bmp = grab(b, shell);
        } catch (Exception e) {
            // Per spec: a failed capture does nothing further.
            lastError = b + ": " + e.getMessage();
            Log.w(TAG, "grab failed via " + b, e);
            if (b == Backend.ADB) { AdbConnection a = adb; if (a != null) a.close(); adb = null; }
            onBackendsChanged();
            return;
        }
        long capMs = SystemClock.uptimeMillis() - tCap;
        lastBackend = b;
        lastError = null;
        Log.i(TAG, "captured " + bmp.getWidth() + "x" + bmp.getHeight() + " via " + b + " in " + capMs + "ms");

        if (crop != null) bmp = applyCrop(bmp, crop);

        final Bitmap thumb = ThumbnailOverlay.makeThumb(bmp, app);
        final ThumbnailOverlay.Token token = overlay.newToken();
        if (prefs.showThumbnail()) main.post(() -> overlay.show(thumb, token));

        final Bitmap full = bmp;
        final Backend backend = b;
        saver.saveAsync(full, backend, shell, capMs, file -> {
            overlay.attachFile(token, file);
            if (file != null) {
                notifySaved(file);
                if (!prefs.showThumbnail() || !overlay.canDraw()) toast(app.getString(R.string.toast_saved, file.getName()));
            }
        });
    }

    private Bitmap grab(Backend b, Shell shell) throws Exception {
        switch (b) {
            case ROOT:
            case ADB:
                return RawScreencap.decode(shell.exec("screencap", 15000));
            case ACCESSIBILITY:
                ShotAccessibilityService s = ShotAccessibilityService.get();
                if (s == null || Build.VERSION.SDK_INT < 30) throw new IOException("accessibility service not running");
                return s.captureBlocking(5000);
            case PROJECTION:
                return projection.capture(1500);
        }
        throw new IOException("unknown backend");
    }

    private void collapseShade(Shell shell) {
        if (Build.VERSION.SDK_INT < 31) {
            try {
                //noinspection deprecation
                app.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            } catch (Exception ignored) {}
        }
        if (shell != null) {
            try {
                shell.exec(Build.VERSION.SDK_INT >= 24 ? "cmd statusbar collapse" : "service call statusbar 2", 3000);
            } catch (IOException e) {
                Log.w(TAG, "collapse via shell: " + e);
            }
        } else {
            ShotAccessibilityService s = ShotAccessibilityService.get();
            if (s != null) s.dismissShade();
        }
    }

    private static Bitmap applyCrop(Bitmap bmp, Rect screenRect) {
        Rect r = new Rect(screenRect);
        if (!r.intersect(0, 0, bmp.getWidth(), bmp.getHeight())) return bmp;
        if (r.width() == bmp.getWidth() && r.height() == bmp.getHeight()) return bmp;
        if (r.width() < bmp.getWidth() / 2 || r.height() < bmp.getHeight() / 2) return bmp; // implausible
        Bitmap c = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height());
        if (c != bmp) bmp.recycle();
        return c;
    }

    // ------------------------------------------------------------------ helpers

    void toast(String msg) {
        main.post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
    }

    /** Shell for auxiliary commands (logcat, dumpsys) if one is known to work; never prompts. */
    Shell auxShell() {
        if (Boolean.TRUE.equals(rootOk)) return rootShell;
        AdbConnection a = adb;
        return a != null && !a.isClosed() ? a : null;
    }
}
