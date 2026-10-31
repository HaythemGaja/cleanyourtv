# CleanYourTV fork + Sreeb features (v3.20)

Private fork of the CleanYourTV home screen (Gathin23/cleanyourtv, MIT — see LICENSE),
built to install over Sreeb as a normal update. The CleanYourTV code is the base;
Sreeb's code was adapted to fit it, not the reverse.

## Update identity (why this installs over Sreeb)

- `applicationId com.mizan.launcher` (gradle.properties `launcherApplicationId`).
  The Java code package stays `com.gathin.tvhome` internally.
- Signed with the existing Sreeb key (keystore outside the repo, passwords from
  environment variables only — never in any file here).
- versionCode 43 / versionName 3.20, above Sreeb v3.19 (vc42).
- Same package + same key = the same app data directory, so the fork reads
  Sreeb's existing data directly: SharedPreferences file `mizan_launcher` and
  `filesDir/tracker/` (titles.jsonl, upnext/missed/foryou/continue/movies lines,
  per-show episode files, index.json). No new store, no re-entry of the TMDB key.

## What was added (all Java, in `app/src/main/java/com/gathin/tvhome/`)

- **Tracker home sections** (`TrackerUi`, `TrackerStore`, `SreebCodecs`,
  `TmdbClient`, `PosterLoader`, `TrackerRefresh`): Hero (first Up Next item,
  backdrop art) → Up Next → Missed (grouped per show) → For You → the fork's
  normal app grid. Posters are 2:3 portrait, never centre-cropped (FIT_CENTER in
  a fixed 2:3 box); hero backdrops are CENTER_CROP landscape art. Poster disk
  cache under `filesDir/posters/` (SHA-256 of URL@maxDim, 200 files / 50 MB) —
  a fork addition; Sreeb's own cache is memory-only. Empty library shows a hint,
  empty sections are hidden, every section fails open. Mark-watched from a card
  dialog writes the shared store (Sreeb's missing-episode-file rule preserved)
  and refreshes that show. "＋ Add a show or movie" searches TMDB and adds to
  the shared store. Hero is a deliberate simplification: Sreeb rotates hero
  candidates with a stored index; the fork shows the first Up Next item, falling
  back to the first For You pick, then hides.
- **App lock** (`ArrowLock`, `ArrowDialog`, gate in `MainActivity`, hook in
  `AppAdapter`): app-tile launches and top-bar app buttons pass through Sreeb's
  arrow-code gate. Code + locked-app membership come from Sreeb's prefs; the
  code is never written anywhere else. A locked app with no code opens the
  set-pattern flow first (fail-open, never locks the user out). Unlock grace
  window honored. The fork has no folder UI (the base has no folder concept),
  so folder-open gating is dormant: the remote `setFolderLock` flag is stored,
  reported in status, and honored by the protocol, but there is no folder open
  to gate until folders are added (out of scope).
- **Phone remote** (`ForkRemoteProtocol`, `ForkRemoteServer`, settings row in
  `LauncherSettingsActivity`): the Sreeb v3.19 listener, same protocol
  (port 47819, pairing-code auth, 5 wrong codes → 60 s lockout, commands
  status / setStyle / setTheme / setAppLock / setFolderLock / setArrowCode /
  refresh). OFF by default. The existing pairing code and flags are read from
  Sreeb's prefs, so the shipped Sreeb Remote phone app keeps working with no
  re-pairing. Two fork adaptations (the fork has one home and no Sreeb theme
  presets): status always reports style `clean`; setStyle accepts only `clean`
  (tv/watch refused with `bad_style`); setTheme accepts only `default`,
  `glass`, `cinematic` (applied as Config overrides after Config.load).
  LAN-only bind (first site-local IPv4, never 0.0.0.0), no foreground service
  (daemon thread, re-armed on Home create while enabled).

## What was NOT ported (out of scope, data left untouched)

Sreeb's IPTV Live tab, profiles system, Watch tabs, and status-bar pills.
Profile note: the fork reads `current_profile` only to pick the `p<N>.`
prefix for locked apps / arrow grace; it does not parse Sreeb's `profile_set`
encoding, and the tracker store itself is shared across profiles in Sreeb.

## The one added permission

`INTERNET` (posters + TMDB refresh; upstream has none). Everything else in the
manifest is untouched, including the theme provider and services.

## Theme system (kept intact)

Config's config.json overlay over built-in config.xml, the theme provider
(authority `${applicationId}.theme`), and the theme directory logic are
untouched. Caveat: `tools/theme.sh` in the home repo hardcodes
`PKG=com.gathin.tvhome`, and the CleanYourTV phone app addresses the launcher
as `com.gathin.tvhome`; with this fork as `com.mizan.launcher`, pushes
addressed to `com.gathin.tvhome` will NOT land on the fork. The push tool must
target `com.mizan.launcher`. On-device verification of phone-app theme pushes
against the fork is still needed — do not claim they work until that passes.

## Files added / changed in this fork

Added (Java): MiniJson, SreebCodecs, ArrowLock, ForkRemoteProtocol,
SreebPrefs, ForkState, TrackerStore, TmdbClient, PosterLoader,
TrackerRefresh, ForkRemoteServer, ArrowDialog, TrackerUi.
Changed (Java): MainActivity (tracker container above the grid, remote-theme
override, lock gate, remote server lifecycle), AppAdapter (LaunchGate hook),
LauncherSettingsActivity (Phone remote row handler).
Changed (resources/config): activity_launcher_settings.xml (phoneRemoteChoice
row), strings.xml (app_name → Sreeb + fork strings), AndroidManifest.xml
(INTERNET only), gradle.properties (launcherApplicationId), app/build.gradle
(versionCode 43 / 3.20, Sreeb signing from env, minify/shrink off).
Unchanged: the base layout (activity_main.xml untouched — the tracker
container is inserted programmatically), the theme system, dock, app handling,
wallpaper handling. LICENSE kept from the repo root.

## Sreeb prefs keys + store paths relied on

Prefs file `mizan_launcher`: `current_profile`, `p<N>.locked`,
`p<N>.unlock_grace_min`, `last_unlock_at`, `arrow_pin`,
`launcher_tmdb_key`, `phone_remote_enabled`, `phone_remote_code`,
`phone_remote_applock`, `phone_remote_folderlock`, `phone_remote_refresh`,
`phone_remote_failures`, `phone_remote_lockout`.
Store `filesDir/tracker/`: titles.jsonl, upnext.txt, continue.txt,
movies.txt, foryou.txt, missed.txt, missed.json, index.json,
episodes/tv_<tmdbId>.json. Fork-owned prefs: `clean_fork_state`
(lastRefreshMs, remoteTheme). Fork disk cache: `filesDir/posters/`.
