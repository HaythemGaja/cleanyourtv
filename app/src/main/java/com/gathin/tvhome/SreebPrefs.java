package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads (and, for remote-controlled keys, writes) Sreeb's
 * SharedPreferences file. This works because the fork installs
 * as a Sreeb update: same applicationId (com.mizan.launcher)
 * means the same data directory, so opening the "mizan_launcher"
 * prefs file here opens Sreeb's own file — no import, no copy,
 * no re-entry of anything.
 *
 * Keys (Sreeb's data/Prefs.kt, verified against the source):
 * - "current_profile" (int, default 1); per-profile keys are
 *   "p<profile>.<name>".
 * - "p<N>.locked": string set of locked app packages.
 * - "p<N>.layout": layout codec string; folder2 lines carry
 *   the folder lock flags (the fork reads them for the remote
 *   status path; the base has no folders to open, so folder
 *   gating itself stays dormant — see TrackerUi/FORK.md).
 * - "arrow_pin": global arrow-code encoding (UDLR, 4–8).
 * - "launcher_tmdb_key": global TMDB key (used only for the
 *   tracker's TMDB refresh; never logged or returned).
 * - "phone_remote_enabled" (default false), "phone_remote_code",
 *   "phone_remote_applock" (default true),
 *   "phone_remote_folderlock" (default true),
 *   "phone_remote_refresh", "phone_remote_failures",
 *   "phone_remote_lockout".
 * - "p<N>.unlock_grace_min" (default 0) + "last_unlock_at".
 *
 * All reads are defensive: a wrong-typed value falls back to
 * the default instead of throwing. The fork writes ONLY the
 * remote flags/code, the arrow code (remote set / set-pattern
 * flow), the unlock stamp, and — since v3.22, deliberately —
 * the locked-apps set (tile-menu Lock/Unlock). It NEVER writes
 * layout or the TMDB key.
 */
final class SreebPrefs {
    private static final String FILE = "mizan_launcher";

    private final SharedPreferences sp;

    SreebPrefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    int currentProfile() {
        int p = getInt("current_profile", 1);
        if (p < 1) return 1;
        if (p > 4) return 4;
        return p;
    }

    private String k(String name) {
        return "p" + currentProfile() + "." + name;
    }

    Set<String> lockedApps() {
        try {
            Set<String> v = sp.getStringSet(k("locked"), null);
            return v == null ? Collections.emptySet() : new HashSet<>(v);
        } catch (ClassCastException e) {
            return Collections.emptySet();
        }
    }

    /** v3.22: tile-menu Lock/Unlock writes ONLY this key. Same file Sreeb reads, so the locks stay
     *  consistent if the user ever returns to Sreeb. Nothing else in this file is touched here. */
    void setLockedApps(Set<String> pkgs) {
        sp.edit().putStringSet(k("locked"), pkgs == null ? new HashSet<>() : new HashSet<>(pkgs)).apply();
    }

    String layoutRaw() {
        return getStr(k("layout"), "");
    }

    String arrowPin() {
        return getStr("arrow_pin", "");
    }

    void setArrowPin(String v) {
        sp.edit().putString("arrow_pin", v == null ? "" : v).apply();
    }

    String tmdbKey() {
        return getStr("launcher_tmdb_key", "");
    }

    boolean remoteEnabled() {
        return getBool("phone_remote_enabled", false);
    }

    void setRemoteEnabled(boolean v) {
        sp.edit().putBoolean("phone_remote_enabled", v).apply();
    }

    String pairingCode() {
        return getStr("phone_remote_code", "");
    }

    void setPairingCode(String v) {
        sp.edit().putString("phone_remote_code", v == null ? "" : v).apply();
    }

    boolean remoteAppLockEnabled() {
        return getBool("phone_remote_applock", true);
    }

    void setRemoteAppLockEnabled(boolean v) {
        sp.edit().putBoolean("phone_remote_applock", v).apply();
    }

    boolean remoteFolderLockEnabled() {
        return getBool("phone_remote_folderlock", true);
    }

    void setRemoteFolderLockEnabled(boolean v) {
        sp.edit().putBoolean("phone_remote_folderlock", v).apply();
    }

    boolean remoteRefreshPending() {
        return getBool("phone_remote_refresh", false);
    }

    void setRemoteRefreshPending(boolean v) {
        sp.edit().putBoolean("phone_remote_refresh", v).apply();
    }

    int remoteFailureCount() {
        return getInt("phone_remote_failures", 0);
    }

    void setRemoteFailureCount(int v) {
        sp.edit().putInt("phone_remote_failures", v).apply();
    }

    long remoteLockoutUntilMs() {
        return getLong("phone_remote_lockout", 0L);
    }

    void setRemoteLockoutUntilMs(long v) {
        sp.edit().putLong("phone_remote_lockout", v).apply();
    }

    int unlockGraceMinutes() {
        int v = getInt(k("unlock_grace_min"), 0);
        return v < 0 ? 0 : v;
    }

    long lastUnlockAt() {
        return getLong("last_unlock_at", 0L);
    }

    void stampUnlock() {
        sp.edit().putLong("last_unlock_at", System.currentTimeMillis()).apply();
    }

    private String getStr(String key, String def) {
        try {
            String v = sp.getString(key, null);
            return v == null ? def : v;
        } catch (ClassCastException e) {
            return def;
        }
    }

    private boolean getBool(String key, boolean def) {
        try {
            return sp.getBoolean(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    private int getInt(String key, int def) {
        try {
            return sp.getInt(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    private long getLong(String key, long def) {
        try {
            return sp.getLong(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }
}
