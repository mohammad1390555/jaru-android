# Once / یک زمانی

VPN scanner and cleaner for Android.

First open is in English. Launcher name follows the phone language: **Once** on English, **یک زمانی** otherwise.

It scans layer by layer, shows each app, checks it. Uncheck what you still need. The rest are removed one by one through the system uninstall screen.

APK is published from Releases. Every push to `main` cuts a new version.

## Install

1. [Releases](https://github.com/mohammad1390555/jaru-android/releases) → latest APK
2. Allow unknown sources
3. Install

Android 8+.

## Scan

1. Live tunnel (`tun` / `TRANSPORT_VPN`) and Always-on
2. Apps that expose `VpnService`
3. Services with `BIND_VPN_SERVICE`
4. Known clients (v2rayNG, Hiddify, Clash, WireGuard, WARP, …)
5. Name/label heuristic — low confidence, unchecked by default

System apps are listed, never selected. Android does not allow silent uninstall.

In-app language: first run English, toggle to فارسی.

## Build

JDK 17 + Android SDK 35.

```bash
./gradlew assembleRelease -PversionCode=1 -PversionName=1.0
```
