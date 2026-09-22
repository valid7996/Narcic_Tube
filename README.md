# NarcicTub

An Android media download manager. Kotlin, Jetpack Compose, Material 3, Clean Architecture, MVVM.

## Status

YouTube and Instagram downloading is implemented on top of
[youtubedl-android](https://github.com/yausername/youtubedl-android) (yt-dlp + Python + ffmpeg bundled).
Direct media links keep using the original HTTP downloader.

### How it works

- `data/ytdlp/YtDlpExtractor` resolves YouTube/Instagram links (`yt-dlp --dump-single-json`) into
  quality variants (`YtDlpInfoParser`). The chosen quality travels in the URL fragment `#nt-f=<format ids>`.
- `data/downloader/RoutingFileDownloader` sends those URLs to `YtDlpFileDownloader`; other URLs go to
  `HttpUrlConnectionDownloader`. Publishing to MediaStore/history is unchanged.
- `YtDlpEngine` is the only class touching the library. It initializes at app start and tries to update
  yt-dlp (needed because YouTube changes often; set `AUTO_UPDATE_ON_LAUNCH = false` to disable).

### Known limitations

- **YouTube needs a JavaScript runtime** for full format support (yt-dlp >= 2025.11.12). Without one, some
  formats are missing and downloads can fail with HTTP 403. Optional fix: put an Android build of Deno at
  `app/src/main/jniLibs/arm64-v8a/libdeno.so` (~88 MB); the engine then passes `--js-runtimes deno:<path>`.
- **Instagram** often requires a login. Import a browser `cookies.txt` in Settings.
- Only progressive/merged single items are downloaded (no playlists).

### Smart link handling (no manual "Resolve" tap, no need to open the app)

- **Auto-resolve.** Typing or pasting a valid link resolves it automatically
  (short debounce after you stop typing/pasting) — `Resolve` is now only a
  manual retry button.
- **In-app clipboard suggestion.** Whenever the Home screen comes to the
  foreground, a copied supported link is offered as a one-tap "Download it?"
  card. Dismissible, never auto-fills on its own, and only reads the
  clipboard while the app is genuinely in the foreground.
- **Floating download bubble.** Tapping Share on YouTube/Instagram (or any
  app) no longer has to open NarcicTub: `ShareTrampolineActivity` is now the
  real Share target, and — if "display over other apps" is granted — it
  opens a small draggable bubble (`OverlayBubbleService`) over whatever app
  you were using. Tap it to see quality choices and hit Download; the bubble
  closes itself a moment later and the existing download notification takes
  it from there. Without that permission it falls back to the previous
  behavior: opening the app with the link pre-filled.
- **Clipboard watcher (optional, Settings → "Floating download bubble").**
  Off by default. When enabled, a foreground service also opens the bubble
  for a newly copied link, without any Share action at all.
  **Real limitation, not a bug:** since Android 10, an app that isn't the
  foreground/focused app generally cannot read the clipboard. The listener
  still runs, but on many devices/Android versions it simply won't fire
  while NarcicTub is fully backgrounded — there's no app-level fix for this
  short of the much heavier AccessibilityService permission, which this app
  deliberately does not request. Sharing (always reliable) and the in-app
  suggestion (reliable whenever the app is open) are the dependable paths;
  this toggle is a best-effort bonus on top of them.

### Where files are saved

Every download lands inside its own app-named subfolder — never loose in
the root of a shared collection:

- Video → the system **Movies** collection, at `Movies/NarcicTub/` — shows
  up in the phone's own Gallery/Video apps like any other video.
- Audio-only → the system **Music** collection, at `Music/NarcicTub/`.
- Anything else (documents, images, unknown types) → whichever location is
  chosen in Settings (defaults to Downloads), at e.g. `Download/NarcicTub/`.

This routing is automatic and based on the real resolved/observed media
type (`DownloadLocationPolicy`) — the manual Settings location only governs
the "anything else" case. On API 26–28 (no shared MediaStore write access)
the same subfolder concept applies inside the app's own external-files
directory instead.

### Pause / resume

Active and queued downloads can be paused (⏸) and resumed (▶) from the
Downloads screen, in addition to cancel. Pausing keeps whatever bytes have
already been transferred:

- **Direct links** resume with a real HTTP Range request, appending only
  the missing remainder.
- **YouTube/Instagram (yt-dlp)** resume by leaving yt-dlp's own partial
  files in place and letting yt-dlp continue them itself (its default
  `--continue` behavior) — occasionally this means redoing the final
  merge step rather than a byte-exact resume, but it never re-downloads
  from zero.

Cancelling a paused item discards the partial data instead.

### Licensing

youtubedl-android and yt-dlp are GPL-3.0 / Unlicense-family components; distributing the APK means
your app must comply with the GPL. Download only content you have the right to save.

## Build

Requires JDK 17 and Android SDK (compileSdk 34, minSdk 26).

```bash
./gradlew test
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Structure

```
app/src/main/java/com/narcictub/app/
├── MainActivity.kt          # Single-activity Compose host
├── NarcicTubApplication.kt # @HiltAndroidApp
└── ui/theme/               # Design system (dark-first M3)
```

## Toolchain note

`dl.google.com` is blocked on the dev machine; `settings.gradle.kts` uses
mavenCentral + Aliyun mirrors of Google Maven. On other machines the plain
`google()`/`mavenCentral()` order also works since both are listed as fallback.
