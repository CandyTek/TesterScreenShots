package com.rc.testershot;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A workspace is a folder under Pictures/TesterScreenShot/. Images live directly in it;
 * sidecar files (same base name, .txt metadata / .log logcat) sit next to them.
 * Uses legacy external storage (targetSdk 29 + requestLegacyExternalStorage).
 */
public final class Workspaces {
    public static final String DEFAULT_NAME = "Default";
    public static final String ROOT_DIR_NAME = "TesterScreenShot";

    private Workspaces() {}

    public static File root() {
        @SuppressWarnings("deprecation")
        File pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        return new File(pics, ROOT_DIR_NAME);
    }

    /** Removes characters that are illegal in file names; returns null if nothing usable remains. */
    public static String sanitize(String name) {
        if (name == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char ch : name.trim().toCharArray()) {
            if (ch < 0x20 || "\\/:*?\"<>|".indexOf(ch) >= 0) sb.append('_');
            else sb.append(ch);
        }
        String s = sb.toString();
        while (s.startsWith(".")) s = s.substring(1);
        s = s.trim();
        if (s.isEmpty()) return null;
        if (s.length() > 64) s = s.substring(0, 64);
        return s;
    }

    public static File dir(String workspace) {
        return new File(root(), workspace);
    }

    public static File currentDir(Context c) {
        File d = dir(Prefs.get(c).workspace());
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** All workspace names, sorted case-insensitively; always contains the current one. */
    public static List<String> list(Context c) {
        List<String> out = new ArrayList<>();
        File[] dirs = root().listFiles(File::isDirectory);
        if (dirs != null) for (File d : dirs) if (!d.getName().startsWith(".")) out.add(d.getName());
        String cur = Prefs.get(c).workspace();
        if (!out.contains(cur)) out.add(cur);
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public static boolean exists(String name) {
        return dir(name).isDirectory();
    }

    public static boolean create(String name) {
        File d = dir(name);
        return d.isDirectory() || d.mkdirs();
    }

    public static boolean rename(Context c, String from, String to) {
        File src = dir(from), dst = dir(to);
        if (dst.exists()) return false;
        List<File> before = listImages(src);
        boolean ok = !src.exists() ? dst.mkdirs() : src.renameTo(dst);
        if (ok) {
            if (Prefs.get(c).workspace().equals(from)) Prefs.get(c).setWorkspace(to);
            scan(c, before);
            scan(c, listImages(dst));
        }
        return ok;
    }

    /** Deletes the workspace folder and everything in it. */
    public static boolean delete(Context c, String name) {
        File d = dir(name);
        List<File> imgs = listImages(d);
        File[] all = d.listFiles();
        if (all != null) for (File f : all) //noinspection ResultOfMethodCallIgnored
            f.delete();
        boolean ok = d.delete() || !d.exists();
        scan(c, imgs);
        if (Prefs.get(c).workspace().equals(name)) {
            List<String> rest = list(c);
            rest.remove(name);
            Prefs.get(c).setWorkspace(rest.isEmpty() ? DEFAULT_NAME : rest.get(0));
        }
        return ok;
    }

    public static boolean isImage(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return !n.startsWith(".") && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp"));
    }

    /** Images of a workspace dir, newest first. */
    public static List<File> listImages(File dir) {
        File[] files = dir.listFiles(f -> f.isFile() && isImage(f.getName()));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, (a, b) -> {
            int cmp = Long.compare(b.lastModified(), a.lastModified());
            return cmp != 0 ? cmp : b.getName().compareTo(a.getName());
        });
        return new ArrayList<>(Arrays.asList(files));
    }

    public static int count(String workspace) {
        return listImages(dir(workspace)).size();
    }

    /** Sidecar files (metadata .txt, logcat .log) belonging to an image. */
    public static List<File> sidecars(File image) {
        List<File> out = new ArrayList<>();
        String base = baseName(image.getName());
        for (String ext : new String[]{".txt", ".log"}) {
            File f = new File(image.getParentFile(), base + ext);
            if (f.isFile()) out.add(f);
        }
        return out;
    }

    /** Deletes an image and its sidecars, and tells the media store. */
    public static boolean deleteImage(Context c, File image) {
        for (File s : sidecars(image)) //noinspection ResultOfMethodCallIgnored
            s.delete();
        boolean ok = image.delete();
        scan(c, Collections.singletonList(image));
        return ok;
    }

    public static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Returns a non-existing file in dir with the given base name and extension. */
    public static File uniqueFile(File dir, String base, String ext) {
        File f = new File(dir, base + "." + ext);
        int i = 2;
        while (f.exists()) f = new File(dir, base + "_" + (i++) + "." + ext);
        return f;
    }

    /** Notifies the media store that these files were added, changed or removed. */
    public static void scan(Context c, List<File> files) {
        if (files == null || files.isEmpty()) return;
        String[] paths = new String[files.size()];
        for (int i = 0; i < paths.length; i++) paths[i] = files.get(i).getAbsolutePath();
        MediaScannerConnection.scanFile(c.getApplicationContext(), paths, null, null);
    }

    public static void scan(Context c, File file) {
        scan(c, Collections.singletonList(file));
    }
}
