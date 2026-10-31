package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal TMDB API client for the fork's tracker refresh
 * (details + season episode lists + search). Plain
 * HttpURLConnection, no Android imports except none at all —
 * it only uses java.net, so the harness can parse its
 * payloads through MiniJson.
 *
 * Key hygiene mirrors Sreeb's TmdbDirect: the key is read
 * from Sreeb's prefs by the caller, used to build the
 * request URL in memory, and NEVER logged, returned, or
 * persisted anywhere else. Paths are allowlisted to tv/,
 * movie/ and search/ prefixes, and ".." / "://" are refused.
 */
final class TmdbClient {
    private static final String BASE = "https://api.themoviedb.org/3/";
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private TmdbClient() {}

    static final class TitleDetails {
        final long tmdbId;
        final String type;
        final String title;
        final String overview;
        final String poster;
        final String backdrop;
        final List<SeasonInfo> seasons;

        TitleDetails(long tmdbId, String type, String title, String overview,
                     String poster, String backdrop, List<SeasonInfo> seasons) {
            this.tmdbId = tmdbId; this.type = type; this.title = title;
            this.overview = overview; this.poster = poster; this.backdrop = backdrop;
            this.seasons = seasons;
        }
    }

    static final class SeasonInfo {
        final int number;
        final int episodeCount;

        SeasonInfo(int number, int episodeCount) {
            this.number = number; this.episodeCount = episodeCount;
        }
    }

    static final class SearchHit {
        final long id;
        final String type;
        final String title;
        final String poster;
        final String backdrop;
        final String overview;

        SearchHit(long id, String type, String title, String poster, String backdrop, String overview) {
            this.id = id; this.type = type; this.title = title;
            this.poster = poster; this.backdrop = backdrop; this.overview = overview;
        }
    }

    /** GET body, or null on any failure (no key, bad path, HTTP, IO). Never throws. */
    static String get(String path, String key) {
        String p = path == null ? "" : path.trim();
        if (p.startsWith("/")) p = p.substring(1);
        if (p.contains("://") || p.contains("..")
                || !(p.startsWith("tv/") || p.startsWith("movie/") || p.startsWith("search/"))) {
            return null;
        }
        if (key == null || key.trim().isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            String url = BASE + p + (p.contains("?") ? "&api_key=" : "?api_key=")
                    + URLEncoder.encode(key.trim(), "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "CleanYourTV-Fork/1.0");
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            while (true) {
                int n = in.read(buf);
                if (n <= 0) break;
                total += n;
                if (total > MAX_BODY_BYTES) break;
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static TitleDetails parseDetails(String json, String type) {
        Map<String, Object> o;
        try {
            o = MiniJson.asObj(MiniJson.parse(json));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (o == null) return null;
        Long id = MiniJson.asNum(o.get("id"));
        if (id == null || id <= 0L) return null;
        boolean movie = "movie".equals(type);
        String title = movie ? str(o, "title") : str(o, "name");
        if (title.trim().isEmpty()) return null;
        List<SeasonInfo> seasons = new ArrayList<>();
        if (!movie) {
            List<Object> arr = MiniJson.asArr(o.get("seasons"));
            if (arr != null) {
                for (Object raw : arr) {
                    Map<String, Object> s = MiniJson.asObj(raw);
                    if (s == null) continue;
                    Long count = MiniJson.asNum(s.get("episode_count"));
                    Long number = MiniJson.asNum(s.get("season_number"));
                    if (count == null || count <= 0L || number == null) continue;
                    seasons.add(new SeasonInfo(number.intValue(), count.intValue()));
                }
                seasons.sort((a, b) -> Integer.compare(a.number, b.number));
            }
        }
        return new TitleDetails(id, movie ? "movie" : "tv", title, str(o, "overview"),
                str(o, "poster_path"), str(o, "backdrop_path"), seasons);
    }

    /** Season payload -> episode (number, air date) pairs, in payload order. */
    static List<SreebCodecs.EpAir> parseSeasonEpisodes(String json) {
        List<SreebCodecs.EpAir> out = new ArrayList<>();
        Map<String, Object> o;
        try {
            o = MiniJson.asObj(MiniJson.parse(json));
        } catch (IllegalArgumentException e) {
            return out;
        }
        if (o == null) return out;
        List<Object> arr = MiniJson.asArr(o.get("episodes"));
        if (arr == null) return out;
        for (Object raw : arr) {
            Map<String, Object> e = MiniJson.asObj(raw);
            if (e == null) continue;
            Long n = MiniJson.asNum(e.get("episode_number"));
            if (n == null || n <= 0L) continue;
            out.add(new SreebCodecs.EpAir(n.intValue(), str(e, "air_date")));
        }
        return out;
    }

    /** search/multi results, tv + movie hits only, in result order. */
    static List<SearchHit> parseSearch(String json) {
        List<SearchHit> out = new ArrayList<>();
        Map<String, Object> o;
        try {
            o = MiniJson.asObj(MiniJson.parse(json));
        } catch (IllegalArgumentException e) {
            return out;
        }
        if (o == null) return out;
        List<Object> arr = MiniJson.asArr(o.get("results"));
        if (arr == null) return out;
        for (Object raw : arr) {
            Map<String, Object> r = MiniJson.asObj(raw);
            if (r == null) continue;
            String media = str(r, "media_type");
            if (!"tv".equals(media) && !"movie".equals(media)) continue;
            Long id = MiniJson.asNum(r.get("id"));
            if (id == null || id <= 0L) continue;
            String title = "movie".equals(media) ? str(r, "title") : str(r, "name");
            if (title.trim().isEmpty()) continue;
            out.add(new SearchHit(id, media, title, str(r, "poster_path"),
                    str(r, "backdrop_path"), str(r, "overview")));
        }
        return out;
    }

    private static String str(Map<String, Object> o, String k) {
        String v = MiniJson.asStr(o.get(k));
        return v == null ? "" : v;
    }
}
