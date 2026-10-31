package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/** Fork-owned state, kept OUT of Sreeb's prefs file: refresh
 *  throttle stamp and the remote theme look. Nothing here is
 *  shared with Sreeb, so it can never confuse a later Sreeb
 *  install. */
final class ForkState {
    private static final String FILE = "clean_fork_state";

    private final SharedPreferences sp;

    ForkState(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    long lastRefreshMs() {
        try {
            return sp.getLong("last_refresh_ms", 0L);
        } catch (ClassCastException e) {
            return 0L;
        }
    }

    void setLastRefreshMs(long v) {
        sp.edit().putLong("last_refresh_ms", v).apply();
    }

    String remoteTheme() {
        try {
            String v = sp.getString("remote_theme", null);
            return v == null ? "default" : v;
        } catch (ClassCastException e) {
            return "default";
        }
    }

    void setRemoteTheme(String v) {
        sp.edit().putString("remote_theme", v == null ? "default" : v).apply();
    }
}
