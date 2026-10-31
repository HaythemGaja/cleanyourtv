package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sreeb tracker-store codecs and section rules, ported to Java for
 * the fork. Pure code, no Android imports — the headless harness
 * verifies it against Sreeb's real store format.
 *
 * Store layout (filesDir/tracker/, shared with Sreeb via the same
 * applicationId): titles.jsonl, upnext.txt, missed.txt,
 * foryou.txt, continue.txt, movies.txt, index.json, and
 * episodes/tv_<tmdbId>.json. Line codec: '|' fields with the
 * %-pipe escape; episode files are small JSON.
 *
 * Codec discipline mirrors Sreeb: a malformed line is skipped
 * (fail-open), never throws; a malformed episode file reads as
 * an empty watched set only through the caller's null check.
 */
final class SreebCodecs {
    private SreebCodecs() {}

    // ------------------------------------------------------------ pipe codec

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("%", "%25").replace("|", "%7C")
                .replace("\n", "%0A").replace("\r", "%0D");
    }

    static String unesc(String s) {
        if (s == null) return "";
        return s.replace("%0D", "\r").replace("%0A", "\n")
                .replace("%7C", "|").replace("%25", "%");
    }

    private static List<String> fields(String line, int count) {
        String[] parts = line.split("\\|", -1);
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(i < parts.length ? unesc(parts[i]) : "");
        }
        return out;
    }

    private static String enc(String... values) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) b.append('|');
            b.append(esc(values[i]));
        }
        return b.toString();
    }

    private static long num(String s) {
        if (s == null) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean flag(String s) {
        return "1".equals(s);
    }

    private static String jsonEsc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
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

    // ------------------------------------------------------------ records

    static final class Title {
        final String type;
        final long tmdbId;
        final String title;
        final String poster;
        final String backdrop;
        final int watchedCount;
        final boolean planned;
        final boolean muted;
        final int nextSeason;
        final int nextEpisode;

        Title(String type, long tmdbId, String title, String poster, String backdrop,
              int watchedCount, boolean planned, boolean muted, int nextSeason, int nextEpisode) {
            this.type = type; this.tmdbId = tmdbId; this.title = title;
            this.poster = poster; this.backdrop = backdrop;
            this.watchedCount = watchedCount; this.planned = planned; this.muted = muted;
            this.nextSeason = nextSeason; this.nextEpisode = nextEpisode;
        }
    }

    static final class UpNextItem {
        final long tmdbId;
        final String title;
        final String sub;
        final String dateIso;
        final String poster;
        final String backdrop;
        final String overview;
        final int season;
        final int episode;
        final String type;

        UpNextItem(long tmdbId, String title, String sub, String dateIso, String poster,
                   String backdrop, String overview, int season, int episode, String type) {
            this.tmdbId = tmdbId; this.title = title; this.sub = sub; this.dateIso = dateIso;
            this.poster = poster; this.backdrop = backdrop; this.overview = overview;
            this.season = season; this.episode = episode; this.type = type;
        }
    }

    static final class ForYouPick {
        final long id;
        final String type;
        final String title;
        final String poster;
        final double voteAverage;
        final String reason;
        final List<String> whyGenres;
        final double score;

        ForYouPick(long id, String type, String title, String poster, double voteAverage,
                   String reason, List<String> whyGenres, double score) {
            this.id = id; this.type = type; this.title = title; this.poster = poster;
            this.voteAverage = voteAverage; this.reason = reason;
            this.whyGenres = whyGenres; this.score = score;
        }
    }

    static final class ContinueItem {
        final long tmdbId;
        final String title;
        final String sub;
        final int watched;
        final int total;
        final String poster;
        final String backdrop;
        final String overview;
        final String type;

        ContinueItem(long tmdbId, String title, String sub, int watched, int total,
                     String poster, String backdrop, String overview, String type) {
            this.tmdbId = tmdbId; this.title = title; this.sub = sub;
            this.watched = watched; this.total = total; this.poster = poster;
            this.backdrop = backdrop; this.overview = overview; this.type = type;
        }
    }

    static final class MovieItem {
        final long tmdbId;
        final String title;
        final String releaseDate;
        final boolean planned;
        final String poster;
        final boolean watched;

        MovieItem(long tmdbId, String title, String releaseDate, boolean planned,
                  String poster, boolean watched) {
            this.tmdbId = tmdbId; this.title = title; this.releaseDate = releaseDate;
            this.planned = planned; this.poster = poster; this.watched = watched;
        }
    }

    static final class EpisodeFile {
        final boolean muted;
        final Set<String> watched;

        EpisodeFile(boolean muted, Set<String> watched) {
            this.muted = muted; this.watched = watched;
        }
    }

    static final class Index {
        final int titles;
        final int upnext;
        final int continueCount;
        final int movies;
        final int foryou;
        final long updated;
        final long fetchedAt;
        final String fingerprint;
        final int episodeFiles;

        Index(int titles, int upnext, int continueCount, int movies, int foryou,
              long updated, long fetchedAt, String fingerprint, int episodeFiles) {
            this.titles = titles; this.upnext = upnext; this.continueCount = continueCount;
            this.movies = movies; this.foryou = foryou; this.updated = updated;
            this.fetchedAt = fetchedAt; this.fingerprint = fingerprint;
            this.episodeFiles = episodeFiles;
        }
    }

    // ------------------------------------------------------------ line codecs

    static String encodeTitle(Title t) {
        return enc(t.type, Long.toString(t.tmdbId), t.title, t.poster, t.backdrop,
                Integer.toString(t.watchedCount), t.planned ? "1" : "0",
                t.muted ? "1" : "0", Integer.toString(t.nextSeason),
                Integer.toString(t.nextEpisode));
    }

    static Title decodeTitle(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        List<String> f = fields(line, 10);
        long id = num(f.get(1));
        if (id <= 0L || f.get(2).trim().isEmpty()) return null;
        return new Title("movie".equals(f.get(0)) ? "movie" : "tv", id, f.get(2), f.get(3),
                f.get(4), (int) Math.max(0L, num(f.get(5))), flag(f.get(6)), flag(f.get(7)),
                (int) Math.max(0L, num(f.get(8))), (int) Math.max(0L, num(f.get(9))));
    }

    static String encodeUpNext(UpNextItem i) {
        return enc(Long.toString(i.tmdbId), i.title, i.sub, i.dateIso, i.poster,
                i.backdrop, i.overview, Integer.toString(i.season),
                Integer.toString(i.episode), i.type);
    }

    static UpNextItem decodeUpNext(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        List<String> f = fields(line, 10);
        long id = num(f.get(0));
        if (id <= 0L || f.get(1).trim().isEmpty()) return null;
        String type = f.get(9).trim().isEmpty() ? "tv" : f.get(9);
        return new UpNextItem(id, f.get(1), f.get(2), f.get(3), f.get(4), f.get(5),
                f.get(6), (int) num(f.get(7)), (int) num(f.get(8)), type);
    }

    static String encodeForYou(ForYouPick p) {
        StringBuilder genres = new StringBuilder();
        for (int i = 0; i < p.whyGenres.size(); i++) {
            if (i > 0) genres.append('\u001F');
            genres.append(p.whyGenres.get(i));
        }
        return enc(Long.toString(p.id), p.type, p.title, p.poster,
                Double.toString(p.voteAverage), p.reason, genres.toString(),
                Double.toString(p.score));
    }

    static ForYouPick decodeForYou(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        List<String> f = fields(line, 8);
        long id = num(f.get(0));
        if (id <= 0L || f.get(2).trim().isEmpty()) return null;
        double vote;
        double score;
        try { vote = Double.parseDouble(f.get(4)); } catch (NumberFormatException e) { vote = 0.0; }
        try { score = Double.parseDouble(f.get(7)); } catch (NumberFormatException e) { score = 0.0; }
        List<String> genres = new ArrayList<>();
        if (!f.get(6).trim().isEmpty()) {
            for (String g : f.get(6).split("\u001F", -1)) {
                if (!g.trim().isEmpty()) genres.add(g);
            }
        }
        String type = f.get(1).trim().isEmpty() ? "tv" : f.get(1);
        return new ForYouPick(id, type, f.get(2), f.get(3), vote, f.get(5), genres, score);
    }

    static String encodeContinue(ContinueItem i) {
        return enc(Long.toString(i.tmdbId), i.title, i.sub, Integer.toString(i.watched),
                Integer.toString(i.total), i.poster, i.backdrop, i.overview, i.type);
    }

    static ContinueItem decodeContinue(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        List<String> f = fields(line, 9);
        long id = num(f.get(0));
        if (id <= 0L || f.get(1).trim().isEmpty()) return null;
        String type = f.get(8).trim().isEmpty() ? "tv" : f.get(8);
        return new ContinueItem(id, f.get(1), f.get(2), (int) num(f.get(3)),
                (int) num(f.get(4)), f.get(5), f.get(6), f.get(7), type);
    }

    static String encodeMovie(MovieItem i) {
        return enc(Long.toString(i.tmdbId), i.title, i.releaseDate,
                i.planned ? "1" : "0", i.poster, i.watched ? "1" : "0");
    }

    static MovieItem decodeMovie(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        List<String> f = fields(line, 6);
        long id = num(f.get(0));
        if (id <= 0L || f.get(1).trim().isEmpty()) return null;
        return new MovieItem(id, f.get(1), f.get(2), flag(f.get(3)), f.get(4), flag(f.get(5)));
    }

    static String encodeEpisode(EpisodeFile e) {
        List<String> sorted = new ArrayList<>(e.watched);
        java.util.Collections.sort(sorted);
        StringBuilder b = new StringBuilder();
        b.append("{\"muted\":").append(e.muted ? "true" : "false").append(",\"watched\":[");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(jsonEsc(sorted.get(i))).append('"');
        }
        b.append("]}");
        return b.toString();
    }

    static EpisodeFile decodeEpisode(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        Map<String, Object> o;
        try {
            o = MiniJson.asObj(MiniJson.parse(json));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (o == null) return null;
        Boolean muted = MiniJson.asBool(o.get("muted"));
        Set<String> watched = new HashSet<>();
        List<Object> arr = MiniJson.asArr(o.get("watched"));
        if (arr != null) {
            for (Object v : arr) {
                String s = MiniJson.asStr(v);
                if (s != null && !s.trim().isEmpty()) watched.add(s);
            }
        }
        return new EpisodeFile(muted != null && muted, watched);
    }

    static String encodeIndex(Index idx) {
        return "{\"titles\":" + idx.titles + ",\"upnext\":" + idx.upnext
                + ",\"continue\":" + idx.continueCount + ",\"movies\":" + idx.movies
                + ",\"foryou\":" + idx.foryou + ",\"updated\":" + idx.updated
                + ",\"fetchedAt\":" + idx.fetchedAt
                + ",\"fingerprint\":\"" + jsonEsc(idx.fingerprint) + "\""
                + ",\"episodeFiles\":" + idx.episodeFiles + "}";
    }

    static Index decodeIndex(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        Map<String, Object> o;
        try {
            o = MiniJson.asObj(MiniJson.parse(json));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (o == null) return null;
        String fp = MiniJson.asStr(o.get("fingerprint"));
        if (fp == null) return null;
        return new Index(n(o, "titles"), n(o, "upnext"), n(o, "continue"), n(o, "movies"),
                n(o, "foryou"), ln(o, "updated"), ln(o, "fetchedAt"), fp, n(o, "episodeFiles"));
    }

    private static int n(Map<String, Object> o, String k) {
        Long v = MiniJson.asNum(o.get(k));
        return v == null ? 0 : v.intValue();
    }

    private static long ln(Map<String, Object> o, String k) {
        Long v = MiniJson.asNum(o.get(k));
        return v == null ? 0L : v;
    }

    // ------------------------------------------------------------ fingerprint (FNV-1a 64)

    static String fingerprint(long updated, List<Title> titles, List<UpNextItem> upNext,
                              List<ContinueItem> cont, List<MovieItem> movies,
                              List<ForYouPick> forYou) {
        StringBuilder sb = new StringBuilder();
        sb.append("u=").append(updated).append(';');
        for (Title t : titles) {
            sb.append("t:").append(t.tmdbId).append(':').append(t.title)
                    .append(':').append(t.watchedCount).append(':')
                    .append(t.planned ? 1 : 0).append(':')
                    .append(t.muted ? 1 : 0).append(';');
        }
        for (UpNextItem i : upNext) sb.append("n:").append(i.tmdbId).append(':').append(i.title).append(';');
        for (ContinueItem i : cont) sb.append("c:").append(i.tmdbId).append(':').append(i.title).append(':').append(i.watched).append(';');
        for (MovieItem i : movies) sb.append("m:").append(i.tmdbId).append(':').append(i.title).append(':').append(i.watched ? 1 : 0).append(';');
        for (ForYouPick p : forYou) sb.append("f:").append(p.id).append(':').append(p.title).append(';');
        long h = -3750763034362895579L;
        String s = sb.toString();
        for (int i = 0; i < s.length(); i++) {
            h = h ^ (long) s.charAt(i);
            h *= 1099511628211L;
        }
        return kotlinHex(h);
    }

    static String missedFingerprint(List<Title> titles) {
        StringBuilder sb = new StringBuilder();
        for (Title t : titles) {
            if (!"tv".equals(t.type)) continue;
            sb.append("t:").append(t.tmdbId).append(':').append(t.watchedCount)
                    .append(':').append(t.planned ? 1 : 0).append(':')
                    .append(t.muted ? 1 : 0).append(';');
        }
        long h = -3750763034362895579L;
        String s = sb.toString();
        for (int i = 0; i < s.length(); i++) {
            h = h ^ (long) s.charAt(i);
            h *= 1099511628211L;
        }
        return kotlinHex(h);
    }

    /**
     * Kotlin's Long.toString(16) + padStart(16, '0'), exactly:
     * negatives render as "-" + magnitude hex, and the zero
     * padding goes on the LEFT of the minus sign (so a negative
     * hash becomes e.g. "0000000000000-ff"). Java's
     * Long.toHexString instead renders two's complement, which
     * would disagree with Sreeb's stored fingerprints half the
     * time — a mismatch only ever triggers a recompute, but
     * matching exactly keeps the shared store consistent.
     */
    static String kotlinHex(long h) {
        String s = h < 0 ? "-" + Long.toHexString(-h) : Long.toHexString(h);
        while (s.length() < 16) s = "0" + s;
        return s;
    }

    // ------------------------------------------------------------ dates + sections

    /** Episode key: "SxE" (Sreeb's LibraryLogic.epKey). */
    static String epKey(int season, int episode) {
        return season + "x" + episode;
    }

    /** Strict yyyy-MM-dd to epoch days (Howard Hinnant's algorithm); null when malformed. */
    static Long isoDays(String iso) {
        if (iso == null) return null;
        String t = iso.trim();
        if (t.length() != 10 || t.charAt(4) != '-' || t.charAt(7) != '-') return null;
        int y, m, d;
        try {
            y = Integer.parseInt(t.substring(0, 4));
            m = Integer.parseInt(t.substring(5, 7));
            d = Integer.parseInt(t.substring(8, 10));
        } catch (NumberFormatException e) {
            return null;
        }
        if (m < 1 || m > 12 || d < 1 || d > 31) return null;
        int yy = m <= 2 ? y - 1 : y;
        long era = Math.floorDiv(yy, 400);
        int yoe = (int) (yy - era * 400);   // yoe is in [0, 399]; the cast is exact.
        int mp = (m + 9) % 12;
        int doy = (153 * mp + 2) / 5 + d - 1;
        long doe = (long) yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097L + doe - 719468L;
    }

    static String todayIso() {
        return java.time.LocalDate.now().toString();
    }

    static final class SeasonEps {
        final int season;
        final List<EpAir> episodes;

        SeasonEps(int season, List<EpAir> episodes) {
            this.season = season; this.episodes = episodes;
        }
    }

    static final class EpAir {
        final int episode;
        final String airDate;

        EpAir(int episode, String airDate) {
            this.episode = episode; this.airDate = airDate;
        }
    }

    /** Next unwatched episode: seasons ascending, episodes ascending,
     *  skipping season < 1 and episode < 1. Returns {season, episode, airDate} or null. */
    static String[] nextEpisode(Set<String> watched, List<SeasonEps> seasons) {
        List<SeasonEps> sorted = new ArrayList<>(seasons);
        java.util.Collections.sort(sorted, (a, b) -> Integer.compare(a.season, b.season));
        for (SeasonEps s : sorted) {
            if (s.season < 1) continue;
            List<EpAir> eps = new ArrayList<>(s.episodes);
            java.util.Collections.sort(eps, (a, b) -> Integer.compare(a.episode, b.episode));
            for (EpAir e : eps) {
                if (e.episode < 1) continue;
                if (!watched.contains(epKey(s.season, e.episode))) {
                    return new String[]{Integer.toString(s.season), Integer.toString(e.episode), e.airDate};
                }
            }
        }
        return null;
    }

    /** All passed unwatched episodes: air date strictly before today, malformed dates skipped. */
    static List<String[]> allMissedEpisodes(Set<String> watched, List<SeasonEps> seasons, String todayIso) {
        List<String[]> out = new ArrayList<>();
        Long todayDay = isoDays(todayIso);
        if (todayDay == null) return out;
        List<SeasonEps> sorted = new ArrayList<>(seasons);
        java.util.Collections.sort(sorted, (a, b) -> Integer.compare(a.season, b.season));
        for (SeasonEps s : sorted) {
            if (s.season < 1) continue;
            List<EpAir> eps = new ArrayList<>(s.episodes);
            java.util.Collections.sort(eps, (a, b) -> Integer.compare(a.episode, b.episode));
            for (EpAir e : eps) {
                if (e.episode < 1) continue;
                if (watched.contains(epKey(s.season, e.episode))) continue;
                Long d = isoDays(e.airDate);
                if (d == null) continue;
                if (d < todayDay) out.add(new String[]{Integer.toString(s.season), Integer.toString(e.episode), e.airDate.trim()});
            }
        }
        return out;
    }

    /** Date-seeded For You rotation (Sreeb v3.13): Fisher-Yates seeded
     *  by the epoch day, stable within a day. Lists of size <= 1 and
     *  unparseable days return unchanged. */
    static List<ForYouPick> rotateForYouPicks(List<ForYouPick> picks, String todayIso) {
        if (picks.size() <= 1) return new ArrayList<>(picks);
        Long day = isoDays(todayIso == null ? null : todayIso.trim());
        if (day == null) return new ArrayList<>(picks);
        List<ForYouPick> out = new ArrayList<>(picks);
        long state = day * 6364136223846793005L + 1442695040888963407L;
        for (int i = out.size() - 1; i >= 1; i--) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int j = (int) ((state >>> 33) % (i + 1));
            ForYouPick tmp = out.get(i);
            out.set(i, out.get(j));
            out.set(j, tmp);
        }
        return out;
    }

    /** Full TMDB image URL for a raw path ("/abc.jpg"); full URLs pass through unchanged. */
    static String posterUrl(String size, String path) {
        if (path == null || path.trim().isEmpty()) return "";
        String p = path.trim();
        if (p.startsWith("http://") || p.startsWith("https://")) return p;
        return "https://image.tmdb.org/t/p/" + size + p;
    }
}
