package com.rc.testershot.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.rc.testershot.Prefs;
import com.rc.testershot.R;
import com.rc.testershot.capture.CaptureEngine;
import com.rc.testershot.capture.ShotAccessibilityService;
import com.rc.testershot.server.LanServer;
import com.rc.testershot.ui.MainActivity;
import com.rc.testershot.ui.TriggerActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service: keeps the "Screenshot" notification in the shade, hosts the LAN server
 * and the screen-recording session.
 */
public class ShotService extends Service implements CaptureEngine.Listener {
    private static final String TAG = "ShotService";
    public static final String CHANNEL = "shot";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_START = "com.rc.testershot.START";
    public static final String ACTION_CAPTURE = "com.rc.testershot.CAPTURE";
    public static final String ACTION_PROJECTION = "com.rc.testershot.PROJECTION";
    public static final String ACTION_SERVER = "com.rc.testershot.SERVER";
    public static final String ACTION_REFRESH = "com.rc.testershot.REFRESH";
    public static final String EXTRA_DELAY = "delay";
    public static final String EXTRA_RESULT_CODE = "code";
    public static final String EXTRA_DATA = "data";

    private static volatile ShotService running;
    private static final LanServerHolder server = new LanServerHolder();

    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> new Thread(r, "service-bg"));
    private CaptureEngine engine;

    public static boolean isRunning() { return running != null; }

    public static void start(Context c, String action) {
        Intent i = new Intent(c, ShotService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static Intent captureIntent(Context c, int delay) {
        return new Intent(c, ShotService.class).setAction(ACTION_CAPTURE).putExtra(EXTRA_DELAY, delay);
    }

    /** The running LAN server, or null if disabled / failed to start. */
    public static LanServer server() { return server.get(); }

    public static String serverError() { return server.error; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        createChannel(this);
        engine = CaptureEngine.get(this);
        engine.addListener(this);
        startForeground(NOTIF_ID, buildNotification());
        engine.detectAsync(false);
        applyServer();
        engine.floatingBall().apply();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        engine.floatingBall().onConfigurationChanged();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always re-assert foreground (startForegroundService contract).
        startForeground(NOTIF_ID, buildNotification());
        // Settings / overlay permission may have changed.
        engine.floatingBall().apply();
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CAPTURE.equals(action)) {
            engine.trigger(intent.getIntExtra(EXTRA_DELAY, 0));
        } else if (ACTION_PROJECTION.equals(action)) {
            int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            if (data != null) {
                try {
                    engine.projection().start(code, data);
                } catch (Exception e) {
                    Log.e(TAG, "projection start", e);
                }
                engine.onBackendsChanged();
            }
        } else if (ACTION_SERVER.equals(action)) {
            applyServer();
        } else if (ACTION_REFRESH.equals(action)) {
            refreshNotification();
        }
        return START_STICKY;
    }

    private void applyServer() {
        final Prefs p = Prefs.get(this);
        bg.execute(() -> {
            server.apply(this, p.serverEnabled(), p.serverPort());
            engine.onBackendsChanged();
        });
    }

    @Override
    public void onBackendsChanged() {
        refreshNotification();
    }

    @Override
    public void onShotSaved(File file) {
        refreshNotification();
    }

    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification());
    }

    public static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    /**
     * Notification buttons must collapse the shade before the capture. A root/adb shell or the
     * accessibility service can do that from a service; otherwise on Android 12+ we go through a
     * transparent trampoline activity (launching an activity always collapses the shade).
     */
    private PendingIntent capturePending(int delay, int requestCode) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        boolean serviceCanCollapse = Build.VERSION.SDK_INT < 31 || engine.shellAvailable() || ShotAccessibilityService.isRunning();
        if (serviceCanCollapse) {
            Intent i = captureIntent(this, delay);
            return Build.VERSION.SDK_INT >= 26
                    ? PendingIntent.getForegroundService(this, requestCode, i, flags)
                    : PendingIntent.getService(this, requestCode, i, flags);
        }
        Intent t = TriggerActivity.intent(this, delay);
        return PendingIntent.getActivity(this, requestCode, t, flags);
    }

    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 10,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP), flags);

        Prefs p = Prefs.get(this);
        CaptureEngine.Backend b = engine != null ? engine.predictBackend() : null;
        String methodText = b == null ? getString(R.string.notif_no_method) : backendLabel(this, b);
        String text = getString(R.string.notif_text, p.workspace(), methodText);

        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        nb.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(R.drawable.ic_camera, getString(R.string.action_screenshot), capturePending(0, 1)).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_timer, getString(R.string.action_screenshot_delay), capturePending(3, 2)).build());
        if (Build.VERSION.SDK_INT < 26) nb.setPriority(Notification.PRIORITY_LOW);
        return nb.build();
    }

    public static String backendLabel(Context c, CaptureEngine.Backend b) {
        switch (b) {
            case ROOT: return c.getString(R.string.method_root);
            case ADB: return c.getString(R.string.method_adb);
            case ACCESSIBILITY: return c.getString(R.string.method_accessibility);
            default: return c.getString(R.string.method_projection);
        }
    }

    @Override
    public void onDestroy() {
        engine.removeListener(this);
        engine.floatingBall().remove();
        server.apply(this, false, 0);
        running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /** Owns the LanServer instance and remembers why it isn't running. */
    private static final class LanServerHolder {
        private LanServer server;
        volatile String error;

        synchronized LanServer get() {
            return server != null && server.isRunning() ? server : null;
        }

        synchronized void apply(Context c, boolean enabled, int port) {
            if (!enabled) {
                if (server != null) server.stop();
                error = null;
                return;
            }
            if (server == null) server = new LanServer(c.getApplicationContext());
            try {
                server.start(port);
                error = null;
            } catch (Exception e) {
                error = e.getMessage();
                Log.e(TAG, "server start on " + port, e);
            }
        }
    }
}
