package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracker home sections for the fork: Hero → Up Next →
 * Missed → For You, then the fork's normal app grid below
 * (the container is wrapped in a scroll view and inserted above
 * the grid by MainActivity, v3.21: scroll view and grid wrapper
 * share the leftover height equally, so the grid is never
 * squeezed to zero).
 *
 * Data comes from TrackerData, prepared by MainActivity
 * from the shared Sreeb store files (already filtered for
 * muted/planned titles). Every section fails open: an
 * empty section is hidden, an empty library shows a hint,
 * a failing poster leaves its card text-only.
 *
 * Cards render 2:3 portrait posters, never centre-cropped
 * (FIT_CENTER in a fixed 2:3 box). The hero backdrop is
 * landscape art and uses CENTER_CROP, as backdrops do.
 */
final class TrackerUi {
    private TrackerUi() {}

    /** One card's click action (mark-watched etc. runs in the caller). */
    interface CardAction {
        void run();
    }

    static final class CardItem {
        final String title;
        final String sub;
        final String posterPath;
        final String overview;
        final CardAction onClick;

        CardItem(String title, String sub, String posterPath, String overview, CardAction onClick) {
            this.title = title; this.sub = sub; this.posterPath = posterPath;
            this.overview = overview; this.onClick = onClick;
        }
    }

    /** Missed lines grouped per show (Sreeb v3.13 groups the same way). */
    static final class MissedGroup {
        final long tmdbId;
        final String title;
        final String poster;
        final String backdrop;
        final List<SreebCodecs.UpNextItem> episodes;

        MissedGroup(long tmdbId, String title, String poster, String backdrop,
                    List<SreebCodecs.UpNextItem> episodes) {
            this.tmdbId = tmdbId; this.title = title; this.poster = poster;
            this.backdrop = backdrop; this.episodes = episodes;
        }
    }

    static final class TrackerData {
        SreebCodecs.UpNextItem hero;
        List<SreebCodecs.UpNextItem> upNext = new ArrayList<>();
        List<MissedGroup> missed = new ArrayList<>();
        List<SreebCodecs.ForYouPick> forYou = new ArrayList<>();
        boolean hasTitles;
    }

    /** Prepare display data from the shared store (call off the main thread). */
    static TrackerData prepare(TrackerStore store) {
        TrackerData data = new TrackerData();
        List<SreebCodecs.Title> titles = store.readTitles();
        data.hasTitles = !titles.isEmpty();
        Map<Long, SreebCodecs.Title> byId = new LinkedHashMap<>();
        for (SreebCodecs.Title t : titles) byId.put(t.tmdbId, t);

        List<SreebCodecs.UpNextItem> up = new ArrayList<>();
        for (SreebCodecs.UpNextItem i : store.readUpNext()) {
            SreebCodecs.Title t = byId.get(i.tmdbId);
            if (t != null && (t.muted || t.planned)) continue;
            up.add(i);
        }
        data.upNext = up;
        if (!up.isEmpty()) data.hero = up.get(0);

        Map<Long, MissedGroup> groups = new LinkedHashMap<>();
        for (SreebCodecs.UpNextItem i : store.readMissed()) {
            SreebCodecs.Title t = byId.get(i.tmdbId);
            if (t != null && (t.muted || t.planned)) continue;
            MissedGroup g = groups.get(i.tmdbId);
            if (g == null) {
                g = new MissedGroup(i.tmdbId, i.title, i.poster, i.backdrop, new ArrayList<>());
                groups.put(i.tmdbId, g);
            }
            g.episodes.add(i);
        }
        data.missed = new ArrayList<>(groups.values());

        List<SreebCodecs.ForYouPick> picks =
                SreebCodecs.rotateForYouPicks(store.readForYou(), SreebCodecs.todayIso());
        if (picks.size() > 20) picks = new ArrayList<>(picks.subList(0, 20));
        data.forYou = picks;
        return data;
    }

    private static int dp(Activity act, int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private static TextView text(Activity act, String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    /** Build the whole tracker container. onMark(ids) and onChanged live in MainActivity. */
    static LinearLayout buildContainer(final Activity act, final TrackerData data,
                                       final PosterLoader posters,
                                       final MarkWatched markWatched,
                                       final Runnable onAddShow) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = dp(act, 56);
        root.setPadding(side, dp(act, 8), side, dp(act, 8));

        if (!data.hasTitles) {
            TextView hint = text(act, act.getString(R.string.tracker_empty_hint), 16, 0xFFACB8C1, false);
            root.addView(hint);
        } else {
            if (data.hero != null) root.addView(buildHero(act, data.hero, posters, markWatched));
            if (!data.upNext.isEmpty()) {
                List<CardItem> cards = new ArrayList<>();
                for (SreebCodecs.UpNextItem i : data.upNext) {
                    final SreebCodecs.UpNextItem item = i;
                    cards.add(new CardItem(i.title, i.sub + (i.dateIso.isEmpty() ? "" : "  ·  " + i.dateIso),
                            i.poster, i.overview,
                            () -> showItemDialog(act, i.title, i.sub, i.dateIso, i.overview,
                                    () -> markWatched.mark(item.tmdbId, item.season, item.episode))));
                }
                root.addView(buildSection(act, act.getString(R.string.tracker_up_next), cards, posters));
            }
            if (!data.missed.isEmpty()) {
                List<CardItem> cards = new ArrayList<>();
                for (MissedGroup g : data.missed) {
                    final MissedGroup group = g;
                    SreebCodecs.UpNextItem first = g.episodes.get(0);
                    String sub = first.sub + (g.episodes.size() > 1
                            ? "  ·  +" + (g.episodes.size() - 1) + " more" : "");
                    cards.add(new CardItem(g.title, sub, g.poster, "",
                            () -> showMissedDialog(act, group, markWatched)));
                }
                root.addView(buildSection(act, act.getString(R.string.tracker_missed), cards, posters));
            }
            if (!data.forYou.isEmpty()) {
                List<CardItem> cards = new ArrayList<>();
                for (SreebCodecs.ForYouPick p : data.forYou) {
                    final SreebCodecs.ForYouPick pick = p;
                    String vote = pick.voteAverage > 0 ? "★ " + pick.voteAverage : "";
                    final String sub = pick.reason.isEmpty() ? vote
                            : (vote.isEmpty() ? pick.reason : pick.reason + "  ·  " + vote);
                    cards.add(new CardItem(pick.title, sub, pick.poster, "",
                            () -> showInfoDialog(act, pick.title, sub, "")));
                }
                root.addView(buildSection(act, act.getString(R.string.tracker_for_you), cards, posters));
            }
        }

        TextView add = text(act, act.getString(R.string.tracker_add_show), 16, 0xFFFFFFFF, false);
        add.setFocusable(true);
        // v3.21: the Add-show button is always the last focusable child of the
        // tracker container, so it is the D-pad exit: DOWN from here must land
        // on the apps grid (which sits below the tracker scroll view).
        add.setNextFocusDownId(R.id.grid);
        add.setPadding(0, dp(act, 12), 0, dp(act, 12));
        add.setOnClickListener(v -> onAddShow.run());
        root.addView(add);
        return root;
    }

    interface MarkWatched {
        void mark(long tmdbId, int season, int episode);
    }

    private static View buildHero(final Activity act, final SreebCodecs.UpNextItem hero,
                                  PosterLoader posters, MarkWatched markWatched) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        ImageView art = new ImageView(act);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 200));
        art.setLayoutParams(artLp);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(art);
        String backdrop = SreebCodecs.posterUrl("w780", hero.backdrop);
        if (backdrop.isEmpty()) backdrop = SreebCodecs.posterUrl("w780", hero.poster);
        posters.load(backdrop, art, 1024);
        TextView title = text(act, hero.title, 26, 0xFFFFFFFF, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(title);
        String sub = hero.sub + (hero.dateIso.isEmpty() ? "" : "  ·  " + hero.dateIso);
        box.addView(text(act, sub, 14, 0xFFACB8C1, false));
        if (hero.overview != null && !hero.overview.isEmpty()) {
            TextView ov = text(act, hero.overview, 14, 0xFFD0D8E0, false);
            ov.setMaxLines(2);
            ov.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(ov);
        }
        box.setFocusable(true);
        box.setOnClickListener(v -> showItemDialog(act, hero.title, hero.sub, hero.dateIso,
                hero.overview, () -> markWatched.mark(hero.tmdbId, hero.season, hero.episode)));
        box.setPadding(0, 0, 0, dp(act, 16));
        return box;
    }

    private static View buildSection(Activity act, String header, List<CardItem> cards, PosterLoader posters) {
        LinearLayout section = new LinearLayout(act);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView h = text(act, header, 18, 0xFFFFFFFF, true);
        h.setPadding(0, dp(act, 12), 0, dp(act, 8));
        section.addView(h);
        RecyclerView row = new RecyclerView(act);
        row.setLayoutManager(new LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false));
        row.setAdapter(new CardAdapter(act, cards, posters));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 230));
        row.setLayoutParams(lp);
        section.addView(row);
        return section;
    }

    private static final class CardAdapter extends RecyclerView.Adapter<CardAdapter.Holder> {
        private final Activity act;
        private final List<CardItem> cards;
        private final PosterLoader posters;

        CardAdapter(Activity act, List<CardItem> cards, PosterLoader posters) {
            this.act = act; this.cards = cards; this.posters = posters;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView art;
            final TextView title;
            final TextView sub;

            Holder(View itemView, ImageView art, TextView title, TextView sub) {
                super(itemView);
                this.art = art; this.title = title; this.sub = sub;
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            int w = dp(act, 120);
            card.setLayoutParams(new ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setPadding(dp(act, 4), dp(act, 4), dp(act, 4), dp(act, 4));
            ImageView art = new ImageView(act);
            art.setLayoutParams(new LinearLayout.LayoutParams(w, dp(act, 180)));
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(art);
            TextView title = text(act, "", 12, 0xFFFFFFFF, false);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(title);
            TextView sub = text(act, "", 11, 0xFFACB8C1, false);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(sub);
            card.setOnFocusChangeListener((v, focused) -> {
                float s = focused ? 1.08f : 1.0f;
                v.setScaleX(s);
                v.setScaleY(s);
            });
            return new Holder(card, art, title, sub);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            CardItem c = cards.get(position);
            h.title.setText(c.title);
            h.sub.setText(c.sub);
            h.art.setImageDrawable(null);
            String url = SreebCodecs.posterUrl("w500", c.posterPath);
            if (!url.isEmpty()) posters.load(url, h.art, 512);
            h.itemView.setOnClickListener(v -> {
                if (c.onClick != null) c.onClick.run();
            });
        }

        @Override
        public int getItemCount() {
            return cards.size();
        }
    }

    private static void showItemDialog(Activity act, String title, String sub, String date,
                                       String overview, final Runnable onMark) {
        StringBuilder msg = new StringBuilder(sub);
        if (!date.isEmpty()) msg.append("  ·  ").append(date);
        if (overview != null && !overview.isEmpty()) msg.append("\n\n").append(overview);
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg.toString())
                .setPositiveButton(R.string.tracker_mark_watched, (d, w) -> onMark.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showMissedDialog(Activity act, MissedGroup group, MarkWatched markWatched) {
        StringBuilder msg = new StringBuilder();
        for (SreebCodecs.UpNextItem e : group.episodes) {
            if (msg.length() > 0) msg.append('\n');
            msg.append(e.sub);
            if (!e.dateIso.isEmpty()) msg.append("  ·  ").append(e.dateIso);
        }
        SreebCodecs.UpNextItem first = group.episodes.get(0);
        new AlertDialog.Builder(act)
                .setTitle(group.title)
                .setMessage(msg.toString())
                .setPositiveButton(R.string.tracker_mark_oldest, (d, w) ->
                        markWatched.mark(first.tmdbId, first.season, first.episode))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showInfoDialog(Activity act, String title, String sub, String overview) {
        StringBuilder msg = new StringBuilder(sub);
        if (overview != null && !overview.isEmpty()) {
            if (msg.length() > 0) msg.append("\n\n");
            msg.append(overview);
        }
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg.toString())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Add-a-show flow: search TMDB by name, pick a hit, add it to the shared store. */
    static void showAddDialog(final Activity act, final SreebPrefs prefs,
                              final TrackerStore store, final Runnable onChanged) {
        final EditText input = new EditText(act);
        input.setHint(R.string.tracker_search_hint);
        input.setTextColor(0xFF111111);
        new AlertDialog.Builder(act)
                .setTitle(R.string.tracker_add_show)
                .setView(input)
                .setPositiveButton(R.string.tracker_search, (d, w) -> {
                    String q = input.getText().toString().trim();
                    if (q.isEmpty()) return;
                    runSearch(act, prefs, store, q, onChanged);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void runSearch(final Activity act, final SreebPrefs prefs,
                                  final TrackerStore store, final String query,
                                  final Runnable onChanged) {
        final String key = prefs.tmdbKey();
        if (key == null || key.trim().isEmpty()) {
            Toast.makeText(act, R.string.tracker_no_key, Toast.LENGTH_LONG).show();
            return;
        }
        Thread t = new Thread(() -> {
            List<TmdbClient.SearchHit> hits;
            try {
                String json = TmdbClient.get("search/multi?query="
                        + java.net.URLEncoder.encode(query, "UTF-8"), key);
                hits = json == null ? new ArrayList<>() : TmdbClient.parseSearch(json);
            } catch (Exception e) {
                hits = new ArrayList<>();
            }
            final List<TmdbClient.SearchHit> found = hits;
            act.runOnUiThread(() -> {
                if (found.isEmpty()) {
                    Toast.makeText(act, R.string.tracker_no_results, Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] labels = new String[found.size()];
                for (int i = 0; i < found.size(); i++) {
                    labels[i] = found.get(i).title + " (" + found.get(i).type + ")";
                }
                new AlertDialog.Builder(act)
                        .setTitle(R.string.tracker_pick_title)
                        .setItems(labels, (d, which) -> {
                            TmdbClient.SearchHit hit = found.get(which);
                            addTitle(act, store, hit, onChanged);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }, "fork-search");
        t.setDaemon(true);
        t.start();
    }

    private static void addTitle(final Activity act, final TrackerStore store,
                                 final TmdbClient.SearchHit hit, final Runnable onChanged) {
        Thread t = new Thread(() -> {
            try {
                List<SreebCodecs.Title> titles = store.readTitles();
                for (SreebCodecs.Title existing : titles) {
                    if (existing.tmdbId == hit.id && existing.type.equals(hit.type)) {
                        act.runOnUiThread(() -> Toast.makeText(act,
                                R.string.tracker_already_tracked, Toast.LENGTH_SHORT).show());
                        return;
                    }
                }
                titles.add(new SreebCodecs.Title(hit.type, hit.id, hit.title, hit.poster,
                        hit.backdrop, 0, false, false, 0, 0));
                if (!store.writeTitles(titles)) return;
                if ("tv".equals(hit.type)) {
                    store.writeEpisode(hit.id, new SreebCodecs.EpisodeFile(false,
                            new java.util.HashSet<>()));
                }
                store.rewriteIndex();
                if ("tv".equals(hit.type)) {
                    TrackerRefresh.refreshShow(act, hit.id, () ->
                            act.runOnUiThread(onChanged));
                } else {
                    act.runOnUiThread(onChanged);
                }
            } catch (Exception e) {
                act.runOnUiThread(() -> Toast.makeText(act,
                        R.string.tracker_add_failed, Toast.LENGTH_SHORT).show());
            }
        }, "fork-add-title");
        t.setDaemon(true);
        t.start();
    }
}
