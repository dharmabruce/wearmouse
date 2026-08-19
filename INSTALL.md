# Installing WearMouse Tap on your watch

WearMouse Tap is not (yet) on the Play Store, so it has to be **sideloaded** onto the
watch with `adb` over Wi-Fi. No root, no unlocking — just Android's built-in developer
mode. Budget 10–20 minutes the first time; the fiddly part is keeping the wireless
debugging connection alive long enough to install.

## What you need

* A **Wear OS 5 (Android 14) or newer** watch. Check on the watch under
  **Settings → About watch** (Galaxy) or **Settings → System → About → Versions**
  (Pixel and most others). The gesture features also need a **gyroscope**; on watches
  without one, taps/flips simply stay unavailable while the regular air mouse still works.
* A computer (Windows/macOS/Linux) on the **same Wi-Fi network** as the watch.
* One APK from the [latest release](https://github.com/dharmabruce/wearmouse/releases):
  * `wearmouse-tap-1.27-tap1-universal.apk` — **use this one if unsure** (works on every watch)
  * `wearmouse-tap-1.27-tap1-arm64-v8a.apk` / `...-armeabi-v7a.apk` — smaller, if you
    know your watch's CPU type

## Step 1 — Install adb on the computer

`adb` is part of Google's free platform-tools:
[developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools).
Download, unzip, and run the commands below from inside that folder (on Windows use
`adb.exe`; on macOS/Linux `./adb`). If you have Android Studio or Homebrew
(`brew install android-platform-tools`), you may already have it — try `adb version`.

## Step 2 — Enable developer mode on the watch

1. On the watch, open **Settings → About watch → Software info** (Galaxy) or
   **Settings → System → About → Versions** (Pixel).
2. Tap **Software version** / **Build number** repeatedly (about 7 times) until it says
   you're a developer.
3. A **Developer options** menu appears in Settings.

## Step 3 — Get the watch on Wi-Fi and start wireless debugging

1. Make sure the watch is actually on Wi-Fi: **Settings → Connections → Wi-Fi**, pick
   your network. Tip: set Wi-Fi to **Always on** for the duration of the install —
   many watches silently drop Wi-Fi to save power (especially while Bluetooth-connected
   to a phone), which kills the adb connection. You can set it back afterwards.
2. In **Developer options**, turn on **ADB debugging**, then **Wireless debugging**
   (accept the prompt for your network).
3. Inside **Wireless debugging**, tap **Pair new device**. The watch shows a
   **pairing code** and an address like `192.168.1.42:41231`.

## Step 4 — Pair, connect, install

On the computer (replace addresses/codes with what *your* watch shows):

```
adb pair 192.168.1.42:41231
```

Enter the pairing code when asked. Then go **back one screen** on the watch to the main
Wireless debugging page — it shows an **IP address & port** that is *different* from the
pairing one. Connect to that:

```
adb connect 192.168.1.42:40551
adb devices
```

`adb devices` should list the watch as `device`. Now install:

```
adb install -r wearmouse-tap-1.27-tap1-universal.apk
```

When it prints `Success`, you're done — WearMouse Tap appears in the watch's app list.
It installs **alongside** the original Play Store WearMouse (different app id), so you
don't need to uninstall anything.

## Troubleshooting

* **The port keeps changing.** Every time wireless debugging turns off and on, the
  connect port rotates. Re-open the Wireless debugging screen, read the new port, and
  `adb connect` again. (Pairing usually survives; you rarely need to re-`pair`.)
* **The connection drops mid-install / watch shows `offline`.** Common. Keep the watch
  screen awake by tapping it while the install runs, then just re-run the
  `adb connect` + `adb install` commands — it usually works on the second try.
* **Wireless debugging keeps switching itself off.** A known Wear OS quirk, often
  triggered by Wi-Fi dozing or by putting the watch on/off the charger. Charger behavior
  genuinely differs by model: some watches only hold Wi-Fi *while* charging, others drop
  wireless debugging *when docked*. If your connection is flaky, try the opposite charger
  state, and toggle Wireless debugging back on (then reconnect — new port).
* **`INSTALL_FAILED_NO_MATCHING_ABIS`.** You grabbed a CPU-specific APK that doesn't
  match your watch — use the `universal` one.
* **Watch won't join Wi-Fi at all.** Some watches refuse Wi-Fi while Bluetooth-tethered
  to a phone. Temporarily turn off Bluetooth on the phone (or the phone connection on
  the watch), install, then re-enable.

After installing, you can turn **Wireless debugging**, **ADB debugging**, and Wi-Fi
**Always on** back off — they're only needed for installs and updates.

## Connecting to the INMO Air3 (or any other host)

1. Open **WearMouse Tap** on the watch and pick a mode (Air mouse / Touchpad / Keyboard).
2. On the glasses (or laptop/TV): open **Bluetooth settings** and pair with your watch.
3. That's it — the watch acts as a standard Bluetooth mouse/keyboard; the host needs no
   extra software. See the [README](README.md) for the gesture controls.

Known quirks: the Air3 pairs fine; some *phones* refuse the mouse connection when asked
to be the host (a companion phone can decline the HID role) — that's a host-side
limitation, not something the app can fix.

## Updating / uninstalling

* **Update:** download the new APK and `adb install -r` it (same steps as above).
  Sideloaded apps do not auto-update.
* **Uninstall:** hold the app icon in the watch launcher → uninstall, or
  `adb uninstall com.ginkage.wearmouse.tap`.
