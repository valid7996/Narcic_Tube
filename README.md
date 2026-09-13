# NarcicTub

An Android media download manager. Kotlin, Jetpack Compose, Material 3, Clean Architecture, MVVM.

## Status

Phase 1 — Project foundation (complete).

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
