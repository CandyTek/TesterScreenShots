package com.rc.testershot;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import java.security.SecureRandom;

/** Typed access to all app settings. */
public final class Prefs {
    public static final String METHOD_AUTO = "auto";
    public static final String METHOD_ROOT = "root";
    public static final String METHOD_ADB = "adb";
    public static final String METHOD_ACCESSIBILITY = "accessibility";
    public static final String METHOD_PROJECTION = "projection";

    public static final String FORMAT_JPEG = "jpeg";
    public static final String FORMAT_PNG = "png";
    public static final String FORMAT_WEBP = "webp";

    private final SharedPreferences sp;

    private static Prefs instance;

    public static synchronized Prefs get(Context c) {
        if (instance == null) instance = new Prefs(c.getApplicationContext());
        return instance;
    }

    private Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() { return sp; }

    // ---- workspace
    public String workspace() { return sp.getString("workspace", Workspaces.DEFAULT_NAME); }
    public void setWorkspace(String name) { sp.edit().putString("workspace", name).apply(); }

    // ---- capture
    public String method() { return sp.getString("method", METHOD_AUTO); }
    public void setMethod(String m) { sp.edit().putString("method", m).apply(); }
    /** In AUTO mode, may the accessibility service be used when root/adb are unavailable. */
    public boolean fallbackAccessibility() { return sp.getBoolean("fallback_accessibility", true); }
    public void setFallbackAccessibility(boolean b) { sp.edit().putBoolean("fallback_accessibility", b).apply(); }
    /** In AUTO mode, may screen-recording permission be used when root/adb are unavailable. */
    public boolean fallbackProjection() { return sp.getBoolean("fallback_projection", true); }
    public void setFallbackProjection(boolean b) { sp.edit().putBoolean("fallback_projection", b).apply(); }
    /** 0 = auto-detect (system property, then 5555). */
    public int adbPort() { return sp.getInt("adb_port", 0); }
    public void setAdbPort(int p) { sp.edit().putInt("adb_port", p).apply(); }
    /** Time to wait for the notification shade to finish collapsing. */
    public int collapseWaitMs() { return sp.getInt("collapse_wait_ms", 450); }
    public void setCollapseWaitMs(int v) { sp.edit().putInt("collapse_wait_ms", v).apply(); }
    public boolean cropNavBar() { return sp.getBoolean("crop_navbar", false); }
    public void setCropNavBar(boolean b) { sp.edit().putBoolean("crop_navbar", b).apply(); }

    // ---- output
    public String format() { return sp.getString("format", FORMAT_JPEG); }
    public void setFormat(String f) { sp.edit().putString("format", f).apply(); }
    public int quality() { return sp.getInt("quality", 95); }
    public void setQuality(int q) { sp.edit().putInt("quality", Math.max(1, Math.min(100, q))).apply(); }
    public boolean saveMeta() { return sp.getBoolean("save_meta", true); }
    public void setSaveMeta(boolean b) { sp.edit().putBoolean("save_meta", b).apply(); }
    public boolean saveLogcat() { return sp.getBoolean("save_logcat", false); }
    public void setSaveLogcat(boolean b) { sp.edit().putBoolean("save_logcat", b).apply(); }
    public int logcatLines() { return sp.getInt("logcat_lines", 500); }
    public void setLogcatLines(int n) { sp.edit().putInt("logcat_lines", n).apply(); }

    public String extension() {
        switch (format()) {
            case FORMAT_PNG: return "png";
            case FORMAT_WEBP: return "webp";
            default: return "jpg";
        }
    }

    @SuppressWarnings("deprecation")
    public Bitmap.CompressFormat compressFormat() {
        switch (format()) {
            case FORMAT_PNG: return Bitmap.CompressFormat.PNG;
            case FORMAT_WEBP: return Bitmap.CompressFormat.WEBP;
            default: return Bitmap.CompressFormat.JPEG;
        }
    }

    // ---- thumbnail overlay
    public boolean showThumbnail() { return sp.getBoolean("show_thumb", true); }
    public void setShowThumbnail(boolean b) { sp.edit().putBoolean("show_thumb", b).apply(); }
    public int thumbnailSeconds() { return sp.getInt("thumb_seconds", 4); }
    public void setThumbnailSeconds(int s) { sp.edit().putInt("thumb_seconds", s).apply(); }

    // ---- http server
    public boolean serverEnabled() { return sp.getBoolean("server_enabled", true); }
    public void setServerEnabled(boolean b) { sp.edit().putBoolean("server_enabled", b).apply(); }
    public int serverPort() { return sp.getInt("server_port", 8848); }
    public void setServerPort(int p) { sp.edit().putInt("server_port", p).apply(); }
    /** Web page "download selected": false = individual files (default), true = one zip. */
    public boolean serverZip() { return sp.getBoolean("server_zip", false); }
    public void setServerZip(boolean b) { sp.edit().putBoolean("server_zip", b).apply(); }

    /** Random access token embedded in the QR link; generated once. */
    public synchronized String serverToken() {
        String t = sp.getString("server_token", null);
        if (t == null) {
            t = newToken();
            sp.edit().putString("server_token", t).apply();
        }
        return t;
    }

    public void regenerateToken() { sp.edit().putString("server_token", newToken()).apply(); }

    private static String newToken() {
        final String chars = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
