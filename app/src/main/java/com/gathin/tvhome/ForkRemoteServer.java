package com.gathin.tvhome;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;

/**
 * The phone-remote listener, ported from Sreeb's
 * data/RemoteServer to the fork's prefs + protocol.
 *
 * A tiny HTTP server on a daemon thread — NOT a Service and
 * NOT a foreground service: it lives only while this process
 * lives, and dies with it. MainActivity re-arms it on
 * create when still enabled in prefs.
 *
 * LAN ONLY: binds the device's site-local IPv4 address,
 * never 0.0.0.0. No port is ever forwarded by this app.
 * All command logic lives in ForkRemoteProtocol; this file
 * only moves bytes: one POST /v1/command endpoint, 8 KB
 * body cap, 10 s socket timeout, every connection failure
 * contained so a hostile phone can never kill Home.
 */
final class ForkRemoteServer {
    private ForkRemoteServer() {}

    /** Live-home nudge after a successful mutating command (MainActivity sets/clears it). */
    static volatile Runnable onRefresh;

    private static volatile ServerSocket socket;
    private static volatile Thread thread;
    private static volatile String boundAddress;
    private static volatile String lastError;

    static boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    static String boundAddress() {
        return boundAddress;
    }

    static String lastError() {
        return lastError;
    }

    /** First up, non-loopback, site-local IPv4 address, or null. */
    static String lanAddress() {
        try {
            java.util.Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                java.util.Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** A fresh 6-digit pairing code (100000–999999). */
    static String generateCode() {
        SecureRandom rnd = new SecureRandom();
        return Integer.toString(100000 + rnd.nextInt(900000));
    }

    /** Idempotent start. Returns false (with lastError set) when there is no LAN to bind. */
    static synchronized boolean start(Context ctx) {
        if (isRunning()) return true;
        lastError = null;
        String lan = lanAddress();
        if (lan == null) {
            lastError = "no_lan";
            return false;
        }
        try {
            final Context appCtx = ctx.getApplicationContext();
            ServerSocket srv = new ServerSocket(ForkRemoteProtocol.PORT, 50, InetAddress.getByName(lan));
            socket = srv;
            boundAddress = lan + ":" + ForkRemoteProtocol.PORT;
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        final Socket conn = srv.accept();
                        Thread handler = new Thread(() -> handleConnection(conn, appCtx), "fork-remote-conn");
                        handler.setDaemon(true);
                        handler.start();
                    } catch (Exception e) {
                        break;
                    }
                }
            }, "fork-remote");
            t.setDaemon(true);
            t.start();
            thread = t;
            return true;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
            boundAddress = null;
            return false;
        }
    }

    static synchronized void stop() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        if (thread != null) thread.interrupt();
        thread = null;
        boundAddress = null;
    }

    private static final class PrefsStore implements ForkRemoteProtocol.Store {
        private final SreebPrefs prefs;
        private final ForkState state;
        private final TrackerStore store;

        PrefsStore(Context ctx) {
            prefs = new SreebPrefs(ctx);
            state = new ForkState(ctx);
            store = new TrackerStore(ctx.getFilesDir());
        }

        @Override public String getPairingCode() { return prefs.pairingCode(); }
        @Override public void setPairingCode(String v) { prefs.setPairingCode(v); }
        @Override public String getStyle() { return "clean"; }
        @Override public void setStyle(String v) { /* Fork has one home; "clean" is a no-op. */ }
        @Override public String getThemeId() { return state.remoteTheme(); }
        @Override public void setThemeId(String v) { state.setRemoteTheme(v); }
        @Override public java.util.List<ForkRemoteProtocol.ThemeOpt> getThemeOptions() {
            return ForkRemoteProtocol.THEME_OPTIONS;
        }
        @Override public boolean isAppLockEnabled() { return prefs.remoteAppLockEnabled(); }
        @Override public void setAppLockEnabled(boolean v) { prefs.setRemoteAppLockEnabled(v); }
        @Override public boolean isFolderLockEnabled() { return prefs.remoteFolderLockEnabled(); }
        @Override public void setFolderLockEnabled(boolean v) { prefs.setRemoteFolderLockEnabled(v); }
        @Override public String getArrowPin() { return prefs.arrowPin(); }
        @Override public void setArrowPin(String v) { prefs.setArrowPin(v); }
        @Override public int getLibraryTitles() {
            try {
                return store.readTitles().size();
            } catch (Exception e) {
                return 0;
            }
        }
        @Override public boolean isRefreshPending() { return prefs.remoteRefreshPending(); }
        @Override public void setRefreshPending(boolean v) { prefs.setRemoteRefreshPending(v); }
        @Override public int getFailureCount() { return prefs.remoteFailureCount(); }
        @Override public void setFailureCount(int v) { prefs.setRemoteFailureCount(v); }
        @Override public long getLockoutUntilMs() { return prefs.remoteLockoutUntilMs(); }
        @Override public void setLockoutUntilMs(long v) { prefs.setRemoteLockoutUntilMs(v); }
    }

    private static void handleConnection(Socket conn, Context appCtx) {
        try {
            conn.setSoTimeout(10_000);
            BufferedReader input = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String requestLine = input.readLine();
            if (requestLine == null) return;
            int contentLength = 0;
            while (true) {
                String line = input.readLine();
                if (line == null || line.isEmpty()) break;
                String[] parts = line.split(":", 2);
                if (parts.length == 2 && parts[0].trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException e) {
                        contentLength = 0;
                    }
                    if (contentLength < 0) contentLength = 0;
                    if (contentLength > 8192) contentLength = 8192;
                }
            }
            char[] bodyChars = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = input.read(bodyChars, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            String body = new String(bodyChars, 0, read);
            int status;
            String payload;
            if (requestLine.startsWith("POST /v1/command")) {
                String json = ForkRemoteProtocol.handle(body, System.currentTimeMillis(),
                        new PrefsStore(appCtx));
                if (json.contains("\"ok\":true")) {
                    status = 200;
                } else if (json.contains("\"locked_out\"")) {
                    status = 429;
                } else if (json.contains("\"bad_code\"") || json.contains("\"missing_code\"")) {
                    status = 401;
                } else {
                    status = 400;
                }
                if (status == 200) {
                    String cmd = null;
                    try {
                        java.util.Map<String, Object> parsed =
                                MiniJson.asObj(MiniJson.parse(body));
                        if (parsed != null) cmd = MiniJson.asStr(parsed.get("command"));
                    } catch (IllegalArgumentException ignored) {
                    }
                    if (cmd != null && !"status".equals(cmd)) {
                        try {
                            Runnable nudge = onRefresh;
                            if (nudge != null) nudge.run();
                        } catch (Exception ignored) {
                        }
                    }
                }
                payload = json;
            } else {
                status = 404;
                payload = "{\"ok\":false,\"error\":\"not_found\"}";
            }
            byte[] bytes = payload.getBytes("UTF-8");
            String head = "HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
            conn.getOutputStream().write(head.getBytes("UTF-8"));
            conn.getOutputStream().write(bytes);
            conn.getOutputStream().flush();
        } catch (Exception e) {
            // A bad phone must never take down Home.
        } finally {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
    }
}
