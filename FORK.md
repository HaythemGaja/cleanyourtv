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

## v3.21 — scrollable home fix (on-device bug from v3.20)

v3.20 put the tracker container bare into the home column above the
grid wrapper. The column is a vertical LinearLayout and the grid
wrapper is weight=1/height=0, so a tall tracker container left the
grid ZERO height: no tiles visible, nothing scrollable, and no tile
click ever reached the lock gate (the gate itself was wired fine).
v3.21 wraps the tracker container in a NestedScrollView that itself
carries weight=1/height=0, so the column's leftover height is shared
equally: the grid always keeps half, with its own RecyclerView
scrolling, and the tracker rows scroll inside their half. D-pad DOWN
from the tracker exits through the Add-show button (always the last
tracker child, nextFocusDownId=grid); UP from the first grid row uses
default focus search back to the nearest tracker child. No layout,
theme, remote, permission, or lock-logic changes; tracker rows stay
on the home under every theme by design. Folders are still out of
scope (the base has no folder UI).

## v3.22 — folders + on-TV lock setup (+ Sreeb icon)

Folders are new UI on the fork base (the base has no folder concept):
folder definitions live in the fork-owned prefs file `clean_fork_state`
(key `folders`, `FolderCodec` pipe encoding — one folder per line:
id | name | locked 0/1 | member packages; malformed lines drop,
the rest survive). A folder tile shows the folder name, an app count,
and a collage of up to four member icons. Foldered apps never appear
as loose tiles on the main grid (no duplicates); the all-apps/library
picker (`AppLibraryDialog`) is unchanged, so members still appear
there. Opening a folder tile shows its members in a dialog grid;
BACK closes it and refocuses the folder tile. Hold OK on a tile gives
"Add to folder" (existing folder or "New folder" with the default
name "Folder N", smallest unused; rename is implemented via an
EditText dialog from the folder tile menu). Inside the folder view,
long-press a member to remove it (it returns to the main grid);
the folder tile menu offers Delete (members return to the grid;
an empty folder still opens with an empty hint and the delete
option). Foldered apps are never docked (dock matching only accepts
real apps). Stale members (uninstalled apps) are pruned on open and
the prune is persisted.

Folder lock gating wires the dormant `ArrowLock.folderGate` live:
locked + code set prompts for the code; locked + no code runs the
set-pattern flow first and then REQUIRES the new code (unlike the
app gate's setup-then-open — a folder with no code is a setup
accident, not a legacy lock); unlocked or remote folder-lock master
off opens; the shared unlock-grace window is honored (no second
grace invented). Folder lock toggles live in the folder tile menu.

On-TV PIN lock setup: the tile menu gains "Lock app" / "Unlock app"
(toggling the Sreeb prefs `p<N>.locked` set) and Settings gains a
"Lock & PIN" row (set/change code — changing requires the current
code first, per Sreeb's pattern; clear requires it too; locked-apps
count shown; the code itself is never displayed). Locking an app
while no code is set forces the set-pattern flow first, then applies
the lock — never a locked app with no code. This is a DELIBERATE
new write into Sreeb prefs (v3.20 deliberately never wrote
lockedApps): the fork now writes ONLY the locked set and the arrow
code there, nothing else, so locks stay consistent if the user ever
returns to Sreeb. Lock code format (UDLR 4–8), grace keys, remote
master flags, and remote setArrowCode behavior are unchanged; the
remote's setAppLock/setFolderLock toggles keep working (master off
suspends gating for apps/folders respectively). The app gate's
locked+no-code behavior is UNCHANGED: setup-then-open, fail-open.

Icon (applied to the tree before this build, verified in the APK):
the upstream CleanYourTV wavy mark is replaced by the Sreeb house
mark — bold red #E50914 geometric S on near-black #141414, as
vectors copied from the Sreeb launcher (mipmap-anydpi-v26
adaptive icon + drawable ic_launcher/background/foreground +
banner.xml TV banner). The wavy jpg and the upstream banner jpg
are deleted with no dangling references; manifest icon/banner
lines are unchanged; minSdk 26, so the adaptive icon covers all
supported devices. The magenta Live TV tile banner (banner_tv.xml)
is not the app banner and was left alone.

No new permissions, no new services, no theme-system or remote
changes, and the v3.21 scroll/focus behavior is untouched (tracker
half + grid half, Add-show button nextFocusDownId=grid).
