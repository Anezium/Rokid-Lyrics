## Rokid Lyrics

<p align="center">
  <img src="Rokid_Lyrics_logo.png" alt="Rokid Lyrics logo" width="220" />
</p>

<p align="center">
  Synced lyrics on Rokid AR glasses, streamed live from your phone over Bluetooth.
</p>

---

## Screenshots

<p align="center">
  <img src="Screenshot_App.jpg" alt="Phone app — Phosphor Glass UI" width="300" />
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="Screenshot_Glasses.jpg" alt="Rokid Glasses — live lyrics display" width="300" />
</p>
<p align="center">
  <em>Phone companion app &nbsp;·&nbsp; Rokid Glasses live display</em>
</p>

---

## How it works

The phone app monitors whatever is playing on Android (Spotify, Apple Music, YouTube, etc.), fetches time-synced lyrics from [LRCLIB](https://lrclib.net), and streams them to the glasses over Bluetooth Classic SPP. The glasses receive a full lyrics snapshot on connect, then receive lightweight progress sync events as the song plays — so lyrics advance in real time on the AR display.

No internet required on the glasses side. No cloud relay. Everything runs locally over Bluetooth.

---

## Features

**Phone app**
- Detects active media sessions automatically (supports any Android media player)
- Prioritizes Spotify when multiple players are active
- Fetches time-synced LRC lyrics from LRCLIB (3-tier lookup: cached → exact → fuzzy search)
- Falls back to plain lyrics if no synced version is available
- Serves lyrics to multiple glasses clients simultaneously over Bluetooth SPP
- Debounced broadcast — only sends deltas to avoid flooding the BT link
- Drift correction: re-syncs playback position if glasses drift > 1.5 seconds
- Runs as a foreground service — stays alive when the screen is off
- **Phosphor Glass UI** — monochrome green HUD aesthetic mirroring the glasses display, with 4-line lyrics cascade, live progress bar, and animated BT status

**Glasses app**
- Connects to the phone over Bluetooth Classic
- Receives a full lyrics snapshot on connect, advances locally by timer
- Displays current + next lyric line on the AR overlay
- Press Enter (or D-pad center) to play/pause the music on the phone
- Minimal battery impact — no polling, event-driven updates only

---

## Project structure

```
android-phone/       Android phone runtime (media monitor + BT server + lyrics engine)
android-glasses/     Android glasses client (BT client + HUD renderer)
shared-contracts/    Shared Bluetooth wire protocol and lyrics data contracts
design/              UI mockups and design references
```

---

## Build

```bash
# Phone APK
android-phone/gradlew.bat assembleDebug

# Glasses APK
android-glasses/gradlew.bat assembleDebug
```

---

## First run

1. Install `lyrics-phone-debug.apk` on the Android phone and `lyrics-glasses-debug.apk` on the Rokid device
2. Pair the phone and the glasses over Bluetooth at the OS level first
3. Open the phone app — grant Bluetooth and notification permissions
4. Tap **[ NOTIF ACCESS ]** and enable the notification listener for Rokid Lyrics
5. Start playing music on the phone (Spotify, YouTube, etc.)
6. Open the glasses app and wait for the status to show **CONNECTED**
7. Lyrics will appear on the glasses display within a few seconds
8. Press Enter on the glasses to force-refresh lyrics at any time
