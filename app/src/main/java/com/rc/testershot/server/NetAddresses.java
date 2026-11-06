package com.rc.testershot.server;

import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** Enumerates the device's LAN-reachable IPv4 addresses, best candidates first. */
public final class NetAddresses {
    private static final String TAG = "LanServer";

    public static final String KIND_WIFI = "wifi";
    public static final String KIND_ETHERNET = "ethernet";
    public static final String KIND_HOTSPOT = "hotspot";
    public static final String KIND_USB = "usb";
    public static final String KIND_OTHER = "other";

    private NetAddresses() {}

    public static final class Entry {
        public final String iface;
        public final String ip;
        public final String kind;

        public Entry(String iface, String ip, String kind) {
            this.iface = iface;
            this.ip = ip;
            this.kind = kind;
        }

        @Override public String toString() { return kind + " " + iface + " " + ip; }
    }

    /** Up, non-loopback, IPv4, non-link-local addresses; cellular interfaces are skipped. */
    public static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return out;
            for (NetworkInterface ni : Collections.list(en)) {
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName();
                if (isCellularOrVirtual(name.toLowerCase(Locale.ROOT))) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                            || a.isMulticastAddress()) continue;
                    String ip = a.getHostAddress();
                    out.add(new Entry(name, ip, kindOf(name, ip)));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot enumerate interfaces", e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                int c = Integer.compare(rank(a.kind), rank(b.kind));
                return c != 0 ? c : a.iface.compareTo(b.iface);
            }
        });
        return out;
    }

    public static String url(String ip, int port, String token) {
        return "http://" + ip + ":" + port + "/?t=" + token;
    }

    private static boolean isCellularOrVirtual(String n) {
        return n.startsWith("rmnet") || n.startsWith("r_rmnet") || n.startsWith("v4-rmnet")
                || n.startsWith("ccmni") || n.startsWith("v4-ccmni") || n.startsWith("pdp")
                || n.startsWith("dummy") || n.startsWith("tun") || n.startsWith("ppp")
                || n.startsWith("ip6tnl") || n.startsWith("sit") || n.startsWith("ifb");
    }

    private static String kindOf(String name, String ip) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap")) return KIND_HOTSPOT;
        if (n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm")) return KIND_USB;
        if (n.startsWith("eth")) return KIND_ETHERNET;
        if (n.startsWith("wlan")) {
            // Classic Android hotspot subnet; otherwise assume a client Wi-Fi connection.
            if (ip.startsWith("192.168.43.")) return KIND_HOTSPOT;
            return KIND_WIFI;
        }
        return KIND_OTHER;
    }

    private static int rank(String kind) {
        switch (kind) {
            case KIND_WIFI: return 0;
            case KIND_ETHERNET: return 1;
            case KIND_HOTSPOT: return 2;
            case KIND_USB: return 3;
            default: return 4;
        }
    }
}
