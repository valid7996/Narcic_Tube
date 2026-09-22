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
        maven("https://maven.aliyun.com/repository/google")
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
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "NarcicTub"
include(":app")
