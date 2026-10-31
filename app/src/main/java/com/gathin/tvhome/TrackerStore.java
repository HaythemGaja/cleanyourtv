package com.gathin.tvhome;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * File IO for Sreeb's tracker store (filesDir/tracker/).
 * The fork reads the same files Sreeb wrote — same
 * applicationId, same data directory — and writes back
 * through the same codecs with atomic tmp+rename writes,
 * so a later Sreeb install (or the fork itself) always
 * sees a consistent store.
 *
 * Bounded reads mirror Sreeb's caps (episode files 256 KB,
 * index 64 KB, section files 4 MB); over-cap or unreadable
 * files read as absent, never throw. Every writer keeps
 * the index consistent (counts from the files just written
 * + content fingerprint), because Sreeb's store validity
 * check compares the index against the files.
 */
final class TrackerStore {
    private static final String DIR = "tracker";
    private static final String F_TITLES = "titles.jsonl";
    private static final String F_UPNEXT = "upnext.txt";
    private static final String F_CONTINUE = "continue.txt";
    private static final String F_MOVIES = "movies.txt";
    private static final String F_FORYOU = "foryou.txt";
    private static final String F_MISSED = "missed.txt";
    private static final String F_MISSED_META = "missed.json";
    private static final String F_INDEX = "index.json";
    private static final String EPISODE_DIR = "episodes";

    private static final long MAX_EPISODE_BYTES = 256L * 1024L;
    private static final long MAX_INDEX_BYTES = 64L * 1024L;
    private static final long MAX_SECTION_BYTES = 4L * 1024L * 1024L;

    private final File dir;

    TrackerStore(File filesDir) {
        dir = new File(filesDir, DIR);
    }

    File dir() {
        return dir;
    }

    private File file(String name) {
        return new File(dir, name);
    }

    private File episodeFile(long tmdbId) {
        return new File(new File(dir, EPISODE_DIR), "tv_" + tmdbId + ".json");
    }

    private String readBounded(File f, long maxBytes) {
        try {
            if (f == null || !f.isFile() || f.length() > maxBytes) return null;
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[(int) f.length()];
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return new String(buf, 0, off, StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }

    private List<String> readLines(String name) {
        List<String> out = new ArrayList<>();
        File f = file(name);
        try {
            if (!f.isFile() || f.length() > MAX_SECTION_BYTES) return out;
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = r.readLine()) != null) out.add(line);
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    private boolean writeAtomic(File target, String content) {
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            File tmp = new File(parent, target.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                w.write(content);
                w.flush();
            } finally {
                out.close();
            }
            if (target.exists() && !target.delete()) return false;
            return tmp.renameTo(target);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean writeLines(String name, List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        return writeAtomic(file(name), b.toString());
    }

    // ------------------------------------------------------------ reads

    List<SreebCodecs.Title> readTitles() {
        List<SreebCodecs.Title> out = new ArrayList<>();
        for (String line : readLines(F_TITLES)) {
            SreebCodecs.Title t = SreebCodecs.decodeTitle(line);
            if (t != null) out.add(t);
        }
        return out;
    }

    List<SreebCodecs.UpNextItem> readUpNext() {
        List<SreebCodecs.UpNextItem> out = new ArrayList<>();
        for (String line : readLines(F_UPNEXT)) {
            SreebCodecs.UpNextItem i = SreebCodecs.decodeUpNext(line);
            if (i != null) out.add(i);
        }
        return out;
    }

    List<SreebCodecs.UpNextItem> readMissed() {
        List<SreebCodecs.UpNextItem> out = new ArrayList<>();
        for (String line : readLines(F_MISSED)) {
            SreebCodecs.UpNextItem i = SreebCodecs.decodeUpNext(line);
            if (i != null) out.add(i);
        }
        return out;
    }

    List<SreebCodecs.ForYouPick> readForYou() {
        List<SreebCodecs.ForYouPick> out = new ArrayList<>();
        for (String line : readLines(F_FORYOU)) {
            SreebCodecs.ForYouPick p = SreebCodecs.decodeForYou(line);
            if (p != null) out.add(p);
        }
        return out;
    }

    List<SreebCodecs.ContinueItem> readContinue() {
        List<SreebCodecs.ContinueItem> out = new ArrayList<>();
        for (String line : readLines(F_CONTINUE)) {
            SreebCodecs.ContinueItem i = SreebCodecs.decodeContinue(line);
            if (i != null) out.add(i);
        }
        return out;
    }

    List<SreebCodecs.MovieItem> readMovies() {
        List<SreebCodecs.MovieItem> out = new ArrayList<>();
        for (String line : readLines(F_MOVIES)) {
            SreebCodecs.MovieItem i = SreebCodecs.decodeMovie(line);
            if (i != null) out.add(i);
        }
        return out;
    }

    SreebCodecs.EpisodeFile readEpisode(long tmdbId) {
        String json = readBounded(episodeFile(tmdbId), MAX_EPISODE_BYTES);
        return SreebCodecs.decodeEpisode(json);
    }

    SreebCodecs.Index readIndex() {
        return SreebCodecs.decodeIndex(readBounded(file(F_INDEX), MAX_INDEX_BYTES));
    }

    int countEpisodeFiles() {
        File d = new File(dir, EPISODE_DIR);
        String[] names = d.list();
        return names == null ? 0 : names.length;
    }

    // ------------------------------------------------------------ writes

    boolean writeTitles(List<SreebCodecs.Title> titles) {
        List<String> lines = new ArrayList<>();
        for (SreebCodecs.Title t : titles) lines.add(SreebCodecs.encodeTitle(t));
        return writeLines(F_TITLES, lines);
    }

    boolean writeUpNext(List<SreebCodecs.UpNextItem> items) {
        List<String> lines = new ArrayList<>();
        for (SreebCodecs.UpNextItem i : items) lines.add(SreebCodecs.encodeUpNext(i));
        return writeLines(F_UPNEXT, lines);
    }

    boolean writeMissed(List<SreebCodecs.UpNextItem> items) {
        List<String> lines = new ArrayList<>();
        for (SreebCodecs.UpNextItem i : items) lines.add(SreebCodecs.encodeUpNext(i));
        return writeLines(F_MISSED, lines);
    }

    boolean writeContinue(List<SreebCodecs.ContinueItem> items) {
        List<String> lines = new ArrayList<>();
        for (SreebCodecs.ContinueItem i : items) lines.add(SreebCodecs.encodeContinue(i));
        return writeLines(F_CONTINUE, lines);
    }

    boolean writeEpisode(long tmdbId, SreebCodecs.EpisodeFile e) {
        return writeAtomic(episodeFile(tmdbId), SreebCodecs.encodeEpisode(e));
    }

    /** Rewrite index.json from the files just written. Returns false when the write fails. */
    boolean rewriteIndex() {
        List<SreebCodecs.Title> titles = readTitles();
        List<SreebCodecs.UpNextItem> up = readUpNext();
        List<SreebCodecs.ContinueItem> cont = readContinue();
        List<SreebCodecs.MovieItem> movies = readMovies();
        List<SreebCodecs.ForYouPick> forYou = readForYou();
        SreebCodecs.Index old = readIndex();
        long updated = System.currentTimeMillis();
        SreebCodecs.Index idx = new SreebCodecs.Index(
                titles.size(), up.size(), cont.size(), movies.size(), forYou.size(),
                updated, old == null ? 0L : old.fetchedAt,
                SreebCodecs.fingerprint(updated, titles, up, cont, movies, forYou),
                countEpisodeFiles());
        return writeAtomic(file(F_INDEX), SreebCodecs.encodeIndex(idx));
    }

    /** Missed meta (missed.json): updated stamp, distinct-show count, input fingerprint. */
    boolean writeMissedMeta(List<SreebCodecs.Title> titles, List<SreebCodecs.UpNextItem> missed) {
        java.util.Set<Long> shows = new java.util.HashSet<>();
        for (SreebCodecs.UpNextItem i : missed) shows.add(i.tmdbId);
        String json = "{\"updated\":" + System.currentTimeMillis()
                + ",\"count\":" + shows.size()
                + ",\"inputFingerprint\":\"" + SreebCodecs.missedFingerprint(titles) + "\"}";
        return writeAtomic(file(F_MISSED_META), json);
    }

    /**
     * Mark one episode watched. Returns false (and writes
     * nothing) when the episode file is missing for a show
     * with a positive watched count — Sreeb's rule: never
     * overwrite a true count with a partial set. Otherwise
     * updates the episode file and the title's watched count
     * (set size), and returns true.
     */
    boolean markWatched(long tmdbId, int season, int episode) {
        List<SreebCodecs.Title> titles = readTitles();
        SreebCodecs.Title target = null;
        for (SreebCodecs.Title t : titles) {
            if ("tv".equals(t.type) && t.tmdbId == tmdbId) { target = t; break; }
        }
        if (target == null) return false;
        SreebCodecs.EpisodeFile ep = readEpisode(tmdbId);
        if (ep == null && target.watchedCount > 0) return false;
        java.util.Set<String> watched = new java.util.HashSet<>();
        if (ep != null) watched.addAll(ep.watched);
        watched.add(SreebCodecs.epKey(season, episode));
        if (!writeEpisode(tmdbId, new SreebCodecs.EpisodeFile(ep != null && ep.muted, watched))) {
            return false;
        }
        List<SreebCodecs.Title> updated = new ArrayList<>();
        for (SreebCodecs.Title t : titles) {
            if (t == target) {
                updated.add(new SreebCodecs.Title(t.type, t.tmdbId, t.title, t.poster,
                        t.backdrop, watched.size(), t.planned, t.muted,
                        t.nextSeason, t.nextEpisode));
            } else {
                updated.add(t);
            }
        }
        return writeTitles(updated);
    }
}
