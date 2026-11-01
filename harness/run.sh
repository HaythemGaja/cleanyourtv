#!/bin/sh
# Headless harness for the fork's pure-Java pieces. Compiled classes go to /tmp,
# never into the fork tree. Run from anywhere; paths resolve from this script.
set -e
FORK_DIR=$(cd "$(dirname "$0")/.." && pwd)
SRC="$FORK_DIR/app/src/main/java/com/gathin/tvhome"
JDK="$HOME/workspace/.tools/jdk-17.0.11+9"
OUT="/tmp/clean-fork-harness-classes"
rm -rf "$OUT"
mkdir -p "$OUT"
"$JDK/bin/javac" -encoding UTF-8 -d "$OUT" \
    "$FORK_DIR/harness/stubs/android/content/Context.java" \
    "$FORK_DIR/harness/stubs/android/content/SharedPreferences.java" \
    "$SRC/MiniJson.java" \
    "$SRC/SreebCodecs.java" \
    "$SRC/ArrowLock.java" \
    "$SRC/ForkRemoteProtocol.java" \
    "$SRC/TrackerStore.java" \
    "$SRC/TrackerLayout.java" \
    "$SRC/FolderCodec.java" \
    "$SRC/SreebPrefs.java" \
    "$SRC/ForkState.java" \
    "$FORK_DIR/harness/HarnessMain.java"
"$JDK/bin/java" -cp "$OUT" com.gathin.tvhome.HarnessMain \
    "$HOME/workspace/cleanyourtv-home/themes/default/config.json"
echo "--- hygiene (shell checks) ---"
echo -n "no keystore/jks/pem FILES in fork tree: "
if find "$FORK_DIR" -name "*.keystore" -o -name "*.jks" -o -name "*.pem" | grep -q .; then
    echo "FAIL"; exit 1
else
    echo "PASS"
fi
echo -n "signing passwords come from env only: "
grep -q "System.getenv('SREEB_STORE_PASSWORD')" "$FORK_DIR/app/build.gradle" \
  && grep -q "System.getenv('SREEB_KEY_PASSWORD')" "$FORK_DIR/app/build.gradle" && echo "PASS"
echo -n "manifest has INTERNET: "
grep -q 'android.permission.INTERNET' "$FORK_DIR/app/src/main/AndroidManifest.xml" && echo "PASS"
echo -n "theme overlay intact (Config FILE_NAME config.json): "
grep -q 'FILE_NAME = "config.json"' "$SRC/Config.java" && echo "PASS"
echo -n "ThemeProvider still in manifest: "
grep -q 'ThemeProvider' "$FORK_DIR/app/src/main/AndroidManifest.xml" && echo "PASS"
echo "--- v3.21 scroll-fix wiring pins (source checks) ---"
echo -n "tracker wrapped in NestedScrollView with weight split: "
grep -q 'new NestedScrollView(this)' "$SRC/MainActivity.java" \
  && grep -q 'LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f' "$SRC/MainActivity.java" && echo "PASS"
echo -n "tracker scroll view added above grid wrapper, old container removed: "
grep -q 'column.addView(scroll, at)' "$SRC/MainActivity.java" \
  && grep -q 'column.removeView(forkTrackerContainer)' "$SRC/MainActivity.java" && echo "PASS"
echo -n "add button carries nextFocusDownId=grid (D-pad exit): "
grep -q 'setNextFocusDownId(R.id.grid)' "$SRC/TrackerUi.java" && echo "PASS"
echo -n "lock gate wiring intact (setLaunchGate + adapter gate): "
grep -q 'adapter.setLaunchGate(this::gateLaunch)' "$SRC/MainActivity.java" \
  && grep -q 'launchGate.gate(e.pkg' "$SRC/AppAdapter.java" && echo "PASS"
echo -n "no new permissions/services vs upstream (only v3.20 INTERNET): "
UPSTREAM_MAN="$HOME/workspace/cleanyourtv-home/launcher/app/src/main/AndroidManifest.xml"
FORK_MAN="$FORK_DIR/app/src/main/AndroidManifest.xml"
if [ -f "$UPSTREAM_MAN" ]; then
  UPERMS=$(grep -o 'android:name="android.permission.[A-Z_]*"' "$UPSTREAM_MAN" | sort -u)
  FPERMS=$(grep -o 'android:name="android.permission.[A-Z_]*"' "$FORK_MAN" | sort -u)
  EXPECTED=$(printf '%s\nandroid:name="android.permission.INTERNET"' "$UPERMS" | sort -u)
  if [ "$FPERMS" = "$EXPECTED" ]; then echo "PASS"; else echo "FAIL"; echo "fork: $FPERMS"; exit 1; fi
else
  grep -q 'android.permission.INTERNET' "$FORK_MAN" && echo "PASS (upstream copy absent, INTERNET present)"
fi
echo -n "remote listener still present on 47819: "
grep -q 'PORT = 47819' "$SRC/ForkRemoteProtocol.java" \
  && grep -q 'ForkRemoteProtocol.PORT' "$SRC/ForkRemoteServer.java" && echo "PASS"
echo -n "grid wrapper weight untouched (still weight=1): "
grep -q 'android:layout_weight="1"' "$FORK_DIR/app/src/main/res/layout/activity_main.xml" && echo "PASS"
echo "--- v3.22 folders + lock setup wiring pins (source checks) ---"
echo -n "folder defs applied in reloadTiles + foldered apps hidden in reload: "
grep -q 'adapter.setFolderDefs(loadFolders())' "$SRC/MainActivity.java" \
  && grep -q 'folderedPkgs.contains(pkg)' "$SRC/AppAdapter.java" && echo "PASS"
echo -n "folder tiles appended after apps + launch intercept to folder view: "
grep -q 'fresh.add(new Entry(f.id, f.name, folderIcon(f.members), caption))' "$SRC/AppAdapter.java" \
  && grep -q 'listener.onFolderRequested(e.folderId)' "$SRC/AppAdapter.java" && echo "PASS"
echo -n "tile menu: folder branch + Add to folder + Lock/Unlock app rows: "
grep -q 'adapter.entryKindAt(position) == AppAdapter.Entry.FOLDER' "$SRC/MainActivity.java" \
  && grep -q 'R.string.folder_add_to' "$SRC/MainActivity.java" \
  && grep -q 'R.string.lock_app' "$SRC/MainActivity.java" && echo "PASS"
echo -n "folder gate: locked+nocode sets pattern then PROMPTS (not setup-then-open): "
grep -q 'showUnlockGate(folder.name, seq, () -> showFolderDialog(folderId))' "$SRC/MainActivity.java" && echo "PASS"
echo -n "app gate unchanged: locked+nocode is setup-then-OPEN (legacy locks never lock out): "
grep -q 'ArrowDialog.showSetup(this, getString(R.string.lock_set_title, label)' "$SRC/MainActivity.java" \
  && grep -q 'forkSreebPrefs.setArrowPin(sequence);' "$SRC/MainActivity.java" \
  && grep -q 'onPass.run();' "$SRC/MainActivity.java" && echo "PASS"
echo -n "folder dialog: cancelable (BACK closes) + dismiss refocuses folder tile: "
grep -q 'd.setCancelable(true)' "$SRC/FolderDialog.java" \
  && grep -q 'adapter.positionOf("folder/folder:" + folderId)' "$SRC/MainActivity.java" && echo "PASS"
echo -n "Lock & PIN settings row wired (layout + activity + current-code-first): "
grep -q 'lockPinChoice' "$FORK_DIR/app/src/main/res/layout/activity_launcher_settings.xml" \
  && grep -q 'showLockPin()' "$SRC/LauncherSettingsActivity.java" \
  && grep -q 'showUnlockSeq(this, getString(R.string.lock_unlock_title' "$SRC/LauncherSettingsActivity.java" && echo "PASS"
echo -n "Sreeb prefs write surface is ONLY locked set + arrow code: "
grep -q 'putStringSet(k("locked")' "$SRC/SreebPrefs.java" \
  && ! grep -q 'putString(k("layout")\|putString(k("launcher_tmdb_key")' "$SRC/SreebPrefs.java" && echo "PASS"
echo -n "folder store is fork-owned (ForkState file, never mizan_launcher): "
grep -q 'setFoldersRaw' "$SRC/ForkState.java" \
  && grep -q 'FILE = "clean_fork_state"' "$SRC/ForkState.java" && echo "PASS"
echo -n "remote master flags still gate app + folder locks: "
grep -q 'remoteAppLockEnabled()' "$SRC/MainActivity.java" \
  && grep -q 'remoteFolderLockEnabled()' "$SRC/MainActivity.java" && echo "PASS"
echo "--- v3.22 Sreeb icon/banner pins (source checks) ---"
echo -n "adaptive icon + banner vectors present: "
[ -f "$FORK_DIR/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml" ] \
  && [ -f "$FORK_DIR/app/src/main/res/drawable/banner.xml" ] \
  && [ -f "$FORK_DIR/app/src/main/res/drawable/ic_launcher_foreground.xml" ] && echo "PASS"
echo -n "upstream wavy jpg + banner jpg gone, no references: "
[ ! -f "$FORK_DIR/app/src/main/res/mipmap-xhdpi/ic_launcher.jpg" ] \
  && [ ! -f "$FORK_DIR/app/src/main/res/drawable-nodpi/banner.jpg" ] \
  && ! grep -rq 'ic_launcher.jpg\|banner.jpg' "$FORK_DIR/app/src/main" && echo "PASS"
echo -n "manifest icon/banner refs unchanged, Live TV tile banner untouched: "
grep -q 'android:icon="@mipmap/ic_launcher"' "$FORK_DIR/app/src/main/AndroidManifest.xml" \
  && grep -q 'android:banner="@drawable/banner"' "$FORK_DIR/app/src/main/AndroidManifest.xml" \
  && [ -f "$FORK_DIR/app/src/main/res/drawable/banner_tv.xml" ] && echo "PASS"
