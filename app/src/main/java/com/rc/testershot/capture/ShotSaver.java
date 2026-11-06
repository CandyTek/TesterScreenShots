package com.rc.testershot.capture;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.rc.testershot.BuildConfig;
import com.rc.testershot.Prefs;
import com.rc.testershot.Workspaces;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Encodes and writes screenshots plus optional sidecars (.txt metadata, .log logcat) off the capture thread. */
final class ShotSaver {
    private static final String TAG = "ShotSaver";
    private static final Pattern FOCUS = Pattern.compile("u\\d+ ([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)");

    interface Callback {
        void onSaved(File fileOrNull);
    }

    private final Context app;
    private final Prefs prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "shot-save"));
    private final Handler main = new Handler(Looper.getMainLooper());

    ShotSaver(Context app) {
        this.app = app;
        this.prefs = Prefs.get(app);
    }

    void saveAsync(Bitmap bmp, CaptureEngine.Backend backend, Shell shell, long captureMs, Callback cb) {
        final Date when = new Date();
        io.execute(() -> {
            File out = null;
            try {
                Shell aux = shell != null ? shell : CaptureEngine.get(app).auxShell();
                String[] fg = foreground(aux);

                File dir = Workspaces.currentDir(app);
                String base = "Screenshot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(when);
                if (fg[0] != null) base += "_" + shortPkg(fg[0]);
                out = Workspaces.uniqueFile(dir, base, prefs.extension());

                long t = System.currentTimeMillis();
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 256 * 1024)) {
                    if (!bmp.compress(prefs.compressFormat(), prefs.quality(), os)) throw new IOException("compress failed");
                }
                long encMs = System.currentTimeMillis() - t;
                List<File> written = new ArrayList<>();
                written.add(out);

                if (prefs.saveMeta()) {
                    File meta = new File(dir, Workspaces.baseName(out.getName()) + ".txt");
                    writeText(meta, metadata(when, backend, bmp, captureMs, encMs, fg));
                }
                if (prefs.saveLogcat()) {
                    byte[] log = logcat(aux, prefs.logcatLines());
                    if (log != null && log.length > 0) {
                        File lf = new File(dir, Workspaces.baseName(out.getName()) + ".log");
                        try (OutputStream os = new FileOutputStream(lf)) { os.write(log); }
                    }
                }
                Workspaces.scan(app, written);
                Log.i(TAG, "saved " + out + " (" + out.length() / 1024 + " KB, encode " + encMs + "ms)");
            } catch (Throwable e) {
                Log.e(TAG, "save failed", e);
                if (out != null) //noinspection ResultOfMethodCallIgnored
                    out.delete();
                out = null;
            } finally {
                bmp.recycle();
            }
            final File result = out;
            main.post(() -> cb.onSaved(result));
        });
    }

    /** [package, activity] of the foreground app, either may be null. */
    private String[] foreground(Shell shell) {
        String pkg = ShotAccessibilityService.foregroundPackage();
        String cls = ShotAccessibilityService.foregroundClass();
        if (pkg != null && ShotAccessibilityService.isRunning()) return new String[]{pkg, cls};
        if (shell != null) {
            try {
                String out = shell.execText("dumpsys window | grep -E 'mFocusedApp|mCurrentFocus'", 4000);
                String focusedApp = null, currentFocus = null;
                for (String line : out.split("\n")) {
                    Matcher m = FOCUS.matcher(line);
                    if (!m.find()) continue;
                    if (line.contains("mFocusedApp") && focusedApp == null) focusedApp = m.group(1) + "/" + m.group(2);
                    if (line.contains("mCurrentFocus") && currentFocus == null) currentFocus = m.group(1) + "/" + m.group(2);
                }
                String s = focusedApp != null ? focusedApp : currentFocus;
                if (s != null) {
                    int slash = s.indexOf('/');
                    String p = s.substring(0, slash), c = s.substring(slash + 1);
                    if (c.startsWith(".")) c = p + c;
                    return new String[]{p, c};
                }
            } catch (IOException e) {
                Log.w(TAG, "foreground: " + e);
            }
        }
        return new String[]{null, null};
    }

    private static String shortPkg(String pkg) {
        String s = pkg.replaceAll("[^A-Za-z0-9_.]", "_");
        return s.length() > 48 ? s.substring(s.length() - 48) : s;
    }

    private String metadata(Date when, CaptureEngine.Backend backend, Bitmap bmp, long capMs, long encMs, String[] fg) {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        StringBuilder sb = new StringBuilder();
        sb.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(when)).append('\n');
        sb.append("workspace: ").append(prefs.workspace()).append('\n');
        if (fg[0] != null) {
            sb.append("app: ").append(fg[0]).append('\n');
            sb.append("app_version: ").append(appVersion(fg[0])).append('\n');
        }
        if (fg[1] != null) sb.append("activity: ").append(fg[1]).append('\n');
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("build: ").append(Build.FINGERPRINT).append('\n');
        sb.append("display: ").append(dm.widthPixels).append('x').append(dm.heightPixels)
                .append(" @ ").append(dm.densityDpi).append("dpi\n");
        sb.append("image: ").append(bmp.getWidth()).append('x').append(bmp.getHeight()).append('\n');
        sb.append("capture: ").append(backend.name().toLowerCase(Locale.ROOT)).append(", ")
                .append(capMs).append(" ms grab, ").append(encMs).append(" ms encode\n");
        sb.append("tool: TesterScreenShot ").append(BuildConfig.VERSION_NAME).append('\n');
        return sb.toString();
    }

    private String appVersion(String pkg) {
        try {
            android.content.pm.PackageInfo pi = app.getPackageManager().getPackageInfo(pkg, 0);
            long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return pi.versionName + " (" + code + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private byte[] logcat(Shell shell, int lines) {
        String cmd = "logcat -d -v threadtime -t " + Math.max(10, lines);
        try {
            if (shell != null) return shell.exec(cmd, 8000);
            if (app.checkPermission("android.permission.READ_LOGS", android.os.Process.myPid(), android.os.Process.myUid())
                    == PackageManager.PERMISSION_GRANTED) {
                Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "threadtime", "-t", String.valueOf(Math.max(10, lines))});
                try (InputStream in = p.getInputStream()) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] b = new byte[32768];
                    int n;
                    while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                    return bos.toByteArray();
                } finally {
                    p.destroy();
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "logcat: " + e);
        }
        return null;
    }

    private static void writeText(File f, String s) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
