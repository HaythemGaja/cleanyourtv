package com.gathin.leantv.remote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone end of Lean TV: a D-pad, Back, Home, volume, power and one line of text, sent to the TV as key
 * events over Adb.
 *
 * <p>Nothing is paired by hand: the first connection is answered by the TV's own "Allow debugging?" prompt,
 * naming this phone, and the key is remembered after that.
 *
 * <p>Every network call runs on one background thread and the screen only shows the result, so a slow or
 * missing TV cannot freeze the buttons.
 */
public final class MainActivity extends Activity {
    private static final String PREFS = "leantv_remote";
    private static final String HOST = "host";
    private static final String KEY_PRIVATE = "key_private";
    private static final String KEY_PUBLIC = "key_public";
    private static final int ADB_PORT = 5555;

    private static final int KEY_BACK = 4, KEY_HOME = 3, KEY_UP = 19, KEY_DOWN = 20, KEY_LEFT = 21,
            KEY_RIGHT = 22, KEY_OK = 23, KEY_VOLUME_UP = 24, KEY_VOLUME_DOWN = 25, KEY_POWER = 26;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private AdbClient client;
    private AdbKeys keys;
    private EditText hostField;
    private EditText textField;
    private TextView status;

    private interface Action {
        void run(AdbClient client) throws IOException;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        hostField = findViewById(R.id.host);
        textField = findViewById(R.id.text);
        status = findViewById(R.id.status);
        hostField.setText(prefs().getString(HOST, ""));

        findViewById(R.id.connect).setOnClickListener(v -> connectOrClose());
        button(R.id.up, KEY_UP);
        button(R.id.down, KEY_DOWN);
        button(R.id.left, KEY_LEFT);
        button(R.id.right, KEY_RIGHT);
        button(R.id.ok, KEY_OK);
        button(R.id.back, KEY_BACK);
        button(R.id.home, KEY_HOME);
        button(R.id.power, KEY_POWER);
        button(R.id.volume_up, KEY_VOLUME_UP);
        button(R.id.volume_down, KEY_VOLUME_DOWN);
        findViewById(R.id.send).setOnClickListener(v -> sendText());
    }

    private void button(int id, int keyCode) {
        findViewById(id).setOnClickListener(v -> on(c -> c.key(keyCode)));
    }

    private void connectOrClose() {
        AdbClient open = client;
        if (open != null) {
            client = null;
            io.execute(open::close);
            show(getString(R.string.not_connected));
            return;
        }
        String host = hostField.getText().toString().trim();
        if (host.isEmpty()) {
            show(getString(R.string.needs_address));
            return;
        }
        prefs().edit().putString(HOST, host).apply();
        show(getString(R.string.connecting, host));
        io.execute(() -> {
            try {
                client = AdbClient.connect(host, ADB_PORT, keys(), 8000);
                show(getString(R.string.connected, host));
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
        });
    }

    private void sendText() {
        String value = textField.getText().toString();
        if (value.isEmpty()) return;
        on(c -> c.text(value));
    }

    private void on(Action action) {
        io.execute(() -> {
            try {
                action.run(client());
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
        });
    }

    private AdbClient client() throws IOException {
        AdbClient open = client;
        if (open == null) throw new IOException(getString(R.string.not_connected));
        return open;
    }

    /** Generated once, then kept in the app's own preferences so the TV stays paired. */
    private AdbKeys keys() {
        if (keys != null) return keys;
        SharedPreferences prefs = prefs();
        String storedPrivate = prefs.getString(KEY_PRIVATE, null);
        String storedPublic = prefs.getString(KEY_PUBLIC, null);
        try {
            if (storedPrivate == null || storedPublic == null) {
                keys = AdbKeys.generate();
                prefs.edit().putString(KEY_PRIVATE, keys.privateBase64())
                        .putString(KEY_PUBLIC, keys.publicBase64()).apply();
            } else {
                keys = AdbKeys.fromBase64(storedPrivate, storedPublic);
            }
            return keys;
        } catch (Exception e) {
            throw new IllegalStateException("This phone could not make an Adb key", e);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void fail(Throwable error) {
        String message = error.getMessage();
        show(getString(R.string.failed, message == null ? error.getClass().getSimpleName() : message));
    }

    private void show(String text) {
        ui.post(() -> status.setText(text));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AdbClient open = client;
        client = null;
        if (open != null) io.execute(open::close);
        io.shutdown();
    }
}
