package com.gathin.leantv.remote;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Just enough of the Adb protocol to run one shell command on a TV over Wi-Fi, which is all a remote needs:
 * connect, prove the key, then "shell:input keyevent 19".
 *
 * <p>The phone is the client and the TV is the server, so the launcher needs no listening socket and keeps its
 * no-INTERNET rule: it only ever answers its own debug bridge, and only while network debugging is on.
 */
final class AdbClient implements Closeable {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final AdbKeys keys;
    private int nextLocal = 1;

    private AdbClient(Socket socket, AdbKeys keys) throws IOException {
        this.socket = socket;
        this.keys = keys;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    static AdbClient connect(String host, int port, AdbKeys keys, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            AdbClient client = new AdbClient(socket, keys);
            client.handshake();
            return client;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already failing: the first error is the useful one.
            }
            throw e;
        }
    }

    /** CNXN, then whatever the TV asks for until it accepts this phone's key and answers CNXN itself. */
    private void handshake() throws IOException {
        send(new AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::leantv-remote\0"));
        boolean signed = false;
        boolean sentKey = false;
        long deadline = System.currentTimeMillis() + 120000;   // the TV waits on its Allow prompt
        while (System.currentTimeMillis() < deadline) {
            AdbMessage message = AdbMessage.read(in);
            if (message == null) throw new IOException("The TV closed the connection");
            if (message.command == AdbMessage.CNXN) return;
            if (message.command != AdbMessage.AUTH || message.arg0 != AdbMessage.AUTH_TOKEN) continue;
            if (!signed) {
                signed = true;
                send(new AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_SIGNATURE, 0, sign(message.data)));
            } else if (!sentKey) {
                sentKey = true;
                send(new AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_PUBLIC_KEY, 0, keys.publicKeyPayload()));
            } else {
                throw new IOException("The TV refused this phone's key");
            }
        }
        throw new IOException("Timed out waiting for the TV to allow debugging");
    }

    private byte[] sign(byte[] token) throws IOException {
        try {
            return keys.sign(token);
        } catch (Exception e) {
            throw new IOException("Could not sign the TV's challenge", e);
        }
    }

    /** Runs one shell command and returns what it printed (usually nothing). */
    synchronized String shell(String command) throws IOException {
        int local = nextLocal++;
        send(new AdbMessage(AdbMessage.OPEN, local, 0, "shell:" + command + "\0"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            AdbMessage message = AdbMessage.read(in);
            if (message == null) break;
            if (message.command == AdbMessage.WRTE) {
                output.write(message.data);
                send(new AdbMessage(AdbMessage.OKAY, local, message.arg1, (byte[]) null));
            } else if (message.command == AdbMessage.CLSE) {
                break;
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    void key(int keyCode) throws IOException {
        shell("input keyevent " + keyCode);
    }

    void text(String value) throws IOException {
        String escaped = escape(value);
        if (!escaped.isEmpty()) shell("input text " + escaped);
    }

    /** What "input text" accepts: single-quoted, spaces as %s, nothing else a shell could read as syntax. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder("'");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == ' ') out.append("%s");
            else if (ch == '\'') out.append("\\'");
            else if (Character.isLetterOrDigit(ch) || ",.!?@#$%&()+-_/=:;*".indexOf(ch) >= 0) out.append(ch);
        }
        return out.length() <= 2 ? "" : out.append('\'').toString();
    }

    private void send(AdbMessage message) throws IOException {
        message.write(out);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a dead socket is not an error worth showing.
        }
    }
}
