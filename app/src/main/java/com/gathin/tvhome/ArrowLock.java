package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;

/**
 * Sreeb's arrow-sequence lock, ported to Java. Pure code, no
 * Android imports — the headless harness verifies it.
 *
 * The secret is a D-pad direction sequence (UP/DOWN/LEFT/RIGHT),
 * 4–8 steps, stored as letters ("UDLRU") in Sreeb's prefs under
 * "arrow_pin". This is a casual lock (keeps kids/guests out),
 * NOT cryptography — the stored value is readable by anyone
 * with device access, exactly as in Sreeb.
 */
final class ArrowLock {
    private ArrowLock() {}

    static final int MIN_LENGTH = 4;
    static final int MAX_LENGTH = 8;

    enum Dir {
        UP('U', "\u2191"), DOWN('D', "\u2193"), LEFT('L', "\u2190"), RIGHT('R', "\u2192");

        final char code;
        final String glyph;

        Dir(char code, String glyph) { this.code = code; this.glyph = glyph; }

        static Dir fromCode(char c) {
            for (Dir d : values()) {
                if (d.code == c) return d;
            }
            return null;
        }
    }

    static String encode(List<Dir> seq) {
        StringBuilder b = new StringBuilder();
        for (Dir d : seq) b.append(d.code);
        return b.toString();
    }

    /** True only for a complete, storable arrow encoding. */
    static boolean isValidEncoded(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        if (raw.length() < MIN_LENGTH || raw.length() > MAX_LENGTH) return false;
        for (int i = 0; i < raw.length(); i++) {
            if (Dir.fromCode(raw.charAt(i)) == null) return false;
        }
        return true;
    }

    /** True for a v1.1-style numeric PIN (all digits, length 4) — discarded, never reinterpreted. */
    static boolean isLegacyNumeric(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() != 4) return false;
        for (int i = 0; i < raw.length(); i++) {
            if (!Character.isDigit(raw.charAt(i))) return false;
        }
        return true;
    }

    /** Decode a stored value; anything malformed decodes to empty ("no lock set", fail-open). */
    static List<Dir> decode(String raw) {
        List<Dir> out = new ArrayList<>();
        if (!isValidEncoded(raw)) return out;
        for (int i = 0; i < raw.length(); i++) out.add(Dir.fromCode(raw.charAt(i)));
        return out;
    }

    static boolean matches(List<Dir> entered, String storedEncoded) {
        List<Dir> stored = decode(storedEncoded);
        return !stored.isEmpty() && stored.equals(entered);
    }

    /** Gate decisions (Sreeb v3.19): remote toggles suspend
     *  enforcement; membership is untouched, so re-enabling
     *  restores exactly the old locks. */
    static boolean appRequiresPrompt(boolean locked, boolean appLockEnabled) {
        return locked && appLockEnabled;
    }

    static boolean folderRequiresPrompt(boolean locked, boolean folderLockEnabled) {
        return locked && folderLockEnabled;
    }

    enum FolderGate { OPEN, PROMPT_PATTERN, PROMPT_SET_PATTERN }

    static FolderGate folderGate(boolean folderLocked, boolean patternSet) {
        if (!folderLocked) return FolderGate.OPEN;
        return patternSet ? FolderGate.PROMPT_PATTERN : FolderGate.PROMPT_SET_PATTERN;
    }

    /** Unlock-grace rule (Sreeb v1.9): inside the window the gate passes silently. */
    static boolean withinGrace(int graceMinutes, long lastUnlockAt, long nowMs) {
        if (graceMinutes <= 0 || lastUnlockAt <= 0L) return false;
        return nowMs - lastUnlockAt < (long) graceMinutes * 60L * 1000L;
    }

    /** Auto-submit rule (Sreeb v3.3): an unlock dialog submits by
     *  itself exactly when the entered count reaches the stored
     *  pattern length. A stored length outside 4–8 never
     *  auto-submits, so the dialog falls back to OK-only. */
    static boolean shouldAutoSubmit(int entered, int storedLength) {
        return storedLength >= MIN_LENGTH && storedLength <= MAX_LENGTH && entered == storedLength;
    }

    enum EntryEvent { CHANGED, REJECTED_FULL, CANCELLED, TOO_SHORT, SUBMITTED }

    /** The entry machine behind the arrow-lock dialog. */
    static final class Entry {
        private final List<Dir> buffer = new ArrayList<>();
        private String submitted;

        int entered() { return buffer.size(); }

        List<Dir> steps() { return new ArrayList<>(buffer); }

        String submittedSequence() { return submitted; }

        boolean canSubmit() {
            return buffer.size() >= MIN_LENGTH && buffer.size() <= MAX_LENGTH;
        }

        EntryEvent append(Dir dir) {
            if (buffer.size() >= MAX_LENGTH) return EntryEvent.REJECTED_FULL;
            buffer.add(dir);
            return EntryEvent.CHANGED;
        }

        EntryEvent pressBack() {
            if (!buffer.isEmpty()) {
                buffer.remove(buffer.size() - 1);
                return EntryEvent.CHANGED;
            }
            return EntryEvent.CANCELLED;
        }

        EntryEvent cancel() {
            buffer.clear();
            return EntryEvent.CANCELLED;
        }

        EntryEvent submit() {
            if (!canSubmit()) return EntryEvent.TOO_SHORT;
            submitted = encode(buffer);
            buffer.clear();
            return EntryEvent.SUBMITTED;
        }

        void reset() {
            buffer.clear();
            submitted = null;
        }
    }

    enum SetupResult { NEED_CONFIRM, DONE, MISMATCH }

    /** Set/change-lock flow: enter once, then again to confirm.
     *  A mismatch restarts the whole flow. */
    static final class SetupFlow {
        private String firstEntry;

        boolean awaitingConfirm() { return firstEntry != null; }

        SetupResult submit(String sequence) {
            if (firstEntry == null) {
                firstEntry = sequence;
                return SetupResult.NEED_CONFIRM;
            } else if (firstEntry.equals(sequence)) {
                firstEntry = null;
                return SetupResult.DONE;
            } else {
                firstEntry = null;
                return SetupResult.MISMATCH;
            }
        }

        void reset() { firstEntry = null; }
    }
}
