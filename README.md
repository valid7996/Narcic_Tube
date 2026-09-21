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
