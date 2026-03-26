## Rokid Lyrics

<p align="center">
  <img src="Rokid_Lyrics_logo.png" alt="Rokid Lyrics logo" width="220" />
</p>

Bluetooth-only split product for synced lyrics on Rokid glasses.

Structure:
- `android-phone/`: dedicated Android phone runtime
- `android-glasses/`: dedicated Android glasses client
- `shared-contracts/`: shared Bluetooth wire protocol and lyrics contracts

Design:
- Phone monitors Android media sessions and fetches lyrics from LRCLIB
- Phone serves lyrics over Bluetooth Classic SPP
- Glasses connect over Bluetooth, receive one full lyrics snapshot, then light progress sync events
- Glasses render and advance lyrics locally

Build:
- `android-phone\\gradlew.bat assembleDebug`
- `android-glasses\\gradlew.bat assembleDebug`

GitHub release:
- Push a version tag, for example `git tag v0.1.0` then `git push origin v0.1.0`
- GitHub Actions will build both APKs and attach them to a GitHub Release automatically
- Published assets:
  - `lyrics-phone-debug.apk`
  - `lyrics-glasses-debug.apk`

Note:
- The automated release currently publishes debug-signed APKs
- If you want store-style signed release APKs, add a release signing config and keystore secrets first

First test flow:
- Install the phone APK on the Android phone and the glasses APK on the Rokid device
- Pair the phone and the glasses over Bluetooth at the OS level first
- Open the phone app and grant Bluetooth permission
- Open Android notification access settings from the phone app and enable the listener
- Start Spotify or another media app on the phone
- Open the glasses app and wait for the Bluetooth status to switch to connected
- Press Enter on the glasses if you want to force a lyrics refresh from the phone
