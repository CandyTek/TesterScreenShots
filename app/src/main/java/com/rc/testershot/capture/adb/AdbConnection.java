package com.rc.testershot.capture.adb;

import android.os.Build;
import android.util.Log;

import com.rc.testershot.capture.Shell;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal ADB wire-protocol client talking to the device's own adbd over TCP (adb tcpip mode).
 * One stream at a time (calls are serialized), which is all the app needs.
 */
public final class AdbConnection implements Shell, Closeable {
    private static final String TAG = "AdbConnection";

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int A_VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 1024 * 1024;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    /** Listener told when the device is showing the "Allow USB debugging?" dialog. */
    public interface AuthListener {
        void onWaitingForUserApproval();
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int localIdSeq = 1;
    private volatile boolean closed;

    private AdbConnection(Socket s) throws IOException {
        this.socket = s;
        this.in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 256 * 1024));
        this.out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
    }

    /** Can anything be reached on 127.0.0.1:port at all? */
    public static boolean isPortOpen(int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Connects and authenticates. If the key is not yet trusted the device shows its approval dialog
     * and this blocks up to approvalTimeoutMs.
     */
    public static AdbConnection connect(int port, AdbKey key, long approvalTimeoutMs, AuthListener listener) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        AdbConnection c = new AdbConnection(s);
        try {
            c.handshake(key, approvalTimeoutMs, listener);
        } catch (IOException e) {
            c.close();
            throw e;
        }
        return c;
    }

    private void handshake(AdbKey key, long approvalTimeoutMs, AuthListener listener) throws IOException {
        socket.setSoTimeout(5000);
        send(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".getBytes(StandardCharsets.US_ASCII));
        boolean sentSignature = false;
        while (true) {
            Msg m = read();
            if (m.cmd == A_CNXN) {
                socket.setSoTimeout(15000);
                Log.i(TAG, "connected: " + new String(m.data, StandardCharsets.UTF_8).trim());
                return;
            }
            if (m.cmd != A_AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb message 0x" + Integer.toHexString(m.cmd));
            if (!sentSignature) {
                send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
                sentSignature = true;
            } else {
                // Key unknown to the device: offer it; the device asks the user.
                String host = "testershot@" + Build.MODEL.replace(' ', '_');
                send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.adbPublicKeyPayload(host));
                if (listener != null) listener.onWaitingForUserApproval();
                socket.setSoTimeout((int) Math.max(5000, approvalTimeoutMs));
                try {
                    Msg r = read();
                    if (r.cmd == A_CNXN) {
                        socket.setSoTimeout(15000);
                        return;
                    }
                    throw new IOException("adb authorization rejected");
                } catch (SocketTimeoutException e) {
                    throw new IOException("adb authorization not confirmed on the device", e);
                }
            }
        }
    }

    @Override
    public String name() { return "adb"; }

    public boolean isClosed() { return closed || socket.isClosed(); }

    /** Runs a command via the binary-safe "exec:" service and returns its stdout. */
    @Override
    public synchronized byte[] exec(String command, long timeoutMs) throws IOException {
        return runService("exec:" + command, timeoutMs);
    }

    private byte[] runService(String service, long timeoutMs) throws IOException {
        if (isClosed()) throw new IOException("adb connection closed");
        int localId = localIdSeq++;
        socket.setSoTimeout((int) Math.max(2000, timeoutMs));
        try {
            send(A_OPEN, localId, 0, (service + "\u0000").getBytes(StandardCharsets.UTF_8));
            int remoteId = 0;
            ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 20);
            while (true) {
                Msg m = read();
                if (m.arg1 != localId) continue; // stale message from an earlier stream
                if (m.cmd == A_OKAY) {
                    remoteId = m.arg0;
                } else if (m.cmd == A_WRTE) {
                    remoteId = m.arg0;
                    buf.write(m.data, 0, m.data.length);
                    send(A_OKAY, localId, remoteId, null);
                } else if (m.cmd == A_CLSE) {
                    if (remoteId == 0) throw new IOException("adb refused service: " + service);
                    send(A_CLSE, localId, remoteId, null);
                    return buf.toByteArray();
                }
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    // ---- framing

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        if (data != null) for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        h.getInt(); // checksum (not verified; TCP is reliable)
        int magic = h.getInt();
        if (magic != ~m.cmd) throw new IOException("bad adb header");
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("bad adb payload length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    @Override
    public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }

    @SuppressWarnings("unused")
    static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] t = new byte[8192];
        int n;
        while ((n = is.read(t)) > 0) b.write(t, 0, n);
        return b.toByteArray();
    }
}
