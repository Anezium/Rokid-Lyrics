# Rokid Lyrics - Remaining Plan

## Current state

The phone app now uses a three-provider pipeline:

- `MUSIXMATCH` as the main authenticated provider
- `NETEASE` as the no-auth secondary provider
- `LRCLIB` as the public fallback

What is already done in `dev`:

- provider abstraction and composite lookup flow
- Musixmatch on-device login and synced line fetch
- Netease official search + lyric fetch on device
- LRCLIB fallback kept in place
- runtime dedup to stop repeated lookup spam during playback
- same-track refresh now keeps already loaded lyrics visible if a retry fails
- media-session selection is now player-agnostic and no longer prefers Spotify
- phone UI reflects `Musixmatch -> Netease -> LRCLIB`
- targeted unit tests added for runtime preservation and media-session priority
- debug APK rebuilt and reinstalled on the connected phone

Current product direction:

- zero backend
- line sync only
- users manage only Musixmatch auth
- Netease requires no user action

## Goal

Ship a stable phone app where:

1. one track change triggers one lyrics lookup
2. synced lyrics stay loaded while playback progresses
3. provider fallback is easy to understand from the UI
4. the Bluetooth / glasses flow stays unchanged

## Remaining work

### P0 - Real device validation

- validate `Musixmatch -> Netease -> LRCLIB` on a real mixed playlist
- capture one confirmed real example for:
  - `MUSIXMATCH`
  - `NETEASE`
  - `LRCLIB`
  - `NO SYNC`
- verify that the source pill and phone status match the actual provider outcome
- re-check the old Spotify-stays-open scenario against another active player

Exit criteria:

- we have real-device proof for all 4 outcomes
- provider shown in UI matches the provider that actually resolved the lyrics
- no stale Spotify session can hijack active playback selection

### P1 - Harden Netease

- improve candidate scoring for ambiguous tracks with more weight on:
  - duration
  - album
  - primary artist
- penalize noisy variants more aggressively:
  - remix
  - live
  - cover
  - instrumental
- make payload handling more explicit between:
  - `klyric`
  - `lrc`
  - legacy lyric payloads
- filter credit lines and recognize explicit instrumental / unavailable-lyrics cases
- expose clearer failure reasons from the provider layer:
  - no candidate
  - track matched but unsynced
  - empty payload
  - provider/network error

Exit criteria:

- fewer false matches on ambiguous tracks
- clearer provider summaries when Netease fails or returns no sync

### P1 - Improve Musixmatch reliability

- reduce false failures when `track.subtitle.get` returns `404`
- try another valid Musixmatch search candidate before giving up
- review session reuse and retry policy to avoid unnecessary captcha pressure
- add clearer status labels for:
  - auth failed
  - token expired
  - track found but no synced subtitle

Exit criteria:

- fewer false `NO SYNC` outcomes on Musixmatch-covered tracks
- auth/session problems are understandable from the phone UI

### P1 - Test expansion

Add or extend tests for:

- composite provider fallback order
- same-track refresh failure preservation
- no-sync result handling
- provider status text after multiple track changes
- Netease ambiguous candidate scoring
- Netease payload edge cases:
  - empty payload
  - instrumental
  - credit-heavy lyrics

Exit criteria:

- runtime and provider edge cases have regression coverage

### P2 - UI and docs polish

- keep source pill semantics limited to:
  - active provider
  - lookup in progress
  - no sync
- polish phone copy around provider state and fallback behavior
- optionally add a lightweight debug/status block for last provider-chain outcome
- update docs so they reflect the current provider stack and current UX

### P2 - Glasses verification

- validate that the glasses HUD behaves correctly with:
  - synced lines
  - no lines
  - provider switch
  - pause / resume
- confirm provider names render cleanly on device

## Known risks

### Technical risks

- Musixmatch may still rate limit or captcha aggressive retry patterns
- Netease is a reverse-engineered integration and may need maintenance if endpoints change

### Product risks

- some tracks will still have no sync even with two providers
- provider coverage may vary by region and catalog

### Maintenance risks

- reverse-engineered providers need periodic fixes
- real-world regression testing matters more than local unit tests here

## Recommended next order

### Step 1

Run real-device validation and collect 4 concrete outcomes:

- Musixmatch hit
- Netease hit
- LRCLIB hit
- no sync

### Step 2

Use those real examples to harden Netease first:

- ambiguous search matches
- empty or noisy payloads
- better status reasons

### Step 3

Tighten Musixmatch reliability:

- retry / candidate behavior
- auth/session status clarity

### Step 4

Do final UI, docs, and glasses verification once provider behavior is stable

## Non-goals for now

- no backend
- no word/syllable sync UI
- no Spotify provider reintroduction
- no Bluetooth protocol rewrite
- no glasses-side architecture change

## Success criteria

The feature is in a good state when:

- changing tracks does not spam provider APIs
- already loaded lyrics stay visible if a same-track refresh fails
- the current line stays active during playback
- the source pill matches the real provider outcome
- users only need Musixmatch credentials
- Netease works silently in the background
- failures degrade to `LRCLIB` or `NO SYNC` cleanly
