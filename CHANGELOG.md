# Changelog

All notable changes to this project are documented in this file.

## [0.2.1] - 2026-03-27

### Fixed
- Added an explicit Bluetooth protocol handshake between the phone and the glasses before any status, snapshot, or playback control message is accepted.
- Rejected incompatible protocol versions and invalid wire messages with clearer disconnect reasons and reconnect guidance on both apps.
- Improved Bluetooth session lifecycle handling with handshake timeouts, safer disconnect cleanup, and client counting based on fully negotiated links.
- Made the glasses HUD surface runtime errors before empty-state copy so connection or protocol issues are visible immediately.
- Updated the phone playback badge to reflect the real lyrics session state instead of inferring playback from track metadata alone.
- Reworked LrcLib search result selection to score title, artist, album, synced lyrics availability, and duration instead of taking the first synced match.
- Added explicit network timeouts to the lyrics client to avoid stalled requests.

### Changed
- Added wire protocol and lyrics lookup unit tests to cover handshake round-trips, protocol version mismatches, and candidate selection edge cases.
- Added release signing support for both Android apps with local fallback signing for `assembleRelease` and CI enforcement for real signing secrets.
- Switched the GitHub release workflow to run unit tests, build release APKs, and publish signed release artifacts from `v*` tags.
- Documented release signing inputs in the README and ignored local keystore material in Git.
