package com.rc.testershot.server;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Log;

import com.rc.testershot.Prefs;
import com.rc.testershot.Workspaces;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal LAN HTTP/1.1 server (Connection: close) that lets a desktop browser browse and
 * download the screenshots of a workspace. Bound to the wildcard address so it answers on
 * every interface (Wi-Fi, Ethernet, hotspot, USB tethering) at once. Every request must carry
 * the access token (query ?t= or cookie t=).
 */
public class LanServer {
    private static final String TAG = "LanServer";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private static final int THREADS = 8;
    private static final int SO_TIMEOUT_MS = 20000;
    private static final int MAX_LINE = 8192;
    private static final int MAX_HEADERS = 100;
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final int THUMB_SIZE = 360;
    private static final int THUMB_QUALITY = 75;
    private static final long THUMB_CACHE_MAX_BYTES = 64L * 1024 * 1024;
    private static final String INDEX_ASSET = "web/index.html";

    private final Context ctx;
    private final File thumbDir;
    /** Limits concurrent bitmap decoding to keep memory in check. */
    private final Semaphore decodeSlots = new Semaphore(2);
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private volatile boolean running;
    private volatile int port;

    public LanServer(Context ctx) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.thumbDir = new File(this.ctx.getCacheDir(), "thumbs");
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start(int port) throws IOException {
        if (running) {
            if (this.port == port) return;
            stop();
        }
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            // Wildcard address (0.0.0.0 / ::) -> every interface.
            ss.bind(new InetSocketAddress(port), 50);
        } catch (IOException e) {
            try { ss.close(); } catch (IOException ignored) {}
            throw e;
        }
        serverSocket = ss;
        this.port = ss.getLocalPort();
        final AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(THREADS, new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "LanServer-worker-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        running = true;
        final ServerSocket acceptOn = ss;
        final ExecutorService workers = pool;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(acceptOn, workers); }
        }, "LanServer-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        new Thread(new Runnable() {
            @Override public void run() { trimThumbCache(); }
        }, "LanServer-trim").start();
        Log.i(TAG, "listening on port " + this.port);
    }

    public synchronized void stop() {
        if (!running && serverSocket == null) return;
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        synchronized (clients) {
            for (Socket s : clients) {
                try { s.close(); } catch (IOException ignored) {}
            }
            clients.clear();
        }
        acceptThread = null;
        Log.i(TAG, "stopped");
    }

    public boolean isRunning() { return running; }

    public int getPort() { return port; }

    private void acceptLoop(ServerSocket ss, ExecutorService workers) {
        while (running && !ss.isClosed()) {
            final Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
                if (ss.isClosed()) break;
                continue;
            } catch (Throwable t) {
                Log.e(TAG, "accept loop error", t);
                continue;
            }
            try {
                s.setSoTimeout(SO_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                clients.add(s);
                workers.execute(new Runnable() {
                    @Override public void run() { serve(s); }
                });
            } catch (RejectedExecutionException | IOException e) {
                clients.remove(s);
                closeQuietly(s);
            }
        }
    }

    // ------------------------------------------------------------------ request handling

    private static final class Request {
        String method;
        String rawPath;
        String query;
        final Map<String, String> headers = new LinkedHashMap<>();
        final Map<String, List<String>> params = new LinkedHashMap<>();

        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }

        String param(String name) {
            List<String> l = params.get(name);
            return l == null || l.isEmpty() ? null : l.get(0);
        }

        List<String> params(String name) {
            List<String> l = params.get(name);
            return l == null ? Collections.<String>emptyList() : l;
        }
    }

    /** Thrown to abort a request with a specific status. */
    private static final class HttpError extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        HttpError(int status, String msg) { super(msg); this.status = status; }
    }

    private void serve(Socket s) {
        try {
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 32 * 1024);
            Request req;
            try {
                req = readRequest(in);
            } catch (HttpError e) {
                sendError(out, e.status, e.getMessage(), false);
                return;
            }
            if (req == null) return; // client closed without sending anything
            boolean head = "HEAD".equals(req.method);
            try {
                handle(req, out, head);
            } catch (HttpError e) {
                sendError(out, e.status, e.getMessage(), head);
            } catch (RuntimeException e) {
                Log.e(TAG, "handler error for " + req.method + " " + req.rawPath, e);
                sendError(out, 500, "Internal error", head);
            }
        } catch (SocketTimeoutException | SocketException e) {
            // client went away or idled; nothing to do
        } catch (IOException e) {
            Log.d(TAG, "connection io: " + e);
        } catch (Throwable t) {
            Log.e(TAG, "unexpected error while serving", t);
        } finally {
            clients.remove(s);
            closeQuietly(s);
        }
    }

    private Request readRequest(InputStream in) throws IOException, HttpError {
        String line = readLine(in);
        if (line == null) return null;
        while (line.isEmpty()) { // tolerate leading CRLFs
            line = readLine(in);
            if (line == null) return null;
        }
        String[] parts = line.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/")) throw new HttpError(400, "Bad request line");
        Request req = new Request();
        req.method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        if (target.startsWith("http://") || target.startsWith("https://")) { // absolute-form
            int p = target.indexOf('/', target.indexOf("//") + 2);
            target = p < 0 ? "/" : target.substring(p);
        }
        if (!target.startsWith("/")) throw new HttpError(400, "Bad request target");
        int hash = target.indexOf('#');
        if (hash >= 0) target = target.substring(0, hash);
        int q = target.indexOf('?');
        req.rawPath = q < 0 ? target : target.substring(0, q);
        req.query = q < 0 ? "" : target.substring(q + 1);

        int count = 0;
        while (true) {
            String h = readLine(in);
            if (h == null) throw new HttpError(400, "Unexpected end of headers");
            if (h.isEmpty()) break;
            if (++count > MAX_HEADERS) throw new HttpError(431, "Too many headers");
            int c = h.indexOf(':');
            if (c <= 0) continue;
            String name = h.substring(0, c).trim().toLowerCase(Locale.ROOT);
            String value = h.substring(c + 1).trim();
            String prev = req.headers.get(name);
            req.headers.put(name, prev == null ? value : prev + ("cookie".equals(name) ? "; " : ", ") + value);
        }

        parseForm(req.query, req.params);

        if ("POST".equals(req.method)) {
            String te = req.header("transfer-encoding");
            if (te != null && !"identity".equalsIgnoreCase(te)) throw new HttpError(411, "Length required");
            String cl = req.header("content-length");
            int len = 0;
            if (cl != null) {
                try { len = Integer.parseInt(cl.trim()); } catch (NumberFormatException e) { throw new HttpError(400, "Bad Content-Length"); }
            }
            if (len < 0) throw new HttpError(400, "Bad Content-Length");
            if (len > MAX_BODY) throw new HttpError(413, "Request body too large");
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(body, off, len - off);
                if (r < 0) throw new HttpError(400, "Truncated body");
                off += r;
            }
            String ct = req.header("content-type");
            if (len > 0 && (ct == null || ct.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded"))) {
                parseForm(new String(body, LATIN1), req.params);
            }
        }
        return req;
    }

    /** Reads a CRLF (or LF) terminated line as ISO-8859-1; null on EOF before any byte. */
    private static String readLine(InputStream in) throws IOException, HttpError {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        boolean any = false;
        while ((b = in.read()) != -1) {
            any = true;
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
            if (buf.size() > MAX_LINE) throw new HttpError(414, "Line too long");
        }
        if (!any) return null;
        return new String(buf.toByteArray(), LATIN1);
    }

    /** Parses application/x-www-form-urlencoded data (also used for the query string). */
    private static void parseForm(String s, Map<String, List<String>> into) {
        if (s == null || s.isEmpty()) return;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                k = URLDecoder.decode(k, "UTF-8");
                v = URLDecoder.decode(v, "UTF-8");
            } catch (IllegalArgumentException | UnsupportedEncodingException e) {
                continue; // skip malformed pair
            }
            List<String> l = into.get(k);
            if (l == null) { l = new ArrayList<>(); into.put(k, l); }
            if (l.size() < 10000) l.add(v);
        }
    }

    /** Percent-decodes a path segment as UTF-8 ('+' stays literal). */
    private static String decodePathSegment(String s) throws HttpError {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) throw new HttpError(400, "Bad escape");
                int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) throw new HttpError(400, "Bad escape");
                out.write((hi << 4) | lo);
                i += 2;
            } else if (c < 0x80) {
                out.write(c);
            } else {
                byte[] b = String.valueOf(c).getBytes(UTF8);
                out.write(b, 0, b.length);
            }
        }
        return new String(out.toByteArray(), UTF8);
    }

    private void handle(Request req, OutputStream out, boolean head) throws IOException, HttpError {
        String path = req.rawPath;
        if ("/favicon.ico".equals(path)) {
            sendBytes(out, 204, null, new byte[0], null, head);
            return;
        }
        boolean isGet = "GET".equals(req.method) || head;
        boolean isPost = "POST".equals(req.method);
        if (!isGet && !isPost) throw new HttpError(405, "Method not allowed");

        // ---- auth
        String expected = Prefs.get(ctx).serverToken();
        String qt = req.param("t");
        boolean viaQuery = qt != null && tokenEquals(qt, expected);
        boolean ok = viaQuery || tokenEquals(cookie(req, "t"), expected);
        if (!ok) throw new HttpError(403, "Access denied: missing or wrong token. Scan the QR code in the app again.");
        String setCookie = viaQuery
                ? "t=" + expected + "; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax"
                : null;

        // ---- routing
        String[] seg = splitPath(path);
        if (seg.length == 0) {
            requireGet(isGet);
            serveIndex(out, setCookie, head);
        } else if (seg.length == 2 && "api".equals(seg[0]) && "workspaces".equals(seg[1])) {
            requireGet(isGet);
            serveWorkspaces(out, setCookie, head);
        } else if (seg.length == 2 && "api".equals(seg[0]) && "list".equals(seg[1])) {
            requireGet(isGet);
            serveList(req, out, setCookie, head);
        } else if (seg.length == 3 && "img".equals(seg[0])) {
            requireGet(isGet);
            serveFile(req, out, setCookie, head, seg[1], seg[2]);
        } else if (seg.length == 3 && "thumb".equals(seg[0])) {
            requireGet(isGet);
            serveThumb(req, out, setCookie, head, seg[1], seg[2]);
        } else if (seg.length == 1 && "zip".equals(seg[0])) {
            serveZip(req, out, setCookie, head);
        } else if (seg.length == 1 && "index.html".equals(seg[0])) {
            requireGet(isGet);
            serveIndex(out, setCookie, head);
        } else {
            throw new HttpError(404, "Not found");
        }
    }

    private static void requireGet(boolean isGet) throws HttpError {
        if (!isGet) throw new HttpError(405, "Method not allowed");
    }

    private static String[] splitPath(String rawPath) throws HttpError {
        List<String> out = new ArrayList<>();
        for (String p : rawPath.split("/")) {
            if (p.isEmpty()) continue;
            out.add(decodePathSegment(p));
        }
        return out.toArray(new String[0]);
    }

    private static String cookie(Request req, String name) {
        String h = req.header("cookie");
        if (h == null) return null;
        for (String part : h.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            if (part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
        }
        return null;
    }

    /** Constant-time comparison. */
    private static boolean tokenEquals(String a, String b) {
        if (a == null || b == null || a.isEmpty()) return false;
        byte[] x = a.getBytes(UTF8), y = b.getBytes(UTF8);
        return MessageDigest.isEqual(x, y);
    }

    // ------------------------------------------------------------------ path safety

    /** A single path component that may be used as a workspace or file name. */
    private static boolean isSafeName(String n) {
        if (n == null || n.isEmpty() || n.length() > 255) return false;
        if (n.equals(".") || n.equals("..") || n.startsWith(".")) return false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c == '/' || c == '\\' || c == ':' || c == 0x7f) return false;
        }
        return true;
    }

    private static boolean isInside(File child, File parent) {
        try {
            String c = child.getCanonicalPath();
            String p = parent.getCanonicalPath();
            return c.startsWith(p + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private static File workspaceDir(String ws) throws HttpError {
        if (!isSafeName(ws)) throw new HttpError(400, "Bad workspace name");
        File d = Workspaces.dir(ws);
        if (!isInside(d, Workspaces.root())) throw new HttpError(400, "Bad workspace name");
        return d;
    }

    private static File workspaceFile(String ws, String name) throws HttpError {
        File d = workspaceDir(ws);
        if (!isSafeName(name)) throw new HttpError(400, "Bad file name");
        File f = new File(d, name);
        if (!isInside(f, d)) throw new HttpError(400, "Bad file name");
        if (!f.isFile()) throw new HttpError(404, "File not found");
        if (!Workspaces.isImage(name) && !isSidecarName(name)) throw new HttpError(403, "File type not served");
        return f;
    }

    private static boolean isSidecarName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".txt") || l.endsWith(".log");
    }

    private String wsParam(Request req) {
        String ws = req.param("ws");
        return ws == null || ws.isEmpty() ? Prefs.get(ctx).workspace() : ws;
    }

    // ------------------------------------------------------------------ endpoints

    private void serveIndex(OutputStream out, String setCookie, boolean head) throws IOException, HttpError {
        byte[] page;
        try (InputStream in = ctx.getAssets().open(INDEX_ASSET)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(32 * 1024);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            page = bo.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "missing asset " + INDEX_ASSET, e);
            throw new HttpError(500, "Web page asset missing");
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cache-Control", "no-cache");
        if (setCookie != null) h.put("Set-Cookie", setCookie);
        sendBytes(out, 200, "text/html; charset=utf-8", page, h, head);
    }

    private void serveWorkspaces(OutputStream out, String setCookie, boolean head) throws IOException {
        String cur = Prefs.get(ctx).workspace();
        JSONArray arr = new JSONArray();
        try {
            for (String name : Workspaces.list(ctx)) {
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("count", Workspaces.count(name));
                o.put("current", name.equals(cur));
                arr.put(o);
            }
        } catch (JSONException e) {
            Log.w(TAG, "json", e);
        }
        sendJson(out, arr.toString(), setCookie, head);
    }

    private void serveList(Request req, OutputStream out, String setCookie, boolean head) throws IOException, HttpError {
        String ws = wsParam(req);
        File dir = workspaceDir(ws);
        if (!dir.isDirectory() && !ws.equals(Prefs.get(ctx).workspace())) throw new HttpError(404, "No such workspace");
        JSONObject root = new JSONObject();
        try {
            root.put("workspace", ws);
            root.put("current", ws.equals(Prefs.get(ctx).workspace()));
            root.put("zip", Prefs.get(ctx).serverZip());
            JSONArray files = new JSONArray();
            for (File f : Workspaces.listImages(dir)) {
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("size", f.length());
                o.put("mtime", f.lastModified());
                JSONArray sc = new JSONArray();
                for (File s : Workspaces.sidecars(f)) sc.put(s.getName());
                o.put("sidecars", sc);
                files.put(o);
            }
            root.put("files", files);
        } catch (JSONException e) {
            Log.w(TAG, "json", e);
        }
        sendJson(out, root.toString(), setCookie, head);
    }

    private void serveFile(Request req, OutputStream out, String setCookie, boolean head, String ws, String name)
            throws IOException, HttpError {
        File f = workspaceFile(ws, name);
        long len = f.length();
        long mtime = f.lastModified();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Last-Modified", httpDate(mtime));
        h.put("Accept-Ranges", "bytes");
        h.put("Cache-Control", "private, max-age=300");
        if (setCookie != null) h.put("Set-Cookie", setCookie);
        if ("1".equals(req.param("dl"))) h.put("Content-Disposition", contentDisposition(name));

        if (notModified(req, mtime)) {
            sendHeaders(out, 304, null, -1, h);
            out.flush();
            return;
        }

        long start = 0, end = len - 1;
        int status = 200;
        String range = req.header("range");
        String ifRange = req.header("if-range");
        if (range != null && (ifRange == null || ifRange.equals(httpDate(mtime)))) {
            long[] r = parseRange(range, len);
            if (r == null) {
                // multi-range or unparseable -> ignore, send whole file
            } else if (r.length == 0) {
                h.put("Content-Range", "bytes */" + len);
                sendBytes(out, 416, "text/plain; charset=utf-8", "Range not satisfiable".getBytes(UTF8), h, head);
                return;
            } else {
                start = r[0];
                end = r[1];
                status = 206;
                h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
            }
        }
        long count = len == 0 ? 0 : end - start + 1;
        sendHeaders(out, status, mimeType(name), count, h);
        if (!head && count > 0) {
            try (FileInputStream in = new FileInputStream(f)) {
                long skipped = 0;
                while (skipped < start) {
                    long s = in.skip(start - skipped);
                    if (s <= 0) throw new IOException("skip failed");
                    skipped += s;
                }
                copy(in, out, count);
            }
        }
        out.flush();
    }

    /**
     * Parses a single "bytes=" range. Returns {start, end} inclusive, an empty array if the
     * range is unsatisfiable, or null if it should be ignored (multi-range / malformed).
     */
    private static long[] parseRange(String header, long len) {
        String h = header.trim();
        if (!h.toLowerCase(Locale.ROOT).startsWith("bytes=")) return null;
        String spec = h.substring(6).trim();
        if (spec.indexOf(',') >= 0) return null;
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
        try {
            long start, end;
            if (a.isEmpty()) {
                if (b.isEmpty()) return null;
                long suffix = Long.parseLong(b);
                if (suffix <= 0) return new long[0];
                start = Math.max(0, len - suffix);
                end = len - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? len - 1 : Long.parseLong(b);
                if (end >= len) end = len - 1;
            }
            if (start < 0 || start >= len || end < start) return new long[0];
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean notModified(Request req, long mtime) {
        String ims = req.header("if-modified-since");
        if (ims == null || req.header("range") != null) return false;
        try {
            Date d = httpDateFormat().parse(ims);
            return d != null && mtime / 1000 <= d.getTime() / 1000;
        } catch (ParseException e) {
            return false;
        }
    }

    private void serveThumb(Request req, OutputStream out, String setCookie, boolean head, String ws, String name)
            throws IOException, HttpError {
        File f = workspaceFile(ws, name);
        if (!Workspaces.isImage(name)) throw new HttpError(404, "Not an image");
        long mtime = f.lastModified();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cache-Control", "private, max-age=604800");
        h.put("Last-Modified", httpDate(mtime));
        if (setCookie != null) h.put("Set-Cookie", setCookie);
        if (notModified(req, mtime)) {
            sendHeaders(out, 304, null, -1, h);
            out.flush();
            return;
        }
        File thumb = thumbFor(f);
        if (thumb == null) throw new HttpError(500, "Cannot decode image");
        sendHeaders(out, 200, "image/jpeg", thumb.length(), h);
        if (!head) {
            try (FileInputStream in = new FileInputStream(thumb)) {
                copy(in, out, thumb.length());
            }
        }
        out.flush();
    }

    /** Returns the cached thumbnail for an image, generating it if necessary. Null on failure. */
    private File thumbFor(File src) {
        String key = sha1(src.getAbsolutePath() + "|" + src.lastModified() + "|" + src.length());
        File cached = new File(thumbDir, key + ".jpg");
        if (cached.isFile() && cached.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            cached.setLastModified(System.currentTimeMillis()); // LRU-ish
            return cached;
        }
        boolean acquired = false;
        Bitmap bmp = null;
        File tmp = null;
        try {
            decodeSlots.acquire();
            acquired = true;
            if (cached.isFile() && cached.length() > 0) return cached; // made while we waited
            //noinspection ResultOfMethodCallIgnored
            thumbDir.mkdirs();

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int longest = Math.max(o.outWidth, o.outHeight);
            int sample = 1;
            while (longest / (sample * 2) >= THUMB_SIZE) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
            if (bmp == null) return null;

            int w = bmp.getWidth(), hgt = bmp.getHeight();
            int lg = Math.max(w, hgt);
            if (lg > THUMB_SIZE) {
                float scale = THUMB_SIZE / (float) lg;
                int nw = Math.max(1, Math.round(w * scale)), nh = Math.max(1, Math.round(hgt * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                if (scaled != bmp) { bmp.recycle(); bmp = scaled; }
            }
            if (bmp.hasAlpha()) { // JPEG has no alpha: flatten on white
                Bitmap flat = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.RGB_565);
                Canvas c = new Canvas(flat);
                c.drawColor(Color.WHITE);
                c.drawBitmap(bmp, 0, 0, null);
                bmp.recycle();
                bmp = flat;
            }
            tmp = new File(thumbDir, key + "." + System.nanoTime() + ".tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, fo)) return null;
            }
            if (!tmp.renameTo(cached)) {
                if (cached.isFile()) return cached;
                return null;
            }
            tmp = null;
            return cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (OutOfMemoryError | Exception e) {
            Log.w(TAG, "thumbnail failed for " + src.getName() + ": " + e);
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
            if (tmp != null) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            if (acquired) decodeSlots.release();
        }
    }

    private void trimThumbCache() {
        try {
            File[] files = thumbDir.listFiles();
            if (files == null) return;
            long total = 0;
            for (File f : files) {
                if (f.getName().endsWith(".tmp")) { //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    continue;
                }
                total += f.length();
            }
            if (total <= THUMB_CACHE_MAX_BYTES) return;
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : files) {
                if (total <= THUMB_CACHE_MAX_BYTES * 3 / 4) break;
                long l = f.length();
                if (f.delete()) total -= l;
            }
        } catch (Throwable t) {
            Log.w(TAG, "thumb cache trim failed", t);
        }
    }

    private void serveZip(Request req, OutputStream out, String setCookie, boolean head) throws IOException, HttpError {
        String ws = wsParam(req);
        File dir = workspaceDir(ws);
        if (!dir.isDirectory()) throw new HttpError(404, "No such workspace");
        boolean side = "1".equals(req.param("side")) || "on".equals(req.param("side")) || "true".equals(req.param("side"));

        List<File> images = new ArrayList<>();
        List<String> names = req.params("f");
        if (names.isEmpty()) {
            images.addAll(Workspaces.listImages(dir)); // no selection given -> whole workspace
        } else {
            Set<String> seen = new LinkedHashSet<>(names);
            for (String n : seen) {
                if (!isSafeName(n) || !Workspaces.isImage(n)) continue;
                File f = new File(dir, n);
                if (f.isFile() && isInside(f, dir)) images.add(f);
            }
        }
        if (images.isEmpty()) throw new HttpError(404, "No files selected");

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Disposition", contentDisposition(ws + "_" + stamp + ".zip"));
        h.put("Cache-Control", "no-store");
        if (setCookie != null) h.put("Set-Cookie", setCookie);
        // No Content-Length: the body is delimited by closing the connection.
        sendHeaders(out, 200, "application/zip", -1, h);
        if (head) { out.flush(); return; }

        ZipOutputStream zip = new ZipOutputStream(new NonClosingOutputStream(out));
        zip.setMethod(ZipOutputStream.DEFLATED);
        byte[] buf = new byte[64 * 1024];
        Set<String> added = new HashSet<>();
        int n = 0;
        for (File img : images) {
            if (!running) break;
            addEntry(zip, img, Deflater.NO_COMPRESSION, buf, added);
            n++;
            if (side) for (File s : Workspaces.sidecars(img)) addEntry(zip, s, Deflater.BEST_SPEED, buf, added);
        }
        zip.finish();
        zip.flush();
        out.flush();
        Log.i(TAG, "zip sent: " + n + " images from " + ws);
    }

    private static void addEntry(ZipOutputStream zip, File f, int level, byte[] buf, Set<String> added) throws IOException {
        if (!added.add(f.getName())) return;
        FileInputStream in;
        try {
            in = new FileInputStream(f);
        } catch (IOException e) {
            Log.w(TAG, "skip unreadable " + f.getName());
            return;
        }
        try {
            zip.setLevel(level);
            ZipEntry e = new ZipEntry(f.getName());
            e.setTime(f.lastModified());
            zip.putNextEntry(e);
            int r;
            while ((r = in.read(buf)) > 0) zip.write(buf, 0, r);
            zip.closeEntry();
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    // ------------------------------------------------------------------ response helpers

    private static final class NonClosingOutputStream extends OutputStream {
        private final OutputStream o;
        NonClosingOutputStream(OutputStream o) { this.o = o; }
        @Override public void write(int b) throws IOException { o.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { o.write(b, off, len); }
        @Override public void flush() throws IOException { o.flush(); }
        @Override public void close() throws IOException { o.flush(); }
    }

    private static void sendHeaders(OutputStream out, int status, String contentType, long length,
                                    Map<String, String> extra) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        sb.append("Date: ").append(httpDate(System.currentTimeMillis())).append("\r\n");
        sb.append("Server: TesterScreenShot\r\n");
        sb.append("Connection: close\r\n");
        sb.append("X-Content-Type-Options: nosniff\r\n");
        sb.append("Referrer-Policy: no-referrer\r\n");
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n");
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n");
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                // Header values must be ASCII without CR/LF.
                String v = e.getValue().replace('\r', ' ').replace('\n', ' ');
                sb.append(e.getKey()).append(": ").append(v).append("\r\n");
            }
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(LATIN1));
    }

    private static void sendBytes(OutputStream out, int status, String contentType, byte[] body,
                                  Map<String, String> extra, boolean head) throws IOException {
        sendHeaders(out, status, contentType, body.length, extra);
        if (!head) out.write(body);
        out.flush();
    }

    private static void sendJson(OutputStream out, String json, String setCookie, boolean head) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cache-Control", "no-store");
        if (setCookie != null) h.put("Set-Cookie", setCookie);
        sendBytes(out, 200, "application/json; charset=utf-8", json.getBytes(UTF8), h, head);
    }

    private static void sendError(OutputStream out, int status, String msg, boolean head) {
        try {
            String m = msg == null ? reason(status) : msg;
            String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>" + status + "</title></head><body style=\"font-family:sans-serif;padding:24px\">"
                    + "<h2>" + status + " " + reason(status) + "</h2><p>" + escapeHtml(m) + "</p></body></html>";
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Cache-Control", "no-store");
            sendBytes(out, status, "text/html; charset=utf-8", html.getBytes(UTF8), h, head);
        } catch (IOException ignored) {
            // client gone
        }
    }

    private static void copy(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = count;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) break;
            out.write(buf, 0, r);
            left -= r;
        }
    }

    private static String reason(int s) {
        switch (s) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 414: return "URI Too Long";
            case 416: return "Range Not Satisfiable";
            case 431: return "Request Header Fields Too Large";
            default: return s >= 500 ? "Internal Server Error" : "Error";
        }
    }

    private static String mimeType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    /** attachment; filename="ascii fallback"; filename*=UTF-8''pct-encoded */
    private static String contentDisposition(String fileName) {
        StringBuilder ascii = new StringBuilder();
        boolean nonAscii = false;
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (c < 0x20 || c >= 0x7f) { ascii.append('_'); nonAscii = true; }
            else if (c == '"' || c == '\\' || c == ';' || c == '%') ascii.append('_');
            else ascii.append(c);
        }
        StringBuilder sb = new StringBuilder("attachment; filename=\"").append(ascii).append('"');
        if (nonAscii || !ascii.toString().equals(fileName)) {
            sb.append("; filename*=UTF-8''").append(rfc5987(fileName));
        }
        return sb.toString();
    }

    private static String rfc5987(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(UTF8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }

    private static SimpleDateFormat httpDateFormat() {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f;
    }

    private static String httpDate(long millis) {
        return httpDateFormat().format(new Date(millis));
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
