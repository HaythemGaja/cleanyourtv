package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Background tracker refresh for the fork: recomputes Up Next
 * and Missed from TMDB season data (the same rules Sreeb's
 * derived workers use), then rewrites the shared store files
 * + index + missed meta atomically.
 *
 * Rules mirrored from Sreeb (data/LibraryLocal):
 * - Next episode: first unwatched episode in season/episode
 *   order, skipping season 0 and episode 0. Caught-up shows
 *   lose their Up Next line; their Continue sub becomes
 *   "✓ All caught up".
 * - Missed: unwatched episodes aired strictly before today.
 *   Planned and muted shows never enter Missed (their lines
 *   are removed); planned shows also lose Up Next lines
 *   (they belong to Want to Watch until started).
 * - TMDB unreachable or keyless: the show's old lines stay
 *   (stale beats absent). Nothing is ever invented.
 * - Writes hold one lock and re-read the episode file, so a
 *   mark that landed mid-fetch is never overwritten.
 *
 * Throttle: refreshAll runs at most once per 24 h unless
 * forced (mark-watched and remote refresh force one show /
 * all shows respectively).
 */
final class TrackerRefresh {
    private static final Object WRITE_LOCK = new Object();
    private static final long THROTTLE_MS = 24L * 60L * 60L * 1000L;

    private static volatile boolean inFlight = false;

    private TrackerRefresh() {}

    interface Done {
        void run();
    }

    private static void postMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    /** Refresh one show on a worker; onDone runs on the main thread (may be null). */
    static void refreshShow(final Context ctx, final long tmdbId, final Done onDone) {
        final Context appCtx = ctx.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                doRefresh(appCtx, tmdbId, false);
            } catch (Exception e) {
                // Fail-open: old lines stay.
            }
            if (onDone != null) postMain(onDone::run);
        }, "fork-refresh-one");
        t.setDaemon(true);
        t.start();
    }

    /** Refresh all TV titles (throttled unless forced); onDone runs on the main thread. */
    static void refreshAll(final Context ctx, final boolean force, final Done onDone) {
        final Context appCtx = ctx.getApplicationContext();
        synchronized (TrackerRefresh.class) {
            if (inFlight) {
                if (onDone != null) postMain(onDone::run);
                return;
            }
            inFlight = true;
        }
        Thread t = new Thread(() -> {
            try {
                ForkState state = new ForkState(appCtx);
                long now = System.currentTimeMillis();
                if (!force && now - state.lastRefreshMs() < THROTTLE_MS) return;
                TrackerStore store = new TrackerStore(appCtx.getFilesDir());
                List<SreebCodecs.Title> titles = store.readTitles();
                for (SreebCodecs.Title title : titles) {
                    if (!"tv".equals(title.type)) continue;
                    try {
                        doRefresh(appCtx, title.tmdbId, true);
                    } catch (Exception e) {
                        // Per-show fail-open: keep that show's old lines.
                    }
                }
                finishBatch(appCtx);
                state.setLastRefreshMs(System.currentTimeMillis());
            } catch (Exception e) {
                // Fail-open.
            } finally {
                synchronized (TrackerRefresh.class) {
                    inFlight = false;
                }
                if (onDone != null) postMain(onDone::run);
            }
        }, "fork-refresh-all");
        t.setDaemon(true);
        t.start();
    }

    /** Recompute one show and write the shared files. batch=true skips the index rewrite (caller writes once). */
    private static void doRefresh(Context appCtx, long tmdbId, boolean batch) {
        SreebPrefs prefs = new SreebPrefs(appCtx);
        TrackerStore store = new TrackerStore(appCtx.getFilesDir());
        String key = prefs.tmdbKey();

        SreebCodecs.Title title0 = null;
        for (SreebCodecs.Title t : store.readTitles()) {
            if ("tv".equals(t.type) && t.tmdbId == tmdbId) { title0 = t; break; }
        }
        if (title0 == null) return;

        synchronized (WRITE_LOCK) {
            List<SreebCodecs.Title> titles = store.readTitles();
            SreebCodecs.Title title = null;
            for (SreebCodecs.Title t : titles) {
                if ("tv".equals(t.type) && t.tmdbId == tmdbId) { title = t; break; }
            }
            if (title == null) return;

            List<SreebCodecs.UpNextItem> up = store.readUpNext();
            List<SreebCodecs.UpNextItem> missed = store.readMissed();
            List<SreebCodecs.ContinueItem> cont = store.readContinue();

            up.removeIf(i -> i.tmdbId == tmdbId);
            missed.removeIf(i -> i.tmdbId == tmdbId);

            if (title.planned) {
                // Planned shows belong to Want to Watch until started: no Up Next, no Missed.
                if (!store.writeUpNext(up) || !store.writeMissed(missed)) return;
                if (!batch) {
                    store.writeMissedMeta(titles, missed);
                    store.rewriteIndex();
                }
                return;
            }
            if (title.muted) {
                // Muted shows never enter Missed; Up Next is left
                // untouched on disk (the display filter hides it).
                if (!store.writeMissed(missed)) return;
                if (!batch) {
                    store.writeMissedMeta(titles, missed);
                    store.rewriteIndex();
                }
                return;
            }

            SreebCodecs.EpisodeFile ep = store.readEpisode(tmdbId);
            if (ep == null && title.watchedCount > 0) return;
            Set<String> watched = ep == null ? new HashSet<>() : new HashSet<>(ep.watched);

            if (key == null || key.trim().isEmpty()) return;
            TmdbClient.TitleDetails details = TmdbClient.parseDetails(
                    TmdbClient.get("tv/" + tmdbId, key), "tv");
            if (details == null) return;
            List<SreebCodecs.SeasonEps> seasons = new ArrayList<>();
            int total = 0;
            for (TmdbClient.SeasonInfo s : details.seasons) {
                if (s.number < 1) continue;
                List<SreebCodecs.EpAir> eps = TmdbClient.parseSeasonEpisodes(
                        TmdbClient.get("tv/" + tmdbId + "/season/" + s.number, key));
                seasons.add(new SreebCodecs.SeasonEps(s.number, eps));
                total += eps.size();
            }

            String[] next = SreebCodecs.nextEpisode(watched, seasons);
            String poster = !title.poster.isEmpty() ? title.poster : details.poster;
            String backdrop = !title.backdrop.isEmpty() ? title.backdrop : details.backdrop;
            if (next != null) {
                up.add(new SreebCodecs.UpNextItem(tmdbId, title.title,
                        "S" + next[0] + " E" + next[1], next[2], poster, backdrop,
                        details.overview, Integer.parseInt(next[0]),
                        Integer.parseInt(next[1]), "tv"));
            }
            List<String[]> missedEps = SreebCodecs.allMissedEpisodes(
                    watched, seasons, SreebCodecs.todayIso());
            for (String[] m : missedEps) {
                missed.add(new SreebCodecs.UpNextItem(tmdbId, title.title,
                        "S" + m[0] + " E" + m[1], m[2], poster, backdrop,
                        "", Integer.parseInt(m[0]), Integer.parseInt(m[1]), "tv"));
            }
            List<SreebCodecs.Title> newTitles = new ArrayList<>();
            for (SreebCodecs.Title t : titles) {
                if (t.tmdbId == tmdbId && "tv".equals(t.type)) {
                    newTitles.add(new SreebCodecs.Title(t.type, t.tmdbId, t.title, t.poster,
                            t.backdrop, t.watchedCount, t.planned, t.muted,
                            next == null ? 0 : Integer.parseInt(next[0]),
                            next == null ? 0 : Integer.parseInt(next[1])));
                } else {
                    newTitles.add(t);
                }
            }
            List<SreebCodecs.ContinueItem> newCont = new ArrayList<>();
            for (SreebCodecs.ContinueItem c : cont) {
                if (c.tmdbId == tmdbId) {
                    String sub = next == null
                            ? "\u2713 All caught up"
                            : "Next S" + next[0] + " E" + next[1] + " \u00B7 "
                                    + watched.size() + " of " + total + " watched";
                    newCont.add(new SreebCodecs.ContinueItem(c.tmdbId, c.title, sub,
                            watched.size(), total, c.poster, c.backdrop, c.overview, c.type));
                } else {
                    newCont.add(c);
                }
            }
            if (!store.writeUpNext(up)) return;
            if (!store.writeMissed(missed)) return;
            if (!store.writeTitles(newTitles)) return;
            if (!store.writeContinue(newCont)) return;
            if (!batch) {
                store.writeMissedMeta(newTitles, missed);
                store.rewriteIndex();
            }
        }
    }

    /** Batch tail: after a refreshAll loop, write missed meta + index once. */
    static void finishBatch(Context ctx) {
        synchronized (WRITE_LOCK) {
            try {
                TrackerStore store = new TrackerStore(ctx.getApplicationContext().getFilesDir());
                store.writeMissedMeta(store.readTitles(), store.readMissed());
                store.rewriteIndex();
            } catch (Exception e) {
                // Fail-open.
            }
        }
    }
}
