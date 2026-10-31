package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless checks for the fork's pure-Java pieces (codecs, section
 * computation, lock machine, remote protocol, shared-store IO).
 * Runs on synthetic data in a temp dir — never the user's real data.
 * Usage: HarnessMain <path-to-sample-theme-config.json>
 */
public final class HarnessMain {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static void checkEq(String name, Object want, Object got) {
        check(name + " [want=" + want + " got=" + got + "]",
                want == null ? got == null : want.equals(got));
    }

    public static void main(String[] args) throws Exception {
        codecs();
        fingerprints();
        sections();
        lock();
        protocol();
        store();
        theme(args.length > 0 ? args[0] : null);
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ---------------- codecs ----------------

    private static void codecs() {
        SreebCodecs.Title t = new SreebCodecs.Title("tv", 123L, "Show | With % Pipe\nAnd Newline ☃",
                "/p.jpg", "/b.jpg", 3, false, true, 2, 5);
        SreebCodecs.Title back = SreebCodecs.decodeTitle(SreebCodecs.encodeTitle(t));
        check("title round-trip", back != null && back.title.equals(t.title)
                && back.tmdbId == 123L && back.muted && back.nextSeason == 2 && back.nextEpisode == 5);

        SreebCodecs.UpNextItem u = new SreebCodecs.UpNextItem(7L, "Up|Next", "S1 E2",
                "2026-10-01", "/p.jpg", "/b.jpg", "Overview % here", 1, 2, "tv");
        SreebCodecs.UpNextItem ub = SreebCodecs.decodeUpNext(SreebCodecs.encodeUpNext(u));
        check("upnext round-trip", ub != null && ub.title.equals("Up|Next")
                && ub.season == 1 && ub.episode == 2 && ub.overview.equals("Overview % here"));

        SreebCodecs.ForYouPick f = new SreebCodecs.ForYouPick(9L, "tv", "Pick", "/p.jpg",
                8.5, "Because you watched X", Arrays.asList("Drama", "Sci-Fi"), 12.25);
        SreebCodecs.ForYouPick fb = SreebCodecs.decodeForYou(SreebCodecs.encodeForYou(f));
        check("foryou round-trip", fb != null && fb.title.equals("Pick")
                && fb.voteAverage == 8.5 && fb.whyGenres.size() == 2 && fb.score == 12.25);

        SreebCodecs.ContinueItem c = new SreebCodecs.ContinueItem(5L, "Cont", "S2 E3",
                4, 10, "/p.jpg", "/b.jpg", "Ov", "tv");
        SreebCodecs.ContinueItem cb = SreebCodecs.decodeContinue(SreebCodecs.encodeContinue(c));
        check("continue round-trip", cb != null && cb.watched == 4 && cb.total == 10);

        SreebCodecs.MovieItem m = new SreebCodecs.MovieItem(11L, "Mov", "2026-01-01",
                false, "/p.jpg", true);
        SreebCodecs.MovieItem mb = SreebCodecs.decodeMovie(SreebCodecs.encodeMovie(m));
        check("movie round-trip", mb != null && mb.title.equals("Mov") && mb.watched);

        Set<String> watched = new HashSet<>(Arrays.asList("1x1", "1x2"));
        SreebCodecs.EpisodeFile e = new SreebCodecs.EpisodeFile(false, watched);
        SreebCodecs.EpisodeFile eb = SreebCodecs.decodeEpisode(SreebCodecs.encodeEpisode(e));
        check("episode json round-trip", eb != null && eb.watched.size() == 2
                && eb.watched.contains("1x1"));

        SreebCodecs.Index idx = new SreebCodecs.Index(3, 2, 1, 0, 4,
                1700000000000L, 1699999999999L, "abcdef0123456789", 2);
        SreebCodecs.Index ib = SreebCodecs.decodeIndex(SreebCodecs.encodeIndex(idx));
        check("index json round-trip", ib != null && ib.titles == 3 && ib.foryou == 4
                && "abcdef0123456789".equals(ib.fingerprint) && ib.episodeFiles == 2);

        check("malformed title id 0 -> null",
                SreebCodecs.decodeTitle("tv|0|Bad|/p|/b|0|0|0|0|0") == null);
        check("malformed title blank name -> null",
                SreebCodecs.decodeTitle("tv|5|   |/p|/b|0|0|0|0|0") == null);
        check("malformed title garbage -> null",
                SreebCodecs.decodeTitle("garbage") == null);
        check("malformed upnext empty -> null",
                SreebCodecs.decodeUpNext("") == null);
        check("malformed episode json -> null",
                SreebCodecs.decodeEpisode("{not json") == null);
        check("malformed index json -> null",
                SreebCodecs.decodeIndex("{\"titles\":1}") == null);

        checkEq("poster url tmdb path", "https://image.tmdb.org/t/p/w500/abc.jpg",
                SreebCodecs.posterUrl("w500", "/abc.jpg"));
        checkEq("poster url full passthrough", "https://example.com/x.jpg",
                SreebCodecs.posterUrl("w500", "https://example.com/x.jpg"));
        checkEq("poster url blank", "", SreebCodecs.posterUrl("w500", ""));
    }

    // ---------------- fingerprints ----------------

    private static void fingerprints() {
        // Kotlin Long.toString(16) renders negatives as '-' + magnitude hex, and padStart
        // pads LEFT of the minus sign. -123456789 -> "-75bcd15" -> "00000000-75bcd15".
        checkEq("kotlinHex negative", "00000000-75bcd15", SreebCodecs.kotlinHex(-123456789L));
        checkEq("kotlinHex positive", "00000000000000ff", SreebCodecs.kotlinHex(255L));
        checkEq("kotlinHex zero", "0000000000000000", SreebCodecs.kotlinHex(0L));

        List<SreebCodecs.Title> titles = Arrays.asList(
                new SreebCodecs.Title("tv", 1L, "A", "/p", "/b", 2, false, false, 1, 3),
                new SreebCodecs.Title("tv", 2L, "B", "/p", "/b", 0, true, false, 0, 0));
        String fp1 = SreebCodecs.fingerprint(1000L, titles, new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        String fp2 = SreebCodecs.fingerprint(1000L, titles, new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        check("fingerprint deterministic", fp1.equals(fp2) && fp1.length() >= 16
                && fp1.matches("[0-9a-f-]+"));
        // Note: length is 17 when the hash is negative with a 16-digit magnitude
        // (Kotlin's '-' + magnitude hex, padStart never truncates) — that is faithful.
        List<SreebCodecs.Title> changed = Arrays.asList(
                new SreebCodecs.Title("tv", 1L, "A", "/p", "/b", 3, false, false, 1, 4),
                new SreebCodecs.Title("tv", 2L, "B", "/p", "/b", 0, true, false, 0, 0));
        check("fingerprint changes with content",
                !fp1.equals(SreebCodecs.fingerprint(1000L, changed, new ArrayList<>(),
                        new ArrayList<>(), new ArrayList<>(), new ArrayList<>())));
        // fetchedAt is NOT a fingerprint input: same content, different fetchedAt -> same fp.
        SreebCodecs.Index a = new SreebCodecs.Index(1, 0, 0, 0, 0, 5L, 111L, fp1, 0);
        SreebCodecs.Index b = new SreebCodecs.Index(1, 0, 0, 0, 0, 5L, 222L, fp1, 0);
        check("fingerprint ignores fetchedAt", a.fingerprint.equals(b.fingerprint));
        check("missed fingerprint deterministic",
                SreebCodecs.missedFingerprint(titles).equals(SreebCodecs.missedFingerprint(titles)));
    }

    // ---------------- sections ----------------

    private static List<SreebCodecs.SeasonEps> seasons() {
        List<SreebCodecs.SeasonEps> out = new ArrayList<>();
        out.add(new SreebCodecs.SeasonEps(2, Arrays.asList(
                new SreebCodecs.EpAir(1, "2026-10-05"),
                new SreebCodecs.EpAir(2, "2026-10-12"))));
        out.add(new SreebCodecs.SeasonEps(1, Arrays.asList(
                new SreebCodecs.EpAir(1, "2026-09-01"),
                new SreebCodecs.EpAir(2, "2026-09-08"))));
        out.add(new SreebCodecs.SeasonEps(0, Arrays.asList(
                new SreebCodecs.EpAir(1, "2026-01-01"))));
        return out;
    }

    private static void sections() {
        Set<String> w = new HashSet<>(Arrays.asList("1x1"));
        String[] next = SreebCodecs.nextEpisode(w, seasons());
        check("nextEpisode skips watched", next != null && next[0].equals("1") && next[1].equals("2"));

        Set<String> wFull1 = new HashSet<>(Arrays.asList("1x1", "1x2"));
        String[] next2 = SreebCodecs.nextEpisode(wFull1, seasons());
        check("nextEpisode season boundary", next2 != null && next2[0].equals("2") && next2[1].equals("1"));

        Set<String> all = new HashSet<>(Arrays.asList("1x1", "1x2", "2x1", "2x2"));
        check("nextEpisode caught-up -> null", SreebCodecs.nextEpisode(all, seasons()) == null);

        List<SreebCodecs.SeasonEps> onlyZero = Arrays.asList(
                new SreebCodecs.SeasonEps(0, Arrays.asList(new SreebCodecs.EpAir(0, "2026-01-01"))));
        check("nextEpisode skips season 0 / ep 0",
                SreebCodecs.nextEpisode(new HashSet<>(), onlyZero) == null);

        // Missed: today = 2026-10-10. 2026-09-01/09-08 passed, 2026-10-05 passed,
        // 2026-10-12 future, 2026-10-10 itself is NOT missed (strictly before today).
        List<SreebCodecs.SeasonEps> missedSeasons = Arrays.asList(
                new SreebCodecs.SeasonEps(1, Arrays.asList(
                        new SreebCodecs.EpAir(1, "2026-09-01"),
                        new SreebCodecs.EpAir(2, "2026-10-10"),
                        new SreebCodecs.EpAir(3, "not-a-date"),
                        new SreebCodecs.EpAir(4, "2026-10-12"))));
        List<String[]> missed = SreebCodecs.allMissedEpisodes(new HashSet<>(), missedSeasons, "2026-10-10");
        check("missed strict-before-today + malformed skipped",
                missed.size() == 1 && missed.get(0)[1].equals("1"));

        Set<String> wMissed = new HashSet<>(Arrays.asList("1x1"));
        check("missed excludes watched",
                SreebCodecs.allMissedEpisodes(wMissed, missedSeasons, "2026-10-10").isEmpty());
        check("missed bad today -> empty",
                SreebCodecs.allMissedEpisodes(new HashSet<>(), missedSeasons, "bogus").isEmpty());

        List<SreebCodecs.ForYouPick> picks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            picks.add(new SreebCodecs.ForYouPick(i + 1, "tv", "P" + i, "/p",
                    7.0, "", new ArrayList<>(), 1.0));
        }
        List<SreebCodecs.ForYouPick> r1 = SreebCodecs.rotateForYouPicks(picks, "2026-10-31");
        List<SreebCodecs.ForYouPick> r2 = SreebCodecs.rotateForYouPicks(picks, "2026-10-31");
        List<SreebCodecs.ForYouPick> r3 = SreebCodecs.rotateForYouPicks(picks, "2026-11-01");
        check("foryou rotation deterministic same day", ids(r1).equals(ids(r2)));
        check("foryou rotation differs across days", !ids(r1).equals(ids(r3)));
        check("foryou rotation is a permutation", new HashSet<>(ids(r1)).size() == 5);
        List<SreebCodecs.ForYouPick> one = Arrays.asList(
                new SreebCodecs.ForYouPick(1, "tv", "Only", "/p", 7.0, "", new ArrayList<>(), 1.0));
        check("foryou size 1 unchanged", SreebCodecs.rotateForYouPicks(one, "2026-10-31").size() == 1
                && SreebCodecs.rotateForYouPicks(one, "2026-10-31").get(0).title.equals("Only"));
        check("foryou bad day unchanged", ids(SreebCodecs.rotateForYouPicks(picks, "bogus")).equals(ids(picks)));
    }

    private static List<Long> ids(List<SreebCodecs.ForYouPick> picks) {
        List<Long> out = new ArrayList<>();
        for (SreebCodecs.ForYouPick p : picks) out.add(p.id);
        return out;
    }

    // ---------------- lock ----------------

    private static void lock() {
        check("codec valid 4", ArrowLock.isValidEncoded("UDLR"));
        check("codec valid 8", ArrowLock.isValidEncoded("UDLRUDLR"));
        check("codec invalid 3", !ArrowLock.isValidEncoded("UDL"));
        check("codec invalid 9", !ArrowLock.isValidEncoded("UDLRUDLRU"));
        check("codec invalid char", !ArrowLock.isValidEncoded("UDLX"));
        check("codec invalid empty", !ArrowLock.isValidEncoded(""));
        check("codec invalid null", !ArrowLock.isValidEncoded(null));
        check("legacy numeric detected", ArrowLock.isLegacyNumeric("1234"));
        check("legacy numeric 3 digits rejected", !ArrowLock.isLegacyNumeric("123"));
        check("UDLR not legacy", !ArrowLock.isLegacyNumeric("UDLR"));
        checkEq("encode UDLR", "UDLR",
                ArrowLock.encode(Arrays.asList(ArrowLock.Dir.UP, ArrowLock.Dir.DOWN,
                        ArrowLock.Dir.LEFT, ArrowLock.Dir.RIGHT)));
        check("decode round-trip", ArrowLock.decode("UDLR").size() == 4
                && ArrowLock.decode("UDLR").get(0) == ArrowLock.Dir.UP);

        ArrowLock.Entry e = new ArrowLock.Entry();
        for (int i = 0; i < 8; i++) e.append(ArrowLock.Dir.UP);
        check("entry 8th accepted", e.entered() == 8);
        check("entry 9th rejected full", e.append(ArrowLock.Dir.UP) == ArrowLock.EntryEvent.REJECTED_FULL);
        check("entry back deletes", e.pressBack() == ArrowLock.EntryEvent.CHANGED && e.entered() == 7);
        ArrowLock.Entry e2 = new ArrowLock.Entry();
        check("entry back on empty cancels", e2.pressBack() == ArrowLock.EntryEvent.CANCELLED);
        e2.append(ArrowLock.Dir.UP);
        e2.append(ArrowLock.Dir.DOWN);
        check("entry submit too short", e2.submit() == ArrowLock.EntryEvent.TOO_SHORT);
        e2.append(ArrowLock.Dir.LEFT);
        e2.append(ArrowLock.Dir.RIGHT);
        check("entry submit ok", e2.submit() == ArrowLock.EntryEvent.SUBMITTED
                && "UDLR".equals(e2.submittedSequence()));

        check("auto-submit at stored length", ArrowLock.shouldAutoSubmit(6, 6));
        check("auto-submit not before length", !ArrowLock.shouldAutoSubmit(5, 6));
        check("auto-submit stored 3 never", !ArrowLock.shouldAutoSubmit(3, 3));
        check("auto-submit stored 9 never", !ArrowLock.shouldAutoSubmit(9, 9));

        ArrowLock.SetupFlow s = new ArrowLock.SetupFlow();
        check("setup first -> need confirm", s.submit("UDLR") == ArrowLock.SetupResult.NEED_CONFIRM);
        check("setup match -> done", s.submit("UDLR") == ArrowLock.SetupResult.DONE);
        ArrowLock.SetupFlow s2 = new ArrowLock.SetupFlow();
        s2.submit("UDLR");
        check("setup mismatch -> mismatch", s2.submit("UDLU") == ArrowLock.SetupResult.MISMATCH);
        check("setup after mismatch restarts", s2.submit("UDLR") == ArrowLock.SetupResult.NEED_CONFIRM);

        check("gate app locked+enabled -> prompt",
                ArrowLock.appRequiresPrompt(true, true));
        check("gate app locked+disabled -> pass",
                !ArrowLock.appRequiresPrompt(true, false));
        check("gate app unlocked+enabled -> pass",
                !ArrowLock.appRequiresPrompt(false, true));
        check("gate folder locked+enabled -> prompt",
                ArrowLock.folderRequiresPrompt(true, true));
        check("gate folder locked+disabled -> pass",
                !ArrowLock.folderRequiresPrompt(true, false));
        check("folder gate enum locked+pattern -> prompt",
                ArrowLock.folderGate(true, true) == ArrowLock.FolderGate.PROMPT_PATTERN);
        check("folder gate enum locked no pattern -> set",
                ArrowLock.folderGate(true, false) == ArrowLock.FolderGate.PROMPT_SET_PATTERN);
        check("folder gate enum unlocked -> open",
                ArrowLock.folderGate(false, true) == ArrowLock.FolderGate.OPEN);

        long now = System.currentTimeMillis();
        check("grace within window", ArrowLock.withinGrace(60, now - 30 * 60 * 1000L, now));
        check("grace past window", !ArrowLock.withinGrace(60, now - 61 * 60 * 1000L, now));
        check("grace 0 minutes never", !ArrowLock.withinGrace(0, now, now));
        check("grace never unlocked never", !ArrowLock.withinGrace(60, 0L, now));
    }

    // ---------------- protocol ----------------

    private static final class FakeStore implements ForkRemoteProtocol.Store {
        String code;
        String style = "clean";
        String theme = "default";
        boolean appLock = true;
        boolean folderLock = true;
        String pin = "UDLRUD";
        int failures = 0;
        long lockout = 0;
        boolean refresh = false;

        @Override public String getPairingCode() { return code; }
        @Override public void setPairingCode(String v) { code = v; }
        @Override public String getStyle() { return style; }
        @Override public void setStyle(String v) { style = v; }
        @Override public String getThemeId() { return theme; }
        @Override public void setThemeId(String v) { theme = v; }
        @Override public List<ForkRemoteProtocol.ThemeOpt> getThemeOptions() {
            return ForkRemoteProtocol.THEME_OPTIONS; }
        @Override public boolean isAppLockEnabled() { return appLock; }
        @Override public void setAppLockEnabled(boolean v) { appLock = v; }
        @Override public boolean isFolderLockEnabled() { return folderLock; }
        @Override public void setFolderLockEnabled(boolean v) { folderLock = v; }
        @Override public String getArrowPin() { return pin; }
        @Override public void setArrowPin(String v) { pin = v; }
        @Override public int getLibraryTitles() { return 45; }
        @Override public boolean isRefreshPending() { return refresh; }
        @Override public void setRefreshPending(boolean v) { refresh = v; }
        @Override public int getFailureCount() { return failures; }
        @Override public void setFailureCount(int v) { failures = v; }
        @Override public long getLockoutUntilMs() { return lockout; }
        @Override public void setLockoutUntilMs(long v) { lockout = v; }
    }

    private static void protocol() {
        FakeStore s = new FakeStore();
        s.code = "123456";
        long now = 1_700_000_000_000L;

        check("protocol bad json", ForkRemoteProtocol.handle("not json", now, s).contains("bad_request"));
        check("protocol empty json", ForkRemoteProtocol.handle("", now, s).contains("bad_request"));

        FakeStore noCode = new FakeStore();
        noCode.code = null;
        check("protocol stored code missing -> bad_code", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"status\"}", now, noCode).contains("bad_code"));
        check("protocol request without pairing -> missing_code", ForkRemoteProtocol.handle(
                "{\"command\":\"status\"}", now, s).contains("missing_code"));

        for (int i = 0; i < 5; i++) {
            String r = ForkRemoteProtocol.handle(
                    "{\"pairing\":\"000000\",\"command\":\"status\"}", now, s);
            check("protocol wrong code attempt " + (i + 1), r.contains("bad_code"));
        }
        check("protocol locked out after 5", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"status\"}", now, s).contains("locked_out"));
        check("protocol lockout refuses right code", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"status\"}", now, s).contains("locked_out"));
        String after = ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"status\"}", now + 61_000L, s);
        check("protocol lockout expires", after.contains("\"ok\":true"));
        check("protocol success resets failures", s.failures == 0);

        String status = ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"status\"}", now + 61_000L, s);
        check("protocol status ok", status.contains("\"ok\":true"));
        check("protocol status style clean", status.contains("\"clean\""));
        check("protocol status theme options", status.contains("glass") && status.contains("cinematic"));
        check("protocol status hides arrow code", !status.contains("UDLRUD"));
        check("protocol status hides tmdb key", !status.toLowerCase().contains("tmdb"));

        check("protocol setStyle clean ok", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"setStyle\",\"style\":\"clean\"}",
                now + 61_000L, s).contains("\"ok\":true"));
        check("protocol setStyle tv refused", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"setStyle\",\"style\":\"tv\"}",
                now + 61_000L, s).contains("bad_style"));
        check("protocol setStyle watch refused", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"setStyle\",\"style\":\"watch\"}",
                now + 61_000L, s).contains("bad_style"));
        check("protocol setStyle bogus refused", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"setStyle\",\"style\":\"ipad\"}",
                now + 61_000L, s).contains("bad_style"));

        check("protocol setTheme glass ok", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"setTheme\",\"themeId\":\"glass\"}",
                now + 61_000L, s).contains("\"ok\":true") && s.theme.equals("glass"));
        check("protocol setTheme bogus refused", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"setTheme\",\"themeId\":\"roku_purple\"}",
                now + 61_000L, s).contains("bad_theme") && s.theme.equals("glass"));

        check("protocol setAppLock false ok", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"setAppLock\",\"enabled\":false}",
                now + 61_000L, s).contains("\"ok\":true") && !s.appLock);
        check("protocol setAppLock string refused", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"setAppLock\",\"enabled\":\"yes\"}",
                now + 61_000L, s).contains("bad_value") && !s.appLock);
        check("protocol setFolderLock ok", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"setFolderLock\",\"enabled\":true}",
                now + 61_000L, s).contains("\"ok\":true") && s.folderLock);

        check("protocol setArrowCode valid ok", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"setArrowCode\",\"arrowCode\":\"UDLU\"}",
                now + 61_000L, s).contains("\"ok\":true") && s.pin.equals("UDLU"));
        check("protocol setArrowCode invalid refused", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"setArrowCode\",\"arrowCode\":\"1234\"}",
                now + 61_000L, s).contains("bad_code_value") && s.pin.equals("UDLU"));

        check("protocol refresh ok", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"refresh\"}",
                now + 61_000L, s).contains("\"ok\":true") && s.refresh);
        check("protocol unknown command", ForkRemoteProtocol.handle(
                "{\"pairing\":\"123456\",\"command\":\"explode\"}",
                now + 61_000L, s).contains("unknown_command"));
    }

    // ---------------- store IO on temp dirs ----------------

    private static void store() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"),
                "fork-harness-" + System.currentTimeMillis());
        new File(tmp, "tracker").mkdirs();
        TrackerStore st = new TrackerStore(tmp);

        List<SreebCodecs.Title> titles = Arrays.asList(
                new SreebCodecs.Title("tv", 1L, "Show A", "/p", "/b", 2, false, false, 1, 3),
                new SreebCodecs.Title("tv", 2L, "Show B", "/p", "/b", 0, false, false, 0, 0),
                new SreebCodecs.Title("movie", 3L, "Film", "/p", "/b", 1, false, false, 0, 0));
        check("store write titles", st.writeTitles(titles));
        List<SreebCodecs.Title> rt = st.readTitles();
        check("store read titles", rt.size() == 3 && rt.get(0).title.equals("Show A"));

        List<SreebCodecs.UpNextItem> up = Arrays.asList(
                new SreebCodecs.UpNextItem(1L, "Show A", "S1 E3", "2026-10-01",
                        "/p", "/b", "Ov", 1, 3, "tv"));
        check("store write upnext", st.writeUpNext(up));
        check("store read upnext", st.readUpNext().size() == 1);

        List<SreebCodecs.UpNextItem> missed = Arrays.asList(
                new SreebCodecs.UpNextItem(1L, "Show A", "S1 E2", "2026-09-01", "/p", "/b", "", 1, 2, "tv"),
                new SreebCodecs.UpNextItem(1L, "Show A", "S1 E1", "2026-08-25", "/p", "/b", "", 1, 1, "tv"),
                new SreebCodecs.UpNextItem(2L, "Show B", "S1 E1", "2026-08-20", "/p", "/b", "", 1, 1, "tv"));
        check("store write missed", st.writeMissed(missed));
        check("store read missed", st.readMissed().size() == 3);
        check("store missed meta written", st.writeMissedMeta(titles, missed)
                && new File(tmp, "tracker/missed.json").isFile());

        check("store read foryou empty on fresh store", st.readForYou().isEmpty());
        check("store rewrite index", st.rewriteIndex());
        SreebCodecs.Index idx = st.readIndex();
        check("store index counts match files", idx != null && idx.titles == 3 && idx.upnext == 1
                && idx.foryou == 0);

        // markWatched: episode file exists with 1x1,1x2 watched; marking 1x3 adds it + bumps count.
        Set<String> w = new HashSet<>(Arrays.asList("1x1", "1x2"));
        check("store write episode", st.writeEpisode(1L, new SreebCodecs.EpisodeFile(false, w)));
        check("store mark watched", st.markWatched(1L, 1, 3));
        SreebCodecs.EpisodeFile after = st.readEpisode(1L);
        check("store mark watched episode file", after != null && after.watched.contains("1x3"));
        SreebCodecs.Title t1 = null;
        for (SreebCodecs.Title t : st.readTitles()) if (t.tmdbId == 1L) t1 = t;
        check("store mark watched title count", t1 != null && t1.watchedCount == 3);

        // Missing episode file + watchedCount > 0 -> write nothing, return false.
        // (Show B has count 0, so it would NOT trip the guard — use a fresh count-2 title.)
        List<SreebCodecs.Title> titlesGuard = new ArrayList<>(st.readTitles());
        titlesGuard.add(new SreebCodecs.Title("tv", 5L, "Guarded", "/p", "/b", 2, false, false, 0, 0));
        check("store add guarded title", st.writeTitles(titlesGuard));
        check("store mark watched missing file guarded", !st.markWatched(5L, 1, 1));
        check("store mark watched guarded leaves titles",
                st.readTitles().size() == 4);

        // Missing episode file + watchedCount == 0 -> allowed (creates the file).
        List<SreebCodecs.Title> titles4 = new ArrayList<>(st.readTitles());
        titles4.add(new SreebCodecs.Title("tv", 4L, "Fresh", "/p", "/b", 0, false, false, 0, 0));
        check("store add fresh title", st.writeTitles(titles4));
        check("store mark watched fresh file", st.markWatched(4L, 1, 1));
        SreebCodecs.EpisodeFile fresh = st.readEpisode(4L);
        check("store fresh episode file", fresh != null && fresh.watched.contains("1x1"));
        deleteRecursively(tmp);
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        f.delete();
    }

    // ---------------- theme system ----------------

    private static void theme(String samplePath) throws Exception {
        if (samplePath == null) {
            check("theme sample path given", false);
            return;
        }
        String json = new String(java.nio.file.Files.readAllBytes(new File(samplePath).toPath()), "UTF-8");
        Map<String, Object> cfg = MiniJson.asObj(MiniJson.parse(json));
        check("theme config parses", cfg != null);
        check("theme config has background_color",
                cfg != null && MiniJson.asStr(cfg.get("background_color")) != null);
        check("theme config has dock_apps",
                cfg != null && MiniJson.asArr(cfg.get("dock_apps")) != null);
        check("theme config has columns",
                cfg != null && MiniJson.asNum(cfg.get("columns")) != null);
    }
}
