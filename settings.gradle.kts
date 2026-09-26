pluginManagement {
    repositories {
        // PHASE 15 (CI): canonical repositories FIRST so a clean GitHub
        // Actions runner resolves AGP/androidx from google()/Central, never
        // from a third-party mirror. Order: google() → Central → Portal.
        google()
        mavenCentral()
        gradlePluginPortal()
        // Local-network fallback ONLY: dl.google.com is unreachable on this
        // machine (404 on every artifact), so the Aliyun google mirror stays
        // as the last resort. CI never reaches it.
        maven("https://maven.aliyun.com/repository/google") {
            // فول‌بک محلی: متادیتای Gradle میرور، نام‌فایل واریانس (مثل
            // ui-graphics-release.aar) را می‌خواهد که خود میرور سرو نمی‌کند؛
            // فقط-POM این را دور می‌زند (نام‌فایل استاندارد aar سرو می‌شود).
            metadataSources { mavenPom(); artifact() }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // PHASE 15 (CI): canonical first (see pluginManagement note) —
        // androidx lives on google(); Central covers the rest. The Aliyun
        // mirrors remain the local-network fallback only.
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google") {
            metadataSources { mavenPom(); artifact() }
        }
        maven("https://maven.aliyun.com/repository/public") {
            metadataSources { mavenPom(); artifact() }
        }
    }
}

rootProject.name = "NarcicTub"
include(":app")
