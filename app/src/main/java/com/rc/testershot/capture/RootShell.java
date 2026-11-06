package com.rc.testershot.capture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs commands through `su -c`. One process per command; stdout is read to EOF (binary safe). */
public final class RootShell implements Shell {

    @Override
    public String name() { return "root"; }

    /** Cheap check: is there an su binary on the usual paths? (Does not prompt.) */
    public static boolean hasSuBinary() {
        String path = System.getenv("PATH");
        String[] dirs = (path != null ? path : "").split(":");
        for (String d : dirs) if (!d.isEmpty() && new File(d, "su").exists()) return true;
        for (String d : new String[]{"/system/xbin", "/system/bin", "/sbin", "/su/bin", "/system_ext/bin", "/debug_ramdisk", "/data/adb/ksu/bin"}) {
            if (new File(d, "su").exists()) return true;
        }
        return false;
    }

    /** Full check: actually runs `su -c id` (may show a root-manager prompt). */
    public boolean probe() {
        if (!hasSuBinary()) return false;
        try {
            String out = execText("id", 15000);
            return out.contains("uid=0");
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public byte[] exec(String command, long timeoutMs) throws IOException {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(false).start();
        try {
            p.getOutputStream().close();
            // stderr must be drained or a chatty command can block.
            final InputStream err = p.getErrorStream();
            Thread drain = new Thread(() -> {
                byte[] b = new byte[1024];
                try { while (err.read(b) >= 0) { /* discard */ } } catch (IOException ignored) {}
            }, "su-stderr");
            drain.setDaemon(true);
            drain.start();

            final Process proc = p;
            Thread killer = new Thread(() -> {
                try {
                    if (!waitFor(proc, timeoutMs)) proc.destroy();
                } catch (InterruptedException ignored) {}
            }, "su-timeout");
            killer.setDaemon(true);
            killer.start();

            byte[] out = readAll(p.getInputStream(), 1 << 20);
            try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            killer.interrupt();
            if (p.exitValue() != 0 && out.length == 0) {
                throw new IOException("su exited with " + p.exitValue());
            }
            return out;
        } finally {
            p.destroy();
        }
    }

    private static boolean waitFor(Process p, long timeoutMs) throws InterruptedException {
        if (android.os.Build.VERSION.SDK_INT >= 26) return p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try { p.exitValue(); return true; } catch (IllegalThreadStateException e) { Thread.sleep(50); }
        }
        return false;
    }

    static byte[] readAll(InputStream in, int initial) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(initial);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    @SuppressWarnings("unused")
    static String text(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
