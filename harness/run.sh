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
