# WearMouse — tap-gesture fork

A Wear OS **air mouse**: your watch becomes a Bluetooth HID mouse and keyboard for any
computer or AR glasses (tested on the INMO Air 3), with no extra software on the host.
This is a fork of [ginkage/WearMouse](https://github.com/ginkage/wearmouse) that adds
hands-light IMU gesture control on top of the original air mouse.

> ⚠️ **Alpha/beta.** The gesture features below are new and were tuned on one person's
> wrist and watch. They ship **off by default** and are marked *(beta)* in
> Settings → Input. Turn them on one at a time, and please
> [file an issue](https://github.com/dharmabruce/wearmouse/issues) if they misfire on
> your hardware. Everything the upstream app already did still works unchanged.

## What this fork adds

**Opt-in IMU gestures** (Settings → Input, off by default):

* **Tap-to-click & grab-to-scroll** *(beta)* — pinch your fingers to click, double-tap to
  double-click, tap-and-hold to drag, and pinch-and-move to scroll with macOS-style
  release inertia. No touching the watch face to click.
* **Wrist-flip Back / Home** *(beta)* — flip your wrist over and back to send Android
  Back; hold it flipped for about a second for Home. Haptic feedback confirms each
  gesture.

**Always available:**

* **Back / Home / Pause in the action drawer** — swipe up on the mouse screen for entries
  that send Android Back/Home over HID (Consumer Control `AC Back`/`AC Home`), plus a
  one-tap **Pause** that freezes all pointer motion, scrolling and clicks while you type
  on the host, then resumes instantly. The Bluetooth link stays up, so there is no
  reconnect delay (unlike disconnecting).
* **Per-device scroll direction** — the "Reverse scroll direction" setting is remembered
  per connected host, so the glasses and a laptop can each keep the direction that feels
  right; the global switch is the default new devices inherit.

## Installing

This fork uses its own application id — `com.ginkage.wearmouse.tap` — so it installs
**alongside** the original Play Store WearMouse without conflicts (and does not receive
its updates). Download an APK from the
[Releases page](https://github.com/dharmabruce/wearmouse/releases) and sideload it to the
watch with `adb install`, or build from source (see below). It is not on the Play Store.

## Credits & license

Based on [ginkage/WearMouse](https://github.com/ginkage/wearmouse), licensed under the
Apache License 2.0 (see [LICENSE](LICENSE)); modifications are noted in the changed files.
Not affiliated with, or endorsed by, Google or the original author. If the upstream app is
useful to you, consider supporting its author:
[Buy Me a Coffee](https://www.buymeacoffee.com/ginkage) ·
[PayPal](https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=LF9S5WAF6E4VA).

## Compatibility

This app is only compatible with Wear OS devices running Android P and above.
You can use it to connect with pretty much any laptop or desktop computer,
running Windows, Linux, Chrome OS, Mac OSX, Android TV, without any additional
software, as long as it has a Bluetooth receiver.

## How to use this app

1. After launch, the first thing you see is the paired devices list.
    * You probably want to pair a laptop or a desktop computer if it is the
       first time you've launched the app.
1. If you tap on "Available devices" option, you'll see nearby devices that
   you can try pairing with.
    * It's a good idea to try pairing with a laptop or a desktop computer.
    * At this screen, the Wear OS device is also discoverable for the nearby
       devices, so you can try searching for it on the other device as well.
1. When you have a paired device, tapping on it will give you an option to
   connect to it. This will bring up the Input Mode dialog.
    * Sometimes this dialog pops up immediately after pairing, saving you a few
       taps.
1. You can now choose between Mouse (the air mouse), Cursor Keys and Keyboard
   Input modes, and also can change a few settings.
    * Every mode (except for the keyboard input) has a welcome screen that
       describes the way to use it.
       
## Navigating the source code

The main sections of the code tree are:

1. /bluetooth
    * Everything related to the HID Device emulation, like report descriptor,
       app configuration, and everything else that uses the new Bluetooth HID
       Device API.
1. /input
    * Handy utilities for sending actual input events, e.g. converting
       characters of an en-US keyboard to scan codes, or converting Rotation
       Vector sensor events to mouse pointer movements.
1. /sensors
    * Implements orientation tracking using Google VR library. The GVR-based
       approach produces results that are a drop-in replacement for the 
       Rotation Vector sensor, but doesn't rely on the watch manufacturer's
       implementation of that sensor.
1. /ui
    * The user interface


[![alt text](https://play.google.com/intl/en_gb/badges/images/generic/en_badge_web_generic.png "Get it on Google Play")](https://play.google.com/store/apps/details?id=com.ginkage.wearmouse)
