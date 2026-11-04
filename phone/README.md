# Lean TV Remote (phone)

The phone end of Lean TV: a D-pad, OK, Back, Home, volume, power and a line of text, sent to the TV as key
events. It is its own app and its own Gradle project, never built into the launcher.

## How it talks to the TV

Over the TV's own debug bridge (Adb, port 5555) on the home network. That choice is deliberate: the launcher has
no `INTERNET` permission and no listening socket by rule, so it cannot accept commands from a phone. Adb is
already on the TV, the phone is the client, and every press arrives as an ordinary `input keyevent`, which the
launcher handles exactly like its own remote.

| The phone needs | The TV needs |
|---|---|
| The TV's address on the home network | Network (or USB) debugging on, in Developer options |
| One tap on the TV's prompt, once | Nothing installed for this: `adb` ships with the TV |

The first connection shows the TV's **Allow debugging?** prompt naming this phone. Accept it and the key is
remembered, so the prompt does not come back.

## Build and install

JDK 17, Android SDK platform 34 and build-tools 34.0.0, the same as the launcher. This module keeps no wrapper of
its own: it builds with the launcher's, so the repo carries one Gradle version.

```bash
cd phone && ../launcher/gradlew --no-daemon assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

`testReleaseUnitTest` checks the Adb framing, the public key struct and the text escaping on a plain JVM.

## What it cannot do

- It is a remote, not a second launcher: apps open on the TV, not on the phone.
- A locked tile still asks for its arrow pattern on the TV. The phone sends arrows, so it can draw the pattern
  like any remote, and nothing here stores it.
- Debugging has to be on, which is also an open door: `addons/remote-access` exists to switch it back off once
  the TV is set up.
