package com.gathin.tvhome;

import java.util.List;
import java.util.Map;

/**
 * Phone-remote v1 protocol, ported from Sreeb's
 * core/RemoteProtocol so the already-shipped Sreeb Remote
 * phone app keeps working against the fork with no re-pairing.
 *
 * TRANSPORT (ForkRemoteServer): one endpoint, POST /v1/command,
 * JSON body {"pairing": "<6 digits>", "command": "<name>", ...}.
 *
 * SECURITY MODEL (same as Sreeb's):
 * - LAN only: the server binds a site-local address, never
 *   0.0.0.0, and the app never forwards a port.
 * - The pairing code is the ONLY credential, shown on the TV,
 *   6 digits, regenerable, and revoking regenerates it.
 * - No command reads secrets: status reports counts and ids
 *   only — never the arrow code, never the TMDB key.
 * - After MAX_FAILURES wrong codes the handler refuses
 *   everything (even a correct code) until LOCKOUT_MS passes.
 *   A rejected command is a no-op that returns an error.
 *
 * FORK ADAPTATIONS (reported plainly, not hidden):
 * - style: the fork has ONE home (the CleanYourTV home +
 *   tracker). status always reports "clean"; setStyle accepts
 *   "clean" (a no-op) and refuses "tv"/"watch" with bad_style,
 *   because those homes do not exist in the fork.
 * - themeId/themeOptions: the fork has no Sreeb theme presets.
 *   The fork offers its own three looks — "default" (no
 *   override), "glass" (frosted glass style), "cinematic"
 *   (cinematic home shelf) — applied as Config overrides.
 *   setTheme accepts only those three ids.
 */
final class ForkRemoteProtocol {
    private ForkRemoteProtocol() {}

    static final int PORT = 47819;
    static final int MAX_FAILURES = 5;
    static final long LOCKOUT_MS = 60_000L;

    /** One fork look for the remote theme picker. */
    static final class ThemeOpt {
        final String id;
        final String name;

        ThemeOpt(String id, String name) { this.id = id; this.name = name; }
    }

    static final List<ThemeOpt> THEME_OPTIONS = java.util.Arrays.asList(
            new ThemeOpt("default", "Default"),
            new ThemeOpt("glass", "Glass"),
            new ThemeOpt("cinematic", "Cinematic"));

    /** The mutable state the handler reads/writes. */
    interface Store {
        String getPairingCode();
        void setPairingCode(String v);
        String getStyle();
        void setStyle(String v);
        String getThemeId();
        void setThemeId(String v);
        List<ThemeOpt> getThemeOptions();
        boolean isAppLockEnabled();
        void setAppLockEnabled(boolean v);
        boolean isFolderLockEnabled();
        void setFolderLockEnabled(boolean v);
        String getArrowPin();
        void setArrowPin(String v);
        int getLibraryTitles();
        boolean isRefreshPending();
        void setRefreshPending(boolean v);
        int getFailureCount();
        void setFailureCount(int v);
        long getLockoutUntilMs();
        void setLockoutUntilMs(long v);
    }

    static boolean isValidPairingCode(String code) {
        if (code == null || code.length() != 6) return false;
        for (int i = 0; i < code.length(); i++) {
            if (!Character.isDigit(code.charAt(i))) return false;
        }
        return true;
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < ' ') b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    private static String err(String code) {
        return "{\"ok\":false,\"error\":\"" + esc(code) + "\"}";
    }

    private static String statusJson(Store store) {
        StringBuilder opts = new StringBuilder();
        List<ThemeOpt> options = store.getThemeOptions();
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) opts.append(',');
            opts.append("{\"id\":\"").append(esc(options.get(i).id))
                    .append("\",\"name\":\"").append(esc(options.get(i).name)).append("\"}");
        }
        return "{"
                + "\"ok\":true,"
                + "\"style\":\"" + esc(store.getStyle()) + "\","
                + "\"themeId\":\"" + esc(store.getThemeId()) + "\","
                + "\"themeOptions\":[" + opts + "],"
                + "\"appLock\":" + store.isAppLockEnabled() + ","
                + "\"folderLock\":" + store.isFolderLockEnabled() + ","
                + "\"lockSet\":" + (store.getArrowPin() != null && !store.getArrowPin().isEmpty()) + ","
                + "\"libraryTitles\":" + store.getLibraryTitles()
                + "}";
    }

    /**
     * Handle one request body. Never throws for bad input: a
     * malformed body, a missing field, or a wrong type is an
     * error reply, never a crash and never a state change.
     * nowMs is injected (not read from the clock) so the
     * lockout is harness-testable.
     */
    static String handle(String requestJson, long nowMs, Store store) {
        Map<String, Object> body;
        try {
            body = MiniJson.asObj(MiniJson.parse(requestJson));
            if (body == null) return err("bad_request");
        } catch (IllegalArgumentException e) {
            return err("bad_request");
        }
        // ---- authentication (before any command runs) ----
        if (nowMs < store.getLockoutUntilMs()) return err("locked_out");
        String pairing = MiniJson.asStr(body.get("pairing"));
        if (pairing == null) return err("missing_code");
        if (!isValidPairingCode(store.getPairingCode()) || !pairing.equals(store.getPairingCode())) {
            int failures = store.getFailureCount() + 1;
            store.setFailureCount(failures);
            if (failures >= MAX_FAILURES) {
                store.setLockoutUntilMs(nowMs + LOCKOUT_MS);
                store.setFailureCount(0);
            }
            return err("bad_code");
        }
        store.setFailureCount(0);
        store.setLockoutUntilMs(0L);
        String command = MiniJson.asStr(body.get("command"));
        if (command == null) return err("unknown_command");
        switch (command) {
            case "status":
                return statusJson(store);
            case "setStyle": {
                String style = MiniJson.asStr(body.get("style"));
                if (!"clean".equals(style)) return err("bad_style");
                store.setStyle("clean");
                return statusJson(store);
            }
            case "setTheme": {
                String id = MiniJson.asStr(body.get("themeId"));
                if (id == null) return err("bad_theme");
                boolean known = false;
                for (ThemeOpt o : store.getThemeOptions()) {
                    if (o.id.equals(id)) { known = true; break; }
                }
                if (!known) return err("bad_theme");
                store.setThemeId(id);
                return statusJson(store);
            }
            case "setAppLock": {
                Boolean enabled = MiniJson.asBool(body.get("enabled"));
                if (enabled == null) return err("bad_value");
                store.setAppLockEnabled(enabled);
                return statusJson(store);
            }
            case "setFolderLock": {
                Boolean enabled = MiniJson.asBool(body.get("enabled"));
                if (enabled == null) return err("bad_value");
                store.setFolderLockEnabled(enabled);
                return statusJson(store);
            }
            case "setArrowCode": {
                String code = MiniJson.asStr(body.get("arrowCode"));
                if (!ArrowLock.isValidEncoded(code)) return err("bad_code_value");
                store.setArrowPin(code);
                return statusJson(store);
            }
            case "refresh":
                store.setRefreshPending(true);
                return statusJson(store);
            default:
                return err("unknown_command");
        }
    }
}
