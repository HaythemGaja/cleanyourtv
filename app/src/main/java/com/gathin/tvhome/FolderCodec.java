package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folders for the fork's home grid. Pure code, no Android
 * imports — the headless harness verifies it.
 *
 * Storage format (one folder per line, pipe codec shared
 * with SreebCodecs so names survive pipes/newlines/%):
 *   esc(id) | esc(name) | locked(0/1) | esc(member),esc(member),...
 * Members are app package names, in order. Decoding is
 * fail-open: a malformed line is dropped, the rest survive;
 * an empty or blank store decodes to no folders. A folder
 * id is "f" + a small integer; names default to "Folder N".
 *
 * Folder definitions live in the fork's own prefs file
 * (ForkState), never in Sreeb's prefs — only the app-lock
 * membership (p<N>.locked) and the arrow code are shared
 * with Sreeb, by design (see SreebPrefs).
 */
final class FolderCodec {
    private FolderCodec() {}

    static final class Folder {
        final String id;
        String name;
        final List<String> members = new ArrayList<>();
        boolean locked;

        Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        Folder copy() {
            Folder f = new Folder(id, name);
            f.members.addAll(members);
            f.locked = locked;
            return f;
        }
    }

    static String encode(List<Folder> folders) {
        StringBuilder b = new StringBuilder();
        for (Folder f : folders) {
            if (b.length() > 0) b.append('\n');
            b.append(SreebCodecs.esc(f.id)).append('|')
                    .append(SreebCodecs.esc(f.name)).append('|')
                    .append(f.locked ? '1' : '0').append('|');
            for (int i = 0; i < f.members.size(); i++) {
                if (i > 0) b.append(',');
                b.append(SreebCodecs.esc(f.members.get(i)));
            }
        }
        return b.toString();
    }

    static List<Folder> decode(String raw) {
        List<Folder> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length < 4) continue; // malformed: drop the line, keep the rest
            String id = SreebCodecs.unesc(parts[0]);
            if (id.isEmpty()) continue;
            Folder f = new Folder(id, SreebCodecs.unesc(parts[1]));
            if (f.name.isEmpty()) f.name = "Folder";
            f.locked = "1".equals(parts[2]);
            if (!parts[3].isEmpty()) {
                for (String m : parts[3].split(",")) {
                    String pkg = SreebCodecs.unesc(m);
                    if (!pkg.isEmpty() && !f.members.contains(pkg)) f.members.add(pkg);
                }
            }
            out.add(f);
        }
        return out;
    }

    static Folder find(List<Folder> folders, String id) {
        if (id == null) return null;
        for (Folder f : folders) {
            if (f.id.equals(id)) return f;
        }
        return null;
    }

    /** Every package currently inside any folder. */
    static Set<String> folderedPackages(List<Folder> folders) {
        Set<String> out = new LinkedHashSet<>();
        for (Folder f : folders) out.addAll(f.members);
        return out;
    }

    /** Smallest unused "Folder N" name. */
    static String nextFolderName(List<Folder> folders) {
        Set<String> names = new LinkedHashSet<>();
        for (Folder f : folders) names.add(f.name);
        for (int n = 1; ; n++) {
            String cand = "Folder " + n;
            if (!names.contains(cand)) return cand;
        }
    }

    private static String nextFolderId(List<Folder> folders) {
        Set<String> ids = new LinkedHashSet<>();
        for (Folder f : folders) ids.add(f.id);
        for (int n = 1; ; n++) {
            String cand = "f" + n;
            if (!ids.contains(cand)) return cand;
        }
    }

    /** Deep copy: callers mutate the copy, never the stored list. */
    static List<Folder> copyOf(List<Folder> folders) {
        List<Folder> out = new ArrayList<>();
        for (Folder f : folders) out.add(f.copy());
        return out;
    }

    /** Add pkg to the folder; if it lives in another folder it MOVES (no duplicates anywhere). */
    static List<Folder> addToFolder(List<Folder> folders, String folderId, String pkg) {
        List<Folder> out = copyOf(folders);
        Folder target = find(out, folderId);
        if (target == null || pkg == null || pkg.isEmpty()) return out;
        for (Folder f : out) {
            if (f != target) f.members.remove(pkg);
        }
        if (!target.members.contains(pkg)) target.members.add(pkg);
        return out;
    }

    /** Create a folder named nextFolderName() holding pkg as its first member. */
    static List<Folder> createFolder(List<Folder> folders, String pkg) {
        List<Folder> out = copyOf(folders);
        if (pkg == null || pkg.isEmpty()) return out;
        for (Folder f : out) f.members.remove(pkg);
        Folder f = new Folder(nextFolderId(out), nextFolderName(out));
        f.members.add(pkg);
        out.add(f);
        return out;
    }

    static List<Folder> removeFromFolder(List<Folder> folders, String folderId, String pkg) {
        List<Folder> out = copyOf(folders);
        Folder f = find(out, folderId);
        if (f != null) f.members.remove(pkg);
        return out;
    }

    /** Delete the folder (its members return to the main grid — read them via find() before deleting). */
    static List<Folder> deleteFolder(List<Folder> folders, String folderId) {
        List<Folder> out = copyOf(folders);
        Folder f = find(out, folderId);
        if (f != null) out.remove(f);
        return out;
    }

    static List<Folder> renameFolder(List<Folder> folders, String folderId, String newName) {
        List<Folder> out = copyOf(folders);
        Folder f = find(out, folderId);
        if (f != null && newName != null && !newName.trim().isEmpty()) f.name = newName.trim();
        return out;
    }

    static List<Folder> setFolderLocked(List<Folder> folders, String folderId, boolean locked) {
        List<Folder> out = copyOf(folders);
        Folder f = find(out, folderId);
        if (f != null) f.locked = locked;
        return out;
    }

    /** Order-preserving dedupe, dropping null/empty entries. */
    static List<String> dedupe(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String s : in) {
            if (s != null && !s.isEmpty() && seen.add(s)) out.add(s);
        }
        return out;
    }

    /** Replace a folder's member list (used by the open-time stale-member prune). */
    static List<Folder> setFolderMembers(List<Folder> folders, String folderId, List<String> members) {
        List<Folder> out = copyOf(folders);
        Folder f = find(out, folderId);
        if (f != null) {
            f.members.clear();
            f.members.addAll(dedupe(members));
        }
        return out;
    }

    /** App-lock membership toggle (pure half of the tile-menu action; prefs I/O lives in SreebPrefs). */
    static Set<String> toggleLocked(Set<String> locked, String pkg) {
        Set<String> out = new LinkedHashSet<>(locked);
        if (!out.remove(pkg)) out.add(pkg);
        return out;
    }

    /** Folder lookup by member package (for the "already in a folder" check). */
    static Folder folderContaining(List<Folder> folders, String pkg) {
        for (Folder f : folders) {
            if (f.members.contains(pkg)) return f;
        }
        return null;
    }
}
