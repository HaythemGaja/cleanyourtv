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
    "$SRC/MiniJson.java" \
    "$SRC/SreebCodecs.java" \
    "$SRC/ArrowLock.java" \
    "$SRC/ForkRemoteProtocol.java" \
    "$SRC/TrackerStore.java" \
    "$SRC/TrackerLayout.java" \
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
grep -q '47819' "$SRC/ForkRemoteServer.java" && echo "PASS"
echo -n "grid wrapper weight untouched (still weight=1): "
grep -q 'android:layout_weight="1"' "$FORK_DIR/app/src/main/res/layout/activity_main.xml" && echo "PASS"
